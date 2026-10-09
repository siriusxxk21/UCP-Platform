package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.form.FormFillBindings;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 关联带入的发布校验规则；真实保存与运行链路由 ApplicationIntegrationTest、RuntimeIntegrationTest 覆盖。 */
class FormFillBindingsTest {
    private static FieldDefinition field(String id, String name, String type) {
        return new FieldDefinition(id, id, id, name, type, null, null, null, false, false, 0);
    }

    private static DataCenter.FieldOptions inactiveOptions() {
        return new DataCenter.FieldOptions(
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                MemberStateEnum.INACTIVE.getCode(),
                List.of(),
                null,
                null,
                null,
                null,
                false,
                false,
                null);
    }

    private static DataCenter.Relation relation(
            String id, String fieldId, String targetObjectId, String kind) {
        return new DataCenter.Relation(
                id, id, id, kind, targetObjectId, fieldId, null, false, "RESTRICT");
    }

    private static DataCenter.Definition definition(
            String objectId,
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options,
            List<DataCenter.Relation> relations) {
        return new DataCenter.Definition(
                objectId,
                objectId,
                objectId,
                null,
                "public",
                "t_" + objectId,
                "GENERATED",
                false,
                fields.isEmpty() ? null : fields.getFirst().id(),
                DataCenter.Settings.defaults(),
                fields,
                options,
                relations,
                List.of(),
                List.of());
    }

    private static ApplicationUi.Node node(String fieldId, FormFills.Binding fill) {
        return node(fieldId, fill, false);
    }

    private static ApplicationUi.Node node(
            String fieldId, FormFills.Binding fill, boolean readOnly) {
        return new ApplicationUi.Node(
                "node_" + fieldId,
                "FIELD",
                fieldId,
                null,
                null,
                null,
                List.of(),
                null,
                new ApplicationUi.FieldPresentation(
                        fieldId, null, null, readOnly, null, null, fill));
    }

    /** 带入来源必须是表单内的关联字段，来源节点同时放进表单。 */
    private static ApplicationUi.Node plain(String fieldId) {
        return node(fieldId, null);
    }

    private static ApplicationUi.Form form(ApplicationUi.Node... nodes) {
        return new ApplicationUi.Form("order", List.of(nodes), List.of());
    }

    private static DataCenter.Definition order(Map<String, DataCenter.FieldOptions> options) {
        return definition(
                "order",
                List.of(
                        field("bank", "银行流水", FieldTypeEnum.REFERENCE.getCode()),
                        field("payAccount", "银行账户", FieldTypeEnum.REFERENCE.getCode()),
                        field("amount", "金额", FieldTypeEnum.DECIMAL.getCode()),
                        field("memo", "备注", FieldTypeEnum.TEXT.getCode()),
                        field("attachment", "附件", FieldTypeEnum.ATTACHMENT.getCode()),
                        field("total", "合计", FieldTypeEnum.FORMULA.getCode()),
                        field("serial", "编号", FieldTypeEnum.AUTO_NUMBER.getCode())),
                options,
                List.of(
                        relation(
                                "r_bank",
                                "bank",
                                "bankStatement",
                                RelationTypeEnum.REFERENCE.getCode()),
                        relation(
                                "r_pay",
                                "payAccount",
                                "bankAccount",
                                RelationTypeEnum.REFERENCE.getCode()),
                        relation(
                                "r_flow",
                                "bank",
                                "bankStatement",
                                RelationTypeEnum.MANY_TO_MANY.getCode())));
    }

    private static DataCenter.Definition statement(Map<String, DataCenter.FieldOptions> options) {
        return definition(
                "bankStatement",
                List.of(
                        field("account", "账户", FieldTypeEnum.REFERENCE.getCode()),
                        field("voucher", "凭证", FieldTypeEnum.REFERENCE.getCode()),
                        field("paid", "发生额", FieldTypeEnum.DECIMAL.getCode()),
                        field("text", "摘要", FieldTypeEnum.TEXT.getCode()),
                        field("file", "回单", FieldTypeEnum.ATTACHMENT.getCode()),
                        field("stopped", "停用摘要", FieldTypeEnum.TEXT.getCode())),
                options,
                List.of(
                        relation(
                                "r_account",
                                "account",
                                "bankAccount",
                                RelationTypeEnum.REFERENCE.getCode()),
                        relation(
                                "r_voucher",
                                "voucher",
                                "voucher",
                                RelationTypeEnum.REFERENCE.getCode())));
    }

    private static Map<String, DataCenter.Definition> definitions(DataCenter.Definition statement) {
        return Map.of(
                "bankStatement",
                statement,
                "bankAccount",
                definition(
                        "bankAccount",
                        List.of(
                                field("name", "账户名称", FieldTypeEnum.TEXT.getCode()),
                                field("statement", "关联流水", FieldTypeEnum.REFERENCE.getCode())),
                        Map.of(),
                        List.of(
                                relation(
                                        "r_statement",
                                        "statement",
                                        "bankStatement",
                                        RelationTypeEnum.REFERENCE.getCode()))));
    }

    private static void validate(
            DataCenter.Definition order, DataCenter.Definition statement, ApplicationUi.Form form) {
        FormFillBindings.validate(form, order, definitions(statement));
    }

    @Test
    void scalarTargetAcceptsCompatibleSourceField() {
        assertThatCode(
                        () ->
                                validate(
                                        order(Map.of()),
                                        statement(Map.of()),
                                        form(
                                                plain("bank"),
                                                node(
                                                        "memo",
                                                        new FormFills.Binding(
                                                                "bank", "text", "DEFAULT")))))
                .doesNotThrowAnyException();
    }

    @Test
    void singleReferenceTargetAcceptsSourceReferenceOnSameObject() {
        assertThatCode(
                        () ->
                                validate(
                                        order(Map.of()),
                                        statement(Map.of()),
                                        form(
                                                plain("bank"),
                                                node(
                                                        "payAccount",
                                                        new FormFills.Binding(
                                                                "bank",
                                                                "account",
                                                                "SOURCE_CHANGE")))))
                .doesNotThrowAnyException();
    }

    @Test
    void referenceTargetRejectsSourceOnAnotherObject() {
        assertThatThrownBy(
                        () ->
                                validate(
                                        order(Map.of()),
                                        statement(Map.of()),
                                        form(
                                                plain("bank"),
                                                node(
                                                        "payAccount",
                                                        new FormFills.Binding(
                                                                "bank", "voucher", "DEFAULT")))))
                .hasMessageContaining("来源与目标必须引用同一对象");
    }

    @Test
    void referenceTargetRejectsScalarSourceField() {
        assertThatThrownBy(
                        () ->
                                validate(
                                        order(Map.of()),
                                        statement(Map.of()),
                                        form(
                                                plain("bank"),
                                                node(
                                                        "payAccount",
                                                        new FormFills.Binding(
                                                                "bank", "text", "DEFAULT")))))
                .hasMessageContaining("来源必须是来源对象上的单值引用字段");
    }

    @Test
    void unsupportedTargetsAreRejected() {
        DataCenter.Definition order = order(Map.of());
        DataCenter.Definition statement = statement(Map.of());
        assertThatThrownBy(
                        () ->
                                validate(
                                        order,
                                        statement,
                                        form(
                                                plain("bank"),
                                                node(
                                                        "payAccount",
                                                        new FormFills.Binding(
                                                                "bank", "account", "DEFAULT"),
                                                        true))))
                .hasMessageContaining("目标不能是只读字段");
        assertThatThrownBy(
                        () ->
                                validate(
                                        order,
                                        statement,
                                        form(
                                                plain("bank"),
                                                node(
                                                        "relation_r_flow",
                                                        new FormFills.Binding(
                                                                "bank", "account", "DEFAULT")))))
                .hasMessageContaining("暂不支持多选关系目标");
        assertThatThrownBy(
                        () ->
                                validate(
                                        order,
                                        statement,
                                        form(
                                                plain("bank"),
                                                node(
                                                        "total",
                                                        new FormFills.Binding(
                                                                "bank", "paid", "DEFAULT")))))
                .hasMessageContaining("目标不能是计算或自动编号字段");
        assertThatThrownBy(
                        () ->
                                validate(
                                        order,
                                        statement,
                                        form(
                                                plain("bank"),
                                                node(
                                                        "attachment",
                                                        new FormFills.Binding(
                                                                "bank", "file", "DEFAULT")))))
                .hasMessageContaining("暂不支持此字段类型");
    }

    @Test
    void missingAndInactiveFieldMessagesAreDistinguishable() {
        DataCenter.Definition order = order(Map.of());
        DataCenter.Definition statement = statement(Map.of());
        assertThatThrownBy(
                        () ->
                                validate(
                                        order,
                                        statement,
                                        form(
                                                node(
                                                        "memo",
                                                        new FormFills.Binding("", "", "DEFAULT")))))
                .hasMessageContaining("“备注”的关联带入尚未选择关联来源字段");
        assertThatThrownBy(
                        () ->
                                validate(
                                        order,
                                        statement,
                                        form(
                                                plain("bank"),
                                                node(
                                                        "memo",
                                                        new FormFills.Binding(
                                                                "bank", "", "DEFAULT")))))
                .hasMessageContaining("“备注”的关联带入尚未选择来源字段");
        assertThatThrownBy(
                        () ->
                                validate(
                                        order,
                                        statement,
                                        form(
                                                plain("bank"),
                                                node(
                                                        "memo",
                                                        new FormFills.Binding(
                                                                "bank", "gone", "DEFAULT")))))
                .hasMessageContaining("来源字段不存在");
        assertThatThrownBy(
                        () ->
                                validate(
                                        order,
                                        statement(Map.of("stopped", inactiveOptions())),
                                        form(
                                                plain("bank"),
                                                node(
                                                        "memo",
                                                        new FormFills.Binding(
                                                                "bank", "stopped", "DEFAULT")))))
                .hasMessageContaining("来源字段“停用摘要”已停用");
        assertThatThrownBy(
                        () ->
                                validate(
                                        order(Map.of("memo", inactiveOptions())),
                                        statement,
                                        form(
                                                plain("bank"),
                                                node(
                                                        "memo",
                                                        new FormFills.Binding(
                                                                "bank", "text", "DEFAULT")))))
                .hasMessageContaining("目标字段已停用");
    }

    @Test
    void sourceRelationMustBeMainFormSingleValue() {
        assertThatThrownBy(
                        () ->
                                validate(
                                        order(Map.of()),
                                        statement(Map.of()),
                                        form(
                                                node(
                                                        "memo",
                                                        new FormFills.Binding(
                                                                "relation_r_flow",
                                                                "text",
                                                                "DEFAULT")))))
                .hasMessageContaining("来源必须是本表单的关联字段");
        DataCenter.Definition detailSource =
                new DataCenter.Definition(
                        "order",
                        "order",
                        "order",
                        null,
                        "public",
                        "t_order",
                        "GENERATED",
                        false,
                        "memo",
                        DataCenter.Settings.defaults(),
                        order(Map.of()).fields(),
                        Map.of(),
                        List.of(
                                new DataCenter.Relation(
                                        "r_detail",
                                        "detail_bank",
                                        "明细银行流水",
                                        RelationTypeEnum.REFERENCE.getCode(),
                                        "bankStatement",
                                        "bank",
                                        null,
                                        false,
                                        "RESTRICT",
                                        "detail1")),
                        List.of(),
                        List.of());
        assertThatThrownBy(
                        () ->
                                validate(
                                        detailSource,
                                        statement(Map.of()),
                                        form(
                                                plain("bank"),
                                                node(
                                                        "memo",
                                                        new FormFills.Binding(
                                                                "bank", "text", "DEFAULT")))))
                .hasMessageContaining("来源必须是主表单的关联字段");
    }

    @Test
    void chainFillIsAllowedWhileCycleIsRejected() {
        // 链式：银行流水被“银行账户”带入写入，备注再取银行流水上的字段；方向单一不成环。
        assertThatCode(
                        () ->
                                validate(
                                        order(Map.of()),
                                        statement(Map.of()),
                                        form(
                                                plain("payAccount"),
                                                node(
                                                        "bank",
                                                        new FormFills.Binding(
                                                                "payAccount",
                                                                "statement",
                                                                "DEFAULT")),
                                                node(
                                                        "memo",
                                                        new FormFills.Binding(
                                                                "bank", "text", "DEFAULT")))))
                .doesNotThrowAnyException();
        // 环：银行账户与银行流水互为带入来源，运行端会互相触发。
        assertThatThrownBy(
                        () ->
                                validate(
                                        order(Map.of()),
                                        statement(Map.of()),
                                        form(
                                                node(
                                                        "bank",
                                                        new FormFills.Binding(
                                                                "payAccount",
                                                                "statement",
                                                                "DEFAULT")),
                                                node(
                                                        "payAccount",
                                                        new FormFills.Binding(
                                                                "bank", "account", "DEFAULT")))))
                .hasMessageContaining("关联带入存在循环联动")
                .hasMessageContaining("银行流水")
                .hasMessageContaining("银行账户");
    }

    @Test
    void newValueRulesProtectTheirTargetsWhileLegacyClearStillWorks() {
        ApplicationUi.Form form =
                form(
                        plain("bank"),
                        node("memo", new FormFills.Binding("bank", "text", "SOURCE_CHANGE", true)),
                        node(
                                "amount",
                                new FormFills.Binding("bank", "paid", "SOURCE_CHANGE", true)));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("bank", null);
        input.put("memo", "规则计算的新值");
        input.put("amount", 12);
        Map<String, Object> previous = Map.of("bank", "1", "memo", "旧值", "amount", 9);
        for (FieldRules rules :
                List.of(
                        new FieldRules(null, null, "1", null, null, null),
                        new FieldRules(
                                null,
                                new FieldRules.Linkage(
                                        "2", List.of(), "3", "FIRST", true, null, null),
                                null,
                                null,
                                null,
                                null))) {
            Map<String, Object> result =
                    FormFillBindings.clearEmptySources(
                            form,
                            input,
                            previous,
                            Set.of("amount"),
                            Map.of("memo", DataCenter.FieldOptions.defaults().withRules(rules)));
            assertThat(result).containsEntry("memo", "规则计算的新值").containsEntry("amount", null);
            assertThat(input).containsEntry("amount", 12);
        }
    }

    @Test
    void referenceFilterAloneDoesNotSuppressLegacyClear() {
        ApplicationUi.Form form =
                form(
                        plain("bank"),
                        node("memo", new FormFills.Binding("bank", "text", "SOURCE_CHANGE", true)));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("bank", null);
        input.put("memo", "旧值");
        FieldRules rules =
                new FieldRules(
                        new FieldRules.Reference("name", List.of()), null, null, null, null, null);
        assertThat(
                        FormFillBindings.clearEmptySources(
                                form,
                                input,
                                Map.of("bank", "1", "memo", "旧值"),
                                Set.of("memo"),
                                Map.of(
                                        "memo",
                                        DataCenter.FieldOptions.defaults().withRules(rules))))
                .containsEntry("memo", null);
    }
}
