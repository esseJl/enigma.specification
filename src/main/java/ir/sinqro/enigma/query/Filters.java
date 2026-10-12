package ir.sinqro.enigma.query;

import ir.sinqro.enigma.model.*;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class Filters {

    private Filters() {
    }

    public static FilterPredicate eq(String field, Object value) {
        return predicate(field, ComparisonOperator.EQ, value);
    }

    public static FilterPredicate neq(String field, Object value) {
        return predicate(field, ComparisonOperator.NEQ, value);
    }

    public static FilterPredicate gt(String field, Object value) {
        return predicate(field, ComparisonOperator.GT, value);
    }

    public static FilterPredicate gte(String field, Object value) {
        return predicate(field, ComparisonOperator.GTE, value);
    }

    public static FilterPredicate lt(String field, Object value) {
        return predicate(field, ComparisonOperator.LT, value);
    }

    public static FilterPredicate lte(String field, Object value) {
        return predicate(field, ComparisonOperator.LTE, value);
    }

    public static FilterPredicate like(String field, String pattern) {
        return predicate(field, ComparisonOperator.LIKE, pattern);
    }

    public static FilterPredicate notLike(String field, String pattern) {
        return predicate(field, ComparisonOperator.NOT_LIKE, pattern);
    }

    public static FilterPredicate contains(String field, String value) {
        return predicate(field, ComparisonOperator.CONTAINS, value);
    }

    public static FilterPredicate notContains(String field, String value) {
        return predicate(field, ComparisonOperator.NOT_CONTAINS, value);
    }

    public static FilterPredicate startsWith(String field, String value) {
        return predicate(field, ComparisonOperator.STARTS_WITH, value);
    }

    public static FilterPredicate endsWith(String field, String value) {
        return predicate(field, ComparisonOperator.ENDS_WITH, value);
    }

    public static FilterPredicate in(String field, Object... values) {
        return new FilterPredicate(field, ComparisonOperator.IN, new MultiValue(Arrays.asList(values)));
    }

    public static FilterPredicate in(String field, List<?> values) {
        return new FilterPredicate(field, ComparisonOperator.IN, new MultiValue(values));
    }

    public static FilterPredicate notIn(String field, Object... values) {
        return new FilterPredicate(field, ComparisonOperator.NOT_IN, new MultiValue(Arrays.asList(values)));
    }

    public static FilterPredicate notIn(String field, List<?> values) {
        return new FilterPredicate(field, ComparisonOperator.NOT_IN, new MultiValue(values));
    }

    public static FilterPredicate between(String field, Object lower, Object upper) {
        return new FilterPredicate(field, ComparisonOperator.BETWEEN, new RangeValue(lower, upper));
    }

    public static FilterPredicate isNull(String field) {
        return new FilterPredicate(field, ComparisonOperator.IS_NULL, null);
    }

    public static FilterPredicate isNotNull(String field) {
        return new FilterPredicate(field, ComparisonOperator.IS_NOT_NULL, null);
    }


    public static FilterNode and(FilterNode... nodes) {
        return group(LogicalOperator.AND, nodes);
    }

    public static FilterNode and(List<FilterNode> nodes) {
        return group(LogicalOperator.AND, nodes.toArray(new FilterNode[0]));
    }

    public static FilterNode or(FilterNode... nodes) {
        return group(LogicalOperator.OR, nodes);
    }

    public static FilterNode or(List<FilterNode> nodes) {
        return group(LogicalOperator.OR, nodes.toArray(new FilterNode[0]));
    }

    public static FilterNode not(FilterNode node) {
        return new FilterGroup(LogicalOperator.NOT, List.of(Objects.requireNonNull(node, "node must not be null")));
    }


    private static FilterNode group(LogicalOperator operator, FilterNode... nodes) {
        List<FilterNode> nonNull = Arrays.stream(nodes)
                .filter(Objects::nonNull)
                .toList();

        if (nonNull.isEmpty()) {
            throw new IllegalArgumentException("At least one non-null filter must be provided to Filters." +
                    (operator == LogicalOperator.OR ? "or(...)" : "and(...)"));
        }
        if (nonNull.size() == 1) {
            return nonNull.get(0);
        }
        return new FilterGroup(operator, nonNull);
    }

    private static FilterPredicate predicate(String field, ComparisonOperator operator, Object value) {
        return new FilterPredicate(field, operator, new SingleValue(value));
    }
}
