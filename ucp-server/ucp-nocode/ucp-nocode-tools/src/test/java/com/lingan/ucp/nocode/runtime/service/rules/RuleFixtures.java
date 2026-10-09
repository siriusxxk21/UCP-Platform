package com.lingan.ucp.nocode.runtime.service.rules;

import com.lingan.ucp.nocode.api.*;

import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 纯内存对象定义与规则构造；字段 ID 与编码相同，便于断言。 */
final class RuleFixtures {
    static final String DETAIL = "lines";

    private RuleFixtures() {}

    static FieldDefinition field(String id, String type) {
        return field(id, id, type);
    }

    static FieldDefinition field(String id, String code, String type) {
        boolean decimal = "DECIMAL".equals(type);
        return new FieldDefinition(
                id,
                id,
                code,
                id,
                type,
                null,
                decimal ? 20 : null,
                decimal ? 4 : null,
                false,
                false,
                0);
    }

    static FieldRules.Condition current(String formField) {
        return new FieldRules.Condition("src_" + formField, "eq", "FORM_FIELD", null, formField);
    }

    static FieldRules linkage(Boolean readOnly, String... formFields) {
        return new FieldRules(
                null,
                new FieldRules.Linkage(
                        "900",
                        Arrays.stream(formFields).map(RuleFixtures::current).toList(),
                        "901",
                        null,
                        readOnly,
                        null,
                        null),
                null,
                null,
                null,
                null);
    }

    static FieldRules formula(String expression, String rounding) {
        return new FieldRules(null, null, expression, rounding, null, null);
    }

    static FieldRules filter(String... formFields) {
        return new FieldRules(
                new FieldRules.Reference(
                        null, Arrays.stream(formFields).map(RuleFixtures::current).toList()),
                null,
                null,
                null,
                null,
                null);
    }

    static Map<String, DataCenter.FieldOptions> options(Map<String, FieldRules> rules) {
        Map<String, DataCenter.FieldOptions> result = new LinkedHashMap<>();
        rules.forEach((id, r) -> result.put(id, DataCenter.FieldOptions.defaults().withRules(r)));
        return result;
    }

    /**
     * 主表：name、company、bank、branch、memo、price、qty、total、ratio、account（引用）。 明细
     * lines：category、rate、note、unit、line_qty、amount、d_price（编码 price，与主表重名）、subject（引用）。
     */
    static DataCenter.Definition definition(
            Map<String, FieldRules> mainRules, Map<String, FieldRules> detailRules) {
        var fields =
                List.of(
                        field("name", "TEXT"),
                        field("company", "TEXT"),
                        field("bank", "TEXT"),
                        field("branch", "TEXT"),
                        field("memo", "TEXT"),
                        field("price", "DECIMAL"),
                        field("qty", "INTEGER"),
                        field("total", "MONEY"),
                        field("ratio", "DECIMAL"),
                        field("account", "REFERENCE"));
        var detail =
                new DataCenter.Detail(
                        DETAIL,
                        DETAIL,
                        "分录",
                        "biz_lines",
                        "ACTIVE",
                        List.of(
                                field("category", "TEXT"),
                                field("rate", "TEXT"),
                                field("note", "TEXT"),
                                field("unit", "DECIMAL"),
                                field("line_qty", "INTEGER"),
                                field("amount", "MONEY"),
                                field("d_price", "price", "DECIMAL"),
                                field("subject", "REFERENCE")),
                        options(detailRules),
                        List.of());
        return new DataCenter.Definition(
                "100",
                "voucher",
                "凭证",
                null,
                "public",
                "biz_voucher",
                "GENERATED",
                false,
                "name",
                DataCenter.Settings.defaults(),
                fields,
                options(mainRules),
                List.of(
                        new DataCenter.Relation(
                                "r1",
                                "account",
                                "口座",
                                "REFERENCE",
                                "200",
                                "account",
                                null,
                                false,
                                "RESTRICT"),
                        new DataCenter.Relation(
                                "r2",
                                "subject",
                                "科目",
                                "REFERENCE",
                                "200",
                                "subject",
                                null,
                                false,
                                "RESTRICT",
                                DETAIL)),
                List.of(),
                List.of(detail));
    }

    static RuleContext context(DataCenter.Definition d) {
        return new RuleContext("1", d, 10001L, null, null);
    }

    static FieldRuleEnforcer enforcer(FieldRuleEvaluator evaluator) {
        var enforcer = new FieldRuleEnforcer();
        ReflectionTestUtils.setField(enforcer, "evaluator", evaluator);
        return enforcer;
    }

    static Map<String, Object> values(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
