package ir.sinqro.enigma.projection;

import ir.sinqro.enigma.annotation.DtoMapper;
import ir.sinqro.enigma.annotation.Mapper;
import ir.sinqro.enigma.annotation.ProjectedCollection;
import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.model.PageRequest;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Self-referencing DTO over a self-referencing entity (the product-category shape), end to end
 * through {@link QueryService}. Batch size 2 forces chunking on every level; depth 3 lets the
 * depth guard be exercised.
 */
@Transactional
@SpringBootTest(classes = RecursiveCollectionProjectionIT.TestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:recursive_projection_it;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.specification.spec.collection-batch-size=2",
        "app.specification.spec.max-collection-rows=100",
        "app.specification.spec.max-collection-depth=3"
})
class RecursiveCollectionProjectionIT {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @org.springframework.boot.persistence.autoconfigure.EntityScan(basePackageClasses = RecursiveCollectionProjectionIT.class)
    @EnableJpaRepositories(basePackageClasses = RecursiveCollectionProjectionIT.class, considerNestedRepositories = true)
    @Import({ir.sinqro.enigma.autoconfigure.EnigmaAutoConfiguration.class, CategoryQueryService.class})
    static class TestConfig {
        @Bean
        org.springframework.core.convert.ConversionService mvcConversionService() {
            return new org.springframework.core.convert.support.DefaultConversionService();
        }
    }

    @Entity @Table(name = "rec_category")
    public static class Category {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String name;
        @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "parent_id") public Category parent;
        @OneToMany(mappedBy = "parent", fetch = FetchType.LAZY) public List<Category> children = new ArrayList<>();
    }

    public interface CategoryRepository extends GenericRepository<Category, Long> {}

    @DtoMapper(Category.class)
    public record CategoryDto(Long id, String name,
                              @Mapper("parent.id") Long parentId,
                              @ProjectedCollection(orderBy = "name") @Mapper("children") List<CategoryDto> children) {}

    @org.springframework.stereotype.Service
    public static class CategoryQueryService extends QueryService<Category, Long, CategoryDto> {
        public CategoryQueryService(CategoryRepository repository) {
            super(repository, CategoryDto.class);
        }
    }

    @Autowired CategoryQueryService service;
    @Autowired EntityManager em;

    private Category cat1, cat2, cat3;

    @BeforeEach
    void data() {
        cat1 = category("cat1", null);
        category("sub1", cat1);
        Category sub11 = category("sub11", cat1);
        category("sub111", sub11);
        cat2 = category("cat2", null);
        category("sub2", cat2);
        category("sub22", cat2);
        cat3 = category("cat3", null);
        Category sub3 = category("sub3", cat3);
        category("sub33", sub3);
        em.flush();
        em.clear();
    }

    private QueryRequest roots() {
        return new QueryRequest(Filters.isNull("parentId"), List.of(SortRequest.asc("name")), new PageRequest(0, 10));
    }

    @Test
    void returnsRootsWithTheirFullSubtrees() {
        var page = service.query(roots());

        assertThat(page.count()).isEqualTo(3); // children do not count as rows
        assertThat(page.result()).extracting(CategoryDto::name).containsExactly("cat1", "cat2", "cat3");

        CategoryDto c1 = page.result().get(0);
        assertThat(c1.parentId()).isNull();
        assertThat(c1.children()).extracting(CategoryDto::name).containsExactly("sub1", "sub11"); // orderBy name
        assertThat(c1.children()).extracting(CategoryDto::parentId).containsOnly(cat1.id);
        CategoryDto sub11 = c1.children().get(1);
        assertThat(sub11.children()).extracting(CategoryDto::name).containsExactly("sub111");
        assertThat(sub11.children().get(0).children()).isEmpty(); // leaf: empty, not null

        assertThat(page.result().get(1).children()).extracting(CategoryDto::name).containsExactly("sub2", "sub22");
        assertThat(page.result().get(2).children().get(0).children())
                .extracting(CategoryDto::name).containsExactly("sub33");
    }

    @Test
    void anyNodeCanBeTheRootOfTheResponse() {
        var page = service.query(QueryRequest.of(Filters.eq("name", "sub11")));
        assertThat(page.result()).singleElement().satisfies(s -> {
            assertThat(s.parentId()).isEqualTo(cat1.id);
            assertThat(s.children()).extracting(CategoryDto::name).containsExactly("sub111");
        });
    }

    @Test
    void terminatesOnCyclicParentData() {
        // corrupt the data: x.parent = y, y.parent = x
        Category x = category("x", null), y = category("y", x);
        x.parent = y;
        em.flush();
        em.clear();

        var page = service.query(QueryRequest.of(Filters.eq("name", "x")));
        CategoryDto rx = page.result().get(0);
        assertThat(rx.children()).extracting(CategoryDto::name).containsExactly("y");
        CategoryDto ry = rx.children().get(0);
        assertThat(ry.children()).extracting(CategoryDto::name).containsExactly("x");
        assertThat(ry.children().get(0).children()).isEmpty(); // the cycle is cut, not followed
    }

    @Test
    void treeOfExactlyMaxDepthLoadsAndDeeperFailsClosed() {
        Category a = category("a", null), b = category("b", a), c = category("c", b);
        Long dId = category("d", c).id; // a has 3 levels below it == max-collection-depth
        em.flush();
        em.clear();
        assertThat(service.query(QueryRequest.of(Filters.eq("name", "a"))).result().get(0)
                .children().get(0).children().get(0).children()).extracting(CategoryDto::name).containsExactly("d");

        category("e", em.find(Category.class, dId)); // now 4 levels below a
        em.flush();
        em.clear();
        assertThatThrownBy(() -> service.query(QueryRequest.of(Filters.eq("name", "a"))))
                .isInstanceOf(QueryRuleException.class)
                .hasMessageContaining("max-collection-depth");
    }

    private Category category(String name, Category parent) {
        Category c = new Category();
        c.name = name;
        c.parent = parent;
        em.persist(c);
        return c;
    }
}
