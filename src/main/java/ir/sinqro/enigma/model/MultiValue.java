package ir.sinqro.enigma.model;

import java.util.List;
import java.util.Objects;

public record MultiValue(List<?> values) implements FilterValue {

    public MultiValue {
        Objects.requireNonNull(values, "values must not be null");

        values = List.copyOf(values);

        if (values.isEmpty()) {
            throw new IllegalArgumentException(
                    "values must not be empty");
        }
    }

    @Override
    public ValueType type() {
        return ValueType.MULTI;
    }
}
