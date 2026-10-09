package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.RuleFixtures.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.FieldRules;
import com.lingan.ucp.nocode.api.SelectionFields;
import com.lingan.ucp.nocode.metadata.service.object.FieldRuleValidator;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 发布校验（设计稿 7.2、15.3.4、15.4.3）逐行一例，逐字断言报错文案；来源对象为内存固定定义。 */
class FieldRuleValidatorTest {
    private static final String FLOW = "100", VOUCHER = "200", COMPANY = "300", ACCOUNT = "400";

    private static Definition company() {
        return object(
                COMPANY,
                "公司",
                List.of(field("301", "c_mc", "公司名称", "TEXT")),
                options(),
                List.of(),
                List.of());
    }

    private static Definition flow(Map<String, FieldOptions> overrides) {
        var options =
                options(
                        Map.entry("107", local("A", "B")),
                        Map.entry("108", dictionary("bank_type")),
                        Map.entry("113", computed("TEXT")),
                        Map.entry("114", computed("DECIMAL")),
                        Map.entry("116", local("A", "B")),
                        Map.entry("117", local("A", "B")));
        options.putAll(overrides);
        return object(
                FLOW,
                "资金流水",
                List.of(
                        field("101", "c_hm", "户名", "TEXT"),
                        field("102", "c_khh", "开户行", "TEXT"),
                        field("103", "c_je", "金额", "MONEY"),
                        field("104", "c_fj", "附件", "ATTACHMENT"),
                        field("105", "c_khrq", "开户日期", "DATE"),
                        field("106", "c_gs", "所属公司", "INTEGER"),
                        field("107", "c_zt", "状态", "SELECT"),
                        field("108", "c_lx", "类型", "SELECT"),
                        field("109", "c_wz", "网址", "URL"),
                        field("110", "c_bh", "编号", "UUID"),
                        field("111", "c_lsbh", "流水编号", "AUTO_NUMBER"),
                        field("112", "c_bz", "备注", "TEXTAREA"),
                        field("113", "c_zy", "摘要", "FORMULA"),
                        field("114", "c_xj", "小计", "FORMULA"),
                        field("115", "c_xq", "详情", "RICH_TEXT"),
                        field("116", "c_pzzt", "凭证状态", "SELECT"),
                        field("117", "c_pzbq", "凭证标签", "MULTI_SELECT")),
                options,
                List.of(ref("r106", "106", COMPANY)),
                List.of());
    }

    private static Definition account() {
        return object(
                ACCOUNT,
                "口座",
                List.of(
                        field("401", "c_mc", "口座名称", "TEXT"),
                        field("402", "c_gs", "所属公司", "INTEGER"),
                        field("403", "c_fj", "附件", "ATTACHMENT"),
                        field("404", "c_kzbh", "口座编号", "AUTO_NUMBER")),
                options(),
                List.of(ref("r402", "402", COMPANY)),
                List.of());
    }

    private static final List<FieldDefinition> VOUCHER_FIELDS =
            List.of(
                    field("201", "c_yhm", "银行名", "TEXT"),
                    field("202", "c_gs", "公司", "INTEGER"),
                    field("203", "c_gsm", "公司名", "TEXT"),
                    field("204", "c_zjls", "资金流水", "INTEGER"),
                    field("205", "c_hj", "合计", "MONEY"),
                    field("206", "c_dj", "单价", "DECIMAL"),
                    field("207", "c_sl", "数量", "INTEGER"),
                    field("208", "c_yhlx", "银行类型", "SELECT"),
                    field("209", "c_bz", "备注", "TEXT"),
                    field("210", "c_kz", "口座", "INTEGER"),
                    field("211", "c_je", "金额", "MONEY"),
                    field("212", "c_sm", "说明", "TEXTAREA"),
                    field("213", "c_lj", "链接", "URL"),
                    field("214", "c_shzt", "审核状态", "SELECT"));

    private static Definition voucher(Map<String, FieldOptions> overrides, List<Detail> details) {
        var options = options(Map.entry("208", local("A", "B")), Map.entry("214", local("A", "B")));
        options.putAll(overrides);
        return object(
                VOUCHER,
                "凭证",
                VOUCHER_FIELDS,
                options,
                List.of(
                        ref("r202", "202", COMPANY),
                        ref("r204", "204", FLOW),
                        ref("r210", "210", ACCOUNT)),
                details);
    }

    private static Definition voucher(Map<String, FieldOptions> overrides) {
        return voucher(overrides, List.of());
    }

    private static Detail entries(Map<String, FieldOptions> options) {
        return detail(
                "250",
                "凭证明细",
                List.of(
                        field("251", "c_je", "金额", "MONEY"),
                        field("252", "c_kmfl", "科目分类", "TEXT"),
                        field("253", "c_zy", "摘要", "TEXT"),
                        field("254", "c_se", "税额", "MONEY")),
                new HashMap<>(options));
    }

    private static Detail expenses(Map<String, FieldOptions> options) {
        return detail(
                "260", "费用明细", List.of(field("261", "c_sm", "说明", "TEXT")), new HashMap<>(options));
    }

    /** 计算字段只带结果类型：发布校验按结果类型判断能否带入。 */
    private static FieldOptions computed(String resultType) {
        return FieldOptions.copyOf(FieldOptions.defaults()).resultType(resultType).build();
    }

    private static void check(Definition d) {
        check(d, flow(Map.of()));
    }

    private static void check(Definition d, Definition source) {
        var published = Map.of(FLOW, source, COMPANY, company(), ACCOUNT, account());
        FieldRuleValidator.validate(d, published::get, id -> "「未知对象」未发布或已停用");
    }

    private static void rejects(Definition d, String message) {
        assertThatThrownBy(() -> check(d)).hasMessage(message);
    }

    @Test
    void validConfigurationsPass() {
        var d =
                voucher(
                        Map.of(
                                "201",
                                ruled(
                                        linkage(
                                                FLOW,
                                                "102",
                                                "FIRST",
                                                current("$record", "eq", "204"),
                                                current("106", "eq", "202"),
                                                constant("101", "like", "银行"))),
                                "210",
                                ruled(reference("401", current("402", "eq", "202"))),
                                "205",
                                ruled(formula("c_dj * c_sl", "HALF_UP")),
                                "208",
                                pick(FLOW, "107").withRules(linkage(FLOW, "107", "FIRST"))));
        assertThatCode(() -> check(d)).doesNotThrowAnyException();
    }

    @Test
    void sourceObjectUnpublished() {
        rejects(
                voucher(Map.of("201", ruled(linkage("999", "102", "FIRST")))),
                "字段「银行名」的数据联动：来源对象「未知对象」未发布或已停用");
    }

    @Test
    void sourceFieldMissingOrInactive() {
        rejects(
                voucher(Map.of("201", ruled(linkage(FLOW, "199", "FIRST")))),
                "字段「银行名」的数据联动：来源字段「199」已不存在或已停用");
        var d = voucher(Map.of("201", ruled(linkage(FLOW, "102", "FIRST"))));
        assertThatThrownBy(() -> check(d, flow(Map.of("102", inactive()))))
                .hasMessage("字段「银行名」的数据联动：来源字段「开户行」已不存在或已停用");
        rejects(
                voucher(
                        Map.of(
                                "201",
                                ruled(linkage(FLOW, "102", "FIRST", constant("198", "eq", "x"))))),
                "字段「银行名」的数据联动：条件字段「198」已不存在或已停用");
    }

    @Test
    void conditionFieldNotFilterable() {
        rejects(
                voucher(
                        Map.of(
                                "201",
                                ruled(linkage(FLOW, "102", "FIRST", constant("104", "eq", "1"))))),
                "字段「银行名」的数据联动：条件字段「附件」不支持筛选");
    }

    @Test
    void urlAndUuidAreNotFilterable() {
        rejects(
                voucher(
                        Map.of(
                                "201",
                                ruled(linkage(FLOW, "102", "FIRST", constant("109", "eq", "x"))))),
                "字段「银行名」的数据联动：条件字段「网址」不支持筛选");
        rejects(
                voucher(
                        Map.of(
                                "201",
                                ruled(linkage(FLOW, "102", "FIRST", constant("110", "eq", "x"))))),
                "字段「银行名」的数据联动：条件字段「编号」不支持筛选");
    }

    @Test
    void operatorMustFitConditionFieldType() {
        rejects(
                voucher(
                        Map.of(
                                "201",
                                ruled(
                                        linkage(
                                                FLOW,
                                                "102",
                                                "FIRST",
                                                constant("105", "like", "2026"))))),
                "字段「银行名」的数据联动：「开户日期」不能用「包含」");
    }

    /**
     * 2026-10-01 算子表：选项、关联、文本可用「不等于」；可作条件的字段都可用「为空 / 不为空」；日期没有「不等于」、多选只有包含与空值判断。 引用筛选与数据联动两处共用同一张表。
     */
    @Test
    void notEqualAndEmptinessFollowOperatorTable() {
        var allowed =
                List.of(
                        constant("116", "neq", "A"),
                        constant("116", "isNull", null),
                        constant("116", "notNull", null),
                        constant("106", "neq", "9"),
                        constant("106", "isNull", null),
                        constant("101", "neq", "张三"),
                        constant("101", "isNull", null),
                        constant("111", "notNull", null),
                        constant("103", "neq", "5"),
                        constant("103", "isNull", null),
                        constant("105", "isNull", null),
                        constant("105", "notNull", null),
                        constant("117", "isNull", null),
                        constant("117", "notNull", null),
                        constant("114", "isNull", null));
        for (var condition : allowed) {
            String label = condition.fieldId() + " " + condition.operator();
            assertThatCode(
                            () ->
                                    check(
                                            voucher(
                                                    Map.of(
                                                            "201",
                                                            ruled(
                                                                    linkage(
                                                                            FLOW, "102", "FIRST",
                                                                            condition))))))
                    .as("数据联动 " + label)
                    .doesNotThrowAnyException();
            assertThatCode(() -> check(voucher(Map.of("204", ruled(reference(null, condition))))))
                    .as("引用筛选 " + label)
                    .doesNotThrowAnyException();
        }
        rejects(
                voucher(
                        Map.of(
                                "204",
                                ruled(reference(null, constant("105", "neq", "2026-01-01"))))),
                "字段「资金流水」的引用筛选：「开户日期」不能用「不等于」");
        rejects(
                voucher(Map.of("204", ruled(reference(null, constant("117", "neq", "A"))))),
                "字段「资金流水」的引用筛选：「凭证标签」不能用「不等于」");
        rejects(
                voucher(Map.of("204", ruled(reference(null, constant("104", "isNull", null))))),
                "字段「资金流水」的引用筛选：条件字段「附件」不支持筛选");
        rejects(
                voucher(Map.of("204", ruled(reference(null, constant("109", "notNull", null))))),
                "字段「资金流水」的引用筛选：条件字段「网址」不支持筛选");
    }

    /** 发布校验：为空 / 不为空带了值被拒；不等于缺值被拒；按记录匹配仍只允许等于。 */
    @Test
    void emptinessRejectsValueAndNotEqualRequiresOne() {
        rejects(
                voucher(Map.of("204", ruled(reference(null, constant("116", "isNull", "A"))))),
                "字段「资金流水」的引用筛选：「为空」不需要比较值，不能填写固定值或当前字段");
        rejects(
                voucher(Map.of("204", ruled(reference(null, current("116", "notNull", "214"))))),
                "字段「资金流水」的引用筛选：「不为空」不需要比较值，不能填写固定值或当前字段");
        rejects(
                voucher(
                        Map.of(
                                "201",
                                ruled(
                                        linkage(
                                                FLOW,
                                                "102",
                                                "FIRST",
                                                constant("116", "neq", null))))),
                "字段「银行名」的数据联动：条件缺少固定值");
        rejects(
                voucher(
                        Map.of(
                                "201",
                                ruled(
                                        linkage(
                                                FLOW,
                                                "102",
                                                "FIRST",
                                                current("$record", "neq", "204"))))),
                "字段「银行名」的数据联动：「按记录匹配」只能使用「等于」");
        rejects(
                voucher(
                        Map.of(
                                "201",
                                ruled(
                                        linkage(
                                                FLOW,
                                                "102",
                                                "FIRST",
                                                constant("$record", "isNull", null))))),
                "字段「银行名」的数据联动：「按记录匹配」只能使用「等于」");
        // 不等于配当前字段：两侧类型照常校验。
        assertThatCode(
                        () ->
                                check(
                                        voucher(
                                                Map.of(
                                                        "201",
                                                        ruled(
                                                                linkage(
                                                                        FLOW,
                                                                        "102",
                                                                        "FIRST",
                                                                        current(
                                                                                "101", "neq",
                                                                                "203")))))))
                .doesNotThrowAnyException();
        rejects(
                voucher(
                        Map.of(
                                "201",
                                ruled(
                                        linkage(
                                                FLOW,
                                                "102",
                                                "FIRST",
                                                current("106", "neq", "203"))))),
                "字段「银行名」的数据联动：条件「所属公司 不等于 当前字段·公司名」两侧类型不一致（引用「公司」对 文本）");
    }

    /**
     * 2026-10-01：选项类条件字段的固定值必须是选项编码。业务方把「凭证状态2 等于 已录入」填成了选项名称，库里存的是编码 ylr，候选恒为空而发布没拦。
     * 引用筛选与数据联动条件同样适用；停用的选项仍算存在。
     */
    @Test
    void optionConstantMustBeOptionCode() {
        var states =
                FieldOptions.copyOf(FieldOptions.defaults())
                        .options(
                                List.of(
                                        new Option("ylr", "已录入", false),
                                        new Option("wlr", "未录入", false),
                                        new Option("zf", "作废", true)))
                        .build();
        var source = flow(Map.of("116", states, "117", states));
        java.util.function.Function<FieldRules.Condition, Definition> filtered =
                c -> voucher(Map.of("204", ruled(reference(null, c))));
        java.util.function.Function<FieldRules.Condition, Definition> linked =
                c -> voucher(Map.of("201", ruled(linkage(FLOW, "102", "FIRST", c))));
        for (String operator : List.of("eq", "neq")) {
            assertThatThrownBy(
                            () -> check(filtered.apply(constant("116", operator, "已录入")), source))
                    .as(operator)
                    .hasMessage("字段「资金流水」的引用筛选：条件字段「凭证状态」的固定值「已录入」不是该字段的有效选项，请从选项中选择");
            assertThatThrownBy(() -> check(linked.apply(constant("116", operator, "已录入")), source))
                    .as(operator)
                    .hasMessage("字段「银行名」的数据联动：条件字段「凭证状态」的固定值「已录入」不是该字段的有效选项，请从选项中选择");
            assertThatCode(() -> check(filtered.apply(constant("116", operator, "ylr")), source))
                    .as(operator)
                    .doesNotThrowAnyException();
            assertThatCode(() -> check(linked.apply(constant("116", operator, "ylr")), source))
                    .as(operator)
                    .doesNotThrowAnyException();
        }
        assertThatCode(() -> check(filtered.apply(constant("116", "eq", "zf")), source))
                .as("停用的选项仍是该字段的选项")
                .doesNotThrowAnyException();
        // 多选：逐项核对，点名不是选项的那一项。
        assertThatThrownBy(
                        () ->
                                check(
                                        filtered.apply(
                                                constant(
                                                        "117",
                                                        "containsAny",
                                                        List.of("ylr", "未录入"))),
                                        source))
                .hasMessage("字段「资金流水」的引用筛选：条件字段「凭证标签」的固定值「未录入」不是该字段的有效选项，请从选项中选择");
        assertThatCode(
                        () ->
                                check(
                                        filtered.apply(
                                                constant(
                                                        "117",
                                                        "containsAny",
                                                        List.of("ylr", "wlr"))),
                                        source))
                .doesNotThrowAnyException();
        // 文本字段的固定值不受此限。
        assertThatCode(() -> check(filtered.apply(constant("101", "eq", "已录入")), source))
                .doesNotThrowAnyException();
    }

    /** 条件字段的选项来自「挑取值」时，按它指向的来源字段的选项核对（业务方场景：资金流水.凭证状态2 挑自 会计凭证录入.凭证状态）。 */
    @Test
    void optionConstantFollowsPickSource() {
        var states =
                FieldOptions.copyOf(FieldOptions.defaults())
                        .options(List.of(new Option("ylr", "已录入", false)))
                        .build();
        var source = flow(Map.of("116", pick(VOUCHER, "214")));
        java.util.function.Function<String, Definition> filtered =
                value ->
                        voucher(
                                Map.of(
                                        "214",
                                        states,
                                        "204",
                                        ruled(reference(null, constant("116", "eq", value)))));
        assertThatThrownBy(() -> check(filtered.apply("已录入"), source))
                .hasMessage("字段「资金流水」的引用筛选：条件字段「凭证状态」的固定值「已录入」不是该字段的有效选项，请从选项中选择");
        assertThatCode(() -> check(filtered.apply("ylr"), source)).doesNotThrowAnyException();
        // 挑取值指向的来源解析不出来（来源对象不在给定定义里）：无从判断，不拦。
        assertThatCode(() -> check(filtered.apply("已录入"), flow(Map.of("116", pick("999", "1")))))
                .doesNotThrowAnyException();
    }

    /** 公共字典的选项由调用方给出的选择目录解析：名称被拒、字典值通过；目录给不出选项时不拦。 */
    @Test
    void optionConstantFollowsDictionary() {
        var published = Map.of(FLOW, flow(Map.of()), COMPANY, company(), ACCOUNT, account());
        java.util.function.BiFunction<FieldDefinition, FieldOptions, List<SelectionFields.Option>>
                catalog =
                        (field, options) ->
                                List.of(
                                        new SelectionFields.Option(
                                                "ICBC", "工商银行", "ICBC", null, "工商银行", false,
                                                false));
        java.util.function.Function<String, Definition> filtered =
                value ->
                        voucher(
                                Map.of(
                                        "204",
                                        ruled(reference(null, constant("108", "eq", value)))));
        assertThatThrownBy(
                        () ->
                                FieldRuleValidator.validate(
                                        filtered.apply("工商银行"),
                                        published::get,
                                        id -> "「?」未发布或已停用",
                                        catalog))
                .hasMessage("字段「资金流水」的引用筛选：条件字段「类型」的固定值「工商银行」不是该字段的有效选项，请从选项中选择");
        assertThatCode(
                        () ->
                                FieldRuleValidator.validate(
                                        filtered.apply("ICBC"),
                                        published::get,
                                        id -> "「?」未发布或已停用",
                                        catalog))
                .doesNotThrowAnyException();
        assertThatCode(() -> check(filtered.apply("工商银行")))
                .as("没有选择目录时公共字典解析不出选项，不拦")
                .doesNotThrowAnyException();
    }

    @Test
    void currentFieldTypeMustMatchConditionField() {
        rejects(
                voucher(
                        Map.of(
                                "201",
                                ruled(linkage(FLOW, "102", "FIRST", current("106", "eq", "203"))))),
                "字段「银行名」的数据联动：条件「所属公司 等于 当前字段·公司名」两侧类型不一致（引用「公司」对 文本）");
    }

    @Test
    void recordKeyNeedsRelationToSource() {
        rejects(
                voucher(
                        Map.of(
                                "201",
                                ruled(
                                        linkage(
                                                FLOW,
                                                "102",
                                                "FIRST",
                                                current("$record", "eq", "202"))))),
                "字段「银行名」的数据联动：「按记录匹配」的当前字段必须是指向「资金流水」的关联字段");
    }

    @Test
    void sumOnTextRejected() {
        rejects(
                voucher(Map.of("201", ruled(linkage(FLOW, "102", "SUM")))),
                "字段「银行名」的数据联动：「求和」只适用于数值或金额来源");
        assertThatCode(() -> check(voucher(Map.of("205", ruled(linkage(FLOW, "103", "SUM"))))))
                .doesNotThrowAnyException();
    }

    @Test
    void unknownMultiRowRejected() {
        rejects(
                voucher(Map.of("201", ruled(linkage(FLOW, "102", "DISTINCT")))),
                "字段「银行名」的数据联动：多行匹配档位「DISTINCT」无效");
    }

    @Test
    void valueTypeMustTransfer() {
        rejects(
                voucher(Map.of("208", local("A", "B").withRules(linkage(FLOW, "102", "FIRST")))),
                "字段「银行类型」的数据联动：来源「开户行」（文本）不能带入到「银行类型」（单选）");
        rejects(
                voucher(Map.of("202", ruled(linkage(FLOW, "102", "FIRST")))),
                "字段「公司」的数据联动：来源「开户行」（文本）不能带入到「公司」（关联）");
        assertThatCode(() -> check(voucher(Map.of("202", ruled(linkage(FLOW, "106", "FIRST"))))))
                .doesNotThrowAnyException();
    }

    /** 2026-10-01 裁定：单行文本目标可带入文本、自动编号、链接与结果为文本的公式；缺省档「拼接成一行」同样可用。 */
    @Test
    void textTargetTakesAutoNumberUrlAndTextFormula() {
        for (String source : List.of("102", "111", "109", "113"))
            for (String mode : Arrays.asList("FIRST", "CONCAT", "ERROR", null))
                assertThatCode(
                                () ->
                                        check(
                                                voucher(
                                                        Map.of(
                                                                "201",
                                                                ruled(
                                                                        linkage(
                                                                                FLOW, source,
                                                                                mode))))))
                        .as("文本 ← " + source + " · " + mode)
                        .doesNotThrowAnyException();
    }

    /** 多行文本目标在单行文本可收的来源之上，另收多行文本。 */
    @Test
    void textareaTargetTakesTextSourcesAndTextarea() {
        for (String source : List.of("102", "112", "111", "109", "113"))
            assertThatCode(
                            () ->
                                    check(
                                            voucher(
                                                    Map.of(
                                                            "212",
                                                            ruled(
                                                                    linkage(
                                                                            FLOW, source,
                                                                            "FIRST"))))))
                    .as("多行文本 ← " + source)
                    .doesNotThrowAnyException();
    }

    /** 多行内容不放进单行文本：仍拒绝，文案点名两侧字段与类型。 */
    @Test
    void singleLineTextRejectsTextarea() {
        rejects(
                voucher(Map.of("201", ruled(linkage(FLOW, "112", "FIRST")))),
                "字段「银行名」的数据联动：来源「备注」（多行文本）不能带入到「银行名」（文本）");
    }

    /** 放开的只有文本目标的这几种来源：其它来源、其它目标的相容规则不变。 */
    @Test
    void otherValueTypesKeepTheirRules() {
        rejects(
                voucher(Map.of("201", ruled(linkage(FLOW, "103", "FIRST")))),
                "字段「银行名」的数据联动：来源「金额」（金额）不能带入到「银行名」（文本）");
        rejects(
                voucher(Map.of("201", ruled(linkage(FLOW, "114", "FIRST")))),
                "字段「银行名」的数据联动：来源「小计」（公式）不能带入到「银行名」（文本）");
        rejects(
                voucher(Map.of("201", ruled(linkage(FLOW, "107", "FIRST")))),
                "字段「银行名」的数据联动：来源「状态」（单选）不能带入到「银行名」（文本）");
        rejects(
                voucher(Map.of("201", ruled(linkage(FLOW, "106", "FIRST")))),
                "字段「银行名」的数据联动：来源「所属公司」（关联）不能带入到「银行名」（文本）");
        rejects(
                voucher(Map.of("201", ruled(linkage(FLOW, "115", "FIRST")))),
                "字段「银行名」的数据联动：来源「详情」（富文本）不能带入到「银行名」（文本）");
        rejects(
                voucher(Map.of("212", ruled(linkage(FLOW, "115", "FIRST")))),
                "字段「说明」的数据联动：来源「详情」（富文本）不能带入到「说明」（多行文本）");
        // 链接目标只收链接；数值目标不收自动编号。
        rejects(
                voucher(Map.of("213", ruled(linkage(FLOW, "102", "FIRST")))),
                "字段「链接」的数据联动：来源「开户行」（文本）不能带入到「链接」（链接）");
        rejects(
                voucher(Map.of("213", ruled(linkage(FLOW, "111", "FIRST")))),
                "字段「链接」的数据联动：来源「流水编号」（自动编号）不能带入到「链接」（链接）");
        rejects(
                voucher(Map.of("207", ruled(linkage(FLOW, "111", "FIRST")))),
                "字段「数量」的数据联动：来源「流水编号」（自动编号）不能带入到「数量」（整数）");
        assertThatCode(() -> check(voucher(Map.of("213", ruled(linkage(FLOW, "109", "FIRST"))))))
                .doesNotThrowAnyException();
        assertThatCode(() -> check(voucher(Map.of("211", ruled(linkage(FLOW, "114", "SUM"))))))
                .doesNotThrowAnyException();
    }

    /**
     * 2026-10-01 裁定：选项类目标再认可反方向——来源字段的挑取值正好指向当前字段（二者共用当前字段的选项集）。
     *
     * <p>指向别的字段、别的对象，或单选多选不一致时仍拒绝。
     */
    @Test
    void optionTargetTakesSourcePickedFromItself() {
        var d = voucher(Map.of("208", local("A", "B").withRules(linkage(FLOW, "116", "FIRST"))));
        assertThatCode(() -> check(d, flow(Map.of("116", pick(VOUCHER, "208")))))
                .doesNotThrowAnyException();
        String rejected = "字段「银行类型」的数据联动：来源「凭证状态」（单选）不能带入到「银行类型」（单选）";
        assertThatThrownBy(() -> check(d, flow(Map.of("116", pick(VOUCHER, "214")))))
                .hasMessage(rejected);
        assertThatThrownBy(() -> check(d, flow(Map.of("116", pick("999", "208")))))
                .hasMessage(rejected);
        assertThatThrownBy(() -> check(d, flow(Map.of("116", dictionary("bank_type")))))
                .hasMessage(rejected);
        var multi =
                voucher(Map.of("208", local("A", "B").withRules(linkage(FLOW, "117", "FIRST"))));
        assertThatThrownBy(() -> check(multi, flow(Map.of("117", pick(VOUCHER, "208")))))
                .hasMessage("字段「银行类型」的数据联动：来源「凭证标签」（多选）不能带入到「银行类型」（单选）");
    }

    /**
     * 两个局部选项字段只有编码→标签逐项一致才算同一套选项（与表单带入沿用的 SelectionCompatibility 同口径）。
     *
     * <p>局部选项的「选择身份」串只说明两边都是局部选项，互不相干的两套选项不能因此互相带入；挑取值的两个方向不受影响。
     */
    @Test
    void localOptionFieldsMustShareCodesAndLabels() {
        var d = voucher(Map.of("208", local("A", "B").withRules(linkage(FLOW, "107", "FIRST"))));
        // 编码与标签逐项一致：放行；顺序、停用状态不影响。
        assertThatCode(() -> check(d)).doesNotThrowAnyException();
        assertThatCode(() -> check(d, flow(Map.of("107", local("B", "A")))))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                check(
                                        d,
                                        flow(
                                                Map.of(
                                                        "107",
                                                        choices(
                                                                new Option("A", "选项A", true),
                                                                new Option("B", "选项B", false))))))
                .doesNotThrowAnyException();
        String rejected = "字段「银行类型」的数据联动：来源「状态」（单选）不能带入到「银行类型」（单选）";
        // 编码不同、少一项、多一项：不是同一套选项。
        assertThatThrownBy(() -> check(d, flow(Map.of("107", local("A", "C")))))
                .hasMessage(rejected);
        assertThatThrownBy(() -> check(d, flow(Map.of("107", local("A"))))).hasMessage(rejected);
        assertThatThrownBy(() -> check(d, flow(Map.of("107", local("A", "B", "C")))))
                .hasMessage(rejected);
        // 编码相同但标签不同：同一个编码在两边含义不同，同样不相容。
        assertThatThrownBy(
                        () ->
                                check(
                                        d,
                                        flow(
                                                Map.of(
                                                        "107",
                                                        choices(
                                                                new Option("A", "已作废", false),
                                                                new Option("B", "选项B", false))))))
                .hasMessage(rejected);
        // 选项集不同的两个字段，靠挑取值把其中一方指向另一方后仍可带入（两个方向都保留）。
        var picking =
                voucher(Map.of("208", pick(FLOW, "107").withRules(linkage(FLOW, "107", "FIRST"))));
        assertThatCode(() -> check(picking, flow(Map.of("107", local("X", "Y")))))
                .doesNotThrowAnyException();
        var reverse =
                voucher(Map.of("208", local("A", "B").withRules(linkage(FLOW, "116", "FIRST"))));
        assertThatCode(() -> check(reverse, flow(Map.of("116", pick(VOUCHER, "208")))))
                .doesNotThrowAnyException();
    }

    private static FieldOptions choices(Option... items) {
        return FieldOptions.copyOf(FieldOptions.defaults()).options(List.of(items)).build();
    }

    @Test
    void labelFieldMustBeLinkable() {
        rejects(voucher(Map.of("210", ruled(reference("403")))), "字段「口座」的显示名字段：显示名字段「附件」不可用作显示名");
        // 自动编号可以作为关联字段的显示名字段（核对项，2026-10-01）。
        assertThatCode(() -> check(voucher(Map.of("210", ruled(reference("404"))))))
                .doesNotThrowAnyException();
    }

    @Test
    void referenceOnlyOnRelationField() {
        rejects(voucher(Map.of("209", ruled(reference("401")))), "字段「备注」不是关联字段，不能设置显示名字段或引用筛选");
    }

    @Test
    void pickValueSourceNeedsOptions() {
        rejects(voucher(Map.of("208", pick(FLOW, "108"))), "字段「银行类型」的「挑取值」来源字段「类型」没有配置选项");
    }

    @Test
    void pickValueAcceptsDictionaryWithItems() {
        var d = voucher(Map.of("208", pick(FLOW, "108")));
        var published = Map.of(FLOW, flow(Map.of()), COMPANY, company(), ACCOUNT, account());
        // 候选 = 来源字段实际生效的选项集：公共字典有项即算有选项，由选择目录解析，不读业务行。
        assertThatCode(
                        () ->
                                FieldRuleValidator.validate(
                                        d,
                                        published::get,
                                        id -> "「?」未发布或已停用",
                                        (field, options) ->
                                                List.of(
                                                        new SelectionFields.Option(
                                                                "ICBC", "工商银行", "ICBC", null,
                                                                "工商银行", false, false))))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                FieldRuleValidator.validate(
                                        d,
                                        published::get,
                                        id -> "「?」未发布或已停用",
                                        (field, options) ->
                                                List.of(
                                                        new SelectionFields.Option(
                                                                "ICBC", "工商银行", "ICBC", null,
                                                                "工商银行", true, false))))
                .hasMessage("字段「银行类型」的「挑取值」来源字段「类型」没有配置选项");
    }

    @Test
    void cycleIsRejected() {
        rejects(
                voucher(
                        Map.of(
                                "201",
                                ruled(linkage(FLOW, "102", "FIRST", current("101", "eq", "209"))),
                                "209",
                                ruled(linkage(FLOW, "101", "FIRST", current("102", "eq", "201"))))),
                "数据联动 / 公式默认值存在循环依赖：主表 · 银行名 → 主表 · 备注 → 主表 · 银行名");
    }

    @Test
    void roundingOnlyForMoney() {
        rejects(
                voucher(Map.of("206", ruled(formula("c_sl * 2", "FLOOR")))),
                "字段「单价」不是金额字段，不能设置取整方式（非金额数值保持小数）");
    }

    @Test
    void roundingNeedsRule() {
        rejects(
                voucher(Map.of("205", ruled(formula(null, "HALF_UP")))),
                "字段「合计」的取整方式只用于数据联动或公式默认值");
    }

    @Test
    void roundingCode() {
        rejects(
                voucher(Map.of("205", ruled(formula("c_dj * c_sl", "CEIL")))),
                "字段「合计」的取整方式「CEIL」无效，可选：四舍五入 / 向下取整 / 去掉小数");
        for (String code : Arrays.asList(null, "HALF_UP", "FLOOR", "DOWN"))
            assertThatCode(() -> check(voucher(Map.of("205", ruled(formula("c_dj * c_sl", code))))))
                    .doesNotThrowAnyException();
    }

    @Test
    void moneyFormulaRejectsRoundWithCurrentRounding() {
        rejects(
                voucher(Map.of("205", ruled(formula("round(c_dj * c_sl)", "DOWN")))),
                "字段「合计」是金额字段，公式里不能写 round()：取整统一由「取整方式」决定（当前：去掉小数）");
    }

    @Test
    void masterCannotReferenceDetail() {
        rejects(
                voucher(
                        Map.of(
                                "201",
                                ruled(linkage(FLOW, "102", "FIRST", current("101", "eq", "252")))),
                        List.of(entries(Map.of()))),
                "主表字段「银行名」的规则不能引用明细字段「科目分类」（一对多，无法确定取哪一行）");
    }

    @Test
    void noCrossDetail() {
        var cross =
                expenses(
                        Map.of(
                                "261",
                                ruled(linkage(FLOW, "102", "FIRST", current("101", "eq", "252")))));
        rejects(
                voucher(Map.of(), List.of(entries(Map.of()), cross)),
                "明细「费用明细」字段「说明」的规则不能引用明细「凭证明细」的字段「科目分类」");
        var sameRowAndMaster =
                entries(
                        Map.of(
                                "253",
                                ruled(
                                        linkage(
                                                FLOW,
                                                "102",
                                                "FIRST",
                                                current("101", "eq", "252"),
                                                current("102", "eq", "209")))));
        assertThatCode(() -> check(voucher(Map.of(), List.of(sameRowAndMaster))))
                .doesNotThrowAnyException();
    }

    @Test
    void cycleAcrossTables() {
        var detail =
                entries(
                        Map.of(
                                "252",
                                ruled(linkage(FLOW, "101", "FIRST", current("101", "eq", "253"))),
                                "253",
                                ruled(
                                        linkage(
                                                FLOW,
                                                "102",
                                                "FIRST",
                                                current("101", "eq", "252"),
                                                current("102", "eq", "209")))));
        rejects(
                voucher(Map.of(), List.of(detail)),
                "数据联动 / 公式默认值存在循环依赖：凭证明细 · 科目分类 → 凭证明细 · 摘要 → 凭证明细 · 科目分类");
    }

    @Test
    void ambiguousFormulaCode() {
        rejects(
                voucher(
                        Map.of(),
                        List.of(entries(Map.of("254", ruled(formula("c_je * 0.1", null)))))),
                "明细「凭证明细」字段「税额」的公式引用「c_je」在主表和明细中重名，无法确定来源");
        assertThatCode(
                        () ->
                                check(
                                        voucher(
                                                Map.of(),
                                                List.of(
                                                        entries(
                                                                Map.of(
                                                                        "254",
                                                                        ruled(
                                                                                formula(
                                                                                        "c_dj *"
                                                                                            + " 0.1",
                                                                                        null))))))))
                .doesNotThrowAnyException();
    }

    @Test
    void pinnedSourceMustBeInApplication() {
        var d = voucher(Map.of("201", ruled(linkage(FLOW, "102", "FIRST"))));
        assertThatThrownBy(
                        () ->
                                FieldRuleValidator.validatePinned(
                                        Map.of(VOUCHER, d, COMPANY, company(), ACCOUNT, account()),
                                        id -> "「资金流水」未加入本应用"))
                .hasMessage("字段「银行名」的数据联动：来源对象「资金流水」未加入本应用");
        assertThatCode(
                        () ->
                                FieldRuleValidator.validatePinned(
                                        Map.of(
                                                VOUCHER,
                                                d,
                                                FLOW,
                                                flow(Map.of()),
                                                COMPANY,
                                                company(),
                                                ACCOUNT,
                                                account()),
                                        id -> "「?」未加入本应用"))
                .doesNotThrowAnyException();
    }

    @Test
    void pinnedPickSourceFollowsApplicationVersions() {
        // 挑取值运行时只读应用固定的对象版本，应用发布按同一口径校验。
        var d = voucher(Map.of("208", pick(FLOW, "107")));
        java.util.function.Function<Definition, Map<String, Definition>> pinned =
                source -> Map.of(VOUCHER, d, FLOW, source, COMPANY, company(), ACCOUNT, account());
        assertThatThrownBy(
                        () ->
                                FieldRuleValidator.validatePinned(
                                        Map.of(VOUCHER, d, COMPANY, company(), ACCOUNT, account()),
                                        id -> "「资金流水」未加入本应用"))
                .hasMessage("字段「银行类型」的挑取值：来源对象「资金流水」未加入本应用");
        assertThatThrownBy(
                        () ->
                                FieldRuleValidator.validatePinned(
                                        pinned.apply(flow(Map.of("107", inactive()))),
                                        id -> "「?」未加入本应用"))
                .hasMessage("字段「银行类型」的挑取值：来源字段「状态」已不存在或已停用");
        assertThatThrownBy(
                        () ->
                                FieldRuleValidator.validatePinned(
                                        pinned.apply(flow(Map.of("107", plain()))),
                                        id -> "「?」未加入本应用"))
                .hasMessage("字段「银行类型」的「挑取值」来源字段「状态」没有配置选项");
        assertThatCode(
                        () ->
                                FieldRuleValidator.validatePinned(
                                        pinned.apply(flow(Map.of())), id -> "「?」未加入本应用"))
                .doesNotThrowAnyException();
    }

    @Test
    void dependenciesListOtherObjectFields() {
        var d =
                voucher(
                        Map.of(
                                "201",
                                ruled(
                                        linkage(
                                                FLOW,
                                                "102",
                                                "FIRST",
                                                current("$record", "eq", "204"),
                                                constant("105", "lt", "2026-01-01"))),
                                "210",
                                ruled(reference("401", current("402", "eq", "202")))));
        assertThat(FieldRuleValidator.dependencies(d))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(FLOW, Set.of("102", "105"), ACCOUNT, Set.of("401", "402")));
    }

    @Test
    void storedDependsOnIsStripped() {
        var rules = new FieldRules(null, null, "c_dj * 2", null, List.of("206"), null);
        assertThat(FieldRuleValidator.normalize(rules).dependsOn()).isNull();
        assertThat(FieldRuleValidator.normalize(rules).defaultFormula()).isEqualTo("c_dj * 2");
    }

    /** 投影才有的 readOnly（2026-09-29）同样不进存储快照；联动自身的只读开关原样保留。 */
    @Test
    void storedReadOnlyIsStripped() {
        var linkage = new FieldRules.Linkage("1", List.of(), "2", "FIRST", null, null, null);
        var rules = new FieldRules(null, linkage, null, null, null, true);
        var normalized = FieldRuleValidator.normalize(rules);
        assertThat(normalized.readOnly()).isNull();
        assertThat(normalized.linkage()).isEqualTo(linkage);
        var stored = new FieldRules(null, linkage, null, null, null, null);
        assertThat(FieldRuleValidator.normalize(stored)).isSameAs(stored);
    }
}
