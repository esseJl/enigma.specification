package ir.sinqro.enigma.model;

import com.fasterxml.jackson.annotation.JsonCreator;

public enum SortDirection {
    ASC,
    DESC;

    @JsonCreator
    public static SortDirection from(String value) {
        if (value == null)
            return DESC;
        return SortDirection.valueOf(value.trim().toUpperCase());
    }
}
