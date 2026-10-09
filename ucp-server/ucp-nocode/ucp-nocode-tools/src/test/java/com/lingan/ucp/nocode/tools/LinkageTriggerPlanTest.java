package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.RuleFixtures.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.FieldRules;
import com.lingan.ucp.nocode.metadata.service.object.LinkageTriggerPlan;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 反向索引的推导（纯函数）：锚点识别、没开自动更新不出行、签名只随决定求值结果的内容变化。 */
class LinkageTriggerPlanTest {
    private static final String FLOW = "100", VOUCHER = "200";

    private static FieldDefinition field(String id, String name, String type, Integer length) {
        return new FieldDefinition(
                id, id, "c_" + id, name, type, length, null, null, false, false, 0);
    }

    private static Definition voucher(String statusName, String statusType) {
        return object(
                VOUCHER,
                "会计凭证",
                List.of(
                        field("201", "摘要", "TEXT", 100),
                        field("204", "资金流水", "INTEGER", null),
                        field("206", "数量", "INTEGER", null),
                        field("214", statusName, statusType, 100)),
                options(),
                List.of(ref("r204", "204", FLOW)),
                List.of());
    }

    private static Definition flow(String name, Map<String, FieldRules> rules, String rounding) {
        Map<String, FieldOptions> options = new HashMap<>();
        rules.forEach((id, r) -> options.put(id, FieldOptions.defaults().withRules(r)));
        return object(
                FLOW,
                name,
                List.of(
                        field("101", "户名", "TEXT", 100),
                        field("113", "凭证摘要", "TEXT", 100),
                        field("116", "凭证状态", "TEXT", 100),
                        field("118", "凭证", "INTEGER", null),
                        field("119", "凭证数量", "INTEGER", null)),
                options,
                List.of(ref("r118", "118", VOUCHER)),
                List.of());
    }

    private static FieldRules rule(
            String valueField,
            String multiRow,
            Boolean autoUpdate,
            String emptyValue,
            FieldRules.Condition... conditions) {
        return new FieldRules(
                null,
                new FieldRules.Linkage(
                        VOUCHER,
                        List.of(conditions),
                        valueField,
                        multiRow,
                        true,
                        autoUpdate,
                        emptyValue),
                null,
                null,
                null,
                null);
    }

    private static FieldRules.Condition currentRecord() {
        return new FieldRules.Condition("204", "eq", "CURRENT_RECORD", null, null);
    }

    private static List<LinkageTriggerPlan.Row> derive(Definition flow, Definition voucher) {
        Map<String, DataCenter.Definition> pinned = new LinkedHashMap<>();
        pinned.put(VOUCHER, voucher);
        pinned.put(FLOW, flow);
        return LinkageTriggerPlan.derive(pinned, Map.of(FLOW, 33, VOUCHER, 7));
    }

    private static String signature(Definition flow, Definition voucher) {
        var rows = derive(flow, voucher);
        assertThat(rows).hasSize(1);
        return rows.getFirst().signature();
    }

    private static final FieldRules BASE = rule("214", "FIRST", true, "未登记", currentRecord());

    @Test
    void recognizesBothAnchorsAndPrefersCurrentRecord() {
        var rows =
                derive(
                        flow(
                                "资金流水",
                                Map.of(
                                        "116",
                                        BASE,
                                        "113",
                                        rule(
                                                "201",
                                                "FIRST",
                                                true,
                                                null,
                                                current("$record", "eq", "118")),
                                        "119",
                                        rule(
                                                "206",
                                                "FIRST",
                                                true,
                                                null,
                                                current("$record", "eq", "118"),
                                                currentRecord())),
                                null),
                        voucher("凭证状态", "TEXT"));
        assertThat(rows)
                .extracting(
                        LinkageTriggerPlan.Row::targetFieldId,
                        LinkageTriggerPlan.Row::anchor,
                        LinkageTriggerPlan.Row::anchorFieldId)
                .containsExactly(
                        tuple("113", "RECORD_KEY", "118"),
                        tuple("116", "CURRENT_RECORD", "204"),
                        // 两种锚点都有时登记 CURRENT_RECORD（不查库就知道牵动谁）。
                        tuple("119", "CURRENT_RECORD", "204"));
        assertThat(rows)
                .allSatisfy(
                        row -> {
                            assertThat(row.sourceObjectId()).isEqualTo(VOUCHER);
                            assertThat(row.targetObjectId()).isEqualTo(FLOW);
                            assertThat(row.targetObjectVersion()).isEqualTo(33);
                            assertThat(row.signature()).matches("[0-9a-f]{64}");
                        });
    }

    @Test
    void linkagesWithoutAutoUpdateProduceNoRows() {
        for (Boolean autoUpdate : new Boolean[] {null, false})
            assertThat(
                            derive(
                                    flow(
                                            "资金流水",
                                            Map.of(
                                                    "116",
                                                    rule(
                                                            "214",
                                                            "FIRST",
                                                            autoUpdate,
                                                            null,
                                                            current("$record", "eq", "118"))),
                                            null),
                                    voucher("凭证状态", "TEXT")))
                    .isEmpty();
        // 停用的字段、来源对象不在固定集合里、没有锚点：都不出行。
        var inactive =
                object(
                        FLOW,
                        "资金流水",
                        List.of(field("101", "户名", "TEXT", 100), field("116", "凭证状态", "TEXT", 100)),
                        options(
                                Map.entry(
                                        "116",
                                        FieldOptions.copyOf(FieldOptions.defaults())
                                                .state("INACTIVE")
                                                .rules(BASE)
                                                .build())),
                        List.of(),
                        List.of());
        assertThat(derive(inactive, voucher("凭证状态", "TEXT"))).isEmpty();
        assertThat(
                        LinkageTriggerPlan.derive(
                                Map.of(FLOW, flow("资金流水", Map.of("116", BASE), null)),
                                Map.of(FLOW, 33)))
                .isEmpty();
        assertThat(
                        derive(
                                flow("资金流水", Map.of("116", rule("214", "FIRST", true, null)), null),
                                voucher("凭证状态", "TEXT")))
                .isEmpty();
    }

    /** 签名不含显示名、对象版本号。 */
    @Test
    void signatureIgnoresDisplayNamesAndVersions() {
        String base = signature(flow("资金流水", Map.of("116", BASE), null), voucher("凭证状态", "TEXT"));
        assertThat(signature(flow("资金流水（改名）", Map.of("116", BASE), null), voucher("状态", "TEXT")))
                .isEqualTo(base);
        Map<String, DataCenter.Definition> pinned = new LinkedHashMap<>();
        pinned.put(VOUCHER, voucher("凭证状态", "TEXT"));
        pinned.put(FLOW, flow("资金流水", Map.of("116", BASE), null));
        assertThat(
                        LinkageTriggerPlan.derive(pinned, Map.of(FLOW, 99, VOUCHER, 1))
                                .getFirst()
                                .signature())
                .isEqualTo(base);
    }

    /** 条件、取值字段、多行档位、空值填入、来源字段类型任何一项变化，签名都变。 */
    @Test
    void signatureChangesWithAnythingThatAffectsTheResult() {
        var voucher = voucher("凭证状态", "TEXT");
        String base = signature(flow("资金流水", Map.of("116", BASE), null), voucher);
        var variants = new LinkedHashMap<String, String>();
        variants.put(
                "条件",
                signature(
                        flow(
                                "资金流水",
                                Map.of(
                                        "116",
                                        rule(
                                                "214",
                                                "FIRST",
                                                true,
                                                "未登记",
                                                currentRecord(),
                                                constant("206", "gt", "0"))),
                                null),
                        voucher));
        variants.put(
                "取值字段",
                signature(
                        flow(
                                "资金流水",
                                Map.of("116", rule("201", "FIRST", true, "未登记", currentRecord())),
                                null),
                        voucher));
        variants.put(
                "多行档位",
                signature(
                        flow(
                                "资金流水",
                                Map.of("116", rule("214", "ERROR", true, "未登记", currentRecord())),
                                null),
                        voucher));
        variants.put(
                "空值填入",
                signature(
                        flow(
                                "资金流水",
                                Map.of("116", rule("214", "FIRST", true, null, currentRecord())),
                                null),
                        voucher));
        variants.put(
                "来源字段类型",
                signature(flow("资金流水", Map.of("116", BASE), null), voucher("凭证状态", "TEXTAREA")));
        assertThat(variants.values()).doesNotContain(base);
        assertThat(new HashSet<>(variants.values())).hasSize(variants.size());
        // 多行档位 null 归一为拼接：与显式 CONCAT 同签名，与 FIRST 不同。
        assertThat(
                        signature(
                                flow(
                                        "资金流水",
                                        Map.of(
                                                "116",
                                                rule("214", null, true, "未登记", currentRecord())),
                                        null),
                                voucher))
                .isEqualTo(
                        signature(
                                flow(
                                        "资金流水",
                                        Map.of(
                                                "116",
                                                rule(
                                                        "214",
                                                        "CONCAT",
                                                        true,
                                                        "未登记",
                                                        currentRecord())),
                                        null),
                                voucher))
                .isNotEqualTo(base);
    }

    /** 取整档位只对金额目标计入签名：金额目标 null 归一为向下取整；非金额恒不计入。 */
    @Test
    void roundingCountsOnlyForMoneyTargets() {
        var money =
                List.of(
                        field("101", "户名", "TEXT", 100),
                        new FieldDefinition(
                                "103", "103", "c_103", "金额", "MONEY", null, 18, 0, false, false,
                                0));
        var source =
                object(
                        VOUCHER,
                        "会计凭证",
                        List.of(
                                field("201", "摘要", "TEXT", 100),
                                field("204", "资金流水", "INTEGER", null),
                                new FieldDefinition(
                                        "205", "205", "c_205", "合计", "MONEY", null, 18, 0, false,
                                        false, 0)),
                        options(),
                        List.of(ref("r204", "204", FLOW)),
                        List.of());
        java.util.function.Function<String, String> sign =
                rounding ->
                        signature(
                                object(
                                        FLOW,
                                        "资金流水",
                                        money,
                                        options(
                                                Map.entry(
                                                        "103",
                                                        FieldOptions.defaults()
                                                                .withRules(
                                                                        new FieldRules(
                                                                                null,
                                                                                rule(
                                                                                                "205",
                                                                                                "SUM",
                                                                                                true,
                                                                                                null,
                                                                                                currentRecord())
                                                                                        .linkage(),
                                                                                null,
                                                                                rounding,
                                                                                null,
                                                                                null)))),
                                        List.of(),
                                        List.of()),
                                source);
        assertThat(sign.apply(null)).isEqualTo(sign.apply("FLOOR"));
        assertThat(sign.apply("HALF_UP")).isNotEqualTo(sign.apply("FLOOR"));
    }
}
