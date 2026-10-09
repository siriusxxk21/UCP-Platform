package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.FieldConversionDependencyInspector.Impact;
import com.richuang.os.nocode.application.service.resource.ApplicationFieldConversionDependencies;
import com.richuang.os.nocode.metadata.service.object.ObjectFieldConversionDependencies;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 转换前精确定位实际配置依赖，不因同名文本、普通展示或不相关资源误阻断。 */
class FieldConversionDependencyTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void taskFixedConditionsAndBusinessSortTrackTheBusinessFormInsideNestedProjectPages() {
        Definition before = definition("1", "TEXT");
        Definition after = definition("1", "INTEGER");
        DynamicConditionDTO condition =
                json.convertValue(
                        Map.of(
                                "logic",
                                "AND",
                                "items",
                                List.of(
                                        Map.of(
                                                "type",
                                                "condition",
                                                "field",
                                                "f",
                                                "operator",
                                                "eq",
                                                "value",
                                                "文字"))),
                        DynamicConditionDTO.class);
        ApplicationUi.TaskView taskView =
                new ApplicationUi.TaskView(
                        "business-form",
                        List.of("business:f"),
                        condition,
                        null,
                        List.of(),
                        new ApplicationUi.TaskSort("business:f", true));
        ApplicationUi.TaskView unrelatedView =
                new ApplicationUi.TaskView(
                        "project-form",
                        List.of("business:f"),
                        condition,
                        null,
                        List.of(),
                        new ApplicationUi.TaskSort("business:f", true));
        ApplicationUi.Node taskNode =
                new ApplicationUi.Node(
                        "task-business",
                        "TASKS",
                        null,
                        "project-form",
                        null,
                        24,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        taskView);
        ApplicationUi.Node unrelated =
                new ApplicationUi.Node(
                        "other-object",
                        "TASKS",
                        null,
                        "project-form",
                        null,
                        24,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        unrelatedView);
        ApplicationUi.Page page =
                new ApplicationUi.Page(
                        List.of(
                                new ApplicationUi.Node(
                                        "group",
                                        "GRID",
                                        null,
                                        null,
                                        null,
                                        24,
                                        List.of(taskNode, unrelated))),
                        "2",
                        1);
        ApplicationCenter.Resource businessForm =
                new ApplicationCenter.Resource(
                        "business-form",
                        "FORM",
                        "business_form",
                        "任务表单",
                        resource(
                                        "FORM",
                                        new ApplicationUi.Form(
                                                "1", List.of(node("f", true)), List.of()))
                                .config());
        ApplicationCenter.Resource projectForm =
                new ApplicationCenter.Resource(
                        "project-form",
                        "FORM",
                        "project_form",
                        "项目表单",
                        resource(
                                        "FORM",
                                        new ApplicationUi.Form(
                                                "2", List.of(node("f", true)), List.of()))
                                .config());
        List<Impact> impacts =
                app(before, after, resource("PAGE", page), projectForm, businessForm);
        assertThat(impacts)
                .hasSize(2)
                .allMatch(impact -> impact.fieldId().equals("f") && impact.blocking());
        assertThat(impacts)
                .extracting(Impact::location)
                .containsExactlyInAnyOrder(
                        "PAGE / PAGE配置 / 任务固定条件 task-business",
                        "PAGE / PAGE配置 / 任务排序 task-business");
        assertThat(impacts)
                .allMatch(
                        impact ->
                                impact.sourceId().equals("app")
                                        && impact.route().equals("/nocode-app/workspace?id=app"));
    }

    @Test
    void unrelatedAndReadOnlyPresentationDoNotBlockConversion() {
        Definition before = definition("1", "TEXT");
        Definition after = definition("1", "INTEGER");
        ApplicationUi.Form form = new ApplicationUi.Form("1", List.of(node("f", true)), List.of());
        ApplicationUi.View view =
                new ApplicationUi.View("1", List.of("f"), Map.of(), "f", false, 20, null);
        ApplicationBusiness.Action unrelated =
                new ApplicationBusiness.Action(
                        "2", "UPDATE_FIELDS", Map.of("f", "f"), null, Map.of());
        assertThat(
                        app(
                                before,
                                after,
                                resource("FORM", form),
                                resource("VIEW", view),
                                resource("ACTION", unrelated)))
                .isEmpty();
    }

    @Test
    void editableFormAndFixedFilterPointToExactResources() {
        Definition before = definition("1", "TEXT");
        Definition after = definition("1", "INTEGER");
        ApplicationUi.Form form = new ApplicationUi.Form("1", List.of(node("f", false)), List.of());
        ApplicationUi.View view =
                new ApplicationUi.View("1", List.of("f"), Map.of("f", "文字"), null, false, 20, null);
        List<Impact> impacts = app(before, after, resource("FORM", form), resource("VIEW", view));
        assertThat(impacts).hasSize(2).allMatch(i -> i.fieldId().equals("f") && i.blocking());
        assertThat(impacts).allMatch(i -> i.route().equals("/nocode-app/workspace?id=app"));
        assertThat(impacts)
                .anyMatch(i -> i.location().contains("字段写入"))
                .anyMatch(i -> i.location().contains("固定筛选"));
    }

    @Test
    void numericWideningRetainsEditableFormWhileNarrowingRequiresRepair() {
        ApplicationUi.Form form = new ApplicationUi.Form("1", List.of(node("f", false)), List.of());
        assertThat(
                        app(
                                definition("1", "INTEGER"),
                                definition("1", "DECIMAL"),
                                resource("FORM", form)))
                .isEmpty();
        assertThat(
                        app(
                                definition("1", "DECIMAL"),
                                definition("1", "INTEGER"),
                                resource("FORM", form)))
                .hasSize(1);
    }

    @Test
    void disabledAutomationDoesNotBlockButActiveTargetWriteDoes() {
        ApplicationAutomations.Assignment assignment =
                new ApplicationAutomations.Assignment("f", "VALUE", null, "文字", null);
        ApplicationAutomations.Config active =
                new ApplicationAutomations.Config(
                        "2",
                        "1",
                        true,
                        "AGGREGATE",
                        Set.of("CREATE"),
                        null,
                        null,
                        List.of(assignment));
        ApplicationAutomations.Config disabled =
                new ApplicationAutomations.Config(
                        "2",
                        "1",
                        false,
                        "AGGREGATE",
                        Set.of("CREATE"),
                        null,
                        null,
                        List.of(assignment));
        assertThat(
                        app(
                                definition("1", "TEXT"),
                                definition("1", "INTEGER"),
                                resource("AUTOMATION", disabled)))
                .isEmpty();
        assertThat(
                        app(
                                definition("1", "TEXT"),
                                definition("1", "INTEGER"),
                                resource("AUTOMATION", active)))
                .singleElement()
                .satisfies(i -> assertThat(i.location()).contains("自动更新目标"));
    }

    @Test
    void relationFillFindsTargetFieldInOtherObject() {
        Definition before = definition("1", "TEXT");
        Definition after = definition("1", "INTEGER");
        Definition other =
                withRelations(
                        definition("2", "INTEGER"),
                        List.of(
                                new Relation(
                                        "r",
                                        "related",
                                        "引用",
                                        "MANY_TO_ONE",
                                        "1",
                                        "f",
                                        null,
                                        false,
                                        "RESTRICT")));
        ApplicationUi.FieldPresentation presentation =
                new ApplicationUi.FieldPresentation(
                        null,
                        null,
                        null,
                        true,
                        null,
                        null,
                        new FormFills.Binding("f", "f", "SNAPSHOT"));
        ApplicationUi.Form form =
                new ApplicationUi.Form(
                        "2",
                        List.of(
                                new ApplicationUi.Node(
                                        "n",
                                        "field",
                                        "other",
                                        null,
                                        null,
                                        24,
                                        List.of(),
                                        null,
                                        presentation)),
                        List.of());
        ApplicationCenter.Definition application =
                new ApplicationCenter.Definition(List.of(), List.of(resource("FORM", form)));
        List<Impact> impacts =
                ApplicationFieldConversionDependencies.inspectDefinition(
                        json,
                        "app",
                        "采购应用",
                        application,
                        before,
                        after,
                        Set.of("f"),
                        Map.of("1", before, "2", other));
        assertThat(impacts)
                .isNotEmpty()
                .allMatch(i -> i.fieldId().equals("f"))
                .anyMatch(i -> i.location().contains("关联带入取值"));
    }

    @Test
    void formulaReferencesAreParsedNotMatchedAsSubstringsOrStringLiterals() {
        Definition before =
                withFormula(definition("1", "TEXT"), "upper(value_extra) || 'value'", null);
        Definition after =
                withFormula(definition("1", "INTEGER"), "upper(value_extra) || 'value'", null);
        assertThat(objects(before, after)).isEmpty();
        before = withFormula(definition("1", "TEXT"), "upper(value)", null);
        after = withFormula(definition("1", "INTEGER"), "upper(value)", null);
        assertThat(objects(before, after))
                .singleElement()
                .satisfies(i -> assertThat(i.location()).contains("公式"));
    }

    @Test
    void storedFormulaBlocksPhysicalTypeChangeEvenWhenValueFamilyIsCompatible() {
        Definition before = withFormula(definition("1", "INTEGER"), "value * 2", null);
        Definition after = withFormula(definition("1", "DECIMAL"), "value * 2", null);
        assertThat(objects(before, after))
                .singleElement()
                .satisfies(i -> assertThat(i.message()).contains("物理计算列"));
    }

    @Test
    void clearingNumericSourceBlocksOnSaveCalculationButNotEmptyColumnOrLiveCalculation() {
        CalculationOptions onSave =
                new CalculationOptions(
                        "LOCAL", "ON_SAVE", null, null, null, null, "AND", List.of(), false,
                        List.of(), null);
        Definition before = withFormula(definition("1", "DECIMAL"), "value * 2", onSave);
        Definition after = withFormula(definition("1", "INTEGER"), "value * 2", onSave);
        assertThat(
                        ObjectFieldConversionDependencies.inspectDefinitions(
                                before, after, Set.of("f"), Map.of("1", after), Set.of("f")))
                .singleElement()
                .satisfies(i -> assertThat(i.message()).contains("清空").contains("持久计算"));
        assertThat(
                        ObjectFieldConversionDependencies.inspectDefinitions(
                                before, after, Set.of("f"), Map.of("1", after), Set.of()))
                .isEmpty();
        CalculationOptions live =
                new CalculationOptions(
                        "LOCAL", "LIVE", null, null, null, null, "AND", List.of(), false, List.of(),
                        null);
        Definition liveBefore = withFormula(definition("1", "DECIMAL"), "value * 2", live);
        Definition liveAfter = withFormula(definition("1", "INTEGER"), "value * 2", live);
        assertThat(
                        ObjectFieldConversionDependencies.inspectDefinitions(
                                liveBefore,
                                liveAfter,
                                Set.of("f"),
                                Map.of("1", liveAfter),
                                Set.of("f")))
                .isEmpty();
    }

    @Test
    void clearingExternalNumericSourceBlocksSavedLookupResultsEvenWithinNumericFamily() {
        Definition before = definition("1", "DECIMAL");
        Definition after = definition("1", "INTEGER");
        CalculationOptions calculation =
                new CalculationOptions(
                        "LOOKUP", "ON_SAVE", "1", null, "value", "SUM", "AND", List.of(), false,
                        List.of(), null);
        Definition other = withFormula(definition("2", "INTEGER"), null, calculation);
        assertThat(
                        ObjectFieldConversionDependencies.inspectDefinitions(
                                before,
                                after,
                                Set.of("f"),
                                Map.of("1", after, "2", other),
                                Set.of("f")))
                .singleElement()
                .satisfies(i -> assertThat(i.sourceId()).isEqualTo("2"));
        assertThat(
                        ObjectFieldConversionDependencies.inspectDefinitions(
                                before,
                                after,
                                Set.of("f"),
                                Map.of("1", after, "2", other),
                                Set.of()))
                .isEmpty();
    }

    @Test
    void clearingMaintainSourceReportsHistoryAndMaintenanceWhileEmptyColumnDoesNot() {
        Definition before = definition("1", "DECIMAL");
        Definition after = definition("1", "INTEGER");
        Definition other = definition("2", "DECIMAL");
        ApplicationAutomations.Assignment assignment =
                new ApplicationAutomations.Assignment("f", "SUM", "f", null, null);
        ApplicationAutomations.Config maintain =
                new ApplicationAutomations.Config(
                        "1",
                        "2",
                        true,
                        "MAINTAIN",
                        Set.of("CREATE"),
                        null,
                        null,
                        List.of(assignment));
        ApplicationCenter.Definition application =
                new ApplicationCenter.Definition(
                        List.of(), List.of(resource("AUTOMATION", maintain)));
        assertThat(
                        ApplicationFieldConversionDependencies.inspectDefinition(
                                json,
                                "app",
                                "应用",
                                application,
                                before,
                                after,
                                Set.of("f"),
                                Map.of("1", before, "2", other),
                                Set.of("f")))
                .hasSize(2)
                .allSatisfy(i -> assertThat(i.blocking()).isTrue())
                .extracting(i -> i.message())
                .anySatisfy(message -> assertThat(message).contains("历史结果"))
                .anySatisfy(message -> assertThat(message).contains("直接清空").contains("自动维护"));
        assertThat(
                        ApplicationFieldConversionDependencies.inspectDefinition(
                                json,
                                "app",
                                "应用",
                                application,
                                before,
                                after,
                                Set.of("f"),
                                Map.of("1", before, "2", other),
                                Set.of()))
                .isEmpty();
        ApplicationAutomations.Config event =
                new ApplicationAutomations.Config(
                        "1", "2", true, "EVENT", Set.of("CREATE"), null, null, List.of(assignment));
        ApplicationCenter.Definition eventApp =
                new ApplicationCenter.Definition(List.of(), List.of(resource("AUTOMATION", event)));
        assertThat(
                        ApplicationFieldConversionDependencies.inspectDefinition(
                                json,
                                "app",
                                "应用",
                                eventApp,
                                before,
                                after,
                                Set.of("f"),
                                Map.of("1", before, "2", other),
                                Set.of("f")))
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i.blocking()).isTrue();
                            assertThat(i.message()).contains("历史结果").doesNotContain("自动维护");
                        });
    }

    @Test
    void clearingConditionFieldBlocksMaintenanceEvenForTypeIndependentNullPredicate() {
        Definition before = definition("1", "DECIMAL");
        Definition after = definition("1", "INTEGER");
        DataScope condition =
                new DataScope(
                        "AND", List.of(new DataScope.Condition("f", "isNull", null)), List.of());
        ApplicationAutomations.Config maintain =
                new ApplicationAutomations.Config(
                        "1",
                        "2",
                        true,
                        "MAINTAIN",
                        Set.of("CREATE"),
                        condition,
                        null,
                        List.of(
                                new ApplicationAutomations.Assignment(
                                        "f", "VALUE", null, 0, null)));
        ApplicationCenter.Definition app =
                new ApplicationCenter.Definition(
                        List.of(), List.of(resource("AUTOMATION", maintain)));
        assertThat(
                        ApplicationFieldConversionDependencies.inspectDefinition(
                                json,
                                "app",
                                "应用",
                                app,
                                before,
                                after,
                                Set.of("f"),
                                Map.of("1", before, "2", definition("2", "INTEGER")),
                                Set.of("f")))
                .singleElement()
                .satisfies(i -> assertThat(i.location()).contains("维护范围条件"));
    }

    @Test
    void externalCalculationFindsTargetByObjectAndFieldCode() {
        Definition before = definition("1", "INTEGER");
        Definition after = definition("1", "TEXT");
        CalculationOptions calculation =
                new CalculationOptions(
                        "LOOKUP", "LIVE", "1", null, "value", "SUM", "AND", List.of(), false,
                        List.of(), null, null);
        Definition other = withFormula(definition("2", "INTEGER"), null, calculation);
        List<Impact> impacts =
                ObjectFieldConversionDependencies.inspectDefinitions(
                        before, after, Set.of("f"), Map.of("1", after, "2", other));
        assertThat(impacts)
                .singleElement()
                .satisfies(
                        i -> {
                            assertThat(i.sourceId()).isEqualTo("2");
                            assertThat(i.location()).contains("计算来源");
                        });
    }

    @Test
    void numericFamilyChangeStillBreaksExactTypeLookupMatch() {
        Definition before = definition("1", "INTEGER");
        Definition after = definition("1", "DECIMAL");
        CalculationOptions calculation =
                new CalculationOptions(
                        "LOOKUP",
                        "LIVE",
                        "1",
                        null,
                        "value",
                        "SUM",
                        "AND",
                        List.of(new CalculationOptions.Match("value", "eq", "value", null)),
                        false,
                        List.of(),
                        null,
                        null);
        Definition other = withFormula(definition("2", "INTEGER"), null, calculation);
        assertThat(
                        ObjectFieldConversionDependencies.inspectDefinitions(
                                before, after, Set.of("f"), Map.of("1", after, "2", other)))
                .singleElement()
                .satisfies(i -> assertThat(i.sourceId()).isEqualTo("2"));
    }

    @Test
    void existingRelationMustBeRemovedBeforeChangingItsStorageColumn() {
        Relation relation =
                new Relation(
                        "r", "supplier", "供应商", "MANY_TO_ONE", "2", "f", null, false, "RESTRICT");
        Definition before = withRelations(definition("1", "INTEGER"), List.of(relation));
        Definition after = withRelations(definition("1", "TEXT"), List.of(relation));
        assertThat(objects(before, after))
                .singleElement()
                .satisfies(i -> assertThat(i.location()).contains("供应商"));
        assertThat(objects(before, definition("1", "TEXT"))).isEmpty();
    }

    @Test
    void newConversionRelationIsNotAnExistingDependency() {
        Definition before = definition("1", "SELECT");
        Relation target =
                new Relation(
                        "new",
                        "supplier",
                        "新供应商关系",
                        "MANY_TO_ONE",
                        "2",
                        "f",
                        null,
                        false,
                        "RESTRICT");
        Definition after = withRelations(definition("1", "REFERENCE"), List.of(target));
        assertThat(objects(before, after)).isEmpty();
    }

    @Test
    void valueGroupingRemainsUsableButUnsupportedReportTypeBlocks() {
        ApplicationReports.Config report =
                new ApplicationReports.Config(
                        "1",
                        List.of(new ApplicationReports.Dimension("f", null, "VALUE")),
                        List.of(),
                        Map.of(),
                        List.of(),
                        null,
                        "Asia/Shanghai",
                        "TABLE",
                        null,
                        false,
                        100,
                        null);
        assertThat(
                        app(
                                definition("1", "TEXT"),
                                definition("1", "INTEGER"),
                                resource("REPORT", report)))
                .isEmpty();
        assertThat(
                        app(
                                definition("1", "TEXT"),
                                definition("1", "TEXTAREA"),
                                resource("REPORT", report)))
                .singleElement()
                .satisfies(i -> assertThat(i.location()).contains("分组"));
    }

    @Test
    void businessNumberRequiresSingleLineTextEvenWithinTextFamily() {
        ApplicationBusiness.NumberRule rule =
                new ApplicationBusiness.NumberRule("1", "f", "NO", "DAY", 4);
        assertThat(
                        app(
                                definition("1", "TEXT"),
                                definition("1", "TEXTAREA"),
                                resource("NUMBER_RULE", rule)))
                .anyMatch(i -> i.location().contains("编号要求可写单行文本"));
    }

    @Test
    void automationRejectsNewDecimalSourceWritingExistingIntegerTarget() {
        Definition before = definition("1", "INTEGER");
        Definition after = definition("1", "DECIMAL");
        Definition target = definition("2", "INTEGER");
        ApplicationAutomations.Config config =
                new ApplicationAutomations.Config(
                        "1",
                        "2",
                        true,
                        "EVENT",
                        Set.of("UPDATE"),
                        null,
                        null,
                        List.of(
                                new ApplicationAutomations.Assignment(
                                        "f", "FIELD", "f", null, null)));
        ApplicationCenter.Definition application =
                new ApplicationCenter.Definition(
                        List.of(), List.of(resource("AUTOMATION", config)));
        assertThat(
                        ApplicationFieldConversionDependencies.inspectDefinition(
                                json,
                                "app",
                                "应用",
                                application,
                                before,
                                after,
                                Set.of("f"),
                                Map.of("1", before, "2", target)))
                .anyMatch(i -> i.location().contains("自动更新两侧类型"));
    }

    private List<Impact> objects(Definition before, Definition after) {
        return ObjectFieldConversionDependencies.inspectDefinitions(
                before, after, Set.of("f"), Map.of(after.objectId(), after));
    }

    private List<Impact> app(
            Definition before, Definition after, ApplicationCenter.Resource... resources) {
        return ApplicationFieldConversionDependencies.inspectDefinition(
                json,
                "app",
                "采购应用",
                new ApplicationCenter.Definition(List.of(), List.of(resources)),
                before,
                after,
                Set.of("f"),
                Map.of("1", before));
    }

    private ApplicationCenter.Resource resource(String kind, Object config) {
        return new ApplicationCenter.Resource(
                kind,
                kind,
                kind,
                kind + "配置",
                json.convertValue(config, new TypeReference<Map<String, Object>>() {}));
    }

    private ApplicationUi.Node node(String field, boolean readOnly) {
        return new ApplicationUi.Node(
                "node",
                "field",
                field,
                null,
                null,
                24,
                List.of(),
                null,
                new ApplicationUi.FieldPresentation(null, null, null, readOnly));
    }

    private Definition definition(String id, String type) {
        FieldDefinition field =
                new FieldDefinition(
                        "f",
                        "f",
                        "value",
                        "原字段",
                        type,
                        "TEXT".equals(type) ? 200 : null,
                        "DECIMAL".equals(type) ? 18 : null,
                        "DECIMAL".equals(type) ? 2 : null,
                        false,
                        false,
                        0);
        return new Definition(
                id,
                "object_" + id,
                "对象" + id,
                null,
                "public",
                "biz_object_" + id,
                "GENERATED",
                false,
                "f",
                Settings.defaults(),
                List.of(field),
                Map.of("f", FieldOptions.defaults()),
                List.of(),
                List.of(),
                List.of());
    }

    private Definition withRelations(Definition d, List<Relation> relations) {
        return new Definition(
                d.objectId(),
                d.objectCode(),
                d.objectName(),
                d.description(),
                d.schemaName(),
                d.tableName(),
                d.source(),
                d.readOnly(),
                d.titleFieldId(),
                d.settings(),
                d.fields(),
                d.fieldOptions(),
                relations,
                d.indexes(),
                d.details());
    }

    private Definition withFormula(
            Definition d, String expression, CalculationOptions calculation) {
        List<FieldDefinition> fields = new ArrayList<>(d.fields());
        fields.add(
                new FieldDefinition(
                        "extra",
                        "extra",
                        "value_extra",
                        "不相关字段",
                        "TEXT",
                        200,
                        null,
                        null,
                        false,
                        false,
                        1));
        fields.add(
                new FieldDefinition(
                        "formula", "formula", "result", "计算结果", "FORMULA", null, null, null, false,
                        false, 2));
        Map<String, FieldOptions> options = new HashMap<>(d.fieldOptions());
        options.put(
                "formula",
                new FieldOptions(
                                null,
                                "NORMAL",
                                null,
                                null,
                                null,
                                null,
                                null,
                                "ACTIVE",
                                List.of(),
                                expression,
                                "DECIMAL",
                                "NONE",
                                null,
                                false,
                                false)
                        .withCalculation(calculation));
        return new Definition(
                d.objectId(),
                d.objectCode(),
                d.objectName(),
                d.description(),
                d.schemaName(),
                d.tableName(),
                d.source(),
                d.readOnly(),
                d.titleFieldId(),
                d.settings(),
                fields,
                options,
                d.relations(),
                d.indexes(),
                d.details());
    }
}
