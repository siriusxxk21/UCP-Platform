package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;
import static com.richuang.os.nocode.tools.ReportMultiSourceFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.resource.ReportGrainMessages;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * R6 衔接：多个数据来源里「按明细行统计」的附加来源也能挂下钻明细视图并按「允许编辑」开关编辑（laneDV 放开顶层，附加来源同样放开）。
 *
 * <p>夹具是契约 14.4 例 3（试算表）：来源 1「借方」、来源 2「贷方」都按明细「分录」统计。给来源 2 挂「分录逐行」数据视图（一行 = 一条分录）：
 *
 * <pre>
 * 分录  凭证 日期        借方科目 借方金额  贷方科目 贷方金额
 * L1    V1   2026-08-01  A1       1000      A2       1000
 * L2    V1   2026-08-01  A3        500      A2        500
 * L3    V2   2026-08-15  A2        300      A1        300
 * L4    V3   2026-09-02  A1        200      A3        200
 * L5    V3   2026-09-02  A1         50      （空）    （空）
 * </pre>
 */
class ReportMultiSourceDetailDrillViewTest {
    private static final String AUG = "2026-08", SEP = "2026-09";
    private static final List<String> EX3 = List.of("dr", "cr", "bal");

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

    // ---------------------------------------------------------------- 配置

    /** 会计凭证上「一行 = 一条分录」的数据视图；detail 为 false 时是一行一张凭证的普通视图。conditions 为分录段的固定条件。 */
    private ApplicationUi.View lineView(boolean detail, DynamicConditionDTO conditions) {
        DataViews.Section section =
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
        DataViews.Composition composition =
                detail
                        ? new DataViews.Composition(
                                "DETAIL",
                                f.lines.id(),
                                List.of(section),
                                List.of(
                                        new DataViews.Column(
                                                "view_credit",
                                                "贷方金额",
                                                "lines",
                                                f.creditAmount,
                                                "DETAIL",
                                                null)))
                        : null;
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

    /** 例 3，来源 2「贷方」挂下钻视图 viewId、允许编辑 editable；来源 1 不挂。 */
    private ApplicationReports.Config creditWithView(String viewId, Boolean editable) {
        ApplicationReports.Config c = f.example3();
        return with(
                c,
                List.of(),
                null,
                List.of(source(c.extraSources().getFirst(), null, null, viewId, editable)));
    }

    /** sectionConditions 在建好对象之后才取（字段 ID 由夹具建对象时生成）。 */
    private void ledger(java.util.function.Supplier<DynamicConditionDTO> sectionConditions) {
        f.objects();
        f.app(
                        f.resource("lineview", "VIEW", lineView(true, sectionConditions.get())),
                        f.resource("report", "REPORT", creditWithView("lineview", Boolean.TRUE)))
                .seed();
    }

    private ApplicationReports.Drill cellDrill(
            List<String> rows, List<String> columns, String metric) {
        return new ApplicationReports.Drill(
                f.app, "report", rows, columns, metric, null, null, null, null, null);
    }

    private ApplicationRecords.DrillPage drillPage(ApplicationReports.Drill drill, long actor) {
        return f.records.drillPage(
                new ApplicationRecords.Query(
                        f.app,
                        f.voucher.objectId(),
                        1,
                        20,
                        null,
                        Map.of(),
                        null,
                        false,
                        "lineview",
                        null,
                        null,
                        List.of(),
                        drill),
                actor);
    }

    private static List<String> ids(ApplicationRecords.DrillPage page) {
        return page.list().stream().map(ApplicationRecords.Row::id).toList();
    }

    private List<String> detailIds(List<String> rows, List<String> columns, String metric) {
        return f
                .reports
                .details(f.drill("report", rows, columns, metric, null), OWNER)
                .getList()
                .stream()
                .map(ApplicationRecords.Row::id)
                .toList();
    }

    private String line(String voucher, String line) {
        return f.key(voucher) + ":" + f.key(line);
    }

    private static void check(
            SoftAssertions soft,
            ApplicationReports.Result r,
            List<String> rows,
            List<String> columns,
            Integer... expected) {
        if (!has(r, rows, columns)) {
            soft.fail("缺少透视格 " + rows + " × " + columns);
            return;
        }
        softCell(soft, cell(r, rows, columns).values(), rows + " × " + columns, EX3, expected);
    }

    // ---------------------------------------------------------------- 1. 保存：明细粒度的附加来源能挂视图，视图形状与来源
    // 1 同一判定

    @Test
    void detailGrainExtraSourceAcceptsOnlyALineViewOfItsDetail() {
        rollback(
                () -> {
                    f.objects();
                    String message =
                            "来源「贷方」：" + ReportGrainMessages.detailDrillView(f.lines.name());
                    // 一行一张凭证的普通视图：来源 2 按明细行统计 ⇒ 拒绝（来源 1 的同一句话加来源前缀）。
                    assertThatThrownBy(
                                    () ->
                                            f.app(
                                                    f.resource(
                                                            "plain", "VIEW", lineView(false, null)),
                                                    f.resource(
                                                            "report",
                                                            "REPORT",
                                                            creditWithView("plain", null))))
                            .hasMessage(message);
                    // 按同一明细逐行显示的视图：通过，允许编辑原样保存。
                    f.app(
                            f.resource("lineview", "VIEW", lineView(true, null)),
                            f.resource(
                                    "report", "REPORT", creditWithView("lineview", Boolean.TRUE)));
                    Map<String, Object> saved =
                            f.apps.get(f.app).draft().resources().stream()
                                    .filter(r -> r.id().equals("report"))
                                    .findFirst()
                                    .orElseThrow()
                                    .config();
                    @SuppressWarnings("unchecked")
                    Map<String, Object> credit =
                            ((List<Map<String, Object>>) saved.get("extraSources")).getFirst();
                    assertThat(credit)
                            .containsEntry("grain", "DETAIL")
                            .containsEntry("detailViewId", "lineview")
                            .containsEntry("detailEditable", Boolean.TRUE);
                    assertThat(saved.get("detailViewId")).isNull();
                });
    }

    // ---------------------------------------------------------------- 2. 下钻：点来源 2
    // 的指标进它的视图，只有命中的明细行

    @Test
    void creditCellDrillsIntoTheSourcesLineViewWithHitLinesOnly() {
        rollback(
                () -> {
                    ledger(() -> null);
                    String a1 = f.key("A1");
                    // A1·08 的贷方合计只有 L3（同一张 V2 上没有别的分录；V1 有两条，都不是 A1 的贷方）。
                    ApplicationRecords.DrillPage page =
                            drillPage(cellDrill(keys(a1), keys(AUG), "cr"), OWNER);
                    assertThat(ids(page)).containsExactly(line("V2", "L3"));
                    assertThat(page.drillTotal()).isEqualTo(1);
                    assertThat(page.list().getFirst().values().get("view_credit"))
                            .asString()
                            .startsWith("300");
                    assertThat(detailIds(keys(a1), keys(AUG), "cr"))
                            .containsExactlyElementsOf(ids(page));
                    // 合计格：贷方金额非空的 4 条分录（点指标下钻照现有规则排除指标列为空的行：L5 没有贷方金额）。
                    ApplicationRecords.DrillPage grand =
                            drillPage(cellDrill(keys(), keys(), "cr"), OWNER);
                    assertThat(ids(grand))
                            .containsExactlyInAnyOrder(
                                    line("V1", "L1"),
                                    line("V1", "L2"),
                                    line("V2", "L3"),
                                    line("V3", "L4"));
                    assertThat(grand.drillTotal()).isEqualTo(4);
                    assertThat(detailIds(keys(), keys(), "cr"))
                            .containsExactlyInAnyOrderElementsOf(ids(grand));
                    // 来源 1（借方）没挂视图：照旧只能看只读明细，进数据视图下钻被拒（DV 的存量规则，按所点指标的来源判）。
                    assertThatThrownBy(() -> drillPage(cellDrill(keys(a1), keys(AUG), "dr"), OWNER))
                            .hasMessage(ReportGrainMessages.VIEW_DRILL);
                    assertThat(detailIds(keys(a1), keys(AUG), "dr"))
                            .containsExactly(line("V1", "L1"));
                    // 计算指标照旧要求分别查看。
                    assertThatThrownBy(
                                    () -> drillPage(cellDrill(keys(a1), keys(AUG), "bal"), OWNER))
                            .hasMessage("计算指标请分别查看其引用指标的明细");
                });
    }

    // ---------------------------------------------------------------- 3. 视图分录段的固定条件只收窄来源 2
    // 的统计（合并语句里同样生效）

    @Test
    void viewSectionConditionsNarrowOnlyThatSourceInTheMergedTable() {
        rollback(
                () -> {
                    ledger(() -> condition(f.creditAmount, "gte", "300"));
                    ApplicationReports.Result r = f.reports.query(f.query("report"), OWNER);
                    String a1 = f.key("A1"), a2 = f.key("A2"), a3 = f.key("A3");
                    SoftAssertions.assertSoftly(
                            soft -> {
                                // 贷方只剩 L1 L2 L3（L4 200 与空的 L5 被条件排除）；借方不受影响。
                                check(soft, r, keys(a1), keys(AUG), 1000, 300, 700);
                                check(soft, r, keys(a1), keys(SEP), 250, null, 250);
                                check(soft, r, keys(a2), keys(AUG), 300, 1500, -1200);
                                check(soft, r, keys(a3), keys(AUG), 500, null, 500);
                                check(soft, r, keys(a3), keys(), 500, null, 500);
                                check(soft, r, keys(), keys(AUG), 1800, 1800, 0);
                                check(soft, r, keys(), keys(SEP), 250, null, 250);
                                check(soft, r, keys(), keys(), 2050, 1800, 250);
                                soft.assertThat(has(r, keys(a3), keys(SEP)))
                                        .as("A3·09 只有 L4（被条件排除）⇒ 没有这一格")
                                        .isFalse();
                                soft.assertThat(has(r, keys((String) null), keys(SEP)))
                                        .as("未填写·09 只有 L5（被条件排除）⇒ 没有这一格")
                                        .isFalse();
                                soft.assertThat(r.sources())
                                        .extracting(
                                                ApplicationReports.SourceSummary::id,
                                                ApplicationReports.SourceSummary::recordCount)
                                        .containsExactly(tuple("main", 5L), tuple("credit", 3L));
                                soft.assertThat(r.recordCount()).isEqualTo(8);
                            });
                    // 统计、只读下钻、视图下钻三方同一范围。
                    ApplicationRecords.DrillPage page =
                            drillPage(cellDrill(keys(), keys(), "cr"), OWNER);
                    assertThat(ids(page))
                            .containsExactlyInAnyOrder(
                                    line("V1", "L1"), line("V1", "L2"), line("V2", "L3"));
                    assertThat(page.drillTotal()).isEqualTo(3);
                    assertThat(detailIds(keys(), keys(), "cr"))
                            .containsExactlyInAnyOrderElementsOf(ids(page));
                });
    }

    // ---------------------------------------------------------------- 4. 编辑：能写的成员改一条分录 ⇒ 来源 2
    // 的格子与跨来源的余额同步变；只读成员不能改

    private ApplicationAuthorization.ObjectGrant voucherWriter() {
        Set<String> main = fieldIds(f.voucher);
        Set<String> lines = Set.of(f.lines.id());
        return new ApplicationAuthorization.ObjectGrant(
                f.voucher.objectId(), Set.of("READ", "UPDATE"), "ALL", main, main, lines, lines);
    }

    /** 把 V2 的 L3 贷方金额改成 amount（整单保存）。 */
    private void editL3(long actor, String amount) {
        ApplicationRecords.Aggregate current =
                f.records.get(f.app, f.voucher.objectId(), f.key("V2"), actor);
        List<ApplicationRecords.Row> rows = new ArrayList<>();
        for (ApplicationRecords.Row row : current.details().get(f.lines.id())) {
            Map<String, Object> values = new LinkedHashMap<>(row.values());
            if (row.id().equals(f.key("L3"))) values.put(f.creditAmount, amount);
            rows.add(new ApplicationRecords.Row(row.id(), row.revision(), values));
        }
        f.records.save(
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
    void editingACreditLineUpdatesTheSourceAndTheCrossSourceBalance() {
        rollback(
                () -> {
                    ledger(() -> null);
                    ApplicationAuthorization.ObjectGrant accounts =
                            read(f.account, "ALL", Set.of());
                    f.members(
                            member(U1, voucherWriter(), accounts),
                            member(U2, read(f.voucher, "ALL", Set.of(f.lines.id())), accounts));
                    String a1 = f.key("A1");
                    ApplicationReports.Drill cell = cellDrill(keys(a1), keys(AUG), "cr");
                    ApplicationRecords.DrillPage writer = drillPage(cell, U1);
                    assertThat(ids(writer)).containsExactly(line("V2", "L3"));
                    assertThat(writer.list().getFirst().permissions().actions()).contains("UPDATE");
                    ApplicationRecords.DrillPage reader = drillPage(cell, U2);
                    assertThat(ids(reader)).containsExactly(line("V2", "L3"));
                    assertThat(reader.list().getFirst().permissions().actions())
                            .doesNotContain("UPDATE");
                    // 只读成员改不了：记录接口按授权拒绝，统计配置的「允许编辑」不放宽它。
                    assertThatThrownBy(() -> editL3(U2, "999"))
                            .isInstanceOf(
                                    com.richuang.os.framework.common.exception.ServiceException
                                            .class);
                    editL3(U1, "350");
                    ApplicationReports.Result after = f.reports.query(f.query("report"), U1);
                    SoftAssertions.assertSoftly(
                            soft -> {
                                check(soft, after, keys(a1), keys(AUG), 1000, 350, 650);
                                check(soft, after, keys(), keys(AUG), 1800, 1850, -50);
                                check(soft, after, keys(), keys(), 2050, 2050, 0);
                            });
                    ApplicationRecords.DrillPage page = drillPage(cell, U1);
                    assertThat(ids(page)).containsExactly(line("V2", "L3"));
                    assertThat(page.list().getFirst().values().get("view_credit"))
                            .asString()
                            .startsWith("350");
                });
    }
}
