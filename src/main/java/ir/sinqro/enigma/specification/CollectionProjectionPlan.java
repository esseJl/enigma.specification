package ir.sinqro.enigma.specification;

import java.util.List;

/**
 * Immutable description of the collections a DTO wants populated. Built once per DTO class
 * (see {@link CollectionProjectionPlanner}) and shared freely between threads.
 */
public final class CollectionProjectionPlan {

    private static final CollectionProjectionPlan EMPTY = new CollectionProjectionPlan(List.of());

    private final List<CollectionPlan> collections;

    CollectionProjectionPlan(List<CollectionPlan> collections) {
        this.collections = List.copyOf(collections);
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

    public enum ContainerKind {SET, LIST}

    /** One collection field. {@code path} is relative to the owning entity. */
    public record CollectionPlan(String dtoField, String path, ContainerKind container,
                                 String orderBy, String activeField, ElementPlan element) {
    }

    public sealed interface ElementPlan permits ScalarElement, DtoElement {
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

    public record ScalarField(String dtoField, String path, Class<?> type) {
    }
}