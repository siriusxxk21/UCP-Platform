package com.richuang.os.nocode.runtime.service.report;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.report.service.authorization.ReportDatasetAuthorizationService;
import com.richuang.os.nocode.report.service.authorization.ReportDatasetAuthorizationService.ApplicationGate;
import com.richuang.os.nocode.report.service.dataset.ReportDatasetAnalysis;
import com.richuang.os.nocode.runtime.dal.query.*;
import com.richuang.os.nocode.runtime.service.access.ScopeConditions;
import com.richuang.os.nocode.runtime.service.record.*;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

/** 固定数据集字段映射到已有聚合执行器；所有关联与筛选字段均先编译数据权限。 */
@Service
public class ReportDatasetQueryServiceImpl implements ReportDatasetQueryService {
    @Resource private ReportDatasetAuthorizationService authorization;
    @Resource private DataObjectApi objects;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordValues values;
    @Resource private ReportGrantConditions conditions;
    @Resource private ReportAggregateReader results;
    @Resource private ObjectMapper json;
    @Resource private ReportDatasetAnalysis analysis;
    @Resource private ScopeConditions scopes;
    @Resource private com.richuang.os.nocode.runtime.dal.mapper.ReportMapper mapper;
    @Resource private com.richuang.os.nocode.runtime.service.selection.SelectionCatalog selections;

    /** 独立组件可复用 SQL 聚合器，但配置来源始终是仪表板服务还原的固定数据集版本。 */
    @Override
    public ApplicationReports.Result chart(
            ReportDashboards.Chart chart, boolean exporting, long actor) {
        return chart(
                new ReportDashboards.Execution(chart, null, List.of(), List.of(), List.of()),
                exporting,
                actor);
    }

    @Override
    public ApplicationReports.Result chart(
            ReportDashboards.Execution execution, boolean exporting, long actor) {
        return chart(execution, null, exporting, actor);
    }

    @Override
    public ApplicationReports.Result chart(
            ReportDashboards.Execution execution,
            ApplicationGate gate,
            boolean exporting,
            long actor) {
        ReportDashboards.Chart chart = execution.chart();
        java.util.function.Function<
                        ReportDatasetAuthorizationService.Context, ApplicationReports.Result>
                action =
                        access -> {
                            ResolvedQuery resolved =
                                    resolve(
                                            chartQuery(
                                                    chart,
                                                    chartDimensions(chart),
                                                    adaptFilters(execution.filters(), access)),
                                            access);
                            Plan plan =
                                    prepareInteraction(
                                            resolved.query(), access, actor, exporting, execution);
                            boolean allowed = access.resourceCanExport();
                            if (allowed && !exporting) {
                                try {
                                    prepareInteraction(
                                            resolved.query(), access, actor, true, execution);
                                } catch (
                                        org.springframework.security.access.AccessDeniedException
                                                denied) {
                                    allowed = false;
                                }
                            }
                            boolean canExport = allowed;
                            return selections.inDefinitions(
                                    plan.definitions(),
                                    () -> {
                                        if (ReportDisplayEnum.PIVOT.matches(chart.display()))
                                            return exportFlag(
                                                    pivot(chart, resolved, plan), canExport);
                                        return project(
                                                results.read(
                                                        plan.statement(),
                                                        plan.names(),
                                                        plan.statement().metrics(),
                                                        resolved.query().timeZone(),
                                                        () -> canExport,
                                                        keys -> labels(plan, keys)),
                                                resolved.outputIds());
                                    });
                        };
        if (gate != null)
            return authorization.withApplicationDashboardAccess(
                    execution.resolved(), gate, actor, action, exporting);
        if (execution.resolved() != null)
            return exporting
                    ? authorization.withDashboardExportAccess(execution.resolved(), actor, action)
                    : authorization.withDashboardDataAccess(execution.resolved(), actor, action);
        return exporting
                ? authorization.withExportAccess(
                        chart.dataset().id(),
                        chart.dataset().versionNo(),
                        chart.dataset().checksum(),
                        false,
                        actor,
                        action)
                : authorization.withDataAccess(
                        chart.dataset().id(),
                        chart.dataset().versionNo(),
                        chart.dataset().checksum(),
                        false,
                        actor,
                        action);
    }

    private ApplicationReports.Result exportFlag(
            ApplicationReports.Result result, boolean allowed) {
        return new ApplicationReports.Result(
                result.dimensionNames(),
                result.metrics(),
                result.groups(),
                result.totals(),
                result.totalGroups(),
                result.recordCount(),
                allowed,
                result.timeZone(),
                result.pivot());
    }

    private List<ReportDatasetQueries.Dimension> chartDimensions(ReportDashboards.Chart chart) {
        List<ReportDatasetQueries.Dimension> dimensions = new ArrayList<>(chart.dimensions());
        if (chart.columnDimensions() != null) dimensions.addAll(chart.columnDimensions());
        return dimensions;
    }

    private ReportDatasetQueries.Query chartQuery(
            ReportDashboards.Chart chart, List<ReportDatasetQueries.Dimension> dimensions) {
        return chartQuery(chart, dimensions, null);
    }

    private ReportDatasetQueries.Query chartQuery(
            ReportDashboards.Chart chart,
            List<ReportDatasetQueries.Dimension> dimensions,
            DataScope filters) {
        return new ReportDatasetQueries.Query(
                chart.dataset().id(),
                chart.dataset().versionNo(),
                chart.dataset().checksum(),
                false,
                dimensions,
                null,
                Map.of(),
                100,
                null,
                chart.metricIds(),
                filters);
    }

    @Override
    public void checkChartAccess(ReportDashboards.Chart chart, long actor) {
        authorization.withDataAccess(
                chart.dataset().id(),
                chart.dataset().versionNo(),
                chart.dataset().checksum(),
                false,
                actor,
                access -> {
                    ResolvedQuery query =
                            resolve(chartQuery(chart, chartDimensions(chart)), access);
                    prepare(query.query(), access, actor, false);
                    return null;
                });
    }

    @Override
    public void checkChartAccess(ReportDashboards.Resolved resolved, long actor) {
        checkChartAccess(resolved, null, actor);
    }

    @Override
    public void checkChartAccess(
            ReportDashboards.Resolved resolved, ApplicationGate gate, long actor) {
        java.util.function.Function<ReportDatasetAuthorizationService.Context, Void> action =
                access -> {
                    ResolvedQuery query =
                            resolve(
                                    chartQuery(resolved.chart(), chartDimensions(resolved.chart())),
                                    access);
                    prepare(query.query(), access, actor, false);
                    return null;
                };
        if (gate != null) {
            authorization.withApplicationDashboardAccess(resolved, gate, actor, action, false);
            return;
        }
        authorization.withDashboardDataAccess(
                resolved,
                actor,
                access -> {
                    ResolvedQuery query =
                            resolve(
                                    chartQuery(resolved.chart(), chartDimensions(resolved.chart())),
                                    access);
                    prepare(query.query(), access, actor, false);
                    return null;
                });
    }

    /** 原键字符串按配置字段类型还原；日期范围的结束日包含当天，时区取固定数据集定义。 */
    private DataScope adaptFilters(
            DataScope scope, ReportDatasetAuthorizationService.Context access) {
        if (scope == null) return null;
        List<DataScope.Condition> conditions = new ArrayList<>();
        for (DataScope.Condition condition : scope.conditions()) {
            ReportDatasets.ResolvedField field =
                    access.source().fields().stream()
                            .filter(f -> f.id().equals(condition.fieldId()))
                            .findFirst()
                            .orElseThrow(() -> invalid("交互字段不属于数据集固定版本"));
            Object value = condition.value();
            String operator = condition.operator();
            if (FieldTypeEnum.BOOLEAN.matches(field.type()) && value != null) {
                value =
                        value instanceof List<?> list
                                ? list.stream().map(this::booleanKey).toList()
                                : booleanKey(value);
            } else if (FieldTypeEnum.DATETIME.matches(field.type())
                    && value instanceof String text
                    && text.length() == 10
                    && Set.of(ScopeOperatorEnum.GTE.getCode(), ScopeOperatorEnum.LTE.getCode())
                            .contains(operator)) {
                java.time.LocalDate date;
                try {
                    date = java.time.LocalDate.parse(text);
                } catch (RuntimeException error) {
                    throw invalid("日期范围格式无效");
                }
                if (ScopeOperatorEnum.LTE.matches(operator)) {
                    date = date.plusDays(1);
                    operator = ScopeOperatorEnum.LT.getCode();
                }
                DataCenter.Definition definition =
                        objects.getVersion(field.objectId(), field.objectVersion()).definition();
                RuntimeSchema.Table table = schemas.main(definition);
                String column = table.columns().get(field.sourceFieldId());
                boolean zoned =
                        table.physical().columns().stream()
                                .anyMatch(
                                        c ->
                                                c.name().equals(column)
                                                        && c.nativeType()
                                                                .contains("with time zone"));
                String zone =
                        access.content().analysis() == null
                                ? "Asia/Shanghai"
                                : access.content().analysis().timeZone();
                value =
                        zoned
                                ? date.atStartOfDay(java.time.ZoneId.of(zone))
                                        .toOffsetDateTime()
                                        .toString()
                                : date.atStartOfDay().toString();
            }
            conditions.add(new DataScope.Condition(condition.fieldId(), operator, value));
        }
        return new DataScope(
                scope.logic(),
                conditions,
                scope.groups().stream().map(g -> adaptFilters(g, access)).toList());
    }

    private Boolean booleanKey(Object value) {
        if (value instanceof Boolean booleanValue) return booleanValue;
        if ("true".equals(value)) return true;
        if ("false".equals(value)) return false;
        throw invalid("布尔原键格式无效");
    }

    /** 钻取与文本字段先参与字段授权，再从可见投影中移出；日期键沿用原桶 SQL。 */
    private Plan prepareInteraction(
            ReportDatasetQueries.Query query,
            ReportDatasetAuthorizationService.Context access,
            long actor,
            boolean exporting,
            ReportDashboards.Execution execution) {
        List<ReportDatasetQueries.Dimension> all = new ArrayList<>(query.dimensions());
        for (ReportDatasetQueries.Dimension dimension : execution.pathDimensions())
            if (!all.contains(dimension)) all.add(dimension);
        for (ReportDashboards.TextFilter filter : execution.textFilters()) {
            ReportDatasetQueries.Dimension dimension =
                    new ReportDatasetQueries.Dimension(filter.fieldId(), "VALUE");
            if (!all.contains(dimension)) all.add(dimension);
        }
        ReportDatasetQueries.Query expanded =
                new ReportDatasetQueries.Query(
                        query.datasetId(),
                        query.versionNo(),
                        query.checksum(),
                        query.preview(),
                        all,
                        query.metrics(),
                        query.equal(),
                        query.limit(),
                        query.timeZone(),
                        query.metricIds(),
                        query.filters());
        Plan plan = prepare(expanded, access, actor, true, exporting, true);
        ReportStatement source = plan.statement();
        List<ReportStatement.Dimension> paths =
                execution.pathDimensions().stream()
                        .map(d -> source.dimensions().get(all.indexOf(d)))
                        .toList();
        List<ReportStatement.TextFilter> text =
                execution.textFilters().stream()
                        .map(
                                f ->
                                        new ReportStatement.TextFilter(
                                                source.dimensions()
                                                        .get(
                                                                all.indexOf(
                                                                        new ReportDatasetQueries
                                                                                .Dimension(
                                                                                f.fieldId(),
                                                                                "VALUE"))),
                                                f.value()))
                        .toList();
        int visible = query.dimensions().size();
        ReportStatement statement =
                new ReportStatement(
                        source.base(),
                        source.dimensions().subList(0, visible),
                        source.joins(),
                        source.filters(),
                        source.metrics(),
                        source.metricColumns(),
                        source.dateColumn(),
                        source.dateWithZone(),
                        source.timeZone(),
                        source.dateFrom(),
                        source.dateToExclusive(),
                        source.group(),
                        source.groupLimit(),
                        source.sortIndex(),
                        source.descending(),
                        source.predicates(),
                        source.drillMetricIndex(),
                        source.columnDimensions(),
                        source.columnGroup(),
                        source.columnLimit(),
                        paths,
                        execution.path(),
                        text);
        return new Plan(
                statement,
                plan.names().subList(0, visible),
                plan.fields().subList(0, visible),
                plan.owners().subList(0, visible),
                plan.titles().subList(0, visible),
                plan.definitions());
    }

    /** 分拆已统一授权的维度为行列，不重新编译或改变条件。 */
    private ReportStatement split(
            ReportStatement source,
            int rows,
            ApplicationReports.Pivot pivot,
            List<String> group,
            List<String> columnGroup,
            Integer metric) {
        return new ReportStatement(
                source.base(),
                source.dimensions().subList(0, rows),
                source.joins(),
                source.filters(),
                source.metrics(),
                source.metricColumns(),
                null,
                false,
                source.timeZone(),
                null,
                null,
                group,
                source.groupLimit(),
                null,
                false,
                source.predicates(),
                metric,
                source.dimensions().subList(rows, source.dimensions().size()),
                columnGroup,
                pivot.maxColumnGroups(),
                source.pathDimensions(),
                source.path(),
                source.textFilters());
    }

    private Plan slice(Plan plan, int start, int end) {
        ReportStatement s = plan.statement();
        ReportStatement sliced =
                new ReportStatement(
                        s.base(),
                        s.dimensions().subList(start, end),
                        s.joins(),
                        s.filters(),
                        s.metrics(),
                        s.metricColumns(),
                        null,
                        false,
                        s.timeZone(),
                        null,
                        null,
                        null,
                        s.groupLimit(),
                        null,
                        false,
                        s.predicates(),
                        null,
                        List.of(),
                        null,
                        0,
                        s.pathDimensions(),
                        s.path(),
                        s.textFilters());
        return new Plan(
                sliced,
                plan.names().subList(start, end),
                plan.fields().subList(start, end),
                plan.owners().subList(start, end),
                plan.titles().subList(start, end),
                plan.definitions());
    }

    private ApplicationReports.Result pivot(
            ReportDashboards.Chart chart, ResolvedQuery resolved, Plan plan) {
        ApplicationReports.Pivot options =
                chart.pivot() == null
                        ? ApplicationReports.Pivot.defaults()
                        : chart.pivot().withDefaults();
        int rows = chart.dimensions().size(), columns = plan.names().size() - rows;
        try {
            com.fasterxml.jackson.databind.JsonNode raw =
                    json.readTree(
                            mapper.pivot(split(plan.statement(), rows, options, null, null, null)));
            List<List<String>> rowKeys = new ArrayList<>(), columnKeys = new ArrayList<>();
            raw.path("rows").forEach(item -> rowKeys.add(ReportDimensionLabels.keys(item)));
            raw.path("columns").forEach(item -> columnKeys.add(ReportDimensionLabels.keys(item)));
            List<List<String>> rowLabels = labels(slice(plan, 0, rows), rowKeys);
            List<List<String>> columnLabels = labels(slice(plan, rows, rows + columns), columnKeys);
            List<ApplicationReports.PivotHeader> rowHeaders = new ArrayList<>(),
                    columnHeaders = new ArrayList<>();
            for (int i = 0; i < rowKeys.size(); i++)
                rowHeaders.add(
                        new ApplicationReports.PivotHeader(rowKeys.get(i), rowLabels.get(i)));
            for (int i = 0; i < columnKeys.size(); i++)
                columnHeaders.add(
                        new ApplicationReports.PivotHeader(columnKeys.get(i), columnLabels.get(i)));
            List<ApplicationReports.PivotCell> cells =
                    ReportPivotCells.cells(
                            ReportPivotCells.parse(
                                    raw.path("cells"), rows, columns, resolved.outputIds()),
                            rows,
                            columns,
                            options,
                            rowKeys,
                            columnKeys);
            Map<String, String> totals = new LinkedHashMap<>();
            resolved.outputIds()
                    .forEach(
                            id ->
                                    totals.put(
                                            id,
                                            raw.path("grand").path(id).isNull()
                                                    ? null
                                                    : raw.path("grand").path(id).asText()));
            long totalRows = raw.path("totalRowGroups").asLong(),
                    totalColumns = raw.path("totalColumnGroups").asLong();
            return new ApplicationReports.Result(
                    plan.names().subList(0, rows),
                    plan.statement().metrics().stream()
                            .filter(m -> resolved.outputIds().contains(m.id()))
                            .toList(),
                    List.of(),
                    totals,
                    totalRows,
                    raw.path("recordCount").asLong(),
                    false,
                    resolved.query().timeZone(),
                    new ApplicationReports.PivotResult(
                            plan.names().subList(0, rows),
                            plan.names().subList(rows, rows + columns),
                            rowHeaders,
                            columnHeaders,
                            cells,
                            totalRows > rowHeaders.size(),
                            totalColumns > columnHeaders.size(),
                            totalRows,
                            totalColumns));
        } catch (java.io.IOException error) {
            throw invalid("透视结果无法读取");
        }
    }

    /** 明细无写入入口；同一 FROM、指标条件、日期桶、空值原键与权限决定记录集。 */
    @Override
    public ReportDashboards.DetailPage details(
            ReportDashboards.Chart chart, ReportDashboards.Details request, long actor) {
        return details(
                new ReportDashboards.Execution(chart, null, List.of(), List.of(), List.of()),
                request,
                actor);
    }

    @Override
    public ReportDashboards.DetailPage details(
            ReportDashboards.Execution execution, ReportDashboards.Details request, long actor) {
        return details(execution, request, null, actor);
    }

    @Override
    public ReportDashboards.DetailPage details(
            ReportDashboards.Execution execution,
            ReportDashboards.Details request,
            ApplicationGate gate,
            long actor) {
        ReportDashboards.Chart chart = execution.chart();
        List<ReportDatasetQueries.Dimension> dimensions = chartDimensions(chart);
        validateDetails(chart, request);
        java.util.function.Function<
                        ReportDatasetAuthorizationService.Context, ReportDashboards.DetailPage>
                action =
                        access -> {
                            ResolvedQuery resolved =
                                    resolve(
                                            chartQuery(
                                                    chart,
                                                    dimensions,
                                                    adaptFilters(execution.filters(), access)),
                                            access);
                            List<ReportDatasetQueries.Dimension> projection = new ArrayList<>();
                            Set<String> ids = new LinkedHashSet<>();
                            dimensions.forEach(d -> ids.add(d.fieldId()));
                            resolved.query()
                                    .metrics()
                                    .forEach(
                                            m -> {
                                                if (m.fieldId() != null) ids.add(m.fieldId());
                                            });
                            ids.forEach(
                                    id ->
                                            projection.add(
                                                    new ReportDatasetQueries.Dimension(
                                                            id, "VALUE")));
                            // COUNT(*) 无字段时只返回记录身份；不扩大读取到未参与统计的字段。
                            ReportDatasetQueries.Query projected =
                                    new ReportDatasetQueries.Query(
                                            resolved.query().datasetId(),
                                            resolved.query().versionNo(),
                                            resolved.query().checksum(),
                                            false,
                                            projection,
                                            resolved.query().metrics(),
                                            Map.of(),
                                            100,
                                            resolved.query().timeZone(),
                                            null,
                                            resolved.query().filters());
                            Plan fields =
                                    prepareInteraction(projected, access, actor, false, execution);
                            ReportStatement statement =
                                    detailStatement(
                                            execution, request, resolved.query(), access, actor);
                            return selections.inDefinitions(
                                    fields.definitions(),
                                    () -> {
                                        try {
                                            com.fasterxml.jackson.databind.JsonNode raw =
                                                    json.readTree(
                                                            mapper.datasetDetails(
                                                                    new ReportDetailStatement(
                                                                            statement,
                                                                            fields.statement()
                                                                                    .dimensions(),
                                                                            (request.pageNo() - 1)
                                                                                    * request
                                                                                            .pageSize(),
                                                                            request.pageSize())));
                                            List<List<String>> values = new ArrayList<>();
                                            raw.path("rows")
                                                    .forEach(
                                                            row ->
                                                                    values.add(
                                                                            ReportDimensionLabels
                                                                                    .keys(
                                                                                            row
                                                                                                    .path(
                                                                                                            "values"))));
                                            List<List<String>> display = labels(fields, values);
                                            List<ReportDashboards.DetailRow> output =
                                                    new ArrayList<>();
                                            for (int i = 0; i < values.size(); i++)
                                                output.add(
                                                        new ReportDashboards.DetailRow(
                                                                raw.path("rows")
                                                                        .get(i)
                                                                        .path("id")
                                                                        .asText(),
                                                                values.get(i),
                                                                display.get(i)));
                                            List<ReportDashboards.DetailColumn> headers =
                                                    new ArrayList<>();
                                            for (int i = 0; i < projection.size(); i++)
                                                headers.add(
                                                        new ReportDashboards.DetailColumn(
                                                                projection.get(i).fieldId(),
                                                                fields.names().get(i)));
                                            return new ReportDashboards.DetailPage(
                                                    headers,
                                                    output,
                                                    raw.path("total").asLong(),
                                                    request.pageNo(),
                                                    request.pageSize());
                                        } catch (java.io.IOException error) {
                                            throw invalid("明细结果无法读取");
                                        }
                                    });
                        };
        if (gate != null)
            return authorization.withApplicationDashboardAccess(
                    execution.resolved(), gate, actor, action, false);
        if (execution.resolved() != null)
            return authorization.withDashboardDataAccess(execution.resolved(), actor, action);
        return authorization.withDataAccess(
                chart.dataset().id(),
                chart.dataset().versionNo(),
                chart.dataset().checksum(),
                false,
                actor,
                action);
    }

    /** 普通只读明细和业务明细必须共享行列键、路径及基础指标的唯一条件构造。 */
    private ReportStatement detailStatement(
            ReportDashboards.Execution execution,
            ReportDashboards.Details request,
            ReportDatasetQueries.Query query,
            ReportDatasetAuthorizationService.Context access,
            long actor) {
        Plan plan = prepareInteraction(query, access, actor, false, execution);
        Integer metric = null;
        if (request.metricId() != null) {
            for (int i = 0; i < plan.statement().metrics().size(); i++)
                if (plan.statement().metrics().get(i).id().equals(request.metricId())) metric = i;
            if (metric != null && plan.statement().metrics().get(metric).formula() != null)
                throw invalid("派生指标请分别查看其基础指标明细，避免混淆不同条件的记录范围");
        }
        return split(
                plan.statement(),
                execution.chart().dimensions().size(),
                ApplicationReports.Pivot.defaults(),
                request.group(),
                request.columnGroup(),
                metric);
    }

    private void validateDetails(ReportDashboards.Chart chart, ReportDashboards.Details request) {
        List<ReportDatasetQueries.Dimension> dimensions = chartDimensions(chart);
        if (request.pageNo() < 1
                || request.pageNo() > 100000
                || request.pageSize() < 1
                || request.pageSize() > 100
                || request.group() != null && request.group().size() > chart.dimensions().size()
                || request.columnGroup() != null
                        && request.columnGroup().size()
                                > dimensions.size() - chart.dimensions().size()
                || request.metricId() != null && !chart.metricIds().contains(request.metricId()))
            throw invalid("明细参数超出组件范围");
    }

    @Override
    public <T> T withBusinessDetails(
            ReportDashboards.Execution execution,
            ReportDashboards.Details request,
            ApplicationGate gate,
            String objectId,
            long actor,
            Function<ReportStatement, T> action) {
        if (execution == null
                || execution.resolved() == null
                || gate == null
                || execution.resolved().entry() != ReportDashboardEntryEnum.APPLICATION_FIXED)
            throw invalid("缺少可信应用业务明细入口");
        validateDetails(execution.chart(), request);
        return authorization.withApplicationDashboardAccess(
                execution.resolved(),
                gate,
                actor,
                access -> {
                    if (!Objects.equals(objectId, access.source().source().root().objectId()))
                        throw invalid("看板业务明细视图必须属于数据集根对象");
                    ResolvedQuery resolved =
                            resolve(
                                    chartQuery(
                                            execution.chart(),
                                            chartDimensions(execution.chart()),
                                            adaptFilters(execution.filters(), access)),
                                    access);
                    return action.apply(
                            detailStatement(execution, request, resolved.query(), access, actor));
                },
                false);
    }

    private record Node(
            String alias,
            DataCenter.Definition definition,
            RuntimeSchema.Table table,
            Set<String> fields) {}

    private record Selected(
            Node node, ReportDatasets.ResolvedField field, FieldDefinition definition) {}

    @Override
    public ApplicationReports.Result query(ReportDatasetQueries.Query request, long actor) {
        if (request == null
                || request.dimensions() == null
                || request.dimensions().size() > 3
                || request.metrics() != null
                        && (request.metrics().isEmpty() || request.metrics().size() > 10)
                || request.metricIds() != null
                        && (request.metricIds().isEmpty() || request.metricIds().size() > 10)
                || request.metrics() != null && request.metricIds() != null
                || request.equal() != null && request.equal().size() > 12
                || request.limit() < 1
                || request.limit() > 200) throw invalid("数据集查询参数超出范围");
        return authorization.withDataAccess(
                request.datasetId(),
                request.versionNo(),
                request.checksum(),
                request.preview(),
                actor,
                access -> {
                    ResolvedQuery resolved = resolve(request, access);
                    return project(execute(resolved.query(), access, actor), resolved.outputIds());
                });
    }

    /** 固定版本决定复用指标和默认时区；请求可选指标，不能重定义同身份口径或移除固定条件。 */
    private record ResolvedQuery(ReportDatasetQueries.Query query, List<String> outputIds) {}

    private ResolvedQuery resolve(
            ReportDatasetQueries.Query request, ReportDatasetAuthorizationService.Context access) {
        ReportDatasets.Analysis configuration = access.content().analysis();
        analysis.validate(configuration, access.source());
        analysis.validateFilters(request.filters(), access.source());
        Map<String, ReportDatasetQueries.Metric> saved = new LinkedHashMap<>();
        if (configuration != null)
            configuration.metrics().forEach(metric -> saved.put(metric.id(), metric));
        List<ReportDatasetQueries.Metric> metrics = request.metrics();
        if (request.metricIds() != null) {
            Set<String> unique = new HashSet<>();
            metrics = new ArrayList<>();
            for (String id : request.metricIds()) {
                if (!unique.add(id) || !saved.containsKey(id)) throw invalid("复用指标不存在或重复");
                metrics.add(saved.get(id));
            }
        } else if (metrics == null) metrics = List.copyOf(saved.values());
        else
            for (ReportDatasetQueries.Metric metric : metrics)
                if (metric != null
                        && saved.containsKey(metric.id())
                        && !saved.get(metric.id()).equals(metric))
                    throw invalid("不能重定义数据集复用指标，请使用 metricIds 引用");
        if (metrics.isEmpty()) throw invalid("请选择至少一个指标");
        if (metrics.stream().anyMatch(Objects::isNull)) throw invalid("指标不能为空");
        List<String> outputIds = metrics.stream().map(ReportDatasetQueries.Metric::id).toList();
        if (new HashSet<>(outputIds).size() != outputIds.size()) throw invalid("指标标识重复");
        Map<String, ReportDatasetQueries.Metric> available = new LinkedHashMap<>(saved);
        metrics.forEach(metric -> available.put(metric.id(), metric));
        Map<String, ReportDatasetQueries.Metric> required = new LinkedHashMap<>();
        for (String id : outputIds) collectMetric(id, available, required);
        metrics = List.copyOf(required.values());
        analysis.validateMetrics(metrics, access.source());
        String timeZone = request.timeZone();
        if (configuration != null) {
            if (timeZone != null && !configuration.timeZone().equals(timeZone))
                throw invalid("查询时区必须与数据集定义一致");
            timeZone = configuration.timeZone();
        }
        analysis.timeZone(timeZone);
        return new ResolvedQuery(
                new ReportDatasetQueries.Query(
                        request.datasetId(),
                        request.versionNo(),
                        request.checksum(),
                        request.preview(),
                        request.dimensions(),
                        metrics,
                        request.equal(),
                        request.limit(),
                        timeZone,
                        null,
                        request.filters()),
                outputIds);
    }

    private void collectMetric(
            String id,
            Map<String, ReportDatasetQueries.Metric> available,
            Map<String, ReportDatasetQueries.Metric> required) {
        ReportDatasetQueries.Metric metric = available.get(id);
        if (metric == null) throw invalid("派生指标引用不存在：" + id);
        if (required.putIfAbsent(id, metric) != null) return;
        if (metric.formula() != null) {
            collectMetric(metric.formula().left(), available, required);
            collectMetric(metric.formula().right(), available, required);
        }
    }

    /** 隐藏依赖仍参与权限检查与总计重算，响应仅返回调用方选择的指标。 */
    private ApplicationReports.Result project(ApplicationReports.Result result, List<String> ids) {
        Map<String, ApplicationReports.Metric> metrics = new HashMap<>();
        result.metrics().forEach(metric -> metrics.put(metric.id(), metric));
        return new ApplicationReports.Result(
                result.dimensionNames(),
                ids.stream().map(metrics::get).toList(),
                result.groups().stream()
                        .map(
                                group ->
                                        new ApplicationReports.Group(
                                                group.keys(),
                                                group.labels(),
                                                projectValues(group.values(), ids)))
                        .toList(),
                projectValues(result.totals(), ids),
                result.totalGroups(),
                result.recordCount(),
                result.canExport(),
                result.timeZone());
    }

    private Map<String, String> projectValues(Map<String, String> values, List<String> ids) {
        Map<String, String> projected = new LinkedHashMap<>();
        ids.forEach(id -> projected.put(id, values.get(id)));
        return projected;
    }

    private record Plan(
            ReportStatement statement,
            List<String> names,
            List<FieldDefinition> fields,
            List<DataCenter.Definition> owners,
            List<ReportStatement.Dimension> titles,
            Map<String, DataCenter.Definition> definitions) {}

    private ApplicationReports.Result execute(
            ReportDatasetQueries.Query request,
            ReportDatasetAuthorizationService.Context access,
            long actor) {
        Plan plan = prepare(request, access, actor, false);
        return selections.inDefinitions(
                plan.definitions(),
                () ->
                        results.read(
                                plan.statement(),
                                plan.names(),
                                plan.statement().metrics(),
                                request.timeZone(),
                                () -> false,
                                keys -> labels(plan, keys)));
    }

    /** 只读取数据集已登记的标题字段，避免为历史版本引入未登记的隐式依赖。 */
    private List<List<String>> labels(Plan plan, List<List<String>> keys) {
        List<List<String>> labels =
                ReportDimensionLabels.labels(selections, plan.fields(), plan.owners(), keys);
        for (int i = 0; i < plan.titles().size(); i++) {
            if (plan.titles().get(i) == null) continue;
            Set<String> values = new LinkedHashSet<>();
            for (List<String> row : keys) if (row.get(i) != null) values.add(row.get(i));
            if (values.isEmpty()) continue;
            try {
                com.fasterxml.jackson.databind.JsonNode raw =
                        json.readTree(
                                mapper.options(
                                        new ReportOptionsStatement(
                                                plan.statement(),
                                                plan.statement().dimensions().get(i),
                                                plan.titles().get(i),
                                                List.copyOf(values),
                                                "",
                                                List.of(),
                                                0,
                                                values.size())));
                Map<String, String> titles = new HashMap<>();
                for (com.fasterxml.jackson.databind.JsonNode item : raw.path("items"))
                    titles.put(item.path("value").asText(), title(item.path("label")));
                for (int row = 0; row < keys.size(); row++) {
                    String key = keys.get(row).get(i);
                    if (key != null) labels.get(row).set(i, titles.getOrDefault(key, "已失效或无权限的记录"));
                }
            } catch (java.io.IOException error) {
                throw invalid("关联标题结果无法读取");
            }
        }
        return labels;
    }

    private String title(com.fasterxml.jackson.databind.JsonNode value) {
        return value.isNull() || value.asText().isBlank() ? "未填写标题" : value.asText();
    }

    @Override
    public ReportDatasetQueries.OptionPage options(
            ReportDatasetQueries.Options request, long actor) {
        if (request == null
                || request.fieldId() == null
                || request.pageNo() < 1
                || request.pageNo() > 10000
                || request.pageSize() < 1
                || request.pageSize() > 100
                || request.search() != null && request.search().length() > 100)
            throw invalid("候选查询参数超出范围");
        return authorization.withDataAccess(
                request.datasetId(),
                request.versionNo(),
                request.checksum(),
                request.preview(),
                actor,
                access -> {
                    analysis.validate(access.content().analysis(), access.source());
                    DataScope candidateFilters =
                            withoutField(request.filters(), request.fieldId(), 0, new int[] {0});
                    analysis.validateFilters(candidateFilters, access.source());
                    String zone =
                            access.content().analysis() == null
                                    ? "Asia/Shanghai"
                                    : access.content().analysis().timeZone();
                    ReportDatasetQueries.Query query =
                            new ReportDatasetQueries.Query(
                                    request.datasetId(),
                                    request.versionNo(),
                                    request.checksum(),
                                    request.preview(),
                                    List.of(
                                            new ReportDatasetQueries.Dimension(
                                                    request.fieldId(), "VALUE")),
                                    List.of(),
                                    Map.of(),
                                    request.pageSize(),
                                    zone,
                                    null,
                                    candidateFilters);
                    Plan plan = prepare(query, access, actor, true);
                    return selections.inDefinitions(
                            plan.definitions(), () -> optionPage(plan, request));
                });
    }

    /** 仪表板已按筛选身份移除自身条件，不能再按字段移除同字段联动或其他筛选。 */
    @Override
    public ReportDatasetQueries.OptionPage options(
            ReportDashboards.Execution execution,
            String fieldId,
            int pageNo,
            int pageSize,
            String search,
            long actor) {
        return options(execution, fieldId, pageNo, pageSize, search, null, actor);
    }

    @Override
    public ReportDatasetQueries.OptionPage options(
            ReportDashboards.Execution execution,
            String fieldId,
            int pageNo,
            int pageSize,
            String search,
            ApplicationGate gate,
            long actor) {
        ReportDashboards.Chart chart = execution.chart();
        ReportDatasetQueries.Options request =
                new ReportDatasetQueries.Options(
                        chart.dataset().id(),
                        chart.dataset().versionNo(),
                        chart.dataset().checksum(),
                        false,
                        fieldId,
                        execution.filters(),
                        pageNo,
                        pageSize,
                        search);
        if (pageNo < 1
                || pageNo > 10000
                || pageSize < 1
                || pageSize > 100
                || search != null && search.length() > 100) throw invalid("候选查询参数超出范围");
        java.util.function.Function<
                        ReportDatasetAuthorizationService.Context, ReportDatasetQueries.OptionPage>
                action =
                        access -> {
                            analysis.validate(access.content().analysis(), access.source());
                            DataScope filters = adaptFilters(execution.filters(), access);
                            analysis.validateFilters(filters, access.source());
                            String zone =
                                    access.content().analysis() == null
                                            ? "Asia/Shanghai"
                                            : access.content().analysis().timeZone();
                            ReportDatasetQueries.Query query =
                                    new ReportDatasetQueries.Query(
                                            request.datasetId(),
                                            request.versionNo(),
                                            request.checksum(),
                                            false,
                                            List.of(
                                                    new ReportDatasetQueries.Dimension(
                                                            fieldId, "VALUE")),
                                            List.of(),
                                            Map.of(),
                                            pageSize,
                                            zone,
                                            null,
                                            filters);
                            Plan plan = prepareInteraction(query, access, actor, false, execution);
                            return selections.inDefinitions(
                                    plan.definitions(), () -> optionPage(plan, request));
                        };
        if (gate != null)
            return authorization.withApplicationDashboardAccess(
                    execution.resolved(), gate, actor, action, false);
        if (execution.resolved() != null)
            return authorization.withDashboardDataAccess(execution.resolved(), actor, action);
        return authorization.withDataAccess(
                request.datasetId(), request.versionNo(), request.checksum(), false, actor, action);
    }

    /** 只删除自身临时条件，递归移除空组；固定条件在 prepare 中独立叠加。 */
    private DataScope withoutField(DataScope scope, String id, int depth, int[] count) {
        if (scope == null) return null;
        count[0] += scope.conditions().size();
        if (depth > 4
                || count[0] > 50
                || !Set.of("AND", "OR").contains(Objects.toString(scope.logic(), ""))
                || scope.conditions().isEmpty() && scope.groups().isEmpty())
            throw invalid("候选筛选条件超出范围或为空");
        List<DataScope.Condition> conditions =
                scope.conditions().stream().filter(c -> !id.equals(c.fieldId())).toList();
        List<DataScope> groups =
                scope.groups().stream()
                        .map(g -> withoutField(g, id, depth + 1, count))
                        .filter(Objects::nonNull)
                        .toList();
        return conditions.isEmpty() && groups.isEmpty()
                ? null
                : new DataScope(scope.logic(), conditions, groups);
    }

    private ReportDatasetQueries.OptionPage optionPage(
            Plan plan, ReportDatasetQueries.Options request) {
        FieldDefinition field = plan.fields().getFirst();
        DataCenter.FieldOptions options =
                plan.owners()
                        .getFirst()
                        .fieldOptions()
                        .getOrDefault(field.id(), DataCenter.FieldOptions.defaults());
        List<String> searchKeys = new ArrayList<>();
        String search = request.search() == null ? "" : request.search().trim();
        if (!search.isEmpty() && SelectionFields.source(field, options) != null) {
            for (SelectionFields.Option option : selections.options(field, options))
                if (option.label()
                        .toLowerCase(Locale.ROOT)
                        .contains(search.toLowerCase(Locale.ROOT))) searchKeys.add(option.value());
        }
        if (FieldTypeEnum.BOOLEAN.matches(field.type()) && !search.isEmpty()) {
            if ("是".contains(search)) searchKeys.add("true");
            if ("否".contains(search)) searchKeys.add("false");
        }
        try {
            com.fasterxml.jackson.databind.JsonNode raw =
                    json.readTree(
                            mapper.options(
                                    new ReportOptionsStatement(
                                            plan.statement(),
                                            plan.statement().dimensions().getFirst(),
                                            plan.titles().getFirst(),
                                            null,
                                            search,
                                            searchKeys,
                                            (request.pageNo() - 1) * request.pageSize(),
                                            request.pageSize())));
            List<List<String>> keys = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode item : raw.path("items")) {
                com.fasterxml.jackson.databind.JsonNode value = item.path("value");
                keys.add(Collections.singletonList(value.isNull() ? null : value.asText()));
            }
            List<List<String>> labels =
                    ReportDimensionLabels.labels(selections, plan.fields(), plan.owners(), keys);
            List<ReportDatasetQueries.Option> items = new ArrayList<>();
            for (int i = 0; i < keys.size(); i++)
                items.add(
                        new ReportDatasetQueries.Option(
                                keys.get(i).getFirst(),
                                plan.titles().getFirst() != null && keys.get(i).getFirst() != null
                                        ? title(raw.path("items").get(i).path("label"))
                                        : labels.get(i).getFirst()));
            return new ReportDatasetQueries.OptionPage(
                    items, raw.path("total").asLong(), request.pageNo(), request.pageSize());
        } catch (java.io.IOException error) {
            throw invalid("候选结果无法读取");
        }
    }

    private Plan prepare(
            ReportDatasetQueries.Query request,
            ReportDatasetAuthorizationService.Context access,
            long actor,
            boolean candidate) {
        return prepare(request, access, actor, candidate, false);
    }

    private Plan prepare(
            ReportDatasetQueries.Query request,
            ReportDatasetAuthorizationService.Context access,
            long actor,
            boolean candidate,
            boolean exporting) {
        return prepare(request, access, actor, candidate, exporting, false);
    }

    private Plan prepare(
            ReportDatasetQueries.Query request,
            ReportDatasetAuthorizationService.Context access,
            long actor,
            boolean candidate,
            boolean exporting,
            boolean multipleBuckets) {
        Map<String, DataCenter.Definition> definitions = new LinkedHashMap<>();
        for (ReportDatasets.ObjectReference reference : access.source().objects())
            definitions.put(
                    reference.objectId(),
                    objects.getVersion(reference.objectId(), reference.versionNo()).definition());
        Map<String, Node> nodes = new LinkedHashMap<>();
        Node root = node("t", definitions.get(access.source().source().root().objectId()));
        nodes.put("", root);
        List<ReportStatement.Join> joins = new ArrayList<>();
        Map<String, Node> relationTargets = new HashMap<>();
        for (ReportDatasets.Relation reference :
                access.source().source().relations().stream()
                        .sorted(Comparator.comparingInt(value -> value.parentPath().size()))
                        .toList()) {
            Node parent = nodes.get(String.join("/", reference.parentPath()));
            DataCenter.Relation relation =
                    parent.definition().relations().stream()
                            .filter(value -> value.id().equals(reference.relationId()))
                            .findFirst()
                            .orElseThrow();
            parent.fields().add(relation.fieldId());
            Node child = node("r" + joins.size(), definitions.get(reference.target().objectId()));
            if (relation.targetFieldId() != null && !relation.targetFieldId().isBlank())
                child.fields().add(relation.targetFieldId());
            List<String> path = new ArrayList<>(reference.parentPath());
            path.add(reference.id());
            nodes.put(String.join("/", path), child);
            relationTargets.put(parent.alias() + "/" + relation.fieldId(), child);
            joins.add(
                    new ReportStatement.Join(
                            child.table().schema(),
                            child.table().name(),
                            child.table().key().name(),
                            parent.alias(),
                            column(parent, relation.fieldId()),
                            null,
                            child.table().physical().columns().stream()
                                    .anyMatch(c -> c.name().equals("deleted"))));
        }
        Map<String, Selected> selected = new HashMap<>();
        for (int i = 0; i < access.source().fields().size(); i++) {
            ReportDatasets.ResolvedField field = access.source().fields().get(i);
            Node owner =
                    nodes.get(String.join("/", access.source().source().fields().get(i).path()));
            FieldDefinition definition =
                    owner.definition().fields().stream()
                            .filter(value -> value.id().equals(field.sourceFieldId()))
                            .findFirst()
                            .orElseThrow();
            selected.put(
                    field.id(),
                    new Selected(
                            owner,
                            field,
                            OrderedCalculations.queryField(
                                    definition,
                                    owner.definition().fieldOptions().get(definition.id()))));
        }
        List<ReportStatement.Dimension> dimensions = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<FieldDefinition> labelFields = new ArrayList<>();
        List<DataCenter.Definition> labelOwners = new ArrayList<>();
        List<ReportStatement.Dimension> titles = new ArrayList<>();
        Set<String> dimensionIds = new HashSet<>();
        for (ReportDatasetQueries.Dimension dimension : request.dimensions()) {
            if (dimension == null
                    || !dimensionIds.add(
                            dimension.fieldId()
                                    + (multipleBuckets ? "/" + dimension.bucket() : "")))
                throw invalid("分组维度为空或重复");
            Selected field = select(selected, dimension.fieldId());
            if (!candidate && !ReportDatasetFieldRoleEnum.DIMENSION.matches(field.field().role()))
                throw invalid("分组字段必须是数据集维度");
            ReportBucketEnum bucket = ReportBucketEnum.fromCode(dimension.bucket());
            if (bucket != ReportBucketEnum.VALUE
                    && !Set.of("DATE", "DATETIME").contains(field.field().type()))
                throw invalid("日期分组需要日期字段");
            dimensions.add(
                    new ReportStatement.Dimension(
                            field.node().alias(),
                            column(field.node(), field.field().sourceFieldId()),
                            bucket,
                            withZone(field),
                            FieldTypeEnum.fromCode(field.field().type()).isNumeric()));
            names.add(field.field().name());
            labelFields.add(field.definition());
            labelOwners.add(field.node().definition());
            Node target =
                    relationTargets.get(field.node().alias() + "/" + field.field().sourceFieldId());
            Selected title =
                    target == null
                            ? null
                            : selected.values().stream()
                                    .filter(
                                            value ->
                                                    value.node() == target
                                                            && value.field()
                                                                    .sourceFieldId()
                                                                    .equals(
                                                                            target.definition()
                                                                                    .titleFieldId())
                                                            && Set.of(
                                                                            FieldTypeEnum.TEXT,
                                                                            FieldTypeEnum.TEXTAREA)
                                                                    .contains(
                                                                            FieldTypeEnum.fromCode(
                                                                                    value.definition()
                                                                                            .type())))
                                    .findFirst()
                                    .orElse(null);
            if (title == null) titles.add(null);
            else {
                target.fields().add(title.field().sourceFieldId());
                titles.add(
                        new ReportStatement.Dimension(
                                target.alias(),
                                column(target, title.field().sourceFieldId()),
                                ReportBucketEnum.VALUE,
                                false,
                                false));
            }
        }
        List<ApplicationReports.Metric> metrics = new ArrayList<>();
        List<String> metricColumns = new ArrayList<>();
        for (ReportDatasetQueries.Metric metric : request.metrics()) {
            ReportOperationEnum operation = ReportOperationEnum.fromCode(metric.operation());
            if (operation == ReportOperationEnum.COUNT || operation == ReportOperationEnum.FORMULA)
                metricColumns.add(null);
            else {
                Selected field = select(selected, metric.fieldId());
                metricColumns.add(column(root, field.field().sourceFieldId()));
            }
            ApplicationReports.Format format = metric.format();
            if (format == null && metric.fieldId() != null && access.content().analysis() != null)
                format = access.content().analysis().fieldFormats().get(metric.fieldId());
            metrics.add(
                    new ApplicationReports.Metric(
                            metric.id(),
                            metric.name(),
                            operation.getCode(),
                            metric.fieldId(),
                            null,
                            metric.formula(),
                            format));
        }
        List<ReportStatement.Filter> filters = new ArrayList<>();
        if (request.equal() != null)
            for (Map.Entry<String, Object> entry : request.equal().entrySet()) {
                Selected field = select(selected, entry.getKey());
                String column = column(field.node(), field.field().sourceFieldId());
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put(
                        column,
                        values.convert(
                                field.definition(),
                                field.node()
                                        .table()
                                        .options()
                                        .getOrDefault(
                                                field.field().sourceFieldId(),
                                                DataCenter.FieldOptions.defaults()),
                                entry.getValue()));
                try {
                    filters.add(
                            new ReportStatement.Filter(
                                    field.node().alias(),
                                    field.node().table().schema(),
                                    field.node().table().name(),
                                    column,
                                    json.writeValueAsString(payload)));
                } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                    throw invalid("筛选值无法编码");
                }
            }
        DataScope fixed =
                access.content().analysis() == null
                        ? null
                        : access.content().analysis().fixedConditions();
        // 即使条件字段未展示，也必须先加入查询字段集合，再按行列绑定规则计算数据权限。
        conditionFields(fixed, selected);
        conditionFields(request.filters(), selected);
        request.metrics().forEach(metric -> conditionFields(metric.conditions(), selected));
        Map<String, QueryWrapper<Object>> predicates = new LinkedHashMap<>();
        RecordStatement base = root.table().statement(null, null, Long.toString(actor), false);
        for (Node owner : nodes.values()) {
            String parameter =
                    owner == root ? "dynamicQuery" : "predicates.access_" + owner.alias();
            QueryWrapper<Object> predicate =
                    conditions.compile(
                            owner.definition(),
                            owner.table(),
                            access.grants().get(owner.definition().objectId()),
                            owner.fields(),
                            access.scopeContext(),
                            actor,
                            owner.alias(),
                            parameter,
                            exporting,
                            access.applicationGrants() == null
                                    ? null
                                    : access.applicationGrants()
                                            .getOrDefault(
                                                    owner.definition().objectId(), List.of()));
            if (owner == root) {
                fixedConditions(predicate, fixed, selected);
                fixedConditions(predicate, request.filters(), selected);
                base = base.conditions(predicate);
            } else predicates.put("access_" + owner.alias(), predicate);
        }
        for (int i = 0; i < request.metrics().size(); i++) {
            DataScope scope = request.metrics().get(i).conditions();
            if (scope != null) {
                QueryWrapper<Object> predicate = new QueryWrapper<>();
                predicate.setParamAlias("predicates.m" + i);
                fixedConditions(predicate, scope, selected);
                predicates.put("m" + i, predicate);
            }
        }
        ReportStatement statement =
                new ReportStatement(
                        base,
                        dimensions,
                        joins,
                        filters,
                        metrics,
                        metricColumns,
                        null,
                        false,
                        request.timeZone(),
                        null,
                        null,
                        null,
                        request.limit(),
                        null,
                        false,
                        predicates,
                        null);
        return new Plan(statement, names, labelFields, labelOwners, titles, definitions);
    }

    private Node node(String alias, DataCenter.Definition definition) {
        return new Node(alias, definition, schemas.main(definition), new LinkedHashSet<>());
    }

    private void conditionFields(DataScope scope, Map<String, Selected> selected) {
        if (scope == null) return;
        scope.conditions().forEach(condition -> select(selected, condition.fieldId()));
        scope.groups().forEach(group -> conditionFields(group, selected));
    }

    /** 保留跨关系的 AND/OR 分组，在根 WHERE 中求值；JOIN 中的权限裁剪仍独立生效。 */
    private void fixedConditions(
            QueryWrapper<Object> where, DataScope scope, Map<String, Selected> selected) {
        if (scope == null) return;
        where.nested(
                group -> {
                    boolean first = true;
                    for (DataScope.Condition condition : scope.conditions()) {
                        if (!first && "OR".equals(scope.logic())) group.or();
                        first = false;
                        Selected field = selected.get(condition.fieldId());
                        scopes.append(
                                group,
                                new DataScope(
                                        "AND",
                                        List.of(
                                                new DataScope.Condition(
                                                        field.field().sourceFieldId(),
                                                        condition.operator(),
                                                        condition.value())),
                                        List.of()),
                                field.node().definition(),
                                field.node().table(),
                                Map.of(),
                                field.node().alias());
                    }
                    for (DataScope nested : scope.groups()) {
                        if (!first && "OR".equals(scope.logic())) group.or();
                        first = false;
                        fixedConditions(group, nested, selected);
                    }
                });
    }

    private Selected select(Map<String, Selected> fields, String id) {
        Selected field = fields.get(id);
        if (field == null) throw invalid("字段不属于数据集：" + id);
        field.node().fields().add(field.field().sourceFieldId());
        return field;
    }

    private String column(Node node, String fieldId) {
        String column = node.table().columns().get(fieldId);
        if (column == null) throw invalid("数据集字段未映射业务列");
        return column;
    }

    private boolean withZone(Selected field) {
        String column = column(field.node(), field.field().sourceFieldId());
        return field.node().table().physical().columns().stream()
                .anyMatch(
                        value ->
                                value.name().equals(column)
                                        && value.nativeType().contains("with time zone"));
    }
}
