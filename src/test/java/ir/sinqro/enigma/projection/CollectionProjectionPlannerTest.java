package ir.sinqro.enigma.projection;

import ir.sinqro.enigma.annotation.DtoMapper;
import ir.sinqro.enigma.annotation.Mapper;
import ir.sinqro.enigma.annotation.ProjectedCollection;
import ir.sinqro.enigma.exception.QueryRuleException;
import ir.sinqro.enigma.specification.CollectionProjectionPlan;
import ir.sinqro.enigma.specification.CollectionProjectionPlan.*;
import ir.sinqro.enigma.specification.CollectionProjectionPlanner;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionProjectionPlannerTest {

    static class User {}

    record RoleDto(@Mapper("role.id") Long id, @Mapper("role.name") String name,
                   @ProjectedCollection Set<String> permissions) {}

    @DtoMapper(User.class)
    record UserDto(Long id,
                   @ProjectedCollection(orderBy = "role.name") @Mapper("userRoles") Set<RoleDto> roles) {}

    static class Legacy { Long id; List<String> tags = new ArrayList<>(); }

    private final CollectionProjectionPlanner planner = new CollectionProjectionPlanner();

    @Test
    void buildsNestedPlanFromAnnotations() {
        CollectionPlan roles = planner.planFor(UserDto.class).collections().get(0);
        assertThat(roles.path()).isEqualTo("userRoles");
        assertThat(roles.container()).isEqualTo(ContainerKind.SET);
        assertThat(roles.orderBy()).isEqualTo("role.name");
        DtoElement element = (DtoElement) roles.element();
        assertThat(element.scalars()).extracting(ScalarField::path).containsExactly("role.id", "role.name");
        assertThat(element.nested()).singleElement().satisfies(n -> {
            assertThat(n.path()).isEqualTo("permissions");
            assertThat(n.element()).isInstanceOf(ScalarElement.class);
        });
    }

    @Test
    void unannotatedCollectionsAreIgnoredForBackwardCompatibility() {
        assertThat(planner.planFor(Legacy.class).hasCollections()).isFalse();
    }

    @Test
    void rejectsUnsupportedContainerAndCycles() {
        class BadContainer { @ProjectedCollection HashSet<String> x; }
        class Cycle { @ProjectedCollection Set<Cycle> self; }
        assertThatThrownBy(() -> planner.planFor(BadContainer.class)).isInstanceOf(QueryRuleException.class);
        assertThatThrownBy(() -> planner.planFor(Cycle.class)).isInstanceOf(QueryRuleException.class);
    }

    @Test
    void isSafeUnderConcurrentFirstUse() throws Exception {
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<CollectionProjectionPlan>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return planner.planFor(UserDto.class);
            }));
        }
        start.countDown();
        for (Future<CollectionProjectionPlan> f : futures) {
            assertThat(f.get().collections()).hasSize(1);
        }
        pool.shutdown();
        assertThat(planner.planFor(UserDto.class)).isSameAs(planner.planFor(UserDto.class));
    }
}
