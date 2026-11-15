package ir.sinqro.enigma.annotation;

import java.lang.annotation.*;

/**
 * Marks a {@link DtoMapper} DTO field of type {@code List<HierarchyNode>} (or {@code Collection})
 * that receives the path the row was grouped by, when the query service defines a
 * {@link ir.sinqro.enigma.grouping.HierarchyGrouping}: one {@link ir.sinqro.enigma.grouping.HierarchyNode}
 * per level that has a node, root first. Rows without any node get an empty list.
 * <p>
 * The value is computed by the grouping query itself, so it always matches the order of the
 * rows. The field is not mapped to an entity path: it is never selected as a column and cannot be
 * used for filtering or sorting. Without a grouping it is left {@code null}.
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HierarchyPath {
}
