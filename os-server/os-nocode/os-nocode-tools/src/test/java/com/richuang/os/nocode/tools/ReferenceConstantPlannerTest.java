package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.RuleFixtures.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.FieldRules;
import com.richuang.os.nocode.tools.ReferenceConstantMigrationPlanner.Stored;
import com.richuang.os.nocode.tools.ReferenceConstantMigrationReport.Row;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 存量转换的判定与改写（纯计算，目标行在内存里）：名称恰好对上 1 条才转换；同一对象里有转不了的值时整对象 BLOCKED；改写只换那一个值。 */
class ReferenceConstantPlannerTest {
    private static final String STATUS = "5413", PROPERTY = "5393", STAY = "6000";

    private static final Definition STATUS_DEF =
            object(
                    STATUS,
                    "管理状态",
                    List.of(field("541", "c_mc", "名称", "TEXT")),
                    options(),
                    List.of(),
                    List.of());
    private static final Definition PROPERTY_DEF =
            object(
                    PROPERTY,
                    "物件",
                    List.of(
                            field("531", "c_mc", "物件名", "TEXT"),
                            field("532", "c_gllx2", "管理状态", "INTEGER")),
                    options(),
                    List.of(ref("r532", "532", STATUS)),
                    List.of());

    /** 管理状态：1 民宿管理、2 一般管理、3 / 4 重复。 */
    private final ReferenceConstantMigrationPlanner.Targets targets =
            new ReferenceConstantMigrationPlanner.Targets() {
                @Override
                public Definition definition(String objectId) {
                    return Map.of(STATUS, STATUS_DEF, PROPERTY, PROPERTY_DEF).get(objectId);
                }

                @Override
                public Set<String> existing(String objectId, Collection<String> ids) {
                    Set<String> found = new LinkedHashSet<>(ids);
                    found.retainAll(Set.of("1", "2", "3", "4"));
                    return found;
                }

                @Override
                public List<Stored> rows(String objectId, int cap) {
                    return List.of(
                            new Stored("1", Map.of("541", "民宿管理")),
                            new Stored("2", Map.of("541", "一般管理")),
                            new Stored("3", Map.of("541", "重复")),
                            new Stored("4", Map.of("541", "重复")));
                }
            };

    private static Definition stay(FieldRules.Condition... filter) {
        return object(
                STAY,
                "入住记录",
                List.of(
                        field("601", "c_mc", "入住人", "TEXT"),
                        field("602", "c_wjmc", "物件名称", "INTEGER")),
                options(Map.entry("602", ruled(reference(null, filter)))),
                List.of(ref("r602", "602", PROPERTY)),
                List.of());
    }

    private List<Row> plan(Definition d) {
        return ReferenceConstantMigrationPlanner.plan(d, 7, targets);
    }

    @Test
    void uniqueNameConverts() {
        var rows = plan(stay(constant("532", "eq", "民宿管理")));
        assertThat(rows).hasSize(1);
        var row = rows.getFirst();
        assertThat(row.status()).isEqualTo("CONVERT");
        assertThat(row.after()).isEqualTo("1");
        assertThat(row.matches()).isEqualTo(1);
        assertThat(row.versionNo()).isEqualTo(7);
        assertThat(row.targetObjectName()).isEqualTo("管理状态");
        assertThat(row.describe()).isEqualTo("入住记录 · 物件名称 · 引用筛选 第 1 条「管理状态」");
    }

    @Test
    void legalValuesAreNotListed() {
        assertThat(
                        plan(
                                stay(
                                        constant("532", "eq", "1"),
                                        constant("532", "neq", 2),
                                        constant("532", "isNull", null))))
                .isEmpty();
        assertThat(plan(stay(constant("531", "eq", "民宿管理")))).as("非引用字段不管").isEmpty();
    }

    @Test
    void missingRecordIdIsListedEvenIfNumeric() {
        var rows = plan(stay(constant("532", "eq", "99")));
        assertThat(rows)
                .singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo("NO_MATCH"));
    }

    @Test
    void ambiguousAndUnknownNamesBlockTheWholeObject() {
        var rows =
                plan(
                        stay(
                                constant("532", "eq", "民宿管理"),
                                constant("532", "neq", "重复"),
                                constant("532", "neq", "没有这个")));
        assertThat(rows)
                .extracting(Row::status)
                .containsExactly("BLOCKED", "AMBIGUOUS", "NO_MATCH");
        assertThat(rows.get(0).after()).isEqualTo("1");
        assertThat(rows.get(1).matches()).isEqualTo(2);
        assertThat(rows.get(1).note()).contains("3、4");
        assertThat(rows.get(0).note()).contains("「重复」").contains("「没有这个」");
    }

    @Test
    void editsReplaceOnlyThatValueAndRefuseChangedValues() {
        var d = stay(constant("532", "eq", "民宿管理"), constant("532", "neq", "2"));
        var row = plan(d).getFirst();
        var edited =
                ReferenceConstantEdits.apply(d.fieldOptions(), d.details(), List.of(row), true);
        var filter = edited.main().get("602").rules().reference().filter();
        assertThat(filter.get(0).value()).isEqualTo("1");
        assertThat(filter.get(0).operator()).isEqualTo("eq");
        assertThat(filter.get(1))
                .isEqualTo(d.fieldOptions().get("602").rules().reference().filter().get(1));
        // 回滚方向：当前值是 1 才改回名称。
        var back = ReferenceConstantEdits.apply(edited.main(), d.details(), List.of(row), false);
        assertThat(back.main().get("602").rules().reference().filter().get(0).value())
                .isEqualTo("民宿管理");
        // 当前值已被别人改过：拒绝，不覆盖。
        assertThatThrownBy(
                        () ->
                                ReferenceConstantEdits.apply(
                                        d.fieldOptions(), d.details(), List.of(row), false))
                .hasMessageContaining("当前值已不是「1」");
    }

    @Test
    void valueReadsTheSamePosition() {
        var d = stay(constant("532", "eq", "民宿管理"));
        var row = plan(d).getFirst();
        assertThat(ReferenceConstantEdits.value(d, row)).isEqualTo("民宿管理");
    }
}
