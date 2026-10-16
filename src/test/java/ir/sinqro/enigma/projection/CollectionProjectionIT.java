package ir.sinqro.enigma.projection;

import ir.sinqro.enigma.annotation.DtoMapper;
import ir.sinqro.enigma.annotation.Mapper;
import ir.sinqro.enigma.annotation.ProjectedCollection;
import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.model.*;
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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Mirrors the real-world shape: User -> UserRole (id, role, permission set) -> Role.
 * Batch size 2 forces chunking; max rows 20 lets the overflow guard be exercised.
 */
@Transactional
@SpringBootTest(classes = CollectionProjectionIT.TestConfig.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:projection_it;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.specification.spec.collection-batch-size=2",
        "app.specification.spec.max-collection-rows=20"
})
class CollectionProjectionIT {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @org.springframework.boot.persistence.autoconfigure.EntityScan(basePackageClasses = CollectionProjectionIT.class)
    @EnableJpaRepositories(basePackageClasses = CollectionProjectionIT.class, considerNestedRepositories = true)
    @Import({ir.sinqro.enigma.autoconfigure.EnigmaAutoConfiguration.class, OwnerQueryService.class})
    static class TestConfig {
        @Bean
        org.springframework.core.convert.ConversionService mvcConversionService() {
            return new org.springframework.core.convert.support.DefaultConversionService();
        }
    }

    public enum Perm {READ, WRITE, DELETE}

    @Entity @Table(name = "proj_owner")
    public static class Owner {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String name;
        @OneToMany(mappedBy = "owner", cascade = CascadeType.ALL) public Set<Assignment> assignments = new HashSet<>();
    }

    @Entity @Table(name = "proj_label")
    public static class Label {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        public String name;
    }

    @Entity @Table(name = "proj_assignment")
    public static class Assignment {
        @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
        @ManyToOne(fetch = FetchType.LAZY, optional = false) public Owner owner;
        @ManyToOne(fetch = FetchType.LAZY, optional = false) public Label label;
        @ElementCollection(fetch = FetchType.LAZY)
        @CollectionTable(name = "proj_assignment_perm", joinColumns = @JoinColumn(name = "assignment_id"))
        @Enumerated(EnumType.STRING) @Column(name = "perm")
        public Set<Perm> perms = new HashSet<>();
    }

    public interface OwnerRepository extends GenericRepository<Owner, Long> {}

    public record AssignmentDto(@Mapper("label.id") Long labelId, @Mapper("label.name") String labelName,
                                @ProjectedCollection Set<String> perms) {}

    @DtoMapper(Owner.class)
    public record OwnerDto(Long id, String name,
                           @ProjectedCollection(orderBy = "label.name") @Mapper("assignments") List<AssignmentDto> assignments) {}

    @org.springframework.stereotype.Service
    public static class OwnerQueryService extends QueryService<Owner, Long, OwnerDto> {
        public OwnerQueryService(OwnerRepository repository) {
            super(repository, OwnerDto.class);
        }
    }

    @Autowired OwnerQueryService service;
    @Autowired EntityManager em;

    @BeforeEach
    void data() {
        Label a = label("alpha"), b = label("beta");
        owner("o1", assignment(a, Perm.READ), assignment(b, Perm.READ, Perm.WRITE));
        owner("o2");
        owner("o3", assignment(b, Perm.DELETE));
        owner("o4", assignment(a));
        em.flush();
        em.clear();
    }

    @Test
    void populatesNestedCollectionsAsDtosWithoutTouchingLazyState() {
        var page = service.query(new QueryRequest(null, List.of(SortRequest.asc("name")), new PageRequest(0, 10)));
        assertThat(page.result()).extracting(OwnerDto::name).containsExactly("o1", "o2", "o3", "o4");

        OwnerDto o1 = page.result().get(0);
        assertThat(o1.assignments()).extracting(AssignmentDto::labelName).containsExactly("alpha", "beta"); // orderBy
        assertThat(o1.assignments().get(1).perms()).containsExactlyInAnyOrder("READ", "WRITE");            // enum -> String
        assertThat(page.result().get(1).assignments()).isEmpty();                                           // no rows -> empty, not null
        assertThat(page.result().get(3).assignments().get(0).perms()).isEmpty();
    }

    @Test
    void worksAcrossBatchBoundariesAndPages() {
        var second = service.query(new QueryRequest(null, List.of(SortRequest.asc("name")), new PageRequest(1, 3)));
        assertThat(second.result()).extracting(OwnerDto::name).containsExactly("o4");
        assertThat(second.count()).isEqualTo(4);
    }

    @Test
    void filterOnCollectionElementUsesRelativePath() {
        var page = service.query(QueryRequest.of(Filters.eq("assignments.labelName", "beta")));
        assertThat(page.result()).extracting(OwnerDto::name).containsExactlyInAnyOrder("o1", "o3");
    }

    @Test
    void filterOnElementCollectionMatchesIndividualElements() {
        assertThat(names(Filters.eq("assignments.perms", "WRITE"))).containsExactly("o1");
        assertThat(names(Filters.eq("assignments.perms", "DELETE"))).containsExactly("o3");
        assertThat(names(Filters.in("assignments.perms", "WRITE", "DELETE"))).containsExactlyInAnyOrder("o1", "o3");
        assertThat(names(Filters.eq("assignments.perms", "READ"))).containsExactly("o1");
    }

    @Test
    void elementCollectionFilterIsEvaluatedOnTheSameRowAsItsSiblings() {
        // o1: alpha[READ], beta[READ, WRITE] -> WRITE exists, but not on the "alpha" assignment
        assertThat(names(Filters.and(Filters.eq("assignments.labelName", "alpha"), Filters.eq("assignments.perms", "WRITE"))))
                .isEmpty();
        assertThat(names(Filters.and(Filters.eq("assignments.labelName", "beta"), Filters.eq("assignments.perms", "WRITE"))))
                .containsExactly("o1");
        assertThat(names(Filters.and(Filters.eq("assignments.labelName", "beta"), Filters.eq("assignments.perms", "DELETE"))))
                .containsExactly("o3");
    }

    @Test
    void elementCollectionFilterCountsEachOwnerOnceAndKeepsAllItsElements() {
        // o1 matches READ through two different assignments: still one owner, one count, full collections returned.
        var page = service.query(new QueryRequest(Filters.eq("assignments.perms", "READ"),
                List.of(SortRequest.asc("name")), new PageRequest(0, 1)));
        assertThat(page.count()).isEqualTo(1);
        assertThat(page.result()).extracting(OwnerDto::name).containsExactly("o1");
        assertThat(page.result().get(0).assignments()).hasSize(2);
        assertThat(page.result().get(0).assignments().get(1).perms()).containsExactlyInAnyOrder("READ", "WRITE");
    }

    @Test
    void elementCollectionNullChecksUseTheJoinedElements() {
        // LEFT join: owners without any assignment (o2) or with an assignment holding no permission (o4)
        assertThat(names(Filters.isNull("assignments.perms"))).containsExactlyInAnyOrder("o2", "o4");
        assertThat(names(Filters.isNotNull("assignments.perms"))).containsExactlyInAnyOrder("o1", "o3");
    }

    @Test
    void elementCollectionFilterRejectsValuesOfTheWrongType() {
        assertThatThrownBy(() -> service.query(QueryRequest.of(Filters.eq("assignments.perms", "NOT_A_PERM"))))
                .isInstanceOf(QueryRuleException.class)
                .hasMessageContaining("assignments.perms");
        assertThatThrownBy(() -> service.query(QueryRequest.of(Filters.contains("assignments.perms", "RE"))))
                .isInstanceOf(QueryRuleException.class);
    }

    @Test
    void failsClosedWhenChildRowBudgetIsExceeded() {
        for (int i = 0; i < 15; i++) {
            owner("bulk" + i, assignment(label("l" + i), Perm.READ));
        }
        em.flush();
        em.clear();
        assertThatThrownBy(() -> service.query(new QueryRequest(null, List.of(), new PageRequest(0, 100))))
                .isInstanceOf(QueryRuleException.class)
                .hasMessageContaining("max-collection-rows");
    }

    // ---- helpers
    private List<String> names(FilterNode filter) {
        return service.query(QueryRequest.of(filter)).result().stream().map(OwnerDto::name).toList();
    }

    private Label label(String name) { Label l = new Label(); l.name = name; em.persist(l); return l; }

    private Assignment assignment(Label label, Perm... perms) {
        Assignment a = new Assignment(); a.label = label; a.perms.addAll(Arrays.asList(perms)); return a;
    }

    private void owner(String name, Assignment... assignments) {
        Owner o = new Owner(); o.name = name;
        for (Assignment a : assignments) { a.owner = o; o.assignments.add(a); }
        em.persist(o);
    }
}
