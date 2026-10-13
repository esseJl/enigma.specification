package ir.sinqro.enigma.specification;

import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.model.*;
import jakarta.persistence.criteria.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;


public class SpecificationBuilder {

    private final FieldMetaRegistry fieldMetaRegistry;
    private final ValueConverter valueConverter;
    private final PathResolver pathResolver;

    private final int maxFilterDepth;
    private final int maxSortFields;
    private final int maxFilterNodes;
    private final int maxJoins;

    private enum Comparison {GT, GE, LT, LE}

    public SpecificationBuilder(FieldMetaRegistry fieldMetaRegistry,
                                ValueConverter valueConverter,
                                PathResolver pathResolver,
                                @Value("${enigma.spec.max-filter-depth:32}") int maxFilterDepth,
                                @Value("${enigma.spec.max-sort-fields:20}") int maxSortFields,
                                @Value("${enigma.spec.max-filter-nodes:1000}") int maxFilterNodes,
                                @Value("${enigma.spec.max-joins:30}") int maxJoins) {
        this.fieldMetaRegistry = Objects.requireNonNull(fieldMetaRegistry);
        this.valueConverter = Objects.requireNonNull(valueConverter);
        this.pathResolver = Objects.requireNonNull(pathResolver);
        this.maxFilterDepth = requirePositive(maxFilterDepth, "maxFilterDepth");
        this.maxSortFields = requirePositive(maxSortFields, "maxSortFields");
        this.maxFilterNodes = requirePositive(maxFilterNodes, "maxFilterNodes");
        this.maxJoins = requirePositive(maxJoins, "maxJoins");
    }


    public <T> Specification<T> buildSpecification(QueryRequest request, Class<?> dtoOrEntity) {
        if (request == null || request.filter() == null) {
            return (root, query, cb) -> cb.conjunction();
        }

        final FilterNode rootNode = request.filter();

        return (root, query, cb) -> {
            JoinContext joinCtx = new JoinContext(maxJoins);
            int[] nodeBudget = {maxFilterNodes};
            Predicate predicate = buildNode(rootNode, root, cb, dtoOrEntity, joinCtx, 0, nodeBudget);

            if (joinCtx.hasCollectionJoin()) {
                query.distinct(true);
            }
            return predicate != null ? predicate : cb.conjunction();
        };
    }

    public <T> Specification<T> buildSpecificationWithJoin(QueryRequest request,
                                                           Class<?> dtoOrEntity,
                                                           JoinContext joinCtx) {
        Objects.requireNonNull(joinCtx, "joinCtx must not be null");
        if (request == null || request.filter() == null) {
            return (root, query, cb) -> cb.conjunction();
        }

        final FilterNode rootNode = request.filter();

        return (root, query, cb) -> {
            int[] nodeBudget = {maxFilterNodes};
            Predicate predicate = buildNode(rootNode, root, cb, dtoOrEntity, joinCtx, 0, nodeBudget);

            if (joinCtx.hasCollectionJoin()) {
                query.distinct(true);
            }
            return predicate != null ? predicate : cb.conjunction();
        };
    }


    public Class<?> resolveEntityClass(Class<?> dtoOrEntity) {
        return fieldMetaRegistry.resolveEntityClass(dtoOrEntity);
    }

    public String resolveSortPath(String clientField, Class<?> dtoOrEntity) {
        return pathResolver.resolveSortPath(clientField, dtoOrEntity);
    }

    public Path<?> resolvePathWithJoin(From<?, ?> from, String entityPath, JoinContext ctx) {
        return pathResolver.resolvePathWithJoin(from, entityPath, ctx);
    }

    public List<FieldMeta> getProjectionFieldMetas(Class<?> dtoOrEntity) {
        return fieldMetaRegistry.getProjectionFieldMetas(dtoOrEntity);
    }

    public <T> List<Selection<?>> buildProjectionSelectionsWithAlias(Root<T> root,
                                                                     Class<?> dtoOrEntity,
                                                                     JoinContext joinCtx) {
        List<FieldMeta> metas = getProjectionFieldMetas(dtoOrEntity);
        if (metas.isEmpty()) {
            throw new QueryRuleException(
                    "No queryable fields found for projection target: "
                            + (dtoOrEntity != null ? dtoOrEntity.getSimpleName() : "null")
                            + ". Check @DtoMapper / @Mapper / @DisableSpecificationQuery configuration.");
        }
        // No aliases on purpose: Criteria Path instances are cached and alias() mutates them in place,
        // so aliasing causes "duplicate alias" errors when two columns resolve to the same path.
        // Tuples are always read by index.
        List<Selection<?>> selections = new ArrayList<>(metas.size());
        for (FieldMeta meta : metas) {
            selections.add(resolvePathWithJoin(root, meta.entityPath(), joinCtx));
        }
        return selections;
    }


    public int maxSortFields() {
        return maxSortFields;
    }

    public int maxJoins() {
        return maxJoins;
    }

    public int maxFilterNodes() {
        return maxFilterNodes;
    }

    public int maxFilterDepth() {
        return maxFilterDepth;
    }

    // -------------------------------------------------------------------------
    // internal
    // -------------------------------------------------------------------------

    private Predicate buildNode(FilterNode node, Root<?> root, CriteriaBuilder cb, Class<?> dtoOrEntity, JoinContext joinCtx, int depth, int[] nodeBudget) {

        if (node == null) {
            return null;
        }
        if (depth >= maxFilterDepth) {
            throw new QueryRuleException(
                    "Filter tree exceeds maximum allowed depth of " + maxFilterDepth);
        }
        if (--nodeBudget[0] < 0) {
            throw new QueryRuleException(
                    "Filter tree exceeds maximum allowed number of nodes of " + maxFilterNodes);
        }

        return switch (node.type()) {
            case PREDICATE -> buildSinglePredicate((FilterPredicate) node, root, cb, dtoOrEntity, joinCtx);
            case GROUP -> buildGroup((FilterGroup) node, root, cb, dtoOrEntity, joinCtx, depth + 1, nodeBudget);
        };
    }

    private Predicate buildGroup(FilterGroup group, Root<?> root, CriteriaBuilder cb, Class<?> dtoOrEntity, JoinContext joinCtx, int depth, int[] nodeBudget) {

        List<FilterNode> children = group.children();
        if (children == null || children.isEmpty()) {
            return cb.conjunction();
        }

        LogicalOperator op = group.operator();

        if (op == LogicalOperator.NOT) {
            if (children.size() != 1) {
                throw new QueryRuleException("NOT operator must have exactly one child");
            }
            Predicate child = buildNode(children.get(0), root, cb, dtoOrEntity, joinCtx, depth, nodeBudget);
            return child == null ? cb.conjunction() : cb.not(child);
        }

        List<Predicate> predicates = new ArrayList<>(children.size());
        for (FilterNode child : children) {
            Predicate p = buildNode(child, root, cb, dtoOrEntity, joinCtx, depth, nodeBudget);
            if (p != null) {
                predicates.add(p);
            }
        }

        if (predicates.isEmpty()) {
            return cb.conjunction();
        }
        if (predicates.size() == 1) {
            return predicates.get(0);
        }

        Predicate[] arr = predicates.toArray(new Predicate[0]);
        return (op == LogicalOperator.OR) ? cb.or(arr) : cb.and(arr);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate buildSinglePredicate(FilterPredicate predicate, Root<?> root, CriteriaBuilder cb, Class<?> dtoOrEntity, JoinContext joinCtx) {

        try {
            Path<?> path = pathResolver.resolvePath(root, predicate.field(), dtoOrEntity, joinCtx);
            Class<?> javaType = path.getJavaType();
            ComparisonOperator operator = predicate.operator();
            FilterValue filterValue = predicate.value();

            if (operator == ComparisonOperator.IS_NULL) {
                return cb.isNull(path);
            }
            if (operator == ComparisonOperator.IS_NOT_NULL) {
                return cb.isNotNull(path);
            }

            if (filterValue == null) {
                throw new IllegalArgumentException("value is required for operator " + operator);
            }

            return switch (operator) {
                case EQ -> cb.equal(path, valueConverter.convertSingle(filterValue, javaType));
                case NEQ -> cb.notEqual(path, valueConverter.convertSingle(filterValue, javaType));

                case GT -> buildComparable(cb, path,
                        valueConverter.convertSingle(filterValue, javaType), Comparison.GT);
                case GTE -> buildComparable(cb, path,
                        valueConverter.convertSingle(filterValue, javaType), Comparison.GE);
                case LT -> buildComparable(cb, path,
                        valueConverter.convertSingle(filterValue, javaType), Comparison.LT);
                case LTE -> buildComparable(cb, path,
                        valueConverter.convertSingle(filterValue, javaType), Comparison.LE);

                case LIKE -> stringLike(cb, path, javaType,
                        valueConverter.convertSingle(filterValue, String.class), false, false);
                case NOT_LIKE -> cb.not(stringLike(cb, path, javaType,
                        valueConverter.convertSingle(filterValue, String.class), false, false));

                case CONTAINS -> stringLike(cb, path, javaType,
                        valueConverter.convertSingle(filterValue, String.class), true, true);
                case NOT_CONTAINS -> cb.not(stringLike(cb, path, javaType,
                        valueConverter.convertSingle(filterValue, String.class), true, true));

                case STARTS_WITH -> stringLike(cb, path, javaType,
                        valueConverter.convertSingle(filterValue, String.class), false, true);
                case ENDS_WITH -> stringLike(cb, path, javaType,
                        valueConverter.convertSingle(filterValue, String.class), true, false);

                case IN -> path.in(valueConverter.convertMulti(filterValue, javaType));
                case NOT_IN -> cb.not(path.in(valueConverter.convertMulti(filterValue, javaType)));

                case BETWEEN -> {
                    RangeValue range = valueConverter.requireRange(filterValue);
                    Object lower = valueConverter.convertValue(range.lower(), javaType);
                    Object upper = valueConverter.convertValue(range.upper(), javaType);

                    if (lower == null && upper == null) {
                        yield cb.conjunction();
                    }
                    if (lower == null) {
                        yield buildComparable(cb, path, upper, Comparison.LE);
                    }
                    if (upper == null) {
                        yield buildComparable(cb, path, lower, Comparison.GE);
                    }
                    yield cb.between((Expression<? extends Comparable>) path, (Comparable) lower, (Comparable) upper);
                }

                default -> throw new UnsupportedOperationException("Operator not supported: " + operator);
            };
        } catch (QueryRuleException re) {
            throw re;
        } catch (Exception e) {
            throw new QueryRuleException(
                    "Invalid filter for field: '" + predicate.field()
                            + "' with operator " + predicate.operator()
                            + " - " + e.getMessage(), e);
        }
    }

    private Predicate stringLike(CriteriaBuilder cb, Path<?> path, Class<?> javaType, Object value, boolean prefixWildcard, boolean suffixWildcard) {

        if (!String.class.isAssignableFrom(javaType)) {
            throw new IllegalArgumentException("String operator can only be applied to String fields");
        }

        String pattern = escapeLike(Objects.toString(value, ""));
        if (prefixWildcard) {
            pattern = "%" + pattern;
        }
        if (suffixWildcard) {
            pattern = pattern + "%";
        }
        return cb.like(cb.lower(path.as(String.class)), pattern.toLowerCase(Locale.ROOT), '\\');
    }

    private static String escapeLike(String raw) {
        if (raw == null) {
            return "";
        }
        return raw
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Predicate buildComparable(CriteriaBuilder cb, Path<?> path, Object value, Comparison comp) {
        if (!(value instanceof Comparable c)) {
            throw new IllegalArgumentException(
                    "Value is not Comparable. Type: " + (value != null ? value.getClass().getName() : "null"));
        }
        Expression<? extends Comparable> expr = (Expression<? extends Comparable>) path;
        return switch (comp) {
            case GT -> cb.greaterThan(expr, c);
            case GE -> cb.greaterThanOrEqualTo(expr, c);
            case LT -> cb.lessThan(expr, c);
            case LE -> cb.lessThanOrEqualTo(expr, c);
        };
    }

    private static int requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException(name + " must be >= 1");
        }
        return value;
    }
}