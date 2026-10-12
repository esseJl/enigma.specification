package ir.sinqro.enigma.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum ComparisonOperator {
    EQ,
    NEQ,
    GT,
    GTE,
    LT,
    LTE,
    LIKE,
    NOT_LIKE,
    CONTAINS,
    NOT_CONTAINS,
    STARTS_WITH,
    ENDS_WITH,
    IN,
    NOT_IN,
    BETWEEN,
    IS_NULL,
    IS_NOT_NULL;

    @JsonCreator
    public static ComparisonOperator from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("operator is required");
        }
        return ComparisonOperator.valueOf(value.trim().toUpperCase().replace('-', '_'));
    }
}
