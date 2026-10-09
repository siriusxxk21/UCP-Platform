package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.ReportDetailGrainFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.application.ApplicationRuntimeService;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

/**
 * 按明细行统计：权限。先裁剪后聚合——看不到的数据不能通过分组、筛选、指标推算出来。
 *
 * <p>明细没有字段级授权：能不能用分录里的字段只看「可查看内部明细」含不含分录，并且只从主对象的访问权判； 主表字段与关系目标对象的字段看各自的可查看字段。应用创建人 10001
 * 按对象授予应用的上限取数；成员 U1 / U2 按成员授权取数。
 */
class ReportDetailGrainPermissionTest {
    private static final String M3 =
            "没有明细「分录」的查看权限，不能按它的行做统计。请在应用的「成员与权限」里为该成员勾选可查看内部明细「分录」；"
                    + "数据对象还没有把这个明细授权给本应用时，先到数据中心该对象的「应用共享授权」里勾选";
    private static final String M4_DATE =
            "统计涉及未授权字段或操作，不能通过分组、筛选或指标推算隐藏数据：没有「凭证」的字段「日期」的查看权限。"
                    + "请在应用的「成员与权限」里为该成员勾选这个可查看字段；数据对象还没有把它授权给本应用时，先到数据中心该对象的「应用共享授权」里勾选";
    private static final String M4_ACCOUNT_NAME =
            "统计涉及未授权字段或操作，不能通过分组、筛选或指标推算隐藏数据：没有「科目」的字段「科目名称」的查看权限。"
                    + "请在应用的「成员与权限」里为该成员勾选这个可查看字段；数据对象还没有把它授权给本应用时，先到数据中心该对象的「应用共享授权」里勾选";
    private static final Set<String> READ = Set.of("READ");
    private static final Set<String> READ_EXPORT = Set.of("READ", "EXPORT");

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

    private void ledger() {
        f.objects();
        f.app(f.resource("report", "REPORT", f.creditPivot())).seed();
    }

    private Set<String> lines() {
        return Set.of(f.lines.id());
    }

    private ApplicationAuthorization.ObjectGrant accountAll() {
        return f.accountGrant(READ_EXPORT, "ALL", ids(f.account.fields()));
    }

    private static BigDecimal total(ApplicationReports.Result r, String metric) {
        return number(cell(r, keys(), keys()).values(), metric);
    }

    /** 成员的可查看内部明细不含「分录」：查询报明细那一句，统计对他不可用，运行端不下发这张统计。 */
    @Test
    void memberWithoutDetailReadGetsDetailMessageAndReportIsUnavailable() {
        rollback(
                () -> {
                    ledger();
                    f.members(
                            member(
                                    U2,
                                    f.voucherGrant(
                                            READ_EXPORT,
                                            "ALL",
                                            f.mainFields(),
                                            Set.of(f.notes.id())),
                                    accountAll()));
                    assertThatThrownBy(() -> f.reports.query(f.query("report"), U2))
                            .hasMessage(M3)
                            .hasMessageNotContaining("未授权");
                    assertThatThrownBy(() -> f.reports.details(f.query("report"), U2))
                            .hasMessage(M3);
                    assertThat(f.reports.available(f.app, "report", U2)).isFalse();
                    assertThat(
                                    servicesContext
                                            .getBean(ApplicationRuntimeService.class)
                                            .application(f.app, U2)
                                            .definition()
                                            .resources())
                            .noneMatch(r -> r.id().equals("report"));
                    // 同一张统计对看得到分录的人照常可用。
                    assertThat(f.reports.available(f.app, "report", OWNER)).isTrue();
                    assertThat(total(f.reports.query(f.query("report"), OWNER), "amount"))
                            .isEqualByComparingTo("2050");
                });
    }

    /** 统计一个明细字段都没用（行 = 主表的公司，指标 = 明细行数、主记录数），仍然是「按分录的行」在数：看不到分录的成员同样不能用， 否则能从行数推算出每张凭证有几条分录。 */
    @Test
    void detailGrainWithoutAnyDetailFieldStillRequiresDetailRead() {
        rollback(
                () -> {
                    f.objects();
                    var counting =
                            f.detailConfig(
                                    "TABLE",
                                    List.of(f.main(f.company, "VALUE")),
                                    List.of(),
                                    List.of(
                                            new ApplicationReports.Metric(
                                                    "lines", "明细行数", "COUNT", null),
                                            new ApplicationReports.Metric(
                                                    "vouchers", "主记录数", "COUNT_ROOT", null)));
                    f.app(f.resource("counting", "REPORT", counting)).seed();
                    f.members(
                            member(
                                    U2,
                                    f.voucherGrant(
                                            READ_EXPORT,
                                            "ALL",
                                            f.mainFields(),
                                            Set.of(f.notes.id())),
                                    accountAll()),
                            member(
                                    U1,
                                    f.voucherGrant(READ_EXPORT, "ALL", f.mainFields(), lines()),
                                    accountAll()));
                    assertThatThrownBy(() -> f.reports.query(f.query("counting"), U2))
                            .hasMessage(M3);
                    assertThat(f.reports.available(f.app, "counting", U2)).isFalse();
                    var r = f.reports.query(f.query("counting"), U1);
                    assertThat(number(r.totals(), "lines")).isEqualByComparingTo("6");
                    assertThat(number(r.totals(), "vouchers")).isEqualByComparingTo("4");
                });
    }

    /** 两条带记录条件的查看授权（一条给成员本人、一条给他所在的角色）：可查看明细取交集，其中一条不含「分录」就不能按分录统计。 */
    @Test
    void scopedGrantsIntersectDetailRead() {
        rollback(
                () -> {
                    ledger();
                    var role = new com.lingan.ucp.module.system.api.permission.dto.RoleRespDTO();
                    role.setId(30011L);
                    role.setCode("ledger_reader");
                    role.setStatus(0);
                    Mockito.when(
                                    servicesContext
                                            .getBean(
                                                    com.lingan.ucp.module.system.api.permission
                                                            .RoleApi.class)
                                            .getRole(30011L))
                            .thenReturn(role);
                    Mockito.when(
                                    servicesContext
                                            .getBean(
                                                    com.lingan.ucp.framework.common.biz.system
                                                            .permission.PermissionCommonApi.class)
                                            .hasAnyRoles(U1, "ledger_reader"))
                            .thenReturn(true);
                    var onlyA =
                            new DataScope(
                                    "AND",
                                    List.of(new DataScope.Condition(f.company, "eq", COMPANY_A)),
                                    List.of());
                    var onlyB =
                            new DataScope(
                                    "AND",
                                    List.of(new DataScope.Condition(f.company, "eq", COMPANY_B)),
                                    List.of());
                    f.members(
                            member(U1, scoped(onlyA, lines()), accountAll()),
                            role(scoped(onlyB, Set.of())));
                    assertThatThrownBy(() -> f.reports.query(f.query("report"), U1)).hasMessage(M3);
                    assertThat(f.reports.available(f.app, "report", U1)).isFalse();
                    // 两条都含「分录」：可用，范围是两条记录条件的并集（甲公司 + 乙公司 = 全部）。
                    f.members(
                            member(U1, scoped(onlyA, lines()), accountAll()),
                            role(scoped(onlyB, lines())));
                    assertCell(f.reports.query(f.query("report"), U1), keys(), keys(), 2050, 6, 4);
                    // 只有一条（甲公司）：只统计甲公司凭证的分录 —— V1 的 L1、L2 与 V2 的 L3。
                    f.members(member(U1, scoped(onlyA, lines()), accountAll()));
                    assertCell(f.reports.query(f.query("report"), U1), keys(), keys(), 1300, 3, 2);
                });
    }

    private static ApplicationAuthorization.Member role(
            ApplicationAuthorization.ObjectGrant... grants) {
        return new ApplicationAuthorization.Member("ROLE", "30011", List.of(grants));
    }

    private ApplicationAuthorization.ObjectGrant scoped(DataScope read, Set<String> details) {
        return new ApplicationAuthorization.ObjectGrant(
                f.voucher.objectId(),
                READ,
                "ALL",
                f.mainFields(),
                Set.of(),
                details,
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of("READ", read),
                Set.of());
    }

    /**
     * 授权勾了「全部」的成员能按分录统计：目标表全部成立。
     *
     * <p>「全部」的授权对着「传给 policy.access 的那个对象定义」展开。明细投影出来的定义里没有明细清单，把它传进去，「全部」会展开成空的可查看明细，
     * 所有人被判成无权。合并态（local/release-r3）里「全部」已是真的写法，所以这里用两件事钉住： ① 成员授权真的写成哨兵 {@code "*"}（可查看字段与
     * 可查看明细都是「全部」）、存下来的也是哨兵时目标表成立；② 统计引擎传给 policy.access 的每一个定义都必须是应用固定的真实对象定义，不能是明细的投影。
     */
    @Test
    void memberWithAllSentinelCanUseDetailGrain() {
        rollback(
                () -> {
                    ledger();
                    Set<String> all = Set.of("*");
                    f.members(
                            member(
                                    U1,
                                    f.voucherGrant(READ_EXPORT, "ALL", all, all),
                                    f.accountGrant(READ_EXPORT, "ALL", all)));
                    var stored =
                            servicesContext
                                    .getBean(ApplicationAuthorizationService.class)
                                    .get(f.app)
                                    .members()
                                    .get(0)
                                    .objects();
                    assertThat(stored)
                            .allSatisfy(g -> assertThat(g.readFields()).containsExactly("*"));
                    assertThat(stored.get(0).readDetails()).containsExactly("*");
                    var original =
                            (ApplicationRuntimePolicy)
                                    ReflectionTestUtils.getField(f.reports, "policy");
                    var spy = Mockito.spy(original);
                    ReflectionTestUtils.setField(f.reports, "policy", spy);
                    try {
                        var r = f.reports.query(f.query("report"), U1);
                        assertCell(r, keys(), keys(), 2050, 6, 4);
                        assertCell(r, keys("销售收入"), keys("2026-07"), 1000, 2, 1);
                        assertCell(r, keys((String) null), keys(), 300, 1, 1);
                        assertThat(
                                        f.reports
                                                .details(f.drill("report", null, null, null), U1)
                                                .getTotal())
                                .isEqualTo(6);
                        assertThat(f.reports.export(f.query("report"), U1).recordCount())
                                .isEqualTo(6);
                        var passed = ArgumentCaptor.forClass(DataCenter.Definition.class);
                        Mockito.verify(spy, Mockito.atLeastOnce())
                                .access(Mockito.eq(f.app), passed.capture(), Mockito.eq(U1));
                        assertThat(passed.getAllValues())
                                .isNotEmpty()
                                .allSatisfy(
                                        d ->
                                                assertThat(d)
                                                        .as("传给 policy.access 的必须是应用固定的真实对象定义")
                                                        .isEqualTo(
                                                                d.objectId()
                                                                                .equals(
                                                                                        f.voucher
                                                                                                .objectId())
                                                                        ? f.voucher
                                                                        : f.account));
                        assertThat(passed.getAllValues()).contains(f.voucher, f.account);
                    } finally {
                        ReflectionTestUtils.setField(f.reports, "policy", original);
                    }
                });
    }

    /** 主表字段真没授权：以原句开头（含「未授权字段」），再说明是哪个对象的哪个字段、去哪里勾。 */
    @Test
    void missingMainFieldGrantNamesObjectFieldAndWhereToGrant() {
        rollback(
                () -> {
                    ledger();
                    var fields = new HashSet<>(f.mainFields());
                    fields.remove(f.booked);
                    f.members(
                            member(
                                    U1,
                                    f.voucherGrant(READ_EXPORT, "ALL", fields, lines()),
                                    accountAll()));
                    assertThatThrownBy(() -> f.reports.query(f.query("report"), U1))
                            .hasMessage(M4_DATE)
                            .hasMessageContaining("未授权字段");
                    assertThat(f.reports.available(f.app, "report", U1)).isFalse();
                });
    }

    /** 关系目标对象的字段没授权：对象名是目标对象。 */
    @Test
    void missingTargetFieldGrantNamesTargetObject() {
        rollback(
                () -> {
                    ledger();
                    f.members(
                            member(
                                    U1,
                                    f.voucherGrant(READ_EXPORT, "ALL", f.mainFields(), lines()),
                                    f.accountGrant(READ_EXPORT, "ALL", Set.of(f.accountCode))));
                    assertThatThrownBy(() -> f.reports.query(f.query("report"), U1))
                            .hasMessage(M4_ACCOUNT_NAME)
                            .hasMessageContaining("未授权字段");
                });
    }

    /**
     * 判定顺序是「范围 → 存在与停用 → 授权」：统计用到的主表字段在应用固定的对象版本里已停用、且不在成员的可查看字段里时，
     * 报「不存在或已停用」，不是「未授权」。（真去停用字段要给对象发新版本，应用会被暂停；这里直接把固定版本快照里的字段标成停用。）
     */
    @Test
    void inactiveFieldReportsInactiveNotUnauthorized() {
        rollback(
                () -> {
                    ledger();
                    var fields = new HashSet<>(f.mainFields());
                    fields.remove(f.booked);
                    f.members(
                            member(
                                    U1,
                                    f.voucherGrant(READ_EXPORT, "ALL", fields, lines()),
                                    accountAll()));
                    f.deactivate(f.booked);
                    assertThatThrownBy(() -> f.reports.query(f.query("report"), U1))
                            .hasMessage("统计字段不存在或已停用")
                            .hasMessageNotContaining("未授权");
                    // 应用创建人的授权清单里仍有这个字段 ID：同样报已停用。
                    assertThatThrownBy(() -> f.reports.query(f.query("report"), OWNER))
                            .hasMessage("统计字段不存在或已停用");
                });
    }

    /** 只看本人：明细行没有自己的记录范围，跟着所属凭证走。U1 的凭证是 V1、V3（V5 没有分录）。 */
    @Test
    void ownScopeOnlyAggregatesOwnVouchersLines() {
        rollback(
                () -> {
                    ledger();
                    f.members(
                            member(
                                    U1,
                                    f.voucherGrant(READ, "OWN", f.mainFields(), lines()),
                                    accountAll()));
                    var r = f.reports.query(f.query("report"), U1);
                    assertThat(r.pivot().rows().stream().map(ApplicationReports.PivotHeader::keys))
                            .containsExactly(keys("销售收入"), keys((String) null));
                    assertCell(r, keys("销售收入"), keys(), 1200, 3, 2);
                    assertCell(r, keys((String) null), keys(), 300, 1, 1);
                    assertCell(r, keys(), keys(), 1500, 4, 2);
                    assertThat(r.recordCount()).isEqualTo(4);
                    var page = f.reports.details(f.drill("report", null, null, null), U1);
                    assertThat(page.getTotal()).isEqualTo(4);
                    assertThat(page.getList().stream().map(ApplicationRecords.Row::id))
                            .containsExactly(
                                    f.rowId("V1", "L1"),
                                    f.rowId("V1", "L2"),
                                    f.rowId("V3", "L4"),
                                    f.rowId("V3", "L5"));
                });
    }

    /** 没有导出权限不能导出；有的话导出与查询同一范围、同一口径。 */
    @Test
    void exportRequiresExportAndUsesSameScope() {
        rollback(
                () -> {
                    ledger();
                    f.members(
                            member(
                                    U1,
                                    f.voucherGrant(READ, "ALL", f.mainFields(), lines()),
                                    accountAll()),
                            member(
                                    U2,
                                    f.voucherGrant(READ_EXPORT, "ALL", f.mainFields(), lines()),
                                    accountAll()));
                    var readOnly = f.reports.query(f.query("report"), U1);
                    assertThat(readOnly.canExport()).isFalse();
                    assertCell(readOnly, keys(), keys(), 2050, 6, 4);
                    assertThatThrownBy(() -> f.reports.export(f.query("report"), U1))
                            .hasMessageContaining("权限");
                    var allowed = f.reports.query(f.query("report"), U2);
                    assertThat(allowed.canExport()).isTrue();
                    var exported = f.reports.export(f.query("report"), U2);
                    assertThat(exported.pivot().cells()).isEqualTo(allowed.pivot().cells());
                    assertThat(exported.totals()).isEqualTo(allowed.totals());
                    assertThat(exported.recordCount()).isEqualTo(6);
                    assertThat(exported.detailName()).isEqualTo("分录");
                });
    }

    /** 成员看不到主表的「摘要」：下钻行里没有这个键，分录字段都在。 */
    @Test
    void drillHidesMainFieldsTheMemberCannotRead() {
        rollback(
                () -> {
                    ledger();
                    var fields = new HashSet<>(f.mainFields());
                    fields.remove(f.memo);
                    f.members(
                            member(U1, f.voucherGrant(READ, "ALL", fields, lines()), accountAll()));
                    var page = f.reports.details(f.drill("report", null, null, null), U1);
                    assertThat(page.getTotal()).isEqualTo(6);
                    assertThat(page.getList())
                            .allSatisfy(
                                    row -> {
                                        assertThat(row.values()).doesNotContainKey(f.memo);
                                        assertThat(row.values().keySet())
                                                .containsAll(fields)
                                                .containsAll(ids(f.lines.fields()));
                                        assertThat(row.permissions().readFields())
                                                .doesNotContain(f.memo)
                                                .containsAll(ids(f.lines.fields()));
                                    });
                });
    }

    /** 导出文件的口径行：明细粒度写「来源明细行 N 行（明细「X」）」，主记录粒度逐字不变。 */
    @Test
    void exportNotesNameTheGrain() {
        rollback(
                () -> {
                    f.objects();
                    var rootTable =
                            f.config(
                                    null,
                                    null,
                                    "TABLE",
                                    List.of(f.main(f.booked, "MONTH")),
                                    List.of(),
                                    List.of(
                                            new ApplicationReports.Metric(
                                                    "income", "入金合计", "SUM", f.income)));
                    var rootPivot =
                            f.config(
                                    null,
                                    null,
                                    "PIVOT",
                                    List.of(f.main(f.company, "VALUE")),
                                    List.of(f.main(f.booked, "MONTH")),
                                    List.of(
                                            new ApplicationReports.Metric(
                                                    "income", "入金合计", "SUM", f.income)));
                    f.app(
                                    f.resource("detail_pivot", "REPORT", f.creditPivot()),
                                    f.resource(
                                            "detail_table",
                                            "REPORT",
                                            f.detailConfig(
                                                    "TABLE",
                                                    List.of(f.creditName()),
                                                    List.of(),
                                                    f.creditMetrics())),
                                    f.resource("root_table", "REPORT", rootTable),
                                    f.resource("root_pivot", "REPORT", rootPivot))
                            .seed();
                    assertThat(lastLine("detail_pivot"))
                            .isEqualTo("来源明细行 6 行（明细「分录」）；时区 Asia/Shanghai");
                    assertThat(lastLine("detail_table"))
                            .isEqualTo("展示 3 / 3 组；来源明细行 6 行（明细「分录」）；时区 Asia/Shanghai");
                    assertThat(lastLine("root_pivot")).isEqualTo("来源记录 5 条；时区 Asia/Shanghai");
                    assertThat(lastLine("root_table"))
                            .isEqualTo("展示 2 / 2 组；来源记录 5 条；时区 Asia/Shanghai");
                });
    }

    /** 导出文件第一列最后一个非空单元格：口径行。 */
    private String lastLine(String report) {
        var excel = servicesContext.getBean(com.lingan.ucp.nocode.web.RecordExcelService.class);
        byte[] file = excel.report(f.query(report), OWNER);
        List<Map<Integer, String>> sheet =
                cn.idev.excel.FastExcelFactory.read(new java.io.ByteArrayInputStream(file))
                        .headRowNumber(0)
                        .sheet()
                        .doReadSync();
        String last = null;
        for (var line : sheet)
            if (line.get(0) != null && !line.get(0).isBlank()) last = line.get(0);
        return last;
    }
}
