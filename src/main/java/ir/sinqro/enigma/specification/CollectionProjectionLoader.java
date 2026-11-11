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

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Loads the collections declared with {@code @ProjectedCollection} for a page of parent rows.
 *
 * <h3>Strategy</h3>
 * One query per collection per chunk of parent ids ({@code WHERE owner.id IN (...)}), never one
 * per parent row. Nested collections are loaded breadth-first using the element ids of the
 * previous level. Self-referencing / mutually recursive DTOs (see {@link CollectionProjectionPlanner})
 * are loaded one query per tree level, each (collection, owner) at most once per request, so
 * loading terminates even on cyclic data. Traversal, cycle handling and limits live in
 * {@link CollectionGraphAssembler}; this class only builds and runs the JPA queries.
 *
 * <h3>Limits (fail closed)</h3>
 * {@code maxRows} child rows read, {@code maxDepth} nested levels holding elements and
 * {@code maxElements} element DTOs produced, per request.
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
    private final int maxDepth;
    private final int maxElements;

    public static final int DEFAULT_MAX_DEPTH = 32;
    public static final int DEFAULT_MAX_ELEMENTS = 50_000;
    private static final int MAX_DEPTH_UPPER_BOUND = 1_000;
    private static final int MAX_ELEMENTS_UPPER_BOUND = 10_000_000;

    private final ConcurrentMap<Class<?>, Optional<String>> idAttributeCache = new ConcurrentHashMap<>();

    public CollectionProjectionLoader(EntityManager em,
                                      ConversionService conversionService,
                                      DtoMapperHelper dtoMapperHelper,
                                      int maxJoins,
                                      int batchSize,
                                      int maxRows) {
        this(em, conversionService, dtoMapperHelper, maxJoins, batchSize, maxRows,
                DEFAULT_MAX_DEPTH, DEFAULT_MAX_ELEMENTS);
    }

    public CollectionProjectionLoader(EntityManager em,
                                      ConversionService conversionService,
                                      DtoMapperHelper dtoMapperHelper,
                                      int maxJoins,
                                      int batchSize,
                                      int maxRows,
                                      int maxDepth,
                                      int maxElements) {
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
        if (maxDepth <= 0 || maxDepth > MAX_DEPTH_UPPER_BOUND) {
            throw new IllegalArgumentException("maxCollectionDepth must be in 1.." + MAX_DEPTH_UPPER_BOUND);
        }
        if (maxElements <= 0 || maxElements > MAX_ELEMENTS_UPPER_BOUND) {
            throw new IllegalArgumentException("maxCollectionElements must be in 1.." + MAX_ELEMENTS_UPPER_BOUND);
        }
        this.maxDepth = maxDepth;
        this.maxElements = maxElements;
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
        requireIdAttribute(ownerEntity);
        RowBudget budget = new RowBudget(maxRows);
        CollectionGraphAssembler assembler = new CollectionGraphAssembler(plan,
                (cp, element, owner, ids) -> fetchChunk(cp, element, owner, ids, budget),
                dtoMapperHelper::instantiate,
                batchSize, maxDepth, maxElements);
        return assembler.load(ownerEntity, ownerIds);
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

    /**
     * Runs one batched query for {@code plan} over a chunk of owners and returns its rows grouped
     * by owner id, in query order. Does not recurse: nested collections are scheduled by
     * {@link CollectionGraphAssembler}.
     */
    private CollectionGraphAssembler.FetchedLevel fetchChunk(CollectionPlan plan, DtoElement element,
                                                             Class<?> ownerEntity, List<Object> ownerIds,
                                                             RowBudget budget) {
        String ownerIdAttr = requireIdAttribute(ownerEntity);
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Tuple> cq = cb.createTupleQuery();
        Root<?> root = cq.from(ownerEntity);
        Path<Object> ownerIdPath = root.get(ownerIdAttr);
        JoinContext pathCtx = new JoinContext(maxJoins);

        List<Selection<?>> selections = new ArrayList<>();
        List<Order> orders = new ArrayList<>();
        selections.add(ownerIdPath);
        orders.add(cb.asc(ownerIdPath));

        Map<Object, List<CollectionGraphAssembler.ElementRow>> out = new LinkedHashMap<>();

        // ---- simple values -------------------------------------------------------------
        if (element == null) {
            ScalarElement scalar = (ScalarElement) plan.element();
            Expression<?> value = resolveLeaf(pathCtx, root, plan.path());
            selections.add(value);
            orders.add(cb.asc(value));
            for (Tuple row : execute(cq, root, ownerIdPath, ownerIds, selections, orders, budget, plan, null)) {
                Object converted = coerce(row.get(1), scalar.type(), plan.dtoField());
                if (converted != null) {
                    out.computeIfAbsent(row.get(0), k -> new ArrayList<>())
                            .add(CollectionGraphAssembler.ElementRow.scalar(converted));
                }
            }
            return new CollectionGraphAssembler.FetchedLevel(null, out);
        }

        // ---- DTO elements --------------------------------------------------------------
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

        // Optional "active only" filter on the element: disableDate IS NULL OR disableDate > now
        Predicate activePredicate = null;
        if (!plan.activeField().isBlank()) {
            Path<LocalDateTime> disableDate = resolveActiveField(elCtx, el, plan);
            activePredicate = cb.or(cb.isNull(disableDate), cb.greaterThan(disableDate, LocalDateTime.now()));
        }

        for (Tuple row : execute(cq, root, ownerIdPath, ownerIds, selections, orders, budget, plan, activePredicate)) {
            Map<String, Object> values = new HashMap<>();
            for (int i = 0; i < element.scalars().size(); i++) {
                ScalarField sf = element.scalars().get(i);
                values.put(sf.dtoField(), coerce(row.get(firstScalarColumn + i), sf.type(),
                        plan.dtoField() + "." + sf.dtoField()));
            }
            Object elementId = idColumn >= 0 ? row.get(idColumn) : null;
            out.computeIfAbsent(row.get(0), k -> new ArrayList<>())
                    .add(CollectionGraphAssembler.ElementRow.dto(elementId, values));
        }
        return new CollectionGraphAssembler.FetchedLevel(elementIdAttr.isPresent() ? elementType : null, out);
    }

    private List<Tuple> execute(CriteriaQuery<Tuple> cq, Root<?> root, Path<Object> ownerIdPath,
                                List<Object> ownerIds, List<Selection<?>> selections,
                                List<Order> orders, RowBudget budget, CollectionPlan plan,
                                Predicate extraPredicate) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        cq.multiselect(selections);
        Predicate where = ownerIdPath.in(ownerIds);
        if (extraPredicate != null) {
            where = cb.and(where, extraPredicate);
        }
        cq.where(where);
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

    @SuppressWarnings("unchecked")
    private Path<LocalDateTime> resolveActiveField(JoinContext elCtx, From<?, ?> el, CollectionPlan plan) {
        Path<?> path = resolveRelative(elCtx, el, plan.activeField(), plan);
        if (!LocalDateTime.class.isAssignableFrom(path.getJavaType())) {
            throw new QueryRuleException("activeField '" + plan.activeField() + "' of collection '"
                    + plan.dtoField() + "' must be a LocalDateTime but is " + path.getJavaType().getSimpleName());
        }
        return (Path<LocalDateTime>) path;
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
        return CollectionGraphAssembler.emptyContainer(kind);
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