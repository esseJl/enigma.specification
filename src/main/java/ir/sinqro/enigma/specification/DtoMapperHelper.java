package ir.sinqro.enigma.specification;

import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.model.FieldMeta;
import jakarta.persistence.Tuple;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


public class DtoMapperHelper {

    private final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Field>> fieldCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Class<?>, Constructor<?>> canonicalCtorCache = new ConcurrentHashMap<>();

    public <D> D mapTupleToDto(Tuple tuple, Class<D> dtoClass, List<FieldMeta> metas) {
        return mapTupleToDto(tuple, dtoClass, metas, Map.of());
    }

    /**
     * Same as {@link #mapTupleToDto(Tuple, Class, List)}, but {@code overrides} (DTO field name to
     * value, e.g. already-loaded collections) take precedence over tuple columns.
     */
    public <D> D mapTupleToDto(Tuple tuple, Class<D> dtoClass, List<FieldMeta> metas,
                               Map<String, Object> overrides) {
        if (dtoClass.isRecord()) {
            try {
                return mapToRecord(tuple, dtoClass, metas, overrides);
            } catch (Exception e) {
                throw new QueryRuleException(
                        "Failed to map Tuple to DTO record: " + dtoClass.getSimpleName()
                                + ". Cause: " + rootMessage(e), e);
            }
        }

        D instance;
        try {
            instance = createInstance(dtoClass);
        } catch (Exception e) {
            throw new QueryRuleException(
                    "Failed to instantiate DTO: " + dtoClass.getSimpleName()
                            + ". Ensure it has a no-arg constructor or a @SuperBuilder-generated builder(). "
                            + "Cause: " + rootMessage(e), e);
        }

        Set<String> allNullNestedPrefixes = allNullNestedPrefixes(tuple, metas);

        for (int i = 0; i < metas.size(); i++) {
            String dtoName = metas.get(i).dtoName();
            if (isUnderAllNullPrefix(dtoName, allNullNestedPrefixes)) {
                continue;
            }
            Object value = tuple.get(i);
            try {
                setFieldValue(instance, dtoName, value);
            } catch (Exception e) {
                String actualType = value != null ? value.getClass().getName() : "null";
                throw new QueryRuleException(
                        "Failed to map Tuple to DTO: " + dtoClass.getSimpleName()
                                + ". Could not set field '" + dtoName + "' (entityPath '"
                                + metas.get(i).entityPath() + "') with a value of runtime type " + actualType
                                + ". This usually means the DTO field's declared type does not match the actual "
                                + "column/association type — e.g. declaring `String` for a field mapped from a "
                                + "`Long`/`UUID` entity property. Fix the field's type in " + dtoClass.getSimpleName()
                                + " to match. Cause: " + rootMessage(e), e);
            }
        }
        for (Map.Entry<String, Object> override : overrides.entrySet()) {
            try {
                setFieldValue(instance, override.getKey(), override.getValue());
            } catch (Exception e) {
                throw new QueryRuleException(
                        "Failed to map Tuple to DTO: " + dtoClass.getSimpleName()
                                + ". Could not set projected collection '" + override.getKey()
                                + "'. Cause: " + rootMessage(e), e);
            }
        }
        return instance;
    }

    /**
     * Builds a DTO (record or mutable class) from values keyed by DTO field name. Missing record
     * components become {@code null} (or the primitive default).
     */
    public <D> D instantiate(Class<D> dtoClass, Map<String, Object> valuesByField) {
        try {
            if (dtoClass.isRecord()) {
                RecordComponent[] components = dtoClass.getRecordComponents();
                Object[] args = new Object[components.length];
                for (int i = 0; i < components.length; i++) {
                    args[i] = valuesByField.get(components[i].getName());
                }
                return newRecord(dtoClass, components, args);
            }
            D instance = createInstance(dtoClass);
            for (Map.Entry<String, Object> e : valuesByField.entrySet()) {
                setFieldValue(instance, e.getKey(), e.getValue());
            }
            return instance;
        } catch (QueryRuleException e) {
            throw e;
        } catch (Exception e) {
            throw new QueryRuleException("Failed to instantiate DTO " + dtoClass.getSimpleName()
                    + ". Cause: " + rootMessage(e), e);
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.getClass().getSimpleName();
    }

    private Set<String> allNullNestedPrefixes(Tuple tuple, List<FieldMeta> metas) {
        Map<String, Boolean> prefixAllNullSoFar = new LinkedHashMap<>();
        for (int i = 0; i < metas.size(); i++) {
            String dtoName = metas.get(i).dtoName();
            boolean isNull = (tuple.get(i) == null);
            String[] parts = dtoName.split("\\.");
            StringBuilder prefixBuilder = new StringBuilder();
            for (int p = 0; p < parts.length - 1; p++) {
                if (!prefixBuilder.isEmpty()) {
                    prefixBuilder.append('.');
                }
                prefixBuilder.append(parts[p]);
                String prefix = prefixBuilder.toString();
                boolean allNullSoFar = prefixAllNullSoFar.getOrDefault(prefix, true);
                prefixAllNullSoFar.put(prefix, allNullSoFar && isNull);
            }
        }
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, Boolean> e : prefixAllNullSoFar.entrySet()) {
            if (Boolean.TRUE.equals(e.getValue())) {
                result.add(e.getKey());
            }
        }
        return result;
    }

    private boolean isUnderAllNullPrefix(String dtoName, Set<String> allNullPrefixes) {
        if (allNullPrefixes.isEmpty()) {
            return false;
        }
        String[] parts = dtoName.split("\\.");
        StringBuilder prefixBuilder = new StringBuilder();
        for (int p = 0; p < parts.length - 1; p++) {
            if (!prefixBuilder.isEmpty()) {
                prefixBuilder.append('.');
            }
            prefixBuilder.append(parts[p]);
            if (allNullPrefixes.contains(prefixBuilder.toString())) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private <D> D mapToRecord(Tuple tuple, Class<D> dtoClass, List<FieldMeta> metas,
                              Map<String, Object> overrides) throws Exception {
        RecordComponent[] components = dtoClass.getRecordComponents();
        Object[] args = new Object[components.length];


        Map<String, Integer> nameToIndex = new LinkedHashMap<>(metas.size());
        for (int i = 0; i < metas.size(); i++) {
            nameToIndex.put(metas.get(i).dtoName(), i);
        }

        for (int i = 0; i < components.length; i++) {
            String name = components[i].getName();
            Integer idx = nameToIndex.get(name);
            if (idx == null) {
                String matchedKey = null;
                for (Map.Entry<String, Integer> e : nameToIndex.entrySet()) {
                    if (e.getKey().endsWith("." + name)) {
                        if (matchedKey != null) {
                            throw new QueryRuleException(
                                    "Ambiguous mapping for record component '" + name
                                            + "' of " + dtoClass.getSimpleName()
                                            + "': multiple projected fields end with '." + name
                                            + "' (at least '" + matchedKey + "' and '" + e.getKey()
                                            + "'). Rename the record component to match the full "
                                            + "field path, or use a distinct short name.");
                        }
                        matchedKey = e.getKey();
                        idx = e.getValue();
                    }
                }
            }
            args[i] = idx != null ? tuple.get(idx) : null;
            if (overrides.containsKey(name)) {
                args[i] = overrides.get(name);
            }
        }

        return newRecord(dtoClass, components, args);
    }

    @SuppressWarnings("unchecked")
    private <D> D newRecord(Class<D> dtoClass, RecordComponent[] components, Object[] args) throws Exception {
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null && components[i].getType().isPrimitive()) {
                args[i] = Array.get(Array.newInstance(components[i].getType(), 1), 0);
            }
        }

        Constructor<D> ctor = (Constructor<D>) canonicalCtorCache.computeIfAbsent(dtoClass, cls -> {
            try {
                Class<?>[] paramTypes = new Class<?>[components.length];
                for (int i = 0; i < components.length; i++) {
                    paramTypes[i] = components[i].getType();
                }
                Constructor<?> canonical = cls.getDeclaredConstructor(paramTypes);
                canonical.setAccessible(true);
                return canonical;
            } catch (NoSuchMethodException e) {
                throw new RuntimeException("Canonical constructor not found for record: " + cls.getName(), e);
            }
        });

        return ctor.newInstance(args);
    }

    private <D> D createInstance(Class<D> dtoClass) throws Exception {
        // no-arg constructor
        try {
            var ctor = dtoClass.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (NoSuchMethodException ignored) {
            // try builder
        }
        try {
            var builderMethod = dtoClass.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            var buildMethod = builder.getClass().getMethod("build");
            @SuppressWarnings("unchecked")
            D result = (D) buildMethod.invoke(builder);
            return result;
        } catch (NoSuchMethodException e) {
            throw new NoSuchMethodException(
                    "No suitable constructor or builder found for DTO: " + dtoClass.getName()
                            + ". Add @NoArgsConstructor or make sure @SuperBuilder is present. "
                            + "Note: builder-based DTOs with @NonNull required fields may still fail here, "
                            + "since fields are set reflectively AFTER build() — prefer @NoArgsConstructor DTOs or records.");
        }
    }

    private void setFieldValue(Object target, String fieldPath, Object value) throws Exception {
        String[] parts = fieldPath.split("\\.");
        Object current = target;

        for (int i = 0; i < parts.length - 1; i++) {
            Field f = getCachedField(current.getClass(), parts[i]);
            f.setAccessible(true);
            requireSingleValuedNestedType(f, fieldPath);
            requireMutableField(f, fieldPath);
            Object nested = f.get(current);
            if (nested == null) {
                nested = f.getType().getDeclaredConstructor().newInstance();
                f.set(current, nested);
            }
            current = nested;
        }

        Field last = getCachedField(current.getClass(), parts[parts.length - 1]);
        last.setAccessible(true);
        requireMutableField(last, fieldPath);
        last.set(current, value);
    }

    private void requireSingleValuedNestedType(Field f, String fieldPath) {
        Class<?> type = f.getType();
        if (Collection.class.isAssignableFrom(type)
                || Map.class.isAssignableFrom(type)
                || type.isArray()
                || type.isInterface()) {
            throw new QueryRuleException(
                    "Cannot map field path '" + fieldPath + "': '" + f.getName()
                            + "' is a to-many/collection or interface-typed field and cannot be populated "
                            + "from a flat tuple projection. Exclude it from the DTO's flat projection and "
                            + "populate it separately.");
        }
    }

    private void requireMutableField(Field f, String fieldPath) {
        if (Modifier.isFinal(f.getModifiers())) {
            throw new QueryRuleException(
                    "Cannot map field path '" + fieldPath + "': field '" + f.getName()
                            + "' on " + f.getDeclaringClass().getSimpleName() + " is declared 'final'. "
                            + "Reflectively assigning a final instance field after construction is unsafe under "
                            + "the Java Memory Model — other threads are not guaranteed to observe the assigned "
                            + "value and may silently read a stale/default one. Use a no-arg constructor with "
                            + "mutable (non-final) fields, or — preferably — make the DTO a Java record so all "
                            + "components are bound safely through the canonical constructor.");
        }
    }

    private Field getCachedField(Class<?> clazz, String name) {
        return fieldCache
                .computeIfAbsent(clazz, c -> new ConcurrentHashMap<>())
                .computeIfAbsent(name, n -> {
                    try {
                        return getFieldFromHierarchy(clazz, n);
                    } catch (NoSuchFieldException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private Field getFieldFromHierarchy(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            try {
                Field f = current.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        assert clazz != null;
        throw new NoSuchFieldException(
                "Field '" + fieldName + "' not found in class hierarchy of " + clazz.getName());
    }
}