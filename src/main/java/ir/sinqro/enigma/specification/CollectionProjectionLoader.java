package ir.sinqro.enigma.specification;

import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.specification.CollectionProjectionPlan.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.*;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.SingularAttribute;
import org.springframework.core.convert.ConversionException;
import org.springframework.core.convert.ConversionService;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ClassUtils;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Loads the collections declared with {@code @ProjectedCollection} for a page of parent rows.
 *
 * <h3>Strategy</h3>
 * One query per collection per chunk of parent ids ({@code WHERE owner.id IN (...)}), never one
 * per parent row. Nested collections recurse using the element ids of the previous level.
 *
 * <h3>Concurrency</h3>
 * <ul>
 *   <li>The bean is stateless apart from {@code idAttributeCache}, a {@link ConcurrentHashMap}
 *       of immutable values (published with {@code putIfAbsent}; no recursive compute).</li>
 *   <li>All per-call state ({@link JoinContext}, criteria objects, {@link RowBudget}, result
 *       maps) is local to the calling thread and never shared.</li>
 *   <li>The {@link EntityManager} is the Spring shared proxy, bound to the caller's transaction.
 *       Queries run sequentially on the caller's thread - deliberately no parallel streams or
 *       executors: a persistence context and its JDBC connection must not be used concurrently.</li>
 *   <li>Returned containers are unmodifiable and safe to publish to other threads.</li>
 * </ul>
 * Child queries are separate statements in the caller's transaction; under READ COMMITTED a
 * concurrent commit may become visible between the parent page and its collections. Use a
 * snapshot isolation level where strict point-in-time consistency is required.
 */
public class CollectionProjectionLoader {

    private static final int MAX_ROWS_UPPER_BOUND = 1_000_000;

    private final EntityManager em;
    private final ConversionService conversionService;
    private final DtoMapperHelper dtoMapperHelper;
    private final int maxJoins;
    private final int batchSize;
    private final int maxRows;

    private final ConcurrentMap<Class<?>, Optional<String>> idAttributeCache = new ConcurrentHashMap<>();

    public CollectionProjectionLoader(EntityManager em,
                                      ConversionService conversionService,
                                      DtoMapperHelper dtoMapperHelper,
                                      int maxJoins,
                                      int batchSize,
                                      int maxRows) {
        this.em = Objects.requireNonNull(em, "em");
        this.conversionService = Objects.requireNonNull(conversionService, "conversionService");
        this.dtoMapperHelper = Objects.requireNonNull(dtoMapperHelper, "dtoMapperHelper");
        if (maxJoins <= 0) {
            throw new IllegalArgumentException("maxJoins must be > 0");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("collectionBatchSize must be > 0");
        }
        if (maxRows <= 0 || maxRows > MAX_ROWS_UPPER_BOUND) {
            throw new IllegalArgumentException("maxCollectionRows must be in 1.." + MAX_ROWS_UPPER_BOUND);
        }
        this.maxJoins = maxJoins;
        this.batchSize = batchSize;
        this.maxRows = maxRows;
    }

    // ------------------------------------------------------------------ public API

    /** Name of the single identifier attribute of {@code entityClass}; fails for composite ids. */
    public String requireIdAttribute(Class<?> entityClass) {
        return idAttributeOf(entityClass).orElseThrow(() -> new QueryRuleException(
                entityClass.getSimpleName() + " is not an entity; cannot project collections from it"));
    }

    /**
     * Loads every collection of {@code plan} for the given owners.
     *
     * @return ownerId to (dtoField to container). Owners without elements are absent; use
     * {@link #valuesFor} to get empty containers for them.
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Map<Object, Map<String, Object>> load(CollectionProjectionPlan plan,
                                                 Class<?> ownerEntity,
                                                 Collection<?> ownerIds) {
        if (!plan.hasCollections() || ownerIds.isEmpty()) {
            return Map.of();
        }
        RowBudget budget = new RowBudget(maxRows);
        Map<Object, Map<String, Object>> result = new HashMap<>();
        for (CollectionPlan cp : plan.collections()) {
            Map<Object, Object> perOwner = fetch(cp, ownerEntity, ownerIds, budget);
            for (Map.Entry<Object, Object> e : perOwner.entrySet()) {
                result.computeIfAbsent(e.getKey(), k -> new HashMap<>()).put(cp.dtoField(), e.getValue());
            }
        }
        return result;
    }

    /** DTO-field overrides for one owner, with empty containers for collections it has none of. */
    public Map<String, Object> valuesFor(CollectionProjectionPlan plan,
                                         Map<Object, Map<String, Object>> loaded,
                                         Object ownerId) {
        Map<String, Object> mine = loaded.getOrDefault(ownerId, Map.of());
        Map<String, Object> values = new HashMap<>();
        for (CollectionPlan cp : plan.collections()) {
            values.put(cp.dtoField(), mine.getOrDefault(cp.dtoField(), emptyContainer(cp.container())));
        }
        return values;
    }

    // ------------------------------------------------------------------ querying

    private Map<Object, Object> fetch(CollectionPlan plan, Class<?> ownerEntity,
                                      Collection<?> ownerIds, RowBudget budget) {
        if (ownerIds.isEmpty()) {
            return Map.of();
        }
        String ownerIdAttr = requireIdAttribute(ownerEntity);
        List<Object> distinctIds = new ArrayList<>(new LinkedHashSet<>(ownerIds));

        Map<Object, List<Object>> collected = new LinkedHashMap<>();
        for (int from = 0; from < distinctIds.size(); from += batchSize) {
            List<Object> chunk = distinctIds.subList(from, Math.min(from + batchSize, distinctIds.size()));
            fetchChunk(plan, ownerEntity, ownerIdAttr, chunk, budget, collected);
        }

        Map<Object, Object> result = new LinkedHashMap<>();
        for (Map.Entry<Object, List<Object>> e : collected.entrySet()) {
            result.put(e.getKey(), toContainer(plan.container(), e.getValue()));
        }
        return result;
    }

    private void fetchChunk(CollectionPlan plan, Class<?> ownerEntity, String ownerIdAttr,
                            List<Object> ownerIds, RowBudget budget,
                            Map<Object, List<Object>> out) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<?> root = cq.from(ownerEntity);
        Path<Object> ownerIdPath = root.get(ownerIdAttr);
        JoinContext pathCtx = new JoinContext(maxJoins);

        List<Selection<?>> selections = new ArrayList<>();
        List<Order> orders = new ArrayList<>();
        selections.add(ownerIdPath);
        orders.add(cb.asc(ownerIdPath));

        // ---- simple values -------------------------------------------------------------
        if (plan.element() instanceof ScalarElement scalar) {
            Expression<?> value = resolveLeaf(pathCtx, root, plan.path());
            selections.add(value);
            orders.add(cb.asc(value));
            for (Tuple row : execute(cq, root, ownerIdPath, ownerIds, selections, orders, budget, plan)) {
                Object converted = coerce(row.get(1), scalar.type(), plan.dtoField());
                if (converted != null) {
                    out.computeIfAbsent(row.get(0), k -> new ArrayList<>()).add(converted);
                }
            }
            return;
        }

        // ---- DTO elements --------------------------------------------------------------
        DtoElement element = (DtoElement) plan.element();
        From<?, ?> el = pathCtx.resolveFrom(root, plan.path(), JoinType.INNER);
        requireCollectionAttribute(pathCtx, root, plan);

        Class<?> elementType = el.getJavaType();
        Optional<String> elementIdAttr = idAttributeOf(elementType);
        if (!element.nested().isEmpty() && elementIdAttr.isEmpty()) {
            throw new QueryRuleException("Nested @ProjectedCollection under '" + plan.dtoField()
                    + "' requires the element type " + elementType.getSimpleName() + " to be an entity");
        }

        JoinContext elCtx = new JoinContext(maxJoins);
        if (!plan.orderBy().isBlank()) {
            orders.add(cb.asc(resolveRelative(elCtx, el, plan.orderBy(), plan)));
        }
        int idColumn = -1;
        if (elementIdAttr.isPresent()) {
            Path<Object> elementId = el.get(elementIdAttr.get());
            idColumn = selections.size();
            selections.add(elementId);
            orders.add(cb.asc(elementId));
        }
        int firstScalarColumn = selections.size();
        for (ScalarField sf : element.scalars()) {
            selections.add(resolveRelative(elCtx, el, sf.path(), plan));
        }
        if (elCtx.hasCollectionJoin()) {
            throw new QueryRuleException("Field paths of collection '" + plan.dtoField()
                    + "' must not traverse another collection; declare it as a nested @ProjectedCollection");
        }

        List<Tuple> rows = execute(cq, root, ownerIdPath, ownerIds, selections, orders, budget, plan);

        // Nested collections: one batched fetch per nested collection for all elements of this chunk.
        List<Map<Object, Object>> nestedResults = new ArrayList<>(element.nested().size());
        if (!element.nested().isEmpty()) {
            Set<Object> elementIds = new LinkedHashSet<>();
            for (Tuple row : rows) {
                elementIds.add(row.get(idColumn));
            }
            for (CollectionPlan nested : element.nested()) {
                nestedResults.add(fetch(nested, elementType, elementIds, budget));
            }
        }

        for (Tuple row : rows) {
            Map<String, Object> values = new HashMap<>();
            for (int i = 0; i < element.scalars().size(); i++) {
                ScalarField sf = element.scalars().get(i);
                values.put(sf.dtoField(), coerce(row.get(firstScalarColumn + i), sf.type(),
                        plan.dtoField() + "." + sf.dtoField()));
            }
            Object elementId = idColumn >= 0 ? row.get(idColumn) : null;
            for (int i = 0; i < element.nested().size(); i++) {
                CollectionPlan nested = element.nested().get(i);
                values.put(nested.dtoField(),
                        nestedResults.get(i).getOrDefault(elementId, emptyContainer(nested.container())));
            }
            out.computeIfAbsent(row.get(0), k -> new ArrayList<>())
                    .add(dtoMapperHelper.instantiate(element.dtoClass(), values));
        }
    }

    private List<Tuple> execute(CriteriaQuery<Tuple> cq, Root<?> root, Path<Object> ownerIdPath,
                                List<Object> ownerIds, List<Selection<?>> selections,
                                List<Order> orders, RowBudget budget, CollectionPlan plan) {
        cq.multiselect(selections);
        cq.where(ownerIdPath.in(ownerIds));
        cq.orderBy(orders);
        TypedQuery<Tuple> query = em.createQuery(cq);
        query.setMaxResults(budget.fetchLimit()); // remaining + 1, to detect overflow without reading it all
        List<Tuple> rows = query.getResultList();
        budget.consume(rows.size(), plan.dtoField());
        return rows;
    }

    // ------------------------------------------------------------------ path resolution

    /** Last segment is joined (and selected as the join itself) if it is a collection of basics. */
    private Expression<?> resolveLeaf(JoinContext ctx, From<?, ?> root, String path) {
        int dot = path.lastIndexOf('.');
        From<?, ?> parent = dot < 0 ? root : ctx.resolveFrom(root, path.substring(0, dot), JoinType.INNER);
        String last = path.substring(dot + 1);
        if (attribute(parent.getJavaType(), last).isCollection()) {
            return ctx.resolveFrom(root, path, JoinType.INNER);
        }
        return parent.get(last);
    }

    private void requireCollectionAttribute(JoinContext ctx, From<?, ?> root, CollectionPlan plan) {
        String path = plan.path();
        int dot = path.lastIndexOf('.');
        From<?, ?> parent = dot < 0 ? root : ctx.resolveFrom(root, path.substring(0, dot), JoinType.INNER);
        if (!attribute(parent.getJavaType(), path.substring(dot + 1)).isCollection()) {
            throw new QueryRuleException("'" + path + "' (field '" + plan.dtoField()
                    + "') is not a to-many association or element collection");
        }
    }

    private Path<?> resolveRelative(JoinContext elCtx, From<?, ?> el, String path, CollectionPlan plan) {
        try {
            return elCtx.resolve(el, path);
        } catch (IllegalArgumentException ex) {
            throw new QueryRuleException("Invalid path '" + path + "' in collection '"
                    + plan.dtoField() + "': " + ex.getMessage(), ex);
        }
    }

    private Attribute<?, ?> attribute(Class<?> type, String name) {
        try {
            return em.getMetamodel().managedType(type).getAttribute(name);
        } catch (IllegalArgumentException ex) {
            throw new QueryRuleException("Unknown attribute '" + name + "' on " + type.getSimpleName(), ex);
        }
    }

    private Optional<String> idAttributeOf(Class<?> type) {
        Optional<String> cached = idAttributeCache.get(type);
        if (cached != null) {
            return cached;
        }
        Optional<String> resolved = resolveIdAttribute(type);
        Optional<String> previous = idAttributeCache.putIfAbsent(type, resolved);
        return previous != null ? previous : resolved;
    }

    private Optional<String> resolveIdAttribute(Class<?> type) {
        EntityType<?> entityType;
        try {
            entityType = em.getMetamodel().entity(type);
        } catch (IllegalArgumentException notAnEntity) {
            return Optional.empty(); // embeddable / basic element type
        }
        if (!entityType.hasSingleIdAttribute()) {
            throw new QueryRuleException("Composite identifiers are not supported for collection projection: "
                    + type.getSimpleName());
        }
        for (SingularAttribute<?, ?> attribute : entityType.getSingularAttributes()) {
            if (attribute.isId()) {
                return Optional.of(attribute.getName());
            }
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------ values

    private Object coerce(Object value, Class<?> target, String where) {
        if (value == null || ClassUtils.isAssignableValue(target, value)) {
            return value;
        }
        try {
            return conversionService.convert(value, target);
        } catch (ConversionException ex) {
            throw new QueryRuleException("Cannot convert " + value.getClass().getSimpleName() + " to "
                    + target.getSimpleName() + " for '" + where + "'", ex);
        }
    }

    public Object emptyContainer(ContainerKind kind) {
        return kind == ContainerKind.SET ? Set.of() : List.of();
    }

    private static Object toContainer(ContainerKind kind, List<Object> values) {
        return kind == ContainerKind.SET
                ? Collections.unmodifiableSet(new LinkedHashSet<>(values))
                : List.copyOf(values);
    }

    /** Per-request, thread-confined counter of child rows read. */
    private static final class RowBudget {
        private final int max;
        private int used;

        RowBudget(int max) {
            this.max = max;
        }

        int fetchLimit() {
            return (int) Math.min(Integer.MAX_VALUE, (long) (max - used) + 1);
        }

        void consume(int rows, String field) {
            used += rows;
            if (used > max) {
                throw new QueryRuleException("Collection '" + field + "' exceeds the maximum of " + max
                        + " child rows per request (app.specification.spec.max-collection-rows). "
                        + "Request a smaller page or narrow the filter.");
            }
        }
    }
}
