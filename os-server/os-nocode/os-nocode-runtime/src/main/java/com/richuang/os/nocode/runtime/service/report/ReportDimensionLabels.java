package com.richuang.os.nocode.runtime.service.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.runtime.service.selection.SelectionCatalog;

import java.util.*;

/** 统计维度的显示文本：汇总表分组与透视表行/列表头共用同一规则，键保持维度原值用于下钻。 */
final class ReportDimensionLabels {
    private ReportDimensionLabels() {}

    static List<String> keys(JsonNode array) {
        List<String> keys = new ArrayList<>();
        for (var key : array) keys.add(key == null || key.isNull() ? null : key.asText());
        return keys;
    }

    /** 按稳定身份分组，名称仅作展示；每个维度批量解析一次，保持下钻和同名目录的身份不变。 空值显示「未填写」，失效或无权的选项保留原键并提示。 */
    static List<List<String>> labels(
            SelectionCatalog selections,
            List<FieldDefinition> fields,
            List<DataCenter.Definition> owners,
            List<List<String>> keyRows) {
        List<Map<String, String>> dimensionLabels = new ArrayList<>();
        List<DataCenter.FieldOptions> dimensionOptions = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            var field = fields.get(i);
            var options =
                    owners.get(i)
                            .fieldOptions()
                            .getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
            dimensionOptions.add(options);
            Map<String, String> labels = new HashMap<>();
            if (SelectionFields.source(field, options) != null) {
                Set<String> ids = new LinkedHashSet<>();
                for (var keys : keyRows)
                    if (i < keys.size() && keys.get(i) != null) ids.add(keys.get(i));
                selections
                        .selectedOptions(field, options, ids)
                        .forEach(option -> labels.put(option.value(), option.label()));
            }
            dimensionLabels.add(labels);
        }
        List<List<String>> result = new ArrayList<>();
        for (var keys : keyRows) {
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++) {
                String value = keys.get(i);
                String label = value == null ? "未填写" : value;
                var field = fields.get(i);
                if (value != null && SelectionFields.source(field, dimensionOptions.get(i)) != null)
                    label = dimensionLabels.get(i).getOrDefault(value, "已失效或无权限的选项");
                if (value != null && FieldTypeEnum.BOOLEAN.matches(field.type()))
                    label = "true".equals(value) ? "是" : "否";
                labels.add(label);
            }
            result.add(labels);
        }
        return result;
    }

    /** 多个数据来源时取引用名称：返回该维度的「目标记录 ID → 名称」；该维度不是单值引用字段时返回 null（按单来源规则显示）。 */
    interface References {
        Map<String, String> labels(int dimension, Set<String> ids);
    }

    /**
     * 多个数据来源的维度文本：单值引用字段的维度显示目标记录名称（取不到显示「已失效或无权查看的记录」），因为对齐时按记录 ID 分组、同名的两条记录是两行； 其余维度与 {@link
     * #labels(SelectionCatalog, List, List, List)} 相同。
     */
    static List<List<String>> labels(
            SelectionCatalog selections,
            List<FieldDefinition> fields,
            List<DataCenter.Definition> owners,
            List<List<String>> keyRows,
            References references) {
        List<List<String>> result = labels(selections, fields, owners, keyRows);
        for (int i = 0; i < fields.size(); i++) {
            Set<String> ids = new LinkedHashSet<>();
            for (List<String> keys : keyRows)
                if (i < keys.size() && keys.get(i) != null) ids.add(keys.get(i));
            Map<String, String> names = references.labels(i, ids);
            if (names == null) continue;
            for (int row = 0; row < keyRows.size(); row++) {
                List<String> keys = keyRows.get(row);
                if (i < keys.size() && keys.get(i) != null)
                    result.get(row).set(i, names.getOrDefault(keys.get(i), "已失效或无权查看的记录"));
            }
        }
        return result;
    }
}
