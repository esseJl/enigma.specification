package ir.sinqro.enigma.specification;


import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.model.FieldMeta;
import jakarta.persistence.criteria.From;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Root;
import org.springframework.stereotype.Component;


@Component
public class PathResolver {

    private final FieldMetaRegistry fieldMetaRegistry;

    public PathResolver(FieldMetaRegistry fieldMetaRegistry) {
        this.fieldMetaRegistry = fieldMetaRegistry;
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
}