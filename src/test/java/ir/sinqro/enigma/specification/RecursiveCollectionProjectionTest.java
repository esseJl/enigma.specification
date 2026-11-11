package ir.sinqro.enigma.specification;

import ir.sinqro.enigma.annotation.DtoMapper;
import ir.sinqro.enigma.annotation.Mapper;
import ir.sinqro.enigma.annotation.ProjectedCollection;
import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.specification.CollectionGraphAssembler.ElementRow;
import ir.sinqro.enigma.specification.CollectionGraphAssembler.FetchedLevel;
import ir.sinqro.enigma.specification.CollectionProjectionPlan.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Planner + assembler behaviour for self-referencing / mutually recursive DTOs, against an
 * in-memory "database" (no JPA): exercises traversal, batching, memoisation, cycle handling and
 * limits deterministically.
 */
class RecursiveCollectionProjectionTest {

    // ------------------------------------------------------------------ fixtures

    static class Category {}          // stands in for the entity
    static class Product {}

    @DtoMapper(Category.class)
    record CategoryDto(Long id, String name, @Mapper("parent.id") Long parentId,
                       @ProjectedCollection(orderBy = "name") @Mapper("children") List<CategoryDto> children) {}

    @DtoMapper(Category.class)
    record TaggedCategoryDto(Long id, String name,
                             @ProjectedCollection Set<String> tags,
                             @ProjectedCollection @Mapper("children") List<TaggedCategoryDto> children) {}

    record NodeB(Long id, String name, @ProjectedCollection List<NodeA> as) {}

    record NodeA(Long id, String name, @ProjectedCollection List<NodeB> bs) {}

    @DtoMapper(Category.class)
    record MutualRoot(Long id, @ProjectedCollection List<NodeA> as) {}

    record Address(String city) {}

    @DtoMapper(Category.class)
    record BadSelfRef(Long id, Address address, @ProjectedCollection List<BadSelfRef> children) {}

    @DtoMapper(Category.class)
    record FlatRootWithObject(Long id, Address address, @ProjectedCollection List<NodeA> as) {}

    private final CollectionProjectionPlanner planner = new CollectionProjectionPlanner();
    private final DtoMapperHelper mapper = new DtoMapperHelper();

    /** owner id -> ordered child ids, per collection field; plus per-id scalar fields. */
    static final class FakeDb {
        final Map<String, Map<Object, List<Long>>> edges = new HashMap<>();
        final Map<Long, Map<String, Object>> fields = new HashMap<>();
        final Map<Long, Set<String>> tags = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        final Map<String, Set<Object>> fetchedOwners = new HashMap<>();

        FakeDb node(long id, String name, Long parent) {
            Map<String, Object> f = new HashMap<>();
            f.put("id", id);
            f.put("name", name);
            f.put("parentId", parent);
            fields.put(id, f);
            if (parent != null) {
                edge("children", parent, id);
            }
            return this;
        }

        FakeDb edge(String field, long owner, long child) {
            edges.computeIfAbsent(field, k -> new HashMap<>()).computeIfAbsent(owner, k -> new ArrayList<>()).add(child);
            return this;
        }

        CollectionGraphAssembler.LevelFetcher fetcher() {
            return (plan, element, ownerEntity, ownerIds) -> {
                calls.add(plan.dtoField() + ownerIds);
                for (Object id : ownerIds) {
                    assertTrue(fetchedOwners.computeIfAbsent(plan.dtoField(), k -> new HashSet<>()).add(id),
                            "owner " + id + " fetched twice for " + plan.dtoField());
                }
                Map<Object, List<ElementRow>> rows = new LinkedHashMap<>();
                if (element == null) { // tags
                    for (Object owner : ownerIds) {
                        for (String t : new TreeSet<>(tags.getOrDefault((Long) owner, Set.of()))) {
                            rows.computeIfAbsent(owner, k -> new ArrayList<>()).add(ElementRow.scalar(t));
                        }
                    }
                    return new FetchedLevel(null, rows);
                }
                for (Object owner : ownerIds) {
                    for (Long child : edges.getOrDefault(plan.dtoField(), Map.of()).getOrDefault(owner, List.of())) {
                        rows.computeIfAbsent(owner, k -> new ArrayList<>()).add(ElementRow.dto(child, fields.get(child)));
                    }
                }
                return new FetchedLevel(Category.class, rows);
            };
        }
    }

    private Map<Object, Map<String, Object>> load(Class<?> dto, FakeDb db, List<Long> roots,
                                                  int batch, int maxDepth, int maxElements) {
        CollectionProjectionPlan plan = planner.planFor(dto);
        db.calls.clear();          // one fake "request" per load
        db.fetchedOwners.clear();
        return new CollectionGraphAssembler(plan, db.fetcher(), mapper::instantiate, batch, maxDepth, maxElements)
                .load(Category.class, roots);
    }

    @SuppressWarnings("unchecked")
    private static List<CategoryDto> children(Map<Object, Map<String, Object>> loaded, long root) {
        return (List<CategoryDto>) loaded.get(root).get("children");
    }

    private static String render(List<CategoryDto> list) {
        StringBuilder sb = new StringBuilder("[");
        for (CategoryDto c : list) {
            if (sb.length() > 1) sb.append(", ");
            sb.append(c.name());
            if (c.children() == null) sb.append("<null>");
            else if (!c.children().isEmpty()) sb.append(render(c.children()));
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ planner

    @Test
    void plansRootSelfReferenceAsRecursiveElement() {
        CollectionProjectionPlan plan = planner.planFor(CategoryDto.class);
        CollectionPlan children = plan.collections().get(0);
        assertEquals("children", children.path());
        assertEquals(new RecursiveElement(CategoryDto.class), children.element());
        assertTrue(plan.isRecursive());

        DtoElement def = plan.dtoElementOf(children);
        assertEquals(CategoryDto.class, def.dtoClass());
        assertEquals(List.of("id", "name", "parent.id"), def.scalars().stream().map(ScalarField::path).toList());
        assertSame(children, def.nested().get(0), "definition must reuse the top-level plan instance");

        // records stay acyclic: these must terminate
        assertNotNull(plan.toString());
        assertEquals(plan.collections().hashCode(), planner.planFor(CategoryDto.class).collections().hashCode());
    }

    @Test
    void plansMutualRecursionBetweenElementTypes() {
        CollectionProjectionPlan plan = planner.planFor(MutualRoot.class);
        DtoElement a = plan.dtoElementOf(plan.collections().get(0));
        assertEquals(NodeA.class, a.dtoClass());
        DtoElement b = plan.dtoElementOf(a.nested().get(0));
        assertEquals(NodeB.class, b.dtoClass());
        CollectionPlan backToA = b.nested().get(0);
        assertEquals(new RecursiveElement(NodeA.class), backToA.element());
        assertSame(a, plan.dtoElementOf(backToA));
    }

    @Test
    void rejectsSelfReferencingRootWithNestedObjectField() {
        QueryRuleException ex = assertThrows(QueryRuleException.class, () -> planner.planFor(BadSelfRef.class));
        assertTrue(ex.getMessage().contains("address"), ex.getMessage());
        // ...but a nested object on a root that is NOT used as an element stays allowed
        assertTrue(planner.planFor(FlatRootWithObject.class).hasCollections());
    }

    // ------------------------------------------------------------------ assembly

    @Test
    void buildsFullTreeWithOneFetchPerLevel() {
        FakeDb db = new FakeDb()
                .node(1, "cat1", null).node(5, "cat2", null).node(6, "cat3", null)
                .node(2, "sub1", 1L).node(3, "sub11", 1L).node(4, "sub111", 3L)
                .node(7, "sub2", 5L).node(8, "sub22", 5L).node(9, "sub3", 6L).node(10, "sub33", 9L);

        var loaded = load(CategoryDto.class, db, List.of(1L, 5L, 6L), 100, 32, 1000);

        assertEquals("[sub1, sub11[sub111]]", render(children(loaded, 1)));
        assertEquals("[sub2, sub22]", render(children(loaded, 5)));
        assertEquals("[sub3[sub33]]", render(children(loaded, 6)));
        CategoryDto sub111 = children(loaded, 1).get(1).children().get(0);
        assertEquals(3L, sub111.parentId());
        assertEquals(List.of(), sub111.children(), "leaf gets an empty list, not null");
        // one fetch per level: children of roots, of depth-1 nodes, probe of depth-2 leaves
        assertEquals(List.of("children[1, 5, 6]", "children[2, 3, 7, 8, 9]", "children[4, 10]"), db.calls);
    }

    @Test
    void chunksEachLevelByBatchSize() {
        FakeDb db = new FakeDb().node(1, "r", null);
        for (long i = 2; i <= 6; i++) db.node(i, "c" + i, 1L);
        load(CategoryDto.class, db, List.of(1L), 2, 32, 1000);
        // [1], then children 2..6 in chunks of 2 -> 3 calls
        assertEquals(List.of("children[1]", "children[2, 3]", "children[4, 5]", "children[6]"), db.calls);
    }

    @Test
    void terminatesOnCyclicDataAndCutsTheRepeatedExpansion() {
        // corrupt data: 1 -> 2 -> 1
        FakeDb db = new FakeDb().node(1, "a", null).node(2, "b", 1L).edge("children", 2, 1);
        var loaded = load(CategoryDto.class, db, List.of(1L, 2L), 10, 32, 1000);
        assertEquals("[b[a]]", render(children(loaded, 1)));
        assertEquals("[a[b]]", render(children(loaded, 2)));
        assertEquals(List.of(), children(loaded, 1).get(0).children().get(0).children());
        assertEquals(List.of("children[1, 2]"), db.calls, "each (collection, owner) fetched once");
    }

    @Test
    void terminatesOnSelfLoop() {
        FakeDb db = new FakeDb().node(1, "self", null).edge("children", 1, 1);
        var loaded = load(CategoryDto.class, db, List.of(1L), 10, 32, 1000);
        assertEquals("[self]", render(children(loaded, 1)));
    }

    @Test
    void depthLimitAllowsExactlyMaxDepthAndRejectsDeeper() {
        FakeDb db = new FakeDb().node(1, "l0", null).node(2, "l1", 1L).node(3, "l2", 2L).node(4, "l3", 3L);
        // 3 levels below the root hold elements
        var ok = load(CategoryDto.class, db, List.of(1L), 10, 3, 1000);
        assertEquals("[l1[l2[l3]]]", render(children(ok, 1)));

        QueryRuleException ex = assertThrows(QueryRuleException.class,
                () -> load(CategoryDto.class, db, List.of(1L), 10, 2, 1000));
        assertTrue(ex.getMessage().contains("max-collection-depth"), ex.getMessage());
    }

    @Test
    void sharedNodesInADagAreExpandedOnEveryPathButFetchedOnce() {
        // many-to-many style: 1 -> {2,3}, 2 -> 4, 3 -> 4, 4 -> 5
        FakeDb db = new FakeDb().node(1, "r", null).node(2, "x", 1L).node(3, "y", 1L).node(4, "shared", 2L)
                .edge("children", 3, 4).node(5, "leaf", 4L);
        var loaded = load(CategoryDto.class, db, List.of(1L), 10, 32, 1000);
        assertEquals("[x[shared[leaf]], y[shared[leaf]]]", render(children(loaded, 1)));
    }

    @Test
    void elementLimitStopsExponentialFanOut() {
        // diamond ladder: each level has 2 nodes that both point to both nodes of the next level
        FakeDb db = new FakeDb().node(0, "root", null);
        long id = 1;
        List<Long> prev = List.of(0L);
        for (int level = 0; level < 20; level++) {
            List<Long> cur = List.of(id, id + 1);
            for (Long p : prev) for (Long c : cur) {
                if (!db.fields.containsKey(c)) db.node(c, "n" + c, null);
                db.edge("children", p, c);
            }
            prev = cur;
            id += 2;
        }
        QueryRuleException ex = assertThrows(QueryRuleException.class,
                () -> load(CategoryDto.class, db, List.of(0L), 10, 64, 10_000));
        assertTrue(ex.getMessage().contains("max-collection-elements"), ex.getMessage());
    }

    @Test
    @SuppressWarnings("unchecked")
    void mixesRecursiveAndScalarCollections() {
        FakeDb db = new FakeDb().node(1, "r", null).node(2, "c", 1L);
        db.tags.put(1L, Set.of("x", "y"));
        db.tags.put(2L, Set.of("z"));
        var loaded = load(TaggedCategoryDto.class, db, List.of(1L), 10, 32, 1000);
        assertEquals(Set.of("x", "y"), loaded.get(1L).get("tags"));
        TaggedCategoryDto child = ((List<TaggedCategoryDto>) loaded.get(1L).get("children")).get(0);
        assertEquals(Set.of("z"), child.tags());
        assertEquals(List.of(), child.children());
    }

    @Test
    @SuppressWarnings("unchecked")
    void loadsMutuallyRecursiveDtos() {
        // root 1 -as-> 10 -bs-> 20 -as-> 10 (cycle through two collections)
        FakeDb db = new FakeDb().node(10, "a10", null).node(20, "b20", null)
                .edge("as", 1, 10).edge("bs", 10, 20).edge("as", 20, 10);
        var loaded = load(MutualRoot.class, db, List.of(1L), 10, 32, 1000);
        NodeA a = ((List<NodeA>) loaded.get(1L).get("as")).get(0);
        NodeB b = a.bs().get(0);
        assertEquals("b20", b.name());
        NodeA again = b.as().get(0);
        assertEquals("a10", again.name());
        assertEquals(List.of(), again.bs(), "cut where (bs, 10) would repeat on the path");
    }

    @Test
    void ownersWithoutElementsGetEmptyContainersAndNullIdsAreIgnored() {
        FakeDb db = new FakeDb().node(1, "lonely", null);
        List<Long> roots = new ArrayList<>(Arrays.asList(1L, null, 1L));
        var loaded = load(CategoryDto.class, db, roots, 10, 32, 1000);
        assertEquals(Set.of(1L), loaded.keySet());
        assertEquals(List.of(), children(loaded, 1));
    }
}
