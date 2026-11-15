package ir.sinqro.enigma.grouping;

/**
 * One group of the path a row was grouped by (see {@link HierarchyGrouping}).
 *
 * @param level 1 = root level
 * @param id    identifier of the tree node (its entity id type, e.g. {@code Long})
 * @param label value of the grouping's label attribute
 */
public record HierarchyNode(int level, Object id, String label) {

    public HierarchyNode {
        if (level < 1) {
            throw new IllegalArgumentException("level must be >= 1");
        }
        if (id == null) {
            throw new IllegalArgumentException("id must not be null");
        }
    }
}
