package ir.sinqro.enigma.specification;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of the collections a DTO wants populated. Built once per DTO class
 * (see {@link CollectionProjectionPlanner}) and shared freely between threads.
 *
 * <h3>Recursive (self-referencing) DTOs</h3>
 * A collection whose element DTO is already being described by an enclosing level (e.g.
 * {@code CategoryDto.children : List<CategoryDto>}, or {@code A -> B -> A}) is planned with a
 * {@link RecursiveElement} instead of an inline {@link DtoElement}. The referenced definition is
 * kept once in {@link #definitions()} and looked up with {@link #dtoElementOf(CollectionPlan)}.
 * This keeps every record in the plan acyclic, so {@code equals}/{@code hashCode}/{@code toString}
 * stay finite while the described structure can recurse.
 */
public final class CollectionProjectionPlan {

    private static final CollectionProjectionPlan EMPTY = new CollectionProjectionPlan(List.of(), Map.of());

    private final List<CollectionPlan> collections;
    private final Map<Class<?>, DtoElement> definitions;

    CollectionProjectionPlan(List<CollectionPlan> collections) {
        this(collections, Map.of());
    }

    CollectionProjectionPlan(List<CollectionPlan> collections, Map<Class<?>, DtoElement> definitions) {
        this.collections = List.copyOf(collections);
        this.definitions = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
    }

    public static CollectionProjectionPlan empty() {
        return EMPTY;
    }

    public List<CollectionPlan> collections() {
        return collections;
    }

    public boolean hasCollections() {
        return !collections.isEmpty();
    }

    /** Element definitions that are the target of at least one {@link RecursiveElement}. */
    public Map<Class<?>, DtoElement> definitions() {
        return definitions;
    }

    /** True if any collection (at any depth) refers back to an enclosing element type. */
    public boolean isRecursive() {
        return !definitions.isEmpty();
    }

    /**
     * The DTO element description of {@code plan}, following a {@link RecursiveElement} to its
     * definition; {@code null} for collections of simple values.
     */
    public DtoElement dtoElementOf(CollectionPlan plan) {
        ElementPlan element = plan.element();
        if (element instanceof DtoElement dto) {
            return dto;
        }
        if (element instanceof RecursiveElement ref) {
            DtoElement def = definitions.get(ref.dtoClass());
            if (def == null) {
                throw new IllegalStateException("No definition for recursive element "
                        + ref.dtoClass().getName() + " (collection '" + plan.dtoField() + "')");
            }
            return def;
        }
        return null;
    }

    @Override
    public String toString() {
        return "CollectionProjectionPlan" + collections + (definitions.isEmpty() ? "" : " definitions=" + definitions.keySet());
    }

    public enum ContainerKind {SET, LIST}

    /** One collection field. {@code path} is relative to the owning entity. */
    public record CollectionPlan(String dtoField, String path, ContainerKind container,
                                 String orderBy, String activeField, ElementPlan element) {
    }

    public sealed interface ElementPlan permits ScalarElement, DtoElement, RecursiveElement {
    }

    /** Collection of simple values; {@code path} of the owning plan points at the value. */
    public record ScalarElement(Class<?> type) implements ElementPlan {
    }

    /** Collection of DTOs; scalar paths are relative to the element entity. */
    public record DtoElement(Class<?> dtoClass, List<ScalarField> scalars,
                             List<CollectionPlan> nested) implements ElementPlan {
        public DtoElement {
            scalars = List.copyOf(scalars);
            nested = List.copyOf(nested);
        }
    }

    /**
     * Collection of DTOs whose element type is an enclosing level of the same plan (self- or
     * mutual reference). Resolve with {@link CollectionProjectionPlan#dtoElementOf(CollectionPlan)}.
     */
    public record RecursiveElement(Class<?> dtoClass) implements ElementPlan {
        public RecursiveElement {
            Objects.requireNonNull(dtoClass, "dtoClass");
        }
    }

    public record ScalarField(String dtoField, String path, Class<?> type) {
    }
}
