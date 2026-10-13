package ir.sinqro.enigma.specification;


import ir.sinqro.enigma.annotation.DisableSpecificationQuery;
import ir.sinqro.enigma.annotation.DtoMapper;
import ir.sinqro.enigma.annotation.Mapper;
import ir.sinqro.enigma.model.FieldMeta;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class FieldMetaRegistry {

    private final Map<Class<?>, Map<String, FieldMeta>> cache = new ConcurrentHashMap<>();

    public Class<?> resolveEntityClass(Class<?> dtoOrEntity) {
        if (dtoOrEntity == null) {
            return null;
        }
        DtoMapper ann = dtoOrEntity.getAnnotation(DtoMapper.class);
        return ann != null ? ann.value() : dtoOrEntity;
    }

    public Map<String, FieldMeta> getFieldMeta(Class<?> dtoOrEntity) {
        if (dtoOrEntity == null) {
            return Map.of();
        }
        Class<?> entityClass = resolveEntityClass(dtoOrEntity);
        return cache.computeIfAbsent(dtoOrEntity, cls -> {
            Map<String, FieldMeta> canonical = new LinkedHashMap<>();
            Map<String, FieldMeta> shortAliases = new LinkedHashMap<>();
            Set<String> ambiguousShortNames = new HashSet<>();
            collectFields(cls, "", canonical, shortAliases, entityClass, Set.of(), ambiguousShortNames, false);

            Map<String, FieldMeta> map = new LinkedHashMap<>(canonical);
            for (Map.Entry<String, FieldMeta> e : shortAliases.entrySet()) {
                String shortName = e.getKey();
                if (ambiguousShortNames.contains(shortName)) {
                    continue;
                }
                map.putIfAbsent(shortName, e.getValue());
            }
            return Collections.unmodifiableMap(map);
        });
    }

    /**
     * Fields usable as a flat, single-valued tuple projection column: allowed
     * (not {@code @DisableSpecificationQuery}) AND not itself, nor nested under,
     * a to-many association. A to-many association field (e.g. {@code addresses})
     * or any of its nested members (e.g. {@code addresses.province}) cannot be
     * represented as one column in a parent-row tuple, so it is excluded here.
     * Such fields remain usable for filtering/sorting via {@link #requireAllowed}.
     * <p>
     * Also excludes any field whose {@code entityPath} is itself a "container"
     * for other registered entityPaths — i.e. a single-valued association field
     * such as {@code address} when {@code address.province}, {@code address.city}
     * etc. are also registered (nested DTO, e.g. {@code AddressDto address;}).
     * Selecting {@code root.get("address")} directly as a flat tuple column would
     * return the associated ENTITY (not a value compatible with the DTO's field
     * type), and combined with its own nested leaf columns would also duplicate
     * data in the query. Only the leaf/scalar entityPaths are real projectable
     * columns; the association itself is just a path prefix used to reach them.
     */
    public List<FieldMeta> getProjectionFieldMetas(Class<?> dtoOrEntity) {
        if (dtoOrEntity == null) {
            return List.of();
        }
        Map<String, FieldMeta> meta = getFieldMeta(dtoOrEntity);

        Set<String> containerEntityPaths = collectContainerEntityPaths(meta.values());

        LinkedHashSet<String> seenPaths = new LinkedHashSet<>();
        List<FieldMeta> result = new ArrayList<>();
        for (FieldMeta m : meta.values()) {
            if (!m.isCollection() && !containerEntityPaths.contains(m.entityPath()) && seenPaths.add(m.entityPath())) {
                result.add(m);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Returns the set of entityPaths that are a strict ancestor (dot-separated
     * prefix) of at least one other registered entityPath — these are
     * "container" paths (e.g. {@code address} is the container of
     * {@code address.province}) and must never be selected as a flat, scalar
     * tuple column themselves.
     */
    private Set<String> collectContainerEntityPaths(Collection<FieldMeta> metas) {
        Set<String> containers = new HashSet<>();
        for (FieldMeta m : metas) {
            String path = m.entityPath();
            int dotIndex = path.lastIndexOf('.');
            while (dotIndex > 0) {
                containers.add(path.substring(0, dotIndex));
                dotIndex = path.lastIndexOf('.', dotIndex - 1);
            }
        }
        return containers;
    }

    public FieldMeta requireAllowed(String clientField, Class<?> dtoOrEntity, String usage) {
        if (clientField == null || clientField.isBlank()) {
            throw new IllegalArgumentException(usage + " field must not be blank");
        }
        if (dtoOrEntity == null) {
            return new FieldMeta(clientField, clientField, false, null, false);
        }

        Map<String, FieldMeta> allowed = getFieldMeta(dtoOrEntity);
        FieldMeta meta = allowed.get(clientField);
        if (meta == null) {
            Class<?> entityClass = resolveEntityClass(dtoOrEntity);
            throw new IllegalArgumentException(
                    "Field '" + clientField + "' is not part of the declared projection"
                            + (entityClass != null ? " for " + entityClass.getSimpleName() : "")
                            + " and cannot be used for " + usage);
        }
        if (meta.disabled()) {
            String reason = meta.disableReason() != null && !meta.disableReason().isBlank()
                    ? ": " + meta.disableReason()
                    : "";
            throw new IllegalArgumentException(
                    "Field '" + clientField + "' is marked with @DisableSpecificationQuery"
                            + reason + " and cannot be used for " + usage);
        }
        return meta;
    }

    // -------------------------------------------------------------------------

    private void collectFields(
            Class<?> clazz,
            String prefix,
            Map<String, FieldMeta> canonical,
            Map<String, FieldMeta> shortAliases,
            Class<?> entityClass,
            Set<Class<?>> visited,
            Set<String> ambiguousShortNames,
            boolean underCollection) {

        if (clazz == null || clazz == Object.class || visited.contains(clazz)) {
            return;
        }

        Set<Class<?>> pathVisited = new HashSet<>(visited);
        pathVisited.add(clazz);

        collectFields(clazz.getSuperclass(), prefix, canonical, shortAliases, entityClass, pathVisited, ambiguousShortNames, underCollection);

        for (Field f : clazz.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                continue;
            }

            String dtoName = prefix.isEmpty() ? f.getName() : prefix + "." + f.getName();

            Mapper mapper = f.getAnnotation(Mapper.class);
            String entityPath = (mapper != null && mapper.value() != null && !mapper.value().isBlank())
                    ? mapper.value()
                    : dtoName;


            DisableSpecificationQuery disable = f.getAnnotation(DisableSpecificationQuery.class);
            if (disable == null && entityClass != null) {
                disable = findDisableAlongPath(entityClass, entityPath);
            }

            boolean isDisabled = disable != null;
            String reason = isDisabled ? disable.reason() : null;

            boolean fieldIsCollection = isCollectionLike(f.getType());

            boolean isUnderCollection = underCollection || fieldIsCollection;

            FieldMeta meta = new FieldMeta(dtoName, entityPath, isDisabled, reason, isUnderCollection);

            canonical.put(dtoName, meta);

            String shortName = f.getName();
            FieldMeta existingShort = shortAliases.get(shortName);
            if (existingShort == null) {
                shortAliases.put(shortName, meta);
            } else if (!existingShort.entityPath().equals(entityPath)) {
                ambiguousShortNames.add(shortName);
            }

            Class<?> recurseType = fieldIsCollection ? resolveNextType(f) : f.getType();
            if (isComplexType(recurseType)) {
                collectFields(recurseType, dtoName, canonical, shortAliases, entityClass, pathVisited, ambiguousShortNames, isUnderCollection);
            }
        }
    }

    private boolean isCollectionLike(Class<?> type) {
        return type != null && (Collection.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)
                || type.isArray());
    }

    private boolean isComplexType(Class<?> type) {
        if (type == null || type.isPrimitive() || type.isEnum() || type.isArray()) {
            return false;
        }
        String name = type.getName();
        if (name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("jakarta.")) {
            return false;
        }
        if (type == LocalDateTime.class || type == LocalDate.class || type == LocalTime.class
                || type == Instant.class || type == Date.class || type == Calendar.class
                || type == OffsetDateTime.class || type == ZonedDateTime.class) {
            return false;
        }
        if (Number.class.isAssignableFrom(type)
                || CharSequence.class.isAssignableFrom(type)
                || Boolean.class.isAssignableFrom(type)
                || Character.class.isAssignableFrom(type)) {
            return false;
        }
        return true;
    }

    private DisableSpecificationQuery findDisableAlongPath(Class<?> entityClass, String entityPath) {
        try {
            String[] parts = entityPath.split("\\.");
            Class<?> current = entityClass;
            for (int i = 0; i < parts.length; i++) {
                Field target = getFieldFromHierarchy(current, parts[i]);
                if (target == null) {
                    return null;
                }
                DisableSpecificationQuery onSegment = target.getAnnotation(DisableSpecificationQuery.class);
                if (onSegment != null) {
                    return onSegment;
                }
                if (i < parts.length - 1) {
                    current = resolveNextType(target);
                    if (current == null) {
                        return null;
                    }
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private Class<?> resolveNextType(Field field) {
        Class<?> rawType = field.getType();

        if (rawType.isArray()) {
            return rawType.getComponentType();
        }

        if (Collection.class.isAssignableFrom(rawType) || Map.class.isAssignableFrom(rawType)) {
            Type genericType = field.getGenericType();
            if (!(genericType instanceof ParameterizedType parameterizedType)) {
                return null;
            }

            Type[] typeArgs = parameterizedType.getActualTypeArguments();
            if (typeArgs.length == 0) {
                return null;
            }

            Type elementType = Map.class.isAssignableFrom(rawType)
                    ? typeArgs[Math.min(1, typeArgs.length - 1)]
                    : typeArgs[0];

            return extractRawClass(elementType);
        }

        return rawType;
    }

    private Class<?> extractRawClass(Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType nestedParameterized) {
            Type rawType = nestedParameterized.getRawType();
            return rawType instanceof Class<?> rawClass ? rawClass : null;
        }
        return null;
    }

    private Field getFieldFromHierarchy(Class<?> clazz, String name) {
        Class<?> c = clazz;
        while (c != null && c != Object.class) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        return null;
    }
}