package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.RuleFixtures.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.lingan.ucp.nocode.api.DataCenter.Definition;
import com.lingan.ucp.nocode.api.DataScope;
import com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator;
import com.lingan.ucp.nocode.application.service.sharing.ObjectGrantValidator.Universe;
import com.lingan.ucp.nocode.enums.MemberStateEnum;
import com.lingan.ucp.nocode.enums.RelationTypeEnum;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 授权「全部」的展开、保存规范化、上限检查与运行时交集；不连库。
 *
 * <p>夹具：主表字段 1、2、3（3 已停用），启用明细 d1、停用明细 d2，多对多关系 m1、普通引用 r1。
 * 放大方向的三处（交集把清单放大成全部、可写越过可读、规范化把清单变全部）各有单独的断言。
 */
class ObjectGrantResolutionTest {
    private static final Set<String> ALL = Set.of("*");
    private final ObjectGrantValidator validator = new ObjectGrantValidator();

    private static Definition definition() {
        return object(
                "100",
                "甲",
                List.of(
                        field("1", "name", "名称", "TEXT"),
                        field("2", "amount", "金额", "INTEGER"),
                        field("3", "retired", "已停用", "TEXT")),
                options(Map.entry("3", inactive())),
                List.of(
                        relation("m1", null, "200", RelationTypeEnum.MANY_TO_MANY.getCode()),
                        ref("r1", "2", "200")),
                List.of(
                        detail("d1", "明细一", List.of(field("11", "line", "行", "TEXT")), Map.of()),
                        new com.lingan.ucp.nocode.api.DataCenter.Detail(
                                "d2",
                                "dd2",
                                "停用明细",
                                "biz_dd2",
                                MemberStateEnum.INACTIVE.getCode(),
                                List.of(field("21", "old", "旧", "TEXT")),
                                Map.of(),
                                List.of())));
    }

    private static ObjectGrant grant(
            Set<String> actions,
            String scope,
            Set<String> readFields,
            Set<String> writeFields,
            Set<String> readDetails,
            Set<String> writeDetails,
            Set<String> readRelations,
            Set<String> writeRelations) {
        return new ObjectGrant(
                "100",
                actions,
                scope,
                readFields,
                writeFields,
                readDetails,
                writeDetails,
                readRelations,
                writeRelations);
    }

    private static ObjectGrant everything() {
        return grant(Set.of("READ", "CREATE", "UPDATE"), "ALL", ALL, ALL, ALL, ALL, ALL, ALL);
    }

    // ── 全集 ──

    @Test
    void universeSkipsInactiveFieldsInactiveDetailsAndNonManyToManyRelations() {
        Universe universe = Universe.of(definition());
        assertThat(universe.fields()).containsExactly("1", "2");
        assertThat(universe.details()).containsExactly("d1");
        assertThat(universe.relations()).containsExactly("m1");
    }

    // ── 展开 ──

    @Test
    void allExpandsToTheVersionUniverseWithoutTheSentinel() {
        ObjectGrant resolved = validator.resolve(everything(), definition());
        assertThat(resolved.readFields()).containsExactly("1", "2");
        assertThat(resolved.writeFields()).containsExactly("1", "2");
        assertThat(resolved.readDetails()).containsExactly("d1");
        assertThat(resolved.writeDetails()).containsExactly("d1");
        assertThat(resolved.readRelations()).containsExactly("m1");
        assertThat(resolved.writeRelations()).containsExactly("m1");
        assertThat(resolved.computeFields()).isEmpty();
        assertThat(resolved.actions()).containsExactlyInAnyOrder("READ", "CREATE", "UPDATE");
        assertThat(resolved.scope()).isEqualTo("ALL");
    }

    @Test
    void listExpandsToItselfMinusDeadIds() {
        ObjectGrant resolved =
                validator.resolve(
                        grant(
                                Set.of("READ"),
                                "OWN",
                                Set.of("1", "3", "999"),
                                Set.of("1", "999"),
                                Set.of("d1", "d2"),
                                Set.of("d2"),
                                Set.of("m1", "r1"),
                                Set.of("r1")),
                        definition());
        assertThat(resolved.readFields()).containsExactly("1");
        assertThat(resolved.writeFields()).containsExactly("1");
        assertThat(resolved.readDetails()).containsExactly("d1");
        assertThat(resolved.writeDetails()).isEmpty();
        assertThat(resolved.readRelations()).containsExactly("m1");
        assertThat(resolved.writeRelations()).isEmpty();
    }

    /** 放大方向：可写「全部」只展开到同一份授权的可读范围，不是对象的全部字段。 */
    @Test
    void writeAllOnlyReachesTheReadableSubset() {
        ObjectGrant resolved =
                validator.resolve(
                        grant(
                                Set.of("READ", "UPDATE"),
                                "ALL",
                                Set.of("1"),
                                ALL,
                                Set.of(),
                                ALL,
                                Set.of(),
                                ALL),
                        definition());
        assertThat(resolved.readFields()).containsExactly("1");
        assertThat(resolved.writeFields()).as("可写不越过可读").containsExactly("1");
        assertThat(resolved.writeDetails()).isEmpty();
        assertThat(resolved.writeRelations()).isEmpty();
    }

    @Test
    void writeListIsCutToTheReadableSetOnResolve() {
        ObjectGrant resolved =
                validator.resolve(
                        grant(
                                Set.of("READ", "UPDATE"),
                                "ALL",
                                Set.of("1"),
                                Set.of("1", "2"),
                                ALL,
                                Set.of("d1"),
                                ALL,
                                Set.of()),
                        definition());
        assertThat(resolved.writeFields()).containsExactly("1");
        assertThat(resolved.writeDetails()).containsExactly("d1");
    }

    // ── 保存规范化 ──

    @Test
    void normalizeDropsDeadIdsInsteadOfRejecting() {
        ObjectGrant normalized =
                validator.normalize(
                        grant(
                                Set.of("READ", "UPDATE"),
                                "ALL",
                                Set.of("1", "2", "3", "999"),
                                Set.of("2", "999"),
                                Set.of("d1", "d2"),
                                Set.of("d1"),
                                Set.of("m1", "r1"),
                                Set.of("m1")),
                        definition(),
                        Universe.of(definition()));
        assertThat(normalized.readFields()).containsExactlyInAnyOrder("1", "2");
        assertThat(normalized.writeFields()).containsExactly("2");
        assertThat(normalized.readDetails()).containsExactly("d1");
        assertThat(normalized.writeDetails()).containsExactly("d1");
        assertThat(normalized.readRelations()).containsExactly("m1");
        assertThat(normalized.writeRelations()).containsExactly("m1");
    }

    /** 放大方向：恰好等于全集的清单保持清单，不会被悄悄变成「全部」。 */
    @Test
    void normalizeKeepsAnExactListAsAList() {
        ObjectGrant normalized =
                validator.normalize(
                        grant(
                                Set.of("READ"),
                                "ALL",
                                Set.of("1", "2"),
                                Set.of(),
                                Set.of("d1"),
                                Set.of(),
                                Set.of("m1"),
                                Set.of()),
                        definition(),
                        Universe.of(definition()));
        assertThat(normalized.readFields()).containsExactlyInAnyOrder("1", "2");
        assertThat(normalized.readDetails()).containsExactly("d1");
        assertThat(normalized.readRelations()).containsExactly("m1");
    }

    @Test
    void normalizeKeepsAllAsStoredAndAcceptsEveryCombination() {
        ObjectGrant normalized =
                validator.normalize(everything(), definition(), Universe.of(definition()));
        assertThat(normalized.readFields()).containsExactly("*");
        assertThat(normalized.writeFields()).containsExactly("*");
        assertThat(normalized.readDetails()).containsExactly("*");
        assertThat(normalized.writeDetails()).containsExactly("*");
        assertThat(normalized.readRelations()).containsExactly("*");
        assertThat(normalized.writeRelations()).containsExactly("*");
        // 读是清单、写是「全部」合法：读到的都可写。
        ObjectGrant listReadAllWrite =
                validator.normalize(
                        grant(
                                Set.of("READ", "UPDATE"),
                                "ALL",
                                Set.of("1"),
                                ALL,
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                Set.of()),
                        definition(),
                        Universe.of(definition()));
        assertThat(listReadAllWrite.readFields()).containsExactly("1");
        assertThat(listReadAllWrite.writeFields()).containsExactly("*");
        // 读是「全部」、写是清单：写的清单只需仍在全集里。
        ObjectGrant allReadListWrite =
                validator.normalize(
                        grant(
                                Set.of("READ", "UPDATE"),
                                "ALL",
                                ALL,
                                Set.of("2", "999"),
                                ALL,
                                Set.of(),
                                ALL,
                                Set.of()),
                        definition(),
                        Universe.of(definition()));
        assertThat(allReadListWrite.writeFields()).containsExactly("2");
    }

    @Test
    void normalizeRemovesWriteItemsThatAreNotReadable() {
        ObjectGrant normalized =
                validator.normalize(
                        grant(
                                Set.of("READ", "UPDATE"),
                                "ALL",
                                Set.of("1"),
                                Set.of("1", "2"),
                                Set.of(),
                                Set.of("d1"),
                                Set.of(),
                                Set.of("m1")),
                        definition(),
                        Universe.of(definition()));
        assertThat(normalized.writeFields()).containsExactly("1");
        assertThat(normalized.writeDetails()).isEmpty();
        assertThat(normalized.writeRelations()).isEmpty();
    }

    @Test
    void normalizeRejectsMixingAllWithIdsWithExactMessages() {
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        grant(
                                                Set.of("READ"),
                                                "ALL",
                                                Set.of("*", "1"),
                                                Set.of(),
                                                Set.of(),
                                                Set.of(),
                                                Set.of(),
                                                Set.of()),
                                        definition()))
                .hasMessage("「全部」不能与具体项同时选择：可查看字段");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        grant(
                                                Set.of("READ"),
                                                "ALL",
                                                ALL,
                                                Set.of("*", "1"),
                                                Set.of(),
                                                Set.of(),
                                                Set.of(),
                                                Set.of()),
                                        definition()))
                .hasMessage("「全部」不能与具体项同时选择：可填写和修改字段");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        grant(
                                                Set.of("READ"),
                                                "ALL",
                                                ALL,
                                                Set.of(),
                                                Set.of("*", "d1"),
                                                Set.of(),
                                                Set.of(),
                                                Set.of()),
                                        definition()))
                .hasMessage("「全部」不能与具体项同时选择：可查看内部明细");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        grant(
                                                Set.of("READ"),
                                                "ALL",
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of("*", "d1"),
                                                Set.of(),
                                                Set.of()),
                                        definition()))
                .hasMessage("「全部」不能与具体项同时选择：可修改内部明细");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        grant(
                                                Set.of("READ"),
                                                "ALL",
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of(),
                                                Set.of("*", "m1"),
                                                Set.of()),
                                        definition()))
                .hasMessage("「全部」不能与具体项同时选择：可查看多对多关系");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        grant(
                                                Set.of("READ"),
                                                "ALL",
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of("*", "m1")),
                                        definition()))
                .hasMessage("「全部」不能与具体项同时选择：可修改多对多关系");
    }

    /** 计算取数不再由人勾选：带了也不报错，规范化后一律为空；旧的两条拒绝不再出现。 */
    @Test
    void normalizeEmptiesComputeFieldsWithoutRejecting() {
        ObjectGrant withCompute =
                new ObjectGrant(
                        "100",
                        Set.of("READ"),
                        "OWN",
                        Set.of("1"),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Map.of(),
                        Set.of("1", "2"));
        ObjectGrant normalized =
                validator.normalize(withCompute, definition(), Universe.of(definition()));
        assertThat(normalized.computeFields()).isEmpty();
        assertThat(normalized.scope()).isEqualTo("OWN");
    }

    // 对照组：现有的拒绝仍然拒绝。

    @Test
    void existingRejectionsStillReject() {
        assertThatThrownBy(() -> validator.validate(null, definition())).hasMessage("授权对象不匹配");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        new ObjectGrant(
                                                "101",
                                                Set.of("READ"),
                                                "ALL",
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of()),
                                        definition()))
                .hasMessage("授权对象不匹配");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        grant(
                                                Set.of("UPDATE"),
                                                "ALL",
                                                ALL,
                                                ALL,
                                                ALL,
                                                ALL,
                                                ALL,
                                                ALL),
                                        definition()))
                .hasMessage("业务授权必须包含查看权限；撤销请使用撤销授权操作");
        // 「允许操作」本期没有「全部」：哨兵落在操作上被枚举校验拒绝。
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        grant(
                                                Set.of("READ", "*"),
                                                "ALL",
                                                ALL,
                                                ALL,
                                                ALL,
                                                ALL,
                                                ALL,
                                                ALL),
                                        definition()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        grant(
                                                Set.of("READ"),
                                                "EVERYONE",
                                                ALL,
                                                ALL,
                                                ALL,
                                                ALL,
                                                ALL,
                                                ALL),
                                        definition()))
                .isInstanceOf(RuntimeException.class);
        DataScope scope = new DataScope("AND", List.of(), List.of());
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        new ObjectGrant(
                                                "100",
                                                Set.of("READ"),
                                                "ALL",
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of(),
                                                Map.of("UPDATE", scope),
                                                Set.of()),
                                        definition()))
                .hasMessage("记录条件必须对应已允许操作");
        assertThatThrownBy(
                        () ->
                                validator.validate(
                                        new ObjectGrant(
                                                "100",
                                                Set.of("READ"),
                                                "ALL",
                                                null,
                                                Set.of(),
                                                ALL,
                                                Set.of()),
                                        definition()))
                .hasMessage("可查看字段包含无效项");
    }

    // ── 上限 ──

    private static ObjectGrant ceiling(Set<String> read, Set<String> write) {
        return grant(Set.of("READ", "CREATE", "UPDATE"), "ALL", read, write, ALL, ALL, ALL, ALL);
    }

    @Test
    void withinPassesWhenEitherSideIsAll() {
        ObjectGrant member =
                grant(Set.of("READ", "UPDATE"), "OWN", ALL, ALL, ALL, Set.of(), ALL, Set.of());
        ObjectGrant listCeiling = ceiling(Set.of("1"), Set.of("1"));
        ObjectGrant kept = validator.within(member, listCeiling);
        assertThat(kept.readFields()).as("成员是全部：原样保留，运行时再取交集").containsExactly("*");
        assertThat(kept.writeFields()).containsExactly("*");
        ObjectGrant listMember =
                grant(
                        Set.of("READ", "UPDATE"),
                        "OWN",
                        Set.of("1", "2"),
                        Set.of("2"),
                        Set.of("d1"),
                        Set.of(),
                        Set.of(),
                        Set.of());
        ObjectGrant untouched = validator.within(listMember, everything());
        assertThat(untouched.readFields()).containsExactlyInAnyOrder("1", "2");
        assertThat(untouched.writeFields()).containsExactly("2");
        assertThat(untouched.readDetails()).containsExactly("d1");
    }

    @Test
    void withinTightensListsInsteadOfRejecting() {
        ObjectGrant member =
                grant(
                        Set.of("READ", "UPDATE"),
                        "OWN",
                        Set.of("1", "2"),
                        Set.of("1", "2"),
                        Set.of("d1"),
                        Set.of("d1"),
                        Set.of("m1"),
                        Set.of("m1"));
        ObjectGrant tightened =
                validator.within(
                        member,
                        grant(
                                Set.of("READ", "UPDATE"),
                                "ALL",
                                Set.of("1"),
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                Set.of("m1"),
                                Set.of()));
        assertThat(tightened.readFields()).containsExactly("1");
        assertThat(tightened.writeFields()).isEmpty();
        assertThat(tightened.readDetails()).isEmpty();
        assertThat(tightened.writeDetails()).isEmpty();
        assertThat(tightened.readRelations()).containsExactly("m1");
        assertThat(tightened.writeRelations()).isEmpty();
        assertThat(tightened.actions()).containsExactlyInAnyOrder("READ", "UPDATE");
        assertThat(tightened.scope()).isEqualTo("OWN");
    }

    /** 上限的可写是「全部」而可读是清单：成员可写清单随收紧后的可读一起收。 */
    @Test
    void withinKeepsMemberWriteInsideTightenedRead() {
        ObjectGrant tightened =
                validator.within(
                        grant(
                                Set.of("READ", "UPDATE"),
                                "ALL",
                                Set.of("1", "2"),
                                Set.of("2"),
                                Set.of(),
                                Set.of(),
                                Set.of(),
                                Set.of()),
                        ceiling(Set.of("1"), ALL));
        assertThat(tightened.readFields()).containsExactly("1");
        assertThat(tightened.writeFields()).isEmpty();
    }

    // 对照组：操作超出、范围超出、没有上限，仍抛原文案。

    @Test
    void withinStillRejectsActionsAndScopeBeyondTheCeiling() {
        ObjectGrant readOnlyCeiling = grant(Set.of("READ"), "ALL", ALL, ALL, ALL, ALL, ALL, ALL);
        assertThatThrownBy(
                        () ->
                                validator.within(
                                        grant(
                                                Set.of("READ", "UPDATE"),
                                                "ALL",
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of()),
                                        readOnlyCeiling))
                .hasMessage("成员授权超出对象授予应用的范围");
        ObjectGrant ownCeiling = grant(Set.of("READ"), "OWN", ALL, ALL, ALL, ALL, ALL, ALL);
        assertThatThrownBy(
                        () ->
                                validator.within(
                                        grant(
                                                Set.of("READ"),
                                                "ALL",
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of()),
                                        ownCeiling))
                .hasMessage("成员授权超出对象授予应用的范围");
        assertThatThrownBy(() -> validator.within(everything(), null))
                .hasMessage("对象尚未授权给此应用或授权已撤销");
        assertThatCode(
                        () ->
                                validator.within(
                                        grant(
                                                Set.of("READ"),
                                                "OWN",
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of(),
                                                ALL,
                                                Set.of()),
                                        ownCeiling))
                .doesNotThrowAnyException();
    }

    // ── 运行时交集 ──

    /** 放大方向：全部 ∩ 清单 = 清单，不是全部。 */
    @Test
    void intersectOfAllAndListIsTheList() {
        ObjectGrant member = grant(Set.of("READ", "UPDATE"), "ALL", ALL, ALL, ALL, ALL, ALL, ALL);
        ObjectGrant listCeiling =
                grant(
                        Set.of("READ", "UPDATE"),
                        "ALL",
                        Set.of("1"),
                        Set.of("1"),
                        Set.of(),
                        Set.of(),
                        Set.of("m1"),
                        Set.of());
        ObjectGrant result = validator.intersect(member, listCeiling);
        assertThat(result.readFields()).containsExactly("1");
        assertThat(result.writeFields()).containsExactly("1");
        assertThat(result.readDetails()).isEmpty();
        assertThat(result.writeDetails()).isEmpty();
        assertThat(result.readRelations()).containsExactly("m1");
        assertThat(result.writeRelations()).isEmpty();
        ObjectGrant mirrored = validator.intersect(listCeiling, member);
        assertThat(mirrored.readFields()).containsExactly("1");
        assertThat(mirrored.readDetails()).isEmpty();
        // 展开后成员只读得到上限里的。
        ObjectGrant resolved = validator.resolve(result, definition());
        assertThat(resolved.readFields()).containsExactly("1");
        assertThat(resolved.writeFields()).containsExactly("1");
    }

    @Test
    void intersectOfAllAndAllStaysAllUntilResolved() {
        ObjectGrant result = validator.intersect(everything(), everything());
        assertThat(result.readFields()).containsExactly("*");
        assertThat(result.writeFields()).containsExactly("*");
        assertThat(result.readDetails()).containsExactly("*");
        assertThat(result.readRelations()).containsExactly("*");
        assertThat(result.computeFields()).isEmpty();
    }

    /** 成员可写「全部」、上限可读清单且可写「全部」：展开后可写只到两层可读的交集。 */
    @Test
    void intersectThenResolveKeepsWriteInsideBothReads() {
        ObjectGrant member =
                grant(Set.of("READ", "UPDATE"), "ALL", Set.of("1", "2"), ALL, ALL, ALL, ALL, ALL);
        ObjectGrant result =
                validator.resolve(
                        validator.intersect(member, ceiling(Set.of("2"), ALL)), definition());
        assertThat(result.readFields()).containsExactly("2");
        assertThat(result.writeFields()).containsExactly("2");
    }

    /** 操作、记录范围、记录条件的合并与改动前逐项相同（期望值写死）。 */
    @Test
    void intersectKeepsActionScopeAndConditionSemantics() {
        DataScope first =
                new DataScope("AND", List.of(new DataScope.Condition("1", "EQ", "甲方")), List.of());
        DataScope second =
                new DataScope("AND", List.of(new DataScope.Condition("2", "GT", 10)), List.of());
        ObjectGrant a =
                new ObjectGrant(
                        "100",
                        Set.of("READ", "UPDATE", "DELETE"),
                        "ALL",
                        ALL,
                        ALL,
                        ALL,
                        ALL,
                        ALL,
                        ALL,
                        Map.of("READ", first, "DELETE", first),
                        Set.of("1"));
        ObjectGrant b =
                new ObjectGrant(
                        "100",
                        Set.of("READ", "UPDATE", "CREATE"),
                        "OWN",
                        ALL,
                        ALL,
                        ALL,
                        ALL,
                        ALL,
                        ALL,
                        Map.of("READ", second),
                        Set.of("1"));
        ObjectGrant result = validator.intersect(a, b);
        assertThat(result.objectId()).isEqualTo("100");
        assertThat(result.actions()).containsExactlyInAnyOrder("READ", "UPDATE");
        assertThat(result.scope()).isEqualTo("OWN");
        assertThat(result.actionScopes().keySet()).containsExactly("READ");
        assertThat(result.actionScopes().get("READ")).isEqualTo(DataScope.and(first, second));
        assertThat(result.computeFields()).isEmpty();
        assertThat(validator.intersect(a, a).scope()).isEqualTo("ALL");
        assertThat(validator.intersect(a, a).actionScopes().keySet())
                .containsExactlyInAnyOrder("READ", "DELETE");
    }
}
