package ir.sinqro.enigma.grouping;

import ir.sinqro.enigma.model.SortDirection;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HierarchyGroupingTest {

    @Test
    void ofUsesConventionalDefaults() {
        HierarchyGrouping grouping = HierarchyGrouping.of(" categories ", 3);

        assertThat(grouping.nodes()).isEqualTo("categories");
        assertThat(grouping.parent()).isEqualTo("parent");
        assertThat(grouping.label()).isEqualTo("name");
        assertThat(grouping.direction()).isEqualTo(SortDirection.ASC);
        assertThat(grouping.ungrouped()).isEqualTo(HierarchyGrouping.Placement.LAST);
    }

    @Test
    void withersReturnModifiedCopies() {
        HierarchyGrouping base = HierarchyGrouping.of("catalog.categories", 2);
        HierarchyGrouping changed = base.withParent("up").withLabel("title")
                .withDirection(SortDirection.DESC).withUngrouped(HierarchyGrouping.Placement.FIRST);

        assertThat(base.parent()).isEqualTo("parent");
        assertThat(changed).isEqualTo(new HierarchyGrouping("catalog.categories", "up", "title", 2,
                SortDirection.DESC, HierarchyGrouping.Placement.FIRST));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> HierarchyGrouping.of(" ", 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HierarchyGrouping.of("a..b", 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HierarchyGrouping.of("categories", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HierarchyGrouping.of("categories", HierarchyGrouping.MAX_DEPTH_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HierarchyGrouping.of("categories", 3).withLabel("parent.name"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HierarchyGrouping.of("categories", 3).withDirection(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void nodeRequiresLevelAndId() {
        assertThatThrownBy(() -> new HierarchyNode(0, 1L, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HierarchyNode(1, null, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(new HierarchyNode(1, 7L, null).label()).isNull();
    }
}
