package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.common.dto.DynamicConditionDTO;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.lingan.ucp.nocode.application.service.resource.ApplicationReportValidator;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.report.ApplicationReportService;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 透视表（PIVOT）与统计下钻到数据视图的回归：真实开发库，每例整体回滚。
 *
 * <p>核心口径：小计与合计按原始记录重新聚合（去重计数、平均、计算指标的小计不等于叶子相加）； 下钻与格子同一记录集；数据视图 reportDrill 与 report-details
 * 同一条件构造。
 */
class ReportPivotIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ApplicationService apps;
    private ApplicationReportService reports;
    private RecordService records;
    private DataObjectApi objects;
    private int serial;

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

    private DataCenter.Definition object() {
        String suffix = "pivot" + serial++;
        var request =
                new SaveObjectDraft(
                        null,
                        null,
                        fixture.prefix + suffix,
                        "透视回归",
                        null,
                        "biz_" + fixture.prefix + suffix,
                        "name",
                        List.of(
                                new FieldDefinition(
                                        "name", null, "name", "名称", "TEXT", 100, null, null, true,
                                        false, 0),
                                new FieldDefinition(
                                        "amount", null, "amount", "金额", "DECIMAL", null, 30, 4,
                                        false, false, 1),
                                new FieldDefinition(
                                        "status", null, "status", "状态", "TEXT", 100, null, null,
                                        false, false, 2),
                                new FieldDefinition(
                                        "booked", null, "booked", "日期", "DATE", null, null, null,
                                        false, false, 3)),
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
        assertThat(publisher.execute(new DataCenter.ExecutePlan(plan.id(), "透视测试"), 10001).state())
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

    private ApplicationCenter.Resource resource(String id, String kind, Object config) {
        return new ApplicationCenter.Resource(
                id,
                kind,
                id,
                "透视回归 " + id,
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
                                "透视验收",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        v.objectId(), v.versionNo(), v.checksum())),
                                        List.of(resources))),
                        10001);
        grantApplicationObjects(a.application().id());
        apps.publish(new ApplicationCenter.Revision(a.application().id(), 0, "透视验收"), 10001);
        return a.application().id();
    }

    private ApplicationRecords.Row row(
            String app,
            DataCenter.Definition d,
            String name,
            String amount,
            String status,
            String date) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(field(d, "name"), name);
        if (amount != null) data.put(field(d, "amount"), amount);
        if (status != null) data.put(field(d, "status"), status);
        if (date != null) data.put(field(d, "booked"), date);
        return records.save(
                        new ApplicationRecords.Save(app, d.objectId(), null, null, data, null),
                        10001)
                .record();
    }

    private ApplicationReports.Dimension dim(DataCenter.Definition d, String code, String bucket) {
        return new ApplicationReports.Dimension(field(d, code), null, bucket);
    }

    /** 五个指标：求和、计数、去重计数（日期）、平均、计算指标（求和 ÷ 计数）。 */
    private List<ApplicationReports.Metric> metrics(DataCenter.Definition d) {
        return List.of(
                new ApplicationReports.Metric("amount", "金额", "SUM", field(d, "amount")),
                new ApplicationReports.Metric("count", "笔数", "COUNT", null),
                new ApplicationReports.Metric("days", "天数", "COUNT_DISTINCT", field(d, "booked")),
                new ApplicationReports.Metric("average", "平均", "AVG", field(d, "amount")),
                new ApplicationReports.Metric(
                        "per",
                        "每笔",
                        "FORMULA",
                        null,
                        null,
                        new ApplicationReports.Formula("DIVIDE", "amount", "count"),
                        null));
    }

    private ApplicationReports.Config pivot(
            DataCenter.Definition d,
            List<ApplicationReports.Dimension> rows,
            List<ApplicationReports.Dimension> columns,
            List<ApplicationReports.Metric> metrics,
            ApplicationReports.Pivot settings,
            int limit,
            String detailViewId,
            DynamicConditionDTO fixed) {
        return new ApplicationReports.Config(
                d.objectId(),
                rows,
                metrics,
                Map.of(),
                List.of(field(d, "status"), field(d, "name")),
                field(d, "booked"),
                "Asia/Shanghai",
                "PIVOT",
                null,
                false,
                limit,
                detailViewId,
                fixed,
                null,
                columns,
                settings,
                null);
    }

    private ApplicationUi.View view(DataCenter.Definition d, Map<String, Object> equal) {
        return new ApplicationUi.View(
                d.objectId(),
                d.fields().stream().map(FieldDefinition::id).toList(),
                equal,
                null,
                false,
                10,
                null);
    }

    private ApplicationReports.Query query(String app) {
        return new ApplicationReports.Query(app, "report", null, null, null, null, null, 1, 20);
    }

    private ApplicationReports.Query drill(
            String app, List<String> group, List<String> columnGroup) {
        return new ApplicationReports.Query(
                app, "report", null, null, null, null, group, 1, 100, null, null, columnGroup);
    }

    private static List<String> keys(String... values) {
        return Arrays.asList(values);
    }

    private ApplicationReports.PivotCell cell(
            ApplicationReports.Result r, List<String> rows, List<String> columns) {
        return r.pivot().cells().stream()
                .filter(c -> c.rowKeys().equals(rows) && c.columnKeys().equals(columns))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少透视格 " + rows + " × " + columns));
    }

    private Optional<ApplicationReports.PivotCell> find(
            ApplicationReports.Result r, List<String> rows, List<String> columns) {
        return r.pivot().cells().stream()
                .filter(c -> c.rowKeys().equals(rows) && c.columnKeys().equals(columns))
                .findFirst();
    }

    private static BigDecimal number(ApplicationReports.PivotCell cell, String metric) {
        var value = cell.values().get(metric);
        return value == null ? null : new BigDecimal(value);
    }

    private static BigDecimal ratio(ApplicationReports.PivotCell cell, String metric) {
        var value = cell.ratios().get(metric);
        return value == null ? null : new BigDecimal(value);
    }

    private DynamicConditionDTO condition(String field, String op, Object value) {
        var item = new DynamicConditionDTO.Item();
        item.setType("condition");
        item.setField(field);
        item.setOperator(op);
        item.setValue(value);
        var tree = new DynamicConditionDTO();
        tree.setLogic(DynamicConditionDTO.Logic.AND);
        tree.setItems(List.of(item));
        return tree;
    }

    /**
     * 六条记录（状态, 名称, 金额, 日期）： paid/a/10/08-01、paid/a/20/08-01、paid/b/30/08-01、paid/a/40/09-05、
     * unpaid/a/100/09-05、unpaid/c/200/09-10。
     */
    private List<ApplicationRecords.Row> seed(String app, DataCenter.Definition d) {
        return List.of(
                row(app, d, "a", "10", "paid", "2026-08-01"),
                row(app, d, "a", "20", "paid", "2026-08-01"),
                row(app, d, "b", "30", "paid", "2026-08-01"),
                row(app, d, "a", "40", "paid", "2026-09-05"),
                row(app, d, "a", "100", "unpaid", "2026-09-05"),
                row(app, d, "c", "200", "unpaid", "2026-09-10"));
    }

    // ---------------------------------------------------------------- 聚合口径

    /** 叶子、各层小计、行合计、列合计、总计的数值都来自原始记录重新聚合。 */
    @Test
    void pivotLeavesSubtotalsAndTotalsAreRecomputedFromRawRecords() {
        rollback(
                () -> {
                    var d = object();
                    var config =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE"), dim(d, "name", "VALUE")),
                                    List.of(dim(d, "booked", "MONTH")),
                                    metrics(d),
                                    null,
                                    30,
                                    null,
                                    null);
                    String app = app(d, resource("report", "REPORT", config));
                    seed(app, d);
                    var r = reports.query(query(app), 10001);
                    var p = r.pivot();
                    assertThat(p.rowDimensionNames()).containsExactly("状态", "名称");
                    assertThat(p.columnDimensionNames()).containsExactly("日期");
                    assertThat(p.rows())
                            .extracting(ApplicationReports.PivotHeader::keys)
                            .containsExactly(
                                    keys("paid", "a"),
                                    keys("paid", "b"),
                                    keys("unpaid", "a"),
                                    keys("unpaid", "c"));
                    assertThat(p.columns())
                            .extracting(ApplicationReports.PivotHeader::keys)
                            .containsExactly(keys("2026-08"), keys("2026-09"));
                    assertThat(p.totalRowGroups()).isEqualTo(4);
                    assertThat(p.totalColumnGroups()).isEqualTo(2);
                    assertThat(p.rowsTruncated()).isFalse();
                    assertThat(p.columnsTruncated()).isFalse();
                    assertThat(r.recordCount()).isEqualTo(6);
                    // 叶子
                    var leaf = cell(r, keys("paid", "a"), keys("2026-08"));
                    assertThat(number(leaf, "amount")).isEqualByComparingTo("30");
                    assertThat(number(leaf, "count")).isEqualByComparingTo("2");
                    assertThat(number(leaf, "days")).isEqualByComparingTo("1");
                    assertThat(number(leaf, "average")).isEqualByComparingTo("15");
                    assertThat(number(leaf, "per")).isEqualByComparingTo("15");
                    // 空组合不出格：unpaid/c 在 8 月没有记录
                    assertThat(find(r, keys("unpaid", "c"), keys("2026-08"))).isEmpty();
                    // 行小计（paid × 8 月）：r1..r3
                    var sub = cell(r, keys("paid"), keys("2026-08"));
                    assertThat(number(sub, "amount")).isEqualByComparingTo("60");
                    assertThat(number(sub, "count")).isEqualByComparingTo("3");
                    // 行合计列组（paid/a × 全部月份）：r1 r2 r4
                    var rowTotal = cell(r, keys("paid", "a"), keys());
                    assertThat(number(rowTotal, "amount")).isEqualByComparingTo("70");
                    assertThat(number(rowTotal, "days")).isEqualByComparingTo("2");
                    // 列合计行（全部 × 9 月）：r4 r5 r6
                    var columnTotal = cell(r, keys(), keys("2026-09"));
                    assertThat(number(columnTotal, "amount")).isEqualByComparingTo("340");
                    assertThat(number(columnTotal, "count")).isEqualByComparingTo("3");
                    // 总计与 Result.totals 一致
                    var grand = cell(r, keys(), keys());
                    assertThat(number(grand, "amount")).isEqualByComparingTo("400");
                    assertThat(number(grand, "count")).isEqualByComparingTo("6");
                    assertThat(number(grand, "days")).isEqualByComparingTo("3");
                    assertThat(new BigDecimal(r.totals().get("amount")))
                            .isEqualByComparingTo("400");
                    assertThat(r.groups()).isEmpty();
                    assertThat(r.totalGroups()).isEqualTo(4);
                    // 占比未开启时不给 ratios
                    assertThat(p.cells()).allMatch(c -> c.ratios() == null);
                });
    }

    /** 反例钉子：去重计数、平均、计算指标在小计与合计层级都不等于叶子相加。 */
    @Test
    void subtotalsOfDistinctCountAverageAndFormulaAreNotLeafSums() {
        rollback(
                () -> {
                    var d = object();
                    var config =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE"), dim(d, "name", "VALUE")),
                                    List.of(dim(d, "booked", "MONTH")),
                                    metrics(d),
                                    null,
                                    30,
                                    null,
                                    null);
                    String app = app(d, resource("report", "REPORT", config));
                    seed(app, d);
                    var r = reports.query(query(app), 10001);
                    // paid × 8 月：叶子 paid/a、paid/b
                    var sub = cell(r, keys("paid"), keys("2026-08"));
                    var leafA = cell(r, keys("paid", "a"), keys("2026-08"));
                    var leafB = cell(r, keys("paid", "b"), keys("2026-08"));
                    for (var metric : List.of("days", "average", "per"))
                        assertThat(number(sub, metric))
                                .as("小计 %s 不能是叶子相加", metric)
                                .isNotEqualByComparingTo(
                                        number(leafA, metric).add(number(leafB, metric)));
                    assertThat(number(sub, "days")).isEqualByComparingTo("1");
                    assertThat(number(sub, "average")).isEqualByComparingTo("20");
                    assertThat(number(sub, "per")).isEqualByComparingTo("20");
                    // paid × 全部月份：两个行叶子的行合计 days 为 2、1，按原始记录应为 2
                    var subTotal = cell(r, keys("paid"), keys());
                    assertThat(number(subTotal, "days")).isEqualByComparingTo("2");
                    assertThat(number(subTotal, "average")).isEqualByComparingTo("25");
                    // 9 月列合计：三个叶子的 days 各为 1，按原始记录应为 2（09-05、09-10）
                    assertThat(number(cell(r, keys(), keys("2026-09")), "days"))
                            .isEqualByComparingTo("2");
                    // 总计：平均 400/6，计算指标同样按总计层的分子分母重算
                    var grand = cell(r, keys(), keys());
                    assertThat(number(grand, "average").doubleValue())
                            .isCloseTo(66.6666667, within(1e-6));
                    assertThat(number(grand, "per").doubleValue())
                            .isCloseTo(66.6666667, within(1e-6));
                });
    }

    /** 开关只决定输出，不改变口径：关掉小计/行合计/列合计后对应的格不出现。 */
    @Test
    void subtotalAndTotalSwitchesOnlyControlOutput() {
        rollback(
                () -> {
                    var d = object();
                    var config =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE"), dim(d, "name", "VALUE")),
                                    List.of(dim(d, "booked", "MONTH")),
                                    metrics(d),
                                    new ApplicationReports.Pivot(false, false, true, null, null),
                                    30,
                                    null,
                                    null);
                    String app = app(d, resource("report", "REPORT", config));
                    seed(app, d);
                    var r = reports.query(query(app), 10001);
                    assertThat(r.pivot().cells())
                            .noneMatch(c -> c.rowKeys().size() == 1)
                            .noneMatch(c -> c.columnKeys().isEmpty())
                            .anyMatch(c -> c.rowKeys().isEmpty());
                    assertThat(new BigDecimal(r.totals().get("amount")))
                            .isEqualByComparingTo("400");
                });
    }

    /** 没有列维度：只有一个列组（keys=[]），totalColumnGroups=1，行小计与合计照常重算。 */
    @Test
    void pivotWithoutColumnDimensionsHasOneColumnGroup() {
        rollback(
                () -> {
                    var d = object();
                    var config =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE"), dim(d, "name", "VALUE")),
                                    List.of(),
                                    metrics(d),
                                    null,
                                    30,
                                    null,
                                    null);
                    String app = app(d, resource("report", "REPORT", config));
                    seed(app, d);
                    var r = reports.query(query(app), 10001);
                    assertThat(r.pivot().columns())
                            .singleElement()
                            .satisfies(h -> assertThat(h.keys()).isEmpty());
                    assertThat(r.pivot().totalColumnGroups()).isEqualTo(1);
                    assertThat(r.pivot().columnsTruncated()).isFalse();
                    assertThat(number(cell(r, keys("paid", "a"), keys()), "days"))
                            .isEqualByComparingTo("2");
                    assertThat(number(cell(r, keys("paid"), keys()), "days"))
                            .isEqualByComparingTo("2");
                    assertThat(number(cell(r, keys(), keys()), "count")).isEqualByComparingTo("6");
                    assertThat(
                                    reports.details(drill(app, keys("unpaid"), keys()), 10001)
                                            .getTotal())
                            .isEqualTo(2);
                });
    }

    // ---------------------------------------------------------------- 占比

    /** ROW / COLUMN / TOTAL 三种基准；分母为 0 时为 null。 */
    @Test
    void ratiosUseRowColumnOrGrandTotalAndNullOnZeroDenominator() {
        rollback(
                () -> {
                    var d = object();
                    var metrics =
                            List.of(
                                    new ApplicationReports.Metric(
                                            "amount", "金额", "SUM", field(d, "amount")));
                    Map<String, String> apps = new LinkedHashMap<>();
                    for (var percent : List.of("ROW", "COLUMN", "TOTAL"))
                        apps.put(
                                percent,
                                app(
                                        d,
                                        resource(
                                                "report",
                                                "REPORT",
                                                pivot(
                                                        d,
                                                        List.of(dim(d, "status", "VALUE")),
                                                        List.of(dim(d, "booked", "MONTH")),
                                                        metrics,
                                                        new ApplicationReports.Pivot(
                                                                null, null, null, percent, null),
                                                        30,
                                                        null,
                                                        null))));
                    String any = apps.get("ROW");
                    row(any, d, "x1", "10", "x", "2026-08-01");
                    row(any, d, "x2", "30", "x", "2026-09-01");
                    row(any, d, "y1", "0", "y", "2026-08-01");
                    var byRow = reports.query(query(apps.get("ROW")), 10001);
                    assertThat(ratio(cell(byRow, keys("x"), keys("2026-08")), "amount"))
                            .isEqualByComparingTo("0.25");
                    assertThat(ratio(cell(byRow, keys("x"), keys("2026-09")), "amount"))
                            .isEqualByComparingTo("0.75");
                    assertThat(ratio(cell(byRow, keys("x"), keys()), "amount"))
                            .isEqualByComparingTo("1");
                    // y 行合计为 0：分母为 0 → null（不是 0）
                    assertThat(cell(byRow, keys("y"), keys("2026-08")).ratios())
                            .containsEntry("amount", null);
                    var byColumn = reports.query(query(apps.get("COLUMN")), 10001);
                    assertThat(ratio(cell(byColumn, keys("x"), keys("2026-08")), "amount"))
                            .isEqualByComparingTo("1");
                    assertThat(ratio(cell(byColumn, keys("y"), keys("2026-08")), "amount"))
                            .isEqualByComparingTo("0");
                    assertThat(ratio(cell(byColumn, keys("x"), keys()), "amount"))
                            .isEqualByComparingTo("1");
                    var byTotal = reports.query(query(apps.get("TOTAL")), 10001);
                    assertThat(ratio(cell(byTotal, keys("x"), keys("2026-09")), "amount"))
                            .isEqualByComparingTo("0.75");
                    assertThat(ratio(cell(byTotal, keys(), keys()), "amount"))
                            .isEqualByComparingTo("1");
                    assertThat(byTotal.pivot().cells()).allMatch(c -> c.ratios() != null);
                });
    }

    // ---------------------------------------------------------------- 截断、排序、空值

    /** 列组按原值升序（数值按大小）、空值最后；超出上限截断并标注 totalColumnGroups，截断不改变合计。 */
    @Test
    void columnGroupsSortNumericallyWithNullsLastAndTruncateWithTotals() {
        rollback(
                () -> {
                    var d = object();
                    var metrics =
                            List.of(new ApplicationReports.Metric("count", "笔数", "COUNT", null));
                    var full =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE")),
                                    List.of(dim(d, "amount", "VALUE")),
                                    metrics,
                                    null,
                                    30,
                                    null,
                                    null);
                    var cut =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE")),
                                    List.of(dim(d, "amount", "VALUE")),
                                    metrics,
                                    new ApplicationReports.Pivot(null, null, null, null, 2),
                                    1,
                                    null,
                                    null);
                    String appFull = app(d, resource("report", "REPORT", full));
                    String appCut = app(d, resource("report", "REPORT", cut));
                    row(appFull, d, "r1", "100", "b", null);
                    row(appFull, d, "r2", "9", "a", null);
                    row(appFull, d, "r3", "10", null, null);
                    row(appFull, d, "r4", null, "a", null);
                    var all = reports.query(query(appFull), 10001).pivot();
                    assertThat(all.columns())
                            .extracting(h -> h.keys().getFirst())
                            .extracting(k -> k == null ? null : new BigDecimal(k).intValue())
                            .containsExactly(9, 10, 100, null);
                    assertThat(all.columns().getLast().labels()).containsExactly("未填写");
                    assertThat(all.rows())
                            .extracting(h -> h.keys().getFirst())
                            .containsExactly("a", "b", null);
                    var r = reports.query(query(appCut), 10001);
                    var cutPivot = r.pivot();
                    assertThat(cutPivot.columns()).hasSize(2);
                    assertThat(cutPivot.columnsTruncated()).isTrue();
                    assertThat(cutPivot.totalColumnGroups()).isEqualTo(4);
                    assertThat(cutPivot.rows()).hasSize(1);
                    assertThat(cutPivot.rowsTruncated()).isTrue();
                    assertThat(cutPivot.totalRowGroups()).isEqualTo(3);
                    var shownColumns =
                            cutPivot.columns().stream()
                                    .map(ApplicationReports.PivotHeader::keys)
                                    .collect(Collectors.toSet());
                    assertThat(cutPivot.cells())
                            .allMatch(
                                    c ->
                                            c.columnKeys().isEmpty()
                                                    || shownColumns.contains(c.columnKeys()));
                    // 合计仍是全部记录
                    assertThat(number(cell(r, keys(), keys()), "count")).isEqualByComparingTo("4");
                    assertThat(r.recordCount()).isEqualTo(4);
                });
    }

    // ---------------------------------------------------------------- 校验

    @Test
    void validatorRejectsInvalidPivotConfigurations() {
        rollback(
                () -> {
                    var d = object();
                    var validator = servicesContext.getBean(ApplicationReportValidator.class);
                    var defs = Map.of(d.objectId(), d);
                    var rows = List.of(dim(d, "status", "VALUE"));
                    var cols = List.of(dim(d, "booked", "MONTH"));
                    var normalized =
                            validator.normalize(
                                    pivot(d, rows, cols, metrics(d), null, 30, null, null), defs);
                    assertThat(normalized.pivot())
                            .isEqualTo(new ApplicationReports.Pivot(true, true, true, "NONE", 24));
                    assertThat(normalized.columnDimensions()).isEqualTo(cols);
                    assertThat(normalized.detailEditable()).isNull();
                    // 同一字段不同粒度允许
                    validator.normalize(
                            pivot(
                                    d,
                                    List.of(dim(d, "booked", "YEAR")),
                                    cols,
                                    metrics(d),
                                    null,
                                    30,
                                    null,
                                    null),
                            defs);
                    Map<String, ApplicationReports.Config> invalid = new LinkedHashMap<>();
                    var table = pivot(d, rows, List.of(), metrics(d), null, 30, null, null);
                    invalid.put(
                            "只有透视表可以设置列维度",
                            withDisplay(
                                    pivot(d, rows, cols, metrics(d), null, 30, null, null),
                                    "TABLE",
                                    null));
                    invalid.put(
                            "只有透视表可以设置透视选项",
                            withDisplay(table, "TABLE", ApplicationReports.Pivot.defaults()));
                    invalid.put(
                            "同一字段不能同时作为透视表的行维度和列维度",
                            pivot(
                                    d,
                                    rows,
                                    List.of(dim(d, "status", "VALUE")),
                                    metrics(d),
                                    null,
                                    30,
                                    null,
                                    null));
                    // 2026-09-30 起列维度不再限 2 个：3 个不重复列维度可以保存（上限见
                    // ReportPivotDimensionsIntegrationTest）
                    assertThat(
                                    validator
                                            .normalize(
                                                    pivot(
                                                            d,
                                                            rows,
                                                            List.of(
                                                                    dim(d, "booked", "MONTH"),
                                                                    dim(d, "name", "VALUE"),
                                                                    dim(d, "amount", "VALUE")),
                                                            metrics(d),
                                                            null,
                                                            30,
                                                            null,
                                                            null),
                                                    defs)
                                            .columnDimensions())
                            .hasSize(3);
                    invalid.put(
                            "透视表列维度不能重复",
                            pivot(
                                    d,
                                    rows,
                                    List.of(dim(d, "booked", "MONTH"), dim(d, "booked", "MONTH")),
                                    metrics(d),
                                    null,
                                    30,
                                    null,
                                    null));
                    invalid.put(
                            "透视表列组上限应为 1 到 100",
                            pivot(
                                    d,
                                    rows,
                                    cols,
                                    metrics(d),
                                    new ApplicationReports.Pivot(null, null, null, null, 101),
                                    30,
                                    null,
                                    null));
                    invalid.put(
                            "透视表列组上限应为 1 到 100 ",
                            pivot(
                                    d,
                                    rows,
                                    cols,
                                    metrics(d),
                                    new ApplicationReports.Pivot(null, null, null, null, 0),
                                    30,
                                    null,
                                    null));
                    invalid.put(
                            "ReportPivotPercentEnum",
                            pivot(
                                    d,
                                    rows,
                                    cols,
                                    metrics(d),
                                    new ApplicationReports.Pivot(null, null, null, "HALF", null),
                                    30,
                                    null,
                                    null));
                    invalid.put(
                            "透视表至少需要一个行维度",
                            pivot(d, List.of(), cols, metrics(d), null, 30, null, null));
                    invalid.put(
                            "最多展示行数应为 1 到 " + ApplicationReports.MAX_TABLE_ROWS,
                            pivot(
                                    d,
                                    rows,
                                    cols,
                                    metrics(d),
                                    null,
                                    ApplicationReports.MAX_TABLE_ROWS + 1,
                                    null,
                                    null));
                    var editable = pivot(d, rows, cols, metrics(d), null, 30, null, null);
                    invalid.put(
                            "明细允许编辑需要先选择下钻明细视图",
                            new ApplicationReports.Config(
                                    editable.objectId(),
                                    editable.dimensions(),
                                    editable.metrics(),
                                    editable.equal(),
                                    editable.filterFieldIds(),
                                    editable.dateFieldId(),
                                    editable.timeZone(),
                                    "TABLE",
                                    null,
                                    false,
                                    30,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    true));
                    invalid.forEach(
                            (message, config) ->
                                    assertThatThrownBy(() -> validator.normalize(config, defs))
                                            .as(message)
                                            .hasMessageContaining(message.trim()));
                });
    }

    private ApplicationReports.Config withDisplay(
            ApplicationReports.Config c, String display, ApplicationReports.Pivot pivot) {
        return new ApplicationReports.Config(
                c.objectId(),
                c.dimensions(),
                c.metrics(),
                c.equal(),
                c.filterFieldIds(),
                c.dateFieldId(),
                c.timeZone(),
                display,
                c.sortMetricId(),
                c.descending(),
                c.limit(),
                c.detailViewId(),
                c.conditions(),
                c.chart(),
                c.columnDimensions(),
                pivot,
                c.detailEditable());
    }

    // ---------------------------------------------------------------- 下钻

    /** 每个透视格（含小计、行合计、列合计、总计）的下钻条数都等于格上的记录数指标。 */
    @Test
    void everyPivotCellIncludingSubtotalsDrillsToTheSameRecords() {
        rollback(
                () -> {
                    var d = object();
                    var config =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE"), dim(d, "name", "VALUE")),
                                    List.of(dim(d, "booked", "MONTH")),
                                    metrics(d),
                                    null,
                                    30,
                                    null,
                                    null);
                    String app = app(d, resource("report", "REPORT", config));
                    var seeded = seed(app, d);
                    var r = reports.query(query(app), 10001);
                    assertThat(r.pivot().cells()).hasSizeGreaterThan(10);
                    for (var c : r.pivot().cells())
                        assertThat(
                                        reports.details(
                                                        drill(app, c.rowKeys(), c.columnKeys()),
                                                        10001)
                                                .getTotal())
                                .as("下钻 %s × %s", c.rowKeys(), c.columnKeys())
                                .isEqualTo(number(c, "count").longValue());
                    // 小计格（paid × 8 月）下钻到 r1..r3
                    var sub = reports.details(drill(app, keys("paid"), keys("2026-08")), 10001);
                    assertThat(sub.getList())
                            .extracting(ApplicationRecords.Row::id)
                            .containsExactlyInAnyOrder(
                                    seeded.get(0).id(), seeded.get(1).id(), seeded.get(2).id());
                    // 列键前缀为空 = 不按列限定
                    assertThat(reports.details(drill(app, keys("paid"), null), 10001).getTotal())
                            .isEqualTo(4);
                    assertThatThrownBy(
                                    () ->
                                            reports.details(
                                                    drill(app, keys("paid", "a", "x"), null),
                                                    10001))
                            .hasMessageContaining("下钻分组键无效");
                    assertThatThrownBy(
                                    () ->
                                            reports.details(
                                                    drill(app, null, keys("2026-08", "x")), 10001))
                            .hasMessageContaining("下钻列键无效");
                });
    }

    /** 非透视报表不能按列键下钻，group 仍必须覆盖全部维度（行为不变）。 */
    @Test
    void nonPivotReportsKeepFullGroupDrillAndRejectColumnKeys() {
        rollback(
                () -> {
                    var d = object();
                    var table =
                            withDisplay(
                                    pivot(
                                            d,
                                            List.of(dim(d, "status", "VALUE")),
                                            null,
                                            metrics(d),
                                            null,
                                            30,
                                            null,
                                            null),
                                    "TABLE",
                                    null);
                    String app = app(d, resource("report", "REPORT", table));
                    seed(app, d);
                    var r = reports.query(query(app), 10001);
                    assertThat(r.pivot()).isNull();
                    assertThat(r.groups()).hasSize(2);
                    assertThat(reports.details(drill(app, keys("paid"), null), 10001).getTotal())
                            .isEqualTo(4);
                    assertThat(reports.details(drill(app, keys("paid"), keys()), 10001).getTotal())
                            .isEqualTo(4);
                    assertThatThrownBy(
                                    () ->
                                            reports.details(
                                                    drill(app, keys("paid"), keys("x")), 10001))
                            .hasMessageContaining("下钻列键无效");
                    assertThatThrownBy(() -> reports.details(drill(app, keys(), null), 10001))
                            .hasMessageContaining("下钻分组键无效");
                });
    }

    private ApplicationReports.Drill reportDrill(
            String app, List<String> group, List<String> columnGroup) {
        return new ApplicationReports.Drill(
                app, "report", group, columnGroup, null, null, null, null, null, null);
    }

    private ApplicationRecords.DrillPage viewDrill(
            String app,
            DataCenter.Definition d,
            String viewId,
            String search,
            ApplicationReports.Drill drill,
            long actor) {
        return records.drillPage(
                new ApplicationRecords.Query(
                        app,
                        d.objectId(),
                        1,
                        100,
                        search,
                        null,
                        null,
                        false,
                        viewId,
                        null,
                        null,
                        List.of(),
                        drill),
                actor);
    }

    private static Set<String> ids(List<ApplicationRecords.Row> rows) {
        return rows.stream().map(ApplicationRecords.Row::id).collect(Collectors.toSet());
    }

    /** 数据视图 reportDrill 与 report-details 返回同一记录集（条数与 id 集合都相等），含小计与合计格。 */
    @Test
    void viewReportDrillReturnsExactlyTheReportDetailsRecordSet() {
        rollback(
                () -> {
                    var d = object();
                    var config =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE"), dim(d, "name", "VALUE")),
                                    List.of(dim(d, "booked", "MONTH")),
                                    metrics(d),
                                    null,
                                    30,
                                    "view_all",
                                    null);
                    String app =
                            app(
                                    d,
                                    resource("report", "REPORT", config),
                                    resource("view_all", "VIEW", view(d, Map.of())));
                    seed(app, d);
                    var r = reports.query(query(app), 10001);
                    for (var c : r.pivot().cells()) {
                        var details =
                                reports.details(drill(app, c.rowKeys(), c.columnKeys()), 10001);
                        var page =
                                viewDrill(
                                        app,
                                        d,
                                        "view_all",
                                        null,
                                        reportDrill(app, c.rowKeys(), c.columnKeys()),
                                        10001);
                        assertThat(page.total())
                                .as("条数 %s × %s", c.rowKeys(), c.columnKeys())
                                .isEqualTo(details.getTotal());
                        assertThat(page.drillTotal()).isEqualTo(details.getTotal());
                        assertThat(ids(page.list()))
                                .as("记录集 %s × %s", c.rowKeys(), c.columnKeys())
                                .isEqualTo(ids(details.getList()));
                    }
                    // 视图对象不一致、应用不一致、缺少视图 都拒绝
                    assertThatThrownBy(
                                    () ->
                                            records.drillPage(
                                                    new ApplicationRecords.Query(
                                                            app,
                                                            d.objectId(),
                                                            1,
                                                            10,
                                                            null,
                                                            null,
                                                            null,
                                                            false,
                                                            null,
                                                            null,
                                                            null,
                                                            List.of(),
                                                            reportDrill(app, null, null)),
                                                    10001))
                            .hasMessageContaining("数据视图");
                    assertThatThrownBy(
                                    () ->
                                            viewDrill(
                                                    app,
                                                    d,
                                                    "view_all",
                                                    null,
                                                    reportDrill("other-app", null, null),
                                                    10001))
                            .hasMessageContaining("同一应用");
                });
    }

    /** 视图自身固定筛选与搜索只收窄：条数变少，drillTotal（仅下钻条件）不变。 */
    @Test
    void viewOwnFiltersNarrowTheDrillButDrillTotalStaysTheSame() {
        rollback(
                () -> {
                    var d = object();
                    var config =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE")),
                                    List.of(dim(d, "booked", "MONTH")),
                                    metrics(d),
                                    null,
                                    30,
                                    "view_all",
                                    null);
                    String app =
                            app(
                                    d,
                                    resource("report", "REPORT", config),
                                    resource("view_all", "VIEW", view(d, Map.of())),
                                    resource(
                                            "only_a",
                                            "VIEW",
                                            view(d, Map.of(field(d, "name"), "a"))));
                    var seeded = seed(app, d);
                    var drill = reportDrill(app, keys("paid"), keys());
                    var details = reports.details(drill(app, keys("paid"), keys()), 10001);
                    assertThat(details.getTotal()).isEqualTo(4);
                    var narrowed = viewDrill(app, d, "only_a", null, drill, 10001);
                    assertThat(narrowed.total()).isEqualTo(3);
                    assertThat(narrowed.drillTotal()).isEqualTo(4);
                    assertThat(ids(narrowed.list()))
                            .isEqualTo(
                                    Set.of(
                                            seeded.get(0).id(),
                                            seeded.get(1).id(),
                                            seeded.get(3).id()));
                    var searched = viewDrill(app, d, "view_all", "b", drill, 10001);
                    assertThat(searched.total()).isEqualTo(1);
                    assertThat(searched.drillTotal()).isEqualTo(4);
                    // 普通分页（不带下钻）不受影响
                    assertThat(
                                    records.page(
                                                    new ApplicationRecords.Query(
                                                            app,
                                                            d.objectId(),
                                                            1,
                                                            100,
                                                            null,
                                                            null,
                                                            null,
                                                            false,
                                                            "only_a"),
                                                    10001)
                                            .getTotal())
                            .isEqualTo(4);
                });
    }

    /** 下钻明细视图的固定筛选本来就是报表统计范围的一部分：格子数值、下钻与视图列表三者一致。 */
    @Test
    void detailViewFixedFiltersAreAlreadyPartOfTheReportScope() {
        rollback(
                () -> {
                    var d = object();
                    var config =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE")),
                                    List.of(dim(d, "booked", "MONTH")),
                                    metrics(d),
                                    null,
                                    30,
                                    "only_a",
                                    null);
                    String app =
                            app(
                                    d,
                                    resource("report", "REPORT", config),
                                    resource(
                                            "only_a",
                                            "VIEW",
                                            view(d, Map.of(field(d, "name"), "a"))));
                    seed(app, d);
                    var r = reports.query(query(app), 10001);
                    var paid = cell(r, keys("paid"), keys());
                    assertThat(number(paid, "count")).isEqualByComparingTo("3");
                    var page =
                            viewDrill(
                                    app,
                                    d,
                                    "only_a",
                                    null,
                                    reportDrill(app, keys("paid"), keys()),
                                    10001);
                    assertThat(page.total()).isEqualTo(3);
                    assertThat(page.drillTotal()).isEqualTo(3);
                });
    }

    // ---------------------------------------------------------------- 报表级筛选

    /** 固定条件、指标条件、公共日期筛选、用户等值筛选全部作用于透视：叶子/小计/合计与下钻条数都在筛选之后。 */
    @Test
    void fixedMetricAndPageFiltersApplyBeforePivotAggregationAndDrill() {
        rollback(
                () -> {
                    var d = object();
                    var metrics =
                            List.of(
                                    new ApplicationReports.Metric("count", "笔数", "COUNT", null),
                                    new ApplicationReports.Metric(
                                            "big",
                                            "大额",
                                            "SUM",
                                            field(d, "amount"),
                                            condition(field(d, "amount"), "gte", "30"),
                                            null,
                                            null));
                    var config =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE"), dim(d, "name", "VALUE")),
                                    List.of(dim(d, "booked", "MONTH")),
                                    metrics,
                                    null,
                                    30,
                                    "view_all",
                                    condition(field(d, "status"), "neq", "void"));
                    String app =
                            app(
                                    d,
                                    resource("report", "REPORT", config),
                                    resource("view_all", "VIEW", view(d, Map.of())));
                    seed(app, d);
                    row(app, d, "a", "999", "void", "2026-08-01");
                    row(app, d, "a", "50", "paid", "2026-07-15");
                    // 公共日期筛选 8/1～9/30 排除 7 月那条；固定条件排除 void
                    var q =
                            new ApplicationReports.Query(
                                    app,
                                    "report",
                                    null,
                                    "2026-08-01",
                                    "2026-09-30",
                                    null,
                                    null,
                                    1,
                                    100);
                    var r = reports.query(q, 10001);
                    assertThat(r.recordCount()).isEqualTo(6);
                    assertThat(r.pivot().columns())
                            .extracting(h -> h.keys().getFirst())
                            .containsExactly("2026-08", "2026-09");
                    assertThat(r.pivot().rows()).noneMatch(h -> "void".equals(h.keys().getFirst()));
                    var grand = cell(r, keys(), keys());
                    assertThat(number(grand, "count")).isEqualByComparingTo("6");
                    // 指标条件只收窄该指标：金额 ≥ 30 的 30+40+100+200
                    assertThat(number(grand, "big")).isEqualByComparingTo("370");
                    var sub = cell(r, keys("paid"), keys("2026-08"));
                    assertThat(number(sub, "count")).isEqualByComparingTo("3");
                    assertThat(number(sub, "big")).isEqualByComparingTo("30");
                    var paidAll = cell(r, keys("paid"), keys());
                    assertThat(number(paidAll, "count")).isEqualByComparingTo("4");
                    // 下钻同样在筛选之后：小计格、带指标条件、reportDrill
                    var drillQuery =
                            new ApplicationReports.Query(
                                    app,
                                    "report",
                                    null,
                                    "2026-08-01",
                                    "2026-09-30",
                                    null,
                                    keys("paid"),
                                    1,
                                    100,
                                    null,
                                    null,
                                    keys());
                    assertThat(reports.details(drillQuery, 10001).getTotal()).isEqualTo(4);
                    var metricDrill =
                            new ApplicationReports.Query(
                                    app,
                                    "report",
                                    null,
                                    "2026-08-01",
                                    "2026-09-30",
                                    null,
                                    keys("paid"),
                                    1,
                                    100,
                                    null,
                                    "big",
                                    keys());
                    assertThat(reports.details(metricDrill, 10001).getTotal()).isEqualTo(2);
                    var page =
                            viewDrill(
                                    app,
                                    d,
                                    "view_all",
                                    null,
                                    new ApplicationReports.Drill(
                                            app,
                                            "report",
                                            keys("paid"),
                                            keys(),
                                            "big",
                                            null,
                                            "2026-08-01",
                                            "2026-09-30",
                                            null,
                                            null),
                                    10001);
                    assertThat(page.total()).isEqualTo(2);
                    assertThat(page.drillTotal()).isEqualTo(2);
                    // 用户等值筛选（开放字段）
                    var filtered =
                            reports.query(
                                    new ApplicationReports.Query(
                                            app,
                                            "report",
                                            Map.of(field(d, "name"), "a"),
                                            "2026-08-01",
                                            "2026-09-30",
                                            null,
                                            null,
                                            1,
                                            100),
                                    10001);
                    assertThat(number(cell(filtered, keys(), keys()), "count"))
                            .isEqualByComparingTo("4");
                });
    }

    // ---------------------------------------------------------------- 权限

    private void sharing(
            String app, DataCenter.Definition d, ApplicationAuthorization.ObjectGrant grant) {
        var s = servicesContext.getBean(ObjectSharingService.class);
        int revision =
                s.forApplication(app).stream()
                        .filter(g -> g.objectId().equals(d.objectId()))
                        .findFirst()
                        .orElseThrow()
                        .revision();
        s.save(new ObjectSharing.Save(d.objectId(), app, revision, grant, "透视授权回归"), 10001);
    }

    private ApplicationAuthorization.ObjectGrant grant(
            DataCenter.Definition d, Set<String> actions, String scope, Set<String> fields) {
        return new ApplicationAuthorization.ObjectGrant(
                d.objectId(), actions, scope, fields, Set.of(), Set.of(), Set.of());
    }

    /** 只看本人：透视各层与两种下钻都只含本人记录；列维度字段无权时整张报表拒绝，不能推算隐藏数据。 */
    @Test
    void recordAndFieldPermissionsApplyBeforeAggregationAndDrill() {
        rollback(
                () -> {
                    var d = object();
                    var config =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE")),
                                    List.of(dim(d, "booked", "MONTH")),
                                    metrics(d),
                                    null,
                                    30,
                                    "view_all",
                                    null);
                    String app =
                            app(
                                    d,
                                    resource("report", "REPORT", config),
                                    resource("view_all", "VIEW", view(d, Map.of())));
                    var seeded = seed(app, d);
                    for (var own : List.of(seeded.get(0), seeded.get(4)))
                        jdbc.update(
                                "UPDATE public.\""
                                        + d.tableName()
                                        + "\" SET creator='20002' WHERE id=?",
                                Long.parseLong(own.id()));
                    var fields =
                            d.fields().stream()
                                    .map(FieldDefinition::id)
                                    .collect(Collectors.toSet());
                    servicesContext
                            .getBean(ApplicationAuthorizationService.class)
                            .save(
                                    new ApplicationAuthorization.Save(
                                            app,
                                            0,
                                            List.of(
                                                    new ApplicationAuthorization.Member(
                                                            "USER",
                                                            "20002",
                                                            List.of(
                                                                    grant(
                                                                            d,
                                                                            Set.of("READ"),
                                                                            "OWN",
                                                                            fields))))),
                                    10001);
                    var r = reports.query(query(app), 20002);
                    assertThat(r.recordCount()).isEqualTo(2);
                    assertThat(number(cell(r, keys(), keys()), "amount"))
                            .isEqualByComparingTo("110");
                    assertThat(number(cell(r, keys("paid"), keys()), "count"))
                            .isEqualByComparingTo("1");
                    assertThat(r.canExport()).isFalse();
                    var own = Set.of(seeded.get(0).id(), seeded.get(4).id());
                    assertThat(ids(reports.details(drill(app, keys(), keys()), 20002).getList()))
                            .isEqualTo(own);
                    var page =
                            viewDrill(
                                    app,
                                    d,
                                    "view_all",
                                    null,
                                    reportDrill(app, keys(), keys()),
                                    20002);
                    assertThat(ids(page.list())).isEqualTo(own);
                    assertThat(page.drillTotal()).isEqualTo(2);
                    // 列维度字段（日期）不在授权字段内：统计与下钻都拒绝
                    var hidden = new HashSet<>(fields);
                    hidden.remove(field(d, "booked"));
                    sharing(app, d, grant(d, Set.of("READ", "EXPORT"), "ALL", hidden));
                    assertThatThrownBy(() -> reports.query(query(app), 10001))
                            .hasMessageContaining("未授权字段");
                    assertThatThrownBy(
                                    () ->
                                            viewDrill(
                                                    app,
                                                    d,
                                                    "view_all",
                                                    null,
                                                    reportDrill(app, keys(), keys()),
                                                    10001))
                            .hasMessageContaining("未授权字段");
                });
    }

    // ---------------------------------------------------------------- 导出、预览、配置投影

    /** 真实 SQL 到 XLSX：平均/去重重新聚合，负数、高精度、空值与零分母不被展示层改写。 */
    @Test
    void numericBoundaryResultsMatchActualWorkbookCells() {
        rollback(
                () -> {
                    DataCenter.Definition d = object();
                    String app =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            pivot(
                                                    d,
                                                    List.of(dim(d, "status", "VALUE")),
                                                    List.of(dim(d, "booked", "MONTH")),
                                                    metrics(d),
                                                    new ApplicationReports.Pivot(
                                                            true, true, true, "ROW", 24),
                                                    30,
                                                    null,
                                                    null)));
                    row(app, d, "a", "10", "pair", "2026-08-01");
                    row(app, d, "b", "20", "pair", "2026-08-01");
                    row(app, d, "c", "-5.1250", "negative", "2026-08-01");
                    row(app, d, "d", "12345678901234567890.1234", "precise", "2026-08-01");
                    row(app, d, "e", null, "empty", "2026-08-01");
                    row(app, d, "f", "0", "zero", "2026-08-01");
                    ApplicationReports.Result result = reports.query(query(app), 10001);
                    assertThat(result.recordCount()).isEqualTo(6);
                    ApplicationReports.PivotCell pair = cell(result, keys("pair"), keys("2026-08"));
                    assertThat(number(pair, "average")).isEqualByComparingTo("15");
                    assertThat(number(pair, "days")).isEqualByComparingTo("1");
                    assertThat(number(pair, "per")).isEqualByComparingTo("15");
                    assertThat(number(cell(result, keys(), keys()), "days"))
                            .isEqualByComparingTo("1");
                    assertThat(number(cell(result, keys(), keys()), "amount"))
                            .isEqualByComparingTo("12345678901234567914.9984");
                    assertThat(cell(result, keys("zero"), keys("2026-08")).ratios())
                            .containsEntry("amount", null);
                    // 旧聚合契约：全空 SUM 为 0，AVG 仍为空，Excel 必须保持这一差异。
                    assertThat(cell(result, keys("empty"), keys("2026-08")).values())
                            .containsEntry("amount", "0")
                            .containsEntry("average", null);
                    com.lingan.ucp.nocode.web.RecordExcelService excel =
                            servicesContext.getBean(
                                    com.lingan.ucp.nocode.web.RecordExcelService.class);
                    byte[] bytes = excel.report(query(app), 10001);
                    try (org.apache.poi.ss.usermodel.Workbook workbook =
                            org.apache.poi.ss.usermodel.WorkbookFactory.create(
                                    new java.io.ByteArrayInputStream(bytes))) {
                        org.apache.poi.ss.usermodel.Sheet sheet = workbook.getSheetAt(0);
                        Map<String, org.apache.poi.ss.usermodel.Row> rows = new HashMap<>();
                        for (org.apache.poi.ss.usermodel.Row row : sheet) {
                            if (row.getRowNum() >= 2 && row.getCell(0) != null)
                                rows.put(row.getCell(0).toString(), row);
                        }
                        for (String group :
                                List.of("pair", "negative", "precise", "empty", "zero")) {
                            org.apache.poi.ss.usermodel.Row row = rows.get(group);
                            assertThat(row).as("导出行 %s", group).isNotNull();
                            ApplicationReports.PivotCell cell =
                                    cell(result, keys(group), keys("2026-08"));
                            for (int index = 0; index < result.metrics().size(); index++) {
                                String id = result.metrics().get(index).id();
                                org.apache.poi.ss.usermodel.Cell value = row.getCell(1 + 2 * index);
                                org.apache.poi.ss.usermodel.Cell ratio = row.getCell(2 + 2 * index);
                                assertThat(value == null ? "" : value.toString())
                                        .isEqualTo(Objects.toString(cell.values().get(id), ""));
                                assertThat(ratio == null ? "" : ratio.toString())
                                        .isEqualTo(Objects.toString(cell.ratios().get(id), ""));
                            }
                        }
                        assertThat(rows.get("precise").getCell(1).getCellType())
                                .isEqualTo(org.apache.poi.ss.usermodel.CellType.STRING);
                        assertThat(rows.get("precise").getCell(1).getStringCellValue())
                                .isEqualTo("12345678901234567890.1234");
                        assertThat(new BigDecimal(rows.get("合计").getCell(1).toString()))
                                .isEqualByComparingTo("12345678901234567914.9984");
                    } catch (java.io.IOException error) {
                        throw new AssertionError("实际 XLSX 无法读取", error);
                    }
                });
    }

    /** 导出为两层表头（列组 → 指标），行表头按行维度分列，小计/合计行列与屏幕一致。 */
    @Test
    void pivotExportWritesTwoLevelHeaders() {
        rollback(
                () -> {
                    var d = object();
                    var metrics =
                            List.of(
                                    new ApplicationReports.Metric(
                                            "amount", "金额", "SUM", field(d, "amount")),
                                    new ApplicationReports.Metric("count", "笔数", "COUNT", null));
                    var config =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE"), dim(d, "name", "VALUE")),
                                    List.of(dim(d, "booked", "MONTH")),
                                    metrics,
                                    null,
                                    30,
                                    null,
                                    null);
                    String app = app(d, resource("report", "REPORT", config));
                    seed(app, d);
                    var excel =
                            servicesContext.getBean(
                                    com.lingan.ucp.nocode.web.RecordExcelService.class);
                    byte[] file = excel.report(query(app), 10001);
                    List<Map<Integer, String>> sheet =
                            cn.idev.excel.FastExcelFactory.read(
                                            new java.io.ByteArrayInputStream(file))
                                    .headRowNumber(0)
                                    .sheet()
                                    .doReadSync();
                    var top = sheet.get(0);
                    var second = sheet.get(1);
                    assertThat(top.get(0)).isEqualTo("状态");
                    assertThat(top.get(1)).isEqualTo("名称");
                    assertThat(top.get(2)).isEqualTo("2026-08");
                    assertThat(second.get(2)).isEqualTo("金额");
                    assertThat(second.get(3)).isEqualTo("笔数");
                    assertThat(top.get(4)).isEqualTo("2026-09");
                    assertThat(top.get(6)).isEqualTo("合计");
                    // 第一行数据：paid/a
                    var first = sheet.get(2);
                    assertThat(first.get(0)).isEqualTo("paid");
                    assertThat(first.get(1)).isEqualTo("a");
                    assertThat(new BigDecimal(first.get(2))).isEqualByComparingTo("30");
                    assertThat(new BigDecimal(first.get(6))).isEqualByComparingTo("70");
                    // paid 小计行紧随 paid/b
                    var subtotal = sheet.get(4);
                    assertThat(subtotal.get(0)).isEqualTo("paid");
                    assertThat(subtotal.get(1)).isEqualTo("小计");
                    assertThat(new BigDecimal(subtotal.get(2))).isEqualByComparingTo("60");
                    // 合计行在所有数据行之后
                    var total =
                            sheet.stream()
                                    .filter(line -> "合计".equals(line.get(0)))
                                    .findFirst()
                                    .orElseThrow();
                    assertThat(new BigDecimal(total.get(6))).isEqualByComparingTo("400");
                });
    }

    /** 运行端配置投影带出透视设置与明细可编辑；设计预览同样走透视查询。 */
    @Test
    void runtimeConfigCarriesPivotSettingsAndPreviewUsesPivot() {
        rollback(
                () -> {
                    var d = object();
                    var base =
                            pivot(
                                    d,
                                    List.of(dim(d, "status", "VALUE")),
                                    List.of(dim(d, "booked", "MONTH")),
                                    metrics(d),
                                    new ApplicationReports.Pivot(true, false, true, "TOTAL", 12),
                                    30,
                                    "view_all",
                                    null);
                    var config =
                            new ApplicationReports.Config(
                                    base.objectId(),
                                    base.dimensions(),
                                    base.metrics(),
                                    base.equal(),
                                    base.filterFieldIds(),
                                    base.dateFieldId(),
                                    base.timeZone(),
                                    base.display(),
                                    null,
                                    false,
                                    30,
                                    "view_all",
                                    null,
                                    null,
                                    base.columnDimensions(),
                                    base.pivot(),
                                    true);
                    String app =
                            app(
                                    d,
                                    resource("report", "REPORT", config),
                                    resource("view_all", "VIEW", view(d, Map.of())));
                    seed(app, d);
                    var runtime =
                            servicesContext
                                    .getBean(
                                            com.lingan.ucp.nocode.runtime.service.application
                                                    .ApplicationRuntimeService.class)
                                    .application(app, 10001);
                    var projected =
                            mapper.convertValue(
                                    runtime.definition().resources().stream()
                                            .filter(res -> res.id().equals("report"))
                                            .findFirst()
                                            .orElseThrow()
                                            .config(),
                                    ApplicationReports.Config.class);
                    assertThat(projected.display()).isEqualTo("PIVOT");
                    assertThat(projected.columnDimensions()).isEqualTo(base.columnDimensions());
                    assertThat(projected.pivot())
                            .isEqualTo(
                                    new ApplicationReports.Pivot(true, false, true, "TOTAL", 12));
                    assertThat(projected.detailEditable()).isTrue();
                    var v = objects.getVersion(d.objectId(), null);
                    var preview =
                            reports.preview(
                                    new ApplicationReports.Preview(
                                            app,
                                            List.of(
                                                    new ApplicationCenter.ObjectReference(
                                                            v.objectId(),
                                                            v.versionNo(),
                                                            v.checksum())),
                                            config,
                                            List.of(
                                                    resource(
                                                            "view_all",
                                                            "VIEW",
                                                            view(d, Map.of())))),
                                    10001);
                    assertThat(preview.pivot()).isNotNull();
                    assertThat(new BigDecimal(preview.totals().get("amount")))
                            .isEqualByComparingTo("400");
                    // 行合计列组关闭：不输出 columnKeys = [] 的格；占比 TOTAL 开启
                    assertThat(preview.pivot().cells())
                            .isNotEmpty()
                            .noneMatch(c -> c.columnKeys().isEmpty())
                            .allMatch(c -> c.ratios() != null);
                });
    }
}
