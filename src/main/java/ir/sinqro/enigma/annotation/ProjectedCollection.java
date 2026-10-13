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
}
