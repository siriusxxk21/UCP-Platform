package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.resource.ApplicationReportValidator;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.report.ApplicationReportService;

import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

/**
 * 透视表维度数量放开（2026-09-30）：行维度 ≥1、列维度 ≥0，不再各限 2 个；只保留「行 + 列 ≤ {@link
 * ApplicationReports#MAX_PIVOT_DIMENSIONS}」。非透视展示方式仍最多 2 个分组。
 *
 * <p>核心钉子：3 行维度 × 3 列维度时，叶子、每一层小计、合计逐格等于直接 SQL（GROUPING SETS 全部「行前缀 × 列前缀」）的结果； 任意层下钻条数 =
 * 格上的记录数；导出表头层数 = 列维度数 + 1 且按层合并。真实开发库，每例整体回滚。
 */
class ReportPivotDimensionsIntegrationTest {
    private static final String[] ROWS = {"status", "region", "name"};
    private static final List<String> METRICS =
            List.of("amount", "count", "days", "average", "per");

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

    private static FieldDefinition text(String code, String name, int order) {
        return new FieldDefinition(
                code, null, code, name, "TEXT", 100, null, null, order == 0, false, order);
    }

    /** 八个字段：足够凑出 11 个互不相同的维度（同一日期字段不同分桶算不同维度）。 */
    private DataCenter.Definition object() {
        String suffix = "pdim" + serial++;
        var request =
                new SaveObjectDraft(
                        null,
                        null,
                        fixture.prefix + suffix,
                        "透视多维",
                        null,
                        "biz_" + fixture.prefix + suffix,
                        "name",
                        List.of(
                                text("name", "名称", 0),
                                new FieldDefinition(
                                        "amount", null, "amount", "金额", "DECIMAL", null, 30, 4,
                                        false, false, 1),
                                text("status", "状态", 2),
                                text("region", "区域", 3),
                                text("category", "类别", 4),
                                new FieldDefinition(
                                        "booked", null, "booked", "日期", "DATE", null, null, null,
                                        false, false, 5),
                                text("code", "编号", 6),
                                text("extra", "备注", 7)),
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
        assertThat(publisher.execute(new DataCenter.ExecutePlan(plan.id(), "透视多维"), 10001).state())
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
                "透视多维 " + id,
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
                                "透视多维验收",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        v.objectId(), v.versionNo(), v.checksum())),
                                        List.of(resources))),
                        10001);
        grantApplicationObjects(a.application().id());
        apps.publish(new ApplicationCenter.Revision(a.application().id(), 0, "透视多维"), 10001);
        return a.application().id();
    }

    private void row(String app, DataCenter.Definition d, String... values) {
        // status, region, name, category, booked, amount, code
        String[] codes = {"status", "region", "name", "category", "booked", "amount", "code"};
        Map<String, Object> data = new LinkedHashMap<>();
        for (int i = 0; i < codes.length; i++)
            if (i < values.length && values[i] != null) data.put(field(d, codes[i]), values[i]);
        records.save(new ApplicationRecords.Save(app, d.objectId(), null, null, data, null), 10001);
    }

    /**
     * 十条记录（状态, 区域, 名称, 类别, 日期, 金额）。paid/east 在 2026 年有 a、b 两个名称但同一天（01-05）， 用于「去重计数的小计 ≠ 叶子相加」；#9
     * 金额为空、#10 区域为空，用于平均与空值键。
     */
    private void seed(String app, DataCenter.Definition d) {
        row(app, d, "paid", "east", "a", "x", "2025-12-03", "10", "k1");
        row(app, d, "paid", "east", "a", "x", "2026-01-05", "20", "k2");
        row(app, d, "paid", "east", "b", "y", "2026-01-05", "30", "k3");
        row(app, d, "paid", "west", "a", "x", "2026-01-20", "40", "k4");
        row(app, d, "paid", "west", "c", "y", "2026-02-01", "50", "k5");
        row(app, d, "unpaid", "east", "a", "x", "2026-01-05", "60", "k6");
        row(app, d, "unpaid", "west", "b", "y", "2025-12-03", "70", "k7");
        row(app, d, "unpaid", "west", "b", "x", "2026-02-01", "80", "k8");
        row(app, d, "paid", "east", "b", "x", "2026-01-05", null, "k9");
        row(app, d, "unpaid", null, "a", "y", "2026-02-14", "5", "k10");
    }

    private ApplicationReports.Dimension dim(DataCenter.Definition d, String code, String bucket) {
        return new ApplicationReports.Dimension(field(d, code), null, bucket);
    }

    private List<ApplicationReports.Dimension> rows(DataCenter.Definition d) {
        return Arrays.stream(ROWS).map(code -> dim(d, code, "VALUE")).toList();
    }

    private List<ApplicationReports.Dimension> columns(DataCenter.Definition d) {
        return List.of(
                dim(d, "booked", "YEAR"), dim(d, "booked", "MONTH"), dim(d, "category", "VALUE"));
    }

    /** 求和、计数、去重计数（日期）、平均、计算指标（求和 ÷ 计数）。 */
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

    private ApplicationReports.Config config(
            DataCenter.Definition d,
            String display,
            List<ApplicationReports.Dimension> rows,
            List<ApplicationReports.Dimension> columns,
            List<ApplicationReports.Metric> metrics,
            String sortMetricId,
            boolean descending) {
        boolean pivot = "PIVOT".equals(display);
        return new ApplicationReports.Config(
                d.objectId(),
                rows,
                metrics,
                Map.of(),
                List.of(field(d, "status")),
                field(d, "booked"),
                "Asia/Shanghai",
                display,
                sortMetricId,
                descending,
                200,
                null,
                null,
                null,
                pivot ? columns : null,
                pivot ? new ApplicationReports.Pivot(true, true, true, "NONE", 100) : null,
                null);
    }

    /** 同 {@link #config}，但行组上限与透视开关（列组上限）由调用方给定。 */
    private ApplicationReports.Config limited(
            DataCenter.Definition d, int rowLimit, ApplicationReports.Pivot settings) {
        return new ApplicationReports.Config(
                d.objectId(),
                rows(d),
                metrics(d),
                Map.of(),
                List.of(field(d, "status")),
                field(d, "booked"),
                "Asia/Shanghai",
                "PIVOT",
                null,
                false,
                rowLimit,
                null,
                null,
                null,
                columns(d),
                settings,
                null);
    }

    private ApplicationReports.Config pivot(
            DataCenter.Definition d,
            List<ApplicationReports.Dimension> rows,
            List<ApplicationReports.Dimension> columns) {
        return config(d, "PIVOT", rows, columns, metrics(d), null, false);
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

    private static List<List<String>> at(List<String> rows, List<String> columns) {
        return Arrays.asList(new ArrayList<>(rows), new ArrayList<>(columns));
    }

    private static Map<List<List<String>>, Map<String, String>> byKeys(
            ApplicationReports.Result r) {
        Map<List<List<String>>, Map<String, String>> result = new HashMap<>();
        for (var c : r.pivot().cells()) result.put(at(c.rowKeys(), c.columnKeys()), c.values());
        return result;
    }

    private static BigDecimal number(Map<String, String> values, String metric) {
        var value = values.get(metric);
        return value == null ? null : new BigDecimal(value);
    }

    /**
     * 独立于被测 SQL 的直接聚合：同一张业务表，GROUP BY GROUPING SETS（行前缀 × 列前缀，共 4×4=16 组）， 键按 GROUPING
     * 标志截成前缀。指标写法与报表口径一致：求和空组为 0，计数，去重计数，平均，求和÷计数（除数为 0 为空）。
     */
    private Map<List<List<String>>, Map<String, String>> direct(DataCenter.Definition d) {
        String[] rowExpr = {
            column(d, "status") + "::text",
            column(d, "region") + "::text",
            column(d, "name") + "::text"
        };
        String[] columnExpr = {
            "to_char(" + column(d, "booked") + ", 'YYYY')",
            "to_char(" + column(d, "booked") + ", 'YYYY-MM')",
            column(d, "category") + "::text"
        };
        List<String> sets = new ArrayList<>();
        for (int r = 3; r >= 0; r--)
            for (int c = 3; c >= 0; c--) {
                List<String> set = new ArrayList<>();
                for (int i = 0; i < r; i++) set.add("d" + i);
                for (int i = 0; i < c; i++) set.add("c" + i);
                sets.add("(" + String.join(",", set) + ")");
            }
        String sql =
                "SELECT GROUPING(d0) g0, GROUPING(d1) g1, GROUPING(d2) g2, GROUPING(c0) h0,"
                    + " GROUPING(c1) h1, GROUPING(c2) h2, d0, d1, d2, c0, c1, c2, COALESCE(SUM(v),"
                    + " 0)::text AS amount, COUNT(*)::text AS count, COUNT(DISTINCT b)::text AS"
                    + " days, AVG(v)::text AS average, (COALESCE(SUM(v), 0)::numeric /"
                    + " NULLIF(COUNT(*)::numeric, 0))::text AS per FROM (SELECT "
                        + rowExpr[0]
                        + " d0, "
                        + rowExpr[1]
                        + " d1, "
                        + rowExpr[2]
                        + " d2, "
                        + columnExpr[0]
                        + " c0, "
                        + columnExpr[1]
                        + " c1, "
                        + columnExpr[2]
                        + " c2, "
                        + column(d, "amount")
                        + " v, "
                        + column(d, "booked")
                        + " b FROM public.\""
                        + d.tableName()
                        + "\") s GROUP BY GROUPING SETS ("
                        + String.join(",", sets)
                        + ")";
        Map<List<List<String>>, Map<String, String>> result = new HashMap<>();
        jdbc.query(
                sql,
                rs -> {
                    List<String> rowKeys = new ArrayList<>(), columnKeys = new ArrayList<>();
                    for (int i = 0; i < 3 && rs.getInt("g" + i) == 0; i++)
                        rowKeys.add(rs.getString("d" + i));
                    for (int i = 0; i < 3 && rs.getInt("h" + i) == 0; i++)
                        columnKeys.add(rs.getString("c" + i));
                    Map<String, String> values = new HashMap<>();
                    for (var metric : METRICS) values.put(metric, rs.getString(metric));
                    result.put(at(rowKeys, columnKeys), values);
                });
        return result;
    }

    // ---------------------------------------------------------------- 3 行维度 × 3 列维度

    /** 叶子、每一层行小计与列小计、行合计列组、列合计行、总计，逐格等于直接 SQL（GROUPING SETS）；没有多余或缺失的格。 */
    @Test
    void threeByThreePivotMatchesDirectGroupingSetsCellByCell() {
        rollback(
                () -> {
                    var d = object();
                    String app =
                            app(d, resource("report", "REPORT", pivot(d, rows(d), columns(d))));
                    seed(app, d);
                    var r = reports.query(query(app), 10001);
                    var p = r.pivot();
                    assertThat(p.rowDimensionNames()).containsExactly("状态", "区域", "名称");
                    assertThat(p.columnDimensionNames()).containsExactly("日期", "日期", "类别");
                    assertThat(p.rows()).allMatch(h -> h.keys().size() == 3);
                    assertThat(p.columns()).allMatch(h -> h.keys().size() == 3);
                    assertThat(p.columns())
                            .extracting(ApplicationReports.PivotHeader::keys)
                            .containsExactly(
                                    keys("2025", "2025-12", "x"),
                                    keys("2025", "2025-12", "y"),
                                    keys("2026", "2026-01", "x"),
                                    keys("2026", "2026-01", "y"),
                                    keys("2026", "2026-02", "x"),
                                    keys("2026", "2026-02", "y"));
                    var actual = byKeys(r);
                    var expected = direct(d);
                    // 16 组分组集合的每一层都在：行前缀长度 0..3 × 列前缀长度 0..3
                    for (int rl = 0; rl <= 3; rl++)
                        for (int cl = 0; cl <= 3; cl++) {
                            int rowLength = rl, columnLength = cl;
                            assertThat(actual.keySet())
                                    .as("层级 行%d × 列%d", rl, cl)
                                    .anyMatch(
                                            k ->
                                                    k.get(0).size() == rowLength
                                                            && k.get(1).size() == columnLength);
                        }
                    assertThat(actual.keySet()).isEqualTo(expected.keySet());
                    for (var entry : expected.entrySet())
                        for (var metric : METRICS) {
                            var want = number(entry.getValue(), metric);
                            var got = number(actual.get(entry.getKey()), metric);
                            if (want == null)
                                assertThat(got).as("%s %s", entry.getKey(), metric).isNull();
                            else
                                assertThat(got)
                                        .as("%s %s", entry.getKey(), metric)
                                        .isEqualByComparingTo(want);
                        }
                    assertThat(new BigDecimal(r.totals().get("amount")))
                            .isEqualByComparingTo("365");
                    assertThat(r.recordCount()).isEqualTo(10);
                });
    }

    /** 反例：第 2 层行小计、第 1 层行小计、第 2 层列小计的去重计数/平均/公式都不等于下一层相加，而等于按原始记录重算。 */
    @Test
    void deeperSubtotalsAreRecomputedNotSummedAtEveryLevel() {
        rollback(
                () -> {
                    var d = object();
                    String app =
                            app(d, resource("report", "REPORT", pivot(d, rows(d), columns(d))));
                    seed(app, d);
                    var cells = byKeys(reports.query(query(app), 10001));
                    // 第 2 层行小计 paid/east × 列第 1 层 2026：#2 #3 #9，金额 50、3 笔、1 天、平均 25、每笔 50/3
                    var sub = cells.get(at(keys("paid", "east"), keys("2026")));
                    var a = cells.get(at(keys("paid", "east", "a"), keys("2026")));
                    var b = cells.get(at(keys("paid", "east", "b"), keys("2026")));
                    assertThat(number(sub, "amount")).isEqualByComparingTo("50");
                    assertThat(number(sub, "count")).isEqualByComparingTo("3");
                    assertThat(number(sub, "days")).isEqualByComparingTo("1");
                    assertThat(number(sub, "average")).isEqualByComparingTo("25");
                    assertThat(number(sub, "per").doubleValue())
                            .isCloseTo(16.6666667, within(1e-6));
                    for (var metric : List.of("days", "average", "per"))
                        assertThat(number(sub, metric))
                                .as("第 2 层行小计 %s 不能是叶子相加", metric)
                                .isNotEqualByComparingTo(number(a, metric).add(number(b, metric)));
                    // 第 1 层行小计 paid × 列第 2 层 2026/2026-01：#2 #3 #4 #9，平均 30，每笔 22.5
                    var top = cells.get(at(keys("paid"), keys("2026", "2026-01")));
                    var east = cells.get(at(keys("paid", "east"), keys("2026", "2026-01")));
                    var west = cells.get(at(keys("paid", "west"), keys("2026", "2026-01")));
                    assertThat(number(top, "average")).isEqualByComparingTo("30");
                    assertThat(number(top, "per")).isEqualByComparingTo("22.5");
                    for (var metric : List.of("average", "per"))
                        assertThat(number(top, metric))
                                .as("第 1 层行小计 %s 不能是下一层相加", metric)
                                .isNotEqualByComparingTo(
                                        number(east, metric).add(number(west, metric)));
                    // 第 2 层列小计 paid × 2026/2026-01：x 类 2 天（01-05、01-20）、y 类 1 天，合起来仍是 2 天
                    var x = cells.get(at(keys("paid"), keys("2026", "2026-01", "x")));
                    var y = cells.get(at(keys("paid"), keys("2026", "2026-01", "y")));
                    assertThat(number(top, "days")).isEqualByComparingTo("2");
                    assertThat(number(top, "days"))
                            .as("第 2 层列小计 days 不能是叶子相加")
                            .isNotEqualByComparingTo(number(x, "days").add(number(y, "days")));
                    // 总计：10 笔、5 个不同日期、平均 365/9（#9 金额为空不计入平均）
                    var grand = cells.get(at(keys(), keys()));
                    assertThat(number(grand, "count")).isEqualByComparingTo("10");
                    assertThat(number(grand, "days")).isEqualByComparingTo("5");
                    assertThat(number(grand, "average").doubleValue())
                            .isCloseTo(40.5555556, within(1e-6));
                });
    }

    /** 任意层的格（叶子、每层小计、合计）下钻条数都等于格上的记录数；三段前缀可用，第四段越界拒绝。 */
    @Test
    void everyCellAtEveryLevelDrillsToItsOwnRecordCount() {
        rollback(
                () -> {
                    var d = object();
                    String app =
                            app(d, resource("report", "REPORT", pivot(d, rows(d), columns(d))));
                    seed(app, d);
                    var r = reports.query(query(app), 10001);
                    Set<String> levels = new TreeSet<>();
                    for (var c : r.pivot().cells()) {
                        assertThat(
                                        reports.details(
                                                        drill(app, c.rowKeys(), c.columnKeys()),
                                                        10001)
                                                .getTotal())
                                .as("下钻 %s × %s", c.rowKeys(), c.columnKeys())
                                .isEqualTo(new BigDecimal(c.values().get("count")).longValue());
                        levels.add(c.rowKeys().size() + "x" + c.columnKeys().size());
                    }
                    assertThat(levels).hasSize(16);
                    // 第 2 层行小计 × 第 2 层列小计：paid/east × 2026/2026-01 = #2 #3 #9
                    assertThat(
                                    reports.details(
                                                    drill(
                                                            app,
                                                            keys("paid", "east"),
                                                            keys("2026", "2026-01")),
                                                    10001)
                                            .getTotal())
                            .isEqualTo(3);
                    // 空值键（区域未填写）同样可下钻
                    assertThat(
                                    reports.details(
                                                    drill(
                                                            app,
                                                            keys("unpaid", null),
                                                            keys("2026", "2026-02", "y")),
                                                    10001)
                                            .getTotal())
                            .isEqualTo(1);
                    assertThatThrownBy(
                                    () ->
                                            reports.details(
                                                    drill(
                                                            app,
                                                            keys("paid", "east", "a", "z"),
                                                            null),
                                                    10001))
                            .hasMessageContaining("下钻分组键无效");
                    assertThatThrownBy(
                                    () ->
                                            reports.details(
                                                    drill(
                                                            app,
                                                            null,
                                                            keys("2026", "2026-01", "x", "z")),
                                                    10001))
                            .hasMessageContaining("下钻列键无效");
                });
    }

    /** 三层行维度设排序指标：先按第 1 层分组的该指标、再第 2 层分组、再叶子（均取合计列组上的值），同值按原值升序；空值最后。 */
    @Test
    void sortMetricOrdersThreeRowLevelsHierarchically() {
        rollback(
                () -> {
                    var d = object();
                    var config =
                            config(d, "PIVOT", rows(d), columns(d), metrics(d), "amount", true);
                    String app = app(d, resource("report", "REPORT", config));
                    seed(app, d);
                    var r = reports.query(query(app), 10001);
                    var cells = byKeys(r);
                    var actual =
                            r.pivot().rows().stream()
                                    .map(ApplicationReports.PivotHeader::keys)
                                    .toList();
                    Comparator<String> key = Comparator.nullsLast(Comparator.naturalOrder());
                    Comparator<List<String>> order = (x, y) -> 0;
                    for (int level = 1; level <= 3; level++) {
                        int length = level;
                        Comparator<List<String>> byMetric =
                                Comparator.comparing(
                                        (List<String> k) ->
                                                number(
                                                        cells.get(at(k.subList(0, length), keys())),
                                                        "amount"),
                                        Comparator.nullsLast(
                                                Comparator.<BigDecimal>reverseOrder()));
                        order =
                                order.thenComparing(byMetric)
                                        .thenComparing(k -> k.get(length - 1), key);
                    }
                    var expected = new ArrayList<>(actual);
                    expected.sort(order);
                    assertThat(actual).isEqualTo(expected);
                    // 第 1 层：unpaid（215）排在 paid（150）之前
                    assertThat(actual.getFirst().getFirst()).isEqualTo("unpaid");
                    // 与按原值升序（未设排序指标）的顺序不同，确认排序真的生效
                    var plain =
                            reports
                                    .query(
                                            query(
                                                    app(
                                                            d,
                                                            resource(
                                                                    "report",
                                                                    "REPORT",
                                                                    pivot(
                                                                            d,
                                                                            rows(d),
                                                                            columns(d))))),
                                            10001)
                                    .pivot()
                                    .rows()
                                    .stream()
                                    .map(ApplicationReports.PivotHeader::keys)
                                    .toList();
                    assertThat(plain)
                            .isNotEqualTo(actual)
                            .containsExactlyInAnyOrderElementsOf(actual);
                });
    }

    // ---------------------------------------------------------------- 截断下的保留格

    /**
     * 行、列都截断，且被保留/被截掉的叶子都带空值键：返回的格恰好是「行前缀属于某个已展示行叶子 × 列前缀属于某个已展示列叶子」的全部分组集合格， 不多不少；每格数值仍等于直接 SQL
     * 按原始记录的聚合（被截掉叶子的记录照样计入其上层小计与合计）。
     *
     * <p>专门区分「汇总掉的层」与「值为空的层」：被截掉的行叶子 空/空/a 的第 1 层前缀「状态为空」与行合计只看键值都是空， 被截掉的列叶子 空/空/空
     * 与列合计全是空值；把两者混同就会多出格子，前缀比对错位就会缺格子。（名称为必填，行叶子第 3 层不能为空。）
     */
    @Test
    void truncatedPivotKeepsExactlyTheCellsOfShownLeavesAtEveryLevel() {
        rollback(
                () -> {
                    var d = object();
                    String app =
                            app(
                                    d,
                                    resource(
                                            "report",
                                            "REPORT",
                                            limited(
                                                    d,
                                                    7,
                                                    new ApplicationReports.Pivot(
                                                            true, true, true, "NONE", 3))));
                    seed(app, d);
                    row(app, d, "unpaid", null, "b", "x", "2026-01-20", "7", "k11");
                    row(app, d, null, null, "a", "y", "2025-12-03", "3", "k12");
                    row(app, d, "paid", "east", "a", null, "2025-12-20", "11", "k13");
                    row(app, d, "paid", "west", "c", null, null, "13", "k14");
                    var r = reports.query(query(app), 10001);
                    var p = r.pivot();
                    var shownRows =
                            List.of(
                                    keys("paid", "east", "a"),
                                    keys("paid", "east", "b"),
                                    keys("paid", "west", "a"),
                                    keys("paid", "west", "c"),
                                    keys("unpaid", "east", "a"),
                                    keys("unpaid", "west", "b"),
                                    keys("unpaid", null, "a"));
                    var shownColumns =
                            List.of(
                                    keys("2025", "2025-12", "x"),
                                    keys("2025", "2025-12", "y"),
                                    keys("2025", "2025-12", null));
                    assertThat(p.rows())
                            .extracting(ApplicationReports.PivotHeader::keys)
                            .containsExactlyElementsOf(shownRows);
                    assertThat(p.columns())
                            .extracting(ApplicationReports.PivotHeader::keys)
                            .containsExactlyElementsOf(shownColumns);
                    assertThat(p.rowsTruncated()).isTrue();
                    assertThat(p.columnsTruncated()).isTrue();
                    assertThat(p.totalRowGroups()).isEqualTo(9);
                    assertThat(p.totalColumnGroups()).isEqualTo(8);
                    var expected = new HashMap<List<List<String>>, Map<String, String>>();
                    for (var entry : direct(d).entrySet()) {
                        var rowKeys = entry.getKey().get(0);
                        var columnKeys = entry.getKey().get(1);
                        if (shownRows.stream()
                                        .anyMatch(
                                                leaf ->
                                                        leaf.subList(0, rowKeys.size())
                                                                .equals(rowKeys))
                                && shownColumns.stream()
                                        .anyMatch(
                                                leaf ->
                                                        leaf.subList(0, columnKeys.size())
                                                                .equals(columnKeys)))
                            expected.put(entry.getKey(), entry.getValue());
                    }
                    var actual = byKeys(r);
                    assertThat(actual.keySet())
                            .as("截断后返回的格 = 已展示叶子各层前缀的组合")
                            .isEqualTo(expected.keySet());
                    for (var entry : expected.entrySet())
                        for (var metric : METRICS) {
                            var want = number(entry.getValue(), metric);
                            var got = number(actual.get(entry.getKey()), metric);
                            if (want == null)
                                assertThat(got).as("%s %s", entry.getKey(), metric).isNull();
                            else
                                assertThat(got)
                                        .as("%s %s", entry.getKey(), metric)
                                        .isEqualByComparingTo(want);
                        }
                    // 被截掉的叶子 unpaid/空/b 的记录仍计入已展示的小计 unpaid/空（#10 #11）
                    assertThat(number(actual.get(at(keys("unpaid", null), keys())), "count"))
                            .isEqualByComparingTo("2");
                    // 被截掉的列叶子 空/空/空（#14）与 2026 年的记录仍计入行合计列组与总计
                    assertThat(number(actual.get(at(keys("paid", "west"), keys())), "amount"))
                            .isEqualByComparingTo("103");
                    assertThat(number(actual.get(at(keys(), keys())), "count"))
                            .isEqualByComparingTo("14");
                    assertThat(r.recordCount()).isEqualTo(14);
                    assertThat(actual).doesNotContainKey(at(keys(null, null, "a"), keys()));
                    assertThat(actual).doesNotContainKey(at(keys((String) null), keys()));
                    assertThat(actual).doesNotContainKey(at(keys(), keys(null, null, null)));
                });
    }

    // ---------------------------------------------------------------- 超时

    /**
     * 透视查询超时（数据库取消语句，SQLSTATE 57014）时给出可读的业务错误，而不是「系统异常」。用行级安全策略让读业务表的语句每行 休眠 1 秒、本事务语句超时设为 500
     * 毫秒，只有读业务表的透视语句会被取消（整体回滚，策略随之撤销）。
     */
    @Test
    void pivotTimeoutBecomesAReadableBusinessError() {
        rollback(
                () -> {
                    var d = object();
                    String app =
                            app(d, resource("report", "REPORT", pivot(d, rows(d), columns(d))));
                    seed(app, d);
                    String table = "public.\"" + d.tableName() + "\"";
                    jdbc.execute("ALTER TABLE " + table + " ENABLE ROW LEVEL SECURITY");
                    jdbc.execute("ALTER TABLE " + table + " FORCE ROW LEVEL SECURITY");
                    jdbc.execute(
                            "CREATE POLICY pivot_timeout_probe ON "
                                    + table
                                    + " USING (pg_sleep(1) IS NOT NULL)");
                    jdbc.execute("SET LOCAL statement_timeout = '500ms'");
                    assertThatThrownBy(() -> reports.query(query(app), 10001))
                            .isInstanceOf(
                                    com.richuang.os.framework.common.exception.ServiceException
                                            .class)
                            .hasMessage("统计数据量过大，超过 20 秒未算完，请减少维度、改用更粗的日期分组（如按月）或加筛选条件");
                });
    }

    // ---------------------------------------------------------------- 上限

    /** 行 + 列 = 10 可以保存并查询（5×5，36 组分组集合）；= 11 拒绝并说明原因；同一侧重复、行列同字段仍拒绝。 */
    @Test
    void pivotAllowsUpToTenDimensionsInTotalAndRejectsEleven() {
        rollback(
                () -> {
                    var d = object();
                    var validator = servicesContext.getBean(ApplicationReportValidator.class);
                    var defs = Map.of(d.objectId(), d);
                    var rows =
                            List.of(
                                    dim(d, "status", "VALUE"),
                                    dim(d, "region", "VALUE"),
                                    dim(d, "name", "VALUE"),
                                    dim(d, "category", "VALUE"),
                                    dim(d, "code", "VALUE"));
                    var columns =
                            List.of(
                                    dim(d, "booked", "YEAR"),
                                    dim(d, "booked", "MONTH"),
                                    dim(d, "booked", "DAY"),
                                    dim(d, "booked", "VALUE"),
                                    dim(d, "amount", "VALUE"));
                    assertThat(ApplicationReports.MAX_PIVOT_DIMENSIONS).isEqualTo(10);
                    var ten = pivot(d, rows, columns);
                    var normalized = validator.normalize(ten, defs);
                    assertThat(normalized.dimensions()).hasSize(5);
                    assertThat(normalized.columnDimensions()).hasSize(5);
                    // 9 行 + 1 列、1 行 + 9 列同样允许：只限合计数
                    var all = new ArrayList<ApplicationReports.Dimension>(rows);
                    all.addAll(columns);
                    validator.normalize(pivot(d, all.subList(0, 9), all.subList(9, 10)), defs);
                    validator.normalize(pivot(d, all.subList(0, 1), all.subList(1, 10)), defs);
                    validator.normalize(pivot(d, all.subList(0, 10), List.of()), defs);
                    String app = app(d, resource("report", "REPORT", ten));
                    seed(app, d);
                    var r = reports.query(query(app), 10001);
                    assertThat(r.pivot().rowDimensionNames()).hasSize(5);
                    assertThat(r.pivot().columnDimensionNames()).hasSize(5);
                    assertThat(r.pivot().rows()).hasSize(10);
                    var grand =
                            r.pivot().cells().stream()
                                    .filter(c -> c.rowKeys().isEmpty() && c.columnKeys().isEmpty())
                                    .findFirst()
                                    .orElseThrow();
                    assertThat(new BigDecimal(grand.values().get("count")))
                            .isEqualByComparingTo("10");
                    // 第 3 层行小计 × 第 4 层列小计 下钻：paid/east/b × 2026/2026-01/2026-01-05/2026-01-05 = #3
                    // #9
                    var cell =
                            r.pivot().cells().stream()
                                    .filter(
                                            c ->
                                                    c.rowKeys().equals(keys("paid", "east", "b"))
                                                            && c.columnKeys().size() == 4
                                                            && "2026-01-05"
                                                                    .equals(c.columnKeys().get(2)))
                                    .findFirst()
                                    .orElseThrow();
                    assertThat(new BigDecimal(cell.values().get("count")))
                            .isEqualByComparingTo("2");
                    assertThat(
                                    reports.details(
                                                    drill(app, cell.rowKeys(), cell.columnKeys()),
                                                    10001)
                                            .getTotal())
                            .isEqualTo(2);
                    // 11 个：拒绝，文案说明原因
                    var elevenRows = new ArrayList<>(rows);
                    elevenRows.add(dim(d, "extra", "VALUE"));
                    assertThatThrownBy(
                                    () -> validator.normalize(pivot(d, elevenRows, columns), defs))
                            .hasMessageContaining("维度过多，请减少")
                            .hasMessageContaining("合计最多 10 个")
                            .hasMessageContaining("(行维度数+1)×(列维度数+1)");
                    var elevenColumns = new ArrayList<>(columns);
                    elevenColumns.add(dim(d, "extra", "VALUE"));
                    assertThatThrownBy(
                                    () -> validator.normalize(pivot(d, rows, elevenColumns), defs))
                            .hasMessageContaining("维度过多，请减少");
                    // 行维度仍至少 1 个
                    assertThatThrownBy(
                                    () -> validator.normalize(pivot(d, List.of(), columns), defs))
                            .hasMessageContaining("透视表至少需要一个行维度");
                    // 同一侧不能重复；行列同字段同分桶不能同时出现
                    assertThatThrownBy(
                                    () ->
                                            validator.normalize(
                                                    pivot(
                                                            d,
                                                            List.of(
                                                                    dim(d, "status", "VALUE"),
                                                                    dim(d, "region", "VALUE"),
                                                                    dim(d, "status", "VALUE")),
                                                            columns),
                                                    defs))
                            .hasMessageContaining("透视表行维度不能重复");
                    assertThatThrownBy(
                                    () ->
                                            validator.normalize(
                                                    pivot(
                                                            d,
                                                            rows,
                                                            List.of(
                                                                    dim(d, "booked", "YEAR"),
                                                                    dim(d, "amount", "VALUE"),
                                                                    dim(d, "booked", "YEAR"))),
                                                    defs))
                            .hasMessageContaining("透视表列维度不能重复");
                    assertThatThrownBy(
                                    () ->
                                            validator.normalize(
                                                    pivot(
                                                            d,
                                                            rows,
                                                            List.of(
                                                                    dim(d, "booked", "YEAR"),
                                                                    dim(d, "name", "VALUE"))),
                                                    defs))
                            .hasMessageContaining("同一字段不能同时作为透视表的行维度和列维度");
                });
    }

    /** 非透视展示方式（汇总表、柱、线、饼）仍最多 2 个分组：第 3 个拒绝，2 个照常。 */
    @Test
    void nonPivotDisplaysStillRejectAThirdGroup() {
        rollback(
                () -> {
                    var d = object();
                    var validator = servicesContext.getBean(ApplicationReportValidator.class);
                    var defs = Map.of(d.objectId(), d);
                    var one = List.of(new ApplicationReports.Metric("count", "笔数", "COUNT", null));
                    var two = rows(d).subList(0, 2);
                    for (var display : List.of("TABLE", "BAR", "LINE", "PIE")) {
                        assertThatThrownBy(
                                        () ->
                                                validator.normalize(
                                                        config(
                                                                d, display, rows(d), null, one,
                                                                null, false),
                                                        defs))
                                .as(display)
                                .hasMessageContaining("最多两个不重复分组");
                        if (!"PIE".equals(display))
                            assertThat(
                                            validator
                                                    .normalize(
                                                            config(
                                                                    d, display, two, null, one,
                                                                    null, false),
                                                            defs)
                                                    .dimensions())
                                    .as(display)
                                    .hasSize(2);
                    }
                    // 指标卡同样按非透视处理：3 个分组先被上限拒绝，2 个分组仍是「指标卡不设置分组」
                    assertThatThrownBy(
                                    () ->
                                            validator.normalize(
                                                    config(
                                                            d, "METRIC", rows(d), null, one, null,
                                                            false),
                                                    defs))
                            .hasMessageContaining("最多两个不重复分组");
                    assertThatThrownBy(
                                    () ->
                                            validator.normalize(
                                                    config(
                                                            d, "METRIC", two, null, one, null,
                                                            false),
                                                    defs))
                            .hasMessageContaining("指标卡不设置分组");
                    assertThat(ApplicationReports.MAX_GROUP_DIMENSIONS).isEqualTo(2);
                });
    }

    // ---------------------------------------------------------------- 导出

    /**
     * 3 层列维度导出：表头 4 层（年 → 月 → 类别 → 指标），合并按层：上层横跨其下全部列组（含该组小计），小计/合计格纵向跨到指标层之上， 行维度列纵跨 4
     * 层；指标层不合并（只有一个指标时，按「相邻同文本」自动合并会把各列组的指标名连成一格，这里必须没有）。
     */
    @Test
    void exportWithThreeColumnDimensionsWritesFourHeaderLevelsMergedByLevel() throws Exception {
        rollback(
                () -> {
                    var d = object();
                    var config =
                            config(
                                    d,
                                    "PIVOT",
                                    rows(d),
                                    columns(d),
                                    List.of(
                                            new ApplicationReports.Metric(
                                                    "count", "笔数", "COUNT", null)),
                                    null,
                                    false);
                    String app = app(d, resource("report", "REPORT", config));
                    seed(app, d);
                    var excel =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.web.RecordExcelService.class);
                    byte[] file = excel.report(query(app), 10001);
                    try (var book = new XSSFWorkbook(new java.io.ByteArrayInputStream(file))) {
                        var sheet = book.getSheetAt(0);
                        java.util.function.BiFunction<Integer, Integer, String> text =
                                (r, c) -> {
                                    var line = sheet.getRow(r);
                                    var cell = line == null ? null : line.getCell(c);
                                    return cell == null ? null : cell.getStringCellValue();
                                };
                        // 行维度名 + 列组（2025 ×3 列：两个类别 + 月小计，年小计；2026 同理）+ 合计
                        assertThat(text.apply(0, 0)).isEqualTo("状态");
                        assertThat(text.apply(0, 2)).isEqualTo("名称");
                        assertThat(text.apply(0, 3)).isEqualTo("2025");
                        assertThat(text.apply(1, 3)).isEqualTo("2025-12");
                        assertThat(text.apply(2, 3)).isEqualTo("x");
                        assertThat(text.apply(2, 4)).isEqualTo("y");
                        assertThat(text.apply(2, 5)).isEqualTo("小计");
                        assertThat(text.apply(1, 6)).isEqualTo("小计");
                        assertThat(text.apply(0, 7)).isEqualTo("2026");
                        assertThat(text.apply(1, 7)).isEqualTo("2026-01");
                        assertThat(text.apply(1, 10)).isEqualTo("2026-02");
                        assertThat(text.apply(1, 13)).isEqualTo("小计");
                        assertThat(text.apply(0, 14)).isEqualTo("合计");
                        for (int c = 3; c <= 14; c++) assertThat(text.apply(3, c)).isEqualTo("笔数");
                        Set<List<Integer>> merged = new HashSet<>();
                        for (CellRangeAddress region : sheet.getMergedRegions())
                            merged.add(
                                    List.of(
                                            region.getFirstRow(),
                                            region.getLastRow(),
                                            region.getFirstColumn(),
                                            region.getLastColumn()));
                        assertThat(merged)
                                .containsExactlyInAnyOrder(
                                        // 行维度列纵跨 4 层
                                        List.of(0, 3, 0, 0),
                                        List.of(0, 3, 1, 1),
                                        List.of(0, 3, 2, 2),
                                        // 第 1 层：年（含其下月小计与年小计）；合计纵跨 3 层列维度
                                        List.of(0, 0, 3, 6),
                                        List.of(0, 0, 7, 13),
                                        List.of(0, 2, 14, 14),
                                        // 第 2 层：月（含月小计）；年小计纵跨第 2、3 层
                                        List.of(1, 1, 3, 5),
                                        List.of(1, 2, 6, 6),
                                        List.of(1, 1, 7, 9),
                                        List.of(1, 1, 10, 12),
                                        List.of(1, 2, 13, 13));
                        // 数据从第 5 行起：首行 paid/east/a，2025-12/x = 1 笔，行合计 2 笔
                        assertThat(text.apply(4, 0)).isEqualTo("paid");
                        assertThat(text.apply(4, 1)).isEqualTo("east");
                        assertThat(text.apply(4, 2)).isEqualTo("a");
                        assertThat(new BigDecimal(text.apply(4, 3))).isEqualByComparingTo("1");
                        assertThat(new BigDecimal(text.apply(4, 14))).isEqualByComparingTo("2");
                        // paid/east/b 之后依次是 paid/east 小计（第 3 列「小计」），然后才是 paid/west
                        assertThat(text.apply(5, 2)).isEqualTo("b");
                        assertThat(text.apply(6, 1)).isEqualTo("east");
                        assertThat(text.apply(6, 2)).isEqualTo("小计");
                        assertThat(new BigDecimal(text.apply(6, 14))).isEqualByComparingTo("4");
                        assertThat(text.apply(7, 1)).isEqualTo("west");
                    } catch (java.io.IOException e) {
                        throw new AssertionError(e);
                    }
                });
    }
}
