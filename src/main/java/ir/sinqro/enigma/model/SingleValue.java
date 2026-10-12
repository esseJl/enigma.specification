package ir.sinqro.enigma.model;

import java.util.Objects;

public record SingleValue(Object value) implements FilterValue {

    public SingleValue {
        Objects.requireNonNull(value, "value must not be null");
    }

    @Override
    public ValueType type() {
        return ValueType.SINGLE;
    }
}
