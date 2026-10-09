package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.RuleFixtures.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.FieldRuleGraph;

import org.junit.jupiter.api.Test;

import java.util.*;

/** 合并依赖图（主表加内部明细）：传递依赖、拓扑顺序、主表字段影响的明细规则与公式编码提取。 */
class FieldRuleGraphTest {
    private static com.richuang.os.nocode.api.DataCenter.Definition graphFixture() {
        // 主表：公司(1) → 口座(2，引用筛选) ；银行名(3) 联动依赖 口座(2)；合计(4) 公式依赖 单价(5)。
        // 明细：分类(11)；科目(12) 引用筛选依赖 本行分类(11) 与 主表公司(1)；税额(13) 公式依赖 本行金额(14)。
        var detail =
                detail(
                        "10",
                        "凭证明细",
                        List.of(
                                field("11", "c_fl", "分类", "TEXT"),
                                field("12", "c_km", "科目", "INTEGER"),
                                field("13", "c_se", "税额", "MONEY"),
                                field("14", "c_je", "金额", "MONEY")),
                        options(
                                Map.entry(
                                        "12",
                                        ruled(
                                                reference(
                                                        null,
                                                        current("91", "eq", "11"),
                                                        current("92", "eq", "1")))),
                                Map.entry("13", ruled(formula("c_je * 0.1", null)))));
        return object(
                "900",
                "凭证",
                List.of(
                        field("1", "c_gs", "公司", "INTEGER"),
                        field("2", "c_kz", "口座", "INTEGER"),
                        field("3", "c_yhm", "银行名", "TEXT"),
                        field("4", "c_hj", "合计", "MONEY"),
                        field("5", "c_dj", "单价", "DECIMAL")),
                options(
                        Map.entry("2", ruled(reference(null, current("81", "eq", "1")))),
                        Map.entry(
                                "3",
                                ruled(
                                        linkage(
                                                "800",
                                                "71",
                                                "FIRST",
                                                current("$record", "eq", "2")))),
                        Map.entry("4", ruled(formula("round(c_dj, 0) + 'c_hj'", null)))),
                List.of(),
                List.of(detail));
    }

    @Test
    void dependsOnIsTransitiveAndSpansMaster() {
        var graph = FieldRuleGraph.of(graphFixture());
        assertThat(graph.dependsOn("3")).containsExactly("1", "2");
        assertThat(graph.dependsOn("12")).containsExactly("1", "11");
        assertThat(graph.dependsOn("13")).containsExactly("14");
        assertThat(graph.dependsOn("4")).containsExactly("5");
        assertThat(graph.dependsOn("5")).isEmpty();
    }

    @Test
    void topoOrdersRuleFieldsAfterTheirDependencies() {
        var topo = FieldRuleGraph.of(graphFixture()).topo();
        assertThat(topo).containsExactlyInAnyOrder("2", "3", "4", "12", "13");
        assertThat(topo.indexOf("2")).isLessThan(topo.indexOf("3"));
        assertThat(FieldRuleGraph.of(graphFixture()).cycle()).isEmpty();
    }

    @Test
    void masterChangeMapsToDetailTargets() {
        var graph = FieldRuleGraph.of(graphFixture());
        assertThat(graph.detailTargetsOfMaster("1")).isEqualTo(Map.of("10", Set.of("12")));
        assertThat(graph.detailTargetsOfMaster("5")).isEmpty();
        assertThat(graph.detailTargetsOfMaster("11")).isEmpty();
        assertThat(graph.detailOf("12")).isEqualTo("10");
        assertThat(graph.detailOf("1")).isNull();
    }

    @Test
    void formulaCodesSkipFunctionsNumbersAndText() {
        assertThat(FieldRuleGraph.formulaCodes("round(c_dj * c_sl, 2) + abs (x_1) || 'c_text'"))
                .containsExactly("c_dj", "c_sl", "x_1");
        assertThat(FieldRuleGraph.formulaCodes("'it''s' || c_a")).containsExactly("c_a");
    }

    @Test
    void cycleIsReportedInDependencyOrder() {
        var d =
                object(
                        "901",
                        "对象",
                        List.of(
                                field("1", "c_a", "甲", "TEXT"),
                                field("2", "c_b", "乙", "TEXT"),
                                field("3", "c_c", "丙", "TEXT")),
                        options(
                                Map.entry(
                                        "1",
                                        ruled(
                                                linkage(
                                                        "800",
                                                        "71",
                                                        "FIRST",
                                                        current("7", "eq", "3")))),
                                Map.entry(
                                        "2",
                                        ruled(
                                                linkage(
                                                        "800",
                                                        "71",
                                                        "FIRST",
                                                        current("7", "eq", "1")))),
                                Map.entry(
                                        "3",
                                        ruled(
                                                linkage(
                                                        "800",
                                                        "71",
                                                        "FIRST",
                                                        current("7", "eq", "2"))))),
                        List.of(),
                        List.of());
        var graph = FieldRuleGraph.of(d);
        // 甲依赖丙、乙依赖甲、丙依赖乙：沿“依赖 → 目标”方向为 甲 → 乙 → 丙 → 甲。
        assertThat(graph.cycle()).containsExactly("1", "2", "3", "1");
        assertThat(graph.topo()).isEmpty();
    }
}
