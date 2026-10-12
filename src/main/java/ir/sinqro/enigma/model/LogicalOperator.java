package ir.sinqro.enigma.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum LogicalOperator {
    AND,
    OR,
    NOT;

    @JsonCreator
    public static LogicalOperator from(String value) {
        if (value == null)
            return AND;
        return LogicalOperator.valueOf(value.trim().toUpperCase());
    }
}
