package com.richuang.os.nocode.runtime.service.report;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.ApplicationReports;
import com.richuang.os.nocode.enums.ReportDisplayEnum;
import com.richuang.os.nocode.enums.ReportSortByEnum;
import com.richuang.os.nocode.runtime.dal.query.ReportStatement;

import java.util.ArrayList;
import java.util.List;

/**
 * 统计行排序与行数上限的换算：把「配置的排序（sortBy / sortMetricId / descending / pivot.columnDescending）」与「查看时点击列头的临时
 * 排序（Query.sort）」合成一份 {@link ReportStatement.Ordering}。
 *
 * <p>存量统计（没有 sortBy、没有列组降序、请求也不带 sort）返回 null，SQL 走原有排序片段，行为逐字不变。存量透视表「没选排序指标却勾了倒序」
 * 原本就不生效（恒按维度值升序），这里同样保持升序；要降序须在配置里明确选「按维度的值 · 降序」（sortBy=DIMENSION）。
 */
final class ReportOrdering {
    private ReportOrdering() {}

    /** 一次查询返回的行数：填了数字按数字；留空（不限制）时汇总表、透视表取保护值（导出更高），其它展示方式保持原有上限。 */
    static int groupLimit(ApplicationReports.Config config, boolean export) {
        if (config.limit() != null) return config.limit();
        ReportDisplayEnum display = ReportDisplayEnum.fromCode(config.display());
        if (display != ReportDisplayEnum.TABLE && display != ReportDisplayEnum.PIVOT)
            return ApplicationReports.MAX_CHART_GROUPS;
        return export ? ApplicationReports.MAX_EXPORT_ROWS : ApplicationReports.MAX_TABLE_ROWS;
    }

    /**
     * @param sortIndex 配置的排序指标在 metrics 里的下标，没有为空
     * @param pivot 透视表设置（已补默认值）；非透视为空
     */
    static ReportStatement.Ordering of(
            ApplicationReports.Config config,
            ApplicationReports.Sort runtime,
            Integer sortIndex,
            ApplicationReports.Pivot pivot,
            int rowDimensions,
            int columnDimensions) {
        boolean pivotDisplay = pivot != null;
        boolean columnDescending = pivotDisplay && Boolean.TRUE.equals(pivot.columnDescending());
        ReportSortByEnum by =
                config.sortBy() == null ? null : ReportSortByEnum.fromCode(config.sortBy());
        Integer runtimeMetric = null;
        List<String> columnKeys = null;
        if (runtime != null) {
            if (runtime.metricId() != null) {
                if (runtime.dimension() != null) throw invalid("排序只能选择一个指标或一个行维度");
                runtimeMetric = metricIndex(config, runtime.metricId());
            } else if (runtime.dimension() == null
                    || runtime.dimension() < 0
                    || runtime.dimension() >= Math.max(1, rowDimensions)) {
                throw invalid("排序列无效");
            }
            if (runtime.columnGroup() != null && !runtime.columnGroup().isEmpty()) {
                if (!pivotDisplay
                        || runtimeMetric == null
                        || runtime.columnGroup().size() > columnDimensions
                        || runtime.columnGroup().stream()
                                .anyMatch(key -> key != null && key.length() > 2000))
                    throw invalid("排序列组无效");
                columnKeys = runtime.columnGroup();
            }
        }
        if (runtime == null && by == null && !columnDescending) return null;
        // 没有分组（指标卡）时只有一行，无序可排，沿用原有片段。
        if (rowDimensions == 0) return null;
        // 配置的口径：sortBy 为空时与存量一致 —— 有排序指标按指标；没有时汇总表按分组值取 descending，透视表恒升序。
        Integer metric = by == ReportSortByEnum.DIMENSION ? null : sortIndex;
        boolean descending = (by != null || metric != null || !pivotDisplay) && config.descending();
        Integer level = null;
        boolean levelDescending = false;
        if (runtimeMetric != null) {
            metric = runtimeMetric;
            descending = runtime.descending();
        } else if (runtime != null) {
            level = runtime.dimension();
            levelDescending = runtime.descending();
        }
        List<ReportStatement.Term> terms = new ArrayList<>();
        if (pivotDisplay) {
            // 逐层：被点的那一层按维度值；其余层按指标（再按该层原值定序）或按维度值。
            for (int i = 0; i < rowDimensions; i++) {
                if (level != null && level == i) {
                    terms.add(new ReportStatement.Term(null, i, levelDescending));
                } else if (metric != null) {
                    terms.add(new ReportStatement.Term(metric, i, descending));
                    terms.add(new ReportStatement.Term(null, i, false));
                } else {
                    terms.add(new ReportStatement.Term(null, i, descending));
                }
            }
        } else if (level != null) {
            terms.add(new ReportStatement.Term(null, level, levelDescending));
            for (int i = 0; i < rowDimensions; i++)
                if (i != level) terms.add(new ReportStatement.Term(null, i, false));
        } else if (metric != null) {
            terms.add(new ReportStatement.Term(metric, -1, descending));
            for (int i = 0; i < rowDimensions; i++)
                terms.add(new ReportStatement.Term(null, i, false));
        } else {
            for (int i = 0; i < rowDimensions; i++)
                terms.add(new ReportStatement.Term(null, i, descending));
        }
        return new ReportStatement.Ordering(List.copyOf(terms), columnKeys, columnDescending);
    }

    private static int metricIndex(ApplicationReports.Config config, String metricId) {
        for (int i = 0; i < config.metrics().size(); i++)
            if (config.metrics().get(i).id().equals(metricId)) return i;
        throw invalid("排序指标不存在");
    }
}
