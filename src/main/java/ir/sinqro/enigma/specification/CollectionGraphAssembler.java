package ir.sinqro.enigma.specification;

import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.specification.CollectionProjectionPlan.CollectionPlan;
import ir.sinqro.enigma.specification.CollectionProjectionPlan.ContainerKind;
import ir.sinqro.enigma.specification.CollectionProjectionPlan.DtoElement;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Persistence-agnostic core of {@link CollectionProjectionLoader}: walks a
 * {@link CollectionProjectionPlan} (which may be recursive), asks a {@link LevelFetcher} for the
 * rows of each collection level and assembles the element DTOs.
 *
 * <h3>Loading (phase 1): breadth-first, memoised</h3>
 * Work is keyed by (collection plan instance, owner entity type). Each (key, owner id) pair is
 * fetched at most once per request, and pending ids of the same key are coalesced into one
 * batched fetch (chunked by {@code batchSize}). A recursive collection is therefore loaded with
 * one query per tree level (per chunk), never per row, and loading always terminates - even on
 * cyclic data - because the set of (key, id) pairs is finite. {@code maxDepth} bounds the number
 * of nested levels that may hold elements (top-level collection = level 1); deeper data fails the
 * request instead of being silently truncated. One extra level is probed to tell the two apart.
 *
 * <h3>Assembly (phase 2): depth-first with cycle cut</h3>
 * If the data itself contains a cycle (e.g. a category that is its own ancestor, or
 * {@code A.related -> B.related -> A}), the same collection would be expanded again for the same
 * owner on the current path. That repeated expansion is returned empty: the element is still
 * emitted (the edge is real), but its collection is not expanded a second time. Elements reached
 * through different paths (DAGs, many-to-many) are emitted fully on each path; the total number
 * of produced elements is bounded by {@code maxElements}.
 * <p>
 * Not thread-safe; create one instance per request (it is cheap).
 */
final class CollectionGraphAssembler {

    /** One element row: the element's id (null if it has none) and either a value or field values. */
    record ElementRow(Object id, Object value, Map<String, Object> fields) {
        static ElementRow scalar(Object value) {
            return new ElementRow(null, value, Map.of());
        }

        static ElementRow dto(Object id, Map<String, Object> fields) {
            return new ElementRow(id, null, fields);
        }
    }

    /**
     * Result of fetching one collection for a chunk of owners. {@code elementEntity} is the entity
     * type of the elements (null for simple values / embeddables); {@code rowsByOwner} keeps the
     * row order of the query per owner.
     */
    record FetchedLevel(Class<?> elementEntity, Map<Object, List<ElementRow>> rowsByOwner) {
    }

    @FunctionalInterface
    interface LevelFetcher {
        FetchedLevel fetch(CollectionPlan plan, DtoElement element, Class<?> ownerEntity, List<Object> ownerIds);
    }

    /** Identity of a collection plan node (records compare by value; we need the instance). */
    private record PlanKey(CollectionPlan plan, Class<?> ownerEntity) {
        @Override
        public boolean equals(Object o) {
            return o instanceof PlanKey k && k.plan == plan && k.ownerEntity == ownerEntity;
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(plan) + System.identityHashCode(ownerEntity);
        }
    }

    private record Expansion(PlanKey key, Object ownerId) {
    }

    private static final class Level {
        Class<?> elementEntity;
        boolean elementEntityKnown;
        final Set<Object> loadedOwners = new HashSet<>();
        final Map<Object, List<ElementRow>> rows = new HashMap<>();
    }

    private static final class Pending {
        final LinkedHashSet<Object> ids = new LinkedHashSet<>();
        int depth;

        Pending(int depth) {
            this.depth = depth;
        }
    }

    private final CollectionProjectionPlan plan;
    private final LevelFetcher fetcher;
    private final BiFunction<Class<?>, Map<String, Object>, Object> instantiator;
    private final int batchSize;
    private final int maxDepth;
    private final int maxElements;

    private final Map<PlanKey, Level> levels = new HashMap<>();
    private int producedElements;

    CollectionGraphAssembler(CollectionProjectionPlan plan,
                             LevelFetcher fetcher,
                             BiFunction<Class<?>, Map<String, Object>, Object> instantiator,
                             int batchSize, int maxDepth, int maxElements) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
        this.instantiator = Objects.requireNonNull(instantiator, "instantiator");
        if (batchSize <= 0 || maxDepth <= 0 || maxElements <= 0) {
            throw new IllegalArgumentException("batchSize, maxDepth and maxElements must be > 0");
        }
        this.batchSize = batchSize;
        this.maxDepth = maxDepth;
        this.maxElements = maxElements;
    }

    /**
     * @return ownerId to (dtoField to container) for every owner in {@code ownerIds} (null ids are
     * ignored); owners without elements get empty containers.
     */
    Map<Object, Map<String, Object>> load(Class<?> ownerEntity, Collection<?> ownerIds) {
        List<Object> roots = new ArrayList<>(new LinkedHashSet<>(ownerIds));
        roots.removeIf(Objects::isNull);
        if (!plan.hasCollections() || roots.isEmpty()) {
            return Map.of();
        }
        fetchAll(ownerEntity, roots);

        Map<Object, Map<String, Object>> result = new LinkedHashMap<>();
        Set<Expansion> path = new HashSet<>();
        for (Object rootId : roots) {
            Map<String, Object> values = new LinkedHashMap<>();
            for (CollectionPlan cp : plan.collections()) {
                values.put(cp.dtoField(), assemble(cp, ownerEntity, rootId, path));
            }
            result.put(rootId, values);
        }
        return result;
    }

    // ------------------------------------------------------------------ phase 1: fetch

    private void fetchAll(Class<?> rootEntity, List<Object> rootIds) {
        LinkedHashMap<PlanKey, Pending> queue = new LinkedHashMap<>();
        for (CollectionPlan cp : plan.collections()) {
            enqueue(queue, new PlanKey(cp, rootEntity), rootIds, 1);
        }
        while (!queue.isEmpty()) {
            Iterator<Map.Entry<PlanKey, Pending>> it = queue.entrySet().iterator();
            Map.Entry<PlanKey, Pending> next = it.next();
            it.remove();
            PlanKey key = next.getKey();
            Pending pending = next.getValue();
            if (pending.ids.isEmpty()) {
                continue;
            }

            Level level = levels.computeIfAbsent(key, k -> new Level());
            List<Object> todo = new ArrayList<>(pending.ids.size());
            for (Object id : pending.ids) {
                if (level.loadedOwners.add(id)) {
                    todo.add(id);
                }
            }
            if (todo.isEmpty()) {
                continue;
            }

            DtoElement element = plan.dtoElementOf(key.plan());
            for (int from = 0; from < todo.size(); from += batchSize) {
                List<Object> chunk = List.copyOf(todo.subList(from, Math.min(from + batchSize, todo.size())));
                FetchedLevel fetched = fetcher.fetch(key.plan(), element, key.ownerEntity(), chunk);
                merge(level, key.plan(), fetched);
            }
            if (pending.depth > maxDepth && hasRows(level, todo)) {
                // Probed one level past the limit (to tell "exactly maxDepth deep" from "deeper").
                throw depthExceeded(key.plan());
            }

            if (element == null || element.nested().isEmpty() || level.elementEntity == null) {
                continue;
            }
            List<Object> elementIds = new ArrayList<>();
            Set<Object> seen = new HashSet<>();
            for (Object owner : todo) {
                for (ElementRow row : level.rows.getOrDefault(owner, List.of())) {
                    if (row.id() != null && seen.add(row.id())) {
                        elementIds.add(row.id());
                    }
                }
            }
            if (elementIds.isEmpty()) {
                continue;
            }
            for (CollectionPlan nested : element.nested()) {
                enqueue(queue, new PlanKey(nested, level.elementEntity), elementIds, pending.depth + 1);
            }
        }
    }

    private static boolean hasRows(Level level, List<Object> owners) {
        for (Object owner : owners) {
            List<ElementRow> rows = level.rows.get(owner);
            if (rows != null && !rows.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void enqueue(LinkedHashMap<PlanKey, Pending> queue, PlanKey key, List<Object> ids, int depth) {
        Level level = levels.get(key);
        Pending pending = queue.get(key);
        for (Object id : ids) {
            if (level != null && level.loadedOwners.contains(id)) {
                continue;
            }
            if (pending == null) {
                pending = new Pending(depth);
                queue.put(key, pending);
            }
            pending.ids.add(id);
            pending.depth = Math.min(pending.depth, depth);
        }
    }

    private static void merge(Level level, CollectionPlan cp, FetchedLevel fetched) {
        if (!level.elementEntityKnown) {
            level.elementEntity = fetched.elementEntity();
            level.elementEntityKnown = true;
        } else if (level.elementEntity != fetched.elementEntity()) {
            throw new IllegalStateException("Collection '" + cp.dtoField() + "' returned elements of "
                    + fetched.elementEntity() + " and " + level.elementEntity);
        }
        for (Map.Entry<Object, List<ElementRow>> e : fetched.rowsByOwner().entrySet()) {
            level.rows.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).addAll(e.getValue());
        }
    }

    // ------------------------------------------------------------------ phase 2: assemble

    private Object assemble(CollectionPlan cp, Class<?> ownerEntity, Object ownerId, Set<Expansion> path) {
        PlanKey key = new PlanKey(cp, ownerEntity);
        Expansion expansion = new Expansion(key, ownerId);
        if (!path.add(expansion)) {
            // Cyclic data: this very collection is already being expanded for this owner further up.
            return emptyContainer(cp.container());
        }
        try {
            Level level = levels.get(key);
            if (level == null || !level.loadedOwners.contains(ownerId)) {
                throw new IllegalStateException("Collection '" + cp.dtoField() + "' was not loaded for owner "
                        + ownerId + " (" + ownerEntity.getSimpleName() + ")");
            }
            List<ElementRow> rows = level.rows.getOrDefault(ownerId, List.of());
            if (!rows.isEmpty() && path.size() > maxDepth) {
                // A longer path than the breadth-first (shortest) one, e.g. through a DAG.
                throw depthExceeded(cp);
            }
            DtoElement element = plan.dtoElementOf(cp);
            List<Object> out = new ArrayList<>(rows.size());
            for (ElementRow row : rows) {
                if (element == null) {
                    out.add(row.value());
                    continue;
                }
                if (++producedElements > maxElements) {
                    throw new QueryRuleException("Collection '" + cp.dtoField() + "' produces more than " + maxElements
                            + " elements per request (app.specification.spec.max-collection-elements). "
                            + "Request a smaller page or narrow the filter.");
                }
                Map<String, Object> values = new HashMap<>(row.fields());
                for (CollectionPlan nested : element.nested()) {
                    values.put(nested.dtoField(), row.id() == null
                            ? emptyContainer(nested.container())
                            : assemble(nested, level.elementEntity, row.id(), path));
                }
                out.add(instantiator.apply(element.dtoClass(), values));
            }
            return toContainer(cp.container(), out);
        } finally {
            path.remove(expansion);
        }
    }

    private QueryRuleException depthExceeded(CollectionPlan cp) {
        return new QueryRuleException("Collection '" + cp.dtoField() + "' is nested deeper than " + maxDepth
                + " levels (app.specification.spec.max-collection-depth). Increase the limit or narrow the query.");
    }

    static Object emptyContainer(ContainerKind kind) {
        return kind == ContainerKind.SET ? Set.of() : List.of();
    }

    static Object toContainer(ContainerKind kind, List<Object> values) {
        return kind == ContainerKind.SET
                ? Collections.unmodifiableSet(new LinkedHashSet<>(values))
                : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
