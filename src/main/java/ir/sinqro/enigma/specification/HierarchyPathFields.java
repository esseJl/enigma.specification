package ir.sinqro.enigma.specification;

import ir.sinqro.enigma.annotation.HierarchyPath;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the DTO fields annotated with {@link HierarchyPath} (superclass fields included) and checks
 * that each one can hold a {@code List}. Results are cached per class; thread-safe.
 */
public final class HierarchyPathFields {

    private static final Map<Class<?>, List<String>> CACHE = new ConcurrentHashMap<>();

    private HierarchyPathFields() {
    }

    /** Names of the {@link HierarchyPath} fields of {@code dtoClass}; empty if there are none. */
    public static List<String> of(Class<?> dtoClass) {
        return CACHE.computeIfAbsent(dtoClass, HierarchyPathFields::scan);
    }

    private static List<String> scan(Class<?> dtoClass) {
        List<String> names = new ArrayList<>();
        for (Class<?> c = dtoClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.isAnnotationPresent(HierarchyPath.class)) {
                    continue;
                }
                if (!field.getType().isAssignableFrom(List.class)) {
                    throw new IllegalStateException("@HierarchyPath field " + c.getSimpleName() + "." + field.getName()
                            + " must be declared as List<HierarchyNode> or Collection<HierarchyNode> (was "
                            + field.getType().getSimpleName() + ")");
                }
                names.add(field.getName());
            }
        }
        return List.copyOf(names);
    }
}
