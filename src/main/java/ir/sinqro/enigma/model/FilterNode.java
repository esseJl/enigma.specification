package ir.sinqro.enigma.model;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = FilterPredicate.class, name = "PREDICATE"),
        @JsonSubTypes.Type(value = FilterGroup.class, name = "GROUP")
})
public sealed interface FilterNode permits FilterPredicate, FilterGroup {

    NodeType type();

    enum NodeType {
        PREDICATE,
        GROUP
    }
}