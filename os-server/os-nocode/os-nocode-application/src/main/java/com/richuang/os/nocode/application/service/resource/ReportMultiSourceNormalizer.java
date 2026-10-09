package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;

import java.util.*;
import java.util.function.Supplier;

/**
 * 多个数据来源统计的保存 / 发布 / 预览校验（契约第 4–6、8 章）。做法：先判来源之间的结构规则（展示方式、来源个数与编码、指标归属、维度个数、日期范围、 筛选对应），再把每个来源拆成一份
 * 单来源配置走 {@link ApplicationReportValidator#normalizeSource}
 * 那一整段现有校验（字段存在与停用、粒度、指标规则、条件、筛选、日期字段、下钻视图……），
 * 附加来源的报错加「来源「…」：」前缀；最后判每一位维度的对齐与筛选对应的相容性，拼回一份规范化的多来源配置。
 */
final class ReportMultiSourceNormalizer {
    private final ApplicationReportValidator validator;

    ReportMultiSourceNormalizer(ApplicationReportValidator validator) {
        this.validator = validator;
    }

    ApplicationReports.Config normalize(
            ApplicationReports.Config c, Map<String, DataCenter.Definition> objects) {
        ReportDisplayEnum display = ReportDisplayEnum.fromCode(c.display());
        if (display != ReportDisplayEnum.PIVOT && display != ReportDisplayEnum.TABLE)
            throw invalid(ReportSourceMessages.DISPLAY);
        List<ApplicationReports.Source> extras = c.extraSources();
        if (extras.size() > ApplicationReports.MAX_EXTRA_SOURCES)
            throw invalid(ReportSourceMessages.TOO_MANY);
        Set<String> sourceIds = new HashSet<>();
        for (ApplicationReports.Source s : extras)
            if (s == null
                    || s.id() == null
                    || !s.id().matches("[a-z][a-z0-9_]{0,19}")
                    || ReportSourceMessages.MAIN.equals(s.id())
                    || !sourceIds.add(s.id())) throw invalid(ReportSourceMessages.CODE);
        name(c.sourceName());
        for (ApplicationReports.Source s : extras) name(s.name());
        List<ApplicationReports.Metric> metrics = metrics(c, sourceIds);
        List<ApplicationReports.Dimension> rows = list(c.dimensions());
        List<ApplicationReports.Dimension> columns = list(c.columnDimensions());
        Set<String> filterKeys = new LinkedHashSet<>(list(c.filterFieldIds()));
        for (ApplicationReports.Source s : extras) {
            if (list(s.dimensions()).size() != rows.size()
                    || list(s.columnDimensions()).size() != columns.size())
                throw invalid(ReportSourceMessages.dimensions(s.name()));
            if (c.dateFieldId() != null && s.dateFieldId() == null)
                throw invalid(ReportSourceMessages.dateRequired(s.name()));
            if (c.dateFieldId() == null && s.dateFieldId() != null)
                throw invalid(ReportSourceMessages.dateUnexpected(s.name()));
            if (s.filterTargets() != null && !filterKeys.containsAll(s.filterTargets().keySet()))
                throw invalid(ReportSourceMessages.filterUnknown(s.name()));
        }
        validator.labels(c.dimensionLabels(), rows);
        validator.labels(c.columnDimensionLabels(), columns);
        sort(c, metrics);
        // 来源 1：顶层配置本身（只留属于它的基础指标；排序与公式在合并层校验）。
        ApplicationReports.Config main =
                validator.normalizeSource(project(c, null, metrics), objects, Integer.MAX_VALUE);
        List<ApplicationReports.Config> projected = new ArrayList<>();
        for (ApplicationReports.Source s : extras) {
            Map<String, String> mapped = new LinkedHashMap<>();
            for (String key : filterKeys) {
                String target = validator.filterKey(c, s, key);
                if (target == null)
                    throw invalid(
                            ReportSourceMessages.filterMissing(
                                    label(main, key, objects), s.name()));
                mapped.put(key, target);
            }
            ApplicationReports.Config raw = project(c, s, metrics);
            projected.add(
                    prefixed(
                            s.name(),
                            () ->
                                    validator.normalizeSource(
                                            withFilters(
                                                    raw,
                                                    List.copyOf(
                                                            new LinkedHashSet<>(mapped.values()))),
                                            objects,
                                            Integer.MAX_VALUE)));
            align(main, s, rows, columns, c, objects);
            if (s.filterTargets() != null)
                for (Map.Entry<String, String> target : s.filterTargets().entrySet())
                    filterTarget(main, s, target.getKey(), target.getValue(), objects);
        }
        return assemble(c, main, extras, projected, metrics);
    }

    // ---------------------------------------------------------------- 结构规则

    private static void name(String name) {
        if (name == null || name.isBlank() || name.length() > 30)
            throw invalid(ReportSourceMessages.NAME);
    }

    /** 指标个数、编码与名称、来源归属、公式与依赖：每个来源至少一个基础指标。 */
    private List<ApplicationReports.Metric> metrics(
            ApplicationReports.Config c, Set<String> sourceIds) {
        List<ApplicationReports.Metric> metrics = list(c.metrics());
        if (metrics.size() > ApplicationReports.MAX_MULTI_SOURCE_METRICS)
            throw invalid(ReportSourceMessages.METRICS);
        Set<String> ids = new HashSet<>();
        Map<String, Integer> perSource = new LinkedHashMap<>();
        perSource.put(null, 0);
        for (String id : sourceIds) perSource.put(id, 0);
        Map<String, ApplicationReports.Metric> byId = new LinkedHashMap<>();
        for (ApplicationReports.Metric m : metrics) {
            if (m == null
                    || m.id() == null
                    || !m.id().matches("[a-z][a-z0-9_]{0,39}")
                    || !ids.add(m.id())) throw invalid("统计指标编码无效或重复");
            if (m.name() == null || m.name().isBlank() || m.name().length() > 60)
                throw invalid("请填写指标名称（最多 60 字符）");
            ReportOperationEnum op = ReportOperationEnum.fromCode(m.operation());
            if (op == ReportOperationEnum.FORMULA) {
                if (m.sourceId() != null) throw invalid(ReportSourceMessages.FORMULA_SOURCE);
                if (m.fieldId() != null || m.conditions() != null || m.formula() == null)
                    throw invalid("计算指标引用已有指标，不另设字段或筛选条件");
                ReportFormulaEnum.fromCode(m.formula().operator());
                format(m.format());
            } else {
                if (m.sourceId() != null && !sourceIds.contains(m.sourceId()))
                    throw invalid(ReportSourceMessages.metricSource(m.name()));
                perSource.merge(m.sourceId(), 1, Integer::sum);
            }
            byId.put(m.id(), m);
        }
        for (Map.Entry<String, Integer> count : perSource.entrySet())
            if (count.getValue() == 0)
                throw invalid(ReportSourceMessages.noMetric(sourceName(c, count.getKey())));
        for (ApplicationReports.Metric m : metrics) formula(m.id(), byId, new HashSet<>());
        return metrics;
    }

    /** 同单来源的计算指标循环与引用检查（原文案）。 */
    private void formula(
            String id, Map<String, ApplicationReports.Metric> metrics, Set<String> path) {
        ApplicationReports.Metric metric = metrics.get(id);
        if (metric == null) throw invalid("计算指标引用了不存在的指标");
        if (!path.add(id)) throw invalid("计算指标不能循环引用");
        if (metric.formula() != null) {
            formula(metric.formula().left(), metrics, path);
            formula(metric.formula().right(), metrics, path);
        }
        path.remove(id);
    }

    /** 同单来源的指标格式规则（原文案）；计算指标不进来源投影，这里单独判。 */
    private static void format(ApplicationReports.Format fmt) {
        if (fmt != null
                && (fmt.unit() != null && fmt.unit().length() > 20
                        || fmt.decimals() != null && (fmt.decimals() < 0 || fmt.decimals() > 8)
                        || fmt.color() != null && !fmt.color().matches("#[0-9a-fA-F]{6}")
                        || fmt.financial() && fmt.percent()))
            throw invalid("指标单位最多 20 字，精度为 0～8 位，颜色使用六位十六进制；财务金额不能同时设为百分比");
    }

    /** 排序指标可以是任一来源的指标或计算指标；排序依据的规则与单来源相同（原文案）。 */
    private static void sort(ApplicationReports.Config c, List<ApplicationReports.Metric> metrics) {
        if (c.sortMetricId() != null
                && metrics.stream().noneMatch(m -> m.id().equals(c.sortMetricId())))
            throw invalid("排序指标不存在");
        ReportSortByEnum sortBy = c.sortBy() == null ? null : ReportSortByEnum.fromCode(c.sortBy());
        if (sortBy == ReportSortByEnum.METRIC && c.sortMetricId() == null)
            throw invalid("按指标排序需要选择排序指标");
        if (sortBy == ReportSortByEnum.DIMENSION && c.sortMetricId() != null)
            throw invalid("按维度的值排序时不能同时设置排序指标");
    }

    // ---------------------------------------------------------------- 来源投影

    /**
     * 来源投影配置（契约第 7 章）：source 为空 = 来源 1。只留属于该来源的基础指标（sourceId 置空），附加来源没有顶层固定等值；排序、图表与四个多来源分量都为空；
     * 下钻明细视图与允许编辑是该来源自己的（契约变更 C2）。附加来源的 filterFieldIds 由调用方换成映射后的键。
     */
    static ApplicationReports.Config project(
            ApplicationReports.Config c,
            ApplicationReports.Source source,
            List<ApplicationReports.Metric> metrics) {
        String sourceId = source == null ? null : source.id();
        List<ApplicationReports.Metric> own = new ArrayList<>();
        for (ApplicationReports.Metric m : metrics)
            if (ReportOperationEnum.FORMULA != ReportOperationEnum.fromCode(m.operation())
                    && Objects.equals(m.sourceId(), sourceId))
                own.add(
                        new ApplicationReports.Metric(
                                m.id(),
                                m.name(),
                                m.operation(),
                                m.fieldId(),
                                m.conditions(),
                                m.formula(),
                                m.format()));
        boolean pivot = ReportDisplayEnum.PIVOT == ReportDisplayEnum.fromCode(c.display());
        return new ApplicationReports.Config(
                source == null ? c.objectId() : source.objectId(),
                source == null ? c.dimensions() : source.dimensions(),
                own,
                source == null ? c.equal() : Map.of(),
                source == null ? c.filterFieldIds() : List.of(),
                source == null ? c.dateFieldId() : source.dateFieldId(),
                c.timeZone(),
                c.display(),
                null,
                false,
                c.limit(),
                source == null ? c.detailViewId() : source.detailViewId(),
                source == null ? c.conditions() : source.conditions(),
                source == null ? c.chart() : null,
                source == null ? c.columnDimensions() : pivot ? source.columnDimensions() : null,
                c.pivot(),
                source == null ? c.detailEditable() : source.detailEditable(),
                null,
                source == null ? c.grain() : source.grain(),
                source == null ? c.detailId() : source.detailId());
    }

    static ApplicationReports.Config withFilters(
            ApplicationReports.Config c, List<String> filterFieldIds) {
        return new ApplicationReports.Config(
                c.objectId(),
                c.dimensions(),
                c.metrics(),
                c.equal(),
                filterFieldIds,
                c.dateFieldId(),
                c.timeZone(),
                c.display(),
                c.sortMetricId(),
                c.descending(),
                c.limit(),
                c.detailViewId(),
                c.conditions(),
                c.chart(),
                c.columnDimensions(),
                c.pivot(),
                c.detailEditable(),
                c.sortBy(),
                c.grain(),
                c.detailId());
    }

    /** 带上多来源的四个分量重建一份配置（其余分量原样）。 */
    static ApplicationReports.Config withSources(
            ApplicationReports.Config c,
            String sourceName,
            List<ApplicationReports.Source> extraSources,
            List<String> dimensionLabels,
            List<String> columnDimensionLabels) {
        return new ApplicationReports.Config(
                c.objectId(),
                c.dimensions(),
                c.metrics(),
                c.equal(),
                c.filterFieldIds(),
                c.dateFieldId(),
                c.timeZone(),
                c.display(),
                c.sortMetricId(),
                c.descending(),
                c.limit(),
                c.detailViewId(),
                c.conditions(),
                c.chart(),
                c.columnDimensions(),
                c.pivot(),
                c.detailEditable(),
                c.sortBy(),
                c.grain(),
                c.detailId(),
                sourceName,
                extraSources,
                dimensionLabels == null ? null : List.copyOf(dimensionLabels),
                columnDimensionLabels == null ? null : List.copyOf(columnDimensionLabels));
    }

    /** 附加来源上复用现有校验：报错加「来源「…」：」前缀，错误码不变。 */
    static <T> T prefixed(String source, Supplier<T> action) {
        try {
            return action.get();
        } catch (ServiceException e) {
            throw new ServiceException(
                    e.getCode(), ReportSourceMessages.prefix(source, e.getMessage()));
        }
    }

    // ---------------------------------------------------------------- 对齐与筛选

    private void align(
            ApplicationReports.Config main,
            ApplicationReports.Source s,
            List<ApplicationReports.Dimension> rows,
            List<ApplicationReports.Dimension> columns,
            ApplicationReports.Config raw,
            Map<String, DataCenter.Definition> objects) {
        DataCenter.Definition mainRoot = validator.object(objects, main.objectId());
        DataCenter.Detail mainDetail = validator.grainDetail(main, mainRoot);
        DataCenter.Definition root = validator.object(objects, s.objectId());
        DataCenter.Detail detail =
                validator.grainDetail(ReportMultiSourceNormalizer.sourceGrain(s), root);
        for (int i = 0; i < rows.size(); i++)
            alignOne(
                    mainRoot,
                    mainDetail,
                    rows.get(i),
                    root,
                    detail,
                    s.dimensions().get(i),
                    s.name(),
                    dimensionName(
                            raw.dimensionLabels(), i, mainRoot, mainDetail, rows.get(i), objects),
                    objects);
        for (int i = 0; i < columns.size(); i++)
            alignOne(
                    mainRoot,
                    mainDetail,
                    columns.get(i),
                    root,
                    detail,
                    s.columnDimensions().get(i),
                    s.name(),
                    dimensionName(
                            raw.columnDimensionLabels(),
                            i,
                            mainRoot,
                            mainDetail,
                            columns.get(i),
                            objects),
                    objects);
    }

    private void alignOne(
            DataCenter.Definition mainRoot,
            DataCenter.Detail mainDetail,
            ApplicationReports.Dimension mainDimension,
            DataCenter.Definition root,
            DataCenter.Detail detail,
            ApplicationReports.Dimension dimension,
            String source,
            String dimensionName,
            Map<String, DataCenter.Definition> objects) {
        ApplicationReportValidator.Resolved first =
                validator.resolve(mainRoot, validator.key(mainDimension), objects, mainDetail);
        ApplicationReportValidator.Resolved other =
                validator.resolve(root, validator.key(dimension), objects, detail);
        validator.alignment(
                first,
                mainDimension,
                other,
                dimension,
                source,
                pathLabel(root, validator.key(dimension), objects, detail),
                dimensionName,
                id -> objectName(objects, id));
    }

    private void filterTarget(
            ApplicationReports.Config main,
            ApplicationReports.Source s,
            String key,
            String target,
            Map<String, DataCenter.Definition> objects) {
        DataCenter.Definition mainRoot = validator.object(objects, main.objectId());
        DataCenter.Detail mainDetail = validator.grainDetail(main, mainRoot);
        DataCenter.Definition root = validator.object(objects, s.objectId());
        DataCenter.Detail detail = validator.grainDetail(sourceGrain(s), root);
        String reason =
                ReportSourceAlignment.filterReason(
                        validator.resolve(mainRoot, key, objects, mainDetail),
                        validator.resolve(root, target, objects, detail),
                        id -> objectName(objects, id));
        if (reason != null)
            throw invalid(
                    ReportSourceMessages.filterIncompatible(
                            s.name(),
                            pathLabel(mainRoot, key, objects, mainDetail),
                            pathLabel(root, target, objects, detail),
                            reason));
    }

    /** 只带粒度的一份配置，用来取附加来源所选的明细。 */
    static ApplicationReports.Config sourceGrain(ApplicationReports.Source s) {
        return new ApplicationReports.Config(
                s.objectId(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                s.grain(),
                s.detailId());
    }

    // ---------------------------------------------------------------- 名称

    private String label(
            ApplicationReports.Config main,
            String key,
            Map<String, DataCenter.Definition> objects) {
        DataCenter.Definition root = validator.object(objects, main.objectId());
        return pathLabel(root, key, objects, validator.grainDetail(main, root));
    }

    /** {维度名}：dimensionLabels[i]，为空取来源 1 该维度的字段名（关系路径时取「关系名 / 字段名」）。 */
    private String dimensionName(
            List<String> labels,
            int index,
            DataCenter.Definition root,
            DataCenter.Detail detail,
            ApplicationReports.Dimension dimension,
            Map<String, DataCenter.Definition> objects) {
        if (labels != null && index < labels.size() && labels.get(index) != null)
            return labels.get(index);
        return pathLabel(root, validator.key(dimension), objects, detail);
    }

    /** 字段键的显示名：无路径 = 字段名；有路径 = 「关系名 / … / 字段名」。 */
    String pathLabel(
            DataCenter.Definition root,
            String key,
            Map<String, DataCenter.Definition> objects,
            DataCenter.Detail detail) {
        String[] parts = key.split(":", -1);
        StringBuilder label = new StringBuilder();
        if (parts.length == 2) {
            DataCenter.Definition owner = root;
            List<String> path = validator.path(parts[0]);
            for (int i = 0; i < path.size(); i++) {
                DataCenter.Relation relation =
                        i == 0
                                ? validator.relation(owner, path.get(i), detail)
                                : validator.targetRelation(owner, path.get(i));
                label.append(relation.name()).append(" / ");
                owner = validator.object(objects, relation.targetObjectId());
            }
        }
        label.append(validator.resolve(root, key, objects, detail).field().name());
        return label.toString();
    }

    private static String objectName(Map<String, DataCenter.Definition> objects, String id) {
        DataCenter.Definition d = objects.get(id);
        return d == null ? Objects.toString(id, "") : d.objectName();
    }

    private static String sourceName(ApplicationReports.Config c, String id) {
        if (id == null) return c.sourceName();
        for (ApplicationReports.Source s : c.extraSources()) if (s.id().equals(id)) return s.name();
        return id;
    }

    // ---------------------------------------------------------------- 拼回

    private ApplicationReports.Config assemble(
            ApplicationReports.Config c,
            ApplicationReports.Config main,
            List<ApplicationReports.Source> extras,
            List<ApplicationReports.Config> projected,
            List<ApplicationReports.Metric> metrics) {
        Map<String, ApplicationReports.Metric> normalized = new HashMap<>();
        for (ApplicationReports.Metric m : main.metrics()) normalized.put(m.id(), m);
        for (ApplicationReports.Config p : projected)
            for (ApplicationReports.Metric m : p.metrics()) normalized.put(m.id(), m);
        List<ApplicationReports.Metric> output = new ArrayList<>();
        for (ApplicationReports.Metric m : metrics) {
            ApplicationReports.Metric n = normalized.get(m.id());
            output.add(
                    n == null
                            ? new ApplicationReports.Metric(
                                    m.id(),
                                    m.name(),
                                    ReportOperationEnum.FORMULA.name(),
                                    null,
                                    null,
                                    m.formula(),
                                    m.format())
                            : new ApplicationReports.Metric(
                                    n.id(),
                                    n.name(),
                                    n.operation(),
                                    n.fieldId(),
                                    n.conditions(),
                                    n.formula(),
                                    n.format(),
                                    m.sourceId()));
        }
        List<ApplicationReports.Source> sources = new ArrayList<>();
        for (int i = 0; i < extras.size(); i++) {
            ApplicationReports.Source s = extras.get(i);
            ApplicationReports.Config p = projected.get(i);
            sources.add(
                    new ApplicationReports.Source(
                            s.id(),
                            s.name(),
                            s.objectId(),
                            p.grain(),
                            p.detailId(),
                            List.copyOf(s.dimensions()),
                            p.columnDimensions() == null ? null : List.copyOf(p.columnDimensions()),
                            s.conditions(),
                            s.dateFieldId(),
                            s.filterTargets() == null || s.filterTargets().isEmpty()
                                    ? null
                                    : Map.copyOf(s.filterTargets()),
                            p.detailViewId(),
                            p.detailEditable()));
        }
        ApplicationReports.Config merged =
                new ApplicationReports.Config(
                        main.objectId(),
                        main.dimensions(),
                        output,
                        main.equal(),
                        main.filterFieldIds(),
                        main.dateFieldId(),
                        main.timeZone(),
                        main.display(),
                        c.sortMetricId(),
                        c.descending(),
                        main.limit(),
                        main.detailViewId(),
                        main.conditions(),
                        main.chart(),
                        main.columnDimensions(),
                        main.pivot(),
                        main.detailEditable(),
                        c.sortBy() == null ? null : ReportSortByEnum.fromCode(c.sortBy()).name(),
                        main.grain(),
                        main.detailId());
        return withSources(
                merged,
                c.sourceName(),
                List.copyOf(sources),
                c.dimensionLabels(),
                c.columnDimensionLabels());
    }

    private static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }
}
