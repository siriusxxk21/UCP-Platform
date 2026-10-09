package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;
import static com.richuang.os.nocode.tools.ReportMultiSourceFixture.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.ApplicationActionEnum;
import com.richuang.os.nocode.runtime.dal.mapper.ReportMapper;
import com.richuang.os.nocode.runtime.dal.query.ReportStatement;
import com.richuang.os.nocode.runtime.service.record.RuntimeSchema;
import com.richuang.os.nocode.runtime.service.report.ApplicationReportService;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 多个数据来源的语句：开工先验证 V1、V3、V4、V5（任务书第 1 节），存量渲染不变（T17）与 sourceFrom / from 的逐行对应（T18）。 需要连库的用例每例整体回滚。V1
 * / V3 / V4 把渲染出的 SQL、参数与物理类型打印到标准输出（原始产物）。
 */
class ReportMultiSourceSqlTest {
    private static final String MAPPER = ReportMapper.class.getName();
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

    /** 取一张已发布统计编好的合并语句（测试里经反射调用私有的 prepare，只读）。 */
    static ReportStatement statement(String app, String report, long actor) {
        try {
            ApplicationReportService service =
                    servicesContext.getBean(ApplicationReportService.class);
            java.lang.reflect.Method prepare =
                    ApplicationReportService.class.getDeclaredMethod(
                            "prepare",
                            ApplicationReports.Query.class,
                            long.class,
                            ApplicationActionEnum.class);
            prepare.setAccessible(true);
            // 同步 dev 后专项 Context
            // 启用了事务代理（NocodeIntegrationSupport.TransactionConfiguration，proxyTargetClass），
            // 取到的是 CGLIB 代理；私有方法要在被代理的原对象上调，否则代理对象的注入字段全是 null。
            Object target =
                    org.springframework.test.util.AopTestUtils.getUltimateTargetObject(service);
            Object plan =
                    prepare.invoke(
                            target,
                            new ApplicationReports.Query(
                                    app, report, null, null, null, null, null, 1, 20),
                            actor,
                            ApplicationActionEnum.READ);
            java.lang.reflect.Method accessor = plan.getClass().getDeclaredMethod("statement");
            accessor.setAccessible(true);
            return (ReportStatement) accessor.invoke(plan);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static BoundSql bound(String statement, ReportStatement parameter) {
        return servicesContext
                .getBean(SqlSessionFactory.class)
                .getConfiguration()
                .getMappedStatement(MAPPER + "." + statement)
                .getBoundSql(parameter);
    }

    /** 每个参数的路径与取到的值，按绑定顺序。 */
    static List<String> bindings(BoundSql sql, ReportStatement parameter) {
        var meta =
                servicesContext
                        .getBean(SqlSessionFactory.class)
                        .getConfiguration()
                        .newMetaObject(parameter);
        List<String> lines = new ArrayList<>();
        for (var mapping : sql.getParameterMappings()) {
            String property = mapping.getProperty();
            Object value =
                    sql.hasAdditionalParameter(property)
                            ? sql.getAdditionalParameter(property)
                            : meta.getValue(property);
            lines.add(property + " = " + value);
        }
        return lines;
    }

    // ---------------------------------------------------------------- V1

    /**
     * V1：两个来源各带一个指标条件、参数值不同，参数路径都是 predicates.m0（本来源里的下标）——只有带上 sources[i].statement 前缀才各自生效。
     * 当月金额只算 ≥ 15000 的入住（排除 S4 的 8000），次月金额只算 ≥ 16000 的（只剩 S4）。
     */
    @Test
    void v1EachSourceBindsItsOwnConditionParameters() {
        rollback(
                () -> {
                    f.objects();
                    ApplicationReports.Config c = f.example2(false);
                    List<ApplicationReports.Metric> metrics =
                            List.of(
                                    new ApplicationReports.Metric(
                                            "cur",
                                            "当月金额",
                                            "SUM",
                                            f.curAmount,
                                            condition(f.curAmount, "gte", "15000"),
                                            null,
                                            null,
                                            null),
                                    new ApplicationReports.Metric(
                                            "nxt",
                                            "次月金额",
                                            "SUM",
                                            f.nextAmount,
                                            condition(f.nextAmount, "gte", "16000"),
                                            null,
                                            null,
                                            "next"),
                                    formula("rev", "月收益", "ADD", "cur", "nxt"));
                    f.app(f.resource("report", "REPORT", c.withMetrics(metrics))).seed();
                    ReportStatement s = statement(f.app, "report", OWNER);
                    BoundSql sql = bound("pivot", s);
                    List<String> params = bindings(sql, s);
                    System.out.println("V1 SQL >>>\n" + sql.getSql() + "\n<<< V1 SQL");
                    params.forEach(p -> System.out.println("V1 PARAM " + p));
                    assertThat(params)
                            .anyMatch(
                                    p ->
                                            p.startsWith(
                                                            "sources[0].statement.predicates.m0.paramNameValuePairs.")
                                                    && p.endsWith("= 15000"))
                            .anyMatch(
                                    p ->
                                            p.startsWith(
                                                            "sources[1].statement.predicates.m0.paramNameValuePairs.")
                                                    && p.endsWith("= 16000"));
                    ApplicationReports.Result r = f.reports.query(f.query("report"), OWNER);
                    String p1 = f.key("P1"), p3 = f.key("P3");
                    SoftAssertionsHolder.check(
                            soft -> {
                                softCell(
                                        soft,
                                        cell(r, keys(), keys()).values(),
                                        "总计",
                                        List.of("cur", "nxt"),
                                        75000,
                                        16000);
                                softCell(
                                        soft,
                                        cell(r, keys(p3), keys("2026-08")).values(),
                                        "P3·08",
                                        List.of("cur", "nxt"),
                                        0,
                                        null);
                                softCell(
                                        soft,
                                        cell(r, keys(p3), keys("2026-09")).values(),
                                        "P3·09",
                                        List.of("cur", "nxt"),
                                        null,
                                        16000);
                                softCell(
                                        soft,
                                        cell(r, keys(p1), keys("2026-08")).values(),
                                        "P1·08",
                                        List.of("cur", "nxt"),
                                        18000,
                                        0);
                            });
                });
    }

    // ---------------------------------------------------------------- V3

    /** V3：三个分支、某指标列只在第三个分支有真列——不带类型的空会先被定成 text 而报错；带 CAST 就不报错。 */
    @Test
    void v3TypedNullPlaceholdersAreRequired() {
        String untyped =
                "SELECT NULL AS v UNION ALL SELECT NULL AS v UNION ALL SELECT CAST(1.50 AS"
                        + " numeric(18,2)) AS v";
        String typed =
                "SELECT CAST(NULL AS numeric(18,2)) AS v UNION ALL SELECT CAST(NULL AS"
                        + " numeric(18,2)) AS v UNION ALL SELECT CAST(1.50 AS numeric(18,2)) AS v";
        Throwable error = catchThrowable(() -> jdbc.queryForList(untyped));
        Throwable cause = error;
        while (cause != null && cause.getCause() != null) cause = cause.getCause();
        System.out.println("V3 untyped >>> " + cause);
        assertThat(error)
                .isNotNull()
                .hasStackTraceContaining("UNION types text and numeric cannot be matched");
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT sum(v)::text AS s FROM (" + typed + ") x");
        System.out.println("V3 typed >>> " + rows);
        assertThat(rows.getFirst().get("s")).isEqualTo("1.50");
    }

    // ---------------------------------------------------------------- V4

    /** V4：夹具对象全部列的物理类型（金额、日期、文本、引用列…）都过 L24 的白名单。 */
    @Test
    void v4NativeTypesPassTheWhitelist() {
        rollback(
                () -> {
                    f.objects();
                    RuntimeSchema schemas = servicesContext.getBean(RuntimeSchema.class);
                    Pattern whitelist = Pattern.compile("[a-z][a-z0-9 ]*(\\([0-9]+(,[0-9]+)?\\))?");
                    List<RuntimeSchema.Table> tables = new ArrayList<>();
                    for (DataCenter.Definition d : f.referenced()) tables.add(schemas.main(d));
                    tables.add(schemas.detail(f.voucher, f.lines));
                    Set<String> types = new TreeSet<>();
                    for (RuntimeSchema.Table t : tables)
                        for (var column : t.physical().columns()) {
                            System.out.println(
                                    "V4 "
                                            + t.name()
                                            + "."
                                            + column.name()
                                            + " : "
                                            + column.nativeType());
                            types.add(column.nativeType());
                        }
                    System.out.println("V4 distinct types: " + types);
                    assertThat(types)
                            .allMatch(
                                    t ->
                                            whitelist
                                                    .matcher(t.trim().toLowerCase(Locale.ROOT))
                                                    .matches());
                });
    }

    // ---------------------------------------------------------------- V5

    /**
     * V5：来源 1 按主记录（凭证张数，按摘要分行），来源 2 按明细行（分录贷方金额），列都是凭证日期按月：laneT 的明细粒度编译在来源投影上原样可用。 V1 08：1 张 /
     * 1500；V2 08：1 张 / 300；V3 09：1 张 / 200（L5 贷方金额空）。
     */
    @Test
    void v5MixedGrainSources() {
        rollback(
                () -> {
                    f.objects();
                    ApplicationReports.Config c =
                            config(
                                    f.voucher.objectId(),
                                    null,
                                    null,
                                    "PIVOT",
                                    List.of(dim(f.memo, null, "VALUE")),
                                    List.of(dim(f.booked, null, "MONTH")),
                                    List.of(
                                            metric("n", "张数", "COUNT", null, null),
                                            metric("cr", "贷方合计", "SUM", f.creditAmount, "lines"),
                                            metric("rows", "分录行数", "COUNT", null, "lines")),
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
                    f.app(f.resource("report", "REPORT", c)).seed();
                    ApplicationReports.Result r = f.reports.query(f.query("report"), OWNER);
                    List<String> m = List.of("n", "cr", "rows");
                    SoftAssertionsHolder.check(
                            soft -> {
                                softCell(
                                        soft,
                                        cell(r, keys("V1"), keys("2026-08")).values(),
                                        "V1",
                                        m,
                                        1,
                                        1500,
                                        2);
                                softCell(
                                        soft,
                                        cell(r, keys("V2"), keys("2026-08")).values(),
                                        "V2",
                                        m,
                                        1,
                                        300,
                                        1);
                                softCell(
                                        soft,
                                        cell(r, keys("V3"), keys("2026-09")).values(),
                                        "V3",
                                        m,
                                        1,
                                        200,
                                        2);
                                softCell(
                                        soft,
                                        cell(r, keys(), keys()).values(),
                                        "总计",
                                        m,
                                        3,
                                        2000,
                                        5);
                                soft.assertThat(r.sources())
                                        .extracting(
                                                ApplicationReports.SourceSummary::recordCount,
                                                ApplicationReports.SourceSummary::detailName)
                                        .containsExactly(tuple(3L, null), tuple(5L, "分录"));
                            });
                    var rows =
                            f.reports.details(
                                    f.drill("report", keys("V1"), keys("2026-08"), "cr", null),
                                    OWNER);
                    assertThat(rows.getList())
                            .extracting(ApplicationRecords.Row::id)
                            .containsExactly(
                                    f.key("V1") + ":" + f.key("L1"),
                                    f.key("V1") + ":" + f.key("L2"));
                    var roots =
                            f.reports.details(
                                    f.drill("report", keys("V1"), keys("2026-08"), "n", null),
                                    OWNER);
                    assertThat(roots.getList())
                            .extracting(ApplicationRecords.Row::id)
                            .containsExactly(f.key("V1"));
                });
    }

    // ---------------------------------------------------------------- T17

    /**
     * T17：单来源语句渲染与基线逐 token 相同由 {@link ProviderXmlCompatibilityTest}
     * 不改一行钉住；这里另外明确断言单来源的渲染里没有任何多来源的记号。
     */
    @Test
    void singleSourceRenderingHasNoMultiSourceTokens() throws Exception {
        var configuration = ProviderXmlCompatibilityTest.configuration();
        var cases =
                ProviderXmlCompatibilityTest.cases().stream()
                        .filter(test -> test.statement().startsWith(MAPPER + "."))
                        .toList();
        assertThat(cases).isNotEmpty();
        for (var test : cases) {
            String sql =
                    configuration
                            .getMappedStatement(test.statement())
                            .getBoundSql(test.parameters())
                            .getSql();
            assertThat(sql)
                    .as(test.key())
                    .doesNotContain(" src")
                    .doesNotContain("CASE WHEN count(*) FILTER")
                    .doesNotContain("sourceCounts")
                    .doesNotContain("UNION ALL")
                    .doesNotContain("sources[");
        }
    }

    // ---------------------------------------------------------------- T18

    /** 一个 &lt;sql id&gt; 片段的原文行（去掉首尾空白、空行与注释行）。 */
    static List<String> fragment(String xml, String id) {
        int start = xml.indexOf("<sql id=\"" + id + "\">");
        assertThat(start).as("片段 " + id).isGreaterThanOrEqualTo(0);
        int end = xml.indexOf("</sql>", start);
        String body = xml.substring(start, end);
        body = body.replaceAll("(?s)<!--.*?-->", "");
        List<String> lines = new ArrayList<>();
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("<sql id=")) lines.add(t);
        }
        return lines;
    }

    /** sourceFrom 的路径还原成顶层名字（契约 11.2 的改写规则逐条反做）。 */
    static String restore(String line) {
        return line.replace(".replace('#{', '#{sources[' + _sourceIndex + '].statement.')", "")
                .replace("'sources[' + _sourceIndex + '].statement.", "'")
                .replace("sources[${_sourceIndex}].statement.", "")
                .replace("_source.statement.", "")
                .replace("refid=\"sourceDateValue\"", "refid=\"dateValue\"");
    }

    static String mapperXml() throws java.io.IOException {
        try (var input =
                ReportMapper.class.getResourceAsStream("/mapper/nocode/ReportMapper.xml")) {
            assertThat(input).isNotNull();
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** T18：sourceFrom / sourceDateValue 与 from / dateValue 逐行对应；以后谁改了 from 而忘了 sourceFrom，这条用例红。 */
    @Test
    void sourceFromMirrorsFromLineByLine() throws Exception {
        String xml = mapperXml();
        assertThat(
                        fragment(xml, "sourceFrom").stream()
                                .map(ReportMultiSourceSqlTest::restore)
                                .toList())
                .isEqualTo(fragment(xml, "from"));
        assertThat(
                        fragment(xml, "sourceDateValue").stream()
                                .map(ReportMultiSourceSqlTest::restore)
                                .toList())
                .isEqualTo(fragment(xml, "dateValue"));
    }

    /** 软断言的小包装（一次报全）。 */
    static final class SoftAssertionsHolder {
        static void check(java.util.function.Consumer<org.assertj.core.api.SoftAssertions> body) {
            org.assertj.core.api.SoftAssertions.assertSoftly(body::accept);
        }
    }
}
