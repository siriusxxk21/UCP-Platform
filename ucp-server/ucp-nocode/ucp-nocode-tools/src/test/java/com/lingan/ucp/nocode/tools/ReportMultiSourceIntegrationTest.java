package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.ReportMultiSourceFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.runtime.service.application.ApplicationRuntimeService;
import com.lingan.ucp.nocode.web.RecordExcelService;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 统计视图多个数据来源：取数与合并（契约 14.2–14.4 的目标表逐格比对、一次报全）、引用维度名称、下钻、导出、运行端下发、存量形状。 真实开发库，每例整体回滚；数据见 {@link
 * ReportMultiSourceFixture#seed}。
 */
class ReportMultiSourceIntegrationTest {
    private ReportMultiSourceFixture f;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        f = new ReportMultiSourceFixture();
    }

    @AfterEach
    void cleanup() {
        f.support.clean();
    }

    private void rollback(Runnable action) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            try {
                                action.run();
                            } finally {
                                tx.setRollbackOnly();
                            }
                        });
    }

    static final String JUL = "2026-07", AUG = "2026-08", SEP = "2026-09";
    static final List<String> EX2 = List.of("cur", "nxt", "rev");
    static final List<String> EX1 = List.of("cur", "nxt", "exp", "inc", "pro");
    static final List<String> EX3 = List.of("dr", "cr", "bal");

    private static void check(
            SoftAssertions soft,
            ApplicationReports.Result r,
            List<String> rows,
            List<String> columns,
            List<String> metrics,
            Integer... expected) {
        if (!has(r, rows, columns)) {
            soft.fail("缺少透视格 " + rows + " × " + columns);
            return;
        }
        softCell(soft, cell(r, rows, columns).values(), rows + " × " + columns, metrics, expected);
    }

    private void none(SoftAssertions soft, ApplicationReports.Result r, String row, String column) {
        soft.assertThat(has(r, keys(row), keys(column)))
                .as("没有格 " + row + " × " + column)
                .isFalse();
    }

    /** 例 2 目标表（契约 14.2）：行键 = 物件记录 ID。 */
    private void assertExample2(ApplicationReports.Result r) {
        String p1 = f.key("P1"), p2 = f.key("P2"), p3 = f.key("P3");
        SoftAssertions.assertSoftly(
                soft -> {
                    check(soft, r, keys(p1), keys(JUL), EX2, 20000, null, 20000);
                    check(soft, r, keys(p1), keys(AUG), EX2, 18000, 10000, 28000);
                    check(soft, r, keys(p1), keys(), EX2, 38000, 10000, 48000);
                    check(soft, r, keys(p2), keys(AUG), EX2, 15000, null, 15000);
                    check(soft, r, keys(p2), keys(SEP), EX2, 22000, 0, 22000);
                    check(soft, r, keys(p2), keys(), EX2, 37000, 0, 37000);
                    check(soft, r, keys(p3), keys(AUG), EX2, 8000, null, 8000);
                    check(soft, r, keys(p3), keys(SEP), EX2, null, 16000, 16000);
                    check(soft, r, keys(p3), keys(), EX2, 8000, 16000, 24000);
                    check(soft, r, keys(), keys(JUL), EX2, 20000, null, 20000);
                    check(soft, r, keys(), keys(AUG), EX2, 41000, 10000, 51000);
                    check(soft, r, keys(), keys(SEP), EX2, 22000, 16000, 38000);
                    check(soft, r, keys(), keys(), EX2, 83000, 26000, 109000);
                    softCell(soft, r.totals(), "总计", EX2, 83000, 26000, 109000);
                    none(soft, r, p1, SEP);
                    none(soft, r, p2, JUL);
                    none(soft, r, p3, JUL);
                    soft.assertThat(r.recordCount()).as("recordCount").isEqualTo(10);
                    soft.assertThat(r.sources())
                            .extracting(
                                    ApplicationReports.SourceSummary::id,
                                    ApplicationReports.SourceSummary::recordCount)
                            .containsExactly(tuple("main", 5L), tuple("next", 5L));
                    soft.assertThat(r.detailName()).isNull();
                });
        // 「空」与「0」分得开：P1·07 次月来源在 7 月没有行 ⇒ null；P2·09 有行、值为 0 ⇒ "0"。
        assertThat(cell(r, keys(p1), keys(JUL)).values().get("nxt")).isNull();
        // 金额列是 numeric(…,2)，「0」以 0.00 的文本返回（与单来源求和同口径）；要紧的是它不是 null。
        assertThat(new java.math.BigDecimal(cell(r, keys(p2), keys(SEP)).values().get("nxt")))
                .isEqualByComparingTo("0");
    }

    /** T1：例 2 透视（同对象、不同日期字段）。预览与已发布统计同一口径。 */
    @Test
    void example2SameObjectDifferentDates() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.example2(false))).seed();
                    ApplicationReports.Result r = f.reports.query(f.query("report"), OWNER);
                    assertExample2(r);
                    assertExample2(f.preview(f.example2(false)));
                    assertThat(r.pivot().rowDimensionNames()).containsExactly("物件");
                    assertThat(r.pivot().columnDimensionNames()).containsExactly("月份");
                    assertThat(r.sources())
                            .extracting(ApplicationReports.SourceSummary::name)
                            .containsExactly("当月", "次月");
                });
    }

    /** T2：两个来源的行维度都用「物件 / 名称」（同一字段对齐）⇒「青山」一行 = P1 + P3。 */
    @Test
    void example2AlignedByNameMergesSameNamedRecords() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.example2(true))).seed();
                    ApplicationReports.Result r = f.reports.query(f.query("report"), OWNER);
                    SoftAssertions.assertSoftly(
                            soft -> {
                                check(soft, r, keys("青山"), keys(JUL), EX2, 20000, null, 20000);
                                check(soft, r, keys("青山"), keys(AUG), EX2, 26000, 10000, 36000);
                                check(soft, r, keys("青山"), keys(SEP), EX2, null, 16000, 16000);
                                check(soft, r, keys("青山"), keys(), EX2, 46000, 26000, 72000);
                                check(soft, r, keys("白川"), keys(AUG), EX2, 15000, null, 15000);
                                check(soft, r, keys("白川"), keys(SEP), EX2, 22000, 0, 22000);
                                check(soft, r, keys("白川"), keys(), EX2, 37000, 0, 37000);
                                check(soft, r, keys(), keys(), EX2, 83000, 26000, 109000);
                            });
                    assertThat(r.pivot().rows()).hasSize(2);
                });
    }

    /** 例 1 目标表（契约 14.3）。 */
    private void assertExample1(ApplicationReports.Result r) {
        String p1 = f.key("P1"), p2 = f.key("P2"), p3 = f.key("P3");
        SoftAssertions.assertSoftly(
                soft -> {
                    check(soft, r, keys(p1), keys(JUL), EX1, 20000, null, 5000, 20000, 15000);
                    check(soft, r, keys(p1), keys(AUG), EX1, 18000, 10000, 7000, 28000, 21000);
                    check(soft, r, keys(p1), keys(), EX1, 38000, 10000, 12000, 48000, 36000);
                    check(soft, r, keys(p2), keys(AUG), EX1, 15000, null, null, 15000, 15000);
                    check(soft, r, keys(p2), keys(SEP), EX1, 22000, 0, 3000, 22000, 19000);
                    check(soft, r, keys(p2), keys(), EX1, 37000, 0, 3000, 37000, 34000);
                    check(soft, r, keys(p3), keys(AUG), EX1, 8000, null, null, 8000, 8000);
                    check(soft, r, keys(p3), keys(SEP), EX1, null, 16000, null, 16000, 16000);
                    check(soft, r, keys(p3), keys(), EX1, 8000, 16000, null, 24000, 24000);
                    check(soft, r, keys((String) null), keys(AUG), EX1, null, null, 1000, 0, -1000);
                    check(soft, r, keys((String) null), keys(), EX1, null, null, 1000, 0, -1000);
                    check(soft, r, keys(), keys(JUL), EX1, 20000, null, 5000, 20000, 15000);
                    check(soft, r, keys(), keys(AUG), EX1, 41000, 10000, 8000, 51000, 43000);
                    check(soft, r, keys(), keys(SEP), EX1, 22000, 16000, 3000, 38000, 35000);
                    check(soft, r, keys(), keys(), EX1, 83000, 26000, 16000, 109000, 93000);
                    softCell(soft, r.totals(), "总计", EX1, 83000, 26000, 16000, 109000, 93000);
                    none(soft, r, null, JUL);
                    none(soft, r, null, SEP);
                    soft.assertThat(r.recordCount()).as("recordCount").isEqualTo(14);
                    soft.assertThat(r.sources())
                            .extracting(
                                    ApplicationReports.SourceSummary::id,
                                    ApplicationReports.SourceSummary::recordCount)
                            .containsExactly(
                                    tuple("main", 5L), tuple("next", 5L), tuple("expense", 4L));
                });
    }

    /** T3：例 1 透视（跨对象）；行按利润降序 ⇒ P1、P2、P3、未填写；「未填写」行收入 0、利润 −1000。 */
    @Test
    void example1CrossObjectProfit() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.example1())).seed();
                    ApplicationReports.Result r = f.reports.query(f.query("report"), OWNER);
                    assertExample1(r);
                    assertThat(r.pivot().rows())
                            .extracting(ApplicationReports.PivotHeader::keys)
                            .containsExactly(
                                    keys(f.key("P1")),
                                    keys(f.key("P2")),
                                    keys(f.key("P3")),
                                    keys((String) null));
                    assertThat(r.pivot().rows())
                            .extracting(h -> h.labels().getFirst())
                            .containsExactly("青山", "白川", "青山", "未填写");
                });
    }

    /** T4：例 1 按利润降序 + 最多 2 行：只显示 P1、P2，共 4 行；列合计与总计仍是全部数据（截断在合并之后）。 */
    @Test
    void example1LimitTruncatesAfterMerge() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.example1("PIVOT", 2))).seed();
                    ApplicationReports.Result r = f.reports.query(f.query("report"), OWNER);
                    assertThat(r.pivot().rows())
                            .extracting(ApplicationReports.PivotHeader::keys)
                            .containsExactly(keys(f.key("P1")), keys(f.key("P2")));
                    assertThat(r.pivot().rowsTruncated()).isTrue();
                    assertThat(r.pivot().totalRowGroups()).isEqualTo(4);
                    SoftAssertions.assertSoftly(
                            soft -> {
                                check(
                                        soft, r, keys(), keys(AUG), EX1, 41000, 10000, 8000, 51000,
                                        43000);
                                check(
                                        soft, r, keys(), keys(), EX1, 83000, 26000, 16000, 109000,
                                        93000);
                                softCell(
                                        soft,
                                        r.totals(),
                                        "总计",
                                        EX1,
                                        83000,
                                        26000,
                                        16000,
                                        109000,
                                        93000);
                            });
                });
    }

    /** T5：查看时点 8 月列组的「支出」降序：P1(7000)、未填写(1000)，P2 / P3 在 8 月没有支出 ⇒ 排最后。 */
    @Test
    void example1ClickedColumnSort() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.example1())).seed();
                    ApplicationReports.Query q =
                            new ApplicationReports.Query(
                                    f.app,
                                    "report",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    1,
                                    100,
                                    null,
                                    null,
                                    null,
                                    new ApplicationReports.Sort("exp", null, List.of(AUG), true));
                    ApplicationReports.Result r = f.reports.query(q, OWNER);
                    List<List<String>> rows =
                            r.pivot().rows().stream()
                                    .map(ApplicationReports.PivotHeader::keys)
                                    .toList();
                    assertThat(rows.subList(0, 2))
                            .containsExactly(keys(f.key("P1")), keys((String) null));
                    assertThat(rows.subList(2, 4))
                            .containsExactlyInAnyOrder(keys(f.key("P2")), keys(f.key("P3")));
                });
    }

    /** 例 3 目标表（契约 14.4）：行键 = 科目记录 ID；标签 = 科目名称。 */
    private void assertExample3(ApplicationReports.Result r) {
        String a1 = f.key("A1"), a2 = f.key("A2"), a3 = f.key("A3");
        SoftAssertions.assertSoftly(
                soft -> {
                    check(soft, r, keys(a1), keys(AUG), EX3, 1000, 300, 700);
                    check(soft, r, keys(a1), keys(SEP), EX3, 250, null, 250);
                    check(soft, r, keys(a1), keys(), EX3, 1250, 300, 950);
                    check(soft, r, keys(a2), keys(AUG), EX3, 300, 1500, -1200);
                    check(soft, r, keys(a2), keys(), EX3, 300, 1500, -1200);
                    check(soft, r, keys(a3), keys(AUG), EX3, 500, null, 500);
                    check(soft, r, keys(a3), keys(SEP), EX3, null, 200, -200);
                    check(soft, r, keys(a3), keys(), EX3, 500, 200, 300);
                    check(soft, r, keys((String) null), keys(SEP), EX3, null, 0, 0);
                    check(soft, r, keys((String) null), keys(), EX3, null, 0, 0);
                    check(soft, r, keys(), keys(AUG), EX3, 1800, 1800, 0);
                    check(soft, r, keys(), keys(SEP), EX3, 250, 200, 50);
                    check(soft, r, keys(), keys(), EX3, 2050, 2000, 50);
                    none(soft, r, a2, SEP);
                    none(soft, r, null, AUG);
                    soft.assertThat(r.recordCount()).isEqualTo(10);
                    soft.assertThat(r.sources())
                            .extracting(
                                    ApplicationReports.SourceSummary::id,
                                    ApplicationReports.SourceSummary::recordCount,
                                    ApplicationReports.SourceSummary::detailName)
                            .containsExactly(tuple("main", 5L, "分录"), tuple("credit", 5L, "分录"));
                });
        assertThat(r.pivot().rows())
                .extracting(h -> h.labels().getFirst())
                .contains("現金", "売上", "普通預金", "未填写");
        assertThat(r.pivot().rowDimensionNames()).containsExactly("科目");
        assertThat(r.pivot().columnDimensionNames()).containsExactly("日期");
    }

    /** T6：例 3 试算表（两个来源都是明细粒度·分录，同一明细的不同字段）。 */
    @Test
    void example3TrialBalanceOnDetailGrain() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.example3())).seed();
                    assertExample3(f.reports.query(f.query("report"), OWNER));
                    assertExample3(f.preview(f.example3()));
                });
    }

    /** 例 1 的汇总表：行 = 物件、月份两个分组。 */
    private ApplicationReports.Config example1Table() {
        ApplicationReports.Config c = f.example1("TABLE", null);
        List<ApplicationReports.Source> sources =
                List.of(
                        new ApplicationReports.Source(
                                "next",
                                "次月",
                                f.stay.objectId(),
                                null,
                                null,
                                List.of(
                                        dim(f.stayProperty, null, "VALUE"),
                                        dim(f.checkOut, null, "MONTH")),
                                null,
                                null,
                                null,
                                null),
                        new ApplicationReports.Source(
                                "expense",
                                "支出",
                                f.expense.objectId(),
                                null,
                                null,
                                List.of(
                                        dim(f.expenseProperty, null, "VALUE"),
                                        dim(f.paidOn, null, "MONTH")),
                                null,
                                null,
                                null,
                                null));
        return new ApplicationReports.Config(
                c.objectId(),
                List.of(dim(f.stayProperty, null, "VALUE"), dim(f.checkIn, null, "MONTH")),
                c.metrics(),
                c.equal(),
                c.filterFieldIds(),
                c.dateFieldId(),
                c.timeZone(),
                "TABLE",
                c.sortMetricId(),
                c.descending(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                c.sortBy(),
                null,
                null,
                c.sourceName(),
                sources,
                List.of("物件", "月份"),
                null);
    }

    /** T7：例 1 汇总表：各组与透视叶子同值，合计等于总计。 */
    @Test
    void example1TableMatchesPivotLeaves() {
        rollback(
                () -> {
                    f.objects();
                    f.app(
                                    f.resource("summary", "REPORT", example1Table()),
                                    f.resource("pivot", "REPORT", f.example1()))
                            .seed();
                    ApplicationReports.Result t = f.reports.query(f.query("summary"), OWNER);
                    ApplicationReports.Result p = f.reports.query(f.query("pivot"), OWNER);
                    assertThat(t.groups()).hasSize(7);
                    SoftAssertions.assertSoftly(
                            soft -> {
                                for (ApplicationReports.Group g : t.groups())
                                    soft.assertThat(g.values())
                                            .as("组 " + g.keys())
                                            .isEqualTo(
                                                    cell(
                                                                    p,
                                                                    keys(g.keys().get(0)),
                                                                    keys(g.keys().get(1)))
                                                            .values());
                                softCell(
                                        soft,
                                        t.totals(),
                                        "合计",
                                        EX1,
                                        83000,
                                        26000,
                                        16000,
                                        109000,
                                        93000);
                                soft.assertThat(t.recordCount()).isEqualTo(14);
                                soft.assertThat(t.dimensionNames()).containsExactly("物件", "月份");
                            });
                });
    }

    /** T8：引用字段维度显示名称：P1、P3 都是「青山」、键不同；目标记录失效后显示「已失效或无权查看的记录」。 */
    @Test
    void referenceDimensionsShowNames() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.example2(false))).seed();
                    ApplicationReports.Result r = f.reports.query(f.query("report"), OWNER);
                    Map<List<String>, List<String>> labels = new LinkedHashMap<>();
                    r.pivot().rows().forEach(h -> labels.put(h.keys(), h.labels()));
                    assertThat(labels)
                            .containsEntry(keys(f.key("P1")), List.of("青山"))
                            .containsEntry(keys(f.key("P2")), List.of("白川"))
                            .containsEntry(keys(f.key("P3")), List.of("青山"));
                    jdbc.update(
                            "UPDATE public.\""
                                    + f.property.tableName()
                                    + "\" SET deleted=1 WHERE id=?",
                            Long.parseLong(f.key("P2")));
                    ApplicationReports.Result after = f.reports.query(f.query("report"), OWNER);
                    Map<List<String>, List<String>> again = new LinkedHashMap<>();
                    after.pivot().rows().forEach(h -> again.put(h.keys(), h.labels()));
                    assertThat(again).containsEntry(keys(f.key("P2")), List.of("已失效或无权查看的记录"));
                    assertThat(again).containsEntry(keys(f.key("P1")), List.of("青山"));
                });
    }

    /** T9：下钻：来源由指标决定；计算指标、缺指标与来源不符分别报原句 / L19 / L20。 */
    @Test
    void drillGoesToTheMetricsSource() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.example1())).seed();
                    String p1 = f.key("P1");
                    var exp =
                            f.reports.details(
                                    f.drill("report", keys(p1), keys(AUG), "exp", null), OWNER);
                    assertThat(exp.getTotal()).isEqualTo(1);
                    assertThat(exp.getList())
                            .extracting(ApplicationRecords.Row::id)
                            .containsExactly(f.key("E2"));
                    var cur =
                            f.reports.details(
                                    f.drill("report", keys(p1), keys(AUG), "cur", null), OWNER);
                    assertThat(cur.getList())
                            .extracting(ApplicationRecords.Row::id)
                            .containsExactly(f.key("S2"));
                    var nxt =
                            f.reports.details(
                                    f.drill("report", keys(p1), keys(AUG), "nxt", "next"), OWNER);
                    // 次月来源按退房月：S1（08-02，10000）与 S2（08-07，0）都在 8 月，合计 10000。
                    assertThat(nxt.getList())
                            .extracting(ApplicationRecords.Row::id)
                            .containsExactly(f.key("S1"), f.key("S2"));
                    assertThatThrownBy(
                                    () ->
                                            f.reports.details(
                                                    f.drill(
                                                            "report", keys(p1), keys(AUG), "pro",
                                                            null),
                                                    OWNER))
                            .hasMessage("计算指标请分别查看其引用指标的明细");
                    assertThatThrownBy(
                                    () ->
                                            f.reports.details(
                                                    f.drill(
                                                            "report", keys(p1), keys(AUG), null,
                                                            null),
                                                    OWNER))
                            .hasMessage("多个来源的统计请点具体指标查看明细");
                    assertThatThrownBy(
                                    () ->
                                            f.reports.details(
                                                    f.drill(
                                                            "report", keys(p1), keys(AUG), "exp",
                                                            "next"),
                                                    OWNER))
                            .hasMessage("下钻的数据来源不存在或与指标不一致");
                    assertThatThrownBy(
                                    () ->
                                            f.reports.details(
                                                    f.drill(
                                                            "report", keys(p1), keys(AUG), null,
                                                            "nope"),
                                                    OWNER))
                            .hasMessage("下钻的数据来源不存在或与指标不一致");
                });
    }

    /** T10：例 3 下钻：点 A1·08 的贷方合计 ⇒ 明细行 L3（id = 凭证ID:明细行ID）。 */
    @Test
    void drillOnDetailGrainSource() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.example3())).seed();
                    var rows =
                            f.reports.details(
                                    f.drill("report", keys(f.key("A1")), keys(AUG), "cr", null),
                                    OWNER);
                    assertThat(rows.getTotal()).isEqualTo(1);
                    assertThat(rows.getList())
                            .extracting(ApplicationRecords.Row::id)
                            .containsExactly(f.key("V2") + ":" + f.key("L3"));
                    assertThat(rows.getList().getFirst().values().get(f.creditAmount).toString())
                            .startsWith("300");
                });
    }

    private List<Map<Integer, String>> sheet(byte[] file) {
        return cn.idev.excel.FastExcelFactory.read(new java.io.ByteArrayInputStream(file))
                .headRowNumber(0)
                .sheet()
                .doReadSync();
    }

    /** T13：导出：透视与汇总表的数据与屏幕逐格一致；口径行逐字。 */
    @Test
    void exportMatchesScreenAndNamesEachSource() {
        rollback(
                () -> {
                    f.objects();
                    f.app(
                                    f.resource("pivot", "REPORT", f.example1()),
                                    f.resource("summary", "REPORT", example1Table()),
                                    f.resource("trial", "REPORT", f.example3()))
                            .seed();
                    RecordExcelService excel = servicesContext.getBean(RecordExcelService.class);
                    List<Map<Integer, String>> pivot = sheet(excel.report(f.query("pivot"), OWNER));
                    assertThat(pivot.getLast().get(0))
                            .isEqualTo("来源：当月 5 条；次月 5 条；支出 4 条；时区 Asia/Tokyo");
                    List<Map<Integer, String>> table =
                            sheet(excel.report(f.query("summary"), OWNER));
                    assertThat(table.getLast().get(0))
                            .isEqualTo("展示 7 / 7 组；来源：当月 5 条；次月 5 条；支出 4 条；时区 Asia/Tokyo");
                    List<Map<Integer, String>> trial = sheet(excel.report(f.query("trial"), OWNER));
                    assertThat(trial.getLast().get(0))
                            .isEqualTo("来源：借方 5 行（明细「分录」）；贷方 5 行（明细「分录」）；时区 Asia/Tokyo");
                    // 汇总表的数据行与屏幕逐格相同（表头一行，之后按屏幕顺序）。
                    ApplicationReports.Result screen = f.reports.query(f.query("summary"), OWNER);
                    for (int i = 0; i < screen.groups().size(); i++) {
                        ApplicationReports.Group g = screen.groups().get(i);
                        Map<Integer, String> line = table.get(i + 1);
                        assertThat(line.get(0)).isEqualTo(g.labels().get(0));
                        assertThat(line.get(1)).isEqualTo(g.labels().get(1));
                        for (int m = 0; m < EX1.size(); m++)
                            assertThat(Objects.toString(line.get(2 + m), ""))
                                    .as("第 " + (i + 1) + " 组 " + EX1.get(m))
                                    .isEqualTo(Objects.toString(g.values().get(EX1.get(m)), ""));
                    }
                    // 透视表：行标签顺序与屏幕相同（表头两层：月份、指标）。
                    ApplicationReports.Result screenPivot =
                            f.reports.query(f.query("pivot"), OWNER);
                    for (int i = 0; i < screenPivot.pivot().rows().size(); i++)
                        assertThat(pivot.get(2 + i).get(0))
                                .isEqualTo(screenPivot.pivot().rows().get(i).labels().getFirst());
                });
    }

    /** T14：运行端下发带上多来源分量，顶层与各附加来源都不带固定条件。 */
    @Test
    void runtimeDeliversExtraSourcesWithoutConditions() {
        rollback(
                () -> {
                    f.objects();
                    ApplicationReports.Config c = f.example1();
                    List<ApplicationReports.Source> sources = new ArrayList<>();
                    for (ApplicationReports.Source s : c.extraSources())
                        sources.add(
                                new ApplicationReports.Source(
                                        s.id(),
                                        s.name(),
                                        s.objectId(),
                                        s.grain(),
                                        s.detailId(),
                                        s.dimensions(),
                                        s.columnDimensions(),
                                        condition(
                                                s.id().equals("next") ? f.nextAmount : f.amount,
                                                "gt",
                                                "0"),
                                        s.dateFieldId(),
                                        s.filterTargets()));
                    ApplicationReports.Config withConditions =
                            with(
                                    with(c, List.of(f.stayProperty), null, sources),
                                    List.of(f.stayProperty),
                                    null,
                                    List.of(
                                            source(sources.get(0), null, null, null, null),
                                            source(
                                                    sources.get(1),
                                                    null,
                                                    Map.of(f.stayProperty, f.expenseProperty),
                                                    null,
                                                    null)));
                    f.app(f.resource("report", "REPORT", withConditions)).seed();
                    var published =
                            servicesContext
                                    .getBean(ApplicationRuntimeService.class)
                                    .application(f.app, OWNER);
                    var resource =
                            published.definition().resources().stream()
                                    .filter(r -> r.id().equals("report"))
                                    .findFirst()
                                    .orElseThrow();
                    ApplicationReports.Config delivered =
                            mapper.convertValue(resource.config(), ApplicationReports.Config.class);
                    assertThat(delivered.extraSources()).hasSize(2);
                    assertThat(delivered.extraSources())
                            .allSatisfy(s -> assertThat(s.conditions()).isNull());
                    assertThat(delivered.conditions()).isNull();
                    assertThat(delivered.sourceName()).isEqualTo("当月");
                    assertThat(delivered.dimensionLabels()).containsExactly("物件");
                    assertThat(delivered.extraSources().get(1).filterTargets())
                            .containsEntry(f.stayProperty, f.expenseProperty);
                    assertThat(delivered.metrics())
                            .extracting(ApplicationReports.Metric::sourceId)
                            .containsExactly(null, "next", "expense", null, null);
                    assertThat(resource.config().toString()).doesNotContain("\"conditions\"");
                });
    }

    /** T16：存量单来源配置经 normalize 后转成的 Map 不出现四个新键，指标不出现 sourceId；结果不出现 sources。 */
    @Test
    void singleSourceShapeIsUnchanged() {
        rollback(
                () -> {
                    f.objects();
                    ApplicationReports.Config single =
                            new ApplicationReports.Config(
                                    f.stay.objectId(),
                                    List.of(dim(f.stayProperty, null, "VALUE")),
                                    List.of(
                                            new ApplicationReports.Metric(
                                                    "cur", "当月金额", "SUM", f.curAmount)),
                                    Map.of(),
                                    List.of(),
                                    null,
                                    "Asia/Tokyo",
                                    "PIVOT",
                                    null,
                                    false,
                                    null,
                                    null,
                                    null,
                                    null,
                                    List.of(dim(f.checkIn, null, "MONTH")),
                                    null,
                                    null,
                                    null);
                    var validator =
                            servicesContext.getBean(
                                    com.lingan.ucp.nocode.application.service.resource
                                            .ApplicationReportValidator.class);
                    Map<String, DataCenter.Definition> definitions = new LinkedHashMap<>();
                    for (DataCenter.Definition d : f.referenced()) definitions.put(d.objectId(), d);
                    ApplicationReports.Config normalized = validator.normalize(single, definitions);
                    Map<String, Object> json =
                            mapper.convertValue(
                                    normalized,
                                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    assertThat(json)
                            .doesNotContainKeys(
                                    "sourceName",
                                    "extraSources",
                                    "dimensionLabels",
                                    "columnDimensionLabels",
                                    "grain",
                                    "detailId");
                    assertThat(json.keySet())
                            .containsExactly(
                                    "objectId",
                                    "dimensions",
                                    "metrics",
                                    "equal",
                                    "filterFieldIds",
                                    "dateFieldId",
                                    "timeZone",
                                    "display",
                                    "sortMetricId",
                                    "descending",
                                    "limit",
                                    "detailViewId",
                                    "conditions",
                                    "chart",
                                    "columnDimensions",
                                    "pivot",
                                    "detailEditable",
                                    "sortBy");
                    @SuppressWarnings("unchecked")
                    Map<String, Object> metric =
                            ((List<Map<String, Object>>) json.get("metrics")).getFirst();
                    assertThat(metric).doesNotContainKey("sourceId");
                    assertThat(metric.keySet())
                            .containsExactly(
                                    "id",
                                    "name",
                                    "operation",
                                    "fieldId",
                                    "conditions",
                                    "formula",
                                    "format");
                    f.app(f.resource("report", "REPORT", single)).seed();
                    Map<String, Object> result =
                            mapper.convertValue(
                                    f.reports.query(f.query("report"), OWNER),
                                    new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    assertThat(result).doesNotContainKeys("sources", "detailName");
                });
    }
}
