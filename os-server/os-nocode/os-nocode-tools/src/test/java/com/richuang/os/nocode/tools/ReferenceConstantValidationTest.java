package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.RuleFixtures.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.FieldRules;
import com.richuang.os.nocode.api.ReferenceRecordLookup;
import com.richuang.os.nocode.metadata.service.object.FieldRuleValidator;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * 引用字段的条件固定值（业务方 2026-10-04 故障：入住记录.物件名称 的引用筛选「管理状态 等于 固定值 民宿管理」，存的是名称而库里是记录 ID）。 对象设计保存 /
 * 发布：先核格式（转不成外键列的类型即拒），再核目标对象里有没有这条记录。应用发布（含自动跟随）不给 records，不核这一项：
 * 跟随一次只同步一个对象，另一个对象还带着存量坏值时挡住会让跟随永远发不出去。逐字断言报错。
 */
class ReferenceConstantValidationTest {
    private static final String STATUS = "5413", PROPERTY = "5393", STAY = "6000";

    /** 管理状态：民宿管理 = 1，一般管理 = 2。 */
    private static Definition status() {
        return object(
                STATUS,
                "管理状态",
                List.of(field("541", "c_mc", "名称", "TEXT")),
                options(),
                List.of(),
                List.of());
    }

    /** 物件：管理状态是指向「管理状态」的引用字段（外键列 INTEGER）。 */
    private static Definition property() {
        return object(
                PROPERTY,
                "物件",
                List.of(
                        field("531", "c_mc", "物件名", "TEXT"),
                        field("532", "c_gllx2", "管理状态", "INTEGER")),
                options(),
                List.of(ref("r532", "532", STATUS)),
                List.of());
    }

    private static Definition stay(FieldRules rules, List<Detail> details) {
        return object(
                STAY,
                "入住记录",
                List.of(
                        field("601", "c_mc", "入住人", "TEXT"),
                        field("602", "c_wjmc", "物件名称", "INTEGER")),
                options(Map.entry("602", ruled(rules))),
                List.of(ref("r602", "602", PROPERTY)),
                details);
    }

    private static Definition stay(Object value) {
        return stay(reference(null, constant("532", "eq", value)), List.of());
    }

    private static final Map<String, Definition> ALL =
            Map.of(STATUS, status(), PROPERTY, property());

    /** 记录核对：管理状态里只有 1、2。calls 记下被问到的 (对象, ID)。 */
    private final List<String> calls = new ArrayList<>();

    private final ReferenceRecordLookup records =
            (objectId, ids) -> {
                ids.forEach(id -> calls.add(objectId + ":" + id));
                if (!STATUS.equals(objectId)) return null;
                Set<String> found = new LinkedHashSet<>();
                for (String id : ids) if (Set.of("1", "2").contains(id)) found.add(id);
                return found;
            };

    private void check(Definition root, ReferenceRecordLookup lookup) {
        FieldRuleValidator.validate(
                root,
                id -> id.equals(root.objectId()) ? root : ALL.get(id),
                id -> "（ID " + id + "）不存在",
                (f, o) -> List.of(),
                lookup);
    }

    private static final String STORED_TEXT = "字段「物件名称」的引用筛选：条件「管理状态」的固定值「民宿管理」不是「管理状态」里的记录，请重新选择";

    @Test
    void storedNameIsRejectedOnDesignSave() {
        assertThatThrownBy(() -> check(stay("民宿管理"), records)).hasMessage(STORED_TEXT);
        assertThat(calls).as("格式不对时不必查库").isEmpty();
    }

    @Test
    void applicationPublishDoesNotCheckReferenceConstants() {
        // 应用发布 / 自动跟随不给 records：存量坏值不在这里挡（由对象设计端阻断、运行时报明白的错）。
        check(stay("民宿管理"), null);
        check(stay("99"), null);
    }

    @Test
    void existingRecordIdPasses() {
        check(stay("1"), records);
        check(stay(2), records);
        assertThat(calls).containsExactly(STATUS + ":1", STATUS + ":2");
    }

    @Test
    void missingRecordIdIsRejectedOnlyWhenRecordsAreChecked() {
        assertThatThrownBy(() -> check(stay("99"), records))
                .hasMessage("字段「物件名称」的引用筛选：条件「管理状态」的固定值「99」不是「管理状态」里的记录，请重新选择");
    }

    @Test
    void unanswerableLookupStillChecksFormat() {
        check(stay("99"), (objectId, ids) -> null);
        assertThatThrownBy(() -> check(stay("民宿管理"), (objectId, ids) -> null))
                .hasMessage(STORED_TEXT);
    }

    @Test
    void onlyTheToolRollbackMayRestoreStoredNames() {
        // 存量转换工具回滚：写回转换前的名称，这一项让路；出了作用域立即恢复。
        assertThat(
                        FieldRuleValidator.restoringStoredConstants(
                                () -> {
                                    check(stay("民宿管理"), records);
                                    return true;
                                }))
                .isTrue();
        assertThatThrownBy(() -> check(stay("民宿管理"), records)).hasMessage(STORED_TEXT);
    }

    @Test
    void notEqualsAndEmptinessFollowTheSameRule() {
        assertThatThrownBy(
                        () ->
                                check(
                                        stay(
                                                reference(null, constant("532", "neq", "一般管理")),
                                                List.of()),
                                        records))
                .hasMessageContaining("固定值「一般管理」不是「管理状态」里的记录");
        check(stay(reference(null, constant("532", "isNull", null)), List.of()), records);
        // 非引用字段的固定值不受影响。
        check(stay(reference(null, constant("531", "eq", "银座公寓")), List.of()), records);
    }

    @Test
    void linkageConditionsAreCheckedToo() {
        var rules = linkage(PROPERTY, "531", "FIRST", constant("532", "eq", "民宿管理"));
        var root =
                object(
                        STAY,
                        "入住记录",
                        List.of(
                                field("601", "c_mc", "入住人", "TEXT"),
                                field("603", "c_wjm", "物件名", "TEXT")),
                        options(Map.entry("603", ruled(rules))),
                        List.of(),
                        List.of());
        assertThatThrownBy(() -> check(root, records))
                .hasMessage("字段「物件名」的数据联动：条件「管理状态」的固定值「民宿管理」不是「管理状态」里的记录，请重新选择");
        var fixed =
                object(
                        STAY,
                        "入住记录",
                        root.fields(),
                        options(
                                Map.entry(
                                        "603",
                                        ruled(
                                                linkage(
                                                        PROPERTY,
                                                        "531",
                                                        "FIRST",
                                                        constant("532", "eq", "1"))))),
                        List.of(),
                        List.of());
        check(fixed, records);
    }

    @Test
    void detailFieldRulesAreCheckedToo() {
        var detail =
                detail(
                        "650",
                        "同住人",
                        List.of(field("651", "c_wj", "物件", "INTEGER")),
                        options(
                                Map.entry(
                                        "651",
                                        ruled(reference(null, constant("532", "eq", "民宿管理"))))));
        var root =
                object(
                        STAY,
                        "入住记录",
                        List.of(field("601", "c_mc", "入住人", "TEXT")),
                        options(),
                        List.of(
                                new Relation(
                                        "r651",
                                        "r651",
                                        "关系r651",
                                        "REFERENCE",
                                        PROPERTY,
                                        "651",
                                        null,
                                        false,
                                        "RESTRICT",
                                        "650")),
                        List.of(detail));
        assertThatThrownBy(() -> check(root, records))
                .hasMessage("明细「同住人」字段「物件」的引用筛选：条件「管理状态」的固定值「民宿管理」不是「管理状态」里的记录，请重新选择");
    }
}
