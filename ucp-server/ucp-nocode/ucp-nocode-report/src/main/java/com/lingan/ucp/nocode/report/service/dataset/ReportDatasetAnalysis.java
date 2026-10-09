package com.lingan.ucp.nocode.report.service.dataset;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.*;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.*;

/** 保存、发布和查询共用的分析校验；只引用来源中登记的字段，复用底座条件值与格式语义。 */
@Component
public class ReportDatasetAnalysis {
    @Resource private DataObjectApi objects;

    public void validate(ReportDatasets.Analysis analysis, ReportDatasets.ResolvedSource source) {
        if (analysis == null) return;
        if (analysis.schemaVersion() != 1 || source == null) throw invalid("分析配置版本或来源无效");
        timeZone(analysis.timeZone());
        validateMetrics(analysis.metrics(), source);
        Map<String, ReportDatasets.ResolvedField> fields = fields(source);
        for (Map.Entry<String, ApplicationReports.Format> entry :
                analysis.fieldFormats().entrySet()) {
            ReportDatasets.ResolvedField field = field(fields, entry.getKey());
            if (!FieldTypeEnum.fromCode(field.type()).isNumeric()) throw invalid("数值格式只能用于数值字段");
            format(entry.getValue());
        }
        validateFilters(analysis.fixedConditions(), source);
    }

    public void validateMetrics(
            List<ReportDatasetQueries.Metric> metrics, ReportDatasets.ResolvedSource source) {
        if (metrics == null || metrics.size() > 10) throw invalid("数据集最多配置 10 个指标（含公式依赖）");
        Map<String, ReportDatasets.ResolvedField> fields = fields(source);
        Set<String> ids = new HashSet<>();
        for (ReportDatasetQueries.Metric metric : metrics) {
            if (metric == null
                    || metric.id() == null
                    || !metric.id().matches("[A-Za-z0-9_-]{1,80}")
                    || !ids.add(metric.id())
                    || metric.name() == null
                    || metric.name().isBlank()
                    || metric.name().length() > 80) throw invalid("指标标识或名称无效或重复");
            ReportOperationEnum op = ReportOperationEnum.fromCode(metric.operation());
            if (op == ReportOperationEnum.FORMULA) {
                if (metric.fieldId() != null
                        || metric.conditions() != null
                        || metric.formula() == null) throw invalid("派生指标只接收指标四则公式，条件请配置在基础指标上");
                ReportFormulaEnum.fromCode(metric.formula().operator());
            } else if (op == ReportOperationEnum.COUNT) {
                if (metric.fieldId() != null) throw invalid("记录计数不接收字段");
            } else {
                ReportDatasets.ResolvedField field = field(fields, metric.fieldId());
                if (!field.relationPath().isEmpty()) throw invalid("指标只能基于根对象字段");
                if (op != ReportOperationEnum.COUNT_FIELD
                        && op != ReportOperationEnum.COUNT_DISTINCT
                        && (!ReportDatasetFieldRoleEnum.MEASURE.matches(field.role())
                                || !FieldTypeEnum.fromCode(field.type()).isNumeric()))
                    throw invalid("数值聚合需要数据集度量字段");
            }
            if (op != ReportOperationEnum.FORMULA && metric.formula() != null)
                throw invalid("基础指标不能携带派生公式");
            validateFilters(metric.conditions(), source);
            format(metric.format());
        }
        Map<String, ReportDatasetQueries.Metric> byId = new LinkedHashMap<>();
        metrics.forEach(metric -> byId.put(metric.id(), metric));
        for (String id : byId.keySet()) validateDependencies(id, byId, new HashSet<>());
    }

    /** 十个指标的有界依赖树在进入 SQL 展开前拒绝失效引用、自引用与循环。 */
    private void validateDependencies(
            String id, Map<String, ReportDatasetQueries.Metric> metrics, Set<String> path) {
        ReportDatasetQueries.Metric metric = metrics.get(id);
        if (metric == null) throw invalid("派生指标引用不存在：" + id);
        if (!path.add(id)) throw invalid("派生指标存在循环引用");
        if (metric.formula() != null) {
            validateDependencies(metric.formula().left(), metrics, path);
            validateDependencies(metric.formula().right(), metrics, path);
        }
        path.remove(id);
    }

    public void timeZone(String value) {
        try {
            ZoneId.of(value);
        } catch (RuntimeException error) {
            throw invalid("统计时区无效");
        }
    }

    private void format(ApplicationReports.Format value) {
        if (value != null
                && (value.unit() != null && value.unit().length() > 20
                        || value.decimals() != null
                                && (value.decimals() < 0 || value.decimals() > 8)
                        || value.color() != null && !value.color().matches("#[0-9a-fA-F]{6}")
                        || value.financial() && value.percent()))
            throw invalid("指标单位最多 20 字，精度为 0～8 位，颜色使用六位十六进制；财务金额不能同时设为百分比");
    }

    /** 固定与临时筛选使用相同白名单，不接收客户端动态身份。 */
    public void validateFilters(DataScope filters, ReportDatasets.ResolvedSource source) {
        validateScope(filters, fields(source), new HashMap<>(), 0, new int[] {0});
    }

    private void validateScope(
            DataScope scope,
            Map<String, ReportDatasets.ResolvedField> fields,
            Map<String, DataCenter.Definition> definitions,
            int depth,
            int[] count) {
        if (scope == null) return;
        if (depth > 4
                || !Set.of("AND", "OR").contains(Objects.toString(scope.logic(), ""))
                || scope.conditions().isEmpty() && scope.groups().isEmpty())
            throw invalid("筛选条件需要有效 AND／OR 条件，最多四层");
        for (DataScope.Condition condition : scope.conditions()) {
            if (++count[0] > 50) throw invalid("筛选条件最多 50 项");
            ReportDatasets.ResolvedField field = field(fields, condition.fieldId());
            DataCenter.Definition definition =
                    definitions.computeIfAbsent(
                            field.objectId(),
                            ignored ->
                                    objects.getVersion(field.objectId(), field.objectVersion())
                                            .definition());
            // 单个条件复用底座类型、常量与操作符校验；分析固定条件不接受客户端身份上下文。
            new DataScope(
                            "AND",
                            List.of(
                                    new DataScope.Condition(
                                            field.sourceFieldId(),
                                            condition.operator(),
                                            condition.value(),
                                            condition.valueSource())),
                            List.of())
                    .validate(definition, false);
        }
        for (DataScope group : scope.groups())
            validateScope(group, fields, definitions, depth + 1, count);
    }

    private Map<String, ReportDatasets.ResolvedField> fields(ReportDatasets.ResolvedSource source) {
        Map<String, ReportDatasets.ResolvedField> fields = new LinkedHashMap<>();
        source.fields().forEach(field -> fields.put(field.id(), field));
        return fields;
    }

    private ReportDatasets.ResolvedField field(
            Map<String, ReportDatasets.ResolvedField> fields, String id) {
        ReportDatasets.ResolvedField field = fields.get(id);
        if (field == null) throw invalid("字段不属于数据集：" + id);
        return field;
    }
}
