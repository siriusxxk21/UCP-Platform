package com.lingan.ucp.nocode.metadata.service.formula;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/** 保存与运行复用计算依赖检查；固定对象版本构成有向无环图，禁止隐式选取多行结果。 */
public final class Calculations {
    public static final String PREVIOUS_PREFIX = "__previous_";

    private Calculations() {}

    public static boolean live(DataCenter.FieldOptions options) {
        return options != null
                && options.calculation() != null
                && CalculationUpdateEnum.LIVE.matches(options.calculation().updateMode());
    }

    /** 只有有序类的 ON_SAVE 表示同组联动，其他 ON_SAVE 继续保留本记录快照语义。 */
    public static boolean ordered(CalculationOptions calculation) {
        return calculation != null
                && (CalculationModeEnum.RUNNING_TOTAL.matches(calculation.mode())
                        || CalculationModeEnum.SEQUENCE.matches(calculation.mode()));
    }

    public static boolean orderedStored(DataCenter.FieldOptions options) {
        return options != null
                && ordered(options.calculation())
                && CalculationUpdateEnum.ON_SAVE.matches(options.calculation().updateMode());
    }

    /** 模式之外必须逐项相等，不放开其他计算类型的存储行为。 */
    public static boolean sameOrderedRule(CalculationOptions before, CalculationOptions after) {
        if (!ordered(before) || !ordered(after)) return false;
        return normalizedOrdered(before).equals(normalizedOrdered(after));
    }

    public static CalculationOptions normalizedOrdered(CalculationOptions value) {
        return new CalculationOptions(
                value.mode(),
                CalculationUpdateEnum.LIVE.getCode(),
                value.targetObjectId(),
                value.relationId(),
                value.targetField(),
                value.aggregate(),
                value.logic(),
                value.conditions(),
                value.excludeCurrent(),
                value.groupFields(),
                value.runningTotal(),
                value.sequence());
    }

    public static FieldDefinition field(DataCenter.Definition d, String code) {
        return d.fields().stream()
                .filter(
                        f ->
                                f.code().equals(code)
                                        && MemberStateEnum.ACTIVE.matches(
                                                d.fieldOptions()
                                                        .getOrDefault(
                                                                f.id(),
                                                                DataCenter.FieldOptions.defaults())
                                                        .state()))
                .findFirst()
                .orElseThrow(() -> invalid("计算引用字段不存在或已停用：" + code));
    }

    public static DataCenter.Relation relation(DataCenter.Definition d, CalculationOptions c) {
        return d.relations().stream()
                .filter(r -> r.id().equals(c.relationId()))
                .findFirst()
                .orElseThrow(() -> invalid("计算关联关系不存在"));
    }

    public static String target(DataCenter.Definition d, CalculationOptions c) {
        return CalculationModeEnum.RELATION.matches(c.mode())
                ? relation(d, c).targetObjectId()
                : c.targetObjectId() == null || c.targetObjectId().isBlank()
                        ? d.objectId()
                        : c.targetObjectId();
    }

    public static boolean tableWide(CalculationOptions c) {
        return CalculationModeEnum.STATISTICS.matches(c.mode())
                || CalculationModeEnum.RUNNING_TOTAL.matches(c.mode());
    }

    public static boolean sequence(CalculationOptions c) {
        return CalculationModeEnum.SEQUENCE.matches(c.mode());
    }

    /** 仅允许基础字段和确定的本行公式，跨行公式及自身结果不能成为递归状态。 */
    public static Map<String, String> sequenceNames(
            DataCenter.Definition d, FieldDefinition output) {
        Map<String, String> names = new HashMap<>();
        for (FieldDefinition field : d.fields()) {
            if (field.id().equals(output.id()) || !deterministicLocal(d, field, new HashSet<>()))
                continue;
            names.put(field.code(), field.id());
            CalculationOptions config = d.fieldOptions().get(output.id()).calculation();
            if (config == null || config.sequence() == null || !cumulative(config))
                names.put(PREVIOUS_PREFIX + field.code(), "previous:" + field.id());
        }
        return names;
    }

    public static boolean cumulative(CalculationOptions c) {
        return sequence(c)
                && c.sequence() != null
                && SequenceOperationEnum.fromCode(c.sequence().operation())
                        == SequenceOperationEnum.CUMULATIVE;
    }

    /** 期初沿用金额精度边界，缺省为零；试算与正式运行使用同一校验。 */
    public static BigDecimal sequenceInitial(CalculationOptions.Sequence rule) {
        try {
            BigDecimal initial =
                    new BigDecimal(rule.initialValue() == null ? "0" : rule.initialValue());
            if (initial.scale() > 10 || initial.precision() - initial.scale() > 28)
                throw invalid("期初值超过小数精度");
            return initial;
        } catch (NumberFormatException error) {
            throw invalid("固定期初值须为有效数字");
        }
    }

    private static boolean deterministicLocal(
            DataCenter.Definition d, FieldDefinition field, Set<String> path) {
        if (!FieldTypeEnum.fromCode(field.type()).isComputed()) return true;
        if (!FieldTypeEnum.FORMULA.matches(field.type())
                || !path.add(field.id())
                || path.size() > 16) return false;
        DataCenter.FieldOptions options =
                d.fieldOptions().getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
        if (options.calculation() != null
                && !CalculationModeEnum.LOCAL.matches(options.calculation().mode())) return false;
        Map<String, String> names = new HashMap<>();
        d.fields().forEach(item -> names.put(item.code(), item.id()));
        for (String code : FieldExpressions.parse(options.expression(), names).references())
            if (!deterministicLocal(d, field(d, code), new HashSet<>(path))) return false;
        return true;
    }

    private static void sequenceDependencies(
            DataCenter.Definition d, FieldDefinition field, Set<String> result) {
        if (!result.add(field.id()) || !FieldTypeEnum.FORMULA.matches(field.type())) return;
        Map<String, String> names = new HashMap<>();
        d.fields().forEach(item -> names.put(item.code(), item.id()));
        for (String code :
                FieldExpressions.parse(d.fieldOptions().get(field.id()).expression(), names)
                        .references()) sequenceDependencies(d, field(d, code), result);
    }

    public static Set<String> sequenceSourceFields(
            DataCenter.Definition d, FieldDefinition output, CalculationOptions c) {
        Set<String> result = new HashSet<>();
        for (String code : c.groupFields()) result.add(field(d, code).id());
        CalculationOptions.Sequence rule = Objects.requireNonNull(c.sequence());
        result.add(field(d, rule.orderField()).id());
        if (rule.tieBreakerField() != null) result.add(field(d, rule.tieBreakerField()).id());
        for (String name :
                FieldExpressions.parse(
                                d.fieldOptions().get(output.id()).expression(),
                                sequenceNames(d, output))
                        .references()) {
            String value = sequenceNames(d, output).get(name);
            String id = value.substring(value.indexOf(':') + 1);
            sequenceDependencies(
                    d,
                    d.fields().stream()
                            .filter(field -> field.id().equals(id))
                            .findFirst()
                            .orElseThrow(),
                    result);
        }
        return result;
    }

    public static Set<String> sourceFields(DataCenter.Definition target, CalculationOptions c) {
        Set<String> result = new HashSet<>();
        if (sequence(c)) return result;
        if (!CalculationAggregateEnum.COUNT.matches(c.aggregate()))
            result.add(field(target, c.targetField()).id());
        else result.add(target.titleFieldId());
        for (CalculationOptions.Match m : c.conditions()) {
            result.add(field(target, m.targetField()).id());
            if (tableWide(c) && m.localField() != null && !m.localField().isBlank())
                result.add(field(target, m.localField()).id());
        }
        for (String code : c.groupFields()) result.add(field(target, code).id());
        CalculationOptions.RunningTotal running = c.runningTotal();
        if (running != null) {
            result.add(field(target, running.orderField()).id());
            for (String code :
                    Arrays.asList(
                            running.tieBreakerField(),
                            running.subtractField(),
                            running.initialField()))
                if (code != null) result.add(field(target, code).id());
        }
        return result;
    }

    public static void validate(
            DataCenter.Definition root, Function<String, DataCenter.Definition> definitions) {
        Set<String> done = new HashSet<>();
        for (FieldDefinition field : root.fields())
            if (root.fieldOptions()
                            .getOrDefault(field.id(), DataCenter.FieldOptions.defaults())
                            .calculation()
                    != null) visit(root, field, definitions, new LinkedHashSet<>(), done);
    }

    private static void visit(
            DataCenter.Definition d,
            FieldDefinition f,
            Function<String, DataCenter.Definition> definitions,
            Set<String> path,
            Set<String> done) {
        String key = d.objectId() + ":" + f.id();
        if (!path.add(key)) throw invalid("计算字段存在循环依赖：" + f.name());
        if (path.size() > 16) throw invalid("计算依赖不能超过 16 层");
        if (done.contains(key)) {
            path.remove(key);
            return;
        }
        DataCenter.FieldOptions o =
                d.fieldOptions().getOrDefault(f.id(), DataCenter.FieldOptions.defaults());
        CalculationOptions c = o.calculation();
        if (c == null) {
            path.remove(key);
            return;
        }
        if (!FieldTypeEnum.FORMULA.matches(f.type())
                || Boolean.TRUE.equals(f.required())
                || Boolean.TRUE.equals(f.unique())
                || o.defaultValue() != null) throw invalid("关联计算是只读字段，不能配置必填、唯一或默认值");
        CalculationModeEnum mode = CalculationModeEnum.fromCode(c.mode());
        CalculationUpdateEnum.fromCode(c.updateMode());
        if (tableWide(c)) validateTableWide(d, o, c);
        else if (sequence(c)) validateSequence(d, f, o, c);
        else if (!sequence(c) && (!c.groupFields().isEmpty() || c.runningTotal() != null))
            throw invalid("分组与累计配置仅适用于全表统计和顺序累计");
        if (mode == CalculationModeEnum.LOCAL) {
            Map<String, String> names = new HashMap<>();
            d.fields().forEach(v -> names.put(v.code(), v.id()));
            // 日期函数的类型检查；保存时落库的公式不能用 TODAY() / NOW()。
            FormulaDates.checkField(
                    f.name(),
                    FieldExpressions.parse(o.expression(), names).expression(),
                    FormulaDates.calculated(d, CalculationUpdateEnum.LIVE.matches(c.updateMode())));
            for (String code : FieldExpressions.parse(o.expression(), names).references()) {
                FieldDefinition dependency = field(d, code);
                classification(
                        o,
                        d.fieldOptions()
                                .getOrDefault(dependency.id(), DataCenter.FieldOptions.defaults()));
                visit(d, dependency, definitions, path, done);
            }
        } else if (sequence(c)) {
            for (String sourceId : sequenceSourceFields(d, f, c)) {
                FieldDefinition dependency =
                        d.fields().stream()
                                .filter(item -> item.id().equals(sourceId))
                                .findFirst()
                                .orElseThrow();
                visit(d, dependency, definitions, path, done);
            }
        } else {
            if (c.conditions().size() > 20 || !Set.of("AND", "OR").contains(c.logic()))
                throw invalid("计算条件最多 20 条，条件组合须为 AND 或 OR");
            DataCenter.Definition target = definitions.apply(target(d, c));
            CalculationAggregateEnum aggregation = CalculationAggregateEnum.fromCode(c.aggregate());
            if (aggregation == CalculationAggregateEnum.COUNT
                    && !FieldTypeEnum.INTEGER.matches(o.resultType())) throw invalid("计数结果类型须为整数");
            if (mode == CalculationModeEnum.RELATION
                    && !RelationTypeEnum.MANY_TO_MANY.matches(relation(d, c).kind())) {
                FieldDefinition source =
                        d.fields().stream()
                                .filter(v -> v.id().equals(relation(d, c).fieldId()))
                                .findFirst()
                                .orElseThrow();
                classification(
                        o,
                        d.fieldOptions()
                                .getOrDefault(source.id(), DataCenter.FieldOptions.defaults()));
            }
            if (mode == CalculationModeEnum.LOOKUP && c.conditions().isEmpty())
                throw invalid("条件计算至少配置一个匹配条件，避免无意汇总整表");
            for (CalculationOptions.Match m : c.conditions()) {
                FieldDefinition destination = field(target, m.targetField());
                Object operand = m.value();
                // 相对日期只在「读取时计算」里成立：保存时计算的结果是快照，过了零点不会自己变。
                if (RelativeDates.isRelative(m.value())
                        && !CalculationUpdateEnum.LIVE.matches(c.updateMode()))
                    throw invalid("匹配条件使用相对日期（今天、本月等）时，计算方式须为「读取时计算」：保存时计算的结果过了零点不会自己变");
                if (m.localField() != null && !m.localField().isBlank()) {
                    if (m.value() != null) throw invalid("匹配条件不能同时配置本行字段和常量");
                    FieldDefinition local = field(d, m.localField());
                    if (FieldTypeEnum.fromCode(local.type()).isComputed())
                        throw invalid("匹配条件应使用本行基础字段");
                    if (!local.type().equals(destination.type())) throw invalid("两侧匹配字段类型须一致");
                    classification(
                            o,
                            d.fieldOptions()
                                    .getOrDefault(local.id(), DataCenter.FieldOptions.defaults()));
                    // 动态操作数在运行时转换；先检查字段和算子组合。
                    operand = sample(destination.type(), m.operator());
                }
                new DataScope(
                                "AND",
                                List.of(
                                        new DataScope.Condition(
                                                destination.id(), m.operator(), operand)),
                                List.of())
                        .validate(target, false);
            }
            for (String id : sourceFields(target, c)) {
                FieldDefinition dependency =
                        target.fields().stream()
                                .filter(v -> v.id().equals(id))
                                .findFirst()
                                .orElseThrow();
                DataCenter.FieldOptions source =
                        target.fieldOptions().getOrDefault(id, DataCenter.FieldOptions.defaults());
                classification(o, source);
                if (id.equals(
                        aggregation == CalculationAggregateEnum.COUNT
                                ? null
                                : field(target, c.targetField()).id())) {
                    FieldTypeEnum type =
                            FieldTypeEnum.fromCode(
                                    FieldTypeEnum.FORMULA.matches(dependency.type())
                                            ? source.resultType()
                                            : dependency.type());
                    if (FieldTypeEnum.SUMMARY.matches(dependency.type())
                            || Set.of(
                                            FieldTypeEnum.URL,
                                            FieldTypeEnum.MULTI_SELECT,
                                            FieldTypeEnum.IMAGE,
                                            FieldTypeEnum.ATTACHMENT,
                                            FieldTypeEnum.REGION,
                                            FieldTypeEnum.CASCADE)
                                    .contains(type)
                            || !type.isNumeric() && aggregation != CalculationAggregateEnum.SINGLE)
                        throw invalid("数值聚合须选择数值字段；文本取值须选择唯一结果");
                    if (aggregation != CalculationAggregateEnum.SINGLE
                            && !Set.of("INTEGER", "DECIMAL", "MONEY").contains(o.resultType()))
                        throw invalid("数值聚合结果类型须为整数、小数或金额");
                    if (aggregation == CalculationAggregateEnum.SINGLE
                            && !type.isNumeric()
                            && !FieldTypeEnum.TEXT.matches(o.resultType()))
                        throw invalid("非数值取值结果须为文本");
                    visit(target, dependency, definitions, path, done);
                }
            }
        }
        path.remove(key);
        done.add(key);
    }

    private static void validateSequence(
            DataCenter.Definition d,
            FieldDefinition output,
            DataCenter.FieldOptions options,
            CalculationOptions c) {
        if (c.targetObjectId() != null || c.relationId() != null || c.runningTotal() != null)
            throw invalid("通用顺序计算仅支持当前对象，不能携带累计余额配置");
        if (Boolean.TRUE.equals(c.excludeCurrent()) || !c.conditions().isEmpty())
            throw invalid("通用顺序计算不配置筛选或排除本记录");
        if (c.sequence() == null) throw invalid("通用顺序计算缺少排序配置");
        if (c.groupFields().size() > 5
                || new HashSet<>(c.groupFields()).size() != c.groupFields().size())
            throw invalid("分组字段最多 5 个且不能重复");
        for (String code : c.groupFields()) {
            FieldTypeEnum type = FieldTypeEnum.fromCode(field(d, code).type());
            if (type.isComputed()
                    || Set.of(
                                    FieldTypeEnum.MULTI_SELECT,
                                    FieldTypeEnum.URL,
                                    FieldTypeEnum.IMAGE,
                                    FieldTypeEnum.ATTACHMENT,
                                    FieldTypeEnum.REGION,
                                    FieldTypeEnum.CASCADE,
                                    FieldTypeEnum.RICH_TEXT)
                            .contains(type)) throw invalid("分组须选择基础单值字段");
        }
        CalculationOptions.Sequence rule = c.sequence();
        SequenceOperationEnum.fromCode(rule.operation());
        if (cumulative(c)) {
            if (!"PREVIOUS".equals(rule.direction())) throw invalid("顺序累计仅支持从前向后累计");
            if (!Set.of("INTEGER", "DECIMAL", "MONEY").contains(options.resultType()))
                throw invalid("顺序累计结果类型须为整数、小数或金额");
            sequenceInitial(rule);
        } else if (rule.initialValue() != null) throw invalid("相邻计算不配置累计期初值，请在公式中使用 coalesce");
        FieldTypeEnum orderType = FieldTypeEnum.fromCode(field(d, rule.orderField()).type());
        if (!orderType.isNumeric()
                && !Set.of(FieldTypeEnum.DATE, FieldTypeEnum.DATETIME, FieldTypeEnum.TIME)
                        .contains(orderType)) throw invalid("顺序计算排序须选择日期、时间或数值基础字段");
        if (rule.tieBreakerField() != null) {
            FieldTypeEnum tieType = FieldTypeEnum.fromCode(field(d, rule.tieBreakerField()).type());
            if (!tieType.isNumeric()
                    && !Set.of(
                                    FieldTypeEnum.DATE,
                                    FieldTypeEnum.DATETIME,
                                    FieldTypeEnum.TIME,
                                    FieldTypeEnum.AUTO_NUMBER,
                                    FieldTypeEnum.TEXT,
                                    FieldTypeEnum.UUID)
                            .contains(tieType)) throw invalid("同值排序须选择可排序的基础字段");
        }
        if (!Set.of("PREVIOUS", "NEXT").contains(Objects.toString(rule.direction(), "")))
            throw invalid("相邻记录方向无效");
        for (FieldDefinition field : d.fields())
            if (field.code().startsWith(PREVIOUS_PREFIX))
                throw invalid("字段编码不能以 " + PREVIOUS_PREFIX + " 开头，避免与相邻记录引用冲突");
        for (String id : sequenceSourceFields(d, output, c))
            classification(
                    options, d.fieldOptions().getOrDefault(id, DataCenter.FieldOptions.defaults()));
        // 日期函数的类型检查；保存时落库的顺序计算不能用 TODAY() / NOW()。
        FormulaDates.checkField(
                output.name(),
                FieldExpressions.parse(options.expression(), sequenceNames(d, output)).expression(),
                FormulaDates.calculated(d, CalculationUpdateEnum.LIVE.matches(c.updateMode())));
    }

    private static void validateTableWide(
            DataCenter.Definition d, DataCenter.FieldOptions output, CalculationOptions c) {
        if (c.targetObjectId() != null && !c.targetObjectId().equals(d.objectId())
                || c.relationId() != null) throw invalid("全表统计和顺序累计仅计算当前对象");
        if (c.groupFields().size() > 5
                || new HashSet<>(c.groupFields()).size() != c.groupFields().size())
            throw invalid("分组字段最多 5 个且不能重复");
        if (!Set.of(
                        FieldTypeEnum.INTEGER.getCode(),
                        FieldTypeEnum.DECIMAL.getCode(),
                        FieldTypeEnum.MONEY.getCode())
                .contains(Objects.toString(output.resultType(), "")))
            throw invalid("全表统计和顺序累计结果类型须为整数、小数或金额");
        CalculationAggregateEnum aggregate = CalculationAggregateEnum.fromCode(c.aggregate());
        if (aggregate == CalculationAggregateEnum.SINGLE) throw invalid("全表统计须选择聚合方式");
        if (aggregate != CalculationAggregateEnum.COUNT) numericSource(d, c.targetField());
        for (String code : c.groupFields()) {
            FieldTypeEnum type = FieldTypeEnum.fromCode(field(d, code).type());
            if (type.isComputed()
                    || Set.of(
                                    FieldTypeEnum.MULTI_SELECT,
                                    FieldTypeEnum.URL,
                                    FieldTypeEnum.IMAGE,
                                    FieldTypeEnum.ATTACHMENT,
                                    FieldTypeEnum.REGION,
                                    FieldTypeEnum.CASCADE,
                                    FieldTypeEnum.RICH_TEXT)
                            .contains(type)) throw invalid("分组须选择单值基础字段");
        }
        if (CalculationModeEnum.STATISTICS.matches(c.mode())) {
            if (c.runningTotal() != null) throw invalid("全表统计不能携带顺序累计配置");
            return;
        }
        CalculationOptions.RunningTotal running = c.runningTotal();
        if (running == null) throw invalid("顺序累计缺少排序与期初配置");
        if (aggregate != CalculationAggregateEnum.SUM || Boolean.TRUE.equals(c.excludeCurrent()))
            throw invalid("顺序累计须求和并包含本记录");
        if (c.conditions().stream()
                .anyMatch(m -> m.localField() != null && !m.localField().isBlank()))
            throw invalid("顺序累计筛选只能使用固定值，不能随本行改变累计口径");
        FieldTypeEnum orderType = FieldTypeEnum.fromCode(field(d, running.orderField()).type());
        if (!orderType.isNumeric()
                && !Set.of(FieldTypeEnum.DATE, FieldTypeEnum.DATETIME, FieldTypeEnum.TIME)
                        .contains(orderType)) throw invalid("累计排序须选择日期、时间或数值基础字段");
        if (running.tieBreakerField() != null) {
            FieldTypeEnum type = FieldTypeEnum.fromCode(field(d, running.tieBreakerField()).type());
            if (!type.isNumeric()
                    && !Set.of(
                                    FieldTypeEnum.DATE,
                                    FieldTypeEnum.DATETIME,
                                    FieldTypeEnum.TIME,
                                    FieldTypeEnum.AUTO_NUMBER,
                                    FieldTypeEnum.TEXT,
                                    FieldTypeEnum.UUID)
                            .contains(type)) throw invalid("同值排序须选择可排序的基础字段");
        }
        if (running.subtractField() != null) numericSource(d, running.subtractField());
        if (running.initialField() != null) {
            numericSource(d, running.initialField());
            if (running.initialValue() != null) throw invalid("期初字段与固定期初值只能选择一种");
        } else {
            try {
                BigDecimal initial = new BigDecimal(Objects.requireNonNull(running.initialValue()));
                if (initial.scale() > 10 || initial.precision() - initial.scale() > 28)
                    throw invalid("期初值超过小数精度");
            } catch (NumberFormatException | NullPointerException e) {
                throw invalid("固定期初值须为有效数字");
            }
        }
    }

    private static void numericSource(DataCenter.Definition d, String code) {
        if (!FieldTypeEnum.fromCode(field(d, code).type()).isNumeric())
            throw invalid("全表统计和顺序累计须选择数值基础字段");
    }

    private static Object sample(String type, String op) {
        ScopeOperatorEnum operator = ScopeOperatorEnum.fromCode(op);
        if (operator == ScopeOperatorEnum.IS_NULL || operator == ScopeOperatorEnum.NOT_NULL)
            return null;
        Object scalar =
                switch (FieldTypeEnum.fromCode(type)) {
                    case BOOLEAN -> false;
                    case DATE -> "2026-01-01";
                    case DATETIME -> "2026-01-01T00:00:00";
                    case TIME -> "00:00:00";
                    case UUID -> "00000000-0000-0000-0000-000000000000";
                    default -> "0";
                };
        return FieldTypeEnum.MULTI_SELECT.matches(type) || operator == ScopeOperatorEnum.IN
                ? List.of()
                : scalar;
    }

    private static void classification(
            DataCenter.FieldOptions output, DataCenter.FieldOptions source) {
        List<String> levels = List.of("NORMAL", "INTERNAL", "SENSITIVE", "SECRET");
        if (levels.indexOf(output.classification()) < levels.indexOf(source.classification()))
            throw invalid("计算结果的数据分类不得低于来源字段");
    }
}
