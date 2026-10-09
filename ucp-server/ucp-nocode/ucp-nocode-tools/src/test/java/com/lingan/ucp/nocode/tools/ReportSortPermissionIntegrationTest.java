package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.FieldRuleFixture.*;
import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.Member;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.lingan.ucp.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.lingan.ucp.nocode.runtime.service.report.ApplicationReportService;
import com.lingan.ucp.nocode.web.RecordExcelService;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.util.*;

/**
 * 统计的排序、不限制行数、点列头排序、导出、下钻在三种授权形态下仍受权限约束（集成分支上「统计排序与行数」与「授权全部 / 隐式只读」同在时）：
 *
 * <ul>
 *   <li>成员授权是「全部」（清单存的是哨兵 {@code "*"}）：与逐项列出全部字段的授权结果相同；
 *   <li>成员只被授权部分字段：用到未授权字段的统计整张拒绝（含排序、导出、下钻请求）；只用已授权字段的统计照常，排序与不限制只在本人能看的记录上算，
 *       下钻行里没有未授权字段，点列头排序指不到配置之外的字段；
 *   <li>隐式只读对象（应用只引用流水，流水引用的公司不在「已引用对象」里）：按引用字段分组时表头是公司名称，除名称外读不到公司的任何字段， 不能沿关系到公司的字段上分组、筛选。
 * </ul>
 *
 * 真实开发库；夹具由 {@link FieldRuleFixture} 建，用例结束后按前缀清理。
 */
class ReportSortPermissionIntegrationTest {
    private static final Set<String> ALL = Set.of("*");
    private FieldRuleFixture f;
    private ApplicationReportService reports;
    private ApplicationAuthorizationService authorization;
    private RecordExcelService excel;
    private DataCenter.Definition company, flow;
    private String app;
    private String companyField;
    private final long owner = 10001L;
    private final long member = 25001L;
    private final Map<String, String> flowIds = new LinkedHashMap<>();
    private final Map<String, String> companyIds = new LinkedHashMap<>();

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
        f = new FieldRuleFixture();
        reports = servicesContext.getBean(ApplicationReportService.class);
        authorization = servicesContext.getBean(ApplicationAuthorizationService.class);
        excel = servicesContext.getBean(RecordExcelService.class);
        company =
                f.object(
                        "co",
                        List.of(field("region", "地区", "TEXT")),
                        Map.of(),
                        List.of(),
                        List.of());
        flow =
                f.object(
                        "flow",
                        List.of(
                                field("income", "入金", "DECIMAL", 20, 4),
                                field("expense", "出金", "DECIMAL", 20, 4),
                                field("booked", "日期", "DATE"),
                                field("memo", "备注", "TEXT")),
                        Map.of(),
                        List.of(reference("company", company)),
                        List.of());
        companyField = relationField(flow, "company");
        // 公司的数据由另一个应用录入；被测应用只引用流水，公司是隐式只读。
        String seed = f.app(company);
        for (String[] row : new String[][] {{"甲公司", "东区"}, {"乙公司", "西区"}})
            companyIds.put(
                    row[0],
                    f.save(
                                    seed,
                                    company,
                                    values(
                                            id(company, "name"),
                                            row[0],
                                            id(company, "region"),
                                            row[1]))
                            .id());
        app = f.app(resources(), flow);
        flowRow("r1", "10", "1", "2026-08-01", "甲公司", "暗号一");
        flowRow("r2", "20", "2", "2026-08-01", "乙公司", "普通");
        flowRow("r3", "40", "4", "2026-09-05", "甲公司", "普通");
        flowRow("r4", "200", "50", "2026-09-10", "乙公司", "普通");
        // r1、r4 算作成员本人创建的（「只看本人」时用）
        for (String name : List.of("r1", "r4"))
            jdbc.update(
                    "UPDATE public.\"" + flow.tableName() + "\" SET creator=? WHERE id=?",
                    Long.toString(member),
                    Long.parseLong(flowIds.get(name)));
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    // ── 夹具 ──

    private void flowRow(
            String name,
            String income,
            String expense,
            String date,
            String companyName,
            String memo) {
        flowIds.put(
                name,
                f.save(
                                app,
                                flow,
                                values(
                                        id(flow, "name"),
                                        name,
                                        id(flow, "income"),
                                        income,
                                        id(flow, "expense"),
                                        expense,
                                        id(flow, "booked"),
                                        date,
                                        companyField,
                                        companyIds.get(companyName),
                                        id(flow, "memo"),
                                        memo))
                        .id());
    }

    private ApplicationCenter.Resource resource(String id, String kind, Object config) {
        return new ApplicationCenter.Resource(
                id,
                kind,
                "perm_" + id,
                id,
                mapper.convertValue(
                        config,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {}));
    }

    private ApplicationReports.Metric sum(String id, String name, String code) {
        return new ApplicationReports.Metric(id, name, "SUM", id(flow, code));
    }

    /** 行数不限制、按行维度的值降序：新功能的两个开关都开着。 */
    private ApplicationReports.Config report(
            String display,
            List<ApplicationReports.Dimension> rows,
            List<ApplicationReports.Metric> metrics) {
        return new ApplicationReports.Config(
                flow.objectId(),
                rows,
                metrics,
                Map.of(),
                List.of(),
                id(flow, "booked"),
                "Asia/Shanghai",
                display,
                null,
                true,
                null,
                "view",
                null,
                null,
                "PIVOT".equals(display) ? List.of() : null,
                null,
                null,
                "DIMENSION");
    }

    private List<ApplicationCenter.Resource> resources() {
        List<ApplicationReports.Dimension> byDate =
                List.of(new ApplicationReports.Dimension(id(flow, "booked"), null, "VALUE"));
        ApplicationReports.Metric count =
                new ApplicationReports.Metric("count", "笔数", "COUNT", null);
        return List.of(
                resource(
                        "view",
                        "VIEW",
                        new ApplicationUi.View(
                                flow.objectId(),
                                flow.fields().stream().map(FieldDefinition::id).toList(),
                                Map.of(),
                                null,
                                false,
                                10,
                                null)),
                resource(
                        "by_date",
                        "REPORT",
                        report(
                                "PIVOT",
                                byDate,
                                List.of(
                                        sum("income", "入金", "income"),
                                        sum("expense", "出金", "expense"),
                                        count))),
                resource(
                        "income_only",
                        "REPORT",
                        report("PIVOT", byDate, List.of(sum("income", "入金", "income"), count))),
                resource(
                        "income_table",
                        "REPORT",
                        report("TABLE", byDate, List.of(sum("income", "入金", "income"), count))),
                resource(
                        "by_company",
                        "REPORT",
                        report(
                                "PIVOT",
                                List.of(
                                        new ApplicationReports.Dimension(
                                                companyField, null, "VALUE")),
                                List.of(sum("income", "入金", "income"), count))));
    }

    private void authorize(ObjectGrant... grants) {
        authorization.save(
                new ApplicationAuthorization.Save(
                        app,
                        authorization.get(app).revision(),
                        List.of(new Member("USER", Long.toString(member), List.of(grants)))),
                owner);
    }

    /** 把对象授予应用的范围（上限）改成「全部」：六个清单都存哨兵，与新建授权的默认值相同。 */
    private void shareAll() {
        var sharing =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService
                                .class);
        var current =
                sharing.forApplication(app).stream()
                        .filter(g -> g.objectId().equals(flow.objectId()))
                        .findFirst()
                        .orElseThrow();
        sharing.save(
                new ObjectSharing.Save(
                        flow.objectId(),
                        app,
                        current.revision(),
                        new ObjectGrant(
                                flow.objectId(),
                                current.permission().actions(),
                                "ALL",
                                ALL,
                                ALL,
                                ALL,
                                ALL,
                                ALL,
                                ALL),
                        "上限改为全部"),
                owner);
        assertThat(sharing.storedPermission(flow.objectId(), app).readFields())
                .containsExactly("*");
    }

    private ObjectGrant grant(Set<String> actions, String scope, Set<String> readFields) {
        return new ObjectGrant(
                flow.objectId(), actions, scope, readFields, Set.of(), Set.of(), Set.of());
    }

    private ApplicationReports.Query query(String report, ApplicationReports.Sort sort) {
        return new ApplicationReports.Query(
                app, report, null, null, null, null, null, 1, 20, null, null, null, sort);
    }

    private ApplicationReports.Query drill(String report, List<String> group) {
        return new ApplicationReports.Query(
                app, report, null, null, null, null, group, 1, 100, null, null, List.of());
    }

    private static ApplicationReports.Sort byMetric(String metricId, boolean descending) {
        return new ApplicationReports.Sort(metricId, null, null, descending);
    }

    private static List<String> keys(String... values) {
        return Arrays.asList(values);
    }

    private static List<List<String>> rowKeys(ApplicationReports.Result r) {
        return r.pivot().rows().stream().map(ApplicationReports.PivotHeader::keys).toList();
    }

    private static List<List<String>> rowLabels(ApplicationReports.Result r) {
        return r.pivot().rows().stream().map(ApplicationReports.PivotHeader::labels).toList();
    }

    private static BigDecimal grand(ApplicationReports.Result r, String metric) {
        return new BigDecimal(r.totals().get(metric));
    }

    private List<Map<Integer, String>> sheet(byte[] file) {
        return cn.idev.excel.FastExcelFactory.read(new java.io.ByteArrayInputStream(file))
                .headRowNumber(0)
                .sheet()
                .doReadSync();
    }

    private static List<String> firstColumn(List<Map<Integer, String>> lines, int from, int to) {
        List<String> result = new ArrayList<>();
        for (int i = from; i < to; i++) result.add(lines.get(i).get(0));
        return result;
    }

    // ── 用例 ──

    /** 上限与成员授权都是「全部」（哨兵）：排序、不限制、点列头排序、导出、下钻都与管理员看到的相同，下钻行带全部字段。 */
    @Test
    void memberGrantedAllSeesTheSameSortedUnlimitedReportAsTheOwner() {
        // 上限也是「全部」：两层取交集后仍是哨兵，要到算有效授权的最后一步才对着对象定义展开
        shareAll();
        authorize(grant(Set.of("READ", "EXPORT"), "ALL", ALL));
        // 存下来的确实是哨兵，不是展开后的清单
        assertThat(authorization.get(app).members().get(0).objects().get(0).readFields())
                .containsExactly("*");
        ApplicationReports.Result mine = reports.query(query("by_date", null), member);
        ApplicationReports.Result theirs = reports.query(query("by_date", null), owner);
        assertThat(rowKeys(mine))
                .containsExactly(keys("2026-09-10"), keys("2026-09-05"), keys("2026-08-01"))
                .isEqualTo(rowKeys(theirs));
        assertThat(mine.pivot().cells()).isEqualTo(theirs.pivot().cells());
        assertThat(mine.pivot().rowsTruncated()).isFalse();
        assertThat(grand(mine, "expense")).isEqualByComparingTo("57");
        assertThat(mine.canExport()).isTrue();
        // 点「出金」列头升序
        assertThat(rowKeys(reports.query(query("by_date", byMetric("expense", false)), member)))
                .containsExactly(keys("2026-08-01"), keys("2026-09-05"), keys("2026-09-10"));
        // 导出：与屏幕同序
        List<Map<Integer, String>> lines =
                sheet(excel.report(query("by_date", byMetric("expense", true)), member));
        assertThat(lines.get(0).values()).contains("日期", "入金", "出金", "笔数");
        assertThat(firstColumn(lines, 1, 4))
                .containsExactly("2026-09-10", "2026-09-05", "2026-08-01");
        // 下钻：这一组的记录，带全部字段
        List<ApplicationRecords.Row> rows =
                reports.details(drill("by_date", keys("2026-08-01")), member).getList();
        assertThat(rows)
                .extracting(ApplicationRecords.Row::id)
                .containsExactlyInAnyOrder(flowIds.get("r1"), flowIds.get("r2"));
        assertThat(rows.get(0).values().keySet())
                .contains(id(flow, "expense"), id(flow, "memo"), id(flow, "income"));
    }

    /** 成员只被授权部分字段、只看本人：用到未授权字段的统计整张拒绝（带不带排序、导出、下钻都一样）；只用已授权字段的统计只在本人的记录上算。 */
    @Test
    void partialFieldGrantStillGatesSortingExportAndDrill() {
        Set<String> allowed =
                Set.of(id(flow, "name"), id(flow, "booked"), id(flow, "income"), companyField);
        authorize(grant(Set.of("READ"), "OWN", allowed));
        // 带「出金」指标的统计：出金不在授权字段里
        assertThatThrownBy(() -> reports.query(query("by_date", null), member))
                .hasMessageContaining("未授权字段");
        assertThatThrownBy(() -> reports.query(query("by_date", byMetric("income", true)), member))
                .hasMessageContaining("未授权字段");
        assertThatThrownBy(() -> reports.query(query("by_date", byMetric("expense", true)), member))
                .hasMessageContaining("未授权字段");
        assertThatThrownBy(() -> reports.details(drill("by_date", keys("2026-08-01")), member))
                .hasMessageContaining("未授权字段");
        assertThatThrownBy(() -> excel.report(query("by_date", null), member))
                .isInstanceOf(com.lingan.ucp.framework.common.exception.ServiceException.class);
        // 只用已授权字段的统计：只算本人的两条（r1 08-01 入金 10、r4 09-10 入金 200），不限制行数也不会多出别人的组
        ApplicationReports.Result r = reports.query(query("income_only", null), member);
        assertThat(r.recordCount()).isEqualTo(2);
        assertThat(rowKeys(r)).containsExactly(keys("2026-09-10"), keys("2026-08-01"));
        assertThat(r.pivot().totalRowGroups()).isEqualTo(2);
        assertThat(grand(r, "income")).isEqualByComparingTo("210");
        assertThat(r.canExport()).isFalse();
        // 点「入金」列头升序：仍只有本人的两组
        assertThat(rowKeys(reports.query(query("income_only", byMetric("income", false)), member)))
                .containsExactly(keys("2026-08-01"), keys("2026-09-10"));
        // 点列头排序指不到配置之外的字段：这张统计没有「出金」指标
        assertThatThrownBy(
                        () ->
                                reports.query(
                                        query("income_only", byMetric("expense", true)), member))
                .hasMessageContaining("排序指标不存在");
        // 汇总表同样
        ApplicationReports.Result table = reports.query(query("income_table", null), member);
        assertThat(table.groups())
                .extracting(ApplicationReports.Group::keys)
                .containsExactly(keys("2026-09-10"), keys("2026-08-01"));
        assertThat(table.totalGroups()).isEqualTo(2);
        // 下钻：只有本人的记录，行里没有未授权字段
        List<ApplicationRecords.Row> rows =
                reports.details(drill("income_only", keys()), member).getList();
        assertThat(rows)
                .extracting(ApplicationRecords.Row::id)
                .containsExactlyInAnyOrder(flowIds.get("r1"), flowIds.get("r4"));
        for (ApplicationRecords.Row row : rows)
            assertThat(row.values().keySet())
                    .doesNotContain(id(flow, "expense"), id(flow, "memo"))
                    .contains(id(flow, "income"));
        // 没有导出权限：导出被拒
        assertThatThrownBy(() -> excel.report(query("income_only", null), member))
                .isInstanceOf(com.lingan.ucp.framework.common.exception.ServiceException.class);
        // 给了导出权限后：导出只有本人的两行、只有这张统计的两个指标，顺序跟着点列头的排序
        authorize(grant(Set.of("READ", "EXPORT"), "OWN", allowed));
        List<Map<Integer, String>> lines =
                sheet(excel.report(query("income_only", byMetric("income", false)), member));
        assertThat(new ArrayList<>(lines.get(0).values()).subList(0, 3))
                .containsExactly("日期", "入金", "笔数");
        assertThat(firstColumn(lines, 1, 4)).containsExactly("2026-08-01", "2026-09-10", "合计");
        assertThat(new BigDecimal(lines.get(3).get(1))).isEqualByComparingTo("210");
        // 带未授权字段的那张统计，有导出权限也不行
        assertThatThrownBy(() -> excel.report(query("by_date", null), member))
                .hasMessageContaining("未授权字段");
    }

    /** 隐式只读对象：按引用字段分组、排序、导出不带出公司的字段；下钻明细里公司一格只有名称；公司不能当独立对象读，也不能沿关系到公司的字段上分组。 */
    @Test
    void impliedReadOnlyTargetLeaksNothingThroughSortedExportedAndDrilledReports() {
        authorize(grant(Set.of("READ", "EXPORT"), "ALL", ALL));
        ApplicationReports.Result r = reports.query(query("by_company", null), member);
        // 行键与表头都是公司记录的主键：统计的分组表头只对挑取值字段解析显示文字，引用字段显示原值（存量行为）。
        // 所以公司的任何字段（连名称）都不经统计流出。
        assertThat(new HashSet<>(rowKeys(r)))
                .containsExactlyInAnyOrder(
                        keys(companyIds.get("甲公司")), keys(companyIds.get("乙公司")));
        assertThat(rowLabels(r)).isEqualTo(rowKeys(r));
        // 点「入金」列头降序：乙公司（220）在甲公司（50）之前
        assertThat(rowKeys(reports.query(query("by_company", byMetric("income", true)), member)))
                .containsExactly(keys(companyIds.get("乙公司")), keys(companyIds.get("甲公司")));
        // 导出：与屏幕同序；整张表里没有公司的地区
        List<Map<Integer, String>> lines =
                sheet(excel.report(query("by_company", byMetric("income", true)), member));
        assertThat(firstColumn(lines, 1, 3))
                .containsExactly(companyIds.get("乙公司"), companyIds.get("甲公司"));
        assertThat(lines.stream().flatMap(line -> line.values().stream()).filter(Objects::nonNull))
                .noneMatch(text -> text.contains("东区") || text.contains("西区"));
        // 下钻到乙公司：两条流水；公司字段显示名称
        List<ApplicationRecords.Row> rows =
                reports.details(drill("by_company", keys(companyIds.get("乙公司"))), member).getList();
        assertThat(rows)
                .extracting(ApplicationRecords.Row::id)
                .containsExactlyInAnyOrder(flowIds.get("r2"), flowIds.get("r4"));
        assertThat(rows.get(0).displayValues().get(companyField)).isEqualTo("乙公司");
        // 公司本身不能当独立对象读
        assertThatThrownBy(
                        () ->
                                f.runtime.page(
                                        new ApplicationRecords.Query(
                                                app,
                                                company.objectId(),
                                                1,
                                                20,
                                                null,
                                                Map.of(),
                                                null,
                                                false),
                                        member))
                .hasMessageContaining("该对象不属于应用的已发布版本");
        // 不能沿关系到公司的「地区」字段上分组：这样的统计保存不了（公司不在已引用对象里）
        String relationId = flow.relations().get(0).id();
        ApplicationReports.Config byRegion =
                report(
                        "PIVOT",
                        List.of(
                                new ApplicationReports.Dimension(
                                        id(company, "region"), relationId, "VALUE")),
                        List.of(sum("income", "入金", "income")));
        assertThatThrownBy(() -> f.app(List.of(resource("by_region", "REPORT", byRegion)), flow))
                .hasMessageContaining("统计对象未被应用引用");
    }
}
