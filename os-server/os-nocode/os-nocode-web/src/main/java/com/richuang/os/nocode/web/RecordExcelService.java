package com.richuang.os.nocode.web;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.excel.core.util.ExcelUtils;
import com.richuang.os.framework.mybatis.core.metadata.BaseDOColumns;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.runtime.service.record.RecordService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.util.*;

/** Excel 只负责传输字段值；结构白名单、权限与整批写入仍由应用运行服务执行。 */
@Service
public class RecordExcelService {
    @Resource private com.richuang.os.nocode.runtime.service.view.DataViewService dataViews;
    @Resource private RecordService records;

    @Resource
    private com.richuang.os.nocode.runtime.service.report.ApplicationReportService reports;

    @Resource private ObjectMapper json;
    @Resource private com.richuang.os.nocode.runtime.service.selection.SelectionCatalog selections;

    public byte[] template(String app, String object, long actor) {
        List<FieldDefinition> fields = importFields(records.importModel(app, object, actor));
        return workbook(fields.stream().map(this::header).toList(), List.of());
    }

    public int importFile(String app, String object, MultipartFile file, long actor) {
        return importFile(app, object, file, actor, null);
    }

    public int importFile(
            String app,
            String object,
            MultipartFile file,
            long actor,
            ApplicationRecords.Context context) {
        ApplicationRecords.Model model = records.importModel(app, object, actor);
        List<FieldDefinition> fields = importFields(model);
        if (file == null || file.isEmpty() || file.getSize() > 2 * 1024 * 1024)
            throw invalid("请选择不超过 2 MB 的 Excel 文件");
        String name = Objects.toString(file.getOriginalFilename(), "").toLowerCase(Locale.ROOT);
        if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) throw invalid("业务导入仅支持 Excel 模板");
        try {
            List<Map<Integer, String>> source =
                    ExcelUtils.read(file, fields.stream().map(this::header).toList(), 500);
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int index = 0; index < source.size(); index++) {
                Map<Integer, String> input = source.get(index);
                if (input.values().stream().allMatch(v -> v == null || v.isBlank())) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 0; column < fields.size(); column++) {
                    FieldDefinition f = fields.get(column);
                    String value = input.get(column);
                    if (value == null || value.isEmpty()) continue;
                    try {
                        var parsed = parse(f, value);
                        // 「code:」导入要解析挑取值候选，来源按本应用固定版本。
                        row.put(
                                f.id(),
                                selections.inApplication(
                                        app,
                                        null,
                                        () ->
                                                selections.importValue(
                                                        f,
                                                        model.object()
                                                                .fieldOptions()
                                                                .getOrDefault(
                                                                        f.id(),
                                                                        DataCenter.FieldOptions
                                                                                .defaults()),
                                                        parsed)));
                    } catch (Exception e) {
                        throw invalid("第 " + (index + 2) + " 行“" + f.name() + "”格式错误；本批未写入");
                    }
                }
                rows.add(row);
            }
            // 空白行不静默跳过，否则报错行号与原工作表不一致。
            if (rows.size() != source.size()) throw invalid("模板数据区域包含空白行，请移除后导入；本批未写入");
            return records.importRecords(app, object, rows, actor, context);
        } catch (com.richuang.os.framework.common.exception.ServiceException e) {
            throw e;
        } catch (IOException e) {
            throw invalid("Excel 文件读取失败");
        }
    }

    public byte[] export(ApplicationRecords.Query query, long actor) {
        ApplicationRecords.Model model =
                records.model(query.applicationId(), query.objectId(), actor);
        List<ApplicationRecords.Row> rows = records.export(query, actor);
        ArrayList<FieldDefinition> available =
                new ArrayList<>(BusinessFields.fields(model.object()));
        if (query.viewId() != null) {
            DataViews.Model viewModel =
                    dataViews.model(query.applicationId(), query.objectId(), query.viewId(), actor);
            available.addAll(viewModel.fields());
            ApplicationUi.View view =
                    dataViews.view(query.applicationId(), query.objectId(), query.viewId(), actor);
            available.removeIf(
                    f ->
                            !view.fieldIds().contains(f.id())
                                    && viewModel.fields().stream()
                                            .noneMatch(c -> c.id().equals(f.id())));
        }
        List<FieldDefinition> fields =
                available.stream()
                        .filter(
                                f ->
                                        rows.isEmpty()
                                                || rows.stream()
                                                        .anyMatch(
                                                                r ->
                                                                        r.values()
                                                                                .containsKey(
                                                                                        f.id())))
                        .toList();
        boolean detailGrain = rows.stream().anyMatch(r -> r.parentId() != null);
        List<String> headers =
                new ArrayList<>(detailGrain ? List.of("记录ID", "主记录ID") : List.of("记录ID"));
        fields.forEach(
                f -> {
                    headers.add(header(f));
                    if (selectionField(model.object(), f)) headers.add(f.name() + "（显示名称）");
                });
        List<List<?>> output = new ArrayList<>();
        for (ApplicationRecords.Row row : rows) {
            List<String> cells = new ArrayList<>(List.of(row.id()));
            if (detailGrain) cells.add(row.parentId());
            for (FieldDefinition field : fields) {
                cells.add(text(row.values().get(field.id())));
                if (selectionField(model.object(), field))
                    cells.add(row.displayValues().getOrDefault(field.id(), ""));
            }
            output.add(cells);
        }
        return workbook(headers, output);
    }

    /** 汇总导出重新校验 EXPORT，字符串单元格保留金额精度及防止公式执行。 */
    public byte[] report(ApplicationReports.Query query, long actor) {
        return reportResult(reports.export(query, actor));
    }

    /** 只格式化已通过各自资源服务 EXPORT 校验的结果，不能直接作为 HTTP 入口。 */
    public byte[] reportResult(ApplicationReports.Result report) {
        if (report.pivot() != null) return pivot(report);
        List<String> headers = new ArrayList<>(report.dimensionNames());
        if (headers.isEmpty()) headers.add("统计范围");
        report.metrics().forEach(m -> headers.add(m.name()));
        List<List<?>> rows = new ArrayList<>();
        for (ApplicationReports.Group group : report.groups()) {
            List<String> cells = new ArrayList<>(group.labels());
            if (cells.isEmpty()) cells.add("当前筛选");
            report.metrics()
                    .forEach(m -> cells.add(Objects.toString(group.values().get(m.id()), "")));
            rows.add(cells);
        }
        List<String> total = new ArrayList<>();
        total.add("全部符合条件记录的总体指标");
        for (int i = 1; i < Math.max(1, report.dimensionNames().size()); i++) total.add("");
        report.metrics().forEach(m -> total.add(Objects.toString(report.totals().get(m.id()), "")));
        rows.add(total);
        metricNotes(report, rows);
        rows.add(
                List.of(
                        "展示 "
                                + report.groups().size()
                                + " / "
                                + report.totalGroups()
                                + " 组；"
                                + source(report)
                                + "；时区 "
                                + report.timeZone()));
        return workbook(headers, rows);
    }

    /** 透视表：表头层数 = 列维度数 + 1（各层列维度 → 指标），按层合并，与屏幕一致；小计与合计为服务端按原始记录重新聚合的值。 */
    private byte[] pivot(ApplicationReports.Result report) {
        var pivot = report.pivot();
        var sheet = ReportPivotWorkbook.build(report, "占比");
        List<List<?>> rows = new ArrayList<>(sheet.rows());
        metricNotes(report, rows);
        rows.add(List.of("小计与合计按原始记录重新聚合，不是叶子相加；占比为原始比值（0.4 表示 40%），分母为 0 时为空。"));
        if (pivot.rowsTruncated())
            rows.add(
                    List.of(
                            "行仅显示前 "
                                    + pivot.rows().size()
                                    + " 个，共 "
                                    + pivot.totalRowGroups()
                                    + " 个"));
        if (pivot.columnsTruncated())
            rows.add(
                    List.of(
                            "列组仅显示前 "
                                    + pivot.columns().size()
                                    + " 个，共 "
                                    + pivot.totalColumnGroups()
                                    + " 个"));
        rows.add(List.of(source(report) + "；时区 " + report.timeZone()));
        try (var output = new ByteArrayOutputStream()) {
            ExcelUtils.writeHeads(output, "统计透视", sheet.heads(), sheet.merges(), rows);
            return output.toByteArray();
        } catch (IOException e) {
            throw invalid("Excel 文件生成失败");
        }
    }

    /** 口径行里的数据来源：按主记录统计是记录条数；按明细行统计是明细行数并写明是哪个明细。 */
    private String source(ApplicationReports.Result report) {
        if (report.sources() != null) return sources(report.sources());
        return report.detailName() == null
                ? "来源记录 " + report.recordCount() + " 条"
                : "来源明细行 " + report.recordCount() + " 行（明细「" + report.detailName() + "」）";
    }

    /** 多个数据来源的口径行：「来源：{来源名} {n} 条；{来源名} {m} 条」，按明细行统计的来源写「{来源名} {n} 行（明细「{明细名}」）」。 */
    private String sources(List<ApplicationReports.SourceSummary> sources) {
        List<String> parts = new ArrayList<>();
        for (ApplicationReports.SourceSummary s : sources)
            parts.add(
                    s.detailName() == null
                            ? s.name() + " " + s.recordCount() + " 条"
                            : s.name() + " " + s.recordCount() + " 行（明细「" + s.detailName() + "」）");
        return "来源：" + String.join("；", parts);
    }

    // 导出保留原始数值；百分比在口径行说明，避免把 0.4 误读为 0.4%。
    private void metricNotes(ApplicationReports.Result report, List<List<?>> rows) {
        for (var metric : report.metrics()) {
            String format =
                    metric.format() != null && metric.format().percent()
                            ? "；原始比值（0.4 表示 40%）"
                            : metric.format() != null && metric.format().unit() != null
                                    ? "；单位：" + metric.format().unit()
                                    : "";
            String calculation =
                    metric.formula() == null
                            ? metric.operation()
                                    + (metric.conditions() == null
                                            ? "；条件以固定数据集/报表定义为准"
                                            : "；包含指标独立条件")
                            : metric.formula().left()
                                    + " "
                                    + metric.formula().operator()
                                    + " "
                                    + metric.formula().right()
                                    + "；总体重算，除数为零为空";
            rows.add(List.of("指标口径：" + metric.name() + "；" + calculation + format));
        }
    }

    private boolean selectionField(DataCenter.Definition d, FieldDefinition f) {
        return SelectionFields.source(
                                f,
                                d.fieldOptions()
                                        .getOrDefault(f.id(), DataCenter.FieldOptions.defaults()))
                        != null
                || d.relations().stream().anyMatch(r -> f.id().equals(r.fieldId()));
    }

    private List<FieldDefinition> importFields(ApplicationRecords.Model model) {
        if (model.object().relations().stream()
                .anyMatch(r -> BusinessFields.multiple(r) && Boolean.TRUE.equals(r.required())))
            throw invalid("当前对象含必填多选关系，请通过表单新建；Excel 导入暂不支持关联表数据");
        List<FieldDefinition> fields =
                model.object().fields().stream()
                        .filter(
                                f -> {
                                    DataCenter.FieldOptions option =
                                            model.object()
                                                    .fieldOptions()
                                                    .getOrDefault(
                                                            f.id(),
                                                            DataCenter.FieldOptions.defaults());
                                    return model.permissions().writeFields().contains(f.id())
                                            && !FieldTypeEnum.fromCode(f.type()).isComputed()
                                            && !FieldTypeEnum.AUTO_NUMBER.matches(f.type())
                                            && !Boolean.TRUE.equals(option.generated())
                                            && !BaseDOColumns.NAMES.contains(
                                                    Objects.toString(option.columnName(), f.code()))
                                            && (!f.id().equals(model.keyFieldId())
                                                    || !model.generatedKey());
                                })
                        .toList();
        if (fields.isEmpty()) throw invalid("没有可导入的业务字段");
        return fields;
    }

    private Object parse(FieldDefinition field, String raw) throws IOException {
        return switch (FieldTypeEnum.fromCode(field.type())) {
            case URL -> raw.stripLeading().startsWith("{") ? json.readValue(raw, Map.class) : raw;
            case BOOLEAN -> {
                if (Set.of("true", "是", "1").contains(raw)) yield true;
                if (Set.of("false", "否", "0").contains(raw)) yield false;
                throw invalid("布尔值无效");
            }
            case MULTI_SELECT, IMAGE, ATTACHMENT, REGION, CASCADE ->
                    json.readValue(raw, List.class);
            default -> raw;
        };
    }

    private String text(Object value) {
        if (value == null) return "";
        try {
            return value instanceof Collection<?> || value instanceof Map<?, ?>
                    ? json.writeValueAsString(value)
                    : value.toString();
        } catch (IOException e) {
            throw invalid("字段无法导出");
        }
    }

    private String header(FieldDefinition field) {
        return field.name() + "【" + field.code() + "】";
    }

    private byte[] workbook(List<String> headers, List<? extends List<?>> rows) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ExcelUtils.write(output, "业务数据", headers, rows);
            return output.toByteArray();
        } catch (IOException e) {
            throw invalid("Excel 文件生成失败");
        }
    }
}
