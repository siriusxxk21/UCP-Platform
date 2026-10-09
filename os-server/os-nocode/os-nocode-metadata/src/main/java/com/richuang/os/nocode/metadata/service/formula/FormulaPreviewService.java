package com.richuang.os.nocode.metadata.service.formula;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.CalculationOptions;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.enums.SequenceOperationEnum;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

/** 设计器样例试算复用生产语法和十进制求值，不接收脚本或查询业务数据。 */
@Service
public class FormulaPreviewService {
    public FormulaPreviewResult preview(FormulaPreview request) {
        if (request == null
                || request.fieldCodes() == null
                || request.fieldCodes().size() > 200
                || request.values() == null
                || request.values().size() > 200) throw invalid("试算最多包含 200 个字段");
        Map<String, String> names = new LinkedHashMap<>();
        for (String code : request.fieldCodes()) {
            if (code == null
                    || !code.matches("[a-z][a-z0-9_]{0,62}")
                    || names.put(code, code) != null) throw invalid("试算字段编码无效或重复");
        }
        Map<String, String> types = request.fieldTypes() == null ? Map.of() : request.fieldTypes();
        if (!names.keySet().containsAll(types.keySet())) throw invalid("试算类型包含未知字段");
        for (String type : types.values()) FieldTypeEnum.fromCode(type);
        if (request.sequence() == null) {
            Map<String, Object> values = values(request.values(), names, types);
            FieldExpressions.Parsed parsed = FieldExpressions.parse(request.expression(), names);
            Object result = evaluate(parsed, values, null);
            // 日期函数算出来的日期标成 DATE（公式字段的结果类型里没有日期，编辑器据此提示只能当中间值）。
            if (result != null
                    && FormulaDates.dateResult(parsed.expression(), FormulaDates.byCode(types)))
                return new FormulaPreviewResult(
                        text(result),
                        FieldTypeEnum.DATE.getCode(),
                        parsed.references().stream().sorted().toList());
            return new FormulaPreviewResult(
                    text(result),
                    resultType(result),
                    parsed.references().stream().sorted().toList());
        }
        return sequence(request, names, types);
    }

    private FormulaPreviewResult sequence(
            FormulaPreview request, Map<String, String> names, Map<String, String> types) {
        if (request.rows() == null || request.rows().isEmpty() || request.rows().size() > 100)
            throw invalid("顺序试算须提供 1 到 100 条样例记录");
        CalculationOptions.Sequence rule = request.sequence();
        SequenceOperationEnum operation = SequenceOperationEnum.fromCode(rule.operation());
        if (!Set.of("PREVIOUS", "NEXT").contains(Objects.toString(rule.direction(), "")))
            throw invalid("相邻记录方向无效");
        if (operation == SequenceOperationEnum.CUMULATIVE && !"PREVIOUS".equals(rule.direction()))
            throw invalid("顺序累计仅支持从前向后累计");
        List<String> groups = request.groupFields() == null ? List.of() : request.groupFields();
        if (groups.size() > 5 || new HashSet<>(groups).size() != groups.size())
            throw invalid("分组字段最多 5 个且不能重复");
        List<String> ordering = new ArrayList<>();
        ordering.add(rule.orderField());
        if (rule.tieBreakerField() != null) ordering.add(rule.tieBreakerField());
        if (!names.keySet().containsAll(groups) || !names.keySet().containsAll(ordering))
            throw invalid("试算分组或排序引用未知字段");
        Map<String, String> expressionNames = new LinkedHashMap<>(names);
        if (operation == SequenceOperationEnum.ADJACENT)
            names.keySet()
                    .forEach(
                            code ->
                                    expressionNames.put(
                                            Calculations.PREVIOUS_PREFIX + code,
                                            "previous:" + code));
        FieldExpressions.Parsed parsed =
                FieldExpressions.parse(request.expression(), expressionNames);
        List<Map<String, Object>> rows =
                request.rows().stream().map(row -> values(row, names, types)).toList();
        Map<List<Object>, List<Integer>> partitions = new LinkedHashMap<>();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            List<Object> key = groups.stream().map(code -> groupValue(row.get(code))).toList();
            partitions.computeIfAbsent(key, ignored -> new ArrayList<>()).add(index);
        }
        FormulaPreviewRow[] results = new FormulaPreviewRow[rows.size()];
        String type = FieldTypeEnum.DECIMAL.getCode();
        for (List<Integer> partition : partitions.values()) {
            partition.sort((left, right) -> compareRows(rows.get(left), rows.get(right), ordering));
            BigDecimal total = Calculations.sequenceInitial(rule);
            for (int position = 0; position < partition.size(); position++) {
                int index = partition.get(position);
                int adjacentPosition = position + ("NEXT".equals(rule.direction()) ? 1 : -1);
                Integer adjacentIndex =
                        adjacentPosition < 0 || adjacentPosition >= partition.size()
                                ? null
                                : partition.get(adjacentPosition);
                Object contribution =
                        evaluate(
                                parsed,
                                rows.get(index),
                                adjacentIndex == null ? null : rows.get(adjacentIndex));
                Object result = contribution;
                if (operation == SequenceOperationEnum.CUMULATIVE) {
                    if (contribution != null)
                        total = total.add(FormulaEvaluator.number(contribution));
                    result = total;
                    adjacentIndex = null;
                }
                if (result != null) type = resultType(result);
                results[index] =
                        new FormulaPreviewRow(
                                index,
                                text(result),
                                operation == SequenceOperationEnum.CUMULATIVE
                                        ? text(contribution)
                                        : null,
                                adjacentIndex);
            }
        }
        return new FormulaPreviewResult(
                null, type, parsed.references().stream().sorted().toList(), List.of(results));
    }

    private static Object groupValue(Object value) {
        return value instanceof BigDecimal decimal ? decimal.stripTrailingZeros() : value;
    }

    private int compareRows(
            Map<String, Object> left, Map<String, Object> right, List<String> ordering) {
        for (String code : ordering) {
            Object a = left.get(code), b = right.get(code);
            int comparison =
                    a == null ? b == null ? 0 : 1 : b == null ? -1 : FormulaEvaluator.compare(a, b);
            if (comparison != 0) return comparison;
        }
        // 相同排序值以输入行号兜底；正式记录以不可变主键兜底。
        return 0;
    }

    private Map<String, Object> values(
            Map<String, String> values, Map<String, String> names, Map<String, String> types) {
        if (values == null || values.size() > 200) throw invalid("样例值最多 200 个字段");
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String value = entry.getValue();
            if (!names.containsKey(entry.getKey()) || value != null && value.length() > 1000)
                throw invalid("样例值包含未知字段或内容过长");
            if (value == null || value.isEmpty()) {
                result.put(entry.getKey(), null);
                continue;
            }
            String type = types.get(entry.getKey());
            boolean numeric =
                    type == null
                            ? value.matches(
                                    "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
                            : FieldTypeEnum.fromCode(type).isNumeric();
            if (numeric) {
                BigDecimal decimal;
                try {
                    decimal = new BigDecimal(value);
                } catch (NumberFormatException error) {
                    throw invalid("试算数值格式或指数超出范围");
                }
                if (decimal.precision() > 38 || decimal.scale() < -38 || decimal.scale() > 38)
                    throw invalid("试算数值精度及小数位范围最多 38 位，请缩小样例数值");
                result.put(entry.getKey(), decimal);
            } else if (FieldTypeEnum.BOOLEAN.matches(type)) {
                if (!Set.of("true", "false").contains(value.toLowerCase(Locale.ROOT)))
                    throw invalid("布尔样例值须为 true 或 false");
                result.put(entry.getKey(), Boolean.valueOf(value));
            } else result.put(entry.getKey(), value);
        }
        return result;
    }

    private Object evaluate(
            FieldExpressions.Parsed parsed,
            Map<String, Object> current,
            Map<String, Object> adjacent) {
        try {
            return FormulaEvaluator.evaluate(
                    parsed.expression(),
                    code ->
                            code.startsWith("previous:")
                                    ? adjacent == null
                                            ? null
                                            : adjacent.get(code.substring("previous:".length()))
                                    : current.get(code));
        } catch (ArithmeticException error) {
            throw invalid("试算参数须为有效数字；四舍五入位数必须为整数");
        }
    }

    private static String text(Object result) {
        return result == null
                ? null
                : result instanceof BigDecimal decimal
                        ? decimal.toPlainString()
                        : result.toString();
    }

    private static String resultType(Object result) {
        return result instanceof BigDecimal
                ? FieldTypeEnum.DECIMAL.getCode()
                : result instanceof Boolean
                        ? FieldTypeEnum.BOOLEAN.getCode()
                        : FieldTypeEnum.TEXT.getCode();
    }
}
