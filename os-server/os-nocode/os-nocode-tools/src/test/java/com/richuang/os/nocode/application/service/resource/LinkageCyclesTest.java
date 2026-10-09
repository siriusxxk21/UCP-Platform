package com.richuang.os.nocode.application.service.resource;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.ApplicationAutomations;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.api.FieldRules;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 数据联动自动更新的字段级成环检查（纯函数）。对象双向、字段无环是合法配置；联动↔联动、联动↔本对象公式默认值、联动↔自动更新规则三种环 各报出完整路径；没开自动更新的联动不连跨对象的边。 */
class LinkageCyclesTest {
    private static final String FLOW = "100", VOUCHER = "200";

    private static FieldDefinition field(String id, String name, String type) {
        return new FieldDefinition(id, id, "c_" + id, name, type, 100, 18, 0, false, false, 0);
    }

    private static Relation ref(String id, String fieldId, String target) {
        return new Relation(
                id, id, "关系" + id, "REFERENCE", target, fieldId, null, false, "RESTRICT");
    }

    private static Definition object(
            String id,
            String name,
            List<FieldDefinition> fields,
            Map<String, FieldRules> rules,
            List<Relation> relations) {
        Map<String, FieldOptions> options = new HashMap<>();
        rules.forEach((key, value) -> options.put(key, FieldOptions.defaults().withRules(value)));
        return new Definition(
                id,
                "o" + id,
                name,
                null,
                "public",
                "biz_o" + id,
                "GENERATED",
                false,
                fields.getFirst().id(),
                Settings.defaults(),
                fields,
                options,
                relations,
                List.of(),
                List.of());
    }

    /** 资金流水：116 凭证状态、113 凭证摘要、118 凭证引用、120 备注。 */
    private static Definition flow(Map<String, FieldRules> rules) {
        return object(
                FLOW,
                "资金流水",
                List.of(
                        field("101", "户名", "TEXT"),
                        field("113", "凭证摘要", "TEXT"),
                        field("116", "凭证状态", "TEXT"),
                        field("118", "凭证", "INTEGER"),
                        field("120", "备注", "TEXT")),
                rules,
                List.of(ref("r118", "118", VOUCHER)));
    }

    /** 会计凭证：201 摘要、204 资金流水引用、214 凭证状态、216 流水户名、217 说明。 */
    private static Definition voucher(Map<String, FieldRules> rules) {
        return object(
                VOUCHER,
                "会计凭证",
                List.of(
                        field("201", "摘要", "TEXT"),
                        field("204", "资金流水", "INTEGER"),
                        field("214", "凭证状态", "TEXT"),
                        field("216", "流水户名", "TEXT"),
                        field("217", "说明", "TEXT")),
                rules,
                List.of(ref("r204", "204", FLOW)));
    }

    private static FieldRules linkage(
            String source, String valueField, Boolean autoUpdate, FieldRules.Condition... c) {
        return new FieldRules(
                null,
                new FieldRules.Linkage(
                        source, List.of(c), valueField, "FIRST", true, autoUpdate, null),
                null,
                null,
                null,
                null);
    }

    private static FieldRules.Condition currentRecord(String field) {
        return new FieldRules.Condition(field, "eq", "CURRENT_RECORD", null, null);
    }

    private static FieldRules.Condition recordKey(String formField) {
        return new FieldRules.Condition("$record", "eq", "FORM_FIELD", null, formField);
    }

    private static void check(
            Definition flow, Definition voucher, ApplicationAutomations.Config... a) {
        Map<String, DataCenter.Definition> pinned = new LinkedHashMap<>();
        pinned.put(FLOW, flow);
        pinned.put(VOUCHER, voucher);
        LinkageCycles.check(pinned, List.of(a));
    }

    /** 标杆场景：凭证从流水取户名（没开自动更新），流水从凭证取状态（开）。对象双向、字段无环，必须通过。 */
    @Test
    void benchmarkIsBidirectionalByObjectButAcyclicByField() {
        assertThatCode(
                        () ->
                                check(
                                        flow(
                                                Map.of(
                                                        "116",
                                                        linkage(
                                                                VOUCHER,
                                                                "214",
                                                                true,
                                                                currentRecord("204")))),
                                        voucher(
                                                Map.of(
                                                        "216",
                                                        linkage(
                                                                FLOW,
                                                                "101",
                                                                null,
                                                                recordKey("204"))))))
                .doesNotThrowAnyException();
        // 两条都开也无环：凭证.流水户名 ← 流水.户名，流水.凭证状态 ← 凭证.凭证状态，各走各的字段。
        assertThatCode(
                        () ->
                                check(
                                        flow(
                                                Map.of(
                                                        "116",
                                                        linkage(
                                                                VOUCHER,
                                                                "214",
                                                                true,
                                                                currentRecord("204")))),
                                        voucher(
                                                Map.of(
                                                        "216",
                                                        linkage(
                                                                FLOW,
                                                                "101",
                                                                true,
                                                                recordKey("204"))))))
                .doesNotThrowAnyException();
    }

    /** 联动 ↔ 联动：流水.凭证状态 ← 凭证.凭证状态，凭证.凭证状态 ← 流水.凭证状态。 */
    @Test
    void linkageToLinkageCycleReportsTheFullPath() {
        assertThatThrownBy(
                        () ->
                                check(
                                        flow(
                                                Map.of(
                                                        "116",
                                                        linkage(
                                                                VOUCHER,
                                                                "214",
                                                                true,
                                                                currentRecord("204")))),
                                        voucher(
                                                Map.of(
                                                        "214",
                                                        linkage(
                                                                FLOW,
                                                                "116",
                                                                true,
                                                                recordKey("204"))))))
                .hasMessage("数据联动自动更新存在循环：会计凭证 · 凭证状态 → 资金流水 · 凭证状态 → 会计凭证 · 凭证状态；请关闭其中一条联动的自动更新");
    }

    /** 没开自动更新的那一条不连跨对象的边：同样的两条联动只开一条就不成环。 */
    @Test
    void linkageWithoutAutoUpdateAddsNoCrossObjectEdge() {
        assertThatCode(
                        () ->
                                check(
                                        flow(
                                                Map.of(
                                                        "116",
                                                        linkage(
                                                                VOUCHER,
                                                                "214",
                                                                true,
                                                                currentRecord("204")))),
                                        voucher(
                                                Map.of(
                                                        "214",
                                                        linkage(
                                                                FLOW,
                                                                "116",
                                                                null,
                                                                recordKey("204"))))))
                .doesNotThrowAnyException();
    }

    /** 联动 ↔ 本对象公式默认值：流水.凭证状态 ← 凭证.说明；凭证.说明 = 公式(流水户名)；凭证.流水户名 ← 流水.凭证状态。 */
    @Test
    void linkageThroughDefaultFormulaCycleReportsTheFullPath() {
        assertThatThrownBy(
                        () ->
                                check(
                                        flow(
                                                Map.of(
                                                        "116",
                                                        linkage(
                                                                VOUCHER,
                                                                "217",
                                                                true,
                                                                currentRecord("204")))),
                                        voucher(
                                                Map.of(
                                                        "217",
                                                        new FieldRules(
                                                                null, null, "c_216", null, null,
                                                                null),
                                                        "216",
                                                        linkage(
                                                                FLOW,
                                                                "116",
                                                                true,
                                                                recordKey("204"))))))
                .hasMessage(
                        "数据联动自动更新存在循环：会计凭证 · 说明 → 资金流水 · 凭证状态 → 会计凭证 · 流水户名 → 会计凭证 ·"
                                + " 说明；请关闭其中一条联动的自动更新");
    }

    /** 联动 ↔ 自动更新规则（持续维护）：流水.凭证状态 ← 凭证.凭证状态；规则把流水.凭证状态写回凭证.凭证状态。 */
    @Test
    void linkageThroughMaintainRuleCycleReportsTheFullPath() {
        var maintain =
                new ApplicationAutomations.Config(
                        FLOW,
                        VOUCHER,
                        true,
                        "MAINTAIN",
                        Set.of(),
                        null,
                        new ApplicationAutomations.Binding("r204", "INCOMING"),
                        List.of(
                                new ApplicationAutomations.Assignment(
                                        "214", "MAX", "116", null, null)));
        var flow = flow(Map.of("116", linkage(VOUCHER, "214", true, currentRecord("204"))));
        assertThatThrownBy(() -> check(flow, voucher(Map.of()), maintain))
                .hasMessage("数据联动自动更新存在循环：会计凭证 · 凭证状态 → 资金流水 · 凭证状态 → 会计凭证 · 凭证状态；请关闭其中一条联动的自动更新");
        // 停用的规则、事件赋值规则不连边。
        var disabled =
                new ApplicationAutomations.Config(
                        FLOW,
                        VOUCHER,
                        false,
                        "MAINTAIN",
                        Set.of(),
                        null,
                        maintain.binding(),
                        maintain.assignments());
        assertThatCode(() -> check(flow, voucher(Map.of()), disabled)).doesNotThrowAnyException();
    }

    /** 没有任何开启自动更新的联动：一条边都不建，存量应用的发布不受影响（哪怕规则之间互相依赖）。 */
    @Test
    void applicationsWithoutAutoUpdateAreUntouched() {
        assertThatCode(
                        () ->
                                check(
                                        flow(
                                                Map.of(
                                                        "116",
                                                        linkage(
                                                                VOUCHER,
                                                                "214",
                                                                null,
                                                                recordKey("118")))),
                                        voucher(
                                                Map.of(
                                                        "214",
                                                        linkage(
                                                                FLOW,
                                                                "116",
                                                                null,
                                                                recordKey("204"))))))
                .doesNotThrowAnyException();
    }
}
