package ir.sinqro.enigma.annotation;

import java.lang.annotation.*;

/**
 * Opt-in marker: populate this DTO collection field (a {@code Set}, {@code List} or
 * {@code Collection}) from a to-many association / element collection of the entity during
 * {@link DtoMapper} projection.
 * <p>
 * The path to the collection is taken from {@link Mapper} (relative to the owning entity) or,
 * if absent, from the field name. Inside an element DTO, {@link Mapper} paths are relative to
 * the element entity. Element types may be a simple value (String, enum, number, ...) or a DTO
 * made of simple fields and further {@code @ProjectedCollection} fields.
 * <p>
 * Collections are loaded with one batched query per collection per page (never per row), inside
 * the caller's read transaction, and are bounded by
 * {@code app.specification.spec.max-collection-rows}.
 * <p>
 * <b>Recursive DTOs.</b> The element type may be the declaring DTO itself or any enclosing
 * element DTO, e.g. a category tree:
 * <pre>{@code
 * @DtoMapper(ProductCategory.class)
 * record CategoryResponse(Long id, String name, @Mapper("parent.id") Long parentId,
 *         @ProjectedCollection(orderBy = "name") @Mapper("children") List<CategoryResponse> children) {}
 * }</pre>
 * Such collections are loaded level by level (one batched query per tree level), each
 * (collection, owner) at most once per request. A DTO used recursively as an element may only
 * contain simple fields and {@code @ProjectedCollection} fields. If the data itself is cyclic
 * (e.g. a node that is its own ancestor) the element is still returned but the repeated
 * collection is left empty instead of being expanded again. Depth and output size are bounded by
 * {@code max-collection-depth} (default 32) and {@code max-collection-elements} (default 50000);
 * exceeding either fails the request.
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProjectedCollection {

    /**
     * Ascending sort path, relative to the element entity. Empty = order by element id
     * (or by value for simple element types). Ignored for simple element types.
     */
    String orderBy() default "";

    /**
     * Path (relative to the element entity) of a nullable date-time "disable date" column.
     * When set, only active elements are loaded: {@code field IS NULL OR field > now}.
     * Not supported for simple element types. Empty = no filtering.
     */
    String activeField() default "";
}