package ir.sinqro.enigma.specification;


import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.model.FieldMeta;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.metamodel.Attribute;
import jakarta.persistence.metamodel.PluralAttribute;
import jakarta.persistence.metamodel.Type;
import org.springframework.stereotype.Component;


public class PathResolver {

    private final FieldMetaRegistry fieldMetaRegistry;
    private final EntityManager entityManager;

    public PathResolver(FieldMetaRegistry fieldMetaRegistry, EntityManager entityManager) {
        this.fieldMetaRegistry = fieldMetaRegistry;
        this.entityManager = entityManager;
    }

    public Path<?> resolvePath(Root<?> root, String clientField, Class<?> dtoOrEntity, JoinContext ctx) {
        FieldMeta meta = fieldMetaRegistry.requireAllowed(clientField, dtoOrEntity, "filter");
        return resolvePathWithJoin(root, meta.entityPath(), ctx);
    }



    public Path<?> resolvePathWithJoin(From<?, ?> from, String entityPath, JoinContext ctx) {
        if (entityPath == null || entityPath.isBlank()) {
            throw new QueryRuleException("Path must not be blank");
        }
        if (ctx == null) {
            throw new IllegalArgumentException("JoinContext must not be null");
        }
        if (endsWithBasicCollection(from, entityPath, ctx)) {
            // LEFT, like every other join created by JoinContext#resolve, so the cached joins stay compatible.
            return ctx.resolveFrom(from, entityPath, JoinType.LEFT);
        }
        try {
            return ctx.resolve(from, entityPath);
        } catch (IllegalArgumentException ex) {
            throw new QueryRuleException(
                    "Unknown path segment in field '" + entityPath + "': " + ex.getMessage(), ex);
        }
    }

    public String resolveSortPath(String clientField, Class<?> dtoOrEntity) {
        return fieldMetaRegistry.requireAllowed(clientField, dtoOrEntity, "sorting").entityPath();
    }

    private boolean endsWithBasicCollection(From<?, ?> from, String entityPath, JoinContext ctx) {
        if (entityPath == null || entityPath.isBlank()) {
            return false;
        }
        int lastDot = entityPath.lastIndexOf('.');
        String attributeName = entityPath.substring(lastDot + 1);
        if (attributeName.isBlank()) {
            return false;
        }
        try {
            // Same LEFT joins that PathResolver would create for the leading segments.
            From<?, ?> owner = lastDot < 0 ? from : ctx.resolveFrom(from, entityPath.substring(0, lastDot), JoinType.LEFT);
            Attribute<?, ?> attribute = entityManager.getMetamodel()
                    .managedType(owner.getJavaType())
                    .getAttribute(attributeName);
            return attribute instanceof PluralAttribute<?, ?, ?> plural
                    && plural.getElementType().getPersistenceType() == Type.PersistenceType.BASIC;
        } catch (IllegalArgumentException unknownPath) {
            // Not a mapped attribute: let PathResolver report it exactly as before.
            return false;
        }
    }
}