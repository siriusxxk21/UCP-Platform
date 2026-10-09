package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.resource.ApplicationReportValidator;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.report.ApplicationReportService;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

/**
 * 统计视图「排序与行数」的回归：配置里的排序（sortBy / 列组降序）、查看时点列头的临时排序（Query.sort）、行数不设上限与保护值、导出与屏幕同序。 真实开发库，每例整体回滚。
 *
 * <p>核心口径：存量配置（没有 sortBy）行为不变；排序只改变顺序与截断时保留哪些行，小计与合计仍按全部原始记录聚合；下钻仍命中所点的那一组。
 */
class ReportSortLimitIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ApplicationService apps;
    private ApplicationReportService reports;
    private RecordService records;
    private DataObjectApi objects;
    private ApplicationReportValidator validator;
    private int serial;
    private final Set<String> seeded = new HashSet<>();

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
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        apps = servicesContext.getBean(ApplicationService.class);
        reports = servicesContext.getBean(ApplicationReportService.class);
        records = servicesContext.getBean(RecordService.class);
        objects = servicesContext.getBean(DataObjectApi.class);
        validator = servicesContext.getBean(ApplicationReportValidator.class);
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
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

    // ---------------------------------------------------------------- 夹具

    /** 资金流水的形状：名称、状态、入金、出金、日期，另有一个整数字段用来验证数值维度按数值大小排序。 */
    private DataCenter.Definition object() {
        String suffix = "sort" + serial++;
        SaveObjectDraft request =
                new SaveObjectDraft(
                        null,
                        null,
                        fixture.prefix + suffix,
                        "排序回归",
                        null,
                        "biz_" + fixture.prefix + suffix,
                        "name",
                        List.of(
                                new FieldDefinition(
                                        "name", null, "name", "名称", "TEXT", 100, null, null, true,
                                        false, 0),
                                new FieldDefinition(
                                        "income", null, "income", "入金", "DECIMAL", null, 30, 4,
                                        false, false, 1),
                                new FieldDefinition(
                                        "expense", null, "expense", "出金", "DECIMAL", null, 30, 4,
                                        false, false, 2),
                                new FieldDefinition(
                                        "status", null, "status", "状态", "TEXT", 100, null, null,
                                        false, false, 3),
                                new FieldDefinition(
                                        "booked", null, "booked", "日期", "DATE", null, null, null,
                                        false, false, 4),
                                new FieldDefinition(
                                        "rank", null, "rank", "序号", "INTEGER", null, null, null,
                                        false, false, 5)),
                        List.of());
        var design =
                designs.save(
                        new DataCenter.SaveDesign(
                                request,
                                DataCenter.Settings.defaults(),
                                Map.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(publisher.execute(new DataCenter.ExecutePlan(plan.id(), "排序测试"), 10001).state())
                .isEqualTo("SUCCEEDED");
        return objects.getPublished(design.draft().id());
    }

    private String field(DataCenter.Definition d, String code) {
        return d.fields().stream()
                .filter(f -> f.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private String column(DataCenter.Definition d, String code) {
        return "\"" + d.fieldOptions().get(field(d, code)).columnName() + "\"";
    }

    private ApplicationCenter.Resource resource(String id, String kind, Object config) {
        return new ApplicationCenter.Resource(
                id,
                kind,
                id,
                "排序回归 " + id,
                mapper.convertValue(
                        config,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {}));
    }

    private String app(DataCenter.Definition d, ApplicationCenter.Resource... resources) {
        var v = objects.getVersion(d.objectId(), null);
        var a =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "app" + serial++,
                                "排序验收",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        v.objectId(), v.versionNo(), v.checksum())),
                                        List.of(resources))),
                        10001);
        grantApplicationObjects(a.application().id());
        apps.publish(new ApplicationCenter.Revision(a.application().id(), 0, "排序验收"), 10001);
        return a.application().id();
    }

    private void row(
            String app,
            DataCenter.Definition d,
            String name,
            String status,
            String income,
            String expense,
            String date,
            Integer rank) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(field(d, "name"), name);
        if (status != null) data.put(field(d, "status"), status);
        if (income != null) data.put(field(d, "income"), income);
        if (expense != null) data.put(field(d, "expense"), expense);
        if (date != null) data.put(field(d, "booked"), date);
        if (rank != null) data.put(field(d, "rank"), rank);
        records.save(new ApplicationRecords.Save(app, d.objectId(), null, null, data, null), 10001);
    }

    /**
     * 六条记录（名称, 状态, 入金, 出金, 日期, 序号）：a/paid/10/1/08-01/2、a/paid/20/0/08-01/10、b/paid/30/5/08-01/1、
     * a/paid/40/2/09-05/2、a/unpaid/100/0/09-05/10、c/unpaid/200/50/09-10/9。
     *
     * <p>按日期：08-01 入 60 出 6；09-05 入 140 出 2；09-10 入 200 出 50。按状态/名称：paid/a 入 70、paid/b 入
     * 30、unpaid/a 入 100、unpaid/c 入 200。按序号：1 → 30、2 → 50、9 → 200、10 → 120。
     */
    private void seed(String app, DataCenter.Definition d) {
        row(app, d, "a", "paid", "10", "1", "2026-08-01", 2);
        row(app, d, "a", "paid", "20", "0", "2026-08-01", 10);
        row(app, d, "b", "paid", "30", "5", "2026-08-01", 1);
        row(app, d, "a", "paid", "40", "2", "2026-09-05", 2);
        row(app, d, "a", "unpaid", "100", "0", "2026-09-05", 10);
        row(app, d, "c", "unpaid", "200", "50", "2026-09-10", 9);
    }

    /** 直接往本例的业务表批量造数：每个日期 perDay 条，入金 = 序号、出金 = 1。只碰本例自己建的表，整体回滚。 */
    private void bulk(DataCenter.Definition d, int days, int perDay) {
        jdbc.update(
                "INSERT INTO public.\""
                        + d.tableName()
                        + "\" ("
                        + column(d, "name")
                        + ","
                        + column(d, "status")
                        + ","
                        + column(d, "income")
                        + ","
                        + column(d, "expense")
                        + ","
                        + column(d, "booked")
                        + ",creator,updater) SELECT '批量' || g, 'bulk', g, 1, DATE '1970-01-01' + g,"
                        + " '10001','10001' FROM generate_series(1, ?) g, generate_series(1, ?) n",
                days,
                perDay);
    }

    private ApplicationReports.Dimension dim(DataCenter.Definition d, String code, String bucket) {
        return new ApplicationReports.Dimension(field(d, code), null, bucket);
    }

    private List<ApplicationReports.Metric> metrics(DataCenter.Definition d) {
        return List.of(
                new ApplicationReports.Metric("income", "入金", "SUM", field(d, "income")),
                new ApplicationReports.Metric("expense", "出金", "SUM", field(d, "expense")),
                new ApplicationReports.Metric("count", "笔数", "COUNT", null));
    }

    /** 统计配置：display 之外的排序与行数相关项逐个给出；其余取最简。 */
    private ApplicationReports.Config config(
            DataCenter.Definition d,
            String display,
            List<ApplicationReports.Dimension> rows,
            List<ApplicationReports.Dimension> columns,
            String sortMetricId,
            boolean descending,
            Integer limit,
            ApplicationReports.Pivot pivot,
            String sortBy) {
        return new ApplicationReports.Config(
                d.objectId(),
                rows,
                metrics(d),
                Map.of(),
                List.of(field(d, "status")),
                field(d, "booked"),
                "Asia/Shanghai",
                display,
                sortMetricId,
                descending,
                limit,
                null,
                null,
                null,
                "PIVOT".equals(display) ? columns : null,
                pivot,
                null,
                sortBy);
    }

    private ApplicationReports.Query query(String app) {
        return query(app, null);
    }

    private ApplicationReports.Query query(String app, ApplicationReports.Sort sort) {
        return new ApplicationReports.Query(
                app, "report", null, null, null, null, null, 1, 20, null, null, null, sort);
    }

    private static ApplicationReports.Sort byMetric(
            String metricId, List<String> columnGroup, boolean descending) {
        return new ApplicationReports.Sort(metricId, null, columnGroup, descending);
    }

    private static ApplicationReports.Sort byDimension(int dimension, boolean descending) {
        return new ApplicationReports.Sort(null, dimension, null, descending);
    }

    private static List<String> keys(String... values) {
        return Arrays.asList(values);
    }

    private List<List<String>> rowKeys(ApplicationReports.Result r) {
        return r.pivot().rows().stream().map(ApplicationReports.PivotHeader::keys).toList();
    }

    private List<List<String>> columnKeys(ApplicationReports.Result r) {
        return r.pivot().columns().stream().map(ApplicationReports.PivotHeader::keys).toList();
    }

    private List<List<String>> groupKeys(ApplicationReports.Result r) {
        return r.groups().stream().map(ApplicationReports.Group::keys).toList();
    }

    private ApplicationReports.PivotCell cell(
            ApplicationReports.Result r, List<String> rows, List<String> columns) {
        return r.pivot().cells().stream()
                .filter(c -> c.rowKeys().equals(rows) && c.columnKeys().equals(columns))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少透视格 " + rows + " × " + columns));
    }

    private static BigDecimal number(ApplicationReports.PivotCell cell, String metric) {
        String value = cell.values().get(metric);
        return value == null ? null : new BigDecimal(value);
    }

    /** 发布一张只含这份统计的应用并查询；同一个对象只造一次数（一个用例里可对同一批记录换不同配置各查一次）。 */
    private ApplicationReports.Result run(
            DataCenter.Definition d,
            ApplicationReports.Config config,
            ApplicationReports.Sort sort) {
        String app = app(d, resource("report", "REPORT", config));
        if (seeded.add(d.objectId())) seed(app, d);
        return reports.query(query(app, sort), 10001);
    }

    private List<Map<Integer, String>> sheet(byte[] file) {
        return cn.idev.excel.FastExcelFactory.read(new java.io.ByteArrayInputStream(file))
                .headRowNumber(0)
                .sheet()
                .doReadSync();
    }

    // ---------------------------------------------------------------- 存量行为不变

    /**
     * 存量配置（没有 sortBy、没有列组降序、请求不带 sort）的顺序与改动前逐项相同：透视表没选排序指标时恒按维度值升序（即使勾了倒序）；
     * 透视表选了排序指标按合计列组上的该指标；汇总表按分组值取 descending，且数值分组仍按文本比较（"10" 排在 "2" 之前）。
     */
    @Test
    void legacyConfigsKeepTheirOrder() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> byDate = List.of(dim(d, "booked", "VALUE"));
                    // 透视表 + 倒序 + 没选排序指标：存量就是升序
                    assertThat(
                                    rowKeys(
                                            run(
                                                    d,
                                                    config(
                                                            d, "PIVOT", byDate, List.of(), null,
                                                            true, 30, null, null),
                                                    null)))
                            .containsExactly(
                                    keys("2026-08-01"), keys("2026-09-05"), keys("2026-09-10"));
                    // 透视表 + 排序指标（出金）+ 倒序
                    assertThat(
                                    rowKeys(
                                            run(
                                                    d,
                                                    config(
                                                            d, "PIVOT", byDate, List.of(),
                                                            "expense", true, 30, null, null),
                                                    null)))
                            .containsExactly(
                                    keys("2026-09-10"), keys("2026-08-01"), keys("2026-09-05"));
                    // 汇总表 + 倒序 + 没选排序指标：按分组值降序
                    assertThat(
                                    groupKeys(
                                            run(
                                                    d,
                                                    config(
                                                            d, "TABLE", byDate, null, null, true,
                                                            30, null, null),
                                                    null)))
                            .containsExactly(
                                    keys("2026-09-10"), keys("2026-09-05"), keys("2026-08-01"));
                    // 汇总表按数值分组：存量按文本比较
                    assertThat(
                                    groupKeys(
                                            run(
                                                    d,
                                                    config(
                                                            d,
                                                            "TABLE",
                                                            List.of(dim(d, "rank", "VALUE")),
                                                            null,
                                                            null,
                                                            false,
                                                            30,
                                                            null,
                                                            null),
                                                    null)))
                            .containsExactly(keys("1"), keys("10"), keys("2"), keys("9"));
                });
    }

    // ---------------------------------------------------------------- 配置里的排序

    /** 业务方那张统计的形状（行 = 日期原值，无列维度，入金/出金）：按维度的值降序 = 新日期在前；空日期恒在最后；截断时留下的是排在前面的行。 */
    @Test
    void pivotSortsRowsByDimensionValueNewestFirst() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> byDate = List.of(dim(d, "booked", "VALUE"));
                    ApplicationReports.Config config =
                            config(
                                    d,
                                    "PIVOT",
                                    byDate,
                                    List.of(),
                                    null,
                                    true,
                                    null,
                                    null,
                                    "DIMENSION");
                    String app = app(d, resource("report", "REPORT", config));
                    seed(app, d);
                    row(app, d, "nodate", "paid", "7", "7", null, 3);
                    ApplicationReports.Result r = reports.query(query(app), 10001);
                    assertThat(rowKeys(r))
                            .containsExactly(
                                    keys("2026-09-10"),
                                    keys("2026-09-05"),
                                    keys("2026-08-01"),
                                    keys((String) null));
                    assertThat(r.pivot().rowsTruncated()).isFalse();
                    assertThat(number(cell(r, keys("2026-09-05"), keys()), "income"))
                            .isEqualByComparingTo("140");
                    // 升序时空日期同样在最后
                    ApplicationReports.Config ascending =
                            config(
                                    d,
                                    "PIVOT",
                                    byDate,
                                    List.of(),
                                    null,
                                    false,
                                    null,
                                    null,
                                    "DIMENSION");
                    String app2 = app(d, resource("report", "REPORT", ascending));
                    assertThat(rowKeys(reports.query(query(app2), 10001)))
                            .containsExactly(
                                    keys("2026-08-01"),
                                    keys("2026-09-05"),
                                    keys("2026-09-10"),
                                    keys((String) null));
                    // 只展示 2 行：留下最新的两个日期；合计仍是全部记录
                    ApplicationReports.Config top2 =
                            config(d, "PIVOT", byDate, List.of(), null, true, 2, null, "DIMENSION");
                    String app3 = app(d, resource("report", "REPORT", top2));
                    ApplicationReports.Result limited = reports.query(query(app3), 10001);
                    assertThat(rowKeys(limited))
                            .containsExactly(keys("2026-09-10"), keys("2026-09-05"));
                    assertThat(limited.pivot().rowsTruncated()).isTrue();
                    assertThat(limited.pivot().totalRowGroups()).isEqualTo(4);
                    assertThat(number(cell(limited, keys(), keys()), "income"))
                            .isEqualByComparingTo("407");
                });
    }

    /** 多层行维度：每一层在各自的上级分组内排序；小计行紧随所在分组，合计行在最后。按指标时每层取该层分组的合计。 */
    @Test
    void multiLevelRowsSortWithinTheirParentAndSubtotalsFollow() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> rows =
                            List.of(dim(d, "status", "VALUE"), dim(d, "name", "VALUE"));
                    ApplicationReports.Result byValue =
                            run(
                                    d,
                                    config(
                                            d,
                                            "PIVOT",
                                            rows,
                                            List.of(),
                                            null,
                                            true,
                                            null,
                                            null,
                                            "DIMENSION"),
                                    null);
                    assertThat(rowKeys(byValue))
                            .containsExactly(
                                    keys("unpaid", "c"),
                                    keys("unpaid", "a"),
                                    keys("paid", "b"),
                                    keys("paid", "a"));
                    // 格的输出顺序即行的显示顺序：叶子、所在分组的小计、……、合计
                    assertThat(
                                    byValue.pivot().cells().stream()
                                            .map(ApplicationReports.PivotCell::rowKeys)
                                            .toList())
                            .containsExactly(
                                    keys("unpaid", "c"),
                                    keys("unpaid", "a"),
                                    keys("unpaid"),
                                    keys("paid", "b"),
                                    keys("paid", "a"),
                                    keys("paid"),
                                    keys());
                    assertThat(number(cell(byValue, keys("unpaid"), keys()), "income"))
                            .isEqualByComparingTo("300");
                    // 按指标（入金）升序：paid（100）在 unpaid（300）之前；组内 paid/b（30）在 paid/a（70）之前
                    ApplicationReports.Result byMetric =
                            run(
                                    d,
                                    config(
                                            d, "PIVOT", rows, List.of(), "income", false, null,
                                            null, "METRIC"),
                                    null);
                    assertThat(rowKeys(byMetric))
                            .containsExactly(
                                    keys("paid", "b"),
                                    keys("paid", "a"),
                                    keys("unpaid", "a"),
                                    keys("unpaid", "c"));
                    // 与存量写法（只有排序指标、没有 sortBy）结果相同
                    assertThat(
                                    rowKeys(
                                            run(
                                                    d,
                                                    config(
                                                            d, "PIVOT", rows, List.of(), "income",
                                                            false, null, null, null),
                                                    null)))
                            .isEqualTo(rowKeys(byMetric));
                });
    }

    /** 透视表列组按值降序（新月份在前）；空值列组仍在最后；列组截断时留下排在前面的列组。 */
    @Test
    void columnGroupsSortDescendingWhenConfigured() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> rows = List.of(dim(d, "status", "VALUE"));
                    List<ApplicationReports.Dimension> months = List.of(dim(d, "booked", "MONTH"));
                    ApplicationReports.Pivot descending =
                            new ApplicationReports.Pivot(null, null, null, null, null, true);
                    ApplicationReports.Config config =
                            config(d, "PIVOT", rows, months, null, false, null, descending, null);
                    String app = app(d, resource("report", "REPORT", config));
                    seed(app, d);
                    row(app, d, "nodate", "paid", "7", "7", null, 3);
                    ApplicationReports.Result r = reports.query(query(app), 10001);
                    assertThat(columnKeys(r))
                            .containsExactly(keys("2026-09"), keys("2026-08"), keys((String) null));
                    // 行没有配置排序：仍是存量的维度值升序
                    assertThat(rowKeys(r)).containsExactly(keys("paid"), keys("unpaid"));
                    assertThat(number(cell(r, keys("paid"), keys("2026-09")), "income"))
                            .isEqualByComparingTo("40");
                    // 不配（空）时是升序
                    ApplicationReports.Config plain =
                            config(d, "PIVOT", rows, months, null, false, null, null, null);
                    String app2 = app(d, resource("report", "REPORT", plain));
                    assertThat(columnKeys(reports.query(query(app2), 10001)))
                            .containsExactly(keys("2026-08"), keys("2026-09"), keys((String) null));
                    // 只留 1 个列组：降序时留下最新的月份
                    ApplicationReports.Config one =
                            config(
                                    d,
                                    "PIVOT",
                                    rows,
                                    months,
                                    null,
                                    false,
                                    null,
                                    new ApplicationReports.Pivot(null, null, null, null, 1, true),
                                    null);
                    String app3 = app(d, resource("report", "REPORT", one));
                    ApplicationReports.Result limited = reports.query(query(app3), 10001);
                    assertThat(columnKeys(limited)).containsExactly(keys("2026-09"));
                    assertThat(limited.pivot().columnsTruncated()).isTrue();
                    assertThat(limited.pivot().totalColumnGroups()).isEqualTo(3);
                });
    }

    /** 汇总表：明确选「按分组的值」后数值分组按数值大小；按指标排序与存量相同；两个分组时都参与。 */
    @Test
    void summaryTableSortsByConfiguredDimensionOrMetric() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> byRank = List.of(dim(d, "rank", "VALUE"));
                    assertThat(
                                    groupKeys(
                                            run(
                                                    d,
                                                    config(
                                                            d,
                                                            "TABLE",
                                                            byRank,
                                                            null,
                                                            null,
                                                            false,
                                                            null,
                                                            null,
                                                            "DIMENSION"),
                                                    null)))
                            .containsExactly(keys("1"), keys("2"), keys("9"), keys("10"));
                    assertThat(
                                    groupKeys(
                                            run(
                                                    d,
                                                    config(
                                                            d,
                                                            "TABLE",
                                                            byRank,
                                                            null,
                                                            null,
                                                            true,
                                                            null,
                                                            null,
                                                            "DIMENSION"),
                                                    null)))
                            .containsExactly(keys("10"), keys("9"), keys("2"), keys("1"));
                    ApplicationReports.Result byMetric =
                            run(
                                    d,
                                    config(
                                            d, "TABLE", byRank, null, "income", true, null, null,
                                            "METRIC"),
                                    null);
                    assertThat(groupKeys(byMetric))
                            .containsExactly(keys("9"), keys("10"), keys("2"), keys("1"));
                    assertThat(new BigDecimal(byMetric.groups().get(0).values().get("income")))
                            .isEqualByComparingTo("200");
                });
    }

    // ---------------------------------------------------------------- 查看时点列头

    /**
     * 点列头的临时排序只改变本次返回的顺序：同一份已发布配置，不带 sort 时仍是配置的顺序。透视表有列组时按所点列组的那一格排（多层逐层），
     * 该列组下没有记录的行排在最后；点行维度列头只改那一层。
     */
    @Test
    void runtimeSortOrdersByTheClickedHeaderWithoutChangingTheConfig() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> rows =
                            List.of(dim(d, "status", "VALUE"), dim(d, "name", "VALUE"));
                    List<ApplicationReports.Dimension> months = List.of(dim(d, "booked", "MONTH"));
                    ApplicationReports.Config config =
                            config(d, "PIVOT", rows, months, null, false, null, null, null);
                    String app = app(d, resource("report", "REPORT", config));
                    seed(app, d);
                    List<List<String>> configured =
                            List.of(
                                    keys("paid", "a"),
                                    keys("paid", "b"),
                                    keys("unpaid", "a"),
                                    keys("unpaid", "c"));
                    assertThat(rowKeys(reports.query(query(app), 10001))).isEqualTo(configured);
                    // 9 月列组的入金，降序：unpaid（300）在 paid（40）之前；paid/b 在 9 月没有记录，排最后
                    ApplicationReports.Result september =
                            reports.query(
                                    query(app, byMetric("income", keys("2026-09"), true)), 10001);
                    assertThat(rowKeys(september))
                            .containsExactly(
                                    keys("unpaid", "c"),
                                    keys("unpaid", "a"),
                                    keys("paid", "a"),
                                    keys("paid", "b"));
                    // 升序：没有记录的仍在所在分组的最后
                    assertThat(
                                    rowKeys(
                                            reports.query(
                                                    query(
                                                            app,
                                                            byMetric(
                                                                    "income",
                                                                    keys("2026-09"),
                                                                    false)),
                                                    10001)))
                            .containsExactly(
                                    keys("paid", "a"),
                                    keys("paid", "b"),
                                    keys("unpaid", "a"),
                                    keys("unpaid", "c"));
                    // 8 月列组的入金，降序：只有 paid 在 8 月有记录；paid/a 30 = paid/b 30，同值按原值升序
                    assertThat(
                                    rowKeys(
                                            reports.query(
                                                    query(
                                                            app,
                                                            byMetric(
                                                                    "income",
                                                                    keys("2026-08"),
                                                                    true)),
                                                    10001)))
                            .containsExactly(
                                    keys("paid", "a"),
                                    keys("paid", "b"),
                                    keys("unpaid", "a"),
                                    keys("unpaid", "c"));
                    // 合计列组（不带列键）的出金，降序：unpaid（50）在 paid（8）之前；paid/b（5）在 paid/a（3）之前
                    assertThat(
                                    rowKeys(
                                            reports.query(
                                                    query(app, byMetric("expense", null, true)),
                                                    10001)))
                            .containsExactly(
                                    keys("unpaid", "c"),
                                    keys("unpaid", "a"),
                                    keys("paid", "b"),
                                    keys("paid", "a"));
                    // 点第 2 个行维度（名称）降序：只改这一层，第 1 层仍按配置（升序）
                    assertThat(rowKeys(reports.query(query(app, byDimension(1, true)), 10001)))
                            .containsExactly(
                                    keys("paid", "b"),
                                    keys("paid", "a"),
                                    keys("unpaid", "c"),
                                    keys("unpaid", "a"));
                    // 点第 1 个行维度（状态）降序
                    assertThat(rowKeys(reports.query(query(app, byDimension(0, true)), 10001)))
                            .containsExactly(
                                    keys("unpaid", "a"),
                                    keys("unpaid", "c"),
                                    keys("paid", "a"),
                                    keys("paid", "b"));
                    // 排序不改变任何格的数值，小计与合计仍在
                    assertThat(number(cell(september, keys("paid"), keys("2026-09")), "income"))
                            .isEqualByComparingTo("40");
                    assertThat(number(cell(september, keys(), keys()), "income"))
                            .isEqualByComparingTo("400");
                    // 再次不带 sort：配置没有被改动
                    assertThat(rowKeys(reports.query(query(app), 10001))).isEqualTo(configured);
                    // 不成立的排序明确拒绝
                    assertThatThrownBy(
                                    () ->
                                            reports.query(
                                                    query(app, byMetric("missing", null, true)),
                                                    10001))
                            .hasMessageContaining("排序指标不存在");
                    assertThatThrownBy(() -> reports.query(query(app, byDimension(2, true)), 10001))
                            .hasMessageContaining("排序列无效");
                    assertThatThrownBy(
                                    () ->
                                            reports.query(
                                                    query(
                                                            app,
                                                            byMetric(
                                                                    "income",
                                                                    keys("2026", "09"),
                                                                    true)),
                                                    10001))
                            .hasMessageContaining("排序列组无效");
                });
    }

    /** 汇总表点列头：按指标或按某个分组列；点第 2 个分组列时它是第一排序键；截断时留下的是按点击排序后排在前面的行。 */
    @Test
    void summaryTableRuntimeSortAndTopNFollowTheClickedColumn() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> rows =
                            List.of(dim(d, "status", "VALUE"), dim(d, "name", "VALUE"));
                    ApplicationReports.Config config =
                            config(d, "TABLE", rows, null, null, false, 2, null, null);
                    String app = app(d, resource("report", "REPORT", config));
                    seed(app, d);
                    // 配置：按分组值升序，只展示前 2 组
                    ApplicationReports.Result plain = reports.query(query(app), 10001);
                    assertThat(groupKeys(plain))
                            .containsExactly(keys("paid", "a"), keys("paid", "b"));
                    assertThat(plain.totalGroups()).isEqualTo(4);
                    // 点「入金」降序：前 2 组是入金最大的两组
                    assertThat(
                                    groupKeys(
                                            reports.query(
                                                    query(app, byMetric("income", null, true)),
                                                    10001)))
                            .containsExactly(keys("unpaid", "c"), keys("unpaid", "a"));
                    // 点第 2 个分组列（名称）降序：c 在最前，其次 b
                    assertThat(groupKeys(reports.query(query(app, byDimension(1, true)), 10001)))
                            .containsExactly(keys("unpaid", "c"), keys("paid", "b"));
                    // 汇总表没有列组
                    assertThatThrownBy(
                                    () ->
                                            reports.query(
                                                    query(
                                                            app,
                                                            byMetric(
                                                                    "income",
                                                                    keys("2026-09"),
                                                                    true)),
                                                    10001))
                            .hasMessageContaining("排序列组无效");
                    // 总体指标不受排序与截断影响
                    assertThat(new BigDecimal(plain.totals().get("income")))
                            .isEqualByComparingTo("400");
                });
    }

    /** 排序之后点格子下钻，命中的仍是那一行（那一格）的记录：条数等于格上的笔数。 */
    @Test
    void drillAfterSortStillHitsTheClickedGroup() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> rows =
                            List.of(dim(d, "status", "VALUE"), dim(d, "name", "VALUE"));
                    List<ApplicationReports.Dimension> months = List.of(dim(d, "booked", "MONTH"));
                    ApplicationReports.Config config =
                            config(d, "PIVOT", rows, months, null, false, null, null, null);
                    String app = app(d, resource("report", "REPORT", config));
                    seed(app, d);
                    ApplicationReports.Sort sort = byMetric("income", keys("2026-09"), true);
                    ApplicationReports.Result r = reports.query(query(app, sort), 10001);
                    for (ApplicationReports.PivotCell cell : r.pivot().cells()) {
                        PageResult<ApplicationRecords.Row> page =
                                reports.details(
                                        new ApplicationReports.Query(
                                                app,
                                                "report",
                                                null,
                                                null,
                                                null,
                                                null,
                                                cell.rowKeys(),
                                                1,
                                                100,
                                                null,
                                                null,
                                                cell.columnKeys(),
                                                sort),
                                        10001);
                        assertThat(BigDecimal.valueOf(page.getTotal()))
                                .as("格 %s × %s 的下钻条数", cell.rowKeys(), cell.columnKeys())
                                .isEqualByComparingTo(number(cell, "count"));
                    }
                    // 第一行（排序后是 unpaid/c）在 9 月的那一格：1 条，就是 c
                    PageResult<ApplicationRecords.Row> first =
                            reports.details(
                                    new ApplicationReports.Query(
                                            app,
                                            "report",
                                            null,
                                            null,
                                            null,
                                            null,
                                            rowKeys(r).get(0),
                                            1,
                                            100,
                                            null,
                                            null,
                                            keys("2026-09")),
                                    10001);
                    assertThat(first.getList())
                            .extracting(row -> row.values().get(field(d, "name")))
                            .containsExactly("c");
                });
    }

    // ---------------------------------------------------------------- 行数

    /** 行数留空 = 不限制，只对汇总表与透视表；图表仍最多 200 组。填了数字按数字。保存校验认识「留空」「排序依据」「列组降序」。 */
    @Test
    void emptyLimitIsUnlimitedForTablesWhileChartsKeepTheirCap() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> byDate = List.of(dim(d, "booked", "VALUE"));
                    String pivotApp =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d, "PIVOT", byDate, List.of(), null, false,
                                                    null, null, null)));
                    bulk(d, 250, 2);
                    ApplicationReports.Result pivot = reports.query(query(pivotApp), 10001);
                    assertThat(pivot.pivot().rows()).hasSize(250);
                    assertThat(pivot.pivot().rowsTruncated()).isFalse();
                    assertThat(pivot.pivot().totalRowGroups()).isEqualTo(250);
                    String tableApp =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d, "TABLE", byDate, null, null, false, null,
                                                    null, null)));
                    ApplicationReports.Result table = reports.query(query(tableApp), 10001);
                    assertThat(table.groups()).hasSize(250);
                    assertThat(table.totalGroups()).isEqualTo(250);
                    // 图表留空：按原有上限 200 组，并如实给出总组数
                    String barApp =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d, "BAR", byDate, null, null, false, null, null,
                                                    null)));
                    ApplicationReports.Result bar = reports.query(query(barApp), 10001);
                    assertThat(bar.groups()).hasSize(200);
                    assertThat(bar.totalGroups()).isEqualTo(250);
                    // 填了数字：按数字
                    String limitedApp =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d, "PIVOT", byDate, List.of(), null, false, 30,
                                                    null, null)));
                    ApplicationReports.Result limited = reports.query(query(limitedApp), 10001);
                    assertThat(limited.pivot().rows()).hasSize(30);
                    assertThat(limited.pivot().rowsTruncated()).isTrue();
                    // 汇总表可以填到 200 以上
                    String wideApp =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d, "TABLE", byDate, null, null, false, 240,
                                                    null, null)));
                    assertThat(reports.query(query(wideApp), 10001).groups()).hasSize(240);
                    // 保存校验
                    var v = objects.getVersion(d.objectId(), null);
                    Map<String, DataCenter.Definition> defs = Map.of(d.objectId(), v.definition());
                    ApplicationReports.Config saved =
                            validator.normalize(
                                    config(
                                            d,
                                            "PIVOT",
                                            byDate,
                                            List.of(dim(d, "status", "VALUE")),
                                            null,
                                            true,
                                            null,
                                            new ApplicationReports.Pivot(
                                                    null, null, null, null, null, true),
                                            "DIMENSION"),
                                    defs);
                    assertThat(saved.limit()).isNull();
                    assertThat(saved.sortBy()).isEqualTo("DIMENSION");
                    assertThat(saved.descending()).isTrue();
                    assertThat(saved.pivot().columnDescending()).isTrue();
                    // 存量配置（没有新键）保存后不凭空多出排序依据与列组降序
                    ApplicationReports.Config legacy =
                            validator.normalize(
                                    config(
                                            d, "PIVOT", byDate, List.of(), null, true, 30, null,
                                            null),
                                    defs);
                    assertThat(legacy.sortBy()).isNull();
                    assertThat(legacy.limit()).isEqualTo(30);
                    assertThat(legacy.pivot())
                            .isEqualTo(new ApplicationReports.Pivot(true, true, true, "NONE", 24));
                    Map<String, ApplicationReports.Config> invalid = new LinkedHashMap<>();
                    invalid.put(
                            "最多展示行数应为 1 到 " + ApplicationReports.MAX_TABLE_ROWS,
                            config(
                                    d,
                                    "TABLE",
                                    byDate,
                                    null,
                                    null,
                                    false,
                                    ApplicationReports.MAX_TABLE_ROWS + 1,
                                    null,
                                    null));
                    invalid.put(
                            "最多展示行数应为 1 到 ",
                            config(d, "PIVOT", byDate, List.of(), null, false, 0, null, null));
                    invalid.put(
                            "展示组数应为 1 到 200",
                            config(d, "BAR", byDate, null, null, false, 201, null, null));
                    invalid.put(
                            "按指标排序需要选择排序指标",
                            config(d, "TABLE", byDate, null, null, false, null, null, "METRIC"));
                    invalid.put(
                            "按维度的值排序时不能同时设置排序指标",
                            config(
                                    d,
                                    "TABLE",
                                    byDate,
                                    null,
                                    "income",
                                    false,
                                    null,
                                    null,
                                    "DIMENSION"));
                    invalid.put(
                            "只有透视表可以设置透视选项",
                            config(
                                    d,
                                    "TABLE",
                                    byDate,
                                    null,
                                    null,
                                    false,
                                    null,
                                    new ApplicationReports.Pivot(
                                            null, null, null, null, null, true),
                                    null));
                    invalid.forEach(
                            (message, config) ->
                                    assertThatThrownBy(() -> validator.normalize(config, defs))
                                            .as(message)
                                            .hasMessageContaining(message));
                    assertThatThrownBy(
                                    () ->
                                            validator.normalize(
                                                    config(
                                                            d, "TABLE", byDate, null, null, false,
                                                            null, null, "LABEL"),
                                                    defs))
                            .isInstanceOf(RuntimeException.class);
                    // 汇总表与透视表的上限值本身可以保存；图表留空也可以保存
                    assertThat(
                                    validator
                                            .normalize(
                                                    config(
                                                            d,
                                                            "TABLE",
                                                            byDate,
                                                            null,
                                                            null,
                                                            false,
                                                            ApplicationReports.MAX_TABLE_ROWS,
                                                            null,
                                                            null),
                                                    defs)
                                            .limit())
                            .isEqualTo(ApplicationReports.MAX_TABLE_ROWS);
                    assertThat(
                                    validator
                                            .normalize(
                                                    config(
                                                            d, "BAR", byDate, null, null, false,
                                                            null, null, null),
                                                    defs)
                                            .limit())
                            .isNull();
                });
    }

    /**
     * 一万行量级：12000 个日期（每个 3 条记录）不限制时完整返回、不截断；每一行的入金/出金/笔数与直接 SQL 逐行相同；按日期降序、按入金降序都 整体有序。耗时只打印成
     * S-PERF 行供交付报告引用，不作断言（本机数字随机器负载波动很大，同一用例 0.7 秒到 7 秒都出现过，不代表线上）。
     */
    @Test
    void tenThousandRowsAreCompleteCorrectAndSorted() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> byDate = List.of(dim(d, "booked", "VALUE"));
                    int days = 12000;
                    String app =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d,
                                                    "PIVOT",
                                                    byDate,
                                                    List.of(),
                                                    null,
                                                    true,
                                                    null,
                                                    null,
                                                    "DIMENSION")));
                    bulk(d, days, 3);
                    reports.query(query(app), 10001);
                    long started = System.nanoTime();
                    ApplicationReports.Result r = reports.query(query(app), 10001);
                    long pivotMillis = (System.nanoTime() - started) / 1_000_000;
                    assertThat(r.pivot().rows()).hasSize(days);
                    assertThat(r.pivot().rowsTruncated()).isFalse();
                    assertThat(r.pivot().totalRowGroups()).isEqualTo(days);
                    assertThat(r.recordCount()).isEqualTo(days * 3L);
                    // 逐行与直接 SQL 相同
                    Map<String, BigDecimal[]> expected = new HashMap<>();
                    jdbc.query(
                            "SELECT "
                                    + column(d, "booked")
                                    + "::text k, SUM("
                                    + column(d, "income")
                                    + ") i, SUM("
                                    + column(d, "expense")
                                    + ") e, COUNT(*) n FROM public.\""
                                    + d.tableName()
                                    + "\" GROUP BY 1",
                            rs -> {
                                expected.put(
                                        rs.getString("k"),
                                        new BigDecimal[] {
                                            rs.getBigDecimal("i"),
                                            rs.getBigDecimal("e"),
                                            rs.getBigDecimal("n")
                                        });
                            });
                    assertThat(expected).hasSize(days);
                    Map<List<String>, ApplicationReports.PivotCell> leaves = new HashMap<>();
                    for (ApplicationReports.PivotCell cell : r.pivot().cells())
                        if (cell.columnKeys().isEmpty() && cell.rowKeys().size() == 1)
                            leaves.put(cell.rowKeys(), cell);
                    assertThat(leaves).hasSize(days);
                    for (ApplicationReports.PivotHeader header : r.pivot().rows()) {
                        BigDecimal[] want = expected.get(header.keys().get(0));
                        ApplicationReports.PivotCell cell = leaves.get(header.keys());
                        assertThat(number(cell, "income")).isEqualByComparingTo(want[0]);
                        assertThat(number(cell, "expense")).isEqualByComparingTo(want[1]);
                        assertThat(number(cell, "count")).isEqualByComparingTo(want[2]);
                    }
                    // 合计 = 全部记录：入金 3 × (1+…+12000)
                    assertThat(number(cell(r, keys(), keys()), "income"))
                            .isEqualByComparingTo(BigDecimal.valueOf(3L * days * (days + 1) / 2));
                    // 日期降序整体有序
                    List<String> dates =
                            r.pivot().rows().stream().map(h -> h.keys().get(0)).toList();
                    assertThat(dates).isSortedAccordingTo(Comparator.reverseOrder());
                    // 点「入金」升序：整体不减（入金 = 3 × 序号，与日期同向）
                    started = System.nanoTime();
                    ApplicationReports.Result byIncome =
                            reports.query(query(app, byMetric("income", null, false)), 10001);
                    long sortedMillis = (System.nanoTime() - started) / 1_000_000;
                    List<BigDecimal> incomes = new ArrayList<>();
                    Map<List<String>, ApplicationReports.PivotCell> sortedLeaves = new HashMap<>();
                    for (ApplicationReports.PivotCell cell : byIncome.pivot().cells())
                        if (cell.rowKeys().size() == 1) sortedLeaves.put(cell.rowKeys(), cell);
                    for (ApplicationReports.PivotHeader header : byIncome.pivot().rows())
                        incomes.add(number(sortedLeaves.get(header.keys()), "income"));
                    assertThat(incomes).hasSize(days).isSorted();
                    // 汇总表同样完整
                    String tableApp =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d,
                                                    "TABLE",
                                                    byDate,
                                                    null,
                                                    null,
                                                    true,
                                                    null,
                                                    null,
                                                    "DIMENSION")));
                    reports.query(query(tableApp), 10001);
                    started = System.nanoTime();
                    ApplicationReports.Result table = reports.query(query(tableApp), 10001);
                    long tableMillis = (System.nanoTime() - started) / 1_000_000;
                    assertThat(table.groups()).hasSize(days);
                    assertThat(table.totalGroups()).isEqualTo(days);
                    assertThat(groupKeys(table).get(0)).isEqualTo(rowKeys(r).get(0));
                    System.out.println(
                            "S-PERF rows="
                                    + days
                                    + " records="
                                    + days * 3
                                    + " pivotMs="
                                    + pivotMillis
                                    + " pivotSortedByMetricMs="
                                    + sortedMillis
                                    + " tableMs="
                                    + tableMillis);
                });
    }

    /**
     * 超过保护值：不限制时页面最多返回 MAX_TABLE_ROWS 行并明确标注（截断标志 + 总行数），不是悄悄少显示；合计仍按全部记录。
     * 导出的保护值更高，同一张统计导出包含全部行，顺序与屏幕相同。
     */
    @Test
    void guardTruncatesTheScreenAndFlagsItWhileExportGoesFurther() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> byDate = List.of(dim(d, "booked", "VALUE"));
                    int guard = ApplicationReports.MAX_TABLE_ROWS, days = guard + 50;
                    String app =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d,
                                                    "PIVOT",
                                                    byDate,
                                                    List.of(),
                                                    null,
                                                    true,
                                                    null,
                                                    null,
                                                    "DIMENSION")));
                    bulk(d, days, 1);
                    long started = System.nanoTime();
                    ApplicationReports.Result r = reports.query(query(app), 10001);
                    long screenMillis = (System.nanoTime() - started) / 1_000_000;
                    assertThat(r.pivot().rows()).hasSize(guard);
                    assertThat(r.pivot().rowsTruncated()).isTrue();
                    assertThat(r.pivot().totalRowGroups()).isEqualTo(days);
                    assertThat(r.totalGroups()).isEqualTo(days);
                    // 留下的是排在前面的（最新的）行
                    String newest =
                            jdbc.queryForObject(
                                    "SELECT (DATE '1970-01-01' + " + days + ")::text",
                                    String.class);
                    assertThat(rowKeys(r).get(0)).containsExactly(newest);
                    // 合计按全部记录，不是已显示行相加
                    assertThat(number(cell(r, keys(), keys()), "income"))
                            .isEqualByComparingTo(BigDecimal.valueOf((long) days * (days + 1) / 2));
                    assertThat(number(cell(r, keys(), keys()), "count"))
                            .isEqualByComparingTo(BigDecimal.valueOf(days));
                    // 汇总表同样截断并给出总组数
                    String tableApp =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d,
                                                    "TABLE",
                                                    byDate,
                                                    null,
                                                    null,
                                                    true,
                                                    null,
                                                    null,
                                                    "DIMENSION")));
                    ApplicationReports.Result table = reports.query(query(tableApp), 10001);
                    assertThat(table.groups()).hasSize(guard);
                    assertThat(table.totalGroups()).isEqualTo(days);
                    assertThat(new BigDecimal(table.totals().get("count")))
                            .isEqualByComparingTo(BigDecimal.valueOf(days));
                    // 导出：全部行，顺序与屏幕相同
                    com.lingan.ucp.nocode.web.RecordExcelService excel =
                            servicesContext.getBean(
                                    com.lingan.ucp.nocode.web.RecordExcelService.class);
                    started = System.nanoTime();
                    byte[] file = excel.report(query(app), 10001);
                    long exportMillis = (System.nanoTime() - started) / 1_000_000;
                    List<Map<Integer, String>> lines = sheet(file);
                    assertThat(lines.get(0).get(0)).isEqualTo("日期");
                    List<String> exported = new ArrayList<>();
                    for (int i = 1; i <= days; i++) exported.add(lines.get(i).get(0));
                    assertThat(exported.subList(0, guard))
                            .isEqualTo(rowKeys(r).stream().map(k -> k.get(0)).toList());
                    assertThat(exported).isSortedAccordingTo(Comparator.reverseOrder());
                    assertThat(lines.get(days + 1).get(0)).isEqualTo("合计");
                    assertThat(lines.stream().map(line -> line.get(0)).filter(Objects::nonNull))
                            .noneMatch(text -> text.startsWith("行仅显示前"));
                    System.out.println(
                            "S-PERF guard="
                                    + guard
                                    + " groups="
                                    + days
                                    + " screenMs="
                                    + screenMillis
                                    + " exportRows="
                                    + days
                                    + " exportMs="
                                    + exportMillis
                                    + " exportBytes="
                                    + file.length);
                });
    }

    /**
     * 行多、列组也多：不限制时展示的行数再受「行数 × 已展示列组数 ≤ MAX_PIVOT_CELLS」约束，超出照常截断并标注；列组少时不受影响；
     * 填的数字不超过原上限（200）时与原先完全相同。合计仍按全部记录。
     */
    @Test
    void widePivotIsCappedByTheCellBudgetAndFlagged() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> byDate = List.of(dim(d, "booked", "VALUE"));
                    List<ApplicationReports.Dimension> byRank = List.of(dim(d, "rank", "VALUE"));
                    int days = 1050, ranks = 100;
                    ApplicationReports.Pivot hundred =
                            new ApplicationReports.Pivot(null, null, null, null, ranks);
                    String app =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d, "PIVOT", byDate, byRank, null, false, null,
                                                    hundred, null)));
                    // 预算按「行数 × 已展示列组数」算，不要求每个组合都有记录：每个日期只造 1 条，序号在 1..100 间轮换
                    jdbc.update(
                            "INSERT INTO public.\""
                                    + d.tableName()
                                    + "\" ("
                                    + column(d, "name")
                                    + ","
                                    + column(d, "income")
                                    + ","
                                    + column(d, "booked")
                                    + ","
                                    + column(d, "rank")
                                    + ",creator,updater) SELECT '宽表', 1, DATE '1970-01-01' + g,"
                                    + " (g % ?) + 1, '10001','10001' FROM generate_series(1, ?) g",
                            ranks,
                            days);
                    ApplicationReports.Result wide = reports.query(query(app), 10001);
                    int allowed = ApplicationReports.MAX_PIVOT_CELLS / ranks;
                    assertThat(wide.pivot().columns()).hasSize(ranks);
                    assertThat(wide.pivot().rows()).hasSize(allowed);
                    assertThat(wide.pivot().rowsTruncated()).isTrue();
                    assertThat(wide.pivot().totalRowGroups()).isEqualTo(days);
                    assertThat(number(cell(wide, keys(), keys()), "count"))
                            .isEqualByComparingTo(BigDecimal.valueOf(days));
                    // 默认列组上限 24：只展示 24 个列组，预算允许 4166 行，1050 行全部展示
                    String narrow =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d, "PIVOT", byDate, byRank, null, false, null,
                                                    null, null)));
                    ApplicationReports.Result some = reports.query(query(narrow), 10001);
                    assertThat(some.pivot().columns()).hasSize(24);
                    assertThat(some.pivot().rows()).hasSize(days);
                    assertThat(some.pivot().rowsTruncated()).isFalse();
                    // 填的数字在原上限以内：与原先相同
                    String legacy =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d, "PIVOT", byDate, byRank, null, false, 150,
                                                    hundred, null)));
                    assertThat(reports.query(query(legacy), 10001).pivot().rows()).hasSize(150);
                });
    }

    /**
     * 保护值的依据（默认不跑：REPORT_ROWS_PERFORMANCE=1 时才跑，只打印 S-PERF 行）：① 超过导出保护值的日期数，量页面查询与导出的耗时、 文件大小；②
     * 页面保护值那么多行 × 24 个列组 × 3 个指标的宽透视表，量查询耗时与返回体大小。
     */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
            named = "REPORT_ROWS_PERFORMANCE",
            matches = "1")
    void guardValuesProbe() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> byDate = List.of(dim(d, "booked", "VALUE"));
                    int days = ApplicationReports.MAX_EXPORT_ROWS + 50;
                    String app =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d,
                                                    "PIVOT",
                                                    byDate,
                                                    List.of(),
                                                    null,
                                                    true,
                                                    null,
                                                    null,
                                                    "DIMENSION")));
                    long started = System.nanoTime();
                    bulk(d, days, 1);
                    long insertMillis = (System.nanoTime() - started) / 1_000_000;
                    reports.query(query(app), 10001);
                    started = System.nanoTime();
                    ApplicationReports.Result screen = reports.query(query(app), 10001);
                    long screenMillis = (System.nanoTime() - started) / 1_000_000;
                    int screenBytes = bytes(screen);
                    com.lingan.ucp.nocode.web.RecordExcelService excel =
                            servicesContext.getBean(
                                    com.lingan.ucp.nocode.web.RecordExcelService.class);
                    started = System.nanoTime();
                    byte[] file = excel.report(query(app), 10001);
                    long exportMillis = (System.nanoTime() - started) / 1_000_000;
                    List<Map<Integer, String>> lines = sheet(file);
                    assertThat(screen.pivot().rows()).hasSize(ApplicationReports.MAX_TABLE_ROWS);
                    assertThat(lines.get(ApplicationReports.MAX_EXPORT_ROWS + 1).get(0))
                            .isEqualTo("合计");
                    assertThat(lines.stream().map(line -> line.get(0)).filter(Objects::nonNull))
                            .anyMatch(
                                    text ->
                                            text.startsWith(
                                                    "行仅显示前 "
                                                            + ApplicationReports.MAX_EXPORT_ROWS
                                                            + " 个，共 "
                                                            + days
                                                            + " 个"));
                    System.out.println(
                            "S-PERF probe=export groups="
                                    + days
                                    + " insertMs="
                                    + insertMillis
                                    + " screenRows="
                                    + screen.pivot().rows().size()
                                    + " screenMs="
                                    + screenMillis
                                    + " screenJsonBytes="
                                    + screenBytes
                                    + " exportRows="
                                    + ApplicationReports.MAX_EXPORT_ROWS
                                    + " exportMs="
                                    + exportMillis
                                    + " exportBytes="
                                    + file.length);
                });
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    int days = ApplicationReports.MAX_TABLE_ROWS, groups = 24;
                    String app =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            config(
                                                    d,
                                                    "PIVOT",
                                                    List.of(dim(d, "booked", "VALUE")),
                                                    List.of(dim(d, "rank", "VALUE")),
                                                    null,
                                                    true,
                                                    null,
                                                    null,
                                                    "DIMENSION")));
                    jdbc.update(
                            "INSERT INTO public.\""
                                    + d.tableName()
                                    + "\" ("
                                    + column(d, "name")
                                    + ","
                                    + column(d, "income")
                                    + ","
                                    + column(d, "expense")
                                    + ","
                                    + column(d, "booked")
                                    + ","
                                    + column(d, "rank")
                                    + ",creator,updater) SELECT '宽表', g, 1, DATE '1970-01-01' + g,"
                                    + " n, '10001','10001' FROM generate_series(1, ?) g,"
                                    + " generate_series(1, ?) n",
                            days,
                            groups);
                    for (int limit : new int[] {200, 2000, 5000, 10000}) {
                        String limitedApp =
                                app(
                                        d,
                                        resource(
                                                "report",
                                                "REPORT",
                                                config(
                                                        d,
                                                        "PIVOT",
                                                        List.of(dim(d, "booked", "VALUE")),
                                                        List.of(dim(d, "rank", "VALUE")),
                                                        null,
                                                        true,
                                                        limit,
                                                        null,
                                                        "DIMENSION")));
                        long t0 = System.nanoTime();
                        ApplicationReports.Result part = reports.query(query(limitedApp), 10001);
                        System.out.println(
                                "S-PERF probe=wide-limit limit="
                                        + limit
                                        + " cells="
                                        + part.pivot().cells().size()
                                        + " queryMs="
                                        + (System.nanoTime() - t0) / 1_000_000
                                        + " jsonBytes="
                                        + bytes(part));
                    }
                    long started = System.nanoTime();
                    ApplicationReports.Result wide = reports.query(query(app), 10001);
                    long wideMillis = (System.nanoTime() - started) / 1_000_000;
                    System.out.println("S-PERF probe=wide-unlimited queryMs=" + wideMillis);
                    started = System.nanoTime();
                    ApplicationReports.Result sorted =
                            reports.query(query(app, byMetric("income", keys("7"), true)), 10001);
                    long sortedMillis = (System.nanoTime() - started) / 1_000_000;
                    assertThat(sorted.pivot().rows())
                            .hasSize(ApplicationReports.MAX_PIVOT_CELLS / groups);
                    assertThat(sorted.pivot().rowsTruncated()).isTrue();
                    System.out.println(
                            "S-PERF probe=wide rows="
                                    + wide.pivot().rows().size()
                                    + " columnGroups="
                                    + wide.pivot().columns().size()
                                    + " records="
                                    + days * groups
                                    + " cells="
                                    + wide.pivot().cells().size()
                                    + " queryMs="
                                    + wideMillis
                                    + " sortedByColumnGroupMs="
                                    + sortedMillis
                                    + " jsonBytes="
                                    + bytes(wide));
                });
    }

    private int bytes(Object value) {
        try {
            return mapper.writeValueAsBytes(value).length;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- 导出

    /** 导出与屏幕一致：行顺序（配置的排序或点列头的临时排序）、行数（填了数字时只导出这么多行并写明截断）、列组顺序。 */
    @Test
    void exportFollowsTheScreenOrderAndRowCount() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> rows =
                            List.of(dim(d, "status", "VALUE"), dim(d, "name", "VALUE"));
                    List<ApplicationReports.Dimension> months = List.of(dim(d, "booked", "MONTH"));
                    ApplicationReports.Config config =
                            config(
                                    d,
                                    "PIVOT",
                                    rows,
                                    months,
                                    null,
                                    true,
                                    null,
                                    new ApplicationReports.Pivot(
                                            null, null, null, null, null, true),
                                    "DIMENSION");
                    String app = app(d, resource("report", "REPORT", config));
                    seed(app, d);
                    com.lingan.ucp.nocode.web.RecordExcelService excel =
                            servicesContext.getBean(
                                    com.lingan.ucp.nocode.web.RecordExcelService.class);
                    List<Map<Integer, String>> configured = sheet(excel.report(query(app), 10001));
                    // 列组降序：9 月在 8 月之前
                    assertThat(configured.get(0).get(2)).isEqualTo("2026-09");
                    assertThat(configured.get(0).get(5)).isEqualTo("2026-08");
                    // 行：unpaid/c、unpaid/a、unpaid 小计、paid/b、paid/a、paid 小计、合计
                    assertThat(labels(configured, 2, 9))
                            .containsExactly(
                                    "unpaid/c",
                                    "unpaid/a",
                                    "unpaid/小计",
                                    "paid/b",
                                    "paid/a",
                                    "paid/小计",
                                    "合计/");
                    // 带上点列头的排序（9 月入金升序）：导出与屏幕同序
                    ApplicationReports.Sort sort = byMetric("income", keys("2026-09"), false);
                    ApplicationReports.Result screen = reports.query(query(app, sort), 10001);
                    assertThat(rowKeys(screen))
                            .containsExactly(
                                    keys("paid", "a"),
                                    keys("paid", "b"),
                                    keys("unpaid", "a"),
                                    keys("unpaid", "c"));
                    assertThat(labels(sheet(excel.report(query(app, sort), 10001)), 2, 9))
                            .containsExactly(
                                    "paid/a",
                                    "paid/b",
                                    "paid/小计",
                                    "unpaid/a",
                                    "unpaid/c",
                                    "unpaid/小计",
                                    "合计/");
                    // 汇总表：填了 2 行，导出也是这 2 行并写明「展示 2 / 4 组」
                    ApplicationReports.Config table =
                            config(d, "TABLE", rows, null, "income", true, 2, null, "METRIC");
                    String tableApp = app(d, resource("report", "REPORT", table));
                    List<Map<Integer, String>> lines = sheet(excel.report(query(tableApp), 10001));
                    assertThat(labels(lines, 1, 3)).containsExactly("unpaid/c", "unpaid/a");
                    assertThat(lines.get(3).get(0)).isEqualTo("全部符合条件记录的总体指标");
                    assertThat(lines.stream().map(line -> line.get(0)).filter(Objects::nonNull))
                            .anyMatch(text -> text.startsWith("展示 2 / 4 组"));
                });
    }

    private static List<String> labels(List<Map<Integer, String>> lines, int from, int to) {
        List<String> result = new ArrayList<>();
        for (int i = from; i < to; i++)
            result.add(lines.get(i).get(0) + "/" + Objects.toString(lines.get(i).get(1), ""));
        return result;
    }

    // ---------------------------------------------------------------- 配置读写

    /** 运行端配置投影带出排序依据、空行数与列组降序；已发布的存量配置（没有这些键）读出来仍是空，不被改写。 */
    @Test
    void runtimeConfigCarriesSortSettingsAndLegacyConfigStaysUntouched() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    List<ApplicationReports.Dimension> rows = List.of(dim(d, "status", "VALUE"));
                    List<ApplicationReports.Dimension> months = List.of(dim(d, "booked", "MONTH"));
                    ApplicationReports.Config config =
                            config(
                                    d,
                                    "PIVOT",
                                    rows,
                                    months,
                                    null,
                                    true,
                                    null,
                                    new ApplicationReports.Pivot(
                                            true, true, true, "NONE", 24, true),
                                    "DIMENSION");
                    // 存量形状：配置里根本没有 sortBy、columnDescending 这两个键，limit 是数字
                    Map<String, Object> legacyMap =
                            mapper.convertValue(
                                    config(
                                            d, "PIVOT", rows, months, "income", true, 30, null,
                                            null),
                                    new com.fasterxml.jackson.core.type.TypeReference<
                                            Map<String, Object>>() {});
                    legacyMap.remove("sortBy");
                    legacyMap.put(
                            "pivot",
                            new LinkedHashMap<>(
                                    Map.of(
                                            "subtotals", true,
                                            "rowTotals", true,
                                            "columnTotals", true,
                                            "percent", "NONE",
                                            "maxColumnGroups", 24)));
                    String app =
                            app(
                                    d,
                                    resource("report", "REPORT", config),
                                    new ApplicationCenter.Resource(
                                            "legacy", "REPORT", "legacy", "存量统计", legacyMap));
                    seed(app, d);
                    List<ApplicationCenter.Resource> projected =
                            servicesContext
                                    .getBean(
                                            com.lingan.ucp.nocode.runtime.service.application
                                                    .ApplicationRuntimeService.class)
                                    .application(app, 10001)
                                    .definition()
                                    .resources();
                    ApplicationReports.Config current =
                            mapper.convertValue(
                                    projected.stream()
                                            .filter(res -> res.id().equals("report"))
                                            .findFirst()
                                            .orElseThrow()
                                            .config(),
                                    ApplicationReports.Config.class);
                    assertThat(current.sortBy()).isEqualTo("DIMENSION");
                    assertThat(current.descending()).isTrue();
                    assertThat(current.limit()).isNull();
                    assertThat(current.pivot().columnDescending()).isTrue();
                    ApplicationReports.Config legacy =
                            mapper.convertValue(
                                    projected.stream()
                                            .filter(res -> res.id().equals("legacy"))
                                            .findFirst()
                                            .orElseThrow()
                                            .config(),
                                    ApplicationReports.Config.class);
                    assertThat(legacy.sortBy()).isNull();
                    assertThat(legacy.sortMetricId()).isEqualTo("income");
                    assertThat(legacy.limit()).isEqualTo(30);
                    assertThat(legacy.pivot().columnDescending()).isNull();
                    // 存量统计照常查询：按入金降序（unpaid 300 在前），列组升序
                    ApplicationReports.Result r =
                            reports.query(
                                    new ApplicationReports.Query(
                                            app, "legacy", null, null, null, null, null, 1, 20),
                                    10001);
                    assertThat(rowKeys(r)).containsExactly(keys("unpaid"), keys("paid"));
                    assertThat(columnKeys(r)).containsExactly(keys("2026-08"), keys("2026-09"));
                });
    }
}
