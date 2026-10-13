package ir.sinqro.enigma.specification;

import ir.sinqro.enigma.annotation.Mapper;
import ir.sinqro.enigma.annotation.ProjectedCollection;
import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.specification.CollectionProjectionPlan.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Reflection-only (no JPA) analysis of a DTO class. Thread-safe: the cache holds immutable
 * plans, built without holding any lock and published via {@code putIfAbsent}, so two threads
 * racing on the first call simply build equal plans and one wins. Invalid DTOs are never
 * cached and fail on every call with the same descriptive error.
 */
public class CollectionProjectionPlanner {

    private static final int MAX_DEPTH = 3;

    private final ConcurrentMap<Class<?>, CollectionProjectionPlan> cache = new ConcurrentHashMap<>();

    public CollectionProjectionPlan planFor(Class<?> dtoClass) {
        Objects.requireNonNull(dtoClass, "dtoClass must not be null");
        CollectionProjectionPlan cached = cache.get(dtoClass);
        if (cached != null) {
            return cached;
        }
        CollectionProjectionPlan built = build(dtoClass);
        CollectionProjectionPlan previous = cache.putIfAbsent(dtoClass, built);
        return previous != null ? previous : built;
    }

    private CollectionProjectionPlan build(Class<?> dtoClass) {
        List<CollectionPlan> plans = new ArrayList<>();
        for (Field f : fieldsOf(dtoClass)) {
            if (f.isAnnotationPresent(ProjectedCollection.class)) {
                plans.add(planCollection(f, Set.of(dtoClass), 1));
            }
        }
        return plans.isEmpty() ? CollectionProjectionPlan.empty() : new CollectionProjectionPlan(plans);
    }

    private CollectionPlan planCollection(Field f, Set<Class<?>> visited, int depth) {
        String where = f.getDeclaringClass().getSimpleName() + "." + f.getName();
        if (depth > MAX_DEPTH) {
            throw new QueryRuleException("@ProjectedCollection nesting is too deep at " + where
                    + " (max " + MAX_DEPTH + ")");
        }
        ContainerKind kind = containerKind(f, where);
        Class<?> elementType = elementType(f, where);
        ProjectedCollection ann = f.getAnnotation(ProjectedCollection.class);
        String path = pathOf(f);

        if (isSimple(elementType)) {
            if (!ann.orderBy().isBlank()) {
                throw new QueryRuleException("orderBy is not supported for simple element type at " + where);
            }
            return new CollectionPlan(f.getName(), path, kind, "", new ScalarElement(elementType));
        }

        if (visited.contains(elementType)) {
            throw new QueryRuleException("Cyclic @ProjectedCollection through " + elementType.getSimpleName()
                    + " at " + where);
        }
        Set<Class<?>> nextVisited = new HashSet<>(visited);
        nextVisited.add(elementType);

        List<ScalarField> scalars = new ArrayList<>();
        List<CollectionPlan> nested = new ArrayList<>();
        for (Field ef : fieldsOf(elementType)) {
            if (ef.isAnnotationPresent(ProjectedCollection.class)) {
                nested.add(planCollection(ef, nextVisited, depth + 1));
            } else if (isCollectionLike(ef.getType())) {
                // not opted in: left unset (null / DTO default), same as top-level behaviour
                continue;
            } else if (isSimple(ef.getType())) {
                scalars.add(new ScalarField(ef.getName(), pathOf(ef), ef.getType()));
            } else {
                throw new QueryRuleException("Unsupported nested object field '" + ef.getName() + "' in collection"
                        + " element " + elementType.getSimpleName() + " (" + where + "). Collection element DTOs"
                        + " may only contain simple fields and @ProjectedCollection fields.");
            }
        }
        if (scalars.isEmpty() && nested.isEmpty()) {
            throw new QueryRuleException("Collection element " + elementType.getSimpleName()
                    + " has no mappable fields (" + where + ")");
        }
        return new CollectionPlan(f.getName(), path, kind, ann.orderBy().trim(),
                new DtoElement(elementType, scalars, nested));
    }

    private static String pathOf(Field f) {
        Mapper m = f.getAnnotation(Mapper.class);
        return (m != null && m.value() != null && !m.value().isBlank()) ? m.value().trim() : f.getName();
    }

    private static ContainerKind containerKind(Field f, String where) {
        Class<?> raw = f.getType();
        if (raw == Set.class) {
            return ContainerKind.SET;
        }
        if (raw == List.class || raw == Collection.class) {
            return ContainerKind.LIST;
        }
        throw new QueryRuleException("@ProjectedCollection field " + where
                + " must be declared as Set, List or Collection (was " + raw.getSimpleName() + ")");
    }

    private static Class<?> elementType(Field f, String where) {
        Type generic = f.getGenericType();
        if (generic instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> element) {
            return element;
        }
        throw new QueryRuleException("@ProjectedCollection field " + where
                + " must have a concrete element type (e.g. Set<RoleDto>)");
    }

    private static boolean isCollectionLike(Class<?> type) {
        return Collection.class.isAssignableFrom(type) || Map.class.isAssignableFrom(type)
                || (type.isArray() && type != byte[].class);
    }

    static boolean isSimple(Class<?> type) {
        if (type.isPrimitive() || type.isEnum() || type == byte[].class) {
            return true;
        }
        if (isCollectionLike(type)) {
            return false;
        }
        String n = type.getName();
        return n.startsWith("java.") || n.startsWith("javax.") || n.startsWith("jakarta.");
    }

    /** Superclass fields first, static/synthetic skipped. */
    private static List<Field> fieldsOf(Class<?> type) {
        List<Field> result = new ArrayList<>();
        collect(type, result);
        return result;
    }

    private static void collect(Class<?> type, List<Field> out) {
        if (type == null || type == Object.class) {
            return;
        }
        collect(type.getSuperclass(), out);
        for (Field f : type.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) {
                out.add(f);
            }
        }
    }
}
