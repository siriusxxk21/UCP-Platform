package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.Selections;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 授权清单「全部」哨兵的集合语义；不连库。放大方向（清单变全部）的每一条都单独钉住。 */
class SelectionsTest {
    private static Set<String> ordered(String... ids) {
        return new LinkedHashSet<>(Arrays.asList(ids));
    }

    @Test
    void isAllOnlyForExactlyOneStar() {
        assertThat(Selections.isAll(null)).isFalse();
        assertThat(Selections.isAll(Set.of())).isFalse();
        assertThat(Selections.isAll(Set.of("*"))).isTrue();
        assertThat(Selections.isAll(List.of("*"))).isTrue();
        assertThat(Selections.isAll(Set.of("*", "1"))).isFalse();
        assertThat(Selections.isAll(Set.of("1"))).isFalse();
    }

    @Test
    void mixingStarWithIdsIsRejectedWithExactMessage() {
        assertThatThrownBy(() -> Selections.requireWellFormed(Set.of("*", "1"), "可查看字段"))
                .isInstanceOf(ServiceException.class)
                .hasMessage("「全部」不能与具体项同时选择：可查看字段");
        assertThatCode(() -> Selections.requireWellFormed(Set.of("*"), "可查看字段"))
                .doesNotThrowAnyException();
        assertThatCode(() -> Selections.requireWellFormed(Set.of("1", "2"), "可查看字段"))
                .doesNotThrowAnyException();
        assertThatCode(() -> Selections.requireWellFormed(Set.of(), "可查看字段"))
                .doesNotThrowAnyException();
        assertThatCode(() -> Selections.requireWellFormed(null, "可查看字段"))
                .doesNotThrowAnyException();
    }

    @Test
    void resolveAllFollowsUniverseOrderAndListKeepsStoredOrder() {
        List<String> universe = List.of("3", "1", "2");
        assertThat(Selections.resolve(Set.of("*"), universe)).containsExactly("3", "1", "2");
        assertThat(Selections.resolve(ordered("2", "9", "3"), universe)).containsExactly("2", "3");
        assertThat(Selections.resolve(Set.of(), universe)).isEmpty();
        assertThat(Selections.resolve(null, universe)).isEmpty();
        assertThat(Selections.resolve(Set.of("*"), List.of())).isEmpty();
    }

    @Test
    void resolvedResultNeverContainsTheSentinel() {
        assertThat(Selections.resolve(Set.of("*"), List.of("1", "2"))).doesNotContain("*");
        assertThat(Selections.resolve(ordered("*", "1"), List.of("1", "2"))).containsExactly("1");
    }

    @Test
    void intersectNeverWidensAListIntoAll() {
        Set<String> all = Set.of("*");
        Set<String> list = Set.of("1", "2");
        assertThat(Selections.intersect(all, list)).containsExactlyInAnyOrder("1", "2");
        assertThat(Selections.intersect(list, all)).containsExactlyInAnyOrder("1", "2");
        assertThat(Selections.intersect(all, all)).containsExactly("*");
        assertThat(Selections.intersect(Set.of("1", "2"), Set.of("2", "3"))).containsExactly("2");
        assertThat(Selections.intersect(all, Set.of())).isEmpty();
        assertThat(Selections.intersect(Set.of(), all)).isEmpty();
    }

    @Test
    void cleanDropsDeadIdsButKeepsAnExactListAsAList() {
        List<String> universe = List.of("1", "2", "3");
        assertThat(Selections.clean(ordered("1", "9", "3"), universe)).containsExactly("1", "3");
        assertThat(Selections.clean(Set.of("*"), universe)).containsExactly("*");
        // 恰好等于全集的清单仍是清单：以后新增的项不会自动进来。
        Set<String> exact = Selections.clean(ordered("1", "2", "3"), universe);
        assertThat(exact).containsExactly("1", "2", "3");
        assertThat(Selections.isAll(exact)).isFalse();
        assertThat(Selections.clean(Set.of(), universe)).isEmpty();
        assertThat(Selections.clean(null, universe)).isEmpty();
    }
}
