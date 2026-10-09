package com.lingan.ucp.nocode.runtime.service.report;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.ApplicationReports;
import com.lingan.ucp.nocode.runtime.dal.query.ReportStatement;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

/** 排序口径换算（不连库）：存量配置不产生新口径（SQL 走原有片段）；配置的排序依据、列组降序与点列头的临时排序如何合成每一层的排序项； 行数留空时各展示方式取哪个上限。 */
class ReportOrderingTest {
    private static final ApplicationReports.Pivot PIVOT = ApplicationReports.Pivot.defaults();

    private static ApplicationReports.Config config(
            String display, String sortMetricId, boolean descending, Integer limit, String sortBy) {
        return new ApplicationReports.Config(
                "object",
                List.of(),
                List.of(
                        new ApplicationReports.Metric("income", "入金", "SUM", "f1"),
                        new ApplicationReports.Metric("expense", "出金", "SUM", "f2")),
                Map.of(),
                List.of(),
                null,
                "Asia/Shanghai",
                display,
                sortMetricId,
                descending,
                limit,
                null,
                null,
                null,
                null,
                null,
                null,
                sortBy);
    }

    private static ReportStatement.Term metric(int index, int level, boolean descending) {
        return new ReportStatement.Term(index, level, descending);
    }

    private static ReportStatement.Term value(int level, boolean descending) {
        return new ReportStatement.Term(null, level, descending);
    }

    private static ApplicationReports.Sort byMetric(
            String id, List<String> columnGroup, boolean descending) {
        return new ApplicationReports.Sort(id, null, columnGroup, descending);
    }

    private static ApplicationReports.Sort byDimension(int index, boolean descending) {
        return new ApplicationReports.Sort(null, index, null, descending);
    }

    /** 存量：没有 sortBy、没有列组降序、请求不带 sort ⇒ null，两条语句都走原有排序片段。 */
    @Test
    void legacyConfigurationsProduceNoOrdering() {
        assertThat(
                        ReportOrdering.of(
                                config("PIVOT", null, true, 30, null), null, null, PIVOT, 1, 0))
                .isNull();
        assertThat(
                        ReportOrdering.of(
                                config("PIVOT", "income", true, 30, null), null, 0, PIVOT, 2, 1))
                .isNull();
        assertThat(ReportOrdering.of(config("TABLE", null, true, 30, null), null, null, null, 2, 0))
                .isNull();
        assertThat(
                        ReportOrdering.of(
                                config("BAR", "expense", false, 30, null), null, 1, null, 1, 0))
                .isNull();
        // 指标卡没有分组：即使带了排序也无序可排
        assertThat(
                        ReportOrdering.of(
                                config("METRIC", null, false, null, null),
                                byMetric("income", null, true),
                                null,
                                null,
                                0,
                                0))
                .isNull();
    }

    /** 配置的排序依据：按维度的值时每层一个维度项、方向取 descending；按指标时每层先指标、再该层原值升序定序。 */
    @Test
    void configuredSortByBuildsOneBlockPerRowLevel() {
        assertThat(
                        ReportOrdering.of(
                                        config("PIVOT", null, true, null, "DIMENSION"),
                                        null,
                                        null,
                                        PIVOT,
                                        2,
                                        1)
                                .terms())
                .containsExactly(value(0, true), value(1, true));
        ReportStatement.Ordering byMetric =
                ReportOrdering.of(
                        config("PIVOT", "expense", true, null, "METRIC"), null, 1, PIVOT, 2, 1);
        assertThat(byMetric.terms())
                .containsExactly(
                        metric(1, 0, true), value(0, false), metric(1, 1, true), value(1, false));
        assertThat(byMetric.columnKeys()).isNull();
        assertThat(byMetric.columnDescending()).isFalse();
        assertThat(byMetric.usesMetric()).isTrue();
        assertThat(byMetric.metricLevel(0)).isTrue();
        assertThat(byMetric.metricLevel(1)).isTrue();
        assertThat(byMetric.metricLevel(2)).isFalse();
        // 汇总表：指标在最前，各分组依次升序；按分组的值时各分组同向
        assertThat(
                        ReportOrdering.of(
                                        config("TABLE", "income", false, null, "METRIC"),
                                        null,
                                        0,
                                        null,
                                        2,
                                        0)
                                .terms())
                .containsExactly(metric(0, -1, false), value(0, false), value(1, false));
        assertThat(
                        ReportOrdering.of(
                                        config("TABLE", null, true, null, "DIMENSION"),
                                        null,
                                        null,
                                        null,
                                        2,
                                        0)
                                .terms())
                .containsExactly(value(0, true), value(1, true));
    }

    /** 只配了列组降序的存量透视表：行的排序项等价于存量（没选指标恒升序、选了指标按指标），勾着的倒序不因此突然生效。 */
    @Test
    void columnDescendingAloneKeepsTheLegacyRowOrder() {
        ApplicationReports.Pivot descending =
                new ApplicationReports.Pivot(true, true, true, "NONE", 24, true);
        ReportStatement.Ordering plain =
                ReportOrdering.of(
                        config("PIVOT", null, true, 30, null), null, null, descending, 2, 1);
        assertThat(plain.columnDescending()).isTrue();
        assertThat(plain.terms()).containsExactly(value(0, false), value(1, false));
        assertThat(plain.usesMetric()).isFalse();
        assertThat(
                        ReportOrdering.of(
                                        config("PIVOT", "income", true, 30, null),
                                        null,
                                        0,
                                        descending,
                                        1,
                                        1)
                                .terms())
                .containsExactly(metric(0, 0, true), value(0, false));
    }

    /** 点列头：指标盖过配置（带所点列组）；行维度只改那一层（汇总表里成为第一排序键）。 */
    @Test
    void runtimeSortOverridesTheConfiguredOrder() {
        ApplicationReports.Config configured = config("PIVOT", null, true, null, "DIMENSION");
        ReportStatement.Ordering clicked =
                ReportOrdering.of(
                        configured,
                        byMetric("expense", List.of("2026-09"), false),
                        null,
                        PIVOT,
                        2,
                        1);
        assertThat(clicked.terms())
                .containsExactly(
                        metric(1, 0, false), value(0, false), metric(1, 1, false), value(1, false));
        assertThat(clicked.columnKeys()).containsExactly("2026-09");
        // 空的列键 = 行合计列组
        assertThat(
                        ReportOrdering.of(
                                        configured,
                                        byMetric("income", List.of(), true),
                                        null,
                                        PIVOT,
                                        1,
                                        1)
                                .columnKeys())
                .isNull();
        // 存量配置 + 点第 2 层行维度：第 1 层保持存量的升序
        assertThat(
                        ReportOrdering.of(
                                        config("PIVOT", null, true, 30, null),
                                        byDimension(1, true),
                                        null,
                                        PIVOT,
                                        2,
                                        0)
                                .terms())
                .containsExactly(value(0, false), value(1, true));
        // 配置按指标 + 点第 1 层行维度：第 2 层仍按指标
        assertThat(
                        ReportOrdering.of(
                                        config("PIVOT", "income", true, 30, null),
                                        byDimension(0, false),
                                        0,
                                        PIVOT,
                                        2,
                                        0)
                                .terms())
                .containsExactly(value(0, false), metric(0, 1, true), value(1, false));
        // 汇总表点第 2 个分组列：它排第一，其余分组升序
        assertThat(
                        ReportOrdering.of(
                                        config("TABLE", "income", true, 30, null),
                                        byDimension(1, true),
                                        0,
                                        null,
                                        2,
                                        0)
                                .terms())
                .containsExactly(value(1, true), value(0, false));
        assertThat(
                        ReportOrdering.of(
                                        config("TABLE", null, false, 30, null),
                                        byMetric("expense", null, true),
                                        null,
                                        null,
                                        1,
                                        0)
                                .terms())
                .containsExactly(metric(1, -1, true), value(0, false));
    }

    @Test
    void invalidRuntimeSortIsRejected() {
        ApplicationReports.Config pivot = config("PIVOT", null, false, null, null);
        assertThatThrownBy(
                        () ->
                                ReportOrdering.of(
                                        pivot, byMetric("missing", null, true), null, PIVOT, 1, 1))
                .hasMessageContaining("排序指标不存在");
        assertThatThrownBy(() -> ReportOrdering.of(pivot, byDimension(1, true), null, PIVOT, 1, 1))
                .hasMessageContaining("排序列无效");
        assertThatThrownBy(() -> ReportOrdering.of(pivot, byDimension(-1, true), null, PIVOT, 1, 1))
                .hasMessageContaining("排序列无效");
        assertThatThrownBy(
                        () ->
                                ReportOrdering.of(
                                        pivot,
                                        new ApplicationReports.Sort(null, null, null, true),
                                        null,
                                        PIVOT,
                                        1,
                                        1))
                .hasMessageContaining("排序列无效");
        assertThatThrownBy(
                        () ->
                                ReportOrdering.of(
                                        pivot,
                                        new ApplicationReports.Sort("income", 0, null, true),
                                        null,
                                        PIVOT,
                                        1,
                                        1))
                .hasMessageContaining("排序只能选择一个指标或一个行维度");
        // 列键比列维度多、按行维度排序却带列键、汇总表带列键
        assertThatThrownBy(
                        () ->
                                ReportOrdering.of(
                                        pivot,
                                        byMetric("income", List.of("2026", "09"), true),
                                        null,
                                        PIVOT,
                                        1,
                                        1))
                .hasMessageContaining("排序列组无效");
        assertThatThrownBy(
                        () ->
                                ReportOrdering.of(
                                        pivot,
                                        new ApplicationReports.Sort(
                                                null, 0, List.of("2026-09"), true),
                                        null,
                                        PIVOT,
                                        1,
                                        1))
                .hasMessageContaining("排序列组无效");
        assertThatThrownBy(
                        () ->
                                ReportOrdering.of(
                                        config("TABLE", null, false, null, null),
                                        byMetric("income", List.of("2026-09"), true),
                                        null,
                                        null,
                                        1,
                                        0))
                .hasMessageContaining("排序列组无效");
    }

    /** 行数：填了数字按数字；留空时汇总表、透视表取页面保护值（导出更高），指标卡与图表保持 200。 */
    @Test
    void emptyLimitResolvesPerDisplayAndExport() {
        assertThat(ReportOrdering.groupLimit(config("PIVOT", null, false, 30, null), false))
                .isEqualTo(30);
        assertThat(ReportOrdering.groupLimit(config("PIVOT", null, false, 30, null), true))
                .isEqualTo(30);
        for (String display : List.of("PIVOT", "TABLE")) {
            assertThat(ReportOrdering.groupLimit(config(display, null, false, null, null), false))
                    .isEqualTo(ApplicationReports.MAX_TABLE_ROWS);
            assertThat(ReportOrdering.groupLimit(config(display, null, false, null, null), true))
                    .isEqualTo(ApplicationReports.MAX_EXPORT_ROWS);
        }
        for (String display : List.of("BAR", "LINE", "PIE", "METRIC"))
            for (boolean export : List.of(false, true))
                assertThat(
                                ReportOrdering.groupLimit(
                                        config(display, null, false, null, null), export))
                        .isEqualTo(ApplicationReports.MAX_CHART_GROUPS);
        assertThat(ApplicationReports.MAX_CHART_GROUPS).isEqualTo(200);
        assertThat(ApplicationReports.MAX_EXPORT_ROWS)
                .isGreaterThanOrEqualTo(ApplicationReports.MAX_TABLE_ROWS);
    }
}
