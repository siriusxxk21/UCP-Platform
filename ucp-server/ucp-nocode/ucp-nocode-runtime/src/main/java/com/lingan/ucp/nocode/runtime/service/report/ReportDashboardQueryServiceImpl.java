package com.lingan.ucp.nocode.runtime.service.report;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.report.service.authorization.ReportDatasetAuthorizationService.ApplicationGate;
import com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardService;
import com.lingan.ucp.nocode.runtime.dal.query.ReportStatement;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.function.Function;

/** 交互请求只有配置身份及原键，字段、数据集、算子与当前层维度均由服务端还原。 */
@Service
public class ReportDashboardQueryServiceImpl implements ReportDashboardQueryService {
    @Resource private ReportDashboardService dashboards;
    @Resource private ReportDatasetQueryService datasets;

    @Override
    public ApplicationReports.Result fixed(
            ReportDashboards.Resolved resolved,
            List<BoundInput> inputs,
            ApplicationGate gate,
            boolean exporting,
            long actor) {
        requireFixed(resolved, gate);
        ApplicationReports.Result result =
                datasets.chart(
                        execution(resolved, null, inputs, gate, actor), gate, exporting, actor);
        recheckSources(resolved, gate, actor);
        dashboards.recheck(resolved, actor, exporting);
        gate.recheck(actor);
        return result;
    }

    @Override
    public ReportDashboards.DetailPage fixedDetails(
            ReportDashboards.Resolved resolved,
            ReportDashboards.Details request,
            List<BoundInput> inputs,
            ApplicationGate gate,
            long actor) {
        requireFixed(resolved, gate);
        ReportDashboards.DetailPage result =
                datasets.details(
                        execution(resolved, null, inputs, gate, actor), request, gate, actor);
        recheckSources(resolved, gate, actor);
        dashboards.recheck(resolved, actor);
        gate.recheck(actor);
        return result;
    }

    @Override
    public <T> T withFixedBusinessDetails(
            ReportDashboards.Resolved resolved,
            ReportDashboards.Details request,
            List<BoundInput> inputs,
            ApplicationGate gate,
            String objectId,
            long actor,
            Function<ReportStatement, T> action) {
        requireFixed(resolved, gate);
        T result =
                datasets.withBusinessDetails(
                        execution(resolved, null, inputs, gate, actor),
                        request,
                        gate,
                        objectId,
                        actor,
                        action);
        recheckSources(resolved, gate, actor);
        dashboards.recheck(resolved, actor);
        gate.recheck(actor);
        return result;
    }

    @Override
    public ReportDatasetQueries.OptionPage fixedOptions(
            ReportDashboards.Resolved resolved,
            ReportDashboards.Options request,
            List<BoundInput> inputs,
            ApplicationGate gate,
            long actor) {
        requireFixed(resolved, gate);
        return options(resolved, request, inputs, gate, actor);
    }

    private void requireFixed(ReportDashboards.Resolved resolved, ApplicationGate gate) {
        if (resolved == null
                || resolved.entry() != ReportDashboardEntryEnum.APPLICATION_FIXED
                || gate == null) throw invalid("缺少可信应用固定入口");
    }

    @Override
    public ApplicationReports.Result previewChart(ReportDashboards.Chart chart, long actor) {
        ReportDashboards.Chart validated = dashboards.previewChart(chart, actor);
        // 无可信看板上下文，执行器走数据集 USE 与三层数据权限，并在交付前复核。
        return datasets.chart(validated, false, actor);
    }

    @Override
    public ApplicationReports.Result query(ReportDashboards.Query request, long actor) {
        return result(request, actor, false);
    }

    @Override
    public ApplicationReports.Result export(ReportDashboards.Query request, long actor) {
        return result(request, actor, true);
    }

    private ApplicationReports.Result result(
            ReportDashboards.Query request, long actor, boolean exporting) {
        ReportDashboards.Resolved resolved = dashboards.resolve(request, actor, exporting);
        ApplicationReports.Result result =
                datasets.chart(execution(resolved, null, actor), exporting, actor);
        recheckSources(resolved, actor);
        dashboards.recheck(resolved, actor, exporting);
        return result;
    }

    @Override
    public ReportDashboards.DetailPage details(ReportDashboards.Details request, long actor) {
        if (request == null) throw invalid("缺少明细查询参数");
        ReportDashboards.Resolved resolved = dashboards.resolve(request.query(), actor);
        ReportDashboards.DetailPage result =
                datasets.details(execution(resolved, null, actor), request, actor);
        recheckSources(resolved, actor);
        dashboards.recheck(resolved, actor);
        return result;
    }

    @Override
    public ReportDatasetQueries.OptionPage options(ReportDashboards.Options request, long actor) {
        if (request == null || request.query() == null) throw invalid("缺少公共筛选候选参数");
        ReportDashboards.Resolved original = dashboards.resolve(request.query(), actor);
        return options(original, request, List.of(), null, actor);
    }

    private ReportDatasetQueries.OptionPage options(
            ReportDashboards.Resolved original,
            ReportDashboards.Options request,
            List<BoundInput> inputs,
            ApplicationGate gate,
            long actor) {
        ReportDashboards.Filter filter =
                filters(original.content()).stream()
                        .filter(f -> f.id().equals(request.filterId()))
                        .findFirst()
                        .orElseThrow(() -> invalid("公共筛选不属于仪表板"));
        ReportDashboardFilterKindEnum kind = ReportDashboardFilterKindEnum.fromCode(filter.kind());
        if (kind != ReportDashboardFilterKindEnum.SELECT
                && kind != ReportDashboardFilterKindEnum.MULTISELECT) throw invalid("仅选择筛选支持候选查询");
        ReportDashboards.Mapping mapping = filter.mappings().getFirst();
        ReportDashboards.Query query = request.query();
        if (!mapping.chartId().equals(query.chartId())
                && query.drillPath() != null
                && !query.drillPath().isEmpty()) throw invalid("候选钻取路径必须属于首个映射组件");
        ReportDashboards.Query candidate =
                new ReportDashboards.Query(
                        query.id(),
                        mapping.chartId(),
                        query.preview(),
                        query.versionNo(),
                        query.checksum(),
                        query.filterValues(),
                        query.selections(),
                        query.drillPath());
        ReportDashboards.Resolved resolved =
                gate == null
                        ? dashboards.resolve(candidate, actor)
                        : dashboards.resolveFixed(candidate, actor, false);
        ReportDatasetQueries.OptionPage result =
                datasets.options(
                        execution(resolved, filter.id(), inputs, gate, actor),
                        mapping.fieldId(),
                        request.pageNo(),
                        request.pageSize(),
                        request.search(),
                        gate,
                        actor);
        recheckSources(resolved, gate, actor);
        dashboards.recheck(original, actor);
        dashboards.recheck(resolved, actor);
        if (gate != null) gate.recheck(actor);
        return result;
    }

    private void recheckSources(ReportDashboards.Resolved resolved, long actor) {
        recheckSources(resolved, null, actor);
    }

    private void recheckSources(
            ReportDashboards.Resolved resolved, ApplicationGate gate, long actor) {
        if (resolved.request().selections() == null) return;
        for (ReportDashboards.Selection selection : resolved.request().selections()) {
            ReportDashboards.Chart source =
                    resolved.content().charts().stream()
                            .filter(chart -> chart.id().equals(selection.chartId()))
                            .findFirst()
                            .orElseThrow();
            datasets.checkChartAccess(sourceResolved(resolved, source), gate, actor);
        }
    }

    private ReportDashboards.Resolved sourceResolved(
            ReportDashboards.Resolved board, ReportDashboards.Chart source) {
        ReportDashboards.Query query = board.request();
        return new ReportDashboards.Resolved(
                new ReportDashboards.Query(
                        query.id(),
                        source.id(),
                        query.preview(),
                        query.versionNo(),
                        query.checksum()),
                board.revision(),
                board.stamp(),
                source,
                board.content(),
                board.entry());
    }

    private List<ReportDashboards.Filter> filters(ReportDashboards.Content content) {
        return content.filters() == null ? List.of() : content.filters();
    }

    /** 平铺交集根节点避免按操作次数嵌套范围；每个多选内部的 OR 保留空值语义。 */
    private ReportDashboards.Execution execution(
            ReportDashboards.Resolved resolved, String excludedFilter, long actor) {
        return execution(resolved, excludedFilter, List.of(), null, actor);
    }

    private ReportDashboards.Execution execution(
            ReportDashboards.Resolved resolved,
            String excludedFilter,
            List<BoundInput> inputs,
            ApplicationGate gate,
            long actor) {
        ReportDashboards.Query request = resolved.request();
        ReportDashboards.Chart chart = resolved.chart();
        Map<String, ReportDashboards.Filter> definitions = new LinkedHashMap<>();
        filters(resolved.content()).forEach(filter -> definitions.put(filter.id(), filter));
        List<ReportDashboards.FilterValue> values =
                request.filterValues() == null ? List.of() : request.filterValues();
        if (values.size() > 10) throw invalid("公共筛选输入最多十项");
        Set<String> unique = new HashSet<>();
        List<DataScope> scopes = new ArrayList<>();
        List<ReportDashboards.TextFilter> text = new ArrayList<>();
        Set<String> bound = new HashSet<>();
        for (BoundInput input : inputs) {
            if (input == null || input.value() == null || !bound.add(input.value().filterId()))
                throw invalid("固定输入筛选重复或不存在");
            ReportDashboards.Filter definition = definitions.get(input.value().filterId());
            if (definition == null) throw invalid("固定输入筛选不属于看板");
            ReportDashboardFilterKindEnum kind =
                    ReportDashboardFilterKindEnum.fromCode(definition.kind());
            validateValue(input.value(), kind);
            for (ReportDashboards.Mapping mapping : definition.mappings()) {
                if (!mapping.chartId().equals(chart.id())) continue;
                if (input.exact()) {
                    if (input.value().values() == null || input.value().values().size() != 1)
                        throw invalid("记录输入必须包含一个原键");
                    scopes.add(equal(mapping.fieldId(), input.value().values().getFirst()));
                } else appendFilter(mapping.fieldId(), input.value(), kind, scopes, text);
            }
        }
        for (ReportDashboards.FilterValue value : values) {
            if (value == null
                    || !unique.add(value.filterId())
                    || !definitions.containsKey(value.filterId())) throw invalid("公共筛选身份不存在或重复");
            if (bound.contains(value.filterId())) throw invalid("用户筛选不能覆盖固定绑定输入");
            ReportDashboards.Filter definition = definitions.get(value.filterId());
            ReportDashboardFilterKindEnum kind =
                    ReportDashboardFilterKindEnum.fromCode(definition.kind());
            validateValue(value, kind);
            if (definition.id().equals(excludedFilter)) continue;
            for (ReportDashboards.Mapping mapping : definition.mappings()) {
                if (!mapping.chartId().equals(chart.id())) continue;
                appendFilter(mapping.fieldId(), value, kind, scopes, text);
            }
        }
        Map<String, ReportDashboards.Chart> charts = new LinkedHashMap<>();
        resolved.content().charts().forEach(c -> charts.put(c.id(), c));
        List<ReportDashboards.Selection> selections =
                request.selections() == null ? List.of() : request.selections();
        if (selections.size() > 30) throw invalid("联动来源最多三十项");
        unique.clear();
        for (ReportDashboards.Selection selection : selections) {
            if (selection == null
                    || !unique.add(selection.chartId())
                    || !charts.containsKey(selection.chartId())) throw invalid("联动来源不存在或重复");
            ReportDashboards.Chart source = charts.get(selection.chartId());
            if (source.links() == null
                    || source.links().isEmpty()
                    || selection.group() == null
                    || selection.group().size() != source.dimensions().size())
                throw invalid("联动需要来源组件的完整基础分组原键");
            selection.group().forEach(this::key);
            // 任一来源撤权后不能继续以它的值驱动其他数据集；不把客户端原键作为授权证明。
            datasets.checkChartAccess(sourceResolved(resolved, source), gate, actor);
            for (ReportDashboards.Link link : source.links()) {
                if (!link.targetChartId().equals(chart.id())) continue;
                int index = -1;
                for (int i = 0; i < source.dimensions().size(); i++)
                    if (source.dimensions().get(i).fieldId().equals(link.sourceFieldId())
                            && ReportBucketEnum.VALUE.matches(source.dimensions().get(i).bucket()))
                        index = i;
                if (index < 0) throw invalid("联动来源字段不属于基础原值维度");
                scopes.add(equal(link.targetFieldId(), selection.group().get(index)));
            }
        }
        List<String> path = request.drillPath() == null ? List.of() : request.drillPath();
        List<ReportDatasetQueries.Dimension> levels = new ArrayList<>(chart.dimensions());
        if (chart.drillDimensions() != null) levels.addAll(chart.drillDimensions());
        if (!path.isEmpty()
                && (chart.dimensions().size() != 1
                        || chart.drillDimensions() == null
                        || path.size() > chart.drillDimensions().size()))
            throw invalid("钻取路径超出组件配置层级");
        path.forEach(this::key);
        List<ReportDatasetQueries.Dimension> ancestors = List.of();
        if (!path.isEmpty()) {
            ancestors = List.copyOf(levels.subList(0, path.size()));
            chart =
                    new ReportDashboards.Chart(
                            chart.id(),
                            chart.title(),
                            chart.display(),
                            chart.dataset(),
                            List.of(levels.get(path.size())),
                            chart.metricIds(),
                            chart.x(),
                            chart.y(),
                            chart.w(),
                            chart.h(),
                            chart.columnDimensions(),
                            chart.pivot(),
                            chart.drillDimensions(),
                            chart.links());
        }
        DataScope scope = scopes.isEmpty() ? null : new DataScope("AND", List.of(), scopes);
        return new ReportDashboards.Execution(chart, scope, ancestors, path, text, resolved);
    }

    private void appendFilter(
            String field,
            ReportDashboards.FilterValue value,
            ReportDashboardFilterKindEnum kind,
            List<DataScope> scopes,
            List<ReportDashboards.TextFilter> text) {
        if (kind == ReportDashboardFilterKindEnum.TEXT
                && value.values() != null
                && !value.values().isEmpty()
                && value.values().getFirst() != null) {
            String term = value.values().getFirst();
            if (!term.isEmpty()) text.add(new ReportDashboards.TextFilter(field, term));
        } else {
            DataScope scope = filterScope(field, value, kind);
            if (scope != null) scopes.add(scope);
        }
    }

    private void key(String value) {
        if (value != null && (value.length() > 2000 || value.indexOf(0) >= 0))
            throw invalid("交互原键格式无效");
    }

    private void validateValue(
            ReportDashboards.FilterValue value, ReportDashboardFilterKindEnum kind) {
        List<String> items = value.values() == null ? List.of() : value.values();
        if (items.size() > (kind == ReportDashboardFilterKindEnum.MULTISELECT ? 100 : 1)
                || new HashSet<>(items).size() != items.size()) throw invalid("公共筛选值数量超出范围或重复");
        items.forEach(this::key);
        boolean range =
                kind == ReportDashboardFilterKindEnum.NUMBER_RANGE
                        || kind == ReportDashboardFilterKindEnum.DATE_RANGE;
        if (range && !items.isEmpty() || !range && (value.from() != null || value.to() != null))
            throw invalid("公共筛选输入与配置类型不匹配");
        if (range) {
            key(value.from());
            key(value.to());
            try {
                if (kind == ReportDashboardFilterKindEnum.NUMBER_RANGE) {
                    BigDecimal from = value.from() == null ? null : new BigDecimal(value.from());
                    BigDecimal to = value.to() == null ? null : new BigDecimal(value.to());
                    if (from != null && to != null && from.compareTo(to) > 0)
                        throw new IllegalArgumentException();
                } else {
                    java.time.Instant from = date(value.from()), to = date(value.to());
                    if (from != null && to != null && from.compareTo(to) > 0)
                        throw new IllegalArgumentException();
                }
            } catch (RuntimeException error) {
                throw invalid("公共筛选范围格式无效或起点晚于终点");
            }
        }
    }

    private java.time.Instant date(String value) {
        if (value == null) return null;
        if (value.length() == 10)
            return java.time.LocalDate.parse(value)
                    .atStartOfDay()
                    .toInstant(java.time.ZoneOffset.UTC);
        String normalized = value.replace(" ", "T");
        try {
            return java.time.OffsetDateTime.parse(normalized).toInstant();
        } catch (java.time.format.DateTimeParseException ignored) {
            return java.time.LocalDateTime.parse(normalized).toInstant(java.time.ZoneOffset.UTC);
        }
    }

    private DataScope filterScope(
            String field, ReportDashboards.FilterValue value, ReportDashboardFilterKindEnum kind) {
        if (kind == ReportDashboardFilterKindEnum.NUMBER_RANGE
                || kind == ReportDashboardFilterKindEnum.DATE_RANGE) {
            List<DataScope.Condition> conditions = new ArrayList<>();
            if (value.from() != null)
                conditions.add(
                        new DataScope.Condition(
                                field, ScopeOperatorEnum.GTE.getCode(), value.from()));
            if (value.to() != null)
                conditions.add(
                        new DataScope.Condition(
                                field, ScopeOperatorEnum.LTE.getCode(), value.to()));
            return conditions.isEmpty() ? null : new DataScope("AND", conditions, List.of());
        }
        if (value.values() == null || value.values().isEmpty()) return null;
        List<String> nonNull = value.values().stream().filter(Objects::nonNull).toList();
        List<DataScope> alternatives = new ArrayList<>();
        if (!nonNull.isEmpty())
            alternatives.add(
                    new DataScope(
                            "AND",
                            List.of(
                                    new DataScope.Condition(
                                            field, ScopeOperatorEnum.IN.getCode(), nonNull)),
                            List.of()));
        if (value.values().stream().anyMatch(Objects::isNull)) alternatives.add(equal(field, null));
        return new DataScope("OR", List.of(), alternatives);
    }

    private DataScope equal(String field, String value) {
        return new DataScope(
                "AND",
                List.of(
                        new DataScope.Condition(
                                field,
                                value == null
                                        ? ScopeOperatorEnum.IS_NULL.getCode()
                                        : ScopeOperatorEnum.EQ.getCode(),
                                value)),
                List.of());
    }
}
