package ir.sinqro.enigma.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = SingleValue.class, name = "SINGLE"),
        @JsonSubTypes.Type(value = MultiValue.class, name = "MULTI"),
        @JsonSubTypes.Type(value = RangeValue.class, name = "RANGE")
})
public sealed interface FilterValue permits SingleValue, MultiValue, RangeValue {

    ValueType type();

    enum ValueType {
        SINGLE,
        MULTI,
        RANGE
    }
}
