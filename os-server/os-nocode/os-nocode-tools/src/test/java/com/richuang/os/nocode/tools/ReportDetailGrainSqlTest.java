package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.ApplicationReports;
import com.richuang.os.nocode.enums.ReportFormulaEnum;
import com.richuang.os.nocode.enums.ReportOperationEnum;
import com.richuang.os.nocode.runtime.dal.mapper.ReportMapper;
import com.richuang.os.nocode.runtime.dal.query.ReportStatement;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.*;
import java.util.stream.Stream;

/**
 * 统计语句的渲染：不连接数据库，只看 MyBatis 绑定后的 SQL 与参数。
 *
 * <p>① 按主记录统计的语句与引入统计粒度之前逐 token 相同：对照的就是 {@code provider-mappings.json} 里原有的那些键，基线文件一个字不动。{@link
 * ProviderXmlCompatibilityTest} 的夹具是「每个计算方式各放一个指标」， 枚举里多了 COUNT_ROOT 之后它生成的语句本身就多了一个指标（且统计引擎从不把
 * COUNT_ROOT 交给 SQL 模板）； 这里把语句还原成原有七种计算方式的形状再比对，证明的是「同样的输入，渲染结果没变」。
 *
 * <p>② 按明细行统计：主表之后内连接明细表，指标列各带自己的别名。
 */
class ReportDetailGrainSqlTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MAPPER = ReportMapper.class.getName();
    private static final List<ReportOperationEnum> ORIGINAL_OPERATIONS =
            List.of(
                    ReportOperationEnum.COUNT,
                    ReportOperationEnum.COUNT_FIELD,
                    ReportOperationEnum.COUNT_DISTINCT,
                    ReportOperationEnum.SUM,
                    ReportOperationEnum.AVG,
                    ReportOperationEnum.MIN,
                    ReportOperationEnum.MAX);

    /** 引入 COUNT_ROOT 之前 ProviderXmlCompatibilityTest 生成的指标：七个基础指标 + 每种公式一个计算指标。 */
    private static ReportStatement original(ReportStatement generated) {
        var metrics = new ArrayList<ApplicationReports.Metric>();
        var columns = new ArrayList<String>();
        for (var op : ORIGINAL_OPERATIONS) {
            metrics.add(
                    new ApplicationReports.Metric(
                            "metric_" + metrics.size(), "Metric", op.getCode(), "1"));
            columns.add(op == ReportOperationEnum.COUNT ? null : "amount");
        }
        for (var op : ReportFormulaEnum.values()) {
            int size = metrics.size();
            metrics.add(
                    new ApplicationReports.Metric(
                            "metric_" + size,
                            "Formula",
                            "FORMULA",
                            null,
                            null,
                            new ApplicationReports.Formula(
                                    op.getCode(),
                                    size == 7 ? "metric_0" : "metric_" + (size - 1),
                                    "metric_3"),
                            null));
            columns.add(null);
        }
        return new ReportStatement(
                generated.base(),
                generated.dimensions(),
                generated.joins(),
                generated.filters(),
                metrics,
                columns,
                generated.dateColumn(),
                generated.dateWithZone(),
                generated.timeZone(),
                generated.dateFrom(),
                generated.dateToExclusive(),
                generated.group(),
                generated.groupLimit(),
                generated.sortIndex(),
                generated.descending(),
                generated.predicates(),
                generated.drillMetricIndex());
    }

    @TestFactory
    Stream<DynamicTest> rootGrainStatementsRenderExactlyTheBaselineSql() throws Exception {
        Map<String, Map<String, Object>> expected;
        try (var input = getClass().getResourceAsStream("/sql-baseline/provider-mappings.json")) {
            assertThat(input).isNotNull();
            expected = JSON.readValue(input, new TypeReference<>() {});
        }
        var configuration = ProviderXmlCompatibilityTest.configuration();
        var cases =
                ProviderXmlCompatibilityTest.cases().stream()
                        .filter(test -> test.statement().startsWith(MAPPER + "."))
                        .toList();
        // 32 个变体 × result / rows / count。
        assertThat(cases).hasSize(96);
        return cases.stream()
                .map(
                        test ->
                                DynamicTest.dynamicTest(
                                        test.key(),
                                        () -> {
                                            assertThat(expected).containsKey(test.key());
                                            var statement =
                                                    original((ReportStatement) test.parameters());
                                            assertThat(statement.detail()).isNull();
                                            assertThat(
                                                            ProviderXmlCompatibilityTest.snapshot(
                                                                    configuration,
                                                                    configuration
                                                                            .getMappedStatement(
                                                                                    test
                                                                                            .statement()),
                                                                    statement))
                                                    .isEqualTo(
                                                            SqlBaselineNames.resultTypes(
                                                                    expected.get(test.key())));
                                        }));
    }

    private static List<String> tokens(String method, Object parameter) throws Exception {
        var configuration = ProviderXmlCompatibilityTest.configuration();
        var snapshot =
                ProviderXmlCompatibilityTest.snapshot(
                        configuration,
                        configuration.getMappedStatement(MAPPER + "." + method),
                        parameter);
        return JSON.convertValue(snapshot.get("sqlTokens"), new TypeReference<>() {});
    }

    private static ReportStatement detailGrain(ReportStatement root, boolean deleted) {
        var aliases = new ArrayList<String>();
        for (int i = 0; i < root.metricColumns().size(); i++)
            aliases.add(root.metricColumns().get(i) == null ? null : i % 2 == 0 ? "t" : "g");
        return new ReportStatement(
                root.base(),
                root.dimensions(),
                root.joins(),
                root.filters(),
                root.metrics(),
                root.metricColumns(),
                root.dateColumn(),
                root.dateWithZone(),
                root.timeZone(),
                root.dateFrom(),
                root.dateToExclusive(),
                root.group(),
                root.groupLimit(),
                root.sortIndex(),
                root.descending(),
                root.predicates(),
                root.drillMetricIndex(),
                root.columnDimensions(),
                root.columnGroup(),
                root.columnLimit(),
                root.exactRollup(),
                root.ordering(),
                root.cellBudget(),
                new ReportStatement.Detail("public", "voucher_lines", "id", "voucher_id", deleted),
                aliases);
    }

    private static ReportStatement variant(int index) {
        return original(
                (ReportStatement)
                        ProviderXmlCompatibilityTest.cases().stream()
                                .filter(
                                        test ->
                                                test.key()
                                                        .endsWith(
                                                                "ReportMapper.result/variant-"
                                                                        + index))
                                .findFirst()
                                .orElseThrow()
                                .parameters());
    }

    private static String sql(List<String> tokens) {
        return String.join(" ", tokens);
    }

    /** 明细连接紧跟主表、在关系连接之前；归属条件不加类型转换；明细表有逻辑删除列时才过滤。 */
    @Test
    void detailGrainJoinsTheDetailTableRightAfterTheMainTable() throws Exception {
        // 变体 20：有两段关系连接（customer、region）与下钻条件。
        var root = variant(20);
        String plain = sql(tokens("count", root));
        String joined = sql(tokens("count", detailGrain(root, true)));
        String join =
                "t JOIN \"public\" . \"voucher_lines\" g ON g . \"voucher_id\" = t . \"custom_id\""
                        + " AND g . \"deleted\" :: text IN ( '0' , 'false' ) LEFT JOIN \"public\" ."
                        + " \"customer\" r0";
        assertThat(plain)
                .doesNotContain(" g ")
                .contains("t LEFT JOIN \"public\" . \"customer\" r0");
        assertThat(joined).contains(join);
        // 除了多出来的这一段连接，其余 token 一个不差。
        assertThat(joined.replace(join, "t LEFT JOIN \"public\" . \"customer\" r0"))
                .isEqualTo(plain);
        String hard = sql(tokens("count", detailGrain(root, false)));
        assertThat(hard)
                .contains(
                        "t JOIN \"public\" . \"voucher_lines\" g ON g . \"voucher_id\" = t ."
                                + " \"custom_id\" LEFT JOIN")
                .doesNotContain("g . \"deleted\"");
    }

    /** 指标列与下钻的「指标字段非空」各用指标自己的别名。 */
    @Test
    void metricColumnsUseTheirOwnAlias() throws Exception {
        var root = variant(21);
        var detail = detailGrain(root, true);
        String result = sql(tokens("result", detail));
        // 夹具里偶数下标的指标列在主表、奇数下标的在明细。
        assertThat(result)
                .contains(", g . \"amount\" AS v1")
                .contains(", t . \"amount\" AS v2")
                .contains(", g . \"amount\" AS v3")
                .doesNotContain("t . \"amount\" AS v1");
        assertThat(sql(tokens("result", root)))
                .contains(", t . \"amount\" AS v1")
                .doesNotContain(" g ");
        // 变体 21 下钻的是下标 1 的指标（列在明细上）。
        assertThat(detail.drillMetricIndex()).isEqualTo(1);
        assertThat(sql(tokens("count", detail))).contains("AND g . \"amount\" IS NOT NULL");
        assertThat(sql(tokens("count", root))).contains("AND t . \"amount\" IS NOT NULL");
    }
}
