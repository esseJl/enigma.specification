package ir.sinqro.enigma.specification;

import ir.sinqro.enigma.grouping.HierarchyGrouping;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CommonAbstractCriteria;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.EntityType;
import jakarta.persistence.metamodel.ManagedType;
import jakarta.persistence.metamodel.Metamodel;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.SingularAttribute;
import jakarta.persistence.metamodel.Type;
import org.springframework.util.ClassUtils;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Validates a {@link HierarchyGrouping} against the JPA metamodel (once per owner entity and
 * grouping) and prepares the per-query {@link HierarchyGroupingQuery}.
 * <p>
 * A grouping that does not fit the model (unknown attribute, {@code nodes} not ending in a to-many
 * association of entities, {@code parent} not a to-one self reference, {@code label} not a String,
 * composite ids) is a programming error and fails with {@link IllegalStateException}.
 * <p>
 * Thread-safe: the cache holds immutable values.
 */
public class HierarchyGroupingSupport {

    private final EntityManager entityManager;
    private final ConcurrentMap<Key, ResolvedHierarchy> cache = new ConcurrentHashMap<>();

    public HierarchyGroupingSupport(EntityManager entityManager) {
        this.entityManager = Objects.requireNonNull(entityManager, "entityManager must not be null");
    }

    /**
     * Prepares the grouping expressions for one criteria query whose root is {@code owner}.
     *
     * @param query the query that owns {@code owner}; sub-queries are created from it
     */
    public HierarchyGroupingQuery prepare(HierarchyGrouping grouping, Class<?> ownerClass,
                                          CommonAbstractCriteria query, Root<?> owner, CriteriaBuilder cb) {
        Objects.requireNonNull(grouping, "grouping must not be null");
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(owner, "owner must not be null");
        Objects.requireNonNull(cb, "cb must not be null");
        return new HierarchyGroupingQuery(grouping, resolve(grouping, ownerClass), query, owner, cb);
    }

    ResolvedHierarchy resolve(HierarchyGrouping grouping, Class<?> ownerClass) {
        Objects.requireNonNull(ownerClass, "ownerClass must not be null");
        Key key = new Key(ownerClass, grouping);
        ResolvedHierarchy cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        ResolvedHierarchy resolved = build(grouping, ownerClass);
        ResolvedHierarchy previous = cache.putIfAbsent(key, resolved);
        return previous != null ? previous : resolved;
    }

    private ResolvedHierarchy build(HierarchyGrouping grouping, Class<?> ownerClass) {
        Metamodel metamodel = entityManager.getMetamodel();
        EntityType<?> ownerType = entity(metamodel, ownerClass, grouping);
        String ownerId = singleIdAttribute(ownerType, grouping);

        String[] path = grouping.nodes().split("\\.");
        ManagedType<?> current = ownerType;
        for (int i = 0; i < path.length - 1; i++) {
            Attribute<?, ?> hop = attribute(current, path[i], grouping);
            if (!(hop instanceof SingularAttribute<?, ?> singular) || !singular.isAssociation()) {
                throw invalid(grouping, "'" + path[i] + "' of nodes path must be a to-one association");
            }
            current = managedType(metamodel, singular.getJavaType(), grouping);
        }
        Attribute<?, ?> last = attribute(current, path[path.length - 1], grouping);
        if (!(last instanceof PluralAttribute<?, ?, ?> plural)
                || plural.getElementType().getPersistenceType() != Type.PersistenceType.ENTITY) {
            throw invalid(grouping, "nodes path must end in a to-many association of entities");
        }

        Class<?> nodeClass = plural.getElementType().getJavaType();
        EntityType<?> nodeType = entity(metamodel, nodeClass, grouping);
        String nodeId = singleIdAttribute(nodeType, grouping);

        Attribute<?, ?> parent = attribute(nodeType, grouping.parent(), grouping);
        if (!(parent instanceof SingularAttribute<?, ?> parentAttr) || !parentAttr.isAssociation()
                || !parentAttr.getJavaType().isAssignableFrom(nodeClass)) {
            throw invalid(grouping, "parent '" + grouping.parent() + "' must be a to-one association of "
                    + nodeClass.getSimpleName() + " to itself");
        }
        Attribute<?, ?> label = attribute(nodeType, grouping.label(), grouping);
        if (!(label instanceof SingularAttribute<?, ?> labelAttr) || labelAttr.isAssociation()
                || !String.class.equals(labelAttr.getJavaType())) {
            throw invalid(grouping, "label '" + grouping.label() + "' must be a String attribute of "
                    + nodeClass.getSimpleName());
        }
        // wrapper type (never a primitive): the id sub-queries are compared with id paths by Hibernate's type check
        Class<?> nodeIdType = ClassUtils.resolvePrimitiveIfNecessary(nodeType.getIdType().getJavaType());
        return new ResolvedHierarchy(ownerClass, ownerId, path, nodeClass, nodeId,
                nodeIdType, grouping.parent(), grouping.label());
    }

    private static EntityType<?> entity(Metamodel metamodel, Class<?> type, HierarchyGrouping grouping) {
        try {
            return metamodel.entity(type);
        } catch (IllegalArgumentException notAnEntity) {
            throw invalid(grouping, type.getSimpleName() + " is not an entity");
        }
    }

    private static ManagedType<?> managedType(Metamodel metamodel, Class<?> type, HierarchyGrouping grouping) {
        try {
            return metamodel.managedType(type);
        } catch (IllegalArgumentException notManaged) {
            throw invalid(grouping, type.getSimpleName() + " is not a managed type");
        }
    }

    private static Attribute<?, ?> attribute(ManagedType<?> type, String name, HierarchyGrouping grouping) {
        try {
            return type.getAttribute(name);
        } catch (IllegalArgumentException unknown) {
            throw invalid(grouping, "unknown attribute '" + name + "' on " + type.getJavaType().getSimpleName());
        }
    }

    private static String singleIdAttribute(EntityType<?> type, HierarchyGrouping grouping) {
        if (!type.hasSingleIdAttribute()) {
            throw invalid(grouping, "composite identifiers are not supported (" + type.getJavaType().getSimpleName() + ")");
        }
        for (SingularAttribute<?, ?> attribute : type.getSingularAttributes()) {
            if (attribute.isId()) {
                return attribute.getName();
            }
        }
        throw invalid(grouping, type.getJavaType().getSimpleName() + " has no id attribute");
    }

    private static IllegalStateException invalid(HierarchyGrouping grouping, String reason) {
        return new IllegalStateException("Invalid " + grouping + ": " + reason);
    }

    private record Key(Class<?> owner, HierarchyGrouping grouping) {
    }

    /** Metamodel facts the query needs; immutable. */
    record ResolvedHierarchy(Class<?> ownerClass, String ownerId, String[] nodePath, Class<?> nodeClass,
                             String nodeId, Class<?> nodeIdType, String parent, String label) {
        ResolvedHierarchy {
            nodePath = nodePath.clone();
        }

        @Override
        public String[] nodePath() {
            return nodePath.clone();
        }
    }
}
