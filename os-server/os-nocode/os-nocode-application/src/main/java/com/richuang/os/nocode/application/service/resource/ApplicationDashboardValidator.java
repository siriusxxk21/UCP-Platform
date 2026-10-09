package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;

import java.util.*;

/** 纯配置白名单校验与固定目录校验分开，旧应用装配不依赖可选的报表模块。 */
public final class ApplicationDashboardValidator {
    private ApplicationDashboardValidator() {}

    public static ApplicationDashboards.Config normalize(
            ApplicationDashboards.Config config,
            Map<String, DataCenter.Definition> definitions,
            Map<String, ApplicationCenter.Resource> resources) {
        if (config == null || config.dashboard() == null) throw invalid("请选择固定仪表板");
        ApplicationDashboards.Reference reference = config.dashboard();
        if (reference.id() == null
                || !reference.id().matches("[1-9][0-9]{0,18}")
                || reference.versionNo() < 1
                || reference.checksum() == null
                || !reference.checksum().matches("[a-f0-9]{64}")) throw invalid("仪表板必须指定固定版本和校验和");
        String context = blank(config.contextObjectId());
        if (context != null && !definitions.containsKey(context)) throw invalid("看板上下文对象必须已加入应用");
        List<ApplicationDashboards.InputBinding> inputs =
                config.inputBindings() == null ? List.of() : config.inputBindings();
        List<ApplicationDashboards.DetailView> details =
                config.detailViews() == null ? List.of() : config.detailViews();
        if (inputs.size() > 10 || details.size() > 30) throw invalid("看板最多绑定十个输入和三十个明细视图");
        Set<String> filters = new HashSet<>(),
                parameters = new HashSet<>(),
                charts = new HashSet<>();
        List<ApplicationDashboards.InputBinding> normalized = new ArrayList<>();
        for (ApplicationDashboards.InputBinding input : inputs) {
            if (input == null || !filters.add(identifier(input.filterId())))
                throw invalid("看板输入筛选不能为空或重复");
            ApplicationDashboardInputSourceEnum source =
                    ApplicationDashboardInputSourceEnum.fromCode(input.source());
            String parameter = blank(input.parameter()), fieldId = blank(input.fieldId());
            if (source == ApplicationDashboardInputSourceEnum.PARAMETER) {
                if (parameter == null
                        || !parameter.matches("[A-Za-z][A-Za-z0-9_]{0,63}")
                        || !parameters.add(parameter)
                        || fieldId != null) throw invalid("参数输入须声明唯一参数名，不能绑定记录字段");
            } else {
                if (context == null || parameter != null) throw invalid("记录输入需要上下文对象，不能声明参数名");
                if (source == ApplicationDashboardInputSourceEnum.RECORD_ID) {
                    if (fieldId != null) throw invalid("当前记录编号输入不能绑定字段");
                } else {
                    FieldConversionCompatibility.Field field =
                            FieldConversionCompatibility.fields(definitions.get(context))
                                    .get(fieldId);
                    if (field == null
                            || !FieldTypeEnum.fromCode(field.definition().type())
                                    .supportsReportGrouping()) throw invalid("看板记录输入须绑定可用标量字段");
                }
            }
            normalized.add(
                    new ApplicationDashboards.InputBinding(
                            input.filterId(), source.getCode(), parameter, fieldId));
        }
        for (ApplicationDashboards.DetailView detail : details) {
            if (detail == null || !charts.add(identifier(detail.chartId())))
                throw invalid("图表明细视图不能为空或重复");
            ApplicationCenter.Resource view = resources.get(identifier(detail.viewId()));
            if (view == null || !ApplicationResourceKindEnum.VIEW.matches(view.kind()))
                throw invalid("图表明细目标须为当前应用业务视图");
        }
        return new ApplicationDashboards.Config(
                reference, context, List.copyOf(normalized), List.copyOf(details));
    }

    /** 由应用保存/发布事务调用；目录检查 VIEW 和对象兼容，本方法检查输入及目标归属。 */
    public static List<ApplicationDashboards.Reference> validate(
            ApplicationCenter.Definition definition,
            ReportCatalogApi catalog,
            ApplicationResourceValidator decoder,
            DataObjectApi objects,
            long actor) {
        List<ApplicationCenter.Resource> dashboards =
                definition.resources().stream()
                        .filter(
                                resource ->
                                        ApplicationResourceKindEnum.REPORT_DASHBOARD.matches(
                                                resource.kind()))
                        .toList();
        if (dashboards.isEmpty()) return List.of();
        if (catalog == null) throw invalid("报表目录服务尚未装配");
        Map<String, ApplicationCenter.Resource> resources = new LinkedHashMap<>();
        definition.resources().forEach(resource -> resources.put(resource.id(), resource));
        Map<String, DataCenter.Definition> definitions = new LinkedHashMap<>();
        definition
                .objects()
                .forEach(
                        ref ->
                                definitions.put(
                                        ref.objectId(),
                                        objects.getVersion(ref.objectId(), ref.versionNo())
                                                .definition()));
        Map<ApplicationDashboards.Reference, ApplicationDashboards.Catalog> fixed =
                new LinkedHashMap<>();
        for (ApplicationCenter.Resource resource : dashboards) {
            ApplicationDashboards.Config config =
                    decoder.decode(resource.config(), ApplicationDashboards.Config.class);
            ApplicationDashboards.Catalog contract =
                    fixed.computeIfAbsent(
                            config.dashboard(),
                            ref -> catalog.fixed(ref, definition.objects(), actor));
            Map<String, ReportDashboards.Chart> charts = new LinkedHashMap<>();
            contract.content().charts().forEach(chart -> charts.put(chart.id(), chart));
            Map<String, ReportDashboards.Filter> filters = new LinkedHashMap<>();
            if (contract.content().filters() != null)
                contract.content().filters().forEach(filter -> filters.put(filter.id(), filter));
            for (ApplicationDashboards.InputBinding input : config.inputBindings()) {
                ReportDashboards.Filter filter = filters.get(input.filterId());
                if (filter == null) throw invalid("输入筛选不属于固定仪表板版本：" + input.filterId());
                ApplicationDashboardInputSourceEnum source =
                        ApplicationDashboardInputSourceEnum.fromCode(input.source());
                if (source == ApplicationDashboardInputSourceEnum.PARAMETER) continue;
                ReportDashboardFilterKindEnum kind =
                        ReportDashboardFilterKindEnum.fromCode(filter.kind());
                if (kind != ReportDashboardFilterKindEnum.TEXT
                        && kind != ReportDashboardFilterKindEnum.SELECT)
                    throw invalid("记录上下文首轮仅绑定文本或单选筛选");
                String sourceType =
                        source == ApplicationDashboardInputSourceEnum.RECORD_FIELD
                                ? FieldConversionCompatibility.fields(
                                                definitions.get(config.contextObjectId()))
                                        .get(input.fieldId())
                                        .definition()
                                        .type()
                                : null;
                for (ReportDashboards.Mapping mapping : filter.mappings()) {
                    ApplicationDashboards.DatasetContract dataset =
                            dataset(contract, charts.get(mapping.chartId()));
                    ReportDatasets.ResolvedField target =
                            dataset.source().fields().stream()
                                    .filter(field -> field.id().equals(mapping.fieldId()))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("看板输入目标字段已失效"));
                    if (sourceType == null) {
                        if (!Set.of(
                                        FieldTypeEnum.REFERENCE,
                                        FieldTypeEnum.UUID,
                                        FieldTypeEnum.TEXT,
                                        FieldTypeEnum.TEXTAREA,
                                        FieldTypeEnum.AUTO_NUMBER,
                                        FieldTypeEnum.INTEGER)
                                .contains(FieldTypeEnum.fromCode(target.type())))
                            throw invalid("记录编号须映射到编号、文本或引用原键字段");
                    } else if (!compatibleType(sourceType, target.type()))
                        throw invalid("记录字段与看板输入字段类型不兼容");
                }
            }
            for (ApplicationDashboards.DetailView detail : config.detailViews()) {
                ReportDashboards.Chart chart = charts.get(detail.chartId());
                if (chart == null) throw invalid("明细图表不属于固定仪表板版本");
                ApplicationUi.View view =
                        decoder.decode(
                                resources.get(detail.viewId()).config(), ApplicationUi.View.class);
                if (!dataset(contract, chart)
                        .source()
                        .source()
                        .root()
                        .objectId()
                        .equals(view.objectId())) throw invalid("图表业务明细视图必须绑定数据集根对象");
            }
        }
        return List.copyOf(fixed.keySet());
    }

    private static ApplicationDashboards.DatasetContract dataset(
            ApplicationDashboards.Catalog catalog, ReportDashboards.Chart chart) {
        if (chart == null) throw invalid("筛选映射图表已失效");
        return catalog.datasets().stream()
                .filter(value -> value.reference().equals(chart.dataset()))
                .findFirst()
                .orElseThrow(() -> invalid("看板固定数据集不存在"));
    }

    private static boolean compatibleType(String before, String after) {
        FieldTypeEnum source = FieldTypeEnum.fromCode(before),
                target = FieldTypeEnum.fromCode(after);
        return source == target
                || source.isNumeric() && target.isNumeric()
                || Set.of(
                                        FieldTypeEnum.TEXT,
                                        FieldTypeEnum.TEXTAREA,
                                        FieldTypeEnum.AUTO_NUMBER,
                                        FieldTypeEnum.UUID)
                                .contains(source)
                        && Set.of(
                                        FieldTypeEnum.TEXT,
                                        FieldTypeEnum.TEXTAREA,
                                        FieldTypeEnum.AUTO_NUMBER,
                                        FieldTypeEnum.UUID)
                                .contains(target);
    }

    private static String blank(String text) {
        return text == null || text.isBlank() ? null : text;
    }

    private static String identifier(String text) {
        if (text == null || !text.matches("[A-Za-z0-9_:-]{1,100}")) throw invalid("看板输入或资源身份无效");
        return text;
    }
}
