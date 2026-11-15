package ir.sinqro.enigma.specification;

import ir.sinqro.enigma.grouping.HierarchyGrouping;
import ir.sinqro.enigma.grouping.HierarchyNode;
import ir.sinqro.enigma.model.SortDirection;
import ir.sinqro.enigma.specification.HierarchyGroupingSupport.ResolvedHierarchy;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.criteria.Subquery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Grouping expressions of one criteria query (see {@link HierarchyGrouping}).
 * <p>
 * For every level {@code k} two correlated scalar sub-queries are built:
 * <pre>
 * id(k)    = (select least(n.id) from Owner o join o.nodes n [left join n.parent a1 ... a(k-1)]
 *             where o.id = owner.id and &lt;n has exactly k-1 ancestors&gt;)
 * label(k) = (select m.label from Node m where m.id = id(k))
 * </pre>
 * Both always describe the same node, also on data with several nodes on one level, and they never
 * multiply rows (unlike joins), so no {@code DISTINCT} is needed. Each call of {@link #selections()}
 * and {@link #orders()} builds fresh sub-query instances: a criteria node is never used twice in
 * one query tree.
 * <p>
 * Not thread-safe; one instance per query, like the criteria objects it wraps.
 */
public final class HierarchyGroupingQuery {

    /** Columns per level in {@link #selections()}: id, label. */
    private static final int COLUMNS_PER_LEVEL = 2;

    private final HierarchyGrouping grouping;
    private final ResolvedHierarchy hierarchy;
    private final CommonAbstractCriteria query;
    private final Root<?> owner;
    private final CriteriaBuilder cb;

    HierarchyGroupingQuery(HierarchyGrouping grouping, ResolvedHierarchy hierarchy,
                           CommonAbstractCriteria query, Root<?> owner, CriteriaBuilder cb) {
        this.grouping = grouping;
        this.hierarchy = hierarchy;
        this.query = query;
        this.owner = owner;
        this.cb = cb;
    }

    /** Number of columns {@link #selections()} adds to the select list. */
    public int columnCount() {
        return grouping.maxDepth() * COLUMNS_PER_LEVEL;
    }

    /** Select-list items: id and label of every level, root level first. */
    public List<Selection<?>> selections() {
        List<Selection<?>> selections = new ArrayList<>(columnCount());
        for (int level = 1; level <= grouping.maxDepth(); level++) {
            selections.add(nodeIdAt(query, level));
            selections.add(labelAt(level));
        }
        return selections;
    }

    /**
     * Orderings that make the grouping: per level label (in the grouping direction) then id, with
     * owners lacking the level-1 node placed per {@link HierarchyGrouping#ungrouped()} and owners
     * whose path ends above a deeper level placed before that level's groups.
     */
    public List<Order> orders() {
        List<Order> orders = new ArrayList<>(columnCount());
        for (int level = 1; level <= grouping.maxDepth(); level++) {
            Nulls nulls = level == 1 && grouping.ungrouped() == HierarchyGrouping.Placement.LAST
                    ? Nulls.LAST
                    : Nulls.FIRST;
            Expression<String> label = labelAt(level);
            orders.add(grouping.direction() == SortDirection.DESC ? cb.desc(label, nulls) : cb.asc(label, nulls));
            orders.add(cb.asc(nodeIdAt(query, level), nulls));
        }
        return orders;
    }

    /**
     * The group path of a row selected with {@link #selections()} starting at {@code firstColumn}:
     * one node per level that has one, root level first; empty when the row has no node.
     */
    public List<HierarchyNode> pathOf(Tuple tuple, int firstColumn) {
        List<HierarchyNode> path = new ArrayList<>(grouping.maxDepth());
        for (int level = 1; level <= grouping.maxDepth(); level++) {
            int column = firstColumn + (level - 1) * COLUMNS_PER_LEVEL;
            Object id = tuple.get(column);
            if (id != null) {
                Object label = tuple.get(column + 1);
                path.add(new HierarchyNode(level, id, label != null ? label.toString() : null));
            }
        }
        return Collections.unmodifiableList(path);
    }

    // ------------------------------------------------------------------ expressions

    private Subquery<String> labelAt(int level) {
        Subquery<String> sub = query.subquery(String.class);
        Root<?> node = sub.from(hierarchy.nodeClass());
        sub.select(node.<String>get(hierarchy.label()));
        sub.where(cb.equal(node.get(hierarchy.nodeId()), nodeIdAt(sub, level)));
        return sub;
    }

    /** Smallest id among the owner's nodes that have exactly {@code level - 1} ancestors. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Subquery<?> nodeIdAt(CommonAbstractCriteria parentQuery, int level) {
        Subquery sub = parentQuery.subquery(hierarchy.nodeIdType());
        Root<?> correlated = sub.from(hierarchy.ownerClass());
        String[] path = hierarchy.nodePath();
        From<?, ?> from = correlated;
        for (int i = 0; i < path.length - 1; i++) {
            from = from.join(path[i], JoinType.INNER);
        }
        From<?, ?> node = from.join(path[path.length - 1], JoinType.INNER);

        Predicate depth;
        if (level == 1) {
            depth = cb.isNull(node.get(hierarchy.parent()));
        } else {
            From<?, ?> ancestor = node;
            for (int i = 1; i < level; i++) {
                ancestor = ancestor.join(hierarchy.parent(), JoinType.LEFT);
            }
            depth = cb.and(cb.isNotNull(ancestor.get(hierarchy.nodeId())), cb.isNull(ancestor.get(hierarchy.parent())));
        }

        sub.select(cb.least((Expression) node.get(hierarchy.nodeId())));
        sub.where(cb.equal(correlated.get(hierarchy.ownerId()), owner.get(hierarchy.ownerId())), depth);
        return sub;
    }
}
