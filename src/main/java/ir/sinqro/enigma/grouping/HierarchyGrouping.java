package ir.sinqro.enigma.grouping;

import ir.sinqro.enigma.model.SortDirection;

import java.util.Objects;

/**
 * Groups the rows of a query (the <em>owners</em>) by a tree they are assigned to, so that a page
 * can be presented as "group rows" followed by their owners, and the grouping stays stable across
 * pages.
 * <p>
 * The tree is a self-referencing entity (adjacency list, e.g. {@code Category.parent}) reached from
 * the owner through a to-many association (e.g. {@code Product.categories}). An owner is expected
 * to hold <b>at most one node per tree level</b>; the node of level {@code k} is the assigned node
 * that has exactly {@code k - 1} ancestors. Rows are ordered by
 * <ol>
 *   <li>level 1 label, level 1 id, level 2 label, level 2 id, ... up to {@link #maxDepth()},</li>
 *   <li>then the client sort, the service's internal sort and finally the owner id.</li>
 * </ol>
 * Within a group, owners whose path ends at that group come <em>before</em> the sub-groups (so a
 * flat list reads unambiguously: every owner belongs to the closest group row above it). Owners
 * without a level-1 node are placed according to {@link #ungrouped()}.
 * <p>
 * Data that breaks the "one node per level" expectation never fails the query: the node with the
 * smallest id of that level is used, deterministically. Nodes deeper than {@code maxDepth} are
 * ignored for grouping.
 * <p>
 * Enable it by overriding {@code QueryService#grouping()}; declare a {@code List<HierarchyNode>}
 * DTO field annotated with {@link ir.sinqro.enigma.annotation.HierarchyPath} to receive the path
 * the row was grouped by.
 *
 * <pre>{@code
 * @Override
 * protected HierarchyGrouping grouping() {
 *     return HierarchyGrouping.of("categories", 3);          // parent = "parent", label = "name"
 * }
 * }</pre>
 *
 * @param nodes     path from the owner to the to-many association of tree nodes; leading segments
 *                  (if any) must be to-one associations, e.g. {@code "categories"} or
 *                  {@code "catalog.categories"}
 * @param parent    self-referencing to-one attribute of the node entity, e.g. {@code "parent"}
 * @param label     String attribute of the node used for ordering and display, e.g. {@code "name"}
 * @param maxDepth  number of tree levels taken into account, {@code 1..}{@value #MAX_DEPTH_LIMIT}
 * @param direction order of groups of the same level by label
 * @param ungrouped placement of owners that have no level-1 node
 */
public record HierarchyGrouping(String nodes,
                                String parent,
                                String label,
                                int maxDepth,
                                SortDirection direction,
                                Placement ungrouped) {

    /** Upper bound of {@link #maxDepth()}: every level costs two correlated sub-queries. */
    public static final int MAX_DEPTH_LIMIT = 16;

    /** Where owners without a level-1 node go. */
    public enum Placement {FIRST, LAST}

    public HierarchyGrouping {
        nodes = requirePath(nodes, "nodes");
        parent = requireAttribute(parent, "parent");
        label = requireAttribute(label, "label");
        if (maxDepth < 1 || maxDepth > MAX_DEPTH_LIMIT) {
            throw new IllegalArgumentException("maxDepth must be in 1.." + MAX_DEPTH_LIMIT + " (was " + maxDepth + ")");
        }
        Objects.requireNonNull(direction, "direction must not be null");
        Objects.requireNonNull(ungrouped, "ungrouped must not be null");
    }

    /**
     * Grouping by the tree reached through {@code nodes}, with the conventional attribute names
     * {@code parent} and {@code name}, ascending, ungrouped owners last.
     */
    public static HierarchyGrouping of(String nodes, int maxDepth) {
        return new HierarchyGrouping(nodes, "parent", "name", maxDepth, SortDirection.ASC, Placement.LAST);
    }

    public HierarchyGrouping withParent(String parent) {
        return new HierarchyGrouping(nodes, parent, label, maxDepth, direction, ungrouped);
    }

    public HierarchyGrouping withLabel(String label) {
        return new HierarchyGrouping(nodes, parent, label, maxDepth, direction, ungrouped);
    }

    public HierarchyGrouping withDirection(SortDirection direction) {
        return new HierarchyGrouping(nodes, parent, label, maxDepth, direction, ungrouped);
    }

    public HierarchyGrouping withUngrouped(Placement ungrouped) {
        return new HierarchyGrouping(nodes, parent, label, maxDepth, direction, ungrouped);
    }

    private static String requirePath(String value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        for (String segment : trimmed.split("\\.", -1)) {
            if (segment.isBlank()) {
                throw new IllegalArgumentException("Empty path segment in " + name + ": '" + value + "'");
            }
        }
        return trimmed;
    }

    private static String requireAttribute(String value, String name) {
        String trimmed = requirePath(value, name);
        if (trimmed.contains(".")) {
            throw new IllegalArgumentException(name + " must be a single attribute of the node entity (was '" + value + "')");
        }
        return trimmed;
    }
}
