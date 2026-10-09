package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;
import static com.richuang.os.nocode.tools.ReportDetailGrainFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.resource.ApplicationReportValidator;
import com.richuang.os.nocode.application.service.resource.ReportGrainMessages;
import com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 按明细行统计：取数与聚合、明细行下钻、序列化与下发。真实开发库，每例整体回滚；数据见 {@link ReportDetailGrainFixture#seed}。
 *
 * <p>目标表 T（行 = 贷方科目 / 科目名称，列 = 日期按月；格内依次为 贷方金额合计 / 行数 / 张数）：
 *
 * <pre>
 *            2026-07        2026-08        行合计
 * 销售收入   1000 / 2 / 1   200 / 1 / 1    1200 / 3 / 2
 * 银行存款    300 / 1 / 1   250 / 1 / 1     550 / 2 / 2
 * 未填写      （无格）      300 / 1 / 1     300 / 1 / 1
 * 列合计     1300 / 3 / 2   750 / 3 / 2    2050 / 6 / 4
 * </pre>
 *
 * 8 月列合计的张数是 2 不是 3：V3 的两条分录分在两行里，合计时只算一张。
 */
class ReportDetailGrainIntegrationTest {
    private ReportDetailGrainFixture f;

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
        f = new ReportDetailGrainFixture();
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

    private static final String JULY = "2026-07";
    private static final String AUGUST = "2026-08";
    private static final String SALES = "销售收入";
    private static final String BANK = "银行存款";

    /** 目标表 T 的全部格。数值逐格比对且一次报全：哪一格不符、差多少都看得到。 */
    private void assertTargetTable(ApplicationReports.Result r) {
        var p = r.pivot();
        assertThat(p.rowDimensionNames()).containsExactly("科目名称");
        assertThat(p.columnDimensionNames()).containsExactly("日期");
        org.assertj.core.api.SoftAssertions.assertSoftly(
                soft -> {
                    softCell(soft, r, keys(), keys(), 2050, 6, 4);
                    softCell(soft, r, keys(SALES), keys(JULY), 1000, 2, 1);
                    softCell(soft, r, keys(SALES), keys(AUGUST), 200, 1, 1);
                    softCell(soft, r, keys(SALES), keys(), 1200, 3, 2);
                    softCell(soft, r, keys(BANK), keys(JULY), 300, 1, 1);
                    softCell(soft, r, keys(BANK), keys(AUGUST), 250, 1, 1);
                    softCell(soft, r, keys(BANK), keys(), 550, 2, 2);
                    softCell(soft, r, keys((String) null), keys(AUGUST), 300, 1, 1);
                    softCell(soft, r, keys((String) null), keys(), 300, 1, 1);
                    softCell(soft, r, keys(), keys(JULY), 1300, 3, 2);
                    softCell(soft, r, keys(), keys(AUGUST), 750, 3, 2);
                    soft.assertThat(number(r.totals(), "amount")).isEqualByComparingTo("2050");
                    soft.assertThat(number(r.totals(), "lines")).isEqualByComparingTo("6");
                    soft.assertThat(number(r.totals(), "vouchers")).isEqualByComparingTo("4");
                    soft.assertThat(r.recordCount()).as("来源明细行数").isEqualTo(6);
                });
        assertThat(has(r, keys((String) null), keys(JULY))).as("未填写 × 7 月没有格").isFalse();
        assertThat(p.rows().stream().map(ApplicationReports.PivotHeader::keys))
                .containsExactly(keys(SALES), keys(BANK), keys((String) null));
        assertThat(p.rows().stream().map(ApplicationReports.PivotHeader::labels))
                .containsExactly(List.of(SALES), List.of(BANK), List.of("未填写"));
        assertThat(p.columns().stream().map(ApplicationReports.PivotHeader::keys))
                .containsExactly(keys(JULY), keys(AUGUST));
        assertThat(r.detailName()).isEqualTo("分录");
        // 结果里返回的仍是配置里的原指标：前端按 COUNT_ROOT 显示「主记录数」。
        assertThat(r.metrics().get(2).operation()).isEqualTo("COUNT_ROOT");
    }

    // ---------------------------------------------------------------- 5.1 取数与聚合

    /** 已删除的行 L7、已删除的凭证 V6、没有分录的 V5 都不进数；预览与已发布统计同一口径。 */
    @Test
    void detailGrainPivotByCreditAccountMatchesHandComputedTable() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.creditPivot())).seed();
                    assertTargetTable(f.reports.query(f.query("report"), OWNER));
                    assertTargetTable(f.preview(f.creditPivot()));
                    // 被去掉的行 L7 物理上还在（逻辑删除），所以它不进数靠的是明细表的「未删除」条件。
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.\""
                                                    + f.lines.tableName()
                                                    + "\"",
                                            Long.class))
                            .isEqualTo(8);
                });
    }

    /** 借方表 T′：银行存款 1000（7 月）、采购成本 300 + 250、现金 500（8 月）；总计 2050。 */
    @Test
    void detailGrainPivotByDebitAccount() {
        rollback(
                () -> {
                    f.objects().app().seed();
                    var r =
                            f.preview(
                                    f.detailConfig(
                                            "PIVOT",
                                            List.of(f.debitName()),
                                            List.of(f.main(f.booked, "MONTH")),
                                            List.of(
                                                    new ApplicationReports.Metric(
                                                            "amount",
                                                            "借方金额合计",
                                                            "SUM",
                                                            f.debitAmount))));
                    assertThat(r.pivot().rows().stream().map(ApplicationReports.PivotHeader::keys))
                            .containsExactly(keys(BANK), keys("采购成本"), keys("现金"));
                    assertThat(amount(r, keys(BANK), keys(JULY))).isEqualByComparingTo("1000");
                    assertThat(has(r, keys(BANK), keys(AUGUST))).isFalse();
                    assertThat(amount(r, keys(BANK), keys())).isEqualByComparingTo("1000");
                    assertThat(amount(r, keys("采购成本"), keys(JULY))).isEqualByComparingTo("300");
                    assertThat(amount(r, keys("采购成本"), keys(AUGUST))).isEqualByComparingTo("250");
                    assertThat(amount(r, keys("采购成本"), keys())).isEqualByComparingTo("550");
                    assertThat(has(r, keys("现金"), keys(JULY))).isFalse();
                    assertThat(amount(r, keys("现金"), keys(AUGUST))).isEqualByComparingTo("500");
                    assertThat(amount(r, keys(), keys(JULY))).isEqualByComparingTo("1300");
                    assertThat(amount(r, keys(), keys(AUGUST))).isEqualByComparingTo("750");
                    assertThat(amount(r, keys(), keys())).isEqualByComparingTo("2050");
                    assertThat(r.recordCount()).isEqualTo(6);
                });
    }

    private static BigDecimal amount(
            ApplicationReports.Result r, List<String> rows, List<String> columns) {
        return number(cell(r, rows, columns).values(), "amount");
    }

    /** 张数在每一层小计、合计上重新去重，不是下面各行相加；与不经预聚合、直接对原始行分组的 SQL 逐格相同。 */
    @Test
    void rootCountIsRecomputedAtEverySubtotalNotSummed() {
        rollback(
                () -> {
                    f.objects().app().seed();
                    var r = f.preview(f.creditPivot());
                    // 各行的张数相加：8 月是 1 + 1 + 1 = 3，行合计是 2 + 2 + 1 = 5；合计格是 2 与 4。
                    assertThat(number(cell(r, keys(), keys(AUGUST)).values(), "vouchers"))
                            .isEqualByComparingTo("2");
                    assertThat(number(cell(r, keys(), keys()).values(), "vouchers"))
                            .isEqualByComparingTo("4");
                    int byRowsInAugust = 0, byRows = 0;
                    for (var row : r.pivot().rows()) {
                        if (has(r, row.keys(), keys(AUGUST)))
                            byRowsInAugust +=
                                    number(cell(r, row.keys(), keys(AUGUST)).values(), "vouchers")
                                            .intValueExact();
                        byRows +=
                                number(cell(r, row.keys(), keys()).values(), "vouchers")
                                        .intValueExact();
                    }
                    assertThat(byRowsInAugust).isEqualTo(3);
                    assertThat(byRows).isEqualTo(5);
                    var direct = directCells();
                    assertThat(r.pivot().cells()).hasSameSizeAs(direct);
                    for (var expected : direct) {
                        var values =
                                cell(
                                                r,
                                                keysOf(expected.get("gd"), expected.get("d0")),
                                                keysOf(expected.get("gc"), expected.get("c0")))
                                        .values();
                        assertThat(number(values, "amount"))
                                .isEqualByComparingTo(expected.get("amount").toString());
                        assertThat(number(values, "lines"))
                                .isEqualByComparingTo(expected.get("line_count").toString());
                        assertThat(number(values, "vouchers"))
                                .isEqualByComparingTo(expected.get("voucher_count").toString());
                    }
                });
    }

    private static List<String> keysOf(Object grouping, Object value) {
        return ((Number) grouping).intValue() == 1
                ? keys()
                : keys(value == null ? null : value.toString());
    }

    private String column(
            List<FieldDefinition> fields, Map<String, DataCenter.FieldOptions> options, String id) {
        var field = fields.stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow();
        var option = options.get(id);
        return option == null || option.columnName() == null ? field.code() : option.columnName();
    }

    /** 不经统计引擎：直接对「凭证 × 分录」的原始行做分组集合。 */
    private List<Map<String, Object>> directCells() {
        String parent = ObjectTables.detail(f.voucher, f.lines).parentColumn();
        String name = column(f.account.fields(), f.account.fieldOptions(), f.accountName);
        String credit = column(f.lines.fields(), f.lines.fieldOptions(), f.credit);
        String amount = column(f.lines.fields(), f.lines.fieldOptions(), f.creditAmount);
        String booked = column(f.voucher.fields(), f.voucher.fieldOptions(), f.booked);
        return jdbc.queryForList(
                "SELECT a.\""
                        + name
                        + "\" AS d0, to_char(v.\""
                        + booked
                        + "\", 'YYYY-MM') AS c0, GROUPING(a.\""
                        + name
                        + "\") AS gd, GROUPING(to_char(v.\""
                        + booked
                        + "\", 'YYYY-MM')) AS gc, COALESCE(SUM(l.\""
                        + amount
                        + "\"), 0) AS amount, COUNT(*) AS line_count, COUNT(DISTINCT v.id) AS"
                        + " voucher_count FROM public.\""
                        + f.voucher.tableName()
                        + "\" v JOIN public.\""
                        + f.lines.tableName()
                        + "\" l ON l.\""
                        + parent
                        + "\" = v.id AND l.deleted = 0 LEFT JOIN public.\""
                        + f.account.tableName()
                        + "\" a ON a.id = l.\""
                        + credit
                        + "\" AND a.deleted = 0 WHERE v.deleted = 0 GROUP BY GROUPING SETS ((1,"
                        + " 2), (1), (2), ())");
    }

    /** 同一配置换成汇总表、柱状图、指标卡：分组值与总体与目标表 T 的行合计、总计相同。 */
    @Test
    void detailGrainTableChartAndMetricCardUseTheSameRows() {
        rollback(
                () -> {
                    f.objects().app().seed();
                    for (String display : List.of("TABLE", "BAR")) {
                        var r =
                                f.preview(
                                        f.detailConfig(
                                                display,
                                                List.of(f.creditName()),
                                                List.of(),
                                                f.creditMetrics()));
                        assertThat(r.pivot()).isNull();
                        assertThat(r.detailName()).isEqualTo("分录");
                        assertThat(r.recordCount()).isEqualTo(6);
                        assertThat(r.totalGroups()).isEqualTo(3);
                        assertThat(r.groups().stream().map(ApplicationReports.Group::keys))
                                .containsExactly(keys(SALES), keys(BANK), keys((String) null));
                        assertGroup(r.groups().get(0), 1200, 3, 2);
                        assertGroup(r.groups().get(1), 550, 2, 2);
                        assertGroup(r.groups().get(2), 300, 1, 1);
                        assertThat(r.groups().get(2).labels()).containsExactly("未填写");
                        assertTotals(r, 2050, 6, 4);
                    }
                    var card =
                            f.preview(
                                    f.detailConfig(
                                            "METRIC", List.of(), List.of(), f.creditMetrics()));
                    assertTotals(card, 2050, 6, 4);
                    assertThat(card.recordCount()).isEqualTo(6);
                    assertThat(card.detailName()).isEqualTo("分录");
                });
    }

    private static void assertGroup(
            ApplicationReports.Group group, int amount, int lineCount, int voucherCount) {
        assertThat(number(group.values(), "amount")).isEqualByComparingTo(Integer.toString(amount));
        assertThat(number(group.values(), "lines"))
                .isEqualByComparingTo(Integer.toString(lineCount));
        assertThat(number(group.values(), "vouchers"))
                .isEqualByComparingTo(Integer.toString(voucherCount));
    }

    private static void assertTotals(
            ApplicationReports.Result r, int amount, int lineCount, int voucherCount) {
        assertThat(number(r.totals(), "amount")).isEqualByComparingTo(Integer.toString(amount));
        assertThat(number(r.totals(), "lines")).isEqualByComparingTo(Integer.toString(lineCount));
        assertThat(number(r.totals(), "vouchers"))
                .isEqualByComparingTo(Integer.toString(voucherCount));
    }

    /** 行 = 公司（主表）、列 = 贷方科目名称；固定等值 公司 = 乙公司。 */
    @Test
    void mainFieldsWorkAsDimensionsAndFiltersAtDetailGrain() {
        rollback(
                () -> {
                    f.objects().app().seed();
                    var r =
                            f.preview(
                                    with(
                                            f.detailConfig(
                                                    "PIVOT",
                                                    List.of(f.main(f.company, "VALUE")),
                                                    List.of(f.creditName()),
                                                    f.creditMetrics()),
                                            Map.of(f.company, COMPANY_B),
                                            List.of(),
                                            null));
                    assertThat(r.pivot().rows().stream().map(ApplicationReports.PivotHeader::keys))
                            .containsExactly(keys(COMPANY_B));
                    assertThat(r.pivot().rows().getFirst().labels()).containsExactly("乙公司");
                    assertThat(amount(r, keys(COMPANY_B), keys(SALES))).isEqualByComparingTo("200");
                    assertThat(amount(r, keys(COMPANY_B), keys(BANK))).isEqualByComparingTo("250");
                    assertThat(amount(r, keys(COMPANY_B), keys((String) null)))
                            .isEqualByComparingTo("300");
                    assertCell(r, keys(), keys(), 750, 3, 2);
                });
    }

    /** 用户筛选、固定等值、指标条件都可以用明细字段。 */
    @Test
    void detailFieldsWorkInFixedUserAndMetricConditions() {
        rollback(
                () -> {
                    f.objects();
                    var open = with(f.creditPivot(), Map.of(), List.of(f.creditAmount), null);
                    f.app(f.resource("report", "REPORT", open)).seed();
                    var filtered =
                            f.reports.query(
                                    new ApplicationReports.Query(
                                            f.app,
                                            "report",
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            1,
                                            20,
                                            condition(f.creditAmount, "gte", "300"),
                                            null),
                                    OWNER);
                    // 贷方金额 ≥ 300：L1 600、L2 400、L3 300、L5 300。
                    assertCell(filtered, keys(), keys(), 1600, 4, 3);
                    var sales =
                            f.preview(
                                    with(
                                            f.creditPivot(),
                                            Map.of(f.credit, f.accounts.get(SALES)),
                                            List.of(),
                                            null));
                    assertCell(sales, keys(), keys(), 1200, 3, 2);
                    var fixed =
                            f.preview(
                                    with(
                                            f.creditPivot(),
                                            Map.of(),
                                            List.of(),
                                            condition(f.creditAmount, "lte", "250")));
                    // 固定条件 贷方金额 ≤ 250：L4 200、L6 250。
                    assertCell(fixed, keys(), keys(), 450, 2, 2);
                    var metrics = new ArrayList<>(f.creditMetrics());
                    metrics.add(
                            new ApplicationReports.Metric(
                                    "large",
                                    "大额贷方",
                                    "SUM",
                                    f.creditAmount,
                                    condition(f.creditAmount, "gt", "300"),
                                    null,
                                    null));
                    var conditional =
                            f.preview(
                                    f.detailConfig(
                                            "PIVOT",
                                            List.of(f.creditName()),
                                            List.of(f.main(f.booked, "MONTH")),
                                            metrics));
                    // 指标条件 贷方金额 > 300：L1 600 + L2 400。
                    assertThat(number(conditional.totals(), "large")).isEqualByComparingTo("1000");
                    assertThat(number(conditional.totals(), "amount")).isEqualByComparingTo("2050");
                });
    }

    /** 极值与去重计数不受重复行影响，主表字段可以用。 */
    @Test
    void minMaxAndDistinctOnMainFieldsAreAllowedAtDetailGrain() {
        rollback(
                () -> {
                    f.objects().app().seed();
                    var r =
                            f.preview(
                                    f.detailConfig(
                                            "TABLE",
                                            List.of(f.creditName()),
                                            List.of(),
                                            List.of(
                                                    new ApplicationReports.Metric(
                                                            "top", "最大入金", "MAX", f.income),
                                                    new ApplicationReports.Metric(
                                                            "low", "最小入金", "MIN", f.income),
                                                    new ApplicationReports.Metric(
                                                            "companies",
                                                            "公司数",
                                                            "COUNT_DISTINCT",
                                                            f.company),
                                                    new ApplicationReports.Metric(
                                                            "peak",
                                                            "最大贷方",
                                                            "MAX",
                                                            f.creditAmount))));
                    var sales =
                            r.groups().stream()
                                    .filter(g -> g.keys().equals(keys(SALES)))
                                    .findFirst()
                                    .orElseThrow();
                    // 销售收入的三条分录来自 V1（入金 1000）与 V3（入金 500）。
                    assertThat(number(sales.values(), "top")).isEqualByComparingTo("1000");
                    assertThat(number(sales.values(), "low")).isEqualByComparingTo("500");
                    assertThat(number(sales.values(), "peak")).isEqualByComparingTo("600");
                    assertThat(number(r.totals(), "companies")).isEqualByComparingTo("2");
                });
    }

    /** 日期范围字段是主表日期：收窄的是所属凭证，再带出它们的分录。 */
    @Test
    void dateRangeOnMainDateFieldNarrowsDetailRows() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.creditPivot())).seed();
                    var r =
                            f.reports.query(
                                    new ApplicationReports.Query(
                                            f.app,
                                            "report",
                                            null,
                                            "2026-08-01",
                                            "2026-08-31",
                                            null,
                                            null,
                                            1,
                                            20),
                                    OWNER);
                    assertCell(r, keys(), keys(), 750, 3, 2);
                    assertThat(r.recordCount()).isEqualTo(3);
                    assertThat(
                                    r.pivot().columns().stream()
                                            .map(ApplicationReports.PivotHeader::keys))
                            .containsExactly(keys(AUGUST));
                });
    }

    /** 贷方科目指向一条这个人看不到的科目记录的分录整行不计，不并进「未填写」。 */
    @Test
    void restrictedTargetRowsAreExcludedNotMergedIntoBlank() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.creditPivot())).seed();
                    // 科目只看本人创建的；「销售收入」这条记录改成别人创建的。
                    jdbc.update(
                            "UPDATE public.\""
                                    + f.account.tableName()
                                    + "\" SET creator='20009' WHERE id=?",
                            Long.parseLong(f.accounts.get(SALES)));
                    f.ceiling(
                            f.account,
                            f.accountGrant(
                                    Set.of("READ", "EXPORT"), "OWN", ids(f.account.fields())));
                    var r = f.reports.query(f.query("report"), OWNER);
                    assertThat(r.pivot().rows().stream().map(ApplicationReports.PivotHeader::keys))
                            .containsExactly(keys(BANK), keys((String) null));
                    assertCell(r, keys((String) null), keys(), 300, 1, 1);
                    assertCell(r, keys(), keys(), 850, 3, 3);
                    assertThat(r.recordCount()).isEqualTo(3);
                });
    }

    /** 直接拿引用字段本身分组：键是科目记录 ID；分组数与值同目标表 T 的行合计。标签照实记录。 */
    @Test
    void detailReferenceFieldItselfAsDimension() {
        rollback(
                () -> {
                    f.objects().app().seed();
                    var r =
                            f.preview(
                                    f.detailConfig(
                                            "TABLE",
                                            List.of(f.main(f.credit, "VALUE")),
                                            List.of(),
                                            f.creditMetrics()));
                    assertThat(r.dimensionNames()).containsExactly("贷方科目");
                    assertThat(r.groups().stream().map(ApplicationReports.Group::keys))
                            .containsExactly(
                                    keys(f.accounts.get(SALES)),
                                    keys(f.accounts.get(BANK)),
                                    keys((String) null));
                    assertGroup(r.groups().get(0), 1200, 3, 2);
                    assertGroup(r.groups().get(1), 550, 2, 2);
                    assertGroup(r.groups().get(2), 300, 1, 1);
                    assertTotals(r, 2050, 6, 4);
                    // 现状：对象引用字段作维度时标签就是记录 ID（显示目标记录名称不在一期）。
                    assertThat(r.groups().get(0).labels()).containsExactly(f.accounts.get(SALES));
                });
    }

    /** 主记录粒度对照表 R：7 月 1000 / 2，8 月 500 / 3；总计 1500 / 5。结果里没有明细名称。 */
    @Test
    void rootGrainResultsAreUntouchedOnTheSameData() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("root", "REPORT", rootTable())).seed();
                    for (var r :
                            List.of(
                                    f.reports.query(f.query("root"), OWNER),
                                    f.preview(rootTable()))) {
                        assertThat(r.detailName()).isNull();
                        assertThat(r.recordCount()).isEqualTo(5);
                        assertThat(r.groups().stream().map(ApplicationReports.Group::keys))
                                .containsExactly(keys(JULY), keys(AUGUST));
                        assertThat(number(r.groups().get(0).values(), "income"))
                                .isEqualByComparingTo("1000");
                        assertThat(number(r.groups().get(0).values(), "count"))
                                .isEqualByComparingTo("2");
                        assertThat(number(r.groups().get(1).values(), "income"))
                                .isEqualByComparingTo("500");
                        assertThat(number(r.groups().get(1).values(), "count"))
                                .isEqualByComparingTo("3");
                        assertThat(number(r.totals(), "income")).isEqualByComparingTo("1500");
                        assertThat(number(r.totals(), "count")).isEqualByComparingTo("5");
                    }
                });
    }

    /** 存量形状的主记录粒度配置：17 参构造器，没有 grain / detailId。 */
    private ApplicationReports.Config rootTable() {
        return new ApplicationReports.Config(
                f.voucher.objectId(),
                List.of(f.main(f.booked, "MONTH")),
                List.of(
                        new ApplicationReports.Metric("income", "入金合计", "SUM", f.income),
                        new ApplicationReports.Metric("count", "张数", "COUNT", null)),
                Map.of(),
                List.of(),
                f.booked,
                "Asia/Shanghai",
                "TABLE",
                null,
                false,
                30,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    // ---------------------------------------------------------------- 5.4 下钻（明细行）

    private Set<String> drillIds(
            String report, List<String> group, List<String> columnGroup, String metric) {
        var page = f.reports.details(f.drill(report, group, columnGroup, metric), OWNER);
        assertThat(page.getList()).hasSize(page.getTotal().intValue());
        return page.getList().stream()
                .map(ApplicationRecords.Row::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void assertDrill(
            List<String> group, List<String> columnGroup, int amount, String... expected) {
        var page = f.reports.details(f.drill("report", group, columnGroup, null), OWNER);
        String at = group + " × " + columnGroup;
        assertThat(page.getTotal()).as("行数 " + at).isEqualTo(expected.length);
        assertThat(page.getList().stream().map(ApplicationRecords.Row::id))
                .as("行身份 " + at)
                .containsExactlyInAnyOrder(expected);
        BigDecimal sum = BigDecimal.ZERO;
        for (var row : page.getList()) {
            assertThat(row.id()).startsWith(row.parentId() + ":");
            sum = sum.add(new BigDecimal(row.values().get(f.creditAmount).toString()));
        }
        assertThat(sum).as("贷方金额之和 " + at).isEqualByComparingTo(Integer.toString(amount));
    }

    /** 目标表 T 的每一个格：条数 = 该格的行数，行身份 = 手算的「主ID:明细行ID」，贷方金额之和 = 该格的值。 */
    @Test
    void everyCellDrillsToItsOwnDetailRows() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.creditPivot())).seed();
                    String l1 = f.rowId("V1", "L1"), l2 = f.rowId("V1", "L2");
                    String l3 = f.rowId("V2", "L3"), l4 = f.rowId("V3", "L4");
                    String l5 = f.rowId("V3", "L5"), l6 = f.rowId("V4", "L6");
                    assertDrill(keys(SALES), keys(JULY), 1000, l1, l2);
                    assertDrill(keys(SALES), keys(AUGUST), 200, l4);
                    assertDrill(keys(SALES), keys(), 1200, l1, l2, l4);
                    assertDrill(keys(BANK), keys(JULY), 300, l3);
                    assertDrill(keys(BANK), keys(AUGUST), 250, l6);
                    assertDrill(keys(BANK), keys(), 550, l3, l6);
                    assertDrill(keys((String) null), keys(AUGUST), 300, l5);
                    assertDrill(keys((String) null), keys(), 300, l5);
                    assertDrill(keys((String) null), keys(JULY), 0);
                    assertDrill(keys(), keys(JULY), 1300, l1, l2, l3);
                    assertDrill(keys(), keys(AUGUST), 750, l4, l5, l6);
                    assertDrill(keys(), keys(), 2050, l1, l2, l3, l4, l5, l6);
                    assertDrill(null, null, 2050, l1, l2, l3, l4, l5, l6);
                });
    }

    /** 行里带主表与明细两部分的值与显示值；排序 = 主表主键、明细行主键。 */
    @Test
    void drillRowsCarryMainAndDetailValuesWithDisplayNames() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.creditPivot())).seed();
                    var page =
                            f.reports.details(
                                    f.drill("report", keys(SALES), keys(JULY), null), OWNER);
                    assertThat(page.getTotal()).isEqualTo(2);
                    assertThat(page.getList().stream().map(ApplicationRecords.Row::id))
                            .containsExactly(f.rowId("V1", "L1"), f.rowId("V1", "L2"));
                    var first = page.getList().get(0);
                    var second = page.getList().get(1);
                    assertThat(first.parentId()).isEqualTo(f.vouchers.get("V1"));
                    assertThat(first.revision())
                            .isEqualTo(
                                    f.records
                                            .get(
                                                    f.app,
                                                    f.voucher.objectId(),
                                                    f.vouchers.get("V1"),
                                                    OWNER)
                                            .record()
                                            .revision());
                    assertThat(first.values().get(f.booked).toString()).startsWith("2026-07-05");
                    assertThat(first.values()).containsEntry(f.memo, "V1");
                    assertThat(new BigDecimal(first.values().get(f.creditAmount).toString()))
                            .isEqualByComparingTo("600");
                    assertThat(new BigDecimal(second.values().get(f.creditAmount).toString()))
                            .isEqualByComparingTo("400");
                    assertThat(new BigDecimal(first.values().get(f.income).toString()))
                            .isEqualByComparingTo("1000");
                    assertThat(first.values().get(f.credit).toString())
                            .isEqualTo(f.accounts.get(SALES));
                    assertThat(first.displayValues()).containsEntry(f.credit, SALES);
                    assertThat(first.displayValues()).containsEntry(f.debit, BANK);
                    assertThat(first.displayValues()).containsEntry(f.company, "甲公司");
                    // 值 = 主表字段 ∪ 分录的全部有效字段；能力取主记录的，可读字段并上分录字段。
                    assertThat(first.values().keySet())
                            .containsAll(f.mainFields())
                            .containsAll(ids(f.lines.fields()));
                    assertThat(first.permissions().readFields())
                            .containsAll(f.mainFields())
                            .containsAll(ids(f.lines.fields()));
                    assertThat(first.permissions().actions()).contains("READ");
                    // 全部六行：先按主表主键，再按明细行主键。
                    var all = f.reports.details(f.drill("report", null, null, null), OWNER);
                    assertThat(all.getList().stream().map(ApplicationRecords.Row::id))
                            .containsExactly(
                                    f.rowId("V1", "L1"),
                                    f.rowId("V1", "L2"),
                                    f.rowId("V2", "L3"),
                                    f.rowId("V3", "L4"),
                                    f.rowId("V3", "L5"),
                                    f.rowId("V4", "L6"));
                });
    }

    /** 指标下钻叠加该指标的条件；「非空」限定落在指标字段自己所在的表上；「主记录数」下钻出来的仍是明细行。 */
    @Test
    void metricDrillAppliesMetricConditionAndNotNullOnTheMetricsOwnTable() {
        rollback(
                () -> {
                    f.objects();
                    var metrics = new ArrayList<>(f.creditMetrics());
                    metrics.add(
                            new ApplicationReports.Metric(
                                    "large",
                                    "大额贷方",
                                    "SUM",
                                    f.creditAmount,
                                    condition(f.creditAmount, "gt", "300"),
                                    null,
                                    null));
                    metrics.add(new ApplicationReports.Metric("top", "最大入金", "MAX", f.income));
                    f.app(
                                    f.resource(
                                            "report",
                                            "REPORT",
                                            f.detailConfig(
                                                    "PIVOT",
                                                    List.of(f.creditName()),
                                                    List.of(f.main(f.booked, "MONTH")),
                                                    metrics)))
                            .seed();
                    assertThat(drillIds("report", null, null, "large"))
                            .containsExactly(f.rowId("V1", "L1"), f.rowId("V1", "L2"));
                    // 张数的 8 月列合计是 2，下钻出来的是参与计算的 3 条分录。
                    assertThat(drillIds("report", keys(), keys(AUGUST), "vouchers"))
                            .containsExactly(
                                    f.rowId("V3", "L4"), f.rowId("V3", "L5"), f.rowId("V4", "L6"));
                    // 指标字段在明细上（贷方金额）与在主表上（入金）各点一次：都能取到同一格的行。
                    assertThat(drillIds("report", keys(SALES), keys(JULY), "amount"))
                            .containsExactly(f.rowId("V1", "L1"), f.rowId("V1", "L2"));
                    assertThat(drillIds("report", keys(SALES), keys(JULY), "top"))
                            .containsExactly(f.rowId("V1", "L1"), f.rowId("V1", "L2"));
                    // 贷方金额为空的分录不参与「贷方金额合计」，点这个指标时不出现；点「行数」时出现。
                    jdbc.update(
                            "UPDATE public.\""
                                    + f.lines.tableName()
                                    + "\" SET \""
                                    + column(
                                            f.lines.fields(),
                                            f.lines.fieldOptions(),
                                            f.creditAmount)
                                    + "\"=NULL WHERE id=?",
                            Long.parseLong(f.lineIds.get("L4")));
                    assertThat(drillIds("report", keys(SALES), keys(AUGUST), "amount")).isEmpty();
                    assertThat(drillIds("report", keys(SALES), keys(AUGUST), "lines"))
                            .containsExactly(f.rowId("V3", "L4"));
                });
    }

    /** 每页 2 条翻页：三页合起来是 6 行，无重复无遗漏。 */
    @Test
    void drillPagination() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.creditPivot())).seed();
                    List<String> seen = new ArrayList<>();
                    for (int page = 1; page <= 3; page++) {
                        var result =
                                f.reports.details(
                                        new ApplicationReports.Query(
                                                f.app, "report", null, null, null, null, null, page,
                                                2),
                                        OWNER);
                        assertThat(result.getTotal()).isEqualTo(6);
                        assertThat(result.getList()).hasSize(2);
                        result.getList().forEach(row -> seen.add(row.id()));
                    }
                    assertThat(seen)
                            .containsExactly(
                                    f.rowId("V1", "L1"),
                                    f.rowId("V1", "L2"),
                                    f.rowId("V2", "L3"),
                                    f.rowId("V3", "L4"),
                                    f.rowId("V3", "L5"),
                                    f.rowId("V4", "L6"));
                    assertThat(
                                    f.reports
                                            .details(
                                                    new ApplicationReports.Query(
                                                            f.app, "report", null, null, null, null,
                                                            null, 4, 2),
                                                    OWNER)
                                            .getList())
                            .isEmpty();
                });
    }

    /** 明细粒度的统计不能作为数据视图的统计下钻来源。 */
    @Test
    void detailGrainReportCannotFeedViewDrill() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.creditPivot())).seed();
                    var drill =
                            new ApplicationReports.Drill(
                                    f.app, "report", null, null, null, null, null, null, null,
                                    null);
                    assertThatThrownBy(
                                    () ->
                                            f.reports.drillScope(
                                                    drill, f.app, f.voucher.objectId(), OWNER))
                            .hasMessage(ReportGrainMessages.VIEW_DRILL);
                    assertThatThrownBy(
                                    () ->
                                            f.reports.drillTotal(
                                                    drill, f.app, f.voucher.objectId(), OWNER))
                            .hasMessage("按明细行的统计暂不支持在数据视图里下钻");
                });
    }

    /** 主记录粒度的下钻仍返回主记录：对照表 R 的 7 月是 V1、V2，没有 parentId。 */
    @Test
    void rootGrainDrillIsUnchanged() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("root", "REPORT", rootTable())).seed();
                    var page = f.reports.details(f.drill("root", keys(JULY), null, null), OWNER);
                    assertThat(page.getTotal()).isEqualTo(2);
                    assertThat(page.getList().stream().map(ApplicationRecords.Row::id))
                            .containsExactly(f.vouchers.get("V1"), f.vouchers.get("V2"));
                    assertThat(page.getList())
                            .allSatisfy(row -> assertThat(row.parentId()).isNull());
                    assertThat(page.getList().getFirst().values().keySet())
                            .containsExactlyInAnyOrderElementsOf(f.mainFields());
                    assertThat(
                                    f.reports
                                            .drillScope(
                                                    new ApplicationReports.Drill(
                                                            f.app,
                                                            "root",
                                                            keys(AUGUST),
                                                            null,
                                                            null,
                                                            null,
                                                            null,
                                                            null,
                                                            null,
                                                            null),
                                                    f.app,
                                                    f.voucher.objectId(),
                                                    OWNER)
                                            .keys())
                            .containsExactlyInAnyOrder(
                                    f.vouchers.get("V3"),
                                    f.vouchers.get("V4"),
                                    f.vouchers.get("V5"));
                });
    }

    // ---------------------------------------------------------------- 5.5 序列化与下发

    private Map<String, Object> asMap(Object value) {
        return mapper.convertValue(
                value, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    /** 主记录粒度的配置保存出来没有 grain、detailId 两个键；传入 ROOT 也归一成没有键。 */
    @Test
    void rootConfigSerializesWithoutNewKeys() {
        rollback(
                () -> {
                    f.objects().app();
                    var validator = servicesContext.getBean(ApplicationReportValidator.class);
                    Map<String, DataCenter.Definition> definitions =
                            Map.of(
                                    f.voucher.objectId(),
                                    f.voucher,
                                    f.account.objectId(),
                                    f.account);
                    var legacy = asMap(validator.normalize(rootTable(), definitions));
                    // 合入「统计排序与行数」之后的基线（local/release-r2）多一个 sortBy 键
                    assertThat(legacy.keySet())
                            .containsExactlyInAnyOrder(
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
                                    "sortBy",
                                    "limit",
                                    "detailViewId",
                                    "conditions",
                                    "chart",
                                    "columnDimensions",
                                    "pivot",
                                    "detailEditable");
                    var rootTable = rootTable();
                    var explicit =
                            f.config(
                                    "ROOT",
                                    null,
                                    "TABLE",
                                    rootTable.dimensions(),
                                    List.of(),
                                    rootTable.metrics());
                    var normalized = validator.normalize(explicit, definitions);
                    assertThat(normalized.grain()).isNull();
                    assertThat(normalized.detailId()).isNull();
                    assertThat(asMap(normalized)).doesNotContainKeys("grain", "detailId");
                    assertThat(mapper.valueToTree(normalized).has("grain")).isFalse();
                    // 已保存进应用的资源配置同样没有这两个键。
                    var saved =
                            f.apps.save(
                                    new ApplicationCenter.Save(
                                            f.app,
                                            f.apps.get(f.app).application().revision(),
                                            f.apps.get(f.app).application().code(),
                                            "账务",
                                            null,
                                            null,
                                            new ApplicationCenter.Definition(
                                                    f.references(),
                                                    List.of(
                                                            f.resource(
                                                                    "root", "REPORT", explicit)))),
                                    OWNER);
                    assertThat(saved.draft().resources().getFirst().config())
                            .doesNotContainKeys("grain", "detailId")
                            .containsKey("detailEditable");
                });
    }

    /** 主记录粒度的结果 JSON 没有 detailName 键；明细粒度有。 */
    @Test
    void rootResultSerializesWithoutDetailName() {
        rollback(
                () -> {
                    f.objects().app().seed();
                    assertThat(mapper.valueToTree(f.preview(rootTable())).has("detailName"))
                            .isFalse();
                    var detail = mapper.valueToTree(f.preview(f.creditPivot()));
                    assertThat(detail.get("detailName").asText()).isEqualTo("分录");
                });
    }

    /** 运行端下发的明细粒度统计配置带着粒度与明细；主记录粒度的不多出键。 */
    @Test
    void runtimeConfigCarriesGrainAndDetailId() {
        rollback(
                () -> {
                    f.objects();
                    f.app(
                                    f.resource("report", "REPORT", f.creditPivot()),
                                    f.resource("root", "REPORT", rootTable()))
                            .seed();
                    var resources =
                            servicesContext
                                    .getBean(ApplicationRuntimeService.class)
                                    .application(f.app, OWNER)
                                    .definition()
                                    .resources();
                    var report =
                            resources.stream().filter(r -> r.id().equals("report")).findFirst();
                    assertThat(report).isPresent();
                    assertThat(report.get().config())
                            .containsEntry("grain", "DETAIL")
                            .containsEntry("detailId", f.lines.id());
                    var root = resources.stream().filter(r -> r.id().equals("root")).findFirst();
                    assertThat(root).isPresent();
                    assertThat(root.get().config()).doesNotContainKeys("grain", "detailId");
                });
    }

    /** 明细粒度配置经保存归一、展示格式补全、替换指标之后两个分量都在。 */
    @Test
    void normalizeKeepsGrainAndDetailId() {
        rollback(
                () -> {
                    f.objects().app();
                    var validator = servicesContext.getBean(ApplicationReportValidator.class);
                    Map<String, DataCenter.Definition> definitions =
                            Map.of(
                                    f.voucher.objectId(),
                                    f.voucher,
                                    f.account.objectId(),
                                    f.account);
                    var normalized = validator.normalize(f.creditPivot(), definitions);
                    assertThat(normalized.grain()).isEqualTo("DETAIL");
                    assertThat(normalized.detailId()).isEqualTo(f.lines.id());
                    var formatted = validator.normalizeFormats(normalized, f.voucher);
                    assertThat(formatted.grain()).isEqualTo("DETAIL");
                    assertThat(formatted.detailId()).isEqualTo(f.lines.id());
                    var replaced = normalized.withMetrics(normalized.metrics());
                    assertThat(replaced).isEqualTo(normalized);
                    assertThat(asMap(normalized))
                            .containsEntry("grain", "DETAIL")
                            .containsEntry("detailId", f.lines.id());
                });
    }

    /** 明细里的金额字段求和自动按金额显示；「主记录数」「明细行数」不是金额。 */
    @Test
    void moneyFormatIsDetectedOnDetailFields() {
        rollback(
                () -> {
                    f.objects().app();
                    var validator = servicesContext.getBean(ApplicationReportValidator.class);
                    var normalized =
                            validator.normalize(
                                    f.creditPivot(),
                                    Map.of(
                                            f.voucher.objectId(),
                                            f.voucher,
                                            f.account.objectId(),
                                            f.account));
                    assertThat(normalized.metrics().get(0).format().financial()).isTrue();
                    assertThat(normalized.metrics().get(1).format()).isNull();
                    assertThat(normalized.metrics().get(2).format()).isNull();
                    // 运行期对已发布快照补展示格式时同样认得明细里的金额字段。
                    assertThat(
                                    validator
                                            .normalizeFormats(f.creditPivot(), f.voucher)
                                            .metrics()
                                            .getFirst()
                                            .format()
                                            .financial())
                            .isTrue();
                });
    }
}
