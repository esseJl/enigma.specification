package ir.sinqro.enigma.grouping;

import ir.sinqro.enigma.annotation.DtoMapper;
import ir.sinqro.enigma.annotation.HierarchyPath;
import ir.sinqro.enigma.annotation.Mapper;
import ir.sinqro.enigma.annotation.ProjectedCollection;
import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.model.PageRequest;
import ir.sinqro.enigma.model.PageResponse;
import ir.sinqro.enigma.model.QueryRequest;
import ir.sinqro.enigma.model.SortRequest;
import ir.sinqro.enigma.query.Filters;
import ir.sinqro.enigma.repository.GenericRepository;
import ir.sinqro.enigma.service.QueryService;
import jakarta.persistence.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * End-to-end hierarchy grouping through {@link QueryService} (owner = item, tree = node), on H2.
 * <pre>
 * A ── A1 ── A11
 * B ── B1
 * </pre>
 */
@Transactional
@SpringBootTest(classes = HierarchyGroupingIT.TestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:hierarchy_grouping_it;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class HierarchyGroupingIT {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @org.springframework.boot.persistence.autoconfigure.EntityScan(basePackageClasses = HierarchyGroupingIT.class)
    @EnableJpaRepositories(basePackageClasses = HierarchyGroupingIT.class, considerNestedRepositories = true)
    @Import({ir.sinqro.enigma.autoconfigure.EnigmaAutoConfiguration.class,
            GroupedItemService.class, PlainItemService.class, BrokenGroupingService.class})
    static class TestConfig {
        @Bean
        org.springframework.core.convert.ConversionService mvcConversionService() {
            return new org.springframework.core.convert.support.DefaultConversionService();
        }
    }

    @Entity @Table(name = "grp_node")
    public static class Node {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String name;
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_id") public Node parent;
    }

    @Entity @Table(name = "grp_item")
    public static class Item {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String name;
        @ManyToMany(fetch = FetchType.LAZY)
        @JoinTable(name = "grp_item_node", joinColumns = @JoinColumn(name = "item_id"),
                inverseJoinColumns = @JoinColumn(name = "node_id"))
        public Set<Node> nodes = new HashSet<>();
    }

    public interface ItemRepository extends GenericRepository<Item, Long> {}

    @DtoMapper(Node.class)
    public record NodeDto(Long id, String name) {}

    @DtoMapper(Item.class)
    public record ItemDto(Long id, String name,
                          @ProjectedCollection(orderBy = "name") @Mapper("nodes") Set<NodeDto> nodes,
                          @HierarchyPath List<HierarchyNode> path) {}

    /** No collections, so the grouping columns are the only trailing columns. */
    @DtoMapper(Item.class)
    public record ItemNameDto(String name, @HierarchyPath List<HierarchyNode> path) {}

    @org.springframework.stereotype.Service
    public static class GroupedItemService extends QueryService<Item, Long, ItemDto> {
        public GroupedItemService(ItemRepository repository) { super(repository, ItemDto.class); }

        @Override
        protected HierarchyGrouping grouping() { return HierarchyGrouping.of("nodes", 3); }
    }

    @org.springframework.stereotype.Service
    public static class PlainItemService extends QueryService<Item, Long, ItemDto> {
        public PlainItemService(ItemRepository repository) { super(repository, ItemDto.class); }
    }

    @org.springframework.stereotype.Service
    public static class BrokenGroupingService extends QueryService<Item, Long, ItemDto> {
        public BrokenGroupingService(ItemRepository repository) { super(repository, ItemDto.class); }

        @Override
        protected HierarchyGrouping grouping() { return HierarchyGrouping.of("name", 3); }
    }

    @Autowired GroupedItemService grouped;
    @Autowired PlainItemService plain;
    @Autowired BrokenGroupingService broken;
    @Autowired EntityManager em;

    private Node a, a1, a11, b, b1;
    private Item echo;

    @BeforeEach
    void data() {
        a = node("A", null);
        a1 = node("A1", a);
        a11 = node("A11", a1);
        b = node("B", null);
        b1 = node("B1", b);
        item("apple", a);
        item("beta", a, a1);
        item("cherry", a, a1, a11);
        item("delta", b, b1);
        echo = item("echo");
        item("fox", b);
        item("golf", a);
        em.flush();
        em.clear();
    }

    private static QueryRequest sortedByName(SortRequest sort, int page, int size) {
        return new QueryRequest(null, List.of(sort), new PageRequest(page, size));
    }

    @Test
    void ordersByGroupPathThenClientSortWithOwnItemsBeforeSubGroupsAndUngroupedLast() {
        PageResponse<ItemDto> page = grouped.query(sortedByName(SortRequest.asc("name"), 0, 20));

        assertThat(page.result()).extracting(ItemDto::name)
                .containsExactly("apple", "golf", "beta", "cherry", "fox", "delta", "echo");
        assertThat(page.count()).isEqualTo(7);
    }

    @Test
    void clientSortOnlyAppliesInsideAGroup() {
        PageResponse<ItemDto> page = grouped.query(sortedByName(SortRequest.desc("name"), 0, 20));

        assertThat(page.result()).extracting(ItemDto::name)
                .containsExactly("golf", "apple", "beta", "cherry", "fox", "delta", "echo");
    }

    @Test
    void exposesTheGroupPathOfEveryRow() {
        List<ItemDto> rows = grouped.query(sortedByName(SortRequest.asc("name"), 0, 20)).result();

        ItemDto cherry = rows.get(3);
        assertThat(cherry.path()).containsExactly(
                new HierarchyNode(1, a.id, "A"), new HierarchyNode(2, a1.id, "A1"), new HierarchyNode(3, a11.id, "A11"));
        assertThat(rows.get(0).path()).containsExactly(new HierarchyNode(1, a.id, "A"));
        assertThat(rows.get(6).path()).isEmpty();                      // echo: no node
        assertThat(cherry.nodes()).extracting(NodeDto::name).containsExactlyInAnyOrder("A", "A1", "A11");
    }

    @Test
    void groupPathColumnsWorkWithoutCollections() {
        List<ItemNameDto> rows = grouped.queryAs(sortedByName(SortRequest.asc("name"), 0, 20), ItemNameDto.class).result();

        assertThat(rows).extracting(ItemNameDto::name)
                .containsExactly("apple", "golf", "beta", "cherry", "fox", "delta", "echo");
        assertThat(rows.get(5).path()).containsExactly(new HierarchyNode(1, b.id, "B"), new HierarchyNode(2, b1.id, "B1"));
    }

    @Test
    void pagesAreStableAndComplete() {
        List<String> all = new ArrayList<>();
        for (int p = 0; p < 4; p++) {
            PageResponse<ItemDto> page = grouped.query(sortedByName(SortRequest.asc("name"), p, 2));
            assertThat(page.count()).isEqualTo(7);
            page.result().forEach(item -> all.add(item.name()));
        }
        assertThat(all).containsExactly("apple", "golf", "beta", "cherry", "fox", "delta", "echo");
    }

    @Test
    void filterThroughTheCollectionNeitherDuplicatesNorBreaksTheOrder() {
        QueryRequest request = new QueryRequest(Filters.in("nodes.name", "A", "A1", "B"),
                List.of(SortRequest.asc("name")), new PageRequest(0, 20));

        PageResponse<ItemDto> page = grouped.query(request);

        assertThat(page.result()).extracting(ItemDto::name)
                .containsExactly("apple", "golf", "beta", "cherry", "fox", "delta");
        assertThat(page.count()).isEqualTo(6);
    }

    @Test
    void filtersOnPlainFieldsStillApply() {
        PageResponse<ItemDto> page = grouped.query(new QueryRequest(Filters.startsWith("name", "e"),
                List.of(), new PageRequest(0, 20)));

        assertThat(page.result()).extracting(ItemDto::name).containsExactly("echo");
    }

    @Test
    void severalNodesOnOneLevelUseTheSmallestIdDeterministically() {
        Item mixed = em.find(Item.class, echo.id);
        mixed.nodes.add(em.find(Node.class, b.id));
        mixed.nodes.add(em.find(Node.class, a.id));                 // two roots: A has the smaller id
        em.flush();
        em.clear();

        List<ItemDto> rows = grouped.query(sortedByName(SortRequest.asc("name"), 0, 20)).result();

        assertThat(rows).extracting(ItemDto::name)
                .containsExactly("apple", "echo", "golf", "beta", "cherry", "fox", "delta");
        assertThat(rows.get(1).path()).containsExactly(new HierarchyNode(1, a.id, "A"));
    }

    @Test
    void entityQueriesAreGroupedToo() {
        List<Item> rows = grouped.queryAs(sortedByName(SortRequest.asc("name"), 0, 20), Item.class).result();

        assertThat(rows).extracting(item -> item.name)
                .containsExactly("apple", "golf", "beta", "cherry", "fox", "delta", "echo");
    }

    @Test
    void withoutGroupingNothingChanges() {
        PageResponse<ItemDto> page = plain.query(sortedByName(SortRequest.asc("name"), 0, 20));

        assertThat(page.result()).extracting(ItemDto::name)
                .containsExactly("apple", "beta", "cherry", "delta", "echo", "fox", "golf");
        assertThat(page.result()).allSatisfy(item -> assertThat(item.path()).isNull());
    }

    @Test
    void theGroupPathIsNeitherFilterableNorSortable() {
        assertThatThrownBy(() -> grouped.query(QueryRequest.of(Filters.eq("path", "A"))))
                .isInstanceOf(QueryRuleException.class)
                .hasMessageContaining("path");
        assertThatThrownBy(() -> grouped.query(sortedByName(SortRequest.asc("path"), 0, 20)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path");
    }

    @Test
    void sortingThroughTheCollectionIsRejectedInGroupedMode() {
        assertThatThrownBy(() -> grouped.query(sortedByName(SortRequest.asc("nodes.name"), 0, 20)))
                .isInstanceOf(QueryRuleException.class)
                .hasMessageContaining("hierarchy grouping");
    }

    @Test
    void aGroupingThatDoesNotFitTheModelFailsClearly() {
        assertThatThrownBy(() -> broken.query(QueryRequest.of(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("to-many association");
    }

    private Node node(String name, Node parent) {
        Node n = new Node();
        n.name = name;
        n.parent = parent;
        em.persist(n);
        return n;
    }

    private Item item(String name, Node... nodes) {
        Item i = new Item();
        i.name = name;
        i.nodes.addAll(List.of(nodes));
        em.persist(i);
        return i;
    }
}
