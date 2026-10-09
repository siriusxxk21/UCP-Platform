package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.form.DetailForms;

import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.*;

/** 报表保存/发布共享的静态契约；运行权限由运行策略另行按当前用户验证。 */
@Component
public class ApplicationReportValidator {
    @jakarta.annotation.Resource
    private com.richuang.os.nocode.metadata.service.formula.OrderedCalculationStateService
            orderedStates;

    public FieldDefinition field(DataCenter.Definition d, String id) {
        FieldDefinition field =
                d.fields().stream()
                        .filter(f -> Objects.equals(f.id(), id))
                        .filter(
                                f ->
                                        !com.richuang.os.nocode.metadata.service.formula
                                                .Calculations.live(d.fieldOptions().get(f.id())))
                        .filter(
                                f ->
                                        !MemberStateEnum.INACTIVE.matches(
                                                d.fieldOptions()
                                                        .getOrDefault(
                                                                f.id(),
                                                                DataCenter.FieldOptions.defaults())
                                                        .state()))
                        .findFirst()
                        .orElseThrow(() -> invalid("统计字段不存在或已停用"));
        if (com.richuang.os.nocode.metadata.service.formula.Calculations.orderedStored(
                d.fieldOptions().get(id))) orderedStates.requireReady(d, List.of(id));
        return OrderedCalculations.queryField(field, d.fieldOptions().get(id));
    }

    public DataCenter.Definition object(Map<String, DataCenter.Definition> objects, String id) {
        DataCenter.Definition d = objects.get(id);
        if (d == null) throw invalid("统计对象未被应用引用");
        return d;
    }

    public DataCenter.Relation relation(DataCenter.Definition d, String id) {
        return relation(d, id, null);
    }

    /** 路径第一段的关系：主表上的单值关系，或粒度明细 detail 上的单值关系（detail 为空 = 按主记录统计）。 落在别的明细上的关系按粒度报错，不当成「没授权」。 */
    public DataCenter.Relation relation(
            DataCenter.Definition d, String id, DataCenter.Detail detail) {
        DataCenter.Relation relation = single(d, id);
        if (relation.sourceDetailId() == null
                || detail != null && Objects.equals(relation.sourceDetailId(), detail.id()))
            return relation;
        DataCenter.Detail other =
                details(d).stream()
                        .filter(t -> Objects.equals(t.id(), relation.sourceDetailId()))
                        .findFirst()
                        .orElse(null);
        String name =
                other == null
                        ? relation.name()
                        : other.fields().stream()
                                .filter(f -> Objects.equals(f.id(), relation.fieldId()))
                                .map(FieldDefinition::name)
                                .findFirst()
                                .orElse(relation.name());
        throw outOfGrain(name, other == null ? relation.sourceDetailId() : other.name(), detail);
    }

    /** 路径第二段的关系：只能是目标对象主表上的单值关系。 */
    public DataCenter.Relation targetRelation(DataCenter.Definition owner, String id) {
        DataCenter.Relation relation = single(owner, id);
        if (relation.sourceDetailId() != null)
            throw invalid(
                    ReportGrainMessages.targetDetailRelation(relation.name(), owner.objectName()));
        return relation;
    }

    private DataCenter.Relation single(DataCenter.Definition d, String id) {
        return d.relations().stream()
                .filter(r -> Objects.equals(r.id(), id))
                .filter(r -> !RelationTypeEnum.MANY_TO_MANY.matches(r.kind()))
                .findFirst()
                .orElseThrow(() -> invalid("分组只能沿已有单值关系，多值关系请用于关联范围"));
    }

    public boolean date(FieldDefinition f) {
        return FieldTypeEnum.DATE.matches(f.type()) || FieldTypeEnum.DATETIME.matches(f.type());
    }

    public List<String> path(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> ids = Arrays.asList(value.split("/", -1));
        if (ids.size() > 2
                || ids.stream().anyMatch(id -> !id.matches("[A-Za-z0-9_-]{1,80}"))
                || new HashSet<>(ids).size() != ids.size()) throw invalid("统计关联路径最多两段且不能循环");
        return ids;
    }

    public record Resolved(DataCenter.Definition owner, FieldDefinition field) {}

    public Resolved resolve(
            DataCenter.Definition root, String key, Map<String, DataCenter.Definition> objects) {
        return resolve(root, key, objects, null);
    }

    /**
     * 字段键在当前粒度下解析到的归属与字段。detail 为空 = 按主记录统计：只认主表字段与主表上的关系；非空 = 按该明细的行统计：再加上它的字段与它上面的关系。
     * 粒度明细的字段归属是该明细的投影定义（只用于读字段配置与关系，⛔ 不能拿去取授权）。
     */
    public Resolved resolve(
            DataCenter.Definition root,
            String key,
            Map<String, DataCenter.Definition> objects,
            DataCenter.Detail detail) {
        if (key == null) throw invalid("统计字段不能为空");
        String[] parts = key.split(":", -1);
        if (parts.length > 2) throw invalid("统计字段路径无效");
        List<String> ids = parts.length == 2 ? path(parts[0]) : List.<String>of();
        String id = parts[parts.length - 1];
        DataCenter.Definition owner = ids.isEmpty() ? scope(root, detail, id) : root;
        for (int i = 0; i < ids.size(); i++)
            owner =
                    object(
                            objects,
                            (i == 0
                                            ? relation(owner, ids.get(i), detail)
                                            : targetRelation(owner, ids.get(i)))
                                    .targetObjectId());
        return new Resolved(owner, field(owner, id));
    }

    /**
     * 无路径字段的归属：主表字段归主对象；粒度明细的字段归该明细的投影定义；落在别的明细里的字段按粒度报错。哪里都找不到时仍归主对象，由 {@link #field}
     * 报「不存在或已停用」。同一对象内字段 ID 来自同一条序列，主表与各明细之间不重复。
     */
    public DataCenter.Definition scope(
            DataCenter.Definition root, DataCenter.Detail detail, String id) {
        if (root.fields().stream().anyMatch(f -> Objects.equals(f.id(), id))) return root;
        for (DataCenter.Detail other : details(root))
            for (FieldDefinition f : other.fields())
                if (Objects.equals(f.id(), id)) {
                    if (detail != null && Objects.equals(other.id(), detail.id()))
                        return DetailForms.definition(root, detail);
                    throw outOfGrain(f.name(), other.name(), detail);
                }
        return root;
    }

    private com.richuang.os.framework.common.exception.ServiceException outOfGrain(
            String field, String other, DataCenter.Detail detail) {
        return invalid(
                detail == null
                        ? ReportGrainMessages.rootGrain(field, other)
                        : ReportGrainMessages.otherDetail(field, other, detail.name()));
    }

    private List<DataCenter.Detail> details(DataCenter.Definition d) {
        return d.details() == null ? List.of() : d.details();
    }

    /**
     * 明细粒度统计的下钻明细视图：须是「一行表示 = 一条内部明细」且明细来源就是统计所按明细的数据视图（下钻出来一行一条明细，条数与金额对得上格子）。 按主记录统计（detail
     * 为空）或未选下钻视图时不判，主记录粒度可选任意同对象视图的存量行为不变。保存、发布与运行期共用同一判定。
     */
    public void requireDrillView(DataCenter.Detail detail, ApplicationUi.View view) {
        if (detail == null || view == null) return;
        DataViews.Composition composition = view.composition();
        if (composition == null
                || !ViewGrainEnum.DETAIL.matches(composition.grain())
                || !Objects.equals(composition.detailId(), detail.id()))
            throw invalid(ReportGrainMessages.detailDrillView(detail.name()));
    }

    /** 统计粒度所选的内部明细；按主记录统计返回空。保存、预览与运行期共用同一判定。 */
    public DataCenter.Detail grainDetail(ApplicationReports.Config c, DataCenter.Definition root) {
        String grain = c.grain() == null || c.grain().isBlank() ? null : c.grain();
        if (grain != null
                && !ViewGrainEnum.ROOT.matches(grain)
                && !ViewGrainEnum.DETAIL.matches(grain))
            throw invalid(ReportGrainMessages.GRAIN_INVALID);
        boolean chosen = c.detailId() != null && !c.detailId().isBlank();
        if (!ViewGrainEnum.DETAIL.matches(grain)) {
            if (chosen) throw invalid(ReportGrainMessages.ROOT_WITH_DETAIL);
            return null;
        }
        if (!chosen) throw invalid(ReportGrainMessages.DETAIL_REQUIRED);
        DataCenter.Detail detail =
                details(root).stream()
                        .filter(t -> Objects.equals(t.id(), c.detailId()))
                        .findFirst()
                        .orElseThrow(() -> invalid(ReportGrainMessages.DETAIL_MISSING));
        if (MemberStateEnum.INACTIVE.matches(detail.state()))
            throw invalid(ReportGrainMessages.detailInactive(detail.name()));
        return detail;
    }

    /**
     * 指标字段的归属与粒度规则：「主记录数」只在明细粒度、不带字段；明细粒度下主表字段不能求和、平均、非空计数（一条主记录有几条明细就被算几遍）；
     * 极值与去重计数不受重复行影响，主表字段与明细字段都可以。返回字段所在的定义，类型规则由调用方接着判。
     */
    public DataCenter.Definition metricScope(
            ApplicationReports.Metric m,
            ReportOperationEnum op,
            DataCenter.Definition root,
            DataCenter.Detail detail) {
        if (op == ReportOperationEnum.COUNT_ROOT) {
            if (detail == null) throw invalid(ReportGrainMessages.ROOT_COUNT_NEEDS_DETAIL);
            if (m.fieldId() != null || m.formula() != null)
                throw invalid(ReportGrainMessages.ROOT_COUNT_WITHOUT_FIELD);
            return root;
        }
        if (op == ReportOperationEnum.FORMULA || op == ReportOperationEnum.COUNT) return root;
        DataCenter.Definition owner = scope(root, detail, m.fieldId());
        if (detail != null
                && owner == root
                && (op == ReportOperationEnum.SUM
                        || op == ReportOperationEnum.AVG
                        || op == ReportOperationEnum.COUNT_FIELD))
            for (FieldDefinition f : root.fields())
                if (Objects.equals(f.id(), m.fieldId()))
                    throw invalid(ReportGrainMessages.mainAggregate(f.name(), detail.name()));
        return owner;
    }

    /** 日期范围字段两种粒度下都只能是主表字段。 */
    public void dateScope(
            ApplicationReports.Config c, DataCenter.Definition root, DataCenter.Detail detail) {
        if (c.dateFieldId() != null && scope(root, detail, c.dateFieldId()) != root)
            throw invalid(ReportGrainMessages.DETAIL_DATE_FIELD);
    }

    /**
     * 运行期读到的可能是早先发布、没有经过现行校验的配置：粒度相关的规则在这里按保存时的同一句话再拦一次，不重做其余校验。 路径上的关系与无路径字段的范围由编译器逐个字段调 {@link
     * #relation(DataCenter.Definition, String, DataCenter.Detail)} 与 {@link #scope} 判定。
     */
    public void requireGrain(
            ApplicationReports.Config c, DataCenter.Definition root, DataCenter.Detail detail) {
        for (ApplicationReports.Metric m : c.metrics())
            metricScope(m, ReportOperationEnum.fromCode(m.operation()), root, detail);
        dateScope(c, root, detail);
    }

    public String key(ApplicationReports.Dimension d) {
        return d.relationPath() == null || d.relationPath().isBlank()
                ? d.fieldId()
                : d.relationPath() + ":" + d.fieldId();
    }

    public boolean scalar(FieldDefinition f) {
        return FieldTypeEnum.fromCode(f.type()).supportsReportGrouping();
    }

    /**
     * 保存、发布与预览的统计配置校验与规范化。多个数据来源（extraSources 非空）另走 {@link ReportMultiSourceNormalizer}：
     * 每个来源拆成一份单来源配置走下面同一段校验，再校验来源之间的对齐与筛选映射。单来源配置的结果与引入多来源前逐键相同。
     */
    public ApplicationReports.Config normalize(
            ApplicationReports.Config c, Map<String, DataCenter.Definition> objects) {
        if (c.multiSource()) return new ReportMultiSourceNormalizer(this).normalize(c, objects);
        if (c.sourceName() != null) throw invalid(ReportSourceMessages.SINGLE_NAME);
        if (c.metrics() != null)
            for (ApplicationReports.Metric m : c.metrics())
                if (m != null && m.sourceId() != null)
                    throw invalid(ReportSourceMessages.metricSource(m.name()));
        ApplicationReports.Config single = normalizeSource(c, objects, 5);
        if (c.dimensionLabels() == null && c.columnDimensionLabels() == null) return single;
        labels(c.dimensionLabels(), single.dimensions());
        labels(c.columnDimensionLabels(), single.columnDimensions());
        return ReportMultiSourceNormalizer.withSources(
                single, null, null, c.dimensionLabels(), c.columnDimensionLabels());
    }

    /** L23：维度显示名为空，或与对应维度等长、每项 1..30 字。 */
    void labels(List<String> labels, List<ApplicationReports.Dimension> dimensions) {
        if (labels == null) return;
        int size = dimensions == null ? 0 : dimensions.size();
        if (labels.size() != size
                || labels.stream().anyMatch(l -> l == null || l.isBlank() || l.length() > 30))
            throw invalid(ReportSourceMessages.LABELS);
    }

    /**
     * 一个来源的单来源校验与规范化（引入多来源前的 normalize 原样移到这里）：maxMetrics 为指标个数上限——单来源 5；多来源拆出的来源投影配置 由 {@link
     * ReportMultiSourceNormalizer} 先按全部指标判过上限，这里传全局上限。
     */
    ApplicationReports.Config normalizeSource(
            ApplicationReports.Config c,
            Map<String, DataCenter.Definition> objects,
            int maxMetrics) {
        var d = object(objects, c.objectId());
        DataCenter.Detail detail = grainDetail(c, d);
        var dims =
                c.dimensions() == null ? List.<ApplicationReports.Dimension>of() : c.dimensions();
        var display = ReportDisplayEnum.fromCode(c.display());
        var columnDims =
                c.columnDimensions() == null
                        ? List.<ApplicationReports.Dimension>of()
                        : c.columnDimensions();
        // 透视表行、列维度各自不限个数，只限合计数（分组集合随维度数平方增长）；其它展示方式仍最多两个分组。
        if (display != ReportDisplayEnum.PIVOT
                && (dims.size() > ApplicationReports.MAX_GROUP_DIMENSIONS
                        || new HashSet<>(dims).size() != dims.size())) throw invalid("最多两个不重复分组");
        for (var dim : dims) validateDimension(dim, d, objects, detail);
        ApplicationReports.Pivot pivot = null;
        if (display == ReportDisplayEnum.PIVOT) {
            if (dims.size() + columnDims.size() > ApplicationReports.MAX_PIVOT_DIMENSIONS)
                throw invalid(
                        "维度过多，请减少：透视表行维度与列维度合计最多 "
                                + ApplicationReports.MAX_PIVOT_DIMENSIONS
                                + " 个（小计与合计要按 (行维度数+1)×(列维度数+1) 组分组重新聚合，维度越多查询越重）");
            Set<String> rowIdentities = new HashSet<>();
            for (var dim : dims) if (!rowIdentities.add(identity(dim))) throw invalid("透视表行维度不能重复");
            Set<String> columnIdentities = new HashSet<>();
            for (var dim : columnDims) {
                validateDimension(dim, d, objects, detail);
                if (rowIdentities.contains(identity(dim))) throw invalid("同一字段不能同时作为透视表的行维度和列维度");
                if (!columnIdentities.add(identity(dim))) throw invalid("透视表列维度不能重复");
            }
            pivot = pivot(c.pivot());
        } else {
            if (!columnDims.isEmpty()) throw invalid("只有透视表可以设置列维度");
            if (c.pivot() != null) throw invalid("只有透视表可以设置透视选项");
        }
        boolean detailEditable = Boolean.TRUE.equals(c.detailEditable());
        if (detailEditable && c.detailViewId() == null) throw invalid("明细允许编辑需要先选择下钻明细视图");
        var metrics = c.metrics();
        if (metrics == null || metrics.isEmpty() || metrics.size() > maxMetrics)
            throw invalid("统计指标应为 1 到 5 个");
        Set<String> ids = new HashSet<>();
        List<ApplicationReports.Metric> normalized = new ArrayList<>();
        for (ApplicationReports.Metric m : metrics) {
            if (m == null
                    || m.id() == null
                    || !m.id().matches("[a-z][a-z0-9_]{0,39}")
                    || !ids.add(m.id())) throw invalid("统计指标编码无效或重复");
            if (m.name() == null || m.name().isBlank() || m.name().length() > 60)
                throw invalid("请填写指标名称（最多 60 字符）");
            var op = ReportOperationEnum.fromCode(m.operation());
            // 「主记录数」的粒度与字段规则放在指标格式校验之后判（见下），与其它计算方式「先报格式冲突」的先后一致。
            DataCenter.Definition owner =
                    op == ReportOperationEnum.COUNT_ROOT ? d : metricScope(m, op, d, detail);
            if (op == ReportOperationEnum.FORMULA) {
                if (m.fieldId() != null || m.conditions() != null || m.formula() == null)
                    throw invalid("计算指标引用已有指标，不另设字段或筛选条件");
                ReportFormulaEnum.fromCode(m.formula().operator());
            } else if (op == ReportOperationEnum.COUNT) {
                if (m.fieldId() != null) throw invalid("记录计数无需指定字段");
            } else if (op == ReportOperationEnum.COUNT_ROOT) {
                if (m.formula() != null)
                    throw invalid(ReportGrainMessages.ROOT_COUNT_WITHOUT_FIELD);
            } else if (op == ReportOperationEnum.COUNT_FIELD
                    || op == ReportOperationEnum.COUNT_DISTINCT) {
                if (!scalar(field(owner, m.fieldId()))) throw invalid("计数字段必须为单值字段");
            } else if (BusinessFields.relation(owner, m.fieldId()) != null
                    || !FieldTypeEnum.fromCode(field(owner, m.fieldId()).type()).isNumeric()) {
                throw invalid("求和、平均、极值需要业务数值字段，不能使用对象引用");
            }
            if (op != ReportOperationEnum.FORMULA && m.formula() != null)
                throw invalid("基础指标不能配置计算公式");
            validateConditions(m.conditions(), d, objects, null, detail);
            ApplicationReports.Format fmt = m.format();
            if (fmt != null
                    && (fmt.unit() != null && fmt.unit().length() > 20
                            || fmt.decimals() != null && (fmt.decimals() < 0 || fmt.decimals() > 8)
                            || fmt.color() != null && !fmt.color().matches("#[0-9a-fA-F]{6}")
                            || fmt.financial() && fmt.percent()))
                throw invalid("指标单位最多 20 字，精度为 0～8 位，颜色使用六位十六进制；财务金额不能同时设为百分比");
            if (op == ReportOperationEnum.COUNT_ROOT) metricScope(m, op, d, detail);
            normalized.add(
                    new ApplicationReports.Metric(
                            m.id(),
                            m.name(),
                            op.name(),
                            m.fieldId(),
                            m.conditions(),
                            m.formula(),
                            fmt));
        }
        LinkedHashMap<String, ApplicationReports.Metric> metricMap =
                new LinkedHashMap<String, ApplicationReports.Metric>();
        normalized.forEach(m -> metricMap.put(m.id(), m));
        for (var metric : normalized) validateFormula(metric.id(), metricMap, new HashSet<>());
        validateConditions(c.conditions(), d, objects, null, detail);
        var equal = c.equal() == null ? Map.<String, Object>of() : c.equal();
        var filters = c.filterFieldIds() == null ? List.<String>of() : c.filterFieldIds();
        if (equal.size() > 20
                || filters.size() > 12
                || new HashSet<>(filters).size() != filters.size())
            throw invalid("固定条件最多 20 项、用户筛选最多 12 项且不能重复");
        HashSet<String> allFilters = new HashSet<>(equal.keySet());
        allFilters.addAll(filters);
        for (var id : allFilters)
            if (!scalar(resolve(d, id, objects, detail).field())) throw invalid("此字段不支持统计筛选");
        dateScope(c, d, detail);
        if (c.dateFieldId() != null && !date(field(d, c.dateFieldId())))
            throw invalid("日期范围需要日期字段");
        String zone = c.timeZone() == null ? "Asia/Shanghai" : c.timeZone();
        try {
            ZoneId.of(zone);
        } catch (RuntimeException e) {
            throw invalid("报表时区无效");
        }
        // PostgreSQL 时区目录与 Java 的短偏移别名不完全相同，仅接收区域名称及 UTC。
        if (!zone.equals("UTC") && !ZoneId.getAvailableZoneIds().contains(zone))
            throw invalid("请选择区域时区");
        if (display == ReportDisplayEnum.METRIC && !dims.isEmpty()) throw invalid("指标卡不设置分组");
        if (display == ReportDisplayEnum.PIVOT && dims.isEmpty()) throw invalid("透视表至少需要一个行维度");
        if (display != ReportDisplayEnum.METRIC && dims.isEmpty()) throw invalid("图表或汇总表至少需要一个分组");
        if (display == ReportDisplayEnum.PIE && (dims.size() != 1 || metrics.size() != 1))
            throw invalid("饼图使用一个分组和一个指标");
        if (c.sortMetricId() != null && !ids.contains(c.sortMetricId())) throw invalid("排序指标不存在");
        // 排序依据为空 = 存量行为；填了就必须与排序指标一致，避免「按指标却没选指标」这类保存后不生效的配置。
        ReportSortByEnum sortBy = c.sortBy() == null ? null : ReportSortByEnum.fromCode(c.sortBy());
        if (sortBy == ReportSortByEnum.METRIC && c.sortMetricId() == null)
            throw invalid("按指标排序需要选择排序指标");
        if (sortBy == ReportSortByEnum.DIMENSION && c.sortMetricId() != null)
            throw invalid("按维度的值排序时不能同时设置排序指标");
        // limit 为空 = 不限制。汇总表、透视表填数字时以页面行数保护值为上限；指标卡与图表保持原有 1 到 200。
        boolean tableDisplay =
                display == ReportDisplayEnum.TABLE || display == ReportDisplayEnum.PIVOT;
        if (c.limit() != null
                && (c.limit() < 1
                        || c.limit()
                                > (tableDisplay
                                        ? ApplicationReports.MAX_TABLE_ROWS
                                        : ApplicationReports.MAX_CHART_GROUPS)))
            throw invalid(
                    tableDisplay
                            ? "最多展示行数应为 1 到 " + ApplicationReports.MAX_TABLE_ROWS + "，留空表示不限制"
                            : "展示组数应为 1 到 " + ApplicationReports.MAX_CHART_GROUPS);
        var chart = c.chart();
        if (chart != null) {
            ReportBarModeEnum.fromCode(chart.barMode());
            ReportLegendPositionEnum.fromCode(chart.legendPosition());
            if (display == ReportDisplayEnum.BAR
                    && ReportBarModeEnum.fromCode(chart.barMode()) != ReportBarModeEnum.GROUPED
                    && normalized.stream()
                            .anyMatch(
                                    m ->
                                            m.formula() != null
                                                    || m.format() != null && m.format().percent()))
                throw invalid("堆叠图不支持比例或计算指标，请使用分组图、表格或指标卡");
            if (display == ReportDisplayEnum.BAR
                    && ReportBarModeEnum.fromCode(chart.barMode()) != ReportBarModeEnum.GROUPED
                    && normalized.stream()
                                    .map(
                                            m ->
                                                    m.format() == null || m.format().unit() == null
                                                            ? ""
                                                            : m.format().unit().trim())
                                    .distinct()
                                    .count()
                            > 1) throw invalid("堆叠指标的单位必须一致");
        }
        ApplicationReports.Config normalizedConfig =
                new ApplicationReports.Config(
                        c.objectId(),
                        List.copyOf(dims),
                        normalized,
                        equal,
                        List.copyOf(filters),
                        c.dateFieldId(),
                        zone,
                        display.name(),
                        c.sortMetricId(),
                        c.descending(),
                        c.limit(),
                        c.detailViewId(),
                        c.conditions(),
                        chart,
                        display == ReportDisplayEnum.PIVOT ? List.copyOf(columnDims) : null,
                        pivot,
                        detailEditable ? Boolean.TRUE : null,
                        sortBy == null ? null : sortBy.name(),
                        detail == null ? null : ViewGrainEnum.DETAIL.getCode(),
                        detail == null ? null : detail.id());
        return normalizeFormats(normalizedConfig, d);
    }

    /**
     * 多个数据来源的对齐判定（契约第 5 章，唯一一处；校验器、页面校验、依赖扫描共用）：附加来源 source 的第 i 位维度（other，按本来源粒度解析） 与来源 1
     * 同位维度（main）能否对齐。能 ⇒ 返回类别；不能 ⇒ 抛 L7（分组方式不同）或 L8（附原因 R1–R6）。field 为本来源该维度的字段显示名， dimension
     * 为该位置的维度名，objectNames 把对象 ID 换成名称（L8·R2 用）。
     */
    public ReportSourceAlignment.Kind alignment(
            Resolved main,
            ApplicationReports.Dimension mainDimension,
            Resolved other,
            ApplicationReports.Dimension otherDimension,
            String source,
            String field,
            String dimension,
            java.util.function.Function<String, String> objectNames) {
        return ReportSourceAlignment.align(
                main, mainDimension, other, otherDimension, source, field, dimension, objectNames);
    }

    /**
     * 顶层筛选键 key（filterFieldIds / 用户筛选 equal 的键、用户条件叶子的字段）在附加来源 source 里对应的字段键（契约第 8 章）：
     * filterTargets 显式指定 ⇒ 它；key 是来源 1 的第 i 个行（列）维度 ⇒ 本来源第 i 个行（列）维度；同一对象同一粒度 ⇒ 同一个键；都不成立 ⇒
     * null（调用方报 L14）。运行期取数与页面校验共用。
     */
    public String filterKey(
            ApplicationReports.Config config, ApplicationReports.Source source, String key) {
        if (source.filterTargets() != null && source.filterTargets().get(key) != null)
            return source.filterTargets().get(key);
        List<ApplicationReports.Dimension> rows =
                config.dimensions() == null ? List.of() : config.dimensions();
        for (int i = 0; i < rows.size(); i++)
            if (Objects.equals(key(rows.get(i)), key)
                    && source.dimensions() != null
                    && i < source.dimensions().size()) return key(source.dimensions().get(i));
        List<ApplicationReports.Dimension> columns =
                config.columnDimensions() == null ? List.of() : config.columnDimensions();
        for (int i = 0; i < columns.size(); i++)
            if (Objects.equals(key(columns.get(i)), key)
                    && source.columnDimensions() != null
                    && i < source.columnDimensions().size())
                return key(source.columnDimensions().get(i));
        if (Objects.equals(source.objectId(), config.objectId())
                && Objects.equals(grainCode(source.grain()), grainCode(config.grain()))
                && Objects.equals(
                        grainCode(source.grain()) == null ? null : source.detailId(),
                        grainCode(config.grain()) == null ? null : config.detailId())) return key;
        return null;
    }

    /**
     * 来源投影配置（契约第 7 章）：source 为空 = 来源 1。只留属于该来源的基础指标（sourceId 置空）；附加来源没有顶层固定等值，可筛选字段换成 {@link
     * #filterKey} 映射后的键（映射不到的键不放进去，由调用方按 L14 处理）；排序、图表与多来源分量为空；下钻明细视图与允许编辑是该来源自己的。 保存校验与运行期取数共用。
     */
    public ApplicationReports.Config project(
            ApplicationReports.Config c, ApplicationReports.Source source) {
        ApplicationReports.Config projected =
                ReportMultiSourceNormalizer.project(c, source, c.metrics());
        if (source == null) return projected;
        Set<String> mapped = new LinkedHashSet<>();
        for (String key : c.filterFieldIds() == null ? List.<String>of() : c.filterFieldIds()) {
            String target = filterKey(c, source, key);
            if (target != null) mapped.add(target);
        }
        return ReportMultiSourceNormalizer.withFilters(projected, List.copyOf(mapped));
    }

    /** 粒度的规范值：空与 ROOT 都是按主记录（null），其余原样。 */
    static String grainCode(String grain) {
        return grain == null || grain.isBlank() || ViewGrainEnum.ROOT.matches(grain) ? null : grain;
    }

    /** 保存、预览与运行共享展示投影；不重新校验历史配置，也不修改传入快照或对象定义。 */
    public ApplicationReports.Config normalizeFormats(
            ApplicationReports.Config config, DataCenter.Definition definition) {
        // 明细粒度下指标字段可以是所选明细的字段：金额判定在「主表字段 + 该明细字段」里找；明细已不存在时按主表判，不在这里报错。
        DataCenter.Definition scope =
                !ViewGrainEnum.DETAIL.matches(config.grain())
                        ? definition
                        : details(definition).stream()
                                .filter(t -> Objects.equals(t.id(), config.detailId()))
                                .findFirst()
                                .map(t -> DetailForms.selectionDefinition(definition, t))
                                .orElse(definition);
        List<ApplicationReports.Metric> metrics =
                config.metrics().stream()
                        .map(
                                metric ->
                                        new ApplicationReports.Metric(
                                                metric.id(),
                                                metric.name(),
                                                metric.operation(),
                                                metric.fieldId(),
                                                metric.conditions(),
                                                metric.formula(),
                                                normalizeFormat(metric, scope)))
                        .toList();
        // 只替换指标：列维度、透视选项、明细可编辑等其余配置原样保留（运行期 prepare 每次都会走这里）。
        return config.withMetrics(metrics);
    }

    private ApplicationReports.Format normalizeFormat(
            ApplicationReports.Metric metric, DataCenter.Definition definition) {
        ApplicationReports.Format format = metric.format();
        ReportOperationEnum operation = ReportOperationEnum.fromCode(metric.operation());
        if (operation == ReportOperationEnum.FORMULA) return format;
        // 金额字段的计数仍是数量；切换来源或聚合方式后清除遗留财务标记。
        boolean money =
                switch (operation) {
                    case SUM, AVG, MIN, MAX ->
                            definition.fields().stream()
                                    .anyMatch(
                                            field ->
                                                    Objects.equals(field.id(), metric.fieldId())
                                                            && FieldTypeEnum.MONEY.matches(
                                                                    field.type()));
                    default -> false;
                };
        boolean financial = money && (format == null || !format.percent());
        if (format == null && !financial) return null;
        // 展示位数由前端统一处理，这里保留配置精度、单位及颜色。
        return new ApplicationReports.Format(
                format == null ? null : format.unit(),
                format == null ? null : format.decimals(),
                format != null && format.percent(),
                format == null ? null : format.color(),
                financial);
    }

    private void validateDimension(
            ApplicationReports.Dimension dim,
            DataCenter.Definition d,
            Map<String, DataCenter.Definition> objects,
            DataCenter.Detail detail) {
        if (dim == null) throw invalid("分组不能为空");
        var f = resolve(d, key(dim), objects, detail).field();
        if (!scalar(f)) throw invalid("此字段不支持统计分组");
        var bucket = ReportBucketEnum.fromCode(dim.bucket());
        if (bucket != ReportBucketEnum.VALUE && !date(f)) throw invalid("日期分组需要日期字段");
    }

    /** 同一字段 = fieldId + relationPath + bucket；空关联路径与缺省视为同一路径。 */
    private String identity(ApplicationReports.Dimension dim) {
        return key(dim) + "|" + ReportBucketEnum.fromCode(dim.bucket()).name();
    }

    /** 透视选项补齐默认值后校验取值范围；保存结果总是完整的非空设置。 */
    private ApplicationReports.Pivot pivot(ApplicationReports.Pivot input) {
        var p = (input == null ? ApplicationReports.Pivot.defaults() : input).withDefaults();
        var percent = ReportPivotPercentEnum.fromCode(p.percent());
        if (p.maxColumnGroups() < 1 || p.maxColumnGroups() > 100)
            throw invalid("透视表列组上限应为 1 到 100");
        return new ApplicationReports.Pivot(
                p.subtotals(),
                p.rowTotals(),
                p.columnTotals(),
                percent.name(),
                p.maxColumnGroups(),
                p.columnDescending());
    }

    private void validateFormula(
            String id, Map<String, ApplicationReports.Metric> metrics, Set<String> path) {
        ApplicationReports.Metric metric = metrics.get(id);
        if (metric == null) throw invalid("计算指标引用了不存在的指标");
        if (!path.add(id)) throw invalid("计算指标不能循环引用");
        if (metric.formula() != null) {
            validateFormula(metric.formula().left(), metrics, path);
            validateFormula(metric.formula().right(), metrics, path);
        }
        path.remove(id);
    }

    /** 保存与运行共享条件树形状校验，使用底座 DTO，保留 AND/OR 语义。 */
    public void visitConditions(
            DynamicConditionDTO tree, java.util.function.Consumer<DynamicConditionDTO.Item> leaf) {
        if (tree == null) return;
        if (tree.getLogic() == null) throw invalid("条件缺少全部满足／任一满足关系");
        visitItems(tree.getItems(), 0, new int[] {0}, leaf);
    }

    private void visitItems(
            List<DynamicConditionDTO.Item> items,
            int depth,
            int[] count,
            java.util.function.Consumer<DynamicConditionDTO.Item> leaf) {
        if (items == null || items.isEmpty() || items.size() > 50 || depth > 4)
            throw invalid("统计条件不能为空，最多 50 项、4 层分组");
        for (DynamicConditionDTO.Item item : items) {
            if (item == null || ++count[0] > 50) throw invalid("统计条件最多 50 项");
            if (item.isGroup()) {
                if (item.getGroupLogic() == null) throw invalid("条件组缺少逻辑关系");
                visitItems(item.getGroupItems(), depth + 1, count, leaf);
            } else {
                if (!"condition".equals(item.getType())) throw invalid("统计条件类型无效");
                leaf.accept(item);
            }
        }
    }

    public void validateConditions(
            DynamicConditionDTO tree,
            DataCenter.Definition root,
            Map<String, DataCenter.Definition> objects,
            Set<String> allowed) {
        validateConditions(tree, root, objects, allowed, null);
    }

    /** detail 为统计粒度所选的内部明细（空 = 按主记录）：条件里的字段键按同一范围解析。 */
    public void validateConditions(
            DynamicConditionDTO tree,
            DataCenter.Definition root,
            Map<String, DataCenter.Definition> objects,
            Set<String> allowed,
            DataCenter.Detail detail) {
        visitConditions(
                tree,
                item -> {
                    if (allowed != null && !allowed.contains(item.getField()))
                        throw invalid("只能使用统计视图开放的筛选字段");
                    var resolved = resolve(root, item.getField(), objects, detail);
                    validateCondition(item, resolved.owner(), resolved.field());
                });
    }

    public void validateCondition(
            DynamicConditionDTO.Item item, DataCenter.Definition owner, FieldDefinition f) {
        RecordQueryOperatorEnum op = RecordQueryOperatorEnum.fromCode(item.getOperator());
        if (!scalar(f) || !op.supportsType(FieldTypeEnum.fromCode(f.type())))
            throw invalid("统计字段不支持此筛选操作：" + f.name());
        if (BusinessFields.relation(owner, f.id()) != null
                && !Set.of(
                                RecordQueryOperatorEnum.EQ,
                                RecordQueryOperatorEnum.NEQ,
                                RecordQueryOperatorEnum.IN,
                                RecordQueryOperatorEnum.IS_NULL,
                                RecordQueryOperatorEnum.NOT_NULL)
                        .contains(op)) throw invalid("引用字段只支持等值、属于任一和空值筛选");
        if (op == RecordQueryOperatorEnum.IS_NULL || op == RecordQueryOperatorEnum.NOT_NULL) return;
        var raw = item.getValue();
        // 相对日期（今天、本月、过去 N 天……）：只校验字段类型、比较方式与形状，执行时按当天换算。
        if (RelativeDates.isRelative(raw)) {
            RelativeDates.check(f, op.getCode(), raw);
            return;
        }
        if (op == RecordQueryOperatorEnum.IN || op == RecordQueryOperatorEnum.BETWEEN) {
            if (!(raw instanceof List<?> list)
                    || list.isEmpty()
                    || list.size() > 100
                    || op == RecordQueryOperatorEnum.BETWEEN && list.size() != 2)
                throw invalid("范围条件值无效");
            for (Object value : list) validateConditionValue(value, owner, f, op);
        } else validateConditionValue(raw, owner, f, op);
    }

    private void validateConditionValue(
            Object raw,
            DataCenter.Definition owner,
            FieldDefinition field,
            RecordQueryOperatorEnum op) {
        if (raw == null
                || raw instanceof Collection<?>
                || raw instanceof Map<?, ?>
                || raw.toString().isBlank()
                || raw.toString().length() > 2000) throw invalid("统计条件值不能为空或超过 2000 字符");
        RecordConditionValues.value(
                field,
                owner.fieldOptions().getOrDefault(field.id(), DataCenter.FieldOptions.defaults()),
                raw,
                op);
    }
}
