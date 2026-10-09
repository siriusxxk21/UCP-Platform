package com.richuang.os.nocode.api;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.enums.*;

import java.math.BigDecimal;
import java.util.*;

/** 按字段身份描述记录范围；同一模型用于 SQL 筛选与写入后的范围检查。 */
public record DataScope(String logic, List<Condition> conditions, List<DataScope> groups) {
    public record Condition(String fieldId, String operator, Object value, String valueSource) {
        public Condition(String fieldId, String operator, Object value) {
            this(fieldId, operator, value, null);
        }
    }

    public DataScope {
        conditions = conditions == null ? List.of() : List.copyOf(conditions);
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    public static DataScope and(DataScope a, DataScope b) {
        if (a == null) return b;
        if (b == null || a.equals(b)) return a;
        return new DataScope("AND", List.of(), List.of(a, b));
    }

    /** 范围里（含嵌套组）有没有相对日期条件；结果会被存下来的入口（如持续维护）用它拒绝相对日期。 */
    public static boolean usesRelativeDate(DataScope scope) {
        return scope != null
                && (scope.conditions().stream().anyMatch(c -> RelativeDates.isRelative(c.value()))
                        || scope.groups().stream().anyMatch(DataScope::usesRelativeDate));
    }

    public void validate(DataCenter.Definition d, boolean dynamic) {
        validate(d, dynamic, 0, new int[] {0}, 4, 50);
    }

    /** 两份已校验授权取交集后保留各自层级和预算，不因增加组合根节点而拒绝合法授权。 */
    public void validateEffective(DataCenter.Definition d) {
        validate(d, true, 0, new int[] {0}, 9, 100);
    }

    private void validate(
            DataCenter.Definition d,
            boolean dynamic,
            int depth,
            int[] count,
            int maximumDepth,
            int maximumConditions) {
        if (depth > maximumDepth
                || !Set.of("AND", "OR").contains(Objects.toString(logic, ""))
                || conditions.isEmpty() && groups.isEmpty())
            throw invalid("记录范围需要 AND／OR 及有效条件，最多四层");
        for (Condition c : conditions) {
            if (++count[0] > maximumConditions) throw invalid("记录范围最多 50 个条件");
            var f = field(d, c.fieldId());
            var type = FieldTypeEnum.fromCode(f.type());
            var op = ScopeOperatorEnum.fromCode(c.operator());
            var source = ScopeValueSourceEnum.fromCode(c.valueSource());
            if (type.isComputed()
                    || Set.of(
                                    FieldTypeEnum.URL,
                                    FieldTypeEnum.IMAGE,
                                    FieldTypeEnum.ATTACHMENT,
                                    FieldTypeEnum.REGION,
                                    FieldTypeEnum.CASCADE,
                                    FieldTypeEnum.RICH_TEXT)
                            .contains(type)) throw invalid("该字段不支持记录范围：" + f.name());
            if (source != ScopeValueSourceEnum.CONSTANT) {
                if (!dynamic || c.value() != null) throw invalid("动态范围值只能使用服务端身份");
                if (!Set.of(
                                FieldTypeEnum.USER,
                                FieldTypeEnum.DEPARTMENT,
                                FieldTypeEnum.INTEGER,
                                FieldTypeEnum.SELECT,
                                FieldTypeEnum.MULTI_SELECT,
                                FieldTypeEnum.REFERENCE)
                        .contains(type)) throw invalid("身份范围应绑定人员或部门身份字段");
                if (source == ScopeValueSourceEnum.CURRENT_DEPARTMENT_TREE
                        && op != ScopeOperatorEnum.IN
                        && op != ScopeOperatorEnum.CONTAINS_ANY) throw invalid("本部门及下级使用属于任意／包含任意");
            }
            boolean array = type == FieldTypeEnum.MULTI_SELECT;
            if ((op == ScopeOperatorEnum.CONTAINS_ANY || op == ScopeOperatorEnum.CONTAINS_ALL)
                    && !array) throw invalid("包含任意／全部只适用于多选字段");
            if (array
                    && !Set.of(
                                    ScopeOperatorEnum.EQ,
                                    ScopeOperatorEnum.NEQ,
                                    ScopeOperatorEnum.CONTAINS_ANY,
                                    ScopeOperatorEnum.CONTAINS_ALL,
                                    ScopeOperatorEnum.IS_NULL,
                                    ScopeOperatorEnum.NOT_NULL)
                            .contains(op)) throw invalid("多选字段不支持此范围算子");
            if (Set.of(
                                    ScopeOperatorEnum.GT,
                                    ScopeOperatorEnum.GTE,
                                    ScopeOperatorEnum.LT,
                                    ScopeOperatorEnum.LTE)
                            .contains(op)
                    && !type.isNumeric()
                    && !Set.of(FieldTypeEnum.DATE, FieldTypeEnum.DATETIME, FieldTypeEnum.TIME)
                            .contains(type)) throw invalid("比较范围需要数值或日期字段");
            // 相对日期（今天、本月、过去 N 天……）：只认日期字段与可组合的比较方式，执行时按当天换算，不存换算结果。
            if (source == ScopeValueSourceEnum.CONSTANT && RelativeDates.isRelative(c.value()))
                RelativeDates.check(f, op.getCode(), c.value());
            else if (source == ScopeValueSourceEnum.CONSTANT
                    && op != ScopeOperatorEnum.IS_NULL
                    && op != ScopeOperatorEnum.NOT_NULL) {
                boolean list = array || op == ScopeOperatorEnum.IN;
                if (list) {
                    if (!(c.value() instanceof List<?> entries) || entries.size() > 100)
                        throw invalid("范围应为最多 100 个值的数组");
                    for (Object value : (List<?>) c.value()) scalar(d, f, value);
                } else scalar(d, f, c.value());
            }
        }
        for (var group : groups)
            group.validate(d, dynamic, depth + 1, count, maximumDepth, maximumConditions);
    }

    public static FieldDefinition field(DataCenter.Definition d, String id) {
        return d.fields().stream()
                .filter(
                        f ->
                                f.id().equals(id)
                                        && !MemberStateEnum.INACTIVE.matches(
                                                d.fieldOptions()
                                                        .getOrDefault(
                                                                id,
                                                                DataCenter.FieldOptions.defaults())
                                                        .state()))
                .map(
                        field ->
                                OrderedCalculations.queryField(
                                        field, d.fieldOptions().get(field.id())))
                .findFirst()
                .orElseThrow(() -> invalid("范围字段不存在或已停用"));
    }

    public static Object scalar(DataCenter.Definition d, FieldDefinition f, Object value) {
        if (value == null || value instanceof Collection<?> || value instanceof Map<?, ?>)
            throw invalid("范围值格式无效：" + f.name());
        return RecordConditionValues.value(
                f,
                d.fieldOptions().getOrDefault(f.id(), DataCenter.FieldOptions.defaults()),
                value,
                RecordQueryOperatorEnum.EQ);
    }

    public static Object operand(Condition c, Map<String, Object> context) {
        var source = ScopeValueSourceEnum.fromCode(c.valueSource());
        Object value =
                source == ScopeValueSourceEnum.CONSTANT ? c.value() : context.get(source.getCode());
        if (source != ScopeValueSourceEnum.CONSTANT
                && value != null
                && !(value instanceof List<?>)
                && Set.of("in", "containsAny", "containsAll").contains(c.operator()))
            return List.of(value);
        return value;
    }

    public boolean matches(
            DataCenter.Definition d, Map<String, Object> values, Map<String, Object> context) {
        var results = new ArrayList<Boolean>();
        for (var c : conditions)
            results.add(matches(d, c, values.get(c.fieldId()), operand(c, context)));
        for (var group : groups) results.add(group.matches(d, values, context));
        return "OR".equals(logic)
                ? results.stream().anyMatch(Boolean::booleanValue)
                : results.stream().allMatch(Boolean::booleanValue);
    }

    private static boolean matches(
            DataCenter.Definition d, Condition c, Object actual, Object wanted) {
        var op = ScopeOperatorEnum.fromCode(c.operator());
        if (op == ScopeOperatorEnum.IS_NULL) return actual == null;
        if (op == ScopeOperatorEnum.NOT_NULL) return actual != null;
        if (wanted == null || actual == null) return false;
        var f = field(d, c.fieldId());
        if (RelativeDates.isRelative(wanted))
            return RelativeDates.matches(
                    f,
                    d.fieldOptions().getOrDefault(f.id(), DataCenter.FieldOptions.defaults()),
                    c.operator(),
                    wanted,
                    scalar(d, f, actual),
                    RelativeDates.today());
        if (actual instanceof List<?> list) {
            var left =
                    list.stream()
                            .map(Object::toString)
                            .collect(java.util.stream.Collectors.toSet());
            var right =
                    (wanted instanceof List<?> v ? v : List.of(wanted))
                            .stream()
                                    .map(Object::toString)
                                    .collect(java.util.stream.Collectors.toSet());
            return switch (op) {
                case CONTAINS_ANY -> right.stream().anyMatch(left::contains);
                case CONTAINS_ALL -> left.containsAll(right);
                case EQ -> left.equals(right);
                case NEQ -> !left.equals(right);
                default -> false;
            };
        }
        if (op == ScopeOperatorEnum.IN)
            return wanted instanceof List<?> list
                    && list.stream().anyMatch(v -> compare(d, f, actual, v) == 0);
        int comparison = compare(d, f, actual, wanted);
        return switch (op) {
            case EQ -> comparison == 0;
            case NEQ -> comparison != 0;
            case GT -> comparison > 0;
            case GTE -> comparison >= 0;
            case LT -> comparison < 0;
            case LTE -> comparison <= 0;
            default -> false;
        };
    }

    private static int compare(DataCenter.Definition d, FieldDefinition f, Object a, Object b) {
        a = scalar(d, f, a);
        b = scalar(d, f, b);
        var type = FieldTypeEnum.fromCode(f.type());
        if (type.isNumeric()
                || Set.of(
                                FieldTypeEnum.USER,
                                FieldTypeEnum.DEPARTMENT,
                                FieldTypeEnum.ORGANIZATION,
                                FieldTypeEnum.POST,
                                FieldTypeEnum.USER_GROUP,
                                FieldTypeEnum.REFERENCE)
                        .contains(type))
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
        if (a instanceof java.time.OffsetDateTime x && b instanceof java.time.OffsetDateTime y)
            return x.toInstant().compareTo(y.toInstant());
        if (a instanceof java.time.LocalDateTime x && b instanceof java.time.LocalDateTime y)
            return x.compareTo(y);
        if (a instanceof java.time.LocalDate x && b instanceof java.time.LocalDate y)
            return x.compareTo(y);
        if (a instanceof java.time.LocalTime x && b instanceof java.time.LocalTime y)
            return x.compareTo(y);
        return a.toString().compareTo(b.toString());
    }
}
