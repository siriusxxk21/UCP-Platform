package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;
import static com.richuang.os.nocode.tools.ReportDetailGrainFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.web.RecordExcelService;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

/**
 * 集成分支 local/release-r3 上「统计排序与行数」与「按明细行统计」同在一张统计里：
 *
 * <ul>
 *   <li>明细粒度的透视表、汇总表按指标 / 行维度排序（配置的与查看时点列头的），行数不限制或填了数字，透视表受格数预算约束；导出与屏幕同序、口径行写明细；
 *   <li>授权：成员是「全部」时与应用创建人看到的相同；成员没有明细的查看权限时，带不带排序、导出、下钻都报「没有明细的查看权限」那一句（不是粒度那一句）；
 *   <li>隐式只读：应用只引用凭证、科目是因关联而只读时，不能沿分录上的关系到科目的字段上分组；按分录上的引用字段本身分组时读不到科目的其它字段。
 * </ul>
 *
 * 数据见 {@link ReportDetailGrainFixture#seed}：在用的分录 6 行，贷方科目「销售收入」3 行（600、400、200，凭证 V1、V3）、「银行存款」2
 * 行（300、250，V2、V4）、 不填 1 行（300，V3）；7 月 3 行（贷方 1300）、8 月 3 行（贷方 750）。
 */
class ReportDetailGrainSortLimitIntegrationTest {
    private static final Set<String> READ_EXPORT = Set.of("READ", "EXPORT");
    private static final Set<String> ALL = Set.of("*");
    private static final String M3 =
            "没有明细「分录」的查看权限，不能按它的行做统计。请在应用的「成员与权限」里为该成员勾选可查看内部明细「分录」；"
                    + "数据对象还没有把这个明细授权给本应用时，先到数据中心该对象的「应用共享授权」里勾选";

    private ReportDetailGrainFixture f;
    private RecordExcelService excel;

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
        excel = servicesContext.getBean(RecordExcelService.class);
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

    // ── 夹具 ──

    /** 明细粒度·分录的配置，带排序依据、方向、行数与透视选项。 */
    private ApplicationReports.Config detail(
            String display,
            List<ApplicationReports.Dimension> rows,
            List<ApplicationReports.Dimension> columns,
            List<ApplicationReports.Metric> metrics,
            String sortBy,
            String sortMetricId,
            boolean descending,
            Integer limit,
            ApplicationReports.Pivot pivot) {
        boolean isPivot = "PIVOT".equals(display);
        return new ApplicationReports.Config(
                f.voucher.objectId(),
                rows,
                metrics,
                Map.of(),
                List.of(),
                f.booked,
                "Asia/Shanghai",
                display,
                sortMetricId,
                descending,
                limit,
                null,
                null,
                null,
                isPivot ? columns : null,
                isPivot ? pivot : null,
                null,
                sortBy,
                "DETAIL",
                f.lines.id());
    }

    private ApplicationReports.Config byAmount(Integer limit) {
        return detail(
                "PIVOT",
                List.of(f.creditName()),
                List.of(),
                f.creditMetrics(),
                "METRIC",
                "amount",
                true,
                limit,
                null);
    }

    private ApplicationReports.Config byMonth() {
        return detail(
                "PIVOT",
                List.of(f.main(f.booked, "MONTH")),
                List.of(),
                f.creditMetrics(),
                "DIMENSION",
                null,
                true,
                null,
                null);
    }

    private ApplicationReports.Config table() {
        return detail(
                "TABLE",
                List.of(f.creditName()),
                List.of(),
                f.creditMetrics(),
                "METRIC",
                "amount",
                true,
                null,
                null);
    }

    private void ledger() {
        f.objects();
        f.app(
                        f.resource("by_amount", "REPORT", byAmount(null)),
                        f.resource("by_amount_top2", "REPORT", byAmount(2)),
                        f.resource("by_month", "REPORT", byMonth()),
                        f.resource("credit_table", "REPORT", table()))
                .seed();
    }

    private ApplicationReports.Query query(String report, ApplicationReports.Sort sort) {
        return new ApplicationReports.Query(
                f.app, report, null, null, null, null, null, 1, 100, null, null, null, sort);
    }

    private static ApplicationReports.Sort byMetric(String metric, boolean descending) {
        return new ApplicationReports.Sort(metric, null, List.of(), descending);
    }

    private static ApplicationReports.Sort byDimension(int index, boolean descending) {
        return new ApplicationReports.Sort(null, index, List.of(), descending);
    }

    private static List<List<String>> rowKeys(ApplicationReports.Result r) {
        return r.pivot().rows().stream().map(ApplicationReports.PivotHeader::keys).toList();
    }

    private static List<List<String>> groupKeys(ApplicationReports.Result r) {
        return r.groups().stream().map(ApplicationReports.Group::keys).toList();
    }

    private List<Map<Integer, String>> sheet(byte[] file) {
        return cn.idev.excel.FastExcelFactory.read(new java.io.ByteArrayInputStream(file))
                .headRowNumber(0)
                .sheet()
                .doReadSync();
    }

    private static String lastLine(List<Map<Integer, String>> lines) {
        return lines.getLast().get(0);
    }

    private static List<String> firstColumn(List<Map<Integer, String>> lines, int from, int to) {
        List<String> result = new ArrayList<>();
        for (int i = from; i < to; i++) result.add(lines.get(i).get(0));
        return result;
    }

    // ── 用例 ──

    /** 明细粒度：配置的排序（按指标、按行维度）与点列头排序都按明细行的格排，合计不受影响；填了行数只展示前几行并标注。 */
    @Test
    void detailGrainPivotSortsAndLimitsLikeTheRootGrain() {
        rollback(
                () -> {
                    ledger();
                    var amount = f.reports.query(query("by_amount", null), OWNER);
                    assertThat(rowKeys(amount))
                            .containsExactly(keys("销售收入"), keys("银行存款"), keys((String) null));
                    assertCell(amount, keys("销售收入"), keys(), 1200, 3, 2);
                    assertCell(amount, keys(), keys(), 2050, 6, 4);
                    assertThat(amount.recordCount()).isEqualTo(6);
                    assertThat(amount.detailName()).isEqualTo("分录");
                    assertThat(amount.pivot().rowsTruncated()).isFalse();
                    // 点「行数」列头升序：1、2、3 行
                    var clicked =
                            f.reports.query(query("by_amount", byMetric("lines", false)), OWNER);
                    assertThat(rowKeys(clicked))
                            .containsExactly(keys((String) null), keys("银行存款"), keys("销售收入"));
                    assertCell(clicked, keys(), keys(), 2050, 6, 4);
                    // 按行维度（月份）降序；点行维度列头改成升序
                    var month = f.reports.query(query("by_month", null), OWNER);
                    assertThat(rowKeys(month)).containsExactly(keys("2026-08"), keys("2026-07"));
                    assertCell(month, keys("2026-08"), keys(), 750, 3, 2);
                    assertCell(month, keys("2026-07"), keys(), 1300, 3, 2);
                    assertThat(
                                    rowKeys(
                                            f.reports.query(
                                                    query("by_month", byDimension(0, false)),
                                                    OWNER)))
                            .containsExactly(keys("2026-07"), keys("2026-08"));
                    // 填了行数 2：只展示贷方金额最大的两组，合计仍按全部明细行
                    var top = f.reports.query(query("by_amount_top2", null), OWNER);
                    assertThat(rowKeys(top)).containsExactly(keys("销售收入"), keys("银行存款"));
                    assertThat(top.pivot().rowsTruncated()).isTrue();
                    assertThat(top.pivot().totalRowGroups()).isEqualTo(3);
                    assertCell(top, keys(), keys(), 2050, 6, 4);
                    // 汇总表：按贷方金额降序，不限制
                    var t = f.reports.query(query("credit_table", null), OWNER);
                    assertThat(groupKeys(t))
                            .containsExactly(keys("销售收入"), keys("银行存款"), keys((String) null));
                    assertThat(
                                    groupKeys(
                                            f.reports.query(
                                                    query("credit_table", byMetric("lines", false)),
                                                    OWNER)))
                            .containsExactly(keys((String) null), keys("银行存款"), keys("销售收入"));
                    // 排序后点格子下钻：返回的是那一组的明细行
                    var drill =
                            f.reports.details(
                                    new ApplicationReports.Query(
                                            f.app,
                                            "by_amount",
                                            null,
                                            null,
                                            null,
                                            null,
                                            keys("销售收入"),
                                            1,
                                            100,
                                            null,
                                            "amount",
                                            List.of(),
                                            byMetric("lines", false)),
                                    OWNER);
                    assertThat(drill.getTotal()).isEqualTo(3);
                    assertThat(drill.getList())
                            .extracting(ApplicationRecords.Row::id)
                            .containsExactlyInAnyOrder(
                                    f.vouchers.get("V1") + ":" + f.lineIds.get("L1"),
                                    f.vouchers.get("V1") + ":" + f.lineIds.get("L2"),
                                    f.vouchers.get("V3") + ":" + f.lineIds.get("L4"));
                });
    }

    /** 导出与屏幕同序（带点列头的排序），口径行写「来源明细行 N 行（明细「分录」）」；汇总表那一行带「展示 M / N 组」。 */
    @Test
    void exportFollowsTheClickedSortAndNamesTheDetail() {
        rollback(
                () -> {
                    ledger();
                    var screen =
                            f.reports.query(query("by_amount", byMetric("lines", false)), OWNER);
                    var pivot =
                            sheet(
                                    excel.report(
                                            query("by_amount", byMetric("lines", false)), OWNER));
                    // 第一行是表头，之后是三组（与屏幕同序），再后是合计
                    assertThat(firstColumn(pivot, 1, 4))
                            .isEqualTo(
                                    screen.pivot().rows().stream()
                                            .map(row -> row.labels().getFirst())
                                            .toList());
                    assertThat(rowKeys(screen))
                            .containsExactly(keys((String) null), keys("银行存款"), keys("销售收入"));
                    assertThat(lastLine(pivot)).isEqualTo("来源明细行 6 行（明细「分录」）；时区 Asia/Shanghai");
                    var top = sheet(excel.report(query("by_amount_top2", null), OWNER));
                    assertThat(lastLine(top)).isEqualTo("来源明细行 6 行（明细「分录」）；时区 Asia/Shanghai");
                    var t = sheet(excel.report(query("credit_table", null), OWNER));
                    assertThat(lastLine(t))
                            .isEqualTo("展示 3 / 3 组；来源明细行 6 行（明细「分录」）；时区 Asia/Shanghai");
                });
    }

    /**
     * 明细粒度的透视表行多、列组也多：不限制时展示的行数受「行数 × 已展示列组数 ≤ MAX_PIVOT_CELLS」约束并标注，合计仍按全部明细行。造数：在凭证 V1 下直接插 1050
     * 行分录，借方金额 1..1050（行维度），贷方金额在 1..100 间轮换（列维度，列组上限 100）。
     */
    @Test
    void detailGrainPivotIsCappedByTheCellBudget() {
        rollback(
                () -> {
                    ledger();
                    int added = 1050, groups = 100;
                    String table = f.lines.tableName();
                    String debitColumn = f.lines.fieldOptions().get(f.debitAmount).columnName();
                    String creditColumn = f.lines.fieldOptions().get(f.creditAmount).columnName();
                    String template = f.lineIds.get("L1");
                    List<String> copied =
                            jdbc.queryForList(
                                    "SELECT column_name FROM information_schema.columns WHERE"
                                            + " table_schema = 'public' AND table_name = ? AND"
                                            + " column_name NOT IN ('id', ?, ?) ORDER BY"
                                            + " ordinal_position",
                                    String.class,
                                    table,
                                    debitColumn,
                                    creditColumn);
                    String list =
                            String.join(",", copied.stream().map(c -> "\"" + c + "\"").toList());
                    jdbc.update(
                            "INSERT INTO public.\""
                                    + table
                                    + "\" (id,\""
                                    + debitColumn
                                    + "\",\""
                                    + creditColumn
                                    + "\","
                                    + list
                                    + ") SELECT (SELECT max(id) FROM public.\""
                                    + table
                                    + "\") + g, g, (g % ?) + 1,"
                                    + list
                                    + " FROM public.\""
                                    + table
                                    + "\" t, generate_series(1, ?) g WHERE t.id = ?",
                            groups,
                            added,
                            Long.parseLong(template));
                    var wide =
                            detail(
                                    "PIVOT",
                                    List.of(f.main(f.debitAmount, "VALUE")),
                                    List.of(f.main(f.creditAmount, "VALUE")),
                                    List.of(
                                            new ApplicationReports.Metric(
                                                    "lines", "行数", "COUNT", null)),
                                    "DIMENSION",
                                    null,
                                    false,
                                    null,
                                    new ApplicationReports.Pivot(null, null, null, null, groups));
                    var r = f.preview(wide);
                    assertThat(r.recordCount()).isEqualTo(6 + added);
                    assertThat(r.pivot().columns()).hasSize(groups);
                    assertThat(r.pivot().rows())
                            .hasSize(ApplicationReports.MAX_PIVOT_CELLS / groups);
                    assertThat(r.pivot().rowsTruncated()).isTrue();
                    assertThat(r.pivot().totalRowGroups()).isEqualTo(added);
                    assertThat(number(cell(r, keys(), keys()).values(), "lines"))
                            .isEqualByComparingTo(BigDecimal.valueOf(6 + added));
                    // 按借方金额升序：第一行是 1（金额字段的原值带两位小数）
                    assertThat(r.pivot().rows().getFirst().keys()).containsExactly("1.00");
                });
    }

    /** 成员授权是「全部」：明细粒度的排序、不限制、点列头排序、导出与应用创建人看到的相同。 */
    @Test
    void memberGrantedAllSeesTheSameSortedDetailReport() {
        rollback(
                () -> {
                    ledger();
                    f.members(
                            member(
                                    U1,
                                    f.voucherGrant(READ_EXPORT, "ALL", ALL, ALL),
                                    f.accountGrant(READ_EXPORT, "ALL", ALL)));
                    for (var sort :
                            Arrays.asList(
                                    null, byMetric("lines", false), byMetric("vouchers", true))) {
                        var mine = f.reports.query(query("by_amount", sort), U1);
                        var theirs = f.reports.query(query("by_amount", sort), OWNER);
                        assertThat(rowKeys(mine)).isEqualTo(rowKeys(theirs));
                        assertThat(mine.pivot().cells()).isEqualTo(theirs.pivot().cells());
                    }
                    assertThat(
                                    sheet(
                                            excel.report(
                                                    query("by_amount", byMetric("lines", false)),
                                                    U1)))
                            .isEqualTo(
                                    sheet(
                                            excel.report(
                                                    query("by_amount", byMetric("lines", false)),
                                                    OWNER)));
                });
    }

    /** 成员没有「分录」的查看权限（主表字段全给）：带不带排序、导出、下钻，都是「没有明细的查看权限」那一句，不是粒度那一句，也不含「未授权」。 */
    @Test
    void memberWithoutDetailReadIsRefusedWithTheRealPermissionMessage() {
        rollback(
                () -> {
                    ledger();
                    f.members(
                            member(
                                    U1,
                                    f.voucherGrant(READ_EXPORT, "ALL", f.mainFields(), Set.of()),
                                    f.accountGrant(READ_EXPORT, "ALL", ALL)));
                    for (var sort :
                            Arrays.asList(null, byMetric("amount", false), byDimension(0, true)))
                        assertThatThrownBy(() -> f.reports.query(query("by_amount", sort), U1))
                                .hasMessage(M3)
                                .hasMessageNotContaining("未授权")
                                .hasMessageNotContaining("按主记录汇总");
                    assertThatThrownBy(
                                    () ->
                                            excel.report(
                                                    query("by_amount", byMetric("lines", true)),
                                                    U1))
                            .hasMessage(M3);
                    assertThatThrownBy(() -> f.reports.export(query("credit_table", null), U1))
                            .hasMessage(M3);
                    assertThatThrownBy(() -> f.reports.details(query("by_amount", null), U1))
                            .hasMessage(M3);
                    assertThat(f.reports.available(f.app, "by_amount", U1)).isFalse();
                });
    }

    /**
     * 隐式只读：另建一个只引用凭证的应用，科目是因分录上的关系而只读的对象。按分录上的引用字段本身分组可以（行键是科目记录 ID，读不到科目编码）；
     * 沿关系到科目的「科目名称」上分组的统计保存不了。
     */
    @Test
    void impliedReadOnlyTargetLeaksNothingUnderTheDetailGrain() {
        rollback(
                () -> {
                    ledger();
                    var reference = f.objects.getVersion(f.voucher.objectId(), null);
                    var raw =
                            detail(
                                    "PIVOT",
                                    List.of(f.main(f.credit, "VALUE")),
                                    List.of(),
                                    f.creditMetrics(),
                                    "METRIC",
                                    "amount",
                                    true,
                                    null,
                                    null);
                    var saved =
                            f.apps.save(
                                    new ApplicationCenter.Save(
                                            null,
                                            null,
                                            f.support.prefix + "implied",
                                            "只引用凭证",
                                            null,
                                            null,
                                            new ApplicationCenter.Definition(
                                                    List.of(
                                                            new ApplicationCenter.ObjectReference(
                                                                    reference.objectId(),
                                                                    reference.versionNo(),
                                                                    reference.checksum())),
                                                    List.of(f.resource("raw", "REPORT", raw)))),
                                    OWNER);
                    String implied = saved.application().id();
                    grantApplicationObjects(implied);
                    f.apps.publish(new ApplicationCenter.Revision(implied, 0, "隐式只读"), OWNER);
                    var r =
                            f.reports.query(
                                    new ApplicationReports.Query(
                                            implied, "raw", null, null, null, null, null, 1, 100,
                                            null, null, null, null),
                                    OWNER);
                    assertThat(new HashSet<>(rowKeys(r)))
                            .isEqualTo(
                                    Set.of(
                                            keys(f.accounts.get("销售收入")),
                                            keys(f.accounts.get("银行存款")),
                                            keys((String) null)));
                    assertThat(rowKeys(r).getFirst()).isEqualTo(keys(f.accounts.get("销售收入")));
                    var exported =
                            sheet(
                                    excel.report(
                                            new ApplicationReports.Query(
                                                    implied, "raw", null, null, null, null, null, 1,
                                                    100, null, null, null, null),
                                            OWNER));
                    assertThat(
                                    exported.stream()
                                            .flatMap(line -> line.values().stream())
                                            .filter(Objects::nonNull))
                            .noneMatch(text -> text.contains("6001") || text.contains("1002"));
                    var path =
                            detail(
                                    "PIVOT",
                                    List.of(f.creditName()),
                                    List.of(),
                                    f.creditMetrics(),
                                    null,
                                    "amount",
                                    true,
                                    null,
                                    null);
                    assertThatThrownBy(
                                    () ->
                                            f.apps.save(
                                                    new ApplicationCenter.Save(
                                                            implied,
                                                            f.apps.get(implied)
                                                                    .application()
                                                                    .revision(),
                                                            f.support.prefix + "implied",
                                                            "只引用凭证",
                                                            null,
                                                            null,
                                                            new ApplicationCenter.Definition(
                                                                    List.of(
                                                                            new ApplicationCenter
                                                                                    .ObjectReference(
                                                                                    reference
                                                                                            .objectId(),
                                                                                    reference
                                                                                            .versionNo(),
                                                                                    reference
                                                                                            .checksum())),
                                                                    List.of(
                                                                            f.resource(
                                                                                    "path",
                                                                                    "REPORT",
                                                                                    path)))),
                                                    OWNER))
                            .hasMessageContaining("统计对象未被应用引用");
                });
    }
}
