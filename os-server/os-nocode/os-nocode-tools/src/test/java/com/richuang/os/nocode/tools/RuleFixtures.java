package com.richuang.os.nocode.tools;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.*;

import java.util.*;

/** 对象规则单元测试的内存定义夹具：只构造固定版本形状，不连接数据库。 */
final class RuleFixtures {
    private RuleFixtures() {}

    static FieldDefinition field(String id, String code, String name, String type) {
        return new FieldDefinition(id, id, code, name, type, null, null, null, false, false, 0);
    }

    static FieldOptions plain() {
        return FieldOptions.defaults();
    }

    static FieldOptions inactive() {
        return FieldOptions.copyOf(FieldOptions.defaults())
                .state(MemberStateEnum.INACTIVE.getCode())
                .build();
    }

    static FieldOptions local(String... codes) {
        return FieldOptions.copyOf(FieldOptions.defaults())
                .options(
                        Arrays.stream(codes)
                                .map(code -> new DataCenter.Option(code, "选项" + code, false))
                                .toList())
                .build();
    }

    static FieldOptions dictionary(String type) {
        return FieldOptions.defaults()
                .withSelection(
                        new SelectionFields.Source(
                                SelectionSourceEnum.SYSTEM_DICTIONARY.getCode(),
                                null,
                                type,
                                List.of(),
                                false,
                                List.of(),
                                SelectionDefaultEnum.NONE.getCode()));
    }

    static FieldOptions pick(String objectId, String fieldId) {
        return FieldOptions.defaults()
                .withSelection(
                        new SelectionFields.Source(
                                SelectionSourceEnum.OBJECT_FIELD_OPTIONS.getCode(),
                                null,
                                null,
                                List.of(),
                                false,
                                List.of(),
                                SelectionDefaultEnum.NONE.getCode(),
                                null,
                                objectId,
                                fieldId));
    }

    static FieldOptions ruled(FieldRules rules) {
        return FieldOptions.defaults().withRules(rules);
    }

    static FieldRules linkage(
            String sourceObjectId,
            String valueFieldId,
            String multiRow,
            FieldRules.Condition... conditions) {
        return new FieldRules(
                null,
                new FieldRules.Linkage(
                        sourceObjectId,
                        List.of(conditions),
                        valueFieldId,
                        multiRow,
                        false,
                        null,
                        null),
                null,
                null,
                null,
                null);
    }

    static FieldRules reference(String labelFieldId, FieldRules.Condition... filter) {
        return new FieldRules(
                new FieldRules.Reference(labelFieldId, List.of(filter)),
                null,
                null,
                null,
                null,
                null);
    }

    static FieldRules formula(String formula, String rounding) {
        return new FieldRules(null, null, formula, rounding, null, null);
    }

    static FieldRules.Condition constant(String fieldId, String operator, Object value) {
        return new FieldRules.Condition(
                fieldId, operator, RuleValueSourceEnum.CONSTANT.getCode(), value, null);
    }

    static FieldRules.Condition current(String fieldId, String operator, String formFieldId) {
        return new FieldRules.Condition(
                fieldId, operator, RuleValueSourceEnum.FORM_FIELD.getCode(), null, formFieldId);
    }

    static Relation relation(String id, String fieldId, String targetObjectId, String kind) {
        return new Relation(
                id, id, "关系" + id, kind, targetObjectId, fieldId, null, false, "RESTRICT");
    }

    static Relation ref(String id, String fieldId, String targetObjectId) {
        return relation(id, fieldId, targetObjectId, RelationTypeEnum.REFERENCE.getCode());
    }

    static Detail detail(
            String id,
            String name,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> options) {
        return new Detail(
                id,
                "d" + id,
                name,
                "biz_d" + id,
                MemberStateEnum.ACTIVE.getCode(),
                fields,
                options,
                List.of());
    }

    static Definition object(
            String id,
            String name,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> options,
            List<Relation> relations,
            List<Detail> details) {
        return new Definition(
                id,
                "o" + id,
                name,
                null,
                "public",
                "biz_o" + id,
                ObjectSourceEnum.GENERATED.getCode(),
                false,
                fields.getFirst().id(),
                Settings.defaults(),
                fields,
                options,
                relations,
                List.of(),
                details);
    }

    /** 可变的选项表，便于每个用例只替换关心的字段。 */
    @SafeVarargs
    static Map<String, FieldOptions> options(Map.Entry<String, FieldOptions>... entries) {
        var result = new HashMap<String, FieldOptions>();
        for (var entry : entries) result.put(entry.getKey(), entry.getValue());
        return result;
    }
}
