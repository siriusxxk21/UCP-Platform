package com.lingan.ucp.nocode.metadata.service.form;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DocumentPolicy.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaEvaluator;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 整单规则的结构检查与纯数据求值；设计、试算和正式保存共用同一语义。 */
public final class DocumentPolicies {
    public static final int MAX_RULES = 100;
    public static final int MAX_PROBLEMS = 100;
    private static final int MAX_STEPS = 2_000_000;

    private DocumentPolicies() {}

    private enum ValueType {
        NUMBER,
        TEXT,
        BOOLEAN,
        NULL
    }

    public record InputRow(String id, String clientRowKey, Map<String, Object> values) {}

    public record Input(Map<String, Object> values, Map<String, List<InputRow>> details) {}

    public static DocumentPolicy policy(DataCenter.Definition d) {
        return d.settings() == null ? null : d.settings().documentPolicy();
    }

    public static DocumentPolicy remap(DocumentPolicy p, Map<String, String> ids) {
        if (p == null) return null;
        Function<String, String> id = v -> v == null ? null : ids.getOrDefault(v, v);
        var lifecycle = p.lifecycle();
        return new DocumentPolicy(
                p.rules().stream()
                        .map(
                                r ->
                                        new Rule(
                                                r.id(),
                                                r.name(),
                                                r.scope(),
                                                id.apply(r.detailId()),
                                                remap(r.when(), ids),
                                                remap(r.assertion(), ids),
                                                id.apply(r.fieldId()),
                                                r.message()))
                        .toList(),
                lifecycle == null
                        ? null
                        : new Lifecycle(
                                id.apply(lifecycle.fieldId()),
                                lifecycle.initialState(),
                                lifecycle.states().stream()
                                        .map(
                                                s ->
                                                        new State(
                                                                s.code(),
                                                                s.name(),
                                                                s.lockedFields().stream()
                                                                        .map(id)
                                                                        .toList(),
                                                                s.lockedDetails().stream()
                                                                        .map(id)
                                                                        .toList(),
                                                                s.allowDelete()))
                                        .toList(),
                                lifecycle.actions()),
                BusinessHandlingPolicies.remap(p.handling(), ids));
    }

    public static Expression remap(Expression e, Map<String, String> ids) {
        if (e == null) return null;
        return new Expression(
                e.op(),
                e.fieldId() == null ? null : ids.getOrDefault(e.fieldId(), e.fieldId()),
                e.detailId() == null ? null : ids.getOrDefault(e.detailId(), e.detailId()),
                e.value(),
                e.args().stream().map(a -> remap(a, ids)).toList());
    }

    public static void validate(DataCenter.Definition d) {
        var policy = policy(d);
        if (policy == null) return;
        BusinessHandlingPolicies.validate(d);
        if (policy.rules().size() > MAX_RULES) throw invalid("整单规则最多 100 条");
        var fields = fields(d.fields(), d.fieldOptions());
        Map<String, Map<String, FieldDefinition>> details = new HashMap<>();
        d.details().stream()
                .filter(t -> MemberStateEnum.ACTIVE.matches(t.state()))
                .forEach(t -> details.put(t.id(), fields(t.fields(), t.fieldOptions())));
        Set<String> ids = new HashSet<>();
        for (var r : policy.rules()) {
            if (r == null || !code(r.id()) || !ids.add(r.id())) throw invalid("规则标识无效或重复");
            text(r.name(), "规则名称", 128);
            text(r.message(), "规则提示", 500);
            var scope = DocumentRuleScopeEnum.fromCode(r.scope());
            boolean row =
                    scope == DocumentRuleScopeEnum.ROW
                            || scope == DocumentRuleScopeEnum.FIELD && r.detailId() != null;
            if ((row || scope == DocumentRuleScopeEnum.DETAIL)
                    && !details.containsKey(r.detailId())) throw invalid("规则必须选择有效的内部明细");
            if (scope == DocumentRuleScopeEnum.DOCUMENT && r.detailId() != null)
                throw invalid("整单规则不应绑定单个明细组");
            var local = new HashMap<>(fields);
            if (row) local.putAll(details.get(r.detailId()));
            if (r.fieldId() != null && !local.containsKey(r.fieldId()))
                throw invalid("规则定位字段不存在或不属于当前范围");
            if (scope == DocumentRuleScopeEnum.FIELD && r.fieldId() == null)
                throw invalid("字段规则必须选择定位字段");
            if (r.when() != null
                    && type(r.when(), local, details, 0, new int[1]) != ValueType.BOOLEAN)
                throw invalid("规则生效条件必须为布尔表达式");
            if (type(r.assertion(), local, details, 0, new int[1]) != ValueType.BOOLEAN)
                throw invalid("规则校验条件必须为布尔表达式");
        }
        var lifecycle = policy.lifecycle();
        if (lifecycle == null) return;
        var field = fields.get(lifecycle.fieldId());
        if (field == null || !FieldTypeEnum.SELECT.matches(field.type()))
            throw invalid("受控状态必须使用主表单选字段");
        var option = d.fieldOptions().getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
        var choices =
                option.options().stream().map(DataCenter.Option::code).collect(Collectors.toSet());
        if (lifecycle.states().isEmpty()
                || lifecycle.states().size() > 30
                || lifecycle.actions().size() > 50) throw invalid("受控状态为 1–30 个，动作最多 50 个");
        Set<String> states = new HashSet<>();
        for (var s : lifecycle.states()) {
            if (s == null || !choices.contains(s.code()) || !states.add(s.code()))
                throw invalid("状态编码不存在或重复");
            text(s.name(), "状态名称", 128);
            if (!fields.keySet().containsAll(s.lockedFields())
                    || !details.keySet().containsAll(s.lockedDetails()))
                throw invalid("状态保护仍引用不存在的字段或明细");
        }
        if (!states.contains(lifecycle.initialState()) || !states.equals(choices))
            throw invalid("状态配置必须覆盖单选项并包含初始状态");
        Set<String> actions = new HashSet<>();
        for (var a : lifecycle.actions()) {
            if (a == null || !code(a.code()) || !actions.add(a.code()))
                throw invalid("状态动作编码无效或重复");
            text(a.name(), "状态动作名称", 128);
            if (a.fromStates().isEmpty()
                    || !states.containsAll(a.fromStates())
                    || !states.contains(a.toState())) throw invalid("状态动作必须指定有效的来源与目标状态");
            if (!Set.of(
                            ApplicationActionEnum.CREATE.getCode(),
                            ApplicationActionEnum.UPDATE.getCode())
                    .contains(a.permission())) throw invalid("状态动作权限须为新建或修改操作");
        }
    }

    private static Map<String, FieldDefinition> fields(
            List<FieldDefinition> fields, Map<String, DataCenter.FieldOptions> options) {
        return fields.stream()
                .filter(
                        f ->
                                !MemberStateEnum.INACTIVE.matches(
                                        options.getOrDefault(
                                                        f.id(), DataCenter.FieldOptions.defaults())
                                                .state()))
                .collect(
                        Collectors.toMap(
                                FieldDefinition::id,
                                f -> {
                                    var o =
                                            options.getOrDefault(
                                                    f.id(), DataCenter.FieldOptions.defaults());
                                    if (FieldTypeEnum.fromCode(f.type()).isComputed()
                                            && o.resultType() != null)
                                        return new FieldDefinition(
                                                f.key(),
                                                f.id(),
                                                f.code(),
                                                f.name(),
                                                o.resultType(),
                                                f.length(),
                                                f.precision(),
                                                f.scale(),
                                                f.required(),
                                                f.unique(),
                                                f.sort());
                                    return f;
                                }));
    }

    private static boolean code(String value) {
        return value != null && value.matches("[A-Za-z][A-Za-z0-9_-]{0,79}");
    }

    private static void text(String value, String label, int limit) {
        if (value == null || value.isBlank() || value.length() > limit)
            throw invalid(label + "不能为空或过长");
    }

    private static ValueType type(
            Expression e,
            Map<String, FieldDefinition> fields,
            Map<String, Map<String, FieldDefinition>> details,
            int depth,
            int[] nodes) {
        if (e == null || depth > 16 || ++nodes[0] > 256) throw invalid("规则表达式为空或过于复杂");
        var op = DocumentOperatorEnum.fromCode(e.op());
        int arity =
                switch (op) {
                    case NOT, EMPTY -> 1;
                    case EQ, NE, GT, GE, LT, LE -> 2;
                    case AND, OR -> -1;
                    default -> 0;
                };
        if (arity >= 0 && e.args().size() != arity
                || arity == -1 && (e.args().size() < 2 || e.args().size() > 20))
            throw invalid("规则算子参数数量无效");
        if (op != DocumentOperatorEnum.VALUE && e.value() != null) throw invalid("非字面量算子不能携带固定值");
        if (op == DocumentOperatorEnum.VALUE) {
            if (e.value() == null) return ValueType.NULL;
            if (e.value() instanceof Boolean) return ValueType.BOOLEAN;
            if (e.value() instanceof Number) return ValueType.NUMBER;
            if (e.value() instanceof String s && s.length() <= 500) return ValueType.TEXT;
            throw invalid("规则固定值须为短文本、数字、布尔或空值");
        }
        if (op == DocumentOperatorEnum.FIELD) return fieldType(fields.get(e.fieldId()));
        if (Set.of(
                        DocumentOperatorEnum.SUM,
                        DocumentOperatorEnum.COUNT,
                        DocumentOperatorEnum.UNIQUE)
                .contains(op)) {
            var group = details.get(e.detailId());
            if (group == null) throw invalid("聚合规则来源明细不存在");
            if (op == DocumentOperatorEnum.COUNT) return ValueType.NUMBER;
            var value = fieldType(group.get(e.fieldId()));
            if (op == DocumentOperatorEnum.SUM && value != ValueType.NUMBER)
                throw invalid("求和仅支持数值字段");
            return op == DocumentOperatorEnum.UNIQUE ? ValueType.BOOLEAN : ValueType.NUMBER;
        }
        var types = e.args().stream().map(a -> type(a, fields, details, depth + 1, nodes)).toList();
        if (Set.of(DocumentOperatorEnum.AND, DocumentOperatorEnum.OR, DocumentOperatorEnum.NOT)
                        .contains(op)
                && types.stream().anyMatch(t -> t != ValueType.BOOLEAN))
            throw invalid("逻辑条件须为布尔类型");
        if (Set.of(
                                DocumentOperatorEnum.EQ,
                                DocumentOperatorEnum.NE,
                                DocumentOperatorEnum.GT,
                                DocumentOperatorEnum.GE,
                                DocumentOperatorEnum.LT,
                                DocumentOperatorEnum.LE)
                        .contains(op)
                && types.get(0) != ValueType.NULL
                && types.get(1) != ValueType.NULL
                && types.get(0) != types.get(1)) throw invalid("比较条件的两侧类型不一致");
        return ValueType.BOOLEAN;
    }

    private static ValueType fieldType(FieldDefinition f) {
        if (f == null) throw invalid("规则引用字段不存在、已停用或不属于当前范围");
        var t = FieldTypeEnum.fromCode(f.type());
        if (t.isNumeric() || t == FieldTypeEnum.FORMULA || t == FieldTypeEnum.SUMMARY)
            return ValueType.NUMBER;
        if (t == FieldTypeEnum.BOOLEAN) return ValueType.BOOLEAN;
        if (Set.of(
                        FieldTypeEnum.TEXT,
                        FieldTypeEnum.TEXTAREA,
                        FieldTypeEnum.SELECT,
                        FieldTypeEnum.DATE,
                        FieldTypeEnum.DATETIME,
                        FieldTypeEnum.UUID,
                        FieldTypeEnum.REFERENCE,
                        FieldTypeEnum.AUTO_NUMBER)
                .contains(t)) return ValueType.TEXT;
        throw invalid("规则暂不支持此字段类型：" + f.name());
    }

    public static List<Problem> evaluate(DocumentPolicy p, Input input) {
        if (p == null) return List.of();
        var errors = new ArrayList<Problem>();
        int[] steps = new int[1];
        for (var rule : p.rules()) {
            var scope = DocumentRuleScopeEnum.fromCode(rule.scope());
            List<InputRow> rows =
                    scope == DocumentRuleScopeEnum.ROW
                                    || scope == DocumentRuleScopeEnum.FIELD
                                            && rule.detailId() != null
                            ? input.details().getOrDefault(rule.detailId(), List.of())
                            : Collections.singletonList(null);
            for (var row : rows) {
                Map<String, Object> values = new HashMap<>(input.values());
                if (row != null) values.putAll(row.values());
                if (rule.when() != null
                        && !Boolean.TRUE.equals(value(rule.when(), values, input, steps))) continue;
                if (!Boolean.TRUE.equals(value(rule.assertion(), values, input, steps))) {
                    errors.add(
                            new Problem(
                                    rule.id(),
                                    rule.scope(),
                                    rule.detailId(),
                                    row == null ? null : row.clientRowKey(),
                                    row == null ? null : row.id(),
                                    rule.fieldId(),
                                    rule.message()));
                    if (errors.size() == MAX_PROBLEMS) return errors;
                }
            }
        }
        return List.copyOf(errors);
    }

    /** 表单条件复用整单表达式语义，但不允许隐式跨明细取数。 */
    public static void validateCondition(
            Expression expression,
            List<FieldDefinition> fields,
            Map<String, DataCenter.FieldOptions> options) {
        if (expression != null
                && type(expression, fields(fields, options), Map.of(), 0, new int[1])
                        != ValueType.BOOLEAN) throw invalid("表单条件必须为布尔表达式");
    }

    public static boolean matches(Expression expression, Map<String, Object> values) {
        return expression != null
                && Boolean.TRUE.equals(
                        value(expression, values, new Input(values, Map.of()), new int[1]));
    }

    private static Object value(
            Expression e, Map<String, Object> values, Input input, int[] steps) {
        if (++steps[0] > MAX_STEPS) throw invalid("整单规则超过计算预算，请简化规则或减少明细");
        var op = DocumentOperatorEnum.fromCode(e.op());
        return switch (op) {
            case VALUE -> e.value();
            case FIELD -> values.get(e.fieldId());
            case COUNT ->
                    BigDecimal.valueOf(
                            input.details().getOrDefault(e.detailId(), List.of()).size());
            case SUM -> {
                var result = BigDecimal.ZERO;
                for (var row : input.details().getOrDefault(e.detailId(), List.of())) {
                    if (++steps[0] > MAX_STEPS) throw invalid("整单规则超过计算预算");
                    Object v = row.values().get(e.fieldId());
                    if (v != null) result = result.add(FormulaEvaluator.number(v));
                }
                yield result;
            }
            case UNIQUE -> {
                Set<Object> seen = new HashSet<>();
                boolean unique = true;
                for (var row : input.details().getOrDefault(e.detailId(), List.of())) {
                    if (++steps[0] > MAX_STEPS) throw invalid("整单规则超过计算预算");
                    Object v = row.values().get(e.fieldId());
                    if (v instanceof Number) v = FormulaEvaluator.number(v).stripTrailingZeros();
                    if (v != null && !seen.add(v)) {
                        unique = false;
                        break;
                    }
                }
                yield unique;
            }
            case AND -> {
                boolean result = true;
                for (var a : e.args())
                    if (!Boolean.TRUE.equals(value(a, values, input, steps))) {
                        result = false;
                        break;
                    }
                yield result;
            }
            case OR -> {
                boolean result = false;
                for (var a : e.args())
                    if (Boolean.TRUE.equals(value(a, values, input, steps))) {
                        result = true;
                        break;
                    }
                yield result;
            }
            case NOT -> !Boolean.TRUE.equals(value(e.args().getFirst(), values, input, steps));
            case EMPTY -> {
                Object v = value(e.args().getFirst(), values, input, steps);
                yield v == null
                        || v instanceof String s && s.isBlank()
                        || v instanceof Collection<?> c && c.isEmpty();
            }
            default -> {
                Object a = value(e.args().get(0), values, input, steps),
                        b = value(e.args().get(1), values, input, steps);
                if (a == null || b == null)
                    yield op == DocumentOperatorEnum.EQ
                            ? a == b
                            : op == DocumentOperatorEnum.NE && a != b;
                int compared =
                        a instanceof Number || b instanceof Number
                                ? FormulaEvaluator.number(a).compareTo(FormulaEvaluator.number(b))
                                : a.toString().compareTo(b.toString());
                yield switch (op) {
                    case EQ -> compared == 0;
                    case NE -> compared != 0;
                    case GT -> compared > 0;
                    case GE -> compared >= 0;
                    case LT -> compared < 0;
                    case LE -> compared <= 0;
                    default -> throw invalid("规则算子不支持");
                };
            }
        };
    }
}
