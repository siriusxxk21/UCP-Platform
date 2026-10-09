package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.ReportMultiSourceFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.runtime.dal.query.ReportStatement;
import com.lingan.ucp.nocode.runtime.service.application.ApplicationRuntimeService;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 多个数据来源的权限（任务书 P1–P5、开工验证 V2）、用户筛选与页面公共筛选的按来源映射（T11、T12）、各来源下钻明细视图与数据视图下钻（契约变更 C2、C3）。 每例整体回滚。 */
class ReportMultiSourcePermissionTest {
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

    private static final List<String> EX1 = List.of("cur", "nxt", "exp", "inc", "pro");

    private boolean delivered(String report, long user) {
        return servicesContext
                .getBean(ApplicationRuntimeService.class)
                .application(f.app, user)
                .definition()
                .resources()
                .stream()
                .anyMatch(r -> r.id().equals(report));
    }

    /** P1：成员没有「支出」的查看权 ⇒ 例 1 对他整块不下发；取数报错以「来源「支出」：」开头；预览缺上限时同样带前缀。 */
    @Test
    void p1MissingSourceObjectHidesTheWholeReport() {
        rollback(
                () -> {
                    f.objects();
                    f.app(
                                    f.resource("report", "REPORT", f.example1()),
                                    f.resource("revenue", "REPORT", f.example2(false)))
                            .seed();
                    f.members(
                            member(
                                    U1,
                                    read(f.stay, "ALL", Set.of()),
                                    read(f.property, "ALL", Set.of())));
                    assertThat(f.reports.available(f.app, "report", U1)).isFalse();
                    assertThat(f.reports.available(f.app, "revenue", U1))
                            .as("只用入住记录的例 2 照常可见")
                            .isTrue();
                    assertThat(delivered("report", U1)).isFalse();
                    assertThat(delivered("revenue", U1)).isTrue();
                    System.out.println(
                            "P1 message: "
                                    + catchThrowable(() -> f.reports.query(f.query("report"), U1))
                                            .getMessage());
                    assertThatThrownBy(() -> f.reports.query(f.query("report"), U1))
                            .hasMessageStartingWith("来源「支出」：");
                    // 搭建时的预览：支出对象不再授予本应用 ⇒ 预览报错写明是哪个来源。
                    f.ceiling(f.expense, null);
                    assertThatThrownBy(() -> f.preview(f.example1()))
                            .hasMessageStartingWith("来源「支出」：");
                });
    }

    /** P2 + V2：成员对支出只有本人范围 ⇒ exp 只含本人（E2）；cur / nxt 不受影响；SQL 里本人范围只绑在支出那个分支上。 */
    @Test
    void p2OwnScopeAppliesOnlyToThatSource() {
        rollback(
                () -> {
                    f.objects();
                    f.app(f.resource("report", "REPORT", f.example1())).seed();
                    f.members(
                            member(
                                    U1,
                                    read(f.stay, "ALL", Set.of()),
                                    read(f.expense, "OWN", Set.of()),
                                    read(f.property, "ALL", Set.of())));
                    ApplicationReports.Result r = f.reports.query(f.query("report"), U1);
                    String p1 = f.key("P1");
                    SoftAssertions.assertSoftly(
                            soft -> {
                                softCell(
                                        soft,
                                        cell(r, keys(p1), keys("2026-07")).values(),
                                        "P1·07",
                                        EX1,
                                        20000,
                                        null,
                                        null,
                                        20000,
                                        20000);
                                softCell(
                                        soft,
                                        cell(r, keys(p1), keys("2026-08")).values(),
                                        "P1·08",
                                        EX1,
                                        18000,
                                        10000,
                                        7000,
                                        28000,
                                        21000);
                                softCell(
                                        soft,
                                        r.totals(),
                                        "总计",
                                        EX1,
                                        83000,
                                        26000,
                                        7000,
                                        109000,
                                        102000);
                                soft.assertThat(r.sources())
                                        .extracting(ApplicationReports.SourceSummary::recordCount)
                                        .containsExactly(5L, 5L, 1L);
                            });
                    ReportStatement s = ReportMultiSourceSqlTest.statement(f.app, "report", U1);
                    List<String> params =
                            ReportMultiSourceSqlTest.bindings(
                                    ReportMultiSourceSqlTest.bound("pivot", s), s);
                    params.forEach(p -> System.out.println("V2 PARAM " + p));
                    assertThat(params).contains("sources[2].statement.base.creator = " + U1);
                    assertThat(params)
                            .noneMatch(p -> p.startsWith("sources[0].statement.base.creator"));
                    assertThat(params)
                            .noneMatch(p -> p.startsWith("sources[1].statement.base.creator"));
                });
    }

    /** P3：看不到明细「分录」⇒ 例 3 不可用，报 laneT 的 M3 原文（来源 1 就失败，不加前缀）；只有附加来源是明细粒度时加前缀。 */
    @Test
    void p3DetailReadIsCheckedPerSource() {
        rollback(
                () -> {
                    f.objects();
                    ApplicationReports.Config mixed =
                            config(
                                    f.voucher.objectId(),
                                    null,
                                    null,
                                    "PIVOT",
                                    List.of(dim(f.memo, null, "VALUE")),
                                    List.of(dim(f.booked, null, "MONTH")),
                                    List.of(
                                            metric("n", "张数", "COUNT", null, null),
                                            metric("cr", "贷方合计", "SUM", f.creditAmount, "lines")),
                                    List.of(),
                                    null,
                                    null,
                                    false,
                                    null,
                                    null,
                                    "凭证",
                                    List.of(
                                            new ApplicationReports.Source(
                                                    "lines",
                                                    "分录",
                                                    f.voucher.objectId(),
                                                    "DETAIL",
                                                    f.lines.id(),
                                                    List.of(dim(f.memo, null, "VALUE")),
                                                    List.of(dim(f.booked, null, "MONTH")),
                                                    null,
                                                    null,
                                                    null)),
                                    null,
                                    null);
                    f.app(
                                    f.resource("trial", "REPORT", f.example3()),
                                    f.resource("mixed", "REPORT", mixed))
                            .seed();
                    f.members(
                            member(
                                    U2,
                                    read(f.voucher, "ALL", Set.of()),
                                    read(f.account, "ALL", Set.of())));
                    String m3 =
                            "没有明细「分录」的查看权限，不能按它的行做统计。请在应用的「成员与权限」里为该成员勾选可查看内部明细「分录」；"
                                    + "数据对象还没有把这个明细授权给本应用时，先到数据中心该对象的「应用共享授权」里勾选";
                    assertThat(f.reports.available(f.app, "trial", U2)).isFalse();
                    assertThatThrownBy(() -> f.reports.query(f.query("trial"), U2)).hasMessage(m3);
                    assertThatThrownBy(() -> f.reports.query(f.query("mixed"), U2))
                            .hasMessage("来源「分录」：" + m3);
                    assertThat(delivered("trial", U2)).isFalse();
                    assertThat(delivered("mixed", U2)).isFalse();
                    // 给了明细就都可用。
                    f.members(
                            member(
                                    U2,
                                    read(f.voucher, "ALL", Set.of(f.lines.id())),
                                    read(f.account, "ALL", Set.of())));
                    assertThat(f.reports.available(f.app, "trial", U2)).isTrue();
                });
    }

    /** P4：授权「全部」哨兵（字段 / 明细为 *）与管理员逐格相同。 */
    @Test
    void p4AllSentinelEqualsOwner() {
        rollback(
                () -> {
                    f.objects();
                    f.app(
                                    f.resource("report", "REPORT", f.example1()),
                                    f.resource("trial", "REPORT", f.example3()))
                            .seed();
                    List<ApplicationAuthorization.ObjectGrant> all = new ArrayList<>();
                    for (DataCenter.Definition d : f.referenced())
                        all.add(
                                new ApplicationAuthorization.ObjectGrant(
                                        d.objectId(),
                                        Set.of("READ"),
                                        "ALL",
                                        Set.of(Selections.ALL),
                                        Set.of(),
                                        Set.of(Selections.ALL),
                                        Set.of()));
                    f.members(member(U1, all.toArray(ApplicationAuthorization.ObjectGrant[]::new)));
                    for (String report : List.of("report", "trial")) {
                        ApplicationReports.Result owner = f.reports.query(f.query(report), OWNER);
                        ApplicationReports.Result member = f.reports.query(f.query(report), U1);
                        assertThat(member.pivot().cells())
                                .as(report)
                                .isEqualTo(owner.pivot().cells());
                        assertThat(member.pivot().rows())
                                .as(report)
                                .isEqualTo(owner.pivot().rows());
                        assertThat(member.sources()).as(report).isEqualTo(owner.sources());
                    }
                });
    }

    /** P5：来源对象只是隐式只读（应用没有显式引用）⇒ 保存被拒，原句加来源前缀。 */
    @Test
    void p5ImplicitReadOnlyObjectCannotBeASource() {
        rollback(
                () -> {
                    f.objects();
                    ApplicationReports.Config c =
                            config(
                                    f.stay.objectId(),
                                    null,
                                    null,
                                    "TABLE",
                                    List.of(dim(f.stayName, null, "VALUE")),
                                    null,
                                    List.of(
                                            metric("n", "入住", "COUNT", null, null),
                                            metric("p", "物件数", "COUNT", null, "props")),
                                    List.of(),
                                    null,
                                    null,
                                    false,
                                    null,
                                    null,
                                    "入住",
                                    List.of(
                                            new ApplicationReports.Source(
                                                    "props",
                                                    "物件",
                                                    f.property.objectId(),
                                                    null,
                                                    null,
                                                    List.of(dim(f.propertyName, null, "VALUE")),
                                                    null,
                                                    null,
                                                    null,
                                                    null)),
                                    null,
                                    null);
                    assertThatThrownBy(
                                    () ->
                                            f.app(
                                                    List.of(
                                                            f.stay, f.expense, f.voucher,
                                                            f.account),
                                                    f.resource("report", "REPORT", c)))
                            .hasMessage("来源「物件」：统计对象未被应用引用");
                });
    }

    /** T11：用户筛选与日期范围按来源映射：按维度推出、同对象同键、显式「筛选对应」三条路都生效。 */
    @Test
    void t11UserFiltersAreMappedPerSource() {
        rollback(
                () -> {
                    f.objects();
                    ApplicationReports.Config ex2 = f.example2(false);
                    ApplicationReports.Config dated =
                            with(
                                    ex2,
                                    List.of(),
                                    f.checkIn,
                                    List.of(
                                            source(
                                                    ex2.extraSources().getFirst(),
                                                    f.checkOut,
                                                    null,
                                                    null,
                                                    null)));
                    ApplicationReports.Config ex1 = f.example1();
                    ApplicationReports.Config byProperty =
                            with(ex1, List.of(f.stayProperty), null, ex1.extraSources());
                    ApplicationReports.Config byChannel =
                            with(
                                    ex1,
                                    List.of(f.stayChannel),
                                    null,
                                    List.of(
                                            ex1.extraSources().get(0),
                                            source(
                                                    ex1.extraSources().get(1),
                                                    null,
                                                    Map.of(f.stayChannel, f.expenseChannel),
                                                    null,
                                                    null)));
                    f.app(
                                    f.resource("dated", "REPORT", dated),
                                    f.resource("by_property", "REPORT", byProperty),
                                    f.resource("by_channel", "REPORT", byChannel))
                            .seed();
                    // 日期范围 9 月：来源 1 按入住日 ⇒ 只剩 S5；来源 2 按退房日 ⇒ S3、S4、S5。
                    ApplicationReports.Result september =
                            f.reports.query(
                                    new ApplicationReports.Query(
                                            f.app,
                                            "dated",
                                            null,
                                            "2026-09-01",
                                            "2026-09-30",
                                            null,
                                            null,
                                            1,
                                            100),
                                    OWNER);
                    assertThat(september.sources())
                            .extracting(ApplicationReports.SourceSummary::recordCount)
                            .containsExactly(1L, 3L);
                    SoftAssertions.assertSoftly(
                            soft ->
                                    softCell(
                                            soft,
                                            september.totals(),
                                            "9 月",
                                            List.of("cur", "nxt", "rev"),
                                            22000,
                                            16000,
                                            38000));
                    // 物件 = P1（维度键）：支出按自己的「物件」筛 ⇒ 只剩 E1、E2。
                    ApplicationReports.Result p1 =
                            f.reports.query(
                                    new ApplicationReports.Query(
                                            f.app,
                                            "by_property",
                                            Map.of(f.stayProperty, f.key("P1")),
                                            null,
                                            null,
                                            null,
                                            null,
                                            1,
                                            100),
                                    OWNER);
                    assertThat(p1.sources())
                            .extracting(ApplicationReports.SourceSummary::recordCount)
                            .containsExactly(2L, 2L, 2L);
                    SoftAssertions.assertSoftly(
                            soft ->
                                    softCell(
                                            soft,
                                            p1.totals(),
                                            "P1",
                                            EX1,
                                            38000,
                                            10000,
                                            12000,
                                            48000,
                                            36000));
                    // 同一筛选用条件树（in）表达也一样映射。
                    ApplicationReports.Result p1Tree =
                            f.reports.query(
                                    new ApplicationReports.Query(
                                            f.app,
                                            "by_property",
                                            null,
                                            null,
                                            null,
                                            null,
                                            null,
                                            1,
                                            100,
                                            condition(f.stayProperty, "in", List.of(f.key("P1"))),
                                            null),
                                    OWNER);
                    assertThat(p1Tree.totals()).isEqualTo(p1.totals());
                    // 渠道 = 网站：次月同对象同键；支出用显式「筛选对应」到自己的渠道 ⇒ E1、E3。
                    ApplicationReports.Result web =
                            f.reports.query(
                                    new ApplicationReports.Query(
                                            f.app,
                                            "by_channel",
                                            Map.of(f.stayChannel, "web"),
                                            null,
                                            null,
                                            null,
                                            null,
                                            1,
                                            100),
                                    OWNER);
                    assertThat(web.sources())
                            .extracting(ApplicationReports.SourceSummary::recordCount)
                            .containsExactly(3L, 3L, 2L);
                    SoftAssertions.assertSoftly(
                            soft ->
                                    softCell(
                                            soft,
                                            web.totals(),
                                            "网站",
                                            EX1,
                                            43000,
                                            26000,
                                            8000,
                                            69000,
                                            61000));
                });
    }

    /** T12：页面公共筛选绑定多来源统计：保存通过；按物件筛时三个来源都按物件筛（与用户筛选同一条路）。 */
    @Test
    void t12PageFilterOnMultiSourceReport() {
        rollback(
                () -> {
                    f.objects();
                    ApplicationReports.Config ex1 = f.example1();
                    ApplicationReports.Config byProperty =
                            with(ex1, List.of(f.stayProperty), null, ex1.extraSources());
                    ApplicationUi.Page page =
                            new ApplicationUi.Page(
                                    List.of(
                                            mapper.convertValue(
                                                    Map.of(
                                                            "id",
                                                            "n1",
                                                            "type",
                                                            "REPORT",
                                                            "resourceId",
                                                            "report"),
                                                    ApplicationUi.Node.class)),
                                    null,
                                    null,
                                    List.of(
                                            new ApplicationReports.Filter(
                                                    "property",
                                                    "物件",
                                                    f.stay.objectId(),
                                                    f.stayProperty,
                                                    false,
                                                    Map.of("report", f.stayProperty))));
                    f.app(
                                    f.resource("report", "REPORT", byProperty),
                                    f.resource("dashboard", "PAGE", page))
                            .seed();
                    ApplicationReports.Result r =
                            f.reports.query(
                                    new ApplicationReports.Query(
                                            f.app,
                                            "report",
                                            Map.of(f.stayProperty, f.key("P2")),
                                            null,
                                            null,
                                            null,
                                            null,
                                            1,
                                            100),
                                    OWNER);
                    assertThat(r.sources())
                            .extracting(ApplicationReports.SourceSummary::recordCount)
                            .containsExactly(2L, 2L, 1L);
                    SoftAssertions.assertSoftly(
                            soft ->
                                    softCell(
                                            soft,
                                            r.totals(),
                                            "P2",
                                            EX1,
                                            37000,
                                            0,
                                            3000,
                                            37000,
                                            34000));
                });
    }

    /** 契约变更 C2 / C3：附加来源挂自己的下钻明细视图；数据视图下钻按指标所属来源取记录集，视图对象须是该来源的对象。 */
    @Test
    void dataViewDrillGoesToTheMetricsSource() {
        rollback(
                () -> {
                    f.objects();
                    ApplicationReports.Config ex1 = f.example1();
                    ApplicationReports.Config withViews =
                            with(
                                    ex1,
                                    List.of(),
                                    null,
                                    List.of(
                                            ex1.extraSources().get(0),
                                            source(
                                                    ex1.extraSources().get(1),
                                                    null,
                                                    null,
                                                    "expense_view",
                                                    true)));
                    f.app(
                                    f.resource("report", "REPORT", withViews),
                                    f.resource(
                                            "expense_view",
                                            "VIEW",
                                            ReportMultiSourceValidationTest.view(f.expense)))
                            .seed();
                    ApplicationReports.Config saved =
                            mapper.convertValue(
                                    f.apps.get(f.app).draft().resources().stream()
                                            .filter(x -> x.id().equals("report"))
                                            .findFirst()
                                            .orElseThrow()
                                            .config(),
                                    ApplicationReports.Config.class);
                    assertThat(saved.extraSources().get(1).detailViewId())
                            .isEqualTo("expense_view");
                    assertThat(saved.extraSources().get(1).detailEditable()).isTrue();
                    String p1 = f.key("P1");
                    ApplicationReports.Drill exp =
                            new ApplicationReports.Drill(
                                    f.app,
                                    "report",
                                    keys(p1),
                                    keys("2026-08"),
                                    "exp",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null);
                    var scope = f.reports.drillScope(exp, f.app, f.expense.objectId(), OWNER);
                    assertThat(scope.keys()).containsExactly(f.key("E2"));
                    assertThat(scope.total()).isEqualTo(1);
                    assertThatThrownBy(
                                    () ->
                                            f.reports.drillScope(
                                                    exp, f.app, f.stay.objectId(), OWNER))
                            .hasMessage("统计下钻与数据视图的对象不一致");
                    ApplicationReports.Drill cur =
                            new ApplicationReports.Drill(
                                    f.app,
                                    "report",
                                    keys(p1),
                                    keys("2026-08"),
                                    "cur",
                                    null,
                                    null,
                                    null,
                                    null,
                                    null);
                    assertThat(f.reports.drillScope(cur, f.app, f.stay.objectId(), OWNER).keys())
                            .containsExactly(f.key("S2"));
                    ApplicationReports.Drill none =
                            new ApplicationReports.Drill(
                                    f.app,
                                    "report",
                                    keys(p1),
                                    keys("2026-08"),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null);
                    assertThatThrownBy(
                                    () ->
                                            f.reports.drillScope(
                                                    none, f.app, f.stay.objectId(), OWNER))
                            .hasMessage("多个来源的统计请点具体指标查看明细");
                    // 运行端下发带上该来源的视图与允许编辑开关。
                    var resource =
                            servicesContext
                                    .getBean(ApplicationRuntimeService.class)
                                    .application(f.app, OWNER)
                                    .definition()
                                    .resources()
                                    .stream()
                                    .filter(x -> x.id().equals("report"))
                                    .findFirst()
                                    .orElseThrow();
                    ApplicationReports.Config runtime =
                            mapper.convertValue(resource.config(), ApplicationReports.Config.class);
                    assertThat(runtime.extraSources().get(1).detailViewId())
                            .isEqualTo("expense_view");
                    assertThat(runtime.extraSources().get(1).detailEditable()).isTrue();
                });
    }
}
