package com.richuang.os.nocode.api;

import com.richuang.os.common.dto.DynamicConditionDTO;

import java.util.List;
import java.util.Map;

/** 报表只描述已发布对象的受控读取，字段/关系为稳定 ID；不接受 SQL 或脚本。 */
public final class ApplicationReports {
    private ApplicationReports() {}

    /**
     * 透视表行维度数 + 列维度数的上限（前端 {@code MAX_PIVOT_DIMENSIONS} 同值）。行、列各自不再限个数，只保留这一条技术防护： 小计与合计要按「行维度前缀 ×
     * 列维度前缀」共 (行维度数+1)×(列维度数+1) 组分组集合重新聚合，维度越多分组集合越多。
     */
    public static final int MAX_PIVOT_DIMENSIONS = 10;

    /** 非透视展示方式（汇总表、柱、线、饼）的分组上限，保持不变。 */
    public static final int MAX_GROUP_DIMENSIONS = 2;

    /** 指标卡、柱、线、饼的展示组数上限（保持不变）；这些展示方式的 limit 为空时也按这个数。 */
    public static final int MAX_CHART_GROUPS = 200;

    /**
     * 汇总表、透视表一次查询返回到页面的行数保护值：limit 为空（不限制）时按这个数，填数字时也以它为上限（前端 {@code MAX_REPORT_TABLE_ROWS}
     * 同值）。超出照常截断并标注「共 M 行，只显示前 N 行」，小计与合计仍按全部原始记录聚合。
     */
    public static final int MAX_TABLE_ROWS = 20000;

    /** 导出时「不限制」的行数保护值（高于页面）；填了数字的统计导出行数与页面相同。 */
    public static final int MAX_EXPORT_ROWS = 100000;

    /**
     * 透视表一次返回到页面的叶子格预算：行数超过 {@link #MAX_CHART_GROUPS} 时，行数 × 已展示列组数不超过这个数（行数不低于
     * MAX_CHART_GROUPS）。没有列维度时不起作用（一个列组）；24 个列组时约 4166 行。超出同样截断并标注。
     */
    public static final int MAX_PIVOT_CELLS = 100000;

    /** 导出时的叶子格预算（高于页面）。 */
    public static final int MAX_EXPORT_PIVOT_CELLS = 250000;

    /** 多个数据来源时附加来源的上限（来源 1 之外最多 3 个，共 4 个；前端 {@code MAX_REPORT_EXTRA_SOURCES} 同值）。 */
    public static final int MAX_EXTRA_SOURCES = 3;

    /** 多个数据来源的统计最多的指标数（单来源仍是 5 个；前端 {@code MAX_REPORT_MULTI_SOURCE_METRICS} 同值）。 */
    public static final int MAX_MULTI_SOURCE_METRICS = 10;

    /** relationPath 为最多两段单值关系 ID，以 / 分隔；不接受表列或关系的重新定义。 */
    public record Dimension(String fieldId, String relationPath, String bucket) {}

    /** 条件只收窄此指标；派生指标先聚合再计算，不能接收脚本。 */
    public record Metric(
            String id,
            String name,
            String operation,
            String fieldId,
            DynamicConditionDTO conditions,
            Formula formula,
            Format format,
            // 多个数据来源时基础指标所属的来源（附加来源的 id）；为空 = 来源 1。计算指标恒为空。单来源不出现这个键。
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String sourceId) {
        /** 引入多个数据来源之前的完整形状：属于来源 1（序列化后不出现 sourceId）。 */
        public Metric(
                String id,
                String name,
                String operation,
                String fieldId,
                DynamicConditionDTO conditions,
                Formula formula,
                Format format) {
            this(id, name, operation, fieldId, conditions, formula, format, null);
        }

        public Metric(String id, String name, String operation, String fieldId) {
            this(id, name, operation, fieldId, null, null, null);
        }
    }

    public record Formula(String operator, String left, String right) {}

    public record Format(
            String unit, Integer decimals, boolean percent, String color, boolean financial) {
        public Format(String unit, Integer decimals, boolean percent, String color) {
            this(unit, decimals, percent, color, false);
        }
    }

    public record Chart(
            String barMode, boolean horizontal, boolean labels, String legendPosition) {}

    /** 配置预览引用精确对象版本，只执行读取，不保存草稿或业务记录。 */
    public record Preview(
            String applicationId,
            List<ApplicationCenter.ObjectReference> objects,
            Config config,
            List<ApplicationCenter.Resource> resources) {}

    /**
     * 透视表设置（仅 PIVOT）。各项为空时取默认值：小计、行合计、列合计均开启，占比 NONE，叶子列组上限 24（1..100）。 保存时由校验器补齐为非空值，运行期仍按 {@link
     * #withDefaults()} 兜底旧数据。columnDescending 为列组排序方向：空按列维度原值升序（存量行为），true 降序（如月份新的在前）； 与
     * detailEditable 一样只保存 true 或空。
     */
    public record Pivot(
            Boolean subtotals,
            Boolean rowTotals,
            Boolean columnTotals,
            String percent,
            Integer maxColumnGroups,
            Boolean columnDescending) {
        public static final int DEFAULT_MAX_COLUMN_GROUPS = 24;

        public Pivot(
                Boolean subtotals,
                Boolean rowTotals,
                Boolean columnTotals,
                String percent,
                Integer maxColumnGroups) {
            this(subtotals, rowTotals, columnTotals, percent, maxColumnGroups, null);
        }

        public static Pivot defaults() {
            return new Pivot(true, true, true, "NONE", DEFAULT_MAX_COLUMN_GROUPS);
        }

        public Pivot withDefaults() {
            return new Pivot(
                    subtotals == null || subtotals,
                    rowTotals == null || rowTotals,
                    columnTotals == null || columnTotals,
                    percent == null ? "NONE" : percent,
                    maxColumnGroups == null ? DEFAULT_MAX_COLUMN_GROUPS : maxColumnGroups,
                    Boolean.TRUE.equals(columnDescending) ? Boolean.TRUE : null);
        }
    }

    /**
     * 筛选键为根字段 ID 或 relationPath:fieldId；避免复制业务表中的关联信息。
     *
     * <p>PIVOT 时 dimensions 为行维度（至少 1 个），columnDimensions 为列维度（可为 0 个），两者合计不超过 {@link
     * #MAX_PIVOT_DIMENSIONS}；其它展示方式 dimensions 最多 {@link #MAX_GROUP_DIMENSIONS} 个，columnDimensions
     * 与 pivot 必须为空。detailEditable 为空视为 false，只在配置了 detailViewId 时可为 true，且只能收窄权限：
     * 最终可编辑仍需下钻视图配置了编辑按钮且当前用户对记录有写权限。
     *
     * <p>limit 为空表示不限制：汇总表、透视表按 {@link #MAX_TABLE_ROWS} 保护（导出按 {@link #MAX_EXPORT_ROWS}），其它展示方式按
     * {@link #MAX_CHART_GROUPS}。sortBy 见 {@link
     * com.richuang.os.nocode.enums.ReportSortByEnum}，为空沿用存量排序行为。
     *
     * <p>grain 为统计粒度（{@link com.richuang.os.nocode.enums.ViewGrainEnum} 的代码）：为空或 ROOT
     * 是一条主记录算一行（保存时归一为空）； DETAIL 是 detailId 指向的内部明细的一行算一行，带着所属主记录的字段。明细粒度下字段键还可以是该明细的字段、
     * 或以该明细上的单值关系开头的路径；COUNT 为明细行数，COUNT_ROOT 为去重后的主记录数；主表字段不能求和、平均或做非空计数； detailViewId
     * 必须为空，下钻直接返回命中的明细行。
     */
    public record Config(
            String objectId,
            List<Dimension> dimensions,
            List<Metric> metrics,
            Map<String, Object> equal,
            List<String> filterFieldIds,
            String dateFieldId,
            String timeZone,
            String display,
            String sortMetricId,
            boolean descending,
            Integer limit,
            String detailViewId,
            DynamicConditionDTO conditions,
            Chart chart,
            List<Dimension> columnDimensions,
            Pivot pivot,
            Boolean detailEditable,
            String sortBy,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String grain,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String detailId,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String sourceName,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    List<Source> extraSources,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    List<String> dimensionLabels,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    List<String> columnDimensionLabels) {
        /** 引入多个数据来源之前的完整形状：只有来源 1（四个新分量为空，序列化后不出现这四个键）。 */
        public Config(
                String objectId,
                List<Dimension> dimensions,
                List<Metric> metrics,
                Map<String, Object> equal,
                List<String> filterFieldIds,
                String dateFieldId,
                String timeZone,
                String display,
                String sortMetricId,
                boolean descending,
                Integer limit,
                String detailViewId,
                DynamicConditionDTO conditions,
                Chart chart,
                List<Dimension> columnDimensions,
                Pivot pivot,
                Boolean detailEditable,
                String sortBy,
                String grain,
                String detailId) {
            this(
                    objectId,
                    dimensions,
                    metrics,
                    equal,
                    filterFieldIds,
                    dateFieldId,
                    timeZone,
                    display,
                    sortMetricId,
                    descending,
                    limit,
                    detailViewId,
                    conditions,
                    chart,
                    columnDimensions,
                    pivot,
                    detailEditable,
                    sortBy,
                    grain,
                    detailId,
                    null,
                    null,
                    null,
                    null);
        }

        /** 是否多个数据来源（附加来源非空）。 */
        public boolean multiSource() {
            return extraSources != null && !extraSources.isEmpty();
        }

        /** 引入统计粒度之前的完整形状：按主记录统计（grain、detailId 为空，序列化后不出现这两个键）。 */
        public Config(
                String objectId,
                List<Dimension> dimensions,
                List<Metric> metrics,
                Map<String, Object> equal,
                List<String> filterFieldIds,
                String dateFieldId,
                String timeZone,
                String display,
                String sortMetricId,
                boolean descending,
                Integer limit,
                String detailViewId,
                DynamicConditionDTO conditions,
                Chart chart,
                List<Dimension> columnDimensions,
                Pivot pivot,
                Boolean detailEditable,
                String sortBy) {
            this(
                    objectId,
                    dimensions,
                    metrics,
                    equal,
                    filterFieldIds,
                    dateFieldId,
                    timeZone,
                    display,
                    sortMetricId,
                    descending,
                    limit,
                    detailViewId,
                    conditions,
                    chart,
                    columnDimensions,
                    pivot,
                    detailEditable,
                    sortBy,
                    null,
                    null);
        }

        public Config(
                String objectId,
                List<Dimension> dimensions,
                List<Metric> metrics,
                Map<String, Object> equal,
                List<String> filterFieldIds,
                String dateFieldId,
                String timeZone,
                String display,
                String sortMetricId,
                boolean descending,
                Integer limit,
                String detailViewId,
                DynamicConditionDTO conditions,
                Chart chart,
                List<Dimension> columnDimensions,
                Pivot pivot,
                Boolean detailEditable) {
            this(
                    objectId,
                    dimensions,
                    metrics,
                    equal,
                    filterFieldIds,
                    dateFieldId,
                    timeZone,
                    display,
                    sortMetricId,
                    descending,
                    limit,
                    detailViewId,
                    conditions,
                    chart,
                    columnDimensions,
                    pivot,
                    detailEditable,
                    null);
        }

        public Config(
                String objectId,
                List<Dimension> dimensions,
                List<Metric> metrics,
                Map<String, Object> equal,
                List<String> filterFieldIds,
                String dateFieldId,
                String timeZone,
                String display,
                String sortMetricId,
                boolean descending,
                Integer limit,
                String detailViewId,
                DynamicConditionDTO conditions,
                Chart chart) {
            this(
                    objectId,
                    dimensions,
                    metrics,
                    equal,
                    filterFieldIds,
                    dateFieldId,
                    timeZone,
                    display,
                    sortMetricId,
                    descending,
                    limit,
                    detailViewId,
                    conditions,
                    chart,
                    null,
                    null,
                    null);
        }

        public Config(
                String objectId,
                List<Dimension> dimensions,
                List<Metric> metrics,
                Map<String, Object> equal,
                List<String> filterFieldIds,
                String dateFieldId,
                String timeZone,
                String display,
                String sortMetricId,
                boolean descending,
                Integer limit,
                String detailViewId) {
            this(
                    objectId,
                    dimensions,
                    metrics,
                    equal,
                    filterFieldIds,
                    dateFieldId,
                    timeZone,
                    display,
                    sortMetricId,
                    descending,
                    limit,
                    detailViewId,
                    null,
                    null);
        }

        /** 只替换指标，其余配置（含列维度、透视选项、明细可编辑、排序依据）原样保留；重建配置一律走这里，避免漏传新增分量。 */
        public Config withMetrics(List<Metric> metrics) {
            return new Config(
                    objectId,
                    dimensions,
                    metrics,
                    equal,
                    filterFieldIds,
                    dateFieldId,
                    timeZone,
                    display,
                    sortMetricId,
                    descending,
                    limit,
                    detailViewId,
                    conditions,
                    chart,
                    columnDimensions,
                    pivot,
                    detailEditable,
                    sortBy,
                    grain,
                    detailId,
                    sourceName,
                    extraSources,
                    dimensionLabels,
                    columnDimensionLabels);
        }
    }

    /**
     * 附加来源（来源 2 起）。字段键相对本来源的 objectId（与本来源的粒度）解析，写法与顶层相同。dimensions / columnDimensions 与顶层等长、逐位对应（第
     * i 个是本来源用来对齐顶层第 i 个行 / 列维度的字段）；filterTargets 的键是顶层 filterFieldIds 里的键， 值是本来源的字段键。 detailViewId
     * / detailEditable 是本来源的下钻明细视图与允许编辑开关（与顶层同一口径，只收不放）。
     */
    public record Source(
            String id,
            String name,
            String objectId,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String grain,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String detailId,
            List<Dimension> dimensions,
            List<Dimension> columnDimensions,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    DynamicConditionDTO conditions,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String dateFieldId,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Map<String, String> filterTargets,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String detailViewId,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    Boolean detailEditable) {
        /** 不挂下钻明细视图的附加来源（契约第一版的十个分量）。 */
        public Source(
                String id,
                String name,
                String objectId,
                String grain,
                String detailId,
                List<Dimension> dimensions,
                List<Dimension> columnDimensions,
                DynamicConditionDTO conditions,
                String dateFieldId,
                Map<String, String> filterTargets) {
            this(
                    id,
                    name,
                    objectId,
                    grain,
                    detailId,
                    dimensions,
                    columnDimensions,
                    conditions,
                    dateFieldId,
                    filterTargets,
                    null,
                    null);
        }
    }

    /** 页面筛选的字段映射仅用于交互，服务器仍根据各报表白名单校验请求。 */
    public record Filter(
            String id,
            String name,
            String objectId,
            String fieldId,
            boolean dateRange,
            Map<String, String> targets) {}

    /**
     * 查看时点击列头的临时排序，只改变本次返回的行顺序（及截断时保留哪些行），不改配置；导出带同一个 sort 即与屏幕同序。
     *
     * <p>metricId 非空：按该指标排序，透视表里 columnGroup 为所点列组的列键前缀（缺省或 [] = 行合计列组），多层行维度时每层在上级分组内 排序。metricId
     * 为空：按第 dimension 个行维度（0 起）的值排序；透视表里只改这一层，其余层保持配置的排序，汇总表里该列为第一排序键。
     */
    public record Sort(
            String metricId, Integer dimension, List<String> columnGroup, boolean descending) {}

    /**
     * group 为行键前缀（非 PIVOT 时必须与维度数相等）；columnGroup 仅 PIVOT 下钻使用，为列键前缀，缺省或 [] 表示不按列限定。 sort
     * 为查看时的临时排序，缺省按配置。
     */
    public record Query(
            String applicationId,
            String reportId,
            Map<String, Object> equal,
            String dateFrom,
            String dateTo,
            ApplicationRecords.Context context,
            List<String> group,
            int pageNo,
            int pageSize,
            DynamicConditionDTO conditions,
            String metricId,
            List<String> columnGroup,
            Sort sort,
            // 仅多个数据来源的下钻：指定来源；一般不用传，由 metricId 推出。
            String sourceId) {
        public Query(
                String applicationId,
                String reportId,
                Map<String, Object> equal,
                String dateFrom,
                String dateTo,
                ApplicationRecords.Context context,
                List<String> group,
                int pageNo,
                int pageSize,
                DynamicConditionDTO conditions,
                String metricId,
                List<String> columnGroup,
                Sort sort) {
            this(
                    applicationId,
                    reportId,
                    equal,
                    dateFrom,
                    dateTo,
                    context,
                    group,
                    pageNo,
                    pageSize,
                    conditions,
                    metricId,
                    columnGroup,
                    sort,
                    null);
        }

        public Query(
                String applicationId,
                String reportId,
                Map<String, Object> equal,
                String dateFrom,
                String dateTo,
                ApplicationRecords.Context context,
                List<String> group,
                int pageNo,
                int pageSize,
                DynamicConditionDTO conditions,
                String metricId,
                List<String> columnGroup) {
            this(
                    applicationId,
                    reportId,
                    equal,
                    dateFrom,
                    dateTo,
                    context,
                    group,
                    pageNo,
                    pageSize,
                    conditions,
                    metricId,
                    columnGroup,
                    null);
        }

        public Query(
                String applicationId,
                String reportId,
                Map<String, Object> equal,
                String dateFrom,
                String dateTo,
                ApplicationRecords.Context context,
                List<String> group,
                int pageNo,
                int pageSize,
                DynamicConditionDTO conditions,
                String metricId) {
            this(
                    applicationId,
                    reportId,
                    equal,
                    dateFrom,
                    dateTo,
                    context,
                    group,
                    pageNo,
                    pageSize,
                    conditions,
                    metricId,
                    null);
        }

        public Query(
                String applicationId,
                String reportId,
                Map<String, Object> equal,
                String dateFrom,
                String dateTo,
                ApplicationRecords.Context context,
                List<String> group,
                int pageNo,
                int pageSize) {
            this(
                    applicationId,
                    reportId,
                    equal,
                    dateFrom,
                    dateTo,
                    context,
                    group,
                    pageNo,
                    pageSize,
                    null,
                    null);
        }
    }

    /** 所有指标是精确十进制字符串；NULL 与 0 不混淆。维度原值用于安全下钻。 */
    public record Group(List<String> keys, List<String> labels, Map<String, String> values) {}

    /** PIVOT 结果；非 PIVOT 为空。 */
    public record Result(
            List<String> dimensionNames,
            List<Metric> metrics,
            List<Group> groups,
            Map<String, String> totals,
            long totalGroups,
            long recordCount,
            boolean canExport,
            String timeZone,
            PivotResult pivot,
            // 仅明细粒度给出：所选明细的名称；此时 recordCount 是来源明细行数而不是主记录数。
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String detailName,
            // 仅多个数据来源给出：各来源的条数（顺序 = 来源 1、附加来源按配置顺序）；此时 recordCount 是各来源条数之和、detailName 为空。
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    List<SourceSummary> sources) {
        /** 单来源的结果：没有各来源条数（序列化后不出现 sources 键）。 */
        public Result(
                List<String> dimensionNames,
                List<Metric> metrics,
                List<Group> groups,
                Map<String, String> totals,
                long totalGroups,
                long recordCount,
                boolean canExport,
                String timeZone,
                PivotResult pivot,
                String detailName) {
            this(
                    dimensionNames,
                    metrics,
                    groups,
                    totals,
                    totalGroups,
                    recordCount,
                    canExport,
                    timeZone,
                    pivot,
                    detailName,
                    null);
        }

        /** 按主记录统计的结果：没有明细名称（序列化后不出现 detailName 键）。 */
        public Result(
                List<String> dimensionNames,
                List<Metric> metrics,
                List<Group> groups,
                Map<String, String> totals,
                long totalGroups,
                long recordCount,
                boolean canExport,
                String timeZone,
                PivotResult pivot) {
            this(
                    dimensionNames,
                    metrics,
                    groups,
                    totals,
                    totalGroups,
                    recordCount,
                    canExport,
                    timeZone,
                    pivot,
                    null);
        }

        public Result(
                List<String> dimensionNames,
                List<Metric> metrics,
                List<Group> groups,
                Map<String, String> totals,
                long totalGroups,
                long recordCount,
                boolean canExport,
                String timeZone) {
            this(
                    dimensionNames,
                    metrics,
                    groups,
                    totals,
                    totalGroups,
                    recordCount,
                    canExport,
                    timeZone,
                    null);
        }
    }

    /** 多个数据来源时一个来源的条数：来源 1 的 id 固定为 main；detailName 仅该来源按明细行统计时给出（recordCount 为明细行数）。 */
    public record SourceSummary(
            String id,
            String name,
            String objectId,
            long recordCount,
            @com.fasterxml.jackson.annotation.JsonInclude(
                            com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
                    String detailName) {}

    /** 叶子行或叶子列组表头：keys 为维度原值（下钻用），labels 为显示文本。 */
    public record PivotHeader(List<String> keys, List<String> labels) {}

    /**
     * 透视格。rowKeys/columnKeys 为前缀：长度等于维度数为叶子，更短为该层小计，[] 为合计（行合计列组或列合计行）。 values 由后端按原始记录重新聚合，
     * 不由叶子相加；ratios 仅 percent≠NONE 时给出，分母为 0 或任一方为空时为 null。
     */
    public record PivotCell(
            List<String> rowKeys,
            List<String> columnKeys,
            Map<String, String> values,
            Map<String, String> ratios) {}

    /** 行按配置的排序（指标取合计列组上的值，或维度原值）或查看时点击列头的临时排序；列组按维度原值升序或降序、空值最后。 截断只影响展示的叶子，不改变小计与合计口径。 */
    public record PivotResult(
            List<String> rowDimensionNames,
            List<String> columnDimensionNames,
            List<PivotHeader> rows,
            List<PivotHeader> columns,
            List<PivotCell> cells,
            boolean rowsTruncated,
            boolean columnsTruncated,
            long totalRowGroups,
            long totalColumnGroups) {}

    /**
     * 数据视图运行期列表的「统计下钻」参数。服务端按与 report-details 相同的条件构造得到记录集，再与视图自身条件取交集。 context 与 {@link
     * Query#context()} 同义：统计放在记录页区块内时必须随下钻带上，保证与格子数值同一范围。
     */
    public record Drill(
            String applicationId,
            String reportId,
            List<String> group,
            List<String> columnGroup,
            String metricId,
            Map<String, Object> equal,
            String dateFrom,
            String dateTo,
            DynamicConditionDTO conditions,
            ApplicationRecords.Context context) {
        /** 转为 report-details 的同一查询；分页参数只用于满足统计请求校验，记录集本身不分页。 */
        public Query query() {
            return new Query(
                    applicationId,
                    reportId,
                    equal,
                    dateFrom,
                    dateTo,
                    context,
                    group,
                    1,
                    100,
                    conditions,
                    metricId,
                    columnGroup);
        }
    }
}
