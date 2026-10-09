package com.richuang.os.nocode.metadata.service.object;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.FieldConversionCompatibility.Field;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.richuang.os.nocode.metadata.dal.mapper.DataCenterMapper;
import com.richuang.os.nocode.metadata.service.formula.Calculations;
import com.richuang.os.nocode.metadata.service.formula.FieldExpressions;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 对象字段影响按稳定字段 ID 和公式语法解析，停用且不再生效的资源不产生阻断。 */
@Component
public class ObjectFieldConversionDependencies implements FieldConversionDependencyInspector {
    private static final Pattern SUMMARY =
            Pattern.compile(
                    "(count|sum|avg|min|max)\\(([a-z][a-z0-9_]*)(?:\\.([a-z][a-z0-9_]*))?\\)");

    @Resource private DataCenterMapper store;
    @Resource private ObjectDesignService designs;

    @Override
    public List<Impact> inspect(
            DataCenter.Definition previous, DataCenter.Definition proposed, Set<String> fieldIds) {
        return inspect(previous, proposed, fieldIds, Set.of());
    }

    @Override
    public List<Impact> inspect(
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            Set<String> fieldIds,
            Set<String> clearedFieldIds) {
        Map<String, DataCenter.Definition> definitions = new LinkedHashMap<>();
        for (ObjectDraftHeadDO head : store.allHeads()) {
            if (!ObjectStatusEnum.ACTIVE.matches(head.getStatus())
                    || head.getCurrentPublishedVersionNo() == null
                    || head.getId().toString().equals(proposed.objectId())) continue;
            DataCenter.Definition published = designs.published(head.getId().toString());
            if (published != null) definitions.put(published.objectId(), published);
        }
        definitions.put(proposed.objectId(), proposed);
        return inspectDefinitions(previous, proposed, fieldIds, definitions, clearedFieldIds);
    }

    /** 纯配置入口便于回归，也供不带数据库的设计预检复用。 */
    public static List<Impact> inspectDefinitions(
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            Set<String> fieldIds,
            Map<String, DataCenter.Definition> definitions) {
        return inspectDefinitions(previous, proposed, fieldIds, definitions, Set.of());
    }

    public static List<Impact> inspectDefinitions(
            DataCenter.Definition previous,
            DataCenter.Definition proposed,
            Set<String> fieldIds,
            Map<String, DataCenter.Definition> definitions,
            Set<String> clearedFieldIds) {
        Map<String, Field> before = FieldConversionCompatibility.fields(previous);
        Map<String, Field> after = FieldConversionCompatibility.fields(proposed);
        List<Impact> result = new ArrayList<>();
        for (String changedId : fieldIds) {
            Field old = before.get(changedId);
            Field next = after.get(changedId);
            if (old == null) continue;
            boolean incompatible = !FieldConversionCompatibility.valueCompatible(old, next);
            boolean storage = FieldConversionCompatibility.storageChanged(old, next);
            for (DataCenter.Definition owner : definitions.values()) {
                if (owner.objectId().equals(proposed.objectId())) {
                    for (DataCenter.Relation relation : owner.relations())
                        if (changedId.equals(relation.fieldId())
                                && previous.relations().stream()
                                        .anyMatch(
                                                oldRelation ->
                                                        oldRelation.id().equals(relation.id())
                                                                && changedId.equals(
                                                                        oldRelation.fieldId()))
                                && (incompatible || storage))
                            add(
                                    result,
                                    changedId,
                                    owner,
                                    "对象关系 / " + relation.name(),
                                    "原列仍作为该关系的引用列，请先解除或调整关系");
                    document(result, changedId, owner, incompatible);
                }
                // 对象规则（数据联动、引用筛选、公式默认值、挑取值）按字段身份引用其它字段，值类型变了就要先调整。
                if (incompatible) {
                    // 来源字段改了选项集或选项来源但仍是单选/多选：挑取值照常取它实际生效的选项，不算被打断。
                    boolean stillChoices =
                            next != null
                                    && (FieldTypeEnum.SELECT.matches(next.definition().type())
                                            || FieldTypeEnum.MULTI_SELECT.matches(
                                                    next.definition().type()));
                    for (FieldRuleValidator.Usage usage :
                            FieldRuleValidator.usages(owner, proposed.objectId(), changedId))
                        if (!(usage.pick() && stillChoices))
                            add(result, changedId, owner, usage.location(), usage.reason());
                }
                calculations(
                        result,
                        changedId,
                        old,
                        proposed,
                        owner,
                        incompatible,
                        storage,
                        definitions,
                        clearedFieldIds.contains(changedId));
            }
        }
        return result.stream().distinct().toList();
    }

    /** 对象规则的本地依赖与跨对象来源都按字段稳定身份检查，转换预检不能遗漏新规则。 */
    private static void objectRules(
            List<Impact> result,
            String changedId,
            DataCenter.Definition proposed,
            DataCenter.Definition owner,
            boolean incompatible,
            boolean clearing) {
        if (!incompatible && !clearing) return;
        boolean sameObject = owner.objectId().equals(proposed.objectId());
        FieldRuleGraph graph = FieldRuleGraph.of(owner);
        Map<String, Field> fields = FieldConversionCompatibility.fields(owner);
        for (Map.Entry<String, Field> entry : fields.entrySet()) {
            FieldRules rule = entry.getValue().options().rules();
            String targetId = entry.getKey();
            String location = "字段 / " + entry.getValue().definition().name();
            if (rule != null) {
                boolean local = sameObject && graph.dependsOn(targetId).contains(changedId);
                boolean source =
                        rule.linkage() != null
                                && proposed.objectId().equals(rule.linkage().sourceObjectId())
                                && (changedId.equals(rule.linkage().valueFieldId())
                                        || ruleConditionsUse(
                                                rule.linkage().conditions(), changedId));
                if (rule.reference() != null) {
                    DataCenter.Relation relation =
                            owner.relations().stream()
                                    .filter(value -> Objects.equals(value.fieldId(), targetId))
                                    .findFirst()
                                    .orElse(null);
                    source |=
                            relation != null
                                    && proposed.objectId().equals(relation.targetObjectId())
                                    && (changedId.equals(rule.reference().labelFieldId())
                                            || ruleConditionsUse(
                                                    rule.reference().filter(), changedId));
                }
                if (local || source || sameObject && changedId.equals(targetId) && incompatible)
                    add(
                            result,
                            changedId,
                            owner,
                            location + " / 对象规则",
                            clearing
                                    ? "清空规则来源不会重算已有记录的联动或公式结果，请先调整规则并确认历史数据"
                                    : "数据联动、引用筛选或公式默认值依赖原字段类型，请先调整对应规则");
            }
            SelectionFields.Source selection = entry.getValue().options().selection();
            if (selection != null
                    && proposed.objectId().equals(selection.sourceObjectId())
                    && changedId.equals(selection.sourceFieldId())
                    && incompatible)
                add(result, changedId, owner, location + " / 挑取值来源", "挑取值仍复用该字段的选项定义，请先调整选项来源");
        }
    }

    private static boolean ruleConditionsUse(
            List<FieldRules.Condition> conditions, String fieldId) {
        return conditions != null
                && conditions.stream().anyMatch(condition -> fieldId.equals(condition.fieldId()));
    }

    private static void calculations(
            List<Impact> result,
            String changedId,
            Field changed,
            DataCenter.Definition proposed,
            DataCenter.Definition owner,
            boolean incompatible,
            boolean storage,
            Map<String, DataCenter.Definition> definitions,
            boolean clearing) {
        inspectFields(
                result,
                changedId,
                changed,
                proposed,
                owner,
                owner.fields(),
                owner.fieldOptions(),
                "主表字段",
                incompatible,
                storage,
                definitions,
                clearing);
        for (DataCenter.Detail detail : owner.details())
            if (!MemberStateEnum.INACTIVE.matches(detail.state()))
                inspectFields(
                        result,
                        changedId,
                        changed,
                        proposed,
                        owner,
                        detail.fields(),
                        detail.fieldOptions(),
                        "内部明细 / " + detail.name(),
                        incompatible,
                        storage,
                        definitions,
                        clearing);
    }

    private static void inspectFields(
            List<Impact> result,
            String changedId,
            Field changed,
            DataCenter.Definition proposed,
            DataCenter.Definition owner,
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options,
            String location,
            boolean incompatible,
            boolean storage,
            Map<String, DataCenter.Definition> definitions,
            boolean clearing) {
        boolean sameObject = owner.objectId().equals(proposed.objectId());
        Map<String, String> codes = new HashMap<>();
        for (FieldDefinition field : fields) {
            codes.put(field.code(), field.id());
            codes.put(Calculations.PREVIOUS_PREFIX + field.code(), field.id());
        }
        for (FieldDefinition field : fields) {
            DataCenter.FieldOptions option =
                    options.getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            if (MemberStateEnum.INACTIVE.matches(option.state()) || changedId.equals(field.id()))
                continue;
            CalculationOptions calculation = option.calculation();
            boolean persistedClear =
                    clearing
                            && calculation != null
                            && CalculationUpdateEnum.ON_SAVE.matches(calculation.updateMode());
            String use = location + " / " + field.name();
            if (FieldTypeEnum.SUMMARY.matches(field.type())) {
                Matcher summary = SUMMARY.matcher(Objects.toString(option.expression(), ""));
                if (sameObject && incompatible && summary.matches() && summary.group(3) != null) {
                    boolean references =
                            owner.details().stream()
                                    .filter(d -> d.code().equals(summary.group(2)))
                                    .flatMap(d -> d.fields().stream())
                                    .anyMatch(
                                            f ->
                                                    f.id().equals(changedId)
                                                            && f.code().equals(summary.group(3)));
                    if (references)
                        add(result, changedId, owner, use + " / 汇总", "汇总仍要求原数值字段，请先调整或删除该汇总");
                }
            } else if (FieldTypeEnum.FORMULA.matches(field.type())) {
                if (sameObject
                        && option.expression() != null
                        && (calculation == null
                                || CalculationModeEnum.LOCAL.matches(calculation.mode())
                                || Calculations.sequence(calculation))) {
                    Set<String> references =
                            FieldExpressions.parse(option.expression(), codes).references();
                    if (references.stream().map(codes::get).anyMatch(changedId::equals)
                            && (incompatible || calculation == null && storage || persistedClear))
                        add(
                                result,
                                changedId,
                                owner,
                                use + " / 公式",
                                persistedClear
                                        ? "清空原列不会触发该计算结果重新保存，请先调整或移除该持久计算配置"
                                        : calculation == null && storage
                                                ? "物理计算列依赖原列类型，请先解除该计算列依赖"
                                                : "公式使用该字段的原值类型，请先调整或删除该公式");
                }
                if (calculation == null) continue;
                String target = Calculations.target(owner, calculation);
                String code = changed.definition().code();
                // 匹配两侧要求完全相同的逻辑类型；整数扩为小数也可能打破这项契约。
                boolean matchChanged = false;
                DataCenter.Definition targetDefinition = definitions.get(target);
                if (targetDefinition != null)
                    for (CalculationOptions.Match match : calculation.conditions()) {
                        if (match.localField() == null || match.localField().isBlank()) continue;
                        boolean affected =
                                sameObject && code.equals(match.localField())
                                        || proposed.objectId().equals(target)
                                                && code.equals(match.targetField());
                        if (affected
                                && !Calculations.field(owner, match.localField())
                                        .type()
                                        .equals(
                                                Calculations.field(
                                                                targetDefinition,
                                                                match.targetField())
                                                        .type())) matchChanged = true;
                    }
                boolean used =
                        proposed.objectId().equals(target)
                                && (Objects.equals(calculation.targetField(), code)
                                        || calculation.conditions().stream()
                                                .anyMatch(c -> code.equals(c.targetField())));
                if (sameObject) {
                    used |=
                            calculation.conditions().stream()
                                    .anyMatch(c -> code.equals(c.localField()));
                    used |= calculation.groupFields().contains(code);
                    if (calculation.runningTotal() != null) {
                        CalculationOptions.RunningTotal running = calculation.runningTotal();
                        used |=
                                Arrays.asList(
                                                running.orderField(),
                                                running.tieBreakerField(),
                                                running.subtractField(),
                                                running.initialField())
                                        .contains(code);
                    }
                    if (calculation.sequence() != null)
                        used |=
                                Arrays.asList(
                                                calculation.sequence().orderField(),
                                                calculation.sequence().tieBreakerField())
                                        .contains(code);
                    if (calculation.relationId() != null)
                        used |=
                                owner.relations().stream()
                                        .anyMatch(
                                                r ->
                                                        r.id().equals(calculation.relationId())
                                                                && changedId.equals(r.fieldId()));
                }
                if (used && (incompatible || matchChanged || persistedClear))
                    add(
                            result,
                            changedId,
                            owner,
                            use + " / 计算来源或条件",
                            persistedClear
                                    ? "清空来源或匹配字段不会重算已保存的计算结果，请先调整或移除该持久计算配置"
                                    : "计算来源、分组、排序或匹配条件仍使用原字段类型，请先调整该计算配置");
            }
        }
    }

    private static void document(
            List<Impact> result, String id, DataCenter.Definition owner, boolean incompatible) {
        if (!incompatible || owner.settings() == null || owner.settings().documentPolicy() == null)
            return;
        DocumentPolicy policy = owner.settings().documentPolicy();
        for (DocumentPolicy.Rule rule : policy.rules())
            if (expressionUses(rule.when(), id) || expressionUses(rule.assertion(), id))
                add(result, id, owner, "整单规则 / " + rule.name(), "规则条件仍使用原字段类型，请先调整或删除该规则");
        if (policy.lifecycle() != null && id.equals(policy.lifecycle().fieldId()))
            add(result, id, owner, "整单状态 / 状态字段", "状态流转仍读写该字段的原状态编码，请先调整状态配置");
        if (policy.handling() != null) {
            handling(result, id, owner, "新增办理", policy.handling().create());
            handling(result, id, owner, "修改办理", policy.handling().update());
        }
    }

    private static void handling(
            List<Impact> result,
            String id,
            DataCenter.Definition owner,
            String name,
            BusinessHandling.Rule rule) {
        if (rule != null
                && (expressionUses(rule.condition(), id) || rule.variables().containsValue(id)))
            add(result, id, owner, "办理策略 / " + name, "办理条件或变量映射使用原字段类型，请先调整该配置");
    }

    public static boolean expressionUses(DocumentPolicy.Expression expression, String id) {
        return expression != null
                && (id.equals(expression.fieldId())
                        || expression.args().stream().anyMatch(e -> expressionUses(e, id)));
    }

    private static void add(
            List<Impact> result,
            String id,
            DataCenter.Definition owner,
            String location,
            String message) {
        result.add(
                new Impact(
                        id,
                        SourceKind.OBJECT.name(),
                        owner.objectId(),
                        owner.objectName(),
                        location,
                        message,
                        "/nocode/object/editor?id=" + owner.objectId(),
                        true));
    }
}
