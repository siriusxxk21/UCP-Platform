package com.lingan.ucp.nocode.api;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.enums.*;

import java.util.*;

/** 固定数据范围与可清除的首次查询分离，候选范围只控制筛选呈现。 */
public record ViewQueryOptions(
        List<DataScope.Condition> fixed,
        Map<String, Object> defaults,
        Map<String, List<String>> candidates) {
    public ViewQueryOptions {
        fixed = fixed == null ? List.of() : List.copyOf(fixed);
        defaults =
                defaults == null
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(defaults));
        candidates = candidates == null ? Map.of() : Map.copyOf(candidates);
    }

    public static ViewQueryOptions empty() {
        return new ViewQueryOptions(null, null, null);
    }

    public DataScope scope() {
        return fixed.isEmpty() ? null : new DataScope("AND", fixed, List.of());
    }

    public void validate(DataCenter.Definition d) {
        if (fixed.size() > 20 || defaults.size() > 20 || candidates.size() > 20)
            throw invalid("视图范围最多配置 20 个字段");
        if (fixed.stream().map(DataScope.Condition::fieldId).distinct().count() != fixed.size())
            throw invalid("固定范围字段不能重复");
        if (scope() != null) scope().validate(d, false);
        for (var entry : defaults.entrySet()) {
            var f = DataScope.field(d, entry.getKey());
            if (!com.lingan.ucp.nocode.enums.RecordQueryOperatorEnum.supports(
                            FieldTypeEnum.fromCode(f.type()))
                    && !FieldTypeEnum.MULTI_SELECT.matches(f.type())) throw invalid("默认查询字段不可用");
            var value = entry.getValue();
            if (value == null) continue;
            // 默认查询的相对日期（今天、本月……）原样保存，打开列表时随查询按当天换算（与「等于」同一口径）。
            if (RelativeDates.isRelative(value)) {
                RelativeDates.check(f, "eq", value);
                continue;
            }
            if (FieldTypeEnum.MULTI_SELECT.matches(f.type()) && !(value instanceof List<?>))
                throw invalid("多选字段的默认查询需要数组");
            if (value instanceof List<?> list) {
                if (list.size() > 100) throw invalid("默认查询值过多");
                list.forEach(v -> DataScope.scalar(d, f, v));
            } else DataScope.scalar(d, f, value);
        }
        for (var entry : candidates.entrySet()) {
            DataScope.field(d, entry.getKey());
            if (entry.getValue() == null || entry.getValue().size() > 100)
                throw invalid("候选范围最多 100 个值");
            if (entry.getValue().stream()
                    .anyMatch(v -> v == null || v.isBlank() || v.length() > 2000))
                throw invalid("候选值无效");
        }
    }

    public ViewQueryOptions visible(Set<String> readable, Map<String, Object> legacy) {
        Map<String, List<String>> options = new LinkedHashMap<>();
        candidates.forEach(
                (id, list) -> {
                    if (readable.contains(id)) options.put(id, list);
                });
        for (var c : fixed) {
            // 相对日期是区间而不是一个候选值，不参与候选收窄。
            if (!readable.contains(c.fieldId())
                    || !Set.of("eq", "in", "containsAny", "containsAll").contains(c.operator())
                    || RelativeDates.isRelative(c.value())) continue;
            restrict(options, c.fieldId(), c.value());
        }
        if (legacy != null)
            legacy.forEach(
                    (id, value) -> {
                        if (readable.contains(id) && value != null && !(value instanceof Map<?, ?>))
                            restrict(options, id, value);
                    });
        var initial = new LinkedHashMap<String, Object>();
        defaults.forEach(
                (id, value) -> {
                    if (readable.contains(id)) initial.put(id, value);
                });
        return new ViewQueryOptions(List.of(), initial, options);
    }

    private static void restrict(Map<String, List<String>> options, String id, Object raw) {
        var allowed =
                (raw instanceof List<?> list ? list : List.of(raw))
                        .stream().map(Object::toString).toList();
        options.compute(
                id,
                (key, old) ->
                        old == null ? allowed : old.stream().filter(allowed::contains).toList());
    }
}
