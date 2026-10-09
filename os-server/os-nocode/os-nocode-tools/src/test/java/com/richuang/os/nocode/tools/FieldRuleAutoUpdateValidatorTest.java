package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.RuleFixtures.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.CalculationOptions;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.DocumentPolicy;
import com.richuang.os.nocode.api.FieldDefinition;
import com.richuang.os.nocode.api.FieldRules;
import com.richuang.os.nocode.metadata.service.object.FieldRuleValidator;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * 数据联动「来源变化时自动更新」的保存与发布校验（契约 3.1 的 S1–S9、3.2 的 F1–F10），每条一正一反，逐字断言报错文案。
 *
 * <p>夹具与标杆场景同形状：资金流水.凭证状态 ← 会计凭证.凭证状态，条件「凭证的『资金流水』等于 当前记录」。定义都是内存固定版本，不连库。
 */
class FieldRuleAutoUpdateValidatorTest {
    private static final String FLOW = "100", VOUCHER = "200", COMPANY = "300";
    private static final String P = "字段「凭证状态」的数据联动";

    private static FieldDefinition field(
            String id, String name, String type, Integer length, Integer precision, Integer scale) {
        return new FieldDefinition(
                id, id, "c_" + id, name, type, length, precision, scale, false, false, 0);
    }

    private static FieldOptions choices(Option... items) {
        return FieldOptions.copyOf(FieldOptions.defaults()).options(List.of(items)).build();
    }

    private static final FieldOptions STATES =
            choices(
                    new Option("wdj", "未登记", false),
                    new Option("ylr", "已录入", false),
                    new Option("zf", "作废", true));

    private static Definition company() {
        return object(
                COMPANY,
                "公司",
                List.of(field("301", "公司名称", "TEXT", 100, null, null)),
                options(),
                List.of(),
                List.of());
    }

    /** 会计凭证（来源）：204 资金流水引用、214 凭证状态、207 计算字段、215 有序落库计算字段、210 公司引用。 */
    private static Definition voucher(Map<String, FieldOptions> overrides) {
        var options =
                options(
                        Map.entry("214", STATES),
                        Map.entry("208", STATES),
                        Map.entry(
                                "207",
                                FieldOptions.copyOf(FieldOptions.defaults())
                                        .resultType("DECIMAL")
                                        .build()),
                        Map.entry(
                                "215",
                                FieldOptions.copyOf(FieldOptions.defaults())
                                        .resultType("DECIMAL")
                                        .calculation(
                                                new CalculationOptions(
                                                        "RUNNING_TOTAL",
                                                        "ON_SAVE",
                                                        null,
                                                        null,
                                                        "c_205",
                                                        "SUM",
                                                        "AND",
                                                        List.of(),
                                                        false,
                                                        List.of(),
                                                        null))
                                        .build()));
        options.putAll(overrides);
        return object(
                VOUCHER,
                "会计凭证",
                List.of(
                        field("201", "摘要", "TEXT", 100, null, null),
                        field("204", "资金流水", "INTEGER", null, null, null),
                        field("205", "合计", "MONEY", null, 18, 0),
                        field("206", "数量", "INTEGER", null, null, null),
                        field("207", "小计", "FORMULA", null, null, null),
                        field("208", "标签", "MULTI_SELECT", null, null, null),
                        field("209", "已审", "BOOLEAN", null, null, null),
                        field("210", "公司", "INTEGER", null, null, null),
                        field("211", "比率", "DECIMAL", null, 10, 2),
                        field("212", "经办人", "USER", null, null, null),
                        field("214", "凭证状态", "SELECT", null, null, null),
                        field("215", "累计", "FORMULA", null, null, null),
                        field("216", "制单日期", "DATE", null, null, null)),
                options,
                List.of(ref("r204", "204", FLOW), ref("r210", "210", COMPANY)),
                List.of());
    }

    private static final List<FieldDefinition> FLOW_FIELDS =
            List.of(
                    field("101", "户名", "TEXT", 100, null, null),
                    field("103", "金额", "MONEY", null, 18, 0),
                    field("106", "所属公司", "INTEGER", null, null, null),
                    field("109", "凭证张数", "INTEGER", null, null, null),
                    field("110", "已做凭证", "BOOLEAN", null, null, null),
                    field("111", "比率", "DECIMAL", null, 10, 2),
                    field("112", "经办人", "USER", null, null, null),
                    field("113", "凭证摘要", "TEXT", 100, null, null),
                    field("114", "凭证日期", "DATE", null, null, null),
                    field("116", "凭证状态", "SELECT", null, null, null),
                    field("117", "凭证标签", "MULTI_SELECT", null, null, null),
                    field("118", "凭证", "INTEGER", null, null, null));

    /** 资金流水（规则所在对象）：106 公司引用、118 凭证引用（指向会计凭证）。 */
    private static Definition flow(
            Map<String, FieldOptions> overrides, List<Detail> details, Settings settings) {
        var options = options(Map.entry("116", STATES), Map.entry("117", STATES));
        options.putAll(overrides);
        return new Definition(
                FLOW,
                "o" + FLOW,
                "资金流水",
                null,
                "public",
                "biz_o" + FLOW,
                "GENERATED",
                false,
                "101",
                settings,
                FLOW_FIELDS,
                options,
                List.of(ref("r106", "106", COMPANY), ref("r118", "118", VOUCHER)),
                List.of(),
                details);
    }

    private static Definition flow(Map<String, FieldOptions> overrides) {
        return flow(overrides, List.of(), Settings.defaults());
    }

    private static FieldRules.Condition currentRecord(String sourceField) {
        return new FieldRules.Condition(sourceField, "eq", "CURRENT_RECORD", null, null);
    }

    /** 流水上的一条联动：来源会计凭证。 */
    private static FieldRules rule(
            String valueField,
            Boolean readOnly,
            Boolean autoUpdate,
            String emptyValue,
            FieldRules.Condition... conditions) {
        return new FieldRules(
                null,
                new FieldRules.Linkage(
                        VOUCHER,
                        List.of(conditions),
                        valueField,
                        "FIRST",
                        readOnly,
                        autoUpdate,
                        emptyValue),
                null,
                null,
                null,
                null);
    }

    /** 标杆配置：流水.凭证状态 ← 凭证.凭证状态，凭证.资金流水 等于 当前记录，自动更新开。 */
    private static FieldOptions benchmark(String emptyValue) {
        return STATES.withRules(rule("214", true, true, emptyValue, currentRecord("204")));
    }

    private static void check(Definition flow, Definition voucher) {
        var published = Map.of(VOUCHER, voucher, COMPANY, company());
        FieldRuleValidator.validate(flow, published::get, id -> "「未知对象」未发布或已停用");
    }

    private static void check(Definition flow) {
        check(flow, voucher(Map.of()));
    }

    private static void rejects(Definition flow, String message) {
        assertThatThrownBy(() -> check(flow)).hasMessage(message);
    }

    private static void passes(Definition flow) {
        assertThatCode(() -> check(flow)).doesNotThrowAnyException();
    }

    @Test
    void benchmarkConfigurationPasses() {
        passes(flow(Map.of("116", benchmark("wdj"))));
        passes(flow(Map.of("116", benchmark(null))));
    }

    // ── 3.1 形状级 ──

    /** S1：开了自动更新必须只读。 */
    @Test
    void s1AutoUpdateRequiresReadOnly() {
        rejects(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule("214", false, true, null, currentRecord("204"))))),
                P + "：开启「来源变化时自动更新」的字段必须只读（可手改的联动不跟随来源变化），请先打开「当前字段只读」");
        // 只读为 null 按只读，同样可以开。
        passes(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule("214", null, true, null, currentRecord("204"))))));
        // 设计保存的逐字段形状校验就拦，不等到发布。
        assertThatThrownBy(
                        () ->
                                FieldRuleValidator.validateShape(
                                        FLOW_FIELDS.get(9),
                                        STATES.withRules(
                                                rule(
                                                        "214",
                                                        false,
                                                        true,
                                                        null,
                                                        currentRecord("204")))))
                .hasMessageContaining("必须只读");
    }

    /** S2：没开自动更新不能配「没有匹配记录时填入」。 */
    @Test
    void s2EmptyValueNeedsAutoUpdate() {
        for (Boolean autoUpdate : new Boolean[] {null, false})
            rejects(
                    flow(
                            Map.of(
                                    "116",
                                    STATES.withRules(
                                            rule(
                                                    "214",
                                                    true,
                                                    autoUpdate,
                                                    "wdj",
                                                    current("$record", "eq", "118"))))),
                    P + "：「没有匹配记录时填入」只在开启「来源变化时自动更新」后可用");
        // 空白当作没填，不报这一条。
        passes(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule(
                                                "214",
                                                true,
                                                null,
                                                " ",
                                                current("$record", "eq", "118"))))));
    }

    /** S3：这种类型的字段暂不支持「没有匹配记录时填入」。 */
    @Test
    void s3EmptyValueTypeNotSupported() {
        rejects(
                flow(
                        Map.of(
                                "114",
                                ruled(
                                        rule(
                                                "216",
                                                true,
                                                true,
                                                "2026-01-01",
                                                currentRecord("204"))))),
                "字段「凭证日期」的数据联动：这种类型的字段暂不支持「没有匹配记录时填入」");
        rejects(
                flow(
                        Map.of(
                                "117",
                                STATES.withRules(
                                        rule(
                                                "208",
                                                true,
                                                true,
                                                "[\"wdj\"]",
                                                currentRecord("204"))))),
                "字段「凭证标签」的数据联动：这种类型的字段暂不支持「没有匹配记录时填入」");
        // 目标是关联字段。
        rejects(
                flow(Map.of("106", ruled(rule("210", true, true, "1", currentRecord("204"))))),
                "字段「所属公司」的数据联动：这种类型的字段暂不支持「没有匹配记录时填入」");
        // 同样的类型不配空值填入就能开（日期在自动更新白名单内）。
        passes(flow(Map.of("114", ruled(rule("216", true, true, null, currentRecord("204"))))));
    }

    /** S4：字面量必须符合目标类型；金额只收整数（不取整）；文本不超过字段长度。 */
    @Test
    void s4EmptyValueMustFitTargetType() {
        rejects(
                flow(Map.of("109", ruled(rule("206", true, true, "abc", currentRecord("204"))))),
                "字段「凭证张数」的数据联动：「没有匹配记录时填入」的值「abc」不符合字段类型");
        rejects(
                flow(Map.of("109", ruled(rule("206", true, true, "1.5", currentRecord("204"))))),
                "字段「凭证张数」的数据联动：「没有匹配记录时填入」的值「1.5」不符合字段类型");
        rejects(
                flow(Map.of("103", ruled(rule("205", true, true, "12.5", currentRecord("204"))))),
                "字段「金额」的数据联动：金额按日元整数保存，「没有匹配记录时填入」请填写整数");
        rejects(
                flow(Map.of("103", ruled(rule("205", true, true, "12.0", currentRecord("204"))))),
                "字段「金额」的数据联动：金额按日元整数保存，「没有匹配记录时填入」请填写整数");
        rejects(
                flow(Map.of("103", ruled(rule("205", true, true, "十二", currentRecord("204"))))),
                "字段「金额」的数据联动：「没有匹配记录时填入」的值「十二」不符合字段类型");
        rejects(
                flow(Map.of("111", ruled(rule("211", true, true, "1.234", currentRecord("204"))))),
                "字段「比率」的数据联动：「没有匹配记录时填入」的值「1.234」不符合字段类型");
        rejects(
                flow(Map.of("110", ruled(rule("209", true, true, "yes", currentRecord("204"))))),
                "字段「已做凭证」的数据联动：「没有匹配记录时填入」的值「yes」不符合字段类型");
        rejects(
                flow(
                        Map.of(
                                "113",
                                ruled(
                                        rule(
                                                "201",
                                                true,
                                                true,
                                                "长".repeat(101),
                                                currentRecord("204"))))),
                "字段「凭证摘要」的数据联动：「没有匹配记录时填入」的值超过字段长度上限 100");
        passes(flow(Map.of("109", ruled(rule("206", true, true, "0", currentRecord("204"))))));
        passes(flow(Map.of("103", ruled(rule("205", true, true, "-12", currentRecord("204"))))));
        passes(flow(Map.of("111", ruled(rule("211", true, true, "1.23", currentRecord("204"))))));
        passes(flow(Map.of("110", ruled(rule("209", true, true, "false", currentRecord("204"))))));
        passes(
                flow(
                        Map.of(
                                "113",
                                ruled(
                                        rule(
                                                "201",
                                                true,
                                                true,
                                                "长".repeat(100),
                                                currentRecord("204"))))));
    }

    /** S5：「等于当前记录」只能用「等于」，且不带比较值。 */
    @Test
    void s5CurrentRecordOnlyEqualsWithoutValue() {
        String message = P + "：「等于当前记录」只能使用「等于」，且不需要比较值";
        for (var condition :
                List.of(
                        new FieldRules.Condition("204", "neq", "CURRENT_RECORD", null, null),
                        new FieldRules.Condition("204", "isNull", "CURRENT_RECORD", null, null),
                        new FieldRules.Condition("204", "eq", "CURRENT_RECORD", "1", null),
                        new FieldRules.Condition("204", "eq", "CURRENT_RECORD", null, "118")))
            rejects(
                    flow(Map.of("116", STATES.withRules(rule("214", true, true, null, condition)))),
                    message);
    }

    /** S6：「按记录匹配」不能与「当前记录」组合。 */
    @Test
    void s6RecordKeyCannotCombineWithCurrentRecord() {
        rejects(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule("214", true, true, null, currentRecord("$record"))))),
                P + "：「按记录匹配」不能与「当前记录」组合，请改选来源对象上的关联字段");
    }

    /** S7：「等于当前记录」的条件只能有一条；与其它条件并存没问题。 */
    @Test
    void s7OnlyOneCurrentRecordCondition() {
        rejects(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule(
                                                "214",
                                                true,
                                                true,
                                                null,
                                                currentRecord("204"),
                                                currentRecord("204"))))),
                P + "：「等于当前记录」的条件只能有一条");
        passes(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule(
                                                "214",
                                                true,
                                                true,
                                                null,
                                                currentRecord("204"),
                                                constant("206", "gt", "0"),
                                                current("201", "eq", "101"))))));
    }

    /** S8：没开自动更新的联动不能用「等于当前记录」。 */
    @Test
    void s8CurrentRecordNeedsAutoUpdate() {
        for (Boolean autoUpdate : new Boolean[] {null, false})
            rejects(
                    flow(
                            Map.of(
                                    "116",
                                    STATES.withRules(
                                            rule(
                                                    "214",
                                                    true,
                                                    autoUpdate,
                                                    null,
                                                    currentRecord("204"))))),
                    P + "：「等于当前记录」只用于开启了「来源变化时自动更新」的联动");
    }

    /** S9：引用筛选里不允许「等于当前记录」。 */
    @Test
    void s9ReferenceFilterRejectsCurrentRecord() {
        rejects(
                flow(Map.of("118", ruled(reference("201", currentRecord("204"))))),
                "字段「凭证」的引用筛选：条件值来源「CURRENT_RECORD」无效");
        passes(flow(Map.of("118", ruled(reference("201", constant("206", "gt", "0"))))));
    }

    // ── 3.2 完整级 ──

    /** F1：明细字段的联动不能开自动更新。 */
    @Test
    void f1DetailFieldNotSupported() {
        var on =
                detail(
                        "150",
                        "流水明细",
                        List.of(field("151", "摘要", "TEXT", 100, null, null)),
                        new HashMap<>(
                                Map.of(
                                        "151",
                                        ruled(
                                                rule(
                                                        "201",
                                                        true,
                                                        true,
                                                        null,
                                                        currentRecord("204"))))));
        rejects(
                flow(Map.of(), List.of(on), Settings.defaults()),
                "明细「流水明细」字段「摘要」的数据联动：明细字段的数据联动暂不支持自动更新");
        var off =
                detail(
                        "150",
                        "流水明细",
                        List.of(field("151", "摘要", "TEXT", 100, null, null)),
                        new HashMap<>(Map.of("151", ruled(rule("201", true, null, null)))));
        passes(flow(Map.of(), List.of(off), Settings.defaults()));
    }

    /** F2：来源对象是本对象。 */
    @Test
    void f2SourceCannotBeSelf() {
        java.util.function.Function<Boolean, FieldRules> self =
                autoUpdate ->
                        new FieldRules(
                                null,
                                new FieldRules.Linkage(
                                        FLOW,
                                        List.of(current("101", "eq", "101")),
                                        "101",
                                        "FIRST",
                                        true,
                                        autoUpdate,
                                        null),
                                null,
                                null,
                                null,
                                null);
        rejects(flow(Map.of("113", ruled(self.apply(true)))), "字段「凭证摘要」的数据联动：来源对象是本对象的联动暂不支持自动更新");
        passes(flow(Map.of("113", ruled(self.apply(null)))));
    }

    /** F3：目标字段类型不在一期白名单（目录、关联等）。 */
    @Test
    void f3TargetTypeWhitelist() {
        String tail = "这种类型的字段暂不支持自动更新（一期支持：文本、数值、金额、百分比、布尔、日期时间、单选、多选）";
        rejects(
                flow(Map.of("112", ruled(rule("212", true, true, null, currentRecord("204"))))),
                "字段「经办人」的数据联动：「经办人」" + tail);
        rejects(
                flow(Map.of("106", ruled(rule("210", true, true, null, currentRecord("204"))))),
                "字段「所属公司」的数据联动：「所属公司」" + tail);
        // 同样的目标不开自动更新仍是合法联动。
        passes(
                flow(
                        Map.of(
                                "112",
                                ruled(
                                        rule(
                                                "212",
                                                true,
                                                null,
                                                null,
                                                current("$record", "eq", "118"))))));
        // 白名单内的类型逐个可开。
        passes(flow(Map.of("109", ruled(rule("206", true, true, null, currentRecord("204"))))));
        passes(flow(Map.of("110", ruled(rule("209", true, true, null, currentRecord("204"))))));
        passes(flow(Map.of("113", ruled(rule("201", true, true, null, currentRecord("204"))))));
        passes(
                flow(
                        Map.of(
                                "117",
                                STATES.withRules(
                                        rule("208", true, true, null, currentRecord("204"))))));
    }

    /** F4：必须有锚点；固定值的「按记录匹配」不算锚点；两种锚点各自成立。 */
    @Test
    void f4NeedsAnchor() {
        String message =
                P
                        + "：自动更新需要一条按记录匹配的条件——「来源对象的关联字段 等于 当前记录」，或「按记录匹配 等于"
                        + " 当前字段（指向来源对象的关联字段）」；否则无法确定哪些记录要更新";
        rejects(flow(Map.of("116", STATES.withRules(rule("214", true, true, null)))), message);
        rejects(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule(
                                                "214",
                                                true,
                                                true,
                                                null,
                                                constant("206", "gt", "0"),
                                                constant("$record", "eq", "7"))))),
                message);
        // 「按记录匹配 等于 当前字段（指向来源对象的关联字段）」是另一种锚点。
        passes(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule(
                                                "214",
                                                true,
                                                true,
                                                null,
                                                current("$record", "eq", "118"))))));
        // 没开自动更新不要求锚点。
        passes(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule(
                                                "214",
                                                true,
                                                null,
                                                null,
                                                constant("206", "gt", "0"))))));
    }

    /** F5：「等于当前记录」只能用于来源对象上指向本对象的单选关联字段。 */
    @Test
    void f5CurrentRecordFieldMustReferenceThisObject() {
        String message = P + "：「等于当前记录」只能用于来源对象上指向「资金流水」的单选关联字段";
        // 指向别的对象的关联字段。
        rejects(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule("214", true, true, null, currentRecord("210"))))),
                message);
        // 普通字段。
        rejects(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule("214", true, true, null, currentRecord("206"))))),
                message);
        rejects(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule("214", true, true, null, currentRecord("299"))))),
                P + "：条件字段「299」已不存在或已停用");
        assertThatThrownBy(
                        () ->
                                check(
                                        flow(Map.of("116", benchmark(null))),
                                        voucher(Map.of("204", inactive()))))
                .hasMessage(P + "：条件字段「资金流水」已不存在或已停用");
    }

    /** F6：开自动更新时带入的来源字段不能是计算字段。 */
    @Test
    void f6ValueFieldCannotBeComputed() {
        rejects(
                flow(Map.of("111", ruled(rule("207", true, true, null, currentRecord("204"))))),
                "字段「比率」的数据联动：开启自动更新时，带入的来源字段「小计」不能是计算字段");
        passes(
                flow(
                        Map.of(
                                "111",
                                ruled(
                                        rule(
                                                "207",
                                                true,
                                                null,
                                                null,
                                                current("$record", "eq", "118"))))));
    }

    /** F7：开自动更新时条件左侧的来源字段不能是有序落库计算字段。 */
    @Test
    void f7ConditionFieldCannotBeOrderedStored() {
        rejects(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule(
                                                "214",
                                                true,
                                                true,
                                                null,
                                                currentRecord("204"),
                                                constant("215", "gt", "0"))))),
                P + "：开启自动更新时，条件字段「累计」不能是有序计算字段");
        passes(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule(
                                                "214",
                                                true,
                                                null,
                                                null,
                                                current("$record", "eq", "118"),
                                                constant("215", "gt", "0"))))));
    }

    /** F8：单选字段的「没有匹配记录时填入」必须是有效且未停用的选项编码；选项集为空时不拦。 */
    @Test
    void f8EmptyValueMustBeEnabledOptionCode() {
        rejects(
                flow(Map.of("116", benchmark("未登记"))),
                P + "：「没有匹配记录时填入」的值「未登记」不是字段「凭证状态」的有效选项，请从选项中选择");
        rejects(
                flow(Map.of("116", benchmark("zf"))),
                P + "：「没有匹配记录时填入」的值「zf」不是字段「凭证状态」的有效选项，请从选项中选择");
        passes(flow(Map.of("116", benchmark("wdj"))));
    }

    private static Settings lifecycle(String fieldId, List<String> locked) {
        return new Settings(
                null,
                null,
                null,
                null,
                new DocumentPolicy(
                        List.of(),
                        new DocumentPolicy.Lifecycle(
                                fieldId,
                                "wdj",
                                List.of(
                                        new DocumentPolicy.State(
                                                "wdj", "草稿", List.of(), List.of(), true),
                                        new DocumentPolicy.State(
                                                "ylr", "已确认", locked, List.of(), false)),
                                List.of())));
    }

    /** F9：单据生命周期的状态字段不能开自动更新。 */
    @Test
    void f9LifecycleStateFieldRejected() {
        rejects(
                flow(Map.of("116", benchmark(null)), List.of(), lifecycle("116", List.of())),
                P + "：由状态动作维护的状态字段不能开启自动更新");
        // 状态字段是别的字段、且没有锁定它时可以开。
        passes(flow(Map.of("116", benchmark(null)), List.of(), lifecycle("117", List.of("101"))));
    }

    /** F10：在任一单据状态下被锁定的字段不能开自动更新。 */
    @Test
    void f10LockedFieldRejected() {
        rejects(
                flow(Map.of("116", benchmark(null)), List.of(), lifecycle("117", List.of("116"))),
                P + "：字段在单据状态「已确认」下被锁定，不能开启自动更新；请先把它从该状态的锁定字段中移除");
        // 没开自动更新的联动不受这条限制。
        passes(
                flow(
                        Map.of(
                                "116",
                                STATES.withRules(
                                        rule(
                                                "214",
                                                true,
                                                null,
                                                null,
                                                current("$record", "eq", "118")))),
                        List.of(),
                        lifecycle("117", List.of("116"))));
    }

    /** autoUpdate 为 null 或 false 时 F1–F10 一条都不触发：把所有一期限制凑在一条没开的联动上，照样通过。 */
    @Test
    void restrictionsOnlyApplyWhenAutoUpdateIsOn() {
        for (Boolean autoUpdate : new Boolean[] {null, false}) {
            var detail =
                    detail(
                            "150",
                            "流水明细",
                            List.of(field("151", "摘要", "TEXT", 100, null, null)),
                            new HashMap<>(
                                    Map.of("151", ruled(rule("201", true, autoUpdate, null)))));
            var d =
                    flow(
                            Map.of(
                                    // 没有锚点、条件字段是有序计算字段、目标被状态锁定。
                                    "116",
                                    STATES.withRules(
                                            rule(
                                                    "214",
                                                    true,
                                                    autoUpdate,
                                                    null,
                                                    constant("215", "gt", "0"))),
                                    // 目标类型不在白名单。
                                    "112",
                                    ruled(rule("212", true, autoUpdate, null)),
                                    // 取值字段是计算字段。
                                    "111",
                                    ruled(rule("207", true, autoUpdate, null))),
                            List.of(detail),
                            lifecycle("117", List.of("116")));
            assertThatCode(() -> check(d))
                    .as("autoUpdate=" + autoUpdate)
                    .doesNotThrowAnyException();
        }
    }

    /** 应用发布按固定版本重跑同一套校验（validatePinned），不需要另写一份。 */
    @Test
    void pinnedValidationRunsTheSameChecks() {
        Map<String, DataCenter.Definition> pinned = new LinkedHashMap<>();
        pinned.put(FLOW, flow(Map.of("116", STATES.withRules(rule("214", true, true, null)))));
        pinned.put(VOUCHER, voucher(Map.of()));
        pinned.put(COMPANY, company());
        assertThatThrownBy(() -> FieldRuleValidator.validatePinned(pinned, id -> "未加入本应用"))
                .hasMessageStartingWith(P + "：自动更新需要一条按记录匹配的条件");
        pinned.put(FLOW, flow(Map.of("116", benchmark("wdj"))));
        assertThatCode(() -> FieldRuleValidator.validatePinned(pinned, id -> "未加入本应用"))
                .doesNotThrowAnyException();
    }
}
