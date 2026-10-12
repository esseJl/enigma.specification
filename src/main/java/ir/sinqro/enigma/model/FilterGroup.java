package ir.sinqro.enigma.model;

import java.util.List;
import java.util.Objects;

public record FilterGroup(LogicalOperator operator, List<FilterNode> children) implements FilterNode {

    public FilterGroup {
        Objects.requireNonNull(operator, "operator must not be null");
        Objects.requireNonNull(children, "children must not be null");

        children = List.copyOf(children);

        validate(operator, children);
    }

    @Override
    public NodeType type() {
        return NodeType.GROUP;
    }

    private static void validate(
            LogicalOperator operator,
            List<FilterNode> children) {
        if (children.isEmpty()) {
            throw new IllegalArgumentException(
                    "children must not be empty");
        }

        if (operator == LogicalOperator.NOT
                && children.size() != 1) {

            throw new IllegalArgumentException(
                    "NOT operator requires exactly one child");
        }
    }
}
