package com.richuang.os.nocode.runtime.service.report;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.ApplicationReports;
import com.richuang.os.nocode.runtime.dal.query.RecordStatement;
import com.richuang.os.nocode.runtime.dal.query.ReportStatement;
import com.richuang.os.nocode.runtime.dal.support.ReportMetricExpressions;
import com.richuang.os.nocode.runtime.dal.support.ReportMetricExpressions.Stage;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 多个数据来源的无状态部分（不连库）：L24 物理类型白名单、表达式记号（单来源逐个不变、多来源的「来源存在」与「空当 0」）、下钻落到哪个来源。 */
class ReportMultiSourcesTest {
    @Test
    void nativeTypeWhitelist() {
        for (String ok :
                List.of(
                        "numeric(18,2)",
                        "bigint",
                        "character varying(100)",
                        "date",
                        "timestamp without time zone",
                        "timestamp with time zone",
                        "smallint",
                        "NUMERIC(18,0)"))
            assertThat(ReportMultiSources.nullType(ok)).isEqualTo(ok.toLowerCase(Locale.ROOT));
        for (String bad :
                List.of(
                        "numeric); DROP TABLE x; --",
                        "text[]",
                        "\"char\"",
                        "",
                        "numeric(18,2,3)",
                        "1int"))
            assertThatThrownBy(() -> ReportMultiSources.nullType(bad))
                    .as(bad)
                    .hasMessage("统计字段的物理类型无法识别");
        assertThatThrownBy(() -> ReportMultiSources.nullType(null)).hasMessage("统计字段的物理类型无法识别");
    }

    private static ApplicationReports.Metric base(String id) {
        return new ApplicationReports.Metric(id, id, "SUM", "f");
    }

    private static ApplicationReports.Metric formula(String id, String op, String l, String r) {
        return new ApplicationReports.Metric(
                id, id, "FORMULA", null, null, new ApplicationReports.Formula(op, l, r), null);
    }

    private static ReportStatement statement(
            List<ApplicationReports.Metric> metrics, List<ReportStatement.Source> sources) {
        RecordStatement base = null;
        return new ReportStatement(
                base,
                List.of(),
                List.of(),
                List.of(),
                metrics,
                Collections.nCopies(metrics.size(), null),
                null,
                false,
                "UTC",
                null,
                null,
                null,
                10,
                null,
                false,
                Map.of(),
                null,
                List.of(),
                null,
                0,
                false,
                null,
                0,
                null,
                Collections.nCopies(metrics.size(), null),
                sources);
    }

    @Test
    void singleSourceTokensAreUnchanged() {
        List<ApplicationReports.Metric> metrics =
                List.of(
                        base("a"),
                        base("b"),
                        formula("c", "ADD", "a", "b"),
                        formula("d", "MULTIPLY", "c", "a"));
        List<List<ReportMetricExpressions.Token>> tokens =
                ReportMetricExpressions.expand(statement(metrics, null));
        assertThat(tokens.get(2))
                .extracting(ReportMetricExpressions.Token::stage)
                .containsExactly(
                        Stage.FORMULA_START,
                        Stage.AGGREGATE,
                        Stage.FORMULA_OPERATOR,
                        Stage.AGGREGATE,
                        Stage.FORMULA_END);
        assertThat(tokens.stream().flatMap(List::stream).map(ReportMetricExpressions.Token::source))
                .containsOnlyNulls();
    }

    @Test
    void multiSourceTokensWrapPresenceAndZero() {
        List<ApplicationReports.Metric> metrics =
                List.of(
                        base("a"),
                        base("b"),
                        formula("c", "SUBTRACT", "a", "b"),
                        formula("d", "DIVIDE", "c", "a"));
        List<ReportStatement.Slot> first =
                Arrays.asList(
                        new ReportStatement.Slot(0, "x", "t", "numeric"),
                        new ReportStatement.Slot(null, null, null, "numeric"),
                        null,
                        null);
        List<ReportStatement.Slot> second =
                Arrays.asList(
                        new ReportStatement.Slot(null, null, null, "numeric"),
                        new ReportStatement.Slot(0, "y", "t", "numeric"),
                        null,
                        null);
        ReportStatement probe = statement(metrics, null);
        List<List<ReportMetricExpressions.Token>> tokens =
                ReportMetricExpressions.expand(
                        statement(
                                metrics,
                                List.of(
                                        new ReportStatement.Source(probe, first),
                                        new ReportStatement.Source(probe, second))));
        assertThat(tokens.get(1))
                .extracting(ReportMetricExpressions.Token::stage)
                .containsExactly(Stage.PRESENCE_START, Stage.AGGREGATE, Stage.PRESENCE_END);
        assertThat(tokens.get(1).getFirst().source()).isEqualTo(1);
        assertThat(tokens.get(0).getFirst().source()).isEqualTo(0);
        assertThat(tokens.get(2))
                .extracting(ReportMetricExpressions.Token::stage)
                .containsExactly(
                        Stage.FORMULA_START,
                        Stage.ZERO_START,
                        Stage.PRESENCE_START,
                        Stage.AGGREGATE,
                        Stage.PRESENCE_END,
                        Stage.ZERO_END,
                        Stage.FORMULA_OPERATOR,
                        Stage.ZERO_START,
                        Stage.PRESENCE_START,
                        Stage.AGGREGATE,
                        Stage.PRESENCE_END,
                        Stage.ZERO_END,
                        Stage.FORMULA_END);
        // 除法不加「空当 0」，里面的减法照加。
        assertThat(tokens.get(3).get(1).stage()).isEqualTo(Stage.FORMULA_START);
        assertThat(tokens.get(3)).filteredOn(t -> t.stage() == Stage.ZERO_START).hasSize(2);
    }

    @Test
    void drillTargetFollowsTheMetric() {
        ApplicationReports.Config c =
                new ApplicationReports.Config(
                        "o",
                        List.of(),
                        List.of(
                                new ApplicationReports.Metric(
                                        "a", "A", "SUM", "f", null, null, null, null),
                                new ApplicationReports.Metric(
                                        "b", "B", "SUM", "f", null, null, null, "s2"),
                                formula("c", "ADD", "a", "b")),
                        Map.of(),
                        List.of(),
                        null,
                        "UTC",
                        "PIVOT",
                        null,
                        false,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "一",
                        List.of(
                                new ApplicationReports.Source(
                                        "s2", "二", "o", null, null, List.of(), List.of(), null,
                                        null, null)),
                        null,
                        null);
        assertThat(ReportMultiSources.target(c, query(null, null))).isNull();
        assertThat(ReportMultiSources.target(c, query("a", null))).isEqualTo("main");
        assertThat(ReportMultiSources.target(c, query("b", null))).isEqualTo("s2");
        assertThat(ReportMultiSources.target(c, query(null, "s2"))).isEqualTo("s2");
        assertThat(ReportMultiSources.target(c, query("b", "s2"))).isEqualTo("s2");
        assertThatThrownBy(() -> ReportMultiSources.target(c, query("b", "main")))
                .hasMessage("下钻的数据来源不存在或与指标不一致");
        assertThatThrownBy(() -> ReportMultiSources.target(c, query(null, "x")))
                .hasMessage("下钻的数据来源不存在或与指标不一致");
        assertThatThrownBy(() -> ReportMultiSources.target(c, query("c", null)))
                .hasMessage("计算指标请分别查看其引用指标的明细");
        assertThatThrownBy(() -> ReportMultiSources.target(c, query("z", null)))
                .hasMessage("下钻指标不存在");
    }

    private static ApplicationReports.Query query(String metric, String source) {
        return new ApplicationReports.Query(
                "app", "r", null, null, null, null, null, 1, 10, null, metric, null, null, source);
    }
}
