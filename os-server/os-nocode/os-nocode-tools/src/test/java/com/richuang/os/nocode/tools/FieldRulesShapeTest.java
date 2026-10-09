package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.RuleFixtures.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.DataCenter.FieldOptions;
import com.richuang.os.nocode.api.FieldRules;
import com.richuang.os.nocode.api.SelectionFields;
import com.richuang.os.nocode.metadata.service.object.FieldRuleValidator;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 规则的静态形状（设计稿 3.5）：只有“且”、结构不嵌套、三选一互斥、条件上限及挑取值来源形状。 */
class FieldRulesShapeTest {
    /** 与设计入口 StrictRequestDecoder 相同：未知属性直接拒绝。 */
    private final ObjectMapper strict =
            new ObjectMapper()
                    .findAndRegisterModules()
                    .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private static void shape(String type, FieldOptions options) {
        FieldRuleValidator.validateShape(field("f1", "c_f1", "备注", type), options);
    }

    @Test
    void noOrNoNesting() {
        String withLogic =
                "{\"linkage\":{\"sourceObjectId\":\"1\",\"logic\":\"OR\",\"conditions\":[],"
                        + "\"valueFieldId\":\"2\"}}";
        assertThatThrownBy(() -> strict.readValue(withLogic, FieldRules.class))
                .hasMessageContaining("logic");
        String nestedGroup =
                "{\"reference\":{\"filter\":[{\"fieldId\":\"3\",\"operator\":\"eq\","
                        + "\"valueSource\":\"CONSTANT\",\"conditions\":[]}]}}";
        assertThatThrownBy(() -> strict.readValue(nestedGroup, FieldRules.class))
                .hasMessageContaining("conditions");
        // 值位置夹带对象（分组）在保存形状校验中拒绝，不当作“或”执行。
        var smuggled =
                ruled(
                        linkage(
                                "1",
                                "2",
                                "FIRST",
                                constant(
                                        "3",
                                        "eq",
                                        Map.of("logic", "OR", "conditions", List.of()))));
        assertThatThrownBy(() -> shape("TEXT", smuggled))
                .hasMessage("字段「备注」的数据联动：条件值格式无效（条件之间只有“且”，不支持分组或嵌套）");
    }

    @Test
    void exclusive() {
        var both =
                FieldOptions.copyOf(ruled(linkage("1", "2", "FIRST"))).defaultValue("固定值").build();
        assertThatThrownBy(() -> shape("TEXT", both)).hasMessage("「备注」同时配置了自定义默认值和数据联动，只能保留一种");
        var three =
                FieldOptions.copyOf(
                                ruled(
                                        new FieldRules(
                                                null,
                                                new FieldRules.Linkage(
                                                        "1", List.of(), "2", "FIRST", false, null,
                                                        null),
                                                "c_a",
                                                null,
                                                null,
                                                null)))
                        .defaultValue("1")
                        .build();
        assertThatThrownBy(() -> shape("INTEGER", three))
                .hasMessage("「备注」同时配置了自定义默认值、数据联动和公式默认值，只能保留一种");
        assertThatThrownBy(() -> shape("SELECT", ruled(formula("c_a", null))))
                .hasMessage("「备注」是选项类字段，不能设置公式默认值");
        // 选项类可以在候选来源上叠加联动。
        assertThatCode(() -> shape("SELECT", local("A").withRules(linkage("1", "2", "FIRST"))))
                .doesNotThrowAnyException();
    }

    @Test
    void conditionLimitsAndOperators() {
        var many = new FieldRules.Condition[21];
        Arrays.fill(many, constant("3", "eq", "x"));
        assertThatThrownBy(() -> shape("TEXT", ruled(linkage("1", "2", "FIRST", many))))
                .hasMessage("字段「备注」的数据联动：条件最多 20 条");
        var twenty = Arrays.copyOf(many, 20);
        assertThatCode(() -> shape("TEXT", ruled(linkage("1", "2", "FIRST", twenty))))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                shape(
                                        "TEXT",
                                        ruled(
                                                linkage(
                                                        "1",
                                                        "2",
                                                        "FIRST",
                                                        current("$record", "neq", "9")))))
                .hasMessage("字段「备注」的数据联动：「按记录匹配」只能使用「等于」");
        assertThatThrownBy(
                        () ->
                                shape(
                                        "TEXT",
                                        ruled(
                                                linkage(
                                                        "1",
                                                        "2",
                                                        "FIRST",
                                                        current("3", "between", "9")))))
                .hasMessage("字段「备注」的数据联动：「在范围内」只能使用固定值，并填写起止两个值");
        assertThatCode(
                        () ->
                                shape(
                                        "TEXT",
                                        ruled(
                                                linkage(
                                                        "1",
                                                        "2",
                                                        "FIRST",
                                                        constant(
                                                                "3",
                                                                "between",
                                                                List.of(
                                                                        "2026-01-01",
                                                                        "2026-12-31"))))))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                shape(
                                        "TEXT",
                                        ruled(
                                                linkage(
                                                        "1",
                                                        "2",
                                                        "FIRST",
                                                        new FieldRules.Condition(
                                                                "3", "eq", "CONSTANT", "x", "4")))))
                .hasMessage("字段「备注」的数据联动：固定值条件不能同时指定当前字段");
        assertThatThrownBy(() -> shape("TEXT", ruled(reference("5", constant("3", "or", "x")))))
                .hasMessage("字段「备注」的引用筛选：条件算子「or」无效");
    }

    /** 2026-10-01：为空 / 不为空不带比较值；不等于与其它带值算子一样必须有值；按记录匹配仍只允许等于。 */
    @Test
    void emptinessTakesNoValueAndNotEqualNeedsOne() {
        for (String operator : List.of("isNull", "notNull")) {
            assertThatCode(
                            () ->
                                    shape(
                                            "TEXT",
                                            ruled(reference("5", constant("3", operator, null)))))
                    .as(operator)
                    .doesNotThrowAnyException();
            assertThatCode(
                            () ->
                                    shape(
                                            "TEXT",
                                            ruled(
                                                    linkage(
                                                            "1",
                                                            "2",
                                                            "FIRST",
                                                            constant("3", operator, null)))))
                    .as(operator)
                    .doesNotThrowAnyException();
        }
        assertThatThrownBy(() -> shape("TEXT", ruled(reference("5", constant("3", "isNull", "x")))))
                .hasMessage("字段「备注」的引用筛选：「为空」不需要比较值，不能填写固定值或当前字段");
        assertThatThrownBy(
                        () ->
                                shape(
                                        "TEXT",
                                        ruled(
                                                linkage(
                                                        "1",
                                                        "2",
                                                        "FIRST",
                                                        current("3", "notNull", "4")))))
                .hasMessage("字段「备注」的数据联动：「不为空」不需要比较值，不能填写固定值或当前字段");
        assertThatThrownBy(
                        () ->
                                shape(
                                        "TEXT",
                                        ruled(
                                                reference(
                                                        "5",
                                                        new FieldRules.Condition(
                                                                "3",
                                                                "isNull",
                                                                "CONSTANT",
                                                                null,
                                                                "4")))))
                .hasMessage("字段「备注」的引用筛选：「为空」不需要比较值，不能填写固定值或当前字段");
        assertThatThrownBy(() -> shape("TEXT", ruled(reference("5", constant("3", "neq", null)))))
                .hasMessage("字段「备注」的引用筛选：条件缺少固定值");
        assertThatThrownBy(() -> shape("TEXT", ruled(reference("5", current("3", "neq", null)))))
                .hasMessage("字段「备注」的引用筛选：条件缺少当前字段");
        assertThatCode(() -> shape("TEXT", ruled(reference("5", constant("3", "neq", "x")))))
                .doesNotThrowAnyException();
        for (String operator : List.of("isNull", "notNull", "neq"))
            assertThatThrownBy(
                            () ->
                                    shape(
                                            "TEXT",
                                            ruled(
                                                    linkage(
                                                            "1",
                                                            "2",
                                                            "FIRST",
                                                            constant(
                                                                    "$record",
                                                                    operator,
                                                                    "neq".equals(operator)
                                                                            ? "9"
                                                                            : null)))))
                    .as(operator)
                    .hasMessage("字段「备注」的数据联动：「按记录匹配」只能使用「等于」");
    }

    /** 存量联动（没有 autoUpdate、emptyValue 两个键）的序列化结果必须逐字节不变：审批生效路径按对象定义摘要比对，一变在途审批全部失效。 */
    @Test
    void legacyLinkageSerializesByteIdentical() throws Exception {
        var plain = new ObjectMapper().findAndRegisterModules();
        String golden =
                "{\"linkage\":{\"sourceObjectId\":\"1\",\"conditions\":[{\"fieldId\":\"3\","
                        + "\"operator\":\"eq\",\"valueSource\":\"FORM_FIELD\",\"value\":null,"
                        + "\"formFieldId\":\"4\"}],\"valueFieldId\":\"2\",\"multiRow\":\"FIRST\","
                        + "\"readOnly\":true}}";
        var legacy = strict.readValue(golden, FieldRules.class);
        assertThat(legacy.linkage().autoUpdate()).isNull();
        assertThat(legacy.linkage().emptyValue()).isNull();
        assertThat(legacy.linkage().autoUpdateOn()).isFalse();
        assertThat(plain.writeValueAsString(legacy)).isEqualTo(golden);
        // 设计保存的归一也不改它：没有这两个键就是没有，不补 false、不补 true。
        assertThat(plain.writeValueAsString(FieldRuleValidator.normalize(legacy)))
                .isEqualTo(golden);
    }

    /** 新键的 JSON 往返；严格解码（未知属性即拒绝）接受这两个键与「等于当前记录」条件。 */
    @Test
    void autoUpdateKeysRoundTripUnderStrictDecoding() throws Exception {
        var plain = new ObjectMapper().findAndRegisterModules();
        String configured =
                "{\"linkage\":{\"sourceObjectId\":\"1\",\"conditions\":[{\"fieldId\":\"3\","
                        + "\"operator\":\"eq\",\"valueSource\":\"CURRENT_RECORD\",\"value\":null,"
                        + "\"formFieldId\":null}],\"valueFieldId\":\"2\",\"multiRow\":\"FIRST\","
                        + "\"readOnly\":true,\"autoUpdate\":true,\"emptyValue\":\"wdj\"}}";
        var rules = strict.readValue(configured, FieldRules.class);
        assertThat(rules.linkage().autoUpdateOn()).isTrue();
        assertThat(rules.linkage().emptyValue()).isEqualTo("wdj");
        assertThat(rules.linkage().conditions().getFirst().valueSource())
                .isEqualTo("CURRENT_RECORD");
        assertThat(plain.writeValueAsString(rules)).isEqualTo(configured);
        // 省略 value / formFieldId 的写法（前端写出形态）同样可解码。
        var compact =
                strict.readValue(
                        "{\"linkage\":{\"sourceObjectId\":\"1\",\"conditions\":[{\"fieldId\":\"3\","
                                + "\"operator\":\"eq\",\"valueSource\":\"CURRENT_RECORD\"}],"
                                + "\"valueFieldId\":\"2\",\"autoUpdate\":true}}",
                        FieldRules.class);
        assertThat(compact.linkage().conditions().getFirst().value()).isNull();
        assertThat(compact.linkage().conditions().getFirst().formFieldId()).isNull();
        assertThat(compact.linkage().autoUpdateOn()).isTrue();
    }

    /** 存储归一：autoUpdate 只有 true 才保留（false 归一为键不存在），空白的 emptyValue 归一为 null；null 绝不补成 true。 */
    @Test
    void normalizeKeepsOnlyTrueAndDropsBlankEmptyValue() throws Exception {
        var plain = new ObjectMapper().findAndRegisterModules();
        java.util.function.BiFunction<Boolean, String, FieldRules> rules =
                (autoUpdate, emptyValue) ->
                        new FieldRules(
                                null,
                                new FieldRules.Linkage(
                                        "1", List.of(), "2", "FIRST", true, autoUpdate, emptyValue),
                                null,
                                null,
                                null,
                                null);
        var off = FieldRuleValidator.normalize(rules.apply(false, "  "));
        assertThat(off.linkage().autoUpdate()).isNull();
        assertThat(off.linkage().emptyValue()).isNull();
        assertThat(plain.writeValueAsString(off))
                .doesNotContain("autoUpdate")
                .doesNotContain("emptyValue");
        var absent = FieldRuleValidator.normalize(rules.apply(null, null));
        assertThat(absent.linkage().autoUpdate()).isNull();
        assertThat(absent.linkage().autoUpdateOn()).isFalse();
        var on = FieldRuleValidator.normalize(rules.apply(true, "wdj"));
        assertThat(on.linkage().autoUpdate()).isTrue();
        assertThat(on.linkage().emptyValue()).isEqualTo("wdj");
        // 归一不动其它内容，也照旧清掉只在投影里出现的 dependsOn / readOnly。
        var projected =
                new FieldRules(
                        null,
                        new FieldRules.Linkage("1", List.of(), "2", "FIRST", true, false, null),
                        null,
                        null,
                        List.of("9"),
                        true);
        var stored = FieldRuleValidator.normalize(projected);
        assertThat(stored.dependsOn()).isNull();
        assertThat(stored.readOnly()).isNull();
        assertThat(stored.linkage().autoUpdate()).isNull();
        assertThat(stored.linkage().readOnly()).isTrue();
    }

    @Test
    void multiSelectTargetCannotConcat() {
        assertThatThrownBy(
                        () -> shape("MULTI_SELECT", local("A").withRules(linkage("1", "2", null))))
                .hasMessage("字段「备注」的数据联动：多选字段不能使用「拼接成一行」，请选择「取第一行」或「报错」");
        assertThatCode(
                        () ->
                                shape(
                                        "MULTI_SELECT",
                                        local("A").withRules(linkage("1", "2", "ERROR"))))
                .doesNotThrowAnyException();
    }

    @Test
    void pickValueSourceShape() {
        var select = field("f1", "c_f1", "银行类型", "SELECT");
        assertThatCode(() -> SelectionFields.validate(select, pick("100", "107")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> SelectionFields.validate(select, pick(null, "107")))
                .hasMessage("挑取值须选择来源对象和来源字段");
        assertThatThrownBy(
                        () ->
                                SelectionFields.validate(
                                        field("f2", "c_f2", "地区", "REGION"), pick("100", "107")))
                .hasMessage("挑取值只适用于单选和多选字段");
        assertThatThrownBy(
                        () ->
                                SelectionFields.validate(
                                        select,
                                        FieldOptions.copyOf(pick("100", "107"))
                                                .options(
                                                        List.of(
                                                                new com.richuang.os.nocode.api
                                                                        .DataCenter.Option(
                                                                        "A", "甲", false)))
                                                .build()))
                .hasMessage("动态来源不能同时维护局部选项");
        var localWithSource =
                FieldOptions.defaults()
                        .withSelection(
                                new SelectionFields.Source(
                                        "LOCAL_OPTIONS",
                                        null,
                                        null,
                                        List.of(),
                                        false,
                                        List.of(),
                                        "NONE",
                                        null,
                                        "100",
                                        "107"));
        assertThatThrownBy(() -> SelectionFields.validate(select, localWithSource))
                .hasMessage("只有挑取值来源可以指定来源对象字段");
    }
}
