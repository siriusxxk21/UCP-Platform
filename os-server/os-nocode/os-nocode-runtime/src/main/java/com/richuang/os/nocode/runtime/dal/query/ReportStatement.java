package com.richuang.os.nocode.runtime.dal.query;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.richuang.os.nocode.api.ApplicationReports;
import com.richuang.os.nocode.enums.ReportBucketEnum;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 经对象版本、物理目录与实时权限编译的内部参数；HTTP 不能直接构造。exactRollup 仅透视表使用：求和/平均的列都是精确数值类型（整数、numeric）时为
 * true，透视查询先按叶子预聚合再做分组集合（结果逐位不变）；有浮点列时为 false，直接对原始记录做分组集合，避免浮点累加顺序变化。
 *
 * <p>cellBudget 仅透视表使用：groupLimit 超过 {@link ApplicationReports#MAX_CHART_GROUPS}（原上限）时，展示的行数再受「行数 ×
 * 已展示列组数 ≤ cellBudget」约束（不低于原上限），避免行多、列组也多时一次返回几十万格；0 表示不设。
 *
 * <p>detail 非空 = 按该内部明细的行统计：取数在主表 t 之后内连接明细表（别名 g），一条明细行算一行；为空 = 按主记录统计， 渲染出的 SQL
 * 与引入统计粒度前相同。metricAliases 与 metricColumns 等长，给出每个指标列所在的别名（主表 t、明细 g；无字段的指标为空）。
 *
 * <p>sources 非空 = 多个数据来源：每个来源是一份编好的单来源语句，取数时逐个来源 UNION ALL 成同一张逐行表，之后的分组、排序、截断与单来源同一段 SQL； 此时顶层的
 * dimensions / columnDimensions 是来源 1 的（只用它们的个数、numeric 与分组方式），metrics 是合并后的全部指标， predicates
 * 对每个非公式指标含键 m{k}（空条件，只用来让聚合带上 FILTER），exactRollup 恒为 false。为空 = 单来源，渲染出的 SQL 与引入多来源前相同。
 */
public record ReportStatement(
        RecordStatement base,
        List<Dimension> dimensions,
        List<Join> joins,
        List<Filter> filters,
        List<ApplicationReports.Metric> metrics,
        List<String> metricColumns,
        String dateColumn,
        boolean dateWithZone,
        String timeZone,
        String dateFrom,
        String dateToExclusive,
        List<String> group,
        int groupLimit,
        Integer sortIndex,
        boolean descending,
        Map<String, QueryWrapper<Object>> predicates,
        Integer drillMetricIndex,
        List<Dimension> columnDimensions,
        List<String> columnGroup,
        int columnLimit,
        boolean exactRollup,
        Ordering ordering,
        int cellBudget,
        Detail detail,
        List<String> metricAliases,
        List<Source> sources,
        List<Dimension> pathDimensions,
        List<String> path,
        List<TextFilter> textFilters) {
    /** 不带新排序口径（ordering 为空）、不设格数预算、按主记录统计：行、列组走原有排序片段，行数只按 groupLimit；指标列都在主表上（别名 t）。 */
    public ReportStatement(
            RecordStatement base,
            List<Dimension> dimensions,
            List<Join> joins,
            List<Filter> filters,
            List<ApplicationReports.Metric> metrics,
            List<String> metricColumns,
            String dateColumn,
            boolean dateWithZone,
            String timeZone,
            String dateFrom,
            String dateToExclusive,
            List<String> group,
            int groupLimit,
            Integer sortIndex,
            boolean descending,
            Map<String, QueryWrapper<Object>> predicates,
            Integer drillMetricIndex,
            List<Dimension> columnDimensions,
            List<String> columnGroup,
            int columnLimit,
            boolean exactRollup) {
        this(
                base,
                dimensions,
                joins,
                filters,
                metrics,
                metricColumns,
                dateColumn,
                dateWithZone,
                timeZone,
                dateFrom,
                dateToExclusive,
                group,
                groupLimit,
                sortIndex,
                descending,
                predicates,
                drillMetricIndex,
                columnDimensions,
                columnGroup,
                columnLimit,
                exactRollup,
                null,
                0,
                null,
                mainAliases(metricColumns));
    }

    /**
     * 明细行下钻（ReportMapper.detailRows）的参数：本语句的全部分量按原名摊开，取数与下钻两个 SQL 片段原样复用、与计数同一份条件； 再加一项 rows ——
     * 所选明细自己的记录语句，只用来投影明细行的字段。按分量逐个取，以后新增分量不用改这里。
     */
    public Map<String, Object> withDetailRows(RecordStatement rows) {
        Map<String, Object> parameters = new HashMap<>();
        try {
            for (java.lang.reflect.RecordComponent component :
                    ReportStatement.class.getRecordComponents())
                parameters.put(component.getName(), component.getAccessor().invoke(this));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        if (parameters.put("rows", rows) != null) throw new IllegalStateException("rows 已是统计语句的分量");
        return parameters;
    }

    private static List<String> mainAliases(List<String> metricColumns) {
        List<String> aliases = new ArrayList<>();
        for (String column : metricColumns) aliases.add(column == null ? null : "t");
        return aliases;
    }

    /** 非透视报表：没有列维度。group 必须与维度数相等（由服务校验），渲染结果与引入列维度前完全一致。 */
    public ReportStatement(
            RecordStatement base,
            List<Dimension> dimensions,
            List<Join> joins,
            List<Filter> filters,
            List<ApplicationReports.Metric> metrics,
            List<String> metricColumns,
            String dateColumn,
            boolean dateWithZone,
            String timeZone,
            String dateFrom,
            String dateToExclusive,
            List<String> group,
            int groupLimit,
            Integer sortIndex,
            boolean descending,
            Map<String, QueryWrapper<Object>> predicates,
            Integer drillMetricIndex) {
        this(
                base,
                dimensions,
                joins,
                filters,
                metrics,
                metricColumns,
                dateColumn,
                dateWithZone,
                timeZone,
                dateFrom,
                dateToExclusive,
                group,
                groupLimit,
                sortIndex,
                descending,
                predicates,
                drillMetricIndex,
                List.of(),
                null,
                0,
                false,
                null,
                0,
                null,
                mainAliases(metricColumns));
    }

    /**
     * 配置了排序依据、列组降序，或查看时点了列头时的排序口径；为空时两条统计语句都走原有排序片段（sortIndex / descending），存量统计逐字不变。
     *
     * <p>terms 按先后构成 ORDER BY。汇总表（result）：指标项取 m{metricIndex}，维度项取 d{dimensionIndex}。透视表（pivot）：每层
     * 行维度依次出现，指标项取该层行分组在 columnKeys 所指列组（空 = 行合计）上的格，维度项取该层原值，所以叶子不跨上层分组交错、小计仍跟着分组。
     * 数值维度按数值大小比较，空值恒在最后。columnDescending 为透视表列组方向。
     */
    public record Ordering(List<Term> terms, List<String> columnKeys, boolean columnDescending) {
        /** 透视表是否需要按某个列组取排序用的格。 */
        public boolean usesMetric() {
            return terms.stream().anyMatch(term -> term.metricIndex() != null);
        }

        /** 透视表第 level 层行分组是否按指标排序（需要连接该层的排序格）。 */
        public boolean metricLevel(int level) {
            return terms.stream()
                    .anyMatch(term -> term.metricIndex() != null && term.dimensionIndex() == level);
        }
    }

    /** metricIndex 非空为指标项（透视表里 dimensionIndex 为所在层，汇总表里不用）；为空为维度项。 */
    public record Term(Integer metricIndex, int dimensionIndex, boolean descending) {}

    /** numeric 仅影响透视表的行列排序：按原值数值大小，而非文本字典序。 */
    public record Dimension(
            String alias,
            String column,
            ReportBucketEnum bucket,
            boolean withZone,
            boolean numeric) {
        public Dimension(String alias, String column, ReportBucketEnum bucket, boolean withZone) {
            this(alias, column, bucket, withZone, false);
        }
    }

    /** 单值引用仅连接目录确认的唯一主键；受限关联对象在 JOIN 中同步过滤。 */
    public record Join(
            String schema,
            String table,
            String key,
            String sourceAlias,
            String sourceColumn,
            String creator,
            boolean deleted) {}

    public record Filter(
            String alias, String schema, String table, String column, String payload) {}

    /** 统计粒度所选内部明细的物理表：parentColumn 为归属列（= 主表主键），deleted 为是否有逻辑删除列。 */
    public record Detail(
            String schema, String table, String key, String parentColumn, boolean deleted) {}

    public ReportStatement(
            RecordStatement base,
            List<Dimension> dimensions,
            List<Join> joins,
            List<Filter> filters,
            List<ApplicationReports.Metric> metrics,
            List<String> metricColumns,
            String dateColumn,
            boolean dateWithZone,
            String timeZone,
            String dateFrom,
            String dateToExclusive,
            List<String> group,
            int groupLimit,
            Integer sortIndex,
            boolean descending,
            Map<String, QueryWrapper<Object>> predicates,
            Integer drillMetricIndex,
            List<Dimension> columnDimensions,
            List<String> columnGroup,
            int columnLimit,
            boolean exactRollup,
            Ordering ordering,
            int cellBudget,
            Detail detail,
            List<String> metricAliases) {
        this(
                base,
                dimensions,
                joins,
                filters,
                metrics,
                metricColumns,
                dateColumn,
                dateWithZone,
                timeZone,
                dateFrom,
                dateToExclusive,
                group,
                groupLimit,
                sortIndex,
                descending,
                predicates,
                drillMetricIndex,
                columnDimensions,
                columnGroup,
                columnLimit,
                exactRollup,
                ordering,
                cellBudget,
                detail,
                metricAliases,
                List.of(),
                List.of(),
                List.of());
    }

    public ReportStatement(
            RecordStatement base,
            List<Dimension> dimensions,
            List<Join> joins,
            List<Filter> filters,
            List<ApplicationReports.Metric> metrics,
            List<String> metricColumns,
            String dateColumn,
            boolean dateWithZone,
            String timeZone,
            String dateFrom,
            String dateToExclusive,
            List<String> group,
            int groupLimit,
            Integer sortIndex,
            boolean descending,
            Map<String, QueryWrapper<Object>> predicates,
            Integer drillMetricIndex,
            List<Dimension> columnDimensions,
            List<String> columnGroup,
            int columnLimit) {
        this(
                base,
                dimensions,
                joins,
                filters,
                metrics,
                metricColumns,
                dateColumn,
                dateWithZone,
                timeZone,
                dateFrom,
                dateToExclusive,
                group,
                groupLimit,
                sortIndex,
                descending,
                predicates,
                drillMetricIndex,
                columnDimensions,
                columnGroup,
                columnLimit,
                false,
                null,
                0,
                null,
                mainAliases(metricColumns),
                List.of(),
                List.of(),
                List.of());
    }

    public ReportStatement(
            RecordStatement base,
            List<Dimension> dimensions,
            List<Join> joins,
            List<Filter> filters,
            List<ApplicationReports.Metric> metrics,
            List<String> metricColumns,
            String dateColumn,
            boolean dateWithZone,
            String timeZone,
            String dateFrom,
            String dateToExclusive,
            List<String> group,
            int groupLimit,
            Integer sortIndex,
            boolean descending,
            Map<String, QueryWrapper<Object>> predicates,
            Integer drillMetricIndex,
            List<Dimension> columnDimensions,
            List<String> columnGroup,
            int columnLimit,
            List<Dimension> pathDimensions,
            List<String> path,
            List<TextFilter> textFilters) {
        this(
                base,
                dimensions,
                joins,
                filters,
                metrics,
                metricColumns,
                dateColumn,
                dateWithZone,
                timeZone,
                dateFrom,
                dateToExclusive,
                group,
                groupLimit,
                sortIndex,
                descending,
                predicates,
                drillMetricIndex,
                columnDimensions,
                columnGroup,
                columnLimit,
                false,
                null,
                0,
                null,
                mainAliases(metricColumns),
                pathDimensions,
                path,
                textFilters);
    }

    /** 看板文本筛选只绑定值，不拼接未授权字段。 */
    public record TextFilter(Dimension field, String value) {}

    /** 多个数据来源（应用统计报表，xiaxihan R6）：不带看板钻取路径与文本筛选（报表中心看板只用于报表中心数据集，从不带 sources）。 */
    public ReportStatement(
            RecordStatement base,
            List<Dimension> dimensions,
            List<Join> joins,
            List<Filter> filters,
            List<ApplicationReports.Metric> metrics,
            List<String> metricColumns,
            String dateColumn,
            boolean dateWithZone,
            String timeZone,
            String dateFrom,
            String dateToExclusive,
            List<String> group,
            int groupLimit,
            Integer sortIndex,
            boolean descending,
            Map<String, QueryWrapper<Object>> predicates,
            Integer drillMetricIndex,
            List<Dimension> columnDimensions,
            List<String> columnGroup,
            int columnLimit,
            boolean exactRollup,
            Ordering ordering,
            int cellBudget,
            Detail detail,
            List<String> metricAliases,
            List<Source> sources) {
        this(
                base,
                dimensions,
                joins,
                filters,
                metrics,
                metricColumns,
                dateColumn,
                dateWithZone,
                timeZone,
                dateFrom,
                dateToExclusive,
                group,
                groupLimit,
                sortIndex,
                descending,
                predicates,
                drillMetricIndex,
                columnDimensions,
                columnGroup,
                columnLimit,
                exactRollup,
                ordering,
                cellBudget,
                detail,
                metricAliases,
                sources,
                List.of(),
                List.of(),
                List.of());
    }

    /** 报表中心看板的钻取路径与文本筛选（dev 报表中心）：单来源，sources 为空。 */
    public ReportStatement(
            RecordStatement base,
            List<Dimension> dimensions,
            List<Join> joins,
            List<Filter> filters,
            List<ApplicationReports.Metric> metrics,
            List<String> metricColumns,
            String dateColumn,
            boolean dateWithZone,
            String timeZone,
            String dateFrom,
            String dateToExclusive,
            List<String> group,
            int groupLimit,
            Integer sortIndex,
            boolean descending,
            Map<String, QueryWrapper<Object>> predicates,
            Integer drillMetricIndex,
            List<Dimension> columnDimensions,
            List<String> columnGroup,
            int columnLimit,
            boolean exactRollup,
            Ordering ordering,
            int cellBudget,
            Detail detail,
            List<String> metricAliases,
            List<Dimension> pathDimensions,
            List<String> path,
            List<TextFilter> textFilters) {
        this(
                base,
                dimensions,
                joins,
                filters,
                metrics,
                metricColumns,
                dateColumn,
                dateWithZone,
                timeZone,
                dateFrom,
                dateToExclusive,
                group,
                groupLimit,
                sortIndex,
                descending,
                predicates,
                drillMetricIndex,
                columnDimensions,
                columnGroup,
                columnLimit,
                exactRollup,
                ordering,
                cellBudget,
                detail,
                metricAliases,
                null,
                pathDimensions,
                path,
                textFilters);
    }

    /** 多来源的一个分支：该来源编好的单来源语句 + 合并后每个指标在本分支的取值方式。 */
    public record Source(ReportStatement statement, List<Slot> slots) {}

    /**
     * 与合并后的 metrics 等长；公式指标为 null。local 非空 = 本来源的指标（statement.metrics 里的下标）；column / alias
     * 为本分支的指标列（无字段的指标为 null）；nullType 为其它来源分支里该指标列的空占位类型（无字段为 null）。
     */
    public record Slot(Integer local, String column, String alias, String nullType) {}
}
