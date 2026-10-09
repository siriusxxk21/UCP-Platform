package com.richuang.os.nocode.runtime.service.rules;

import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.formula.Calculations;

import java.util.*;
import java.util.function.Function;

/**
 * 规则条件的「当前记录字段」替换与编译。只产出底座 DynamicConditionDTO（单层 AND）和按主键匹配的记录 ID，不拼 SQL；执行统一交给
 * RecordConditions.append，全程参数化。
 *
 * <p>判定顺序同老 resolveOne：先收集所有缺值的当前字段（PENDING 优先，不查库、不退化成全量），再逐条校验来源字段、可查询性、算子与值。任何一条画不出都
 * fail-closed，绝不跳过（跳过等于少一个且，命中范围放大）。
 *
 * <p>空值口径（2026-10-01）：产出的条件由 RecordConditions.appendRule 执行——「不等于」对空值安全（来源字段为空的行算不等于）， 「为空 /
 * 不为空」不带比较值，文本形态的列把空串也算空、多选把空列表也算空。
 */
public final class FieldRuleConditions {
    /** 运行端允许的算子；eq 在当前字段为多值时改写为 in。 */
    static final Set<String> OPERATORS =
            Set.of(
                    "eq",
                    "neq",
                    "like",
                    "notLike",
                    "gt",
                    "gte",
                    "lt",
                    "lte",
                    "between",
                    "containsAny",
                    "isNull",
                    "notNull");

    private static final Set<FieldTypeEnum> NOT_FILTERABLE =
            Set.of(
                    FieldTypeEnum.SUMMARY,
                    FieldTypeEnum.IMAGE,
                    FieldTypeEnum.ATTACHMENT,
                    FieldTypeEnum.RICH_TEXT,
                    FieldTypeEnum.REGION,
                    FieldTypeEnum.CASCADE,
                    FieldTypeEnum.URL,
                    FieldTypeEnum.UUID);

    /** 失败消息以它开头表示「配置本身有误」（例如引用字段的固定值不是目标对象里的记录），调用方拼成「…的引用筛选配置有误：…」； 其余失败拼成「…无法求值：…」。 */
    public static final String CONFIG_ERROR = "配置有误：";

    private FieldRuleConditions() {}

    /** 失败原因接在「「字段」的引用筛选 / 数据联动」之后：配置有误的原样接，其余前面加「无法求值：」。 */
    public static String reason(String message, String state) {
        if (message != null && message.startsWith(CONFIG_ERROR)) return message;
        return "无法求值：" + (message == null ? state : message);
    }

    /**
     * 替换并编译一组条件（引用筛选等不认「等于当前记录」的场合）：出现 CURRENT_RECORD 条件即失败，不放大命中范围。
     *
     * @param conditions 规则里的条件（只有 AND）；空表示命中全部行
     * @param source 条件字段所属的来源对象固定版本
     * @param allowed 来源对象上当前操作者可查询的字段（已剔除 LIVE 计算字段）
     * @param values 当前记录值，按全局字段 ID；运行候选/求值时来自请求，保存时来自服务端合并值
     * @param formFields 当前记录可引用的字段 ID → 名称；不在其中的 formFieldId 视为本对象没有此字段
     */
    public static ReferenceScope compile(
            List<FieldRules.Condition> conditions,
            DataCenter.Definition source,
            Set<String> allowed,
            Map<String, Object> values,
            Map<String, String> formFields) {
        return compile(conditions, source, allowed, values, formFields, null);
    }

    /** 同上；objectNames 按对象 ID 给出对象名，用于「固定值不是「X」里的记录」这句话（取不到时退回条件字段名）。 */
    public static ReferenceScope compile(
            List<FieldRules.Condition> conditions,
            DataCenter.Definition source,
            Set<String> allowed,
            Map<String, Object> values,
            Map<String, String> formFields,
            Function<String, String> objectNames) {
        return compile(
                conditions, source, allowed, values, formFields, false, null, true, objectNames);
    }

    /**
     * 数据联动的条件：currentRecord 为真（联动开启了自动更新）时认「等于当前记录」（CURRENT_RECORD）。
     *
     * @param currentRecordId 当前记录 ID；为 null（新建未保存）时该条件待定，不查库
     */
    public static ReferenceScope compile(
            List<FieldRules.Condition> conditions,
            DataCenter.Definition source,
            Set<String> allowed,
            Map<String, Object> values,
            Map<String, String> formFields,
            boolean currentRecord,
            String currentRecordId) {
        return compile(
                conditions,
                source,
                allowed,
                values,
                formFields,
                currentRecord,
                currentRecordId,
                (Function<String, String>) null);
    }

    /** 同上；objectNames 见六参数的引用筛选重载。 */
    public static ReferenceScope compile(
            List<FieldRules.Condition> conditions,
            DataCenter.Definition source,
            Set<String> allowed,
            Map<String, Object> values,
            Map<String, String> formFields,
            boolean currentRecord,
            String currentRecordId,
            Function<String, String> objectNames) {
        return compile(
                conditions,
                source,
                allowed,
                values,
                formFields,
                currentRecord,
                currentRecordId,
                false,
                objectNames);
    }

    /**
     * @param relativeDates 是否认相对日期：引用筛选认（候选现查现算）；数据联动不认（取到的值写进字段，过了零点不会自己变，设计保存已拒绝，这里兜底）
     */
    private static ReferenceScope compile(
            List<FieldRules.Condition> conditions,
            DataCenter.Definition source,
            Set<String> allowed,
            Map<String, Object> values,
            Map<String, String> formFields,
            boolean currentRecord,
            String currentRecordId,
            boolean relativeDates,
            Function<String, String> objectNames) {
        var list = conditions == null ? List.<FieldRules.Condition>of() : conditions;
        var pending = new LinkedHashSet<String>();
        boolean awaitingRecord = false;
        for (var c : list) {
            if (c == null) return fail(FieldRuleStateEnum.INCOMPLETE_CONFIG, "条件配置不完整");
            if (RuleValueSourceEnum.CURRENT_RECORD.matches(c.valueSource())) {
                // 只在开启自动更新的数据联动上有效；形状不对一律失败（发布校验已拒绝，这里兜底不放大命中范围）。
                if (!currentRecord
                        || !"eq".equals(c.operator())
                        || c.value() != null
                        || c.formFieldId() != null
                        || FieldRules.RECORD_KEY.equals(c.fieldId()))
                    return fail(FieldRuleStateEnum.CONDITION_UNSUPPORTED, "「等于当前记录」的条件配置无效");
                if (currentRecordId == null || currentRecordId.isBlank()) awaitingRecord = true;
                continue;
            }
            // 为空 / 不为空不取任何值：既不等当前字段，也不接受夹带的值（发布校验已拒绝，这里兜底不放大命中范围）。
            if (FieldRuleMatrix.valueless(c.operator())) {
                if (!RuleValueSourceEnum.CONSTANT.matches(c.valueSource())
                        || c.value() != null
                        || c.formFieldId() != null)
                    return fail(FieldRuleStateEnum.CONDITION_UNSUPPORTED, "「为空」「不为空」不需要比较值，条件配置无效");
                continue;
            }
            if (!RuleValueSourceEnum.FORM_FIELD.matches(c.valueSource())) continue;
            if (c.formFieldId() == null || c.formFieldId().isBlank())
                return fail(FieldRuleStateEnum.INCOMPLETE_CONFIG, "条件缺少当前字段");
            if (!formFields.containsKey(c.formFieldId())
                    || candidates(values == null ? null : values.get(c.formFieldId())).isEmpty())
                pending.add(c.formFieldId());
        }
        if (!pending.isEmpty())
            return new ReferenceScope(
                    FieldRuleStateEnum.PENDING_ROW_VALUE.getCode(),
                    "这条规则要先知道当前表单的「"
                            + String.join(
                                    "」「",
                                    pending.stream()
                                            .map(id -> formFields.getOrDefault(id, id))
                                            .toList())
                            + "」，请先填写",
                    List.copyOf(pending),
                    null,
                    false,
                    null);
        // 当前记录还没有 ID（新建未保存）：没有任何来源行会指向它，待定且不查库。
        if (awaitingRecord)
            return new ReferenceScope(
                    FieldRuleStateEnum.PENDING_ROW_VALUE.getCode(),
                    "这条规则按当前记录匹配，保存后才能取值",
                    List.of(),
                    null,
                    false,
                    null);
        List<DynamicConditionDTO.Item> items = new ArrayList<>();
        Set<String> recordKeys = null;
        for (var c : list) {
            boolean formField = RuleValueSourceEnum.FORM_FIELD.matches(c.valueSource());
            boolean current = RuleValueSourceEnum.CURRENT_RECORD.matches(c.valueSource());
            if (!formField && !current && !RuleValueSourceEnum.CONSTANT.matches(c.valueSource()))
                return fail(FieldRuleStateEnum.CONDITION_UNSUPPORTED, "条件的取值来源无效");
            // 「等于当前记录」编译成来源对象那个引用字段上的 eq <当前记录 ID>，其余同固定值条件。
            Object raw =
                    formField ? values.get(c.formFieldId()) : current ? currentRecordId : c.value();
            if (FieldRules.RECORD_KEY.equals(c.fieldId())) {
                if (!"eq".equals(c.operator()))
                    return fail(FieldRuleStateEnum.CONDITION_UNSUPPORTED, "「按记录匹配」只能用「等于」");
                var ids = new LinkedHashSet<String>();
                candidates(raw).forEach(v -> ids.add(v.toString()));
                if (ids.isEmpty())
                    return fail(FieldRuleStateEnum.CONDITION_UNSUPPORTED, "「按记录匹配」缺少记录值");
                if (recordKeys == null) recordKeys = ids;
                else recordKeys.retainAll(ids);
                continue;
            }
            var field =
                    source.fields().stream()
                            .filter(f -> Objects.equals(f.id(), c.fieldId()))
                            .findFirst()
                            .orElse(null);
            var options =
                    field == null
                            ? null
                            : source.fieldOptions()
                                    .getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            if (field == null || MemberStateEnum.INACTIVE.matches(options.state()))
                return fail(
                        FieldRuleStateEnum.CONDITION_FIELD_MISSING,
                        "条件字段在来源对象「" + source.objectName() + "」中已不存在或已停用");
            var type = FieldTypeEnum.fromCode(field.type());
            if (NOT_FILTERABLE.contains(type) || Calculations.live(options))
                return fail(
                        FieldRuleStateEnum.CONDITION_FIELD_NOT_FILTERABLE,
                        "条件字段「" + field.name() + "」不支持筛选");
            if (!allowed.contains(field.id()))
                return fail(
                        FieldRuleStateEnum.SOURCE_NOT_READABLE,
                        "当前用户无权按来源字段「" + field.name() + "」查询");
            var relation = BusinessFields.relation(source, field.id());
            if (current && (relation == null || BusinessFields.multiple(relation)))
                return fail(
                        FieldRuleStateEnum.CONDITION_UNSUPPORTED,
                        "条件字段「" + field.name() + "」不是单选关联字段，不能用「等于当前记录」");
            var item =
                    item(c, field, options, raw, formField, relation, relativeDates, objectNames);
            if (item instanceof ReferenceScope failed) return failed;
            items.add((DynamicConditionDTO.Item) item);
        }
        DynamicConditionDTO dto = null;
        if (!items.isEmpty()) {
            dto = new DynamicConditionDTO();
            dto.setLogic(DynamicConditionDTO.Logic.AND);
            dto.setItems(items);
        }
        return new ReferenceScope(
                FieldRuleStateEnum.APPLIED.getCode(),
                null,
                List.of(),
                dto,
                recordKeys != null,
                recordKeys == null ? null : List.copyOf(recordKeys));
    }

    /** 单条条件：多值取并集只对 eq 成立；between 只接受固定的 [起, 止]；值须能按来源字段类型转换；为空 / 不为空不带值。 */
    private static Object item(
            FieldRules.Condition c,
            FieldDefinition field,
            DataCenter.FieldOptions options,
            Object raw,
            boolean formField,
            DataCenter.Relation relation,
            boolean relativeDates,
            Function<String, String> objectNames) {
        String operator = c.operator();
        if (operator == null || !OPERATORS.contains(operator))
            return fail(
                    FieldRuleStateEnum.CONDITION_UNSUPPORTED,
                    "条件「" + field.name() + "」的算子「" + operator + "」不受支持");
        // 为空 / 不为空对所有可筛字段成立（不可筛的类型在上一步已拒绝）；空的口径由 RecordConditions.appendRule 按列形态决定。
        if (FieldRuleMatrix.valueless(operator)) return condition(field, operator, null);
        // 相对日期：原样交给 RecordConditions.appendRule，按执行当天换算成边界；形状不对或不在引用筛选里一律失败，不跳过（跳过等于放大命中范围）。
        if (!formField && RelativeDates.isRelative(raw)) {
            if (!relativeDates)
                return fail(
                        FieldRuleStateEnum.CONDITION_UNSUPPORTED,
                        "条件「" + field.name() + "」用了相对日期，数据联动不支持相对日期");
            try {
                RelativeDates.check(field, operator, raw);
            } catch (ServiceException e) {
                return fail(
                        FieldRuleStateEnum.CONDITION_UNSUPPORTED,
                        "条件「" + field.name() + "」的相对日期无效：" + e.getMessage());
            }
            return condition(field, operator, raw);
        }
        Object value;
        if ("between".equals(operator)) {
            if (formField || !(raw instanceof List<?> pair) || pair.size() != 2)
                return fail(
                        FieldRuleStateEnum.CONDITION_UNSUPPORTED,
                        "条件「" + field.name() + "」用了「在范围内」，但值不是固定的 [起, 止] 两格");
            value = pair;
        } else if ("containsAny".equals(operator)) {
            value = candidates(raw);
        } else if (raw instanceof Collection<?>) {
            if (!"eq".equals(operator))
                return fail(
                        FieldRuleStateEnum.CURRENT_FIELD_MULTI_VALUE,
                        "条件「" + field.name() + "」的右侧是多个值，而算子是「" + operator + "」，说不清要比哪一个");
            operator = "in";
            value = candidates(raw);
        } else value = raw;
        if (value == null
                || value instanceof String s && s.isEmpty()
                || value instanceof Collection<?> list && (list.isEmpty() || list.size() > 100))
            return fail(
                    FieldRuleStateEnum.CONDITION_UNSUPPORTED,
                    "条件「" + field.name() + "」的值为空或超过 100 项");
        var type = FieldTypeEnum.fromCode(field.type());
        var parsed = RecordQueryOperatorEnum.fromCode(operator);
        if (!parsed.supportsType(type)
                || relation != null && !Set.of("eq", "neq", "in").contains(operator))
            return fail(
                    FieldRuleStateEnum.CONDITION_UNSUPPORTED,
                    "「" + field.name() + "」不能用「" + operator + "」");
        for (Object v : value instanceof Collection<?> list ? list : List.of(value))
            try {
                RecordConditionValues.value(field, options, v, parsed);
            } catch (ServiceException e) {
                // 值转换失败时整条规则失败，不能跳过这一条件（跳过等于放大命中范围）。
                // 引用字段的固定值转换不了：多半是早先在自由文本框里填了名称，说清楚该去哪里改，而不是「格式无效」。
                if (relation != null && !formField)
                    return fail(
                            FieldRuleStateEnum.CONDITION_UNSUPPORTED,
                            CONFIG_ERROR
                                    + "条件「"
                                    + field.name()
                                    + "」的固定值「"
                                    + v
                                    + "」不是「"
                                    + targetName(relation, field, objectNames)
                                    + "」里的记录，请到对象设计里重新选择");
                return fail(
                        FieldRuleStateEnum.CONDITION_UNSUPPORTED,
                        "条件「" + field.name() + "」的值无法按字段类型比较：" + e.getMessage());
            }
        return condition(
                field, operator, value instanceof Collection<?> list ? List.copyOf(list) : value);
    }

    private static String targetName(
            DataCenter.Relation relation,
            FieldDefinition field,
            Function<String, String> objectNames) {
        String name = null;
        if (objectNames != null)
            try {
                name = objectNames.apply(relation.targetObjectId());
            } catch (RuntimeException unavailable) {
                name = null;
            }
        return name == null || name.isBlank() ? field.name() : name;
    }

    private static DynamicConditionDTO.Item condition(
            FieldDefinition field, String operator, Object value) {
        var item = new DynamicConditionDTO.Item();
        item.setType("condition");
        item.setField(field.id());
        item.setOperator(operator);
        item.setValue(value);
        return item;
    }

    /** 当前字段取值：去掉 null 与空串并去重；标量视为单元素。结果为空即「没值」。 */
    public static List<Object> candidates(Object raw) {
        var result = new LinkedHashSet<Object>();
        if (raw instanceof Collection<?> list) {
            for (Object v : list)
                if (v != null && !(v instanceof String s && s.isEmpty())) result.add(v);
        } else if (raw != null && !(raw instanceof String s && s.isEmpty())) result.add(raw);
        return List.copyOf(result);
    }

    static ReferenceScope fail(FieldRuleStateEnum state, String message) {
        return new ReferenceScope(state.getCode(), message, List.of(), null, false, null);
    }
}
