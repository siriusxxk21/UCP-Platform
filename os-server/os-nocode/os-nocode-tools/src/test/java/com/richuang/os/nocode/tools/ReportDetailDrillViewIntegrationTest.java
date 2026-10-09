package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;
import static com.richuang.os.nocode.tools.ReportDetailGrainFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.resource.ReportGrainMessages;
import com.richuang.os.nocode.runtime.service.record.RecordService;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 按明细行统计的下钻挂「下钻明细视图」并按「允许编辑」开关编辑（laneDV）。数据见 {@link ReportDetailGrainFixture#seed}：
 *
 * <pre>
 * 分录（贷方科目 / 贷方金额 / 所属凭证 / 月份）
 * L1 销售收入 600 V1 07   L2 销售收入 400 V1 07   L3 银行存款 300 V2 07
 * L4 销售收入 200 V3 08   L5 （空）   300 V3 08   L6 银行存款 250 V4 08
 * </pre>
 *
 * 下钻视图「lineview」= 凭证对象上「一行 = 一条分录」的数据视图。格子「销售收入 × 2026-08」只有 L4；它所属的 V3 还有一条不属于这个科目的 L5， 按主记录取交集会把
 * L5 也带出来——这里断言不会。
 */
class ReportDetailDrillViewIntegrationTest {
    private static final String JULY = "2026-07";
    private static final String AUGUST = "2026-08";
    private static final String SALES = "销售收入";
    private static final Set<String> READ = Set.of("READ");
    private static final Set<String> READ_UPDATE = Set.of("READ", "UPDATE");

    private ReportDetailGrainFixture f;
    private RecordService records;

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
        records = servicesContext.getBean(RecordService.class);
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

    // ---------------------------------------------------------------- 配置

    /** 凭证对象上按明细逐行显示的数据视图；detailId 为空时是「一行一张凭证」的普通视图。conditions 为粒度明细段的固定条件。 */
    private ApplicationUi.View lineView(String detailId, DynamicConditionDTO conditions) {
        var section =
                new DataViews.Section(
                        "lines",
                        "分录",
                        f.lines.id(),
                        null,
                        null,
                        null,
                        List.of(f.debitAmount, f.creditAmount),
                        conditions,
                        20,
                        true);
        var composition =
                detailId == null
                        ? null
                        : new DataViews.Composition(
                                "DETAIL",
                                detailId,
                                List.of(section),
                                List.of(
                                        new DataViews.Column(
                                                "view_credit",
                                                "贷方金额",
                                                "lines",
                                                f.creditAmount,
                                                "DETAIL",
                                                null)));
        return new ApplicationUi.View(
                f.voucher.objectId(),
                List.of(f.memo, f.booked),
                Map.of(),
                null,
                false,
                20,
                null,
                Map.of(),
                null,
                null,
                null,
                null,
                composition);
    }

    /** 附注明细上的明细粒度视图（明细不同）。 */
    private ApplicationUi.View notesView() {
        var section =
                new DataViews.Section(
                        "notes",
                        "附注",
                        f.notes.id(),
                        null,
                        null,
                        null,
                        List.of(f.remark, f.noteAmount),
                        null,
                        20,
                        true);
        return new ApplicationUi.View(
                f.voucher.objectId(),
                List.of(f.memo),
                Map.of(),
                null,
                false,
                20,
                null,
                Map.of(),
                null,
                null,
                null,
                null,
                new DataViews.Composition(
                        "DETAIL",
                        f.notes.id(),
                        List.of(section),
                        List.of(
                                new DataViews.Column(
                                        "view_remark", "备注", "notes", f.remark, "DETAIL", null))));
    }

    private static ApplicationReports.Config withView(
            ApplicationReports.Config c, String viewId, Boolean editable) {
        return new ApplicationReports.Config(
                c.objectId(),
                c.dimensions(),
                c.metrics(),
                c.equal(),
                c.filterFieldIds(),
                c.dateFieldId(),
                c.timeZone(),
                c.display(),
                c.sortMetricId(),
                c.descending(),
                c.limit(),
                viewId,
                c.conditions(),
                c.chart(),
                c.columnDimensions(),
                c.pivot(),
                editable,
                c.sortBy(),
                c.grain(),
                c.detailId());
    }

    /** 主记录粒度：行 = 日期按月，指标 = 记录计数。 */
    private ApplicationReports.Config rootByMonth() {
        return f.config(
                null,
                null,
                "TABLE",
                List.of(f.main(f.booked, "MONTH")),
                List.of(),
                List.of(new ApplicationReports.Metric("count", "张数", "COUNT", null)));
    }

    /** 凭证账务应用：明细粒度目标表 T（挂 lineview、允许编辑）+ lineview。 */
    private void ledger(java.util.function.Supplier<DynamicConditionDTO> sectionConditions) {
        f.objects();
        f.app(
                        f.resource(
                                "lineview",
                                "VIEW",
                                lineView(f.lines.id(), sectionConditions.get())),
                        f.resource(
                                "report",
                                "REPORT",
                                withView(f.creditPivot(), "lineview", Boolean.TRUE)))
                .seed();
    }

    private ApplicationReports.Drill drill(String report, List<String> rows, List<String> columns) {
        return new ApplicationReports.Drill(
                f.app, report, rows, columns, null, null, null, null, null, null);
    }

    private ApplicationRecords.DrillPage drillPage(
            String view, ApplicationReports.Drill drill, long actor) {
        return records.drillPage(
                new ApplicationRecords.Query(
                        f.app,
                        f.voucher.objectId(),
                        1,
                        20,
                        null,
                        Map.of(),
                        null,
                        false,
                        view,
                        null,
                        null,
                        List.of(),
                        drill),
                actor);
    }

    private static List<String> ids(ApplicationRecords.DrillPage page) {
        return page.list().stream().map(ApplicationRecords.Row::id).toList();
    }

    private List<String> detailIds(String report, List<String> rows, List<String> columns) {
        return f.reports.details(f.drill(report, rows, columns, null), OWNER).getList().stream()
                .map(ApplicationRecords.Row::id)
                .toList();
    }

    // ---------------------------------------------------------------- 1. 下钻进明细粒度视图 = 命中的明细行

    /** 格子「销售收入 × 8 月」只有 L4：下钻视图里只有 L4，没有同一张 V3 上不属于这个科目的 L5；与只读下钻、drillTotal 三方一致。 */
    @Test
    void drillIntoDetailViewShowsHitLinesOnly() {
        rollback(
                () -> {
                    ledger(() -> null);
                    var cell = drill("report", keys(SALES), keys(AUGUST));
                    var page = drillPage("lineview", cell, OWNER);
                    assertThat(ids(page)).containsExactly(f.rowId("V3", "L4"));
                    assertThat(page.total()).isEqualTo(1);
                    assertThat(page.drillTotal()).isEqualTo(1);
                    assertThat(page.list().getFirst().parentId()).isEqualTo(f.vouchers.get("V3"));
                    assertThat(page.list().getFirst().values().get("view_credit"))
                            .asString()
                            .startsWith("200");
                    assertThat(detailIds("report", keys(SALES), keys(AUGUST)))
                            .containsExactlyElementsOf(ids(page));
                    // 合计格：6 条分录全在，条数 = 格子的行数。
                    var grand = drillPage("lineview", drill("report", keys(), keys()), OWNER);
                    assertThat(ids(grand))
                            .containsExactlyInAnyOrder(
                                    f.rowId("V1", "L1"),
                                    f.rowId("V1", "L2"),
                                    f.rowId("V2", "L3"),
                                    f.rowId("V3", "L4"),
                                    f.rowId("V3", "L5"),
                                    f.rowId("V4", "L6"));
                    var result = f.reports.query(f.query("report"), OWNER);
                    assertThat(number(cell(result, keys(), keys()).values(), "lines"))
                            .isEqualByComparingTo("6");
                    // 一个月的小计列：7 月 3 条（L1 L2 L3）。
                    assertThat(
                                    ids(
                                            drillPage(
                                                    "lineview",
                                                    drill("report", keys(), keys(JULY)),
                                                    OWNER)))
                            .containsExactlyInAnyOrder(
                                    f.rowId("V1", "L1"), f.rowId("V1", "L2"), f.rowId("V2", "L3"));
                });
    }

    // ---------------------------------------------------------------- 2. 视图明细段的固定条件并入统计

    /** lineview 的分录段固定条件「贷方金额 ≥ 300」：统计、只读下钻、视图下钻三方同一范围（L1 L2 L3 L5）。 */
    @Test
    void detailSectionFixedConditionsNarrowTheReport() {
        rollback(
                () -> {
                    ledger(() -> condition(f.creditAmount, "gte", "300"));
                    var result = f.reports.query(f.query("report"), OWNER);
                    var grand = cell(result, keys(), keys()).values();
                    assertThat(number(grand, "lines")).isEqualByComparingTo("4");
                    assertThat(number(grand, "amount")).isEqualByComparingTo("1600");
                    assertThat(result.recordCount()).isEqualTo(4);
                    // 销售收入 × 8 月只有 L4（200），不满足条件 ⇒ 这一格不存在。
                    assertThat(has(result, keys(SALES), keys(AUGUST))).isFalse();
                    var page = drillPage("lineview", drill("report", keys(), keys()), OWNER);
                    assertThat(ids(page))
                            .containsExactlyInAnyOrder(
                                    f.rowId("V1", "L1"),
                                    f.rowId("V1", "L2"),
                                    f.rowId("V2", "L3"),
                                    f.rowId("V3", "L5"));
                    assertThat(page.drillTotal()).isEqualTo(4);
                    assertThat(detailIds("report", keys(), keys()))
                            .containsExactlyInAnyOrderElementsOf(ids(page));
                });
    }

    // ---------------------------------------------------------------- 3. 改一条明细金额 ⇒
    // 统计与下钻同步变；只读成员不能改

    private ApplicationAuthorization.ObjectGrant voucherWriter() {
        Set<String> main = f.mainFields();
        Set<String> lines = Set.of(f.lines.id());
        return new ApplicationAuthorization.ObjectGrant(
                f.voucher.objectId(), READ_UPDATE, "ALL", main, main, lines, lines);
    }

    private ApplicationAuthorization.ObjectGrant accountAll() {
        return f.accountGrant(READ, "ALL", ReportDetailGrainFixture.ids(f.account.fields()));
    }

    /** 把 V3 的 L4 贷方金额改成 amount（整单保存，L5 原样带回）。 */
    private void editL4(long actor, String amount) {
        var current = records.get(f.app, f.voucher.objectId(), f.vouchers.get("V3"), actor);
        List<ApplicationRecords.Row> rows = new ArrayList<>();
        for (var row : current.details().get(f.lines.id())) {
            Map<String, Object> values = new LinkedHashMap<>(row.values());
            if (row.id().equals(f.lineIds.get("L4"))) values.put(f.creditAmount, amount);
            rows.add(new ApplicationRecords.Row(row.id(), row.revision(), values));
        }
        records.save(
                new ApplicationRecords.Save(
                        f.app,
                        f.voucher.objectId(),
                        current.record().id(),
                        current.record().revision(),
                        Map.of(),
                        Map.of(f.lines.id(), rows)),
                actor);
    }

    @Test
    void editingALineChangesReportAndDrillAndRespectsMemberWritePermission() {
        rollback(
                () -> {
                    ledger(() -> null);
                    f.members(
                            member(U1, voucherWriter(), accountAll()),
                            member(
                                    U2,
                                    f.voucherGrant(
                                            READ, "ALL", f.mainFields(), Set.of(f.lines.id())),
                                    accountAll()));
                    var cell = drill("report", keys(SALES), keys(AUGUST));
                    // 能写的成员：下钻行带着所属主记录的「编辑」权限；只读成员没有。
                    var writer = drillPage("lineview", cell, U1);
                    assertThat(ids(writer)).containsExactly(f.rowId("V3", "L4"));
                    assertThat(writer.list().getFirst().permissions().actions()).contains("UPDATE");
                    var reader = drillPage("lineview", cell, U2);
                    assertThat(ids(reader)).containsExactly(f.rowId("V3", "L4"));
                    assertThat(reader.list().getFirst().permissions().actions())
                            .doesNotContain("UPDATE");
                    // 只读成员改不了：记录接口按授权拒绝，统计配置的「允许编辑」不放宽它。
                    assertThatThrownBy(() -> editL4(U2, "999"))
                            .isInstanceOf(
                                    com.richuang.os.framework.common.exception.ServiceException
                                            .class);
                    var before = f.reports.query(f.query("report"), U1);
                    assertThat(number(cell(before, keys(SALES), keys(AUGUST)).values(), "amount"))
                            .isEqualByComparingTo("200");
                    editL4(U1, "700");
                    var after = f.reports.query(f.query("report"), U1);
                    assertThat(number(cell(after, keys(SALES), keys(AUGUST)).values(), "amount"))
                            .isEqualByComparingTo("700");
                    assertThat(number(cell(after, keys(), keys()).values(), "amount"))
                            .isEqualByComparingTo("2550");
                    var page = drillPage("lineview", cell, U1);
                    assertThat(ids(page)).containsExactly(f.rowId("V3", "L4"));
                    assertThat(page.list().getFirst().values().get("view_credit"))
                            .asString()
                            .startsWith("700");
                });
    }

    // ---------------------------------------------------------------- 4. 保存校验：只能挂同一明细的明细粒度视图

    @Test
    void saveAcceptsOnlyADetailViewOfTheSameDetail() {
        rollback(
                () -> {
                    f.objects();
                    var message = ReportGrainMessages.detailDrillView(f.lines.name());
                    assertThat(message)
                            .isEqualTo(
                                    "按明细行统计时，下钻明细视图须是按明细「分录」逐行显示的数据视图（视图设置里「一行表示」选「一条内部明细」、明细来源选「分录」）");
                    // 一行一张凭证的普通视图
                    assertThatThrownBy(
                                    () ->
                                            f.app(
                                                    f.resource(
                                                            "plain", "VIEW", lineView(null, null)),
                                                    f.resource(
                                                            "report",
                                                            "REPORT",
                                                            withView(
                                                                    f.creditPivot(),
                                                                    "plain",
                                                                    null))))
                            .hasMessage(message);
                    // 另一个明细（附注）上的明细粒度视图
                    assertThatThrownBy(
                                    () ->
                                            f.app(
                                                    f.resource("notesview", "VIEW", notesView()),
                                                    f.resource(
                                                            "report",
                                                            "REPORT",
                                                            withView(
                                                                    f.creditPivot(),
                                                                    "notesview",
                                                                    Boolean.TRUE))))
                            .hasMessage(message);
                    // 同一明细：通过，允许编辑原样保存。
                    f.app(
                            f.resource("lineview", "VIEW", lineView(f.lines.id(), null)),
                            f.resource(
                                    "report",
                                    "REPORT",
                                    withView(f.creditPivot(), "lineview", Boolean.TRUE)));
                    var saved =
                            f.apps.get(f.app).draft().resources().stream()
                                    .filter(r -> r.id().equals("report"))
                                    .findFirst()
                                    .orElseThrow()
                                    .config();
                    assertThat(saved.get("detailViewId")).isEqualTo("lineview");
                    assertThat(saved.get("detailEditable")).isEqualTo(Boolean.TRUE);
                    assertThat(saved.get("grain")).isEqualTo("DETAIL");
                    // 主记录粒度照旧可挂任意同对象视图（普通视图、明细粒度视图都行）。
                    f.app(
                            f.resource("plain", "VIEW", lineView(null, null)),
                            f.resource(
                                    "rootreport",
                                    "REPORT",
                                    withView(rootByMonth(), "plain", null)));
                    f.app(
                            f.resource("lineview", "VIEW", lineView(f.lines.id(), null)),
                            f.resource(
                                    "rootreport",
                                    "REPORT",
                                    withView(rootByMonth(), "lineview", Boolean.TRUE)));
                });
    }

    /** 运行期对早先发布的配置按保存时的同一句话再拦一次：同一个判定函数。 */
    @Test
    void runtimeRecheckUsesTheSameRule() {
        rollback(
                () -> {
                    f.objects();
                    var validator =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.application.service.resource
                                            .ApplicationReportValidator.class);
                    var message = ReportGrainMessages.detailDrillView(f.lines.name());
                    assertThatThrownBy(
                                    () -> validator.requireDrillView(f.lines, lineView(null, null)))
                            .hasMessage(message);
                    assertThatThrownBy(() -> validator.requireDrillView(f.lines, notesView()))
                            .hasMessage(message);
                    assertThatCode(
                                    () ->
                                            validator.requireDrillView(
                                                    f.lines, lineView(f.lines.id(), null)))
                            .doesNotThrowAnyException();
                    // 主记录粒度（detail 为空）与未选视图都不判。
                    assertThatCode(() -> validator.requireDrillView(null, lineView(null, null)))
                            .doesNotThrowAnyException();
                    assertThatCode(() -> validator.requireDrillView(f.lines, null))
                            .doesNotThrowAnyException();
                });
    }

    // ---------------------------------------------------------------- 5. 存量与主记录粒度不变

    /** 没有下钻视图的明细粒度统计：照旧不能进数据视图（含明细粒度视图），只读下钻照旧。 */
    @Test
    void legacyDetailReportWithoutViewIsUnchanged() {
        rollback(
                () -> {
                    f.objects();
                    f.app(
                                    f.resource("lineview", "VIEW", lineView(f.lines.id(), null)),
                                    f.resource("report", "REPORT", f.creditPivot()))
                            .seed();
                    var cell = drill("report", keys(SALES), keys(AUGUST));
                    assertThatThrownBy(() -> drillPage("lineview", cell, OWNER))
                            .hasMessage(ReportGrainMessages.VIEW_DRILL);
                    assertThatThrownBy(
                                    () ->
                                            f.reports.drillScope(
                                                    cell,
                                                    f.app,
                                                    f.voucher.objectId(),
                                                    f.lines.id(),
                                                    OWNER))
                            .hasMessage(ReportGrainMessages.VIEW_DRILL);
                    assertThat(detailIds("report", keys(SALES), keys(AUGUST)))
                            .containsExactly(f.rowId("V3", "L4"));
                });
    }

    /** 挂了下钻视图的明细粒度统计，不能拿到另一个明细或一行一张凭证的视图里下钻。 */
    @Test
    void detailReportDrillRejectsViewsOfOtherShapes() {
        rollback(
                () -> {
                    ledger(() -> null);
                    var cell = drill("report", keys(SALES), keys(AUGUST));
                    for (String viewDetail : Arrays.asList(null, f.notes.id()))
                        assertThatThrownBy(
                                        () ->
                                                f.reports.drillScope(
                                                        cell,
                                                        f.app,
                                                        f.voucher.objectId(),
                                                        viewDetail,
                                                        OWNER))
                                .hasMessage(ReportGrainMessages.VIEW_DRILL);
                    assertThat(
                                    f.reports
                                            .drillScope(
                                                    cell,
                                                    f.app,
                                                    f.voucher.objectId(),
                                                    f.lines.id(),
                                                    OWNER)
                                            .detailKeys())
                            .containsExactly(f.lineIds.get("L4"));
                    assertThat(
                                    f.reports
                                            .drillScope(
                                                    drill("report", keys(), keys(AUGUST)),
                                                    f.app,
                                                    f.voucher.objectId(),
                                                    f.lines.id(),
                                                    OWNER)
                                            .keys())
                            .as("主记录主键去重：8 月 3 条分录来自 V3、V4 两张")
                            .containsExactlyInAnyOrder(f.vouchers.get("V3"), f.vouchers.get("V4"));
                });
    }

    /** 主记录粒度的统计下钻进明细粒度视图：照旧显示命中凭证的全部分录（7 月 = V1、V2 ⇒ L1 L2 L3），没有明细行范围。 */
    @Test
    void rootReportIntoDetailViewIsUnchanged() {
        rollback(
                () -> {
                    f.objects();
                    f.app(
                                    f.resource("lineview", "VIEW", lineView(f.lines.id(), null)),
                                    f.resource(
                                            "root",
                                            "REPORT",
                                            withView(rootByMonth(), "lineview", null)))
                            .seed();
                    var page = drillPage("lineview", drill("root", keys(JULY), null), OWNER);
                    assertThat(ids(page))
                            .containsExactlyInAnyOrder(
                                    f.rowId("V1", "L1"), f.rowId("V1", "L2"), f.rowId("V2", "L3"));
                    assertThat(page.drillTotal()).isEqualTo(2);
                    var scope =
                            f.reports.drillScope(
                                    drill("root", keys(JULY), null),
                                    f.app,
                                    f.voucher.objectId(),
                                    f.lines.id(),
                                    OWNER);
                    assertThat(scope.detailKeys()).isNull();
                    assertThat(scope.keys())
                            .containsExactlyInAnyOrder(f.vouchers.get("V1"), f.vouchers.get("V2"));
                });
    }
}
