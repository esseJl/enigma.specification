package ir.sinqro.enigma.model;

import java.util.Objects;

public record FilterPredicate(String field, ComparisonOperator operator, FilterValue value) implements FilterNode {

    public FilterPredicate {
        Objects.requireNonNull(field, "field must not be null");
        Objects.requireNonNull(operator, "operator must not be null");

        if (field.isBlank()) {
            throw new IllegalArgumentException("field must not be blank");
        }

        if (requiresValue(operator) && value == null) {
            throw new IllegalArgumentException(
                    "value is required for operator " + operator);
        }

        if (!requiresValue(operator) && value != null) {
            throw new IllegalArgumentException(
                    "value is not allowed for operator " + operator);
        }
    }

    @Override
    public NodeType type() {
        return NodeType.PREDICATE;
    }

    private static boolean requiresValue(ComparisonOperator operator) {
        return switch (operator) {
            case IS_NULL, IS_NOT_NULL -> false;
            default -> true;
        };
    }
}
