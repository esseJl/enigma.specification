package ir.sinqro.enigma.model;

import java.util.Objects;

public record SortRequest(String field, SortDirection direction) {

    public SortRequest {
        Objects.requireNonNull(field);
        Objects.requireNonNull(direction);

        if (field.isBlank()) {
            throw new IllegalArgumentException(
                    "field must not be blank");
        }
    }

    public static SortRequest asc(
            String field) {
        return new SortRequest(
                field,
                SortDirection.ASC);
    }

    public static SortRequest desc(
            String field) {
        return new SortRequest(
                field,
                SortDirection.DESC);
    }
}
