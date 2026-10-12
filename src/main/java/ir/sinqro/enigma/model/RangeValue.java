package ir.sinqro.enigma.model;

public record RangeValue(Object lower, Object upper) implements FilterValue {

    public RangeValue {
        if (lower == null && upper == null) {
            throw new IllegalArgumentException(
                    "At least one of lower or upper must be non-null for RANGE");
        }
    }

    @Override
    public ValueType type() {
        return ValueType.RANGE;
    }
}