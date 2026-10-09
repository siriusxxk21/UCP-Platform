package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.form.DocumentPolicies;
import com.richuang.os.nocode.metadata.service.formula.FieldExpressions;
import com.richuang.os.nocode.metadata.service.formula.FormulaEvaluator;

import java.math.*;
import java.util.*;
import java.util.regex.Pattern;

/** 当前尚未写库的单据试算。复用公式解析器，明细聚合只读取本次最终行集合。 */
public final class DocumentCalculations {
    private static final Pattern SUMMARY =
            Pattern.compile(
                    "(count|sum|avg|min|max)\\(([a-z][a-z0-9_]*)(?:\\.([a-z][a-z0-9_]*))?\\)");

    private DocumentCalculations() {}

    public static DocumentPolicies.Input calculate(
            DataCenter.Definition d, DocumentPolicies.Input input) {
        return calculate(
                d, input, values -> new Table(d.fields(), d.fieldOptions(), values).calculate());
    }

    public static DocumentPolicies.Input calculate(
            DataCenter.Definition d,
            DocumentPolicies.Input input,
            java.util.function.UnaryOperator<Map<String, Object>> mainCalculator) {
        Map<String, List<DocumentPolicies.InputRow>> groups = new LinkedHashMap<>();
        for (var detail : d.details()) {
            if (!input.details().containsKey(detail.id())) continue;
            var rows = new ArrayList<DocumentPolicies.InputRow>();
            for (var row : input.details().get(detail.id())) {
                var evaluator = new Table(detail.fields(), detail.fieldOptions(), row.values());
                rows.add(
                        new DocumentPolicies.InputRow(
                                row.id(), row.clientRowKey(), evaluator.calculate()));
            }
            groups.put(detail.id(), rows);
        }
        var main = new LinkedHashMap<>(input.values());
        for (var field : d.fields()) {
            if (!FieldTypeEnum.SUMMARY.matches(field.type())) continue;
            var option =
                    d.fieldOptions().getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            if (MemberStateEnum.INACTIVE.matches(option.state())) continue;
            var match = SUMMARY.matcher(Objects.toString(option.expression(), ""));
            if (!match.matches()) throw invalid("汇总表达式无效：" + field.name());
            var detail =
                    d.details().stream()
                            .filter(t -> t.code().equals(match.group(2)))
                            .findFirst()
                            .orElseThrow(() -> invalid("汇总来源明细不存在"));
            var rows = groups.getOrDefault(detail.id(), List.of());
            if (SummaryOperationEnum.fromCode(match.group(1)) == SummaryOperationEnum.COUNT) {
                main.put(field.id(), BigDecimal.valueOf(rows.size()));
                continue;
            }
            var source =
                    detail.fields().stream()
                            .filter(f -> f.code().equals(match.group(3)))
                            .findFirst()
                            .orElseThrow(() -> invalid("汇总来源字段不存在"));
            var numbers =
                    rows.stream()
                            .map(r -> r.values().get(source.id()))
                            .filter(Objects::nonNull)
                            .map(FormulaEvaluator::number)
                            .toList();
            Object value;
            if (numbers.isEmpty())
                value =
                        SummaryOperationEnum.fromCode(match.group(1)) == SummaryOperationEnum.SUM
                                ? BigDecimal.ZERO
                                : null;
            else {
                var sum = numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                value =
                        switch (SummaryOperationEnum.fromCode(match.group(1))) {
                            case SUM -> sum;
                            case AVG ->
                                    sum.divide(
                                            BigDecimal.valueOf(numbers.size()),
                                            16,
                                            RoundingMode.HALF_UP);
                            case MIN -> numbers.stream().min(BigDecimal::compareTo).orElseThrow();
                            case MAX -> numbers.stream().max(BigDecimal::compareTo).orElseThrow();
                            default -> throw invalid("汇总方式无效");
                        };
            }
            main.put(field.id(), value);
        }
        return new DocumentPolicies.Input(mainCalculator.apply(main), groups);
    }

    private static final class Table {
        private final Map<String, FieldDefinition> fields = new LinkedHashMap<>();
        private final Map<String, String> names = new HashMap<>();
        private final Map<String, DataCenter.FieldOptions> options;
        private final Map<String, Object> values;
        private final Set<String> visited = new HashSet<>(), visiting = new HashSet<>();

        Table(
                List<FieldDefinition> fields,
                Map<String, DataCenter.FieldOptions> options,
                Map<String, Object> values) {
            fields.forEach(
                    f -> {
                        this.fields.put(f.id(), f);
                        names.put(f.code(), f.id());
                    });
            this.options = options;
            this.values = new LinkedHashMap<>(values);
        }

        Map<String, Object> calculate() {
            fields.keySet().forEach(this::value);
            return values;
        }

        private Object value(String id) {
            var f = fields.get(id);
            if (f == null) throw invalid("公式依赖字段不存在");
            var o = options.getOrDefault(id, DataCenter.FieldOptions.defaults());
            if (!FieldTypeEnum.FORMULA.matches(f.type())
                    || MemberStateEnum.INACTIVE.matches(o.state())
                    || o.calculation() != null
                            && !CalculationModeEnum.LOCAL.matches(o.calculation().mode()))
                return values.get(id);
            if (visited.contains(id)) return values.get(id);
            if (!visiting.add(id) || visiting.size() > 16) throw invalid("单据计算存在循环依赖或层级过深");
            Object result =
                    FormulaEvaluator.evaluate(
                            FieldExpressions.parse(o.expression(), names).expression(),
                            this::value);
            if (result != null && !FieldTypeEnum.TEXT.matches(o.resultType())) {
                var number = FormulaEvaluator.number(result);
                if (FieldTypeEnum.INTEGER.matches(o.resultType())) {
                    try {
                        result = number.longValueExact();
                    } catch (ArithmeticException e) {
                        throw invalid("计算结果须为有效整数");
                    }
                } else {
                    number = number.setScale(10, RoundingMode.HALF_UP);
                    if (number.precision() > 38) throw invalid("计算结果超过数值精度");
                    result = number;
                }
            }
            values.put(id, result);
            visiting.remove(id);
            visited.add(id);
            return result;
        }
    }
}
