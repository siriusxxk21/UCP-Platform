package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;
import static com.lingan.ucp.nocode.tools.ReportMultiSourceFixture.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.runtime.dal.query.ReportStatement;
import com.lingan.ucp.nocode.runtime.service.record.RuntimeSchema;

import org.apache.ibatis.mapping.BoundSql;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 任务书 S1（只打印不断言）：例 1 放大到 入住记录 6000 条（× 2 个来源）+ 支出 6000 条；例 3 放大到 凭证 6000 张 × 3 条分录（× 2 个来源）。 各跑 5
 * 次打印耗时，并打印一次 EXPLAIN (ANALYZE, BUFFERS)。直接用 SQL 复制夹具行放大（不走记录保存，只为量查询本身）；整例回滚。
 */
class ReportMultiSourcePerformanceTest {
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

    private static List<String> columns(String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema='public' AND"
                        + " table_name=? AND column_name <> 'id' ORDER BY ordinal_position",
                String.class,
                table);
    }

    private static String quoted(List<String> columns, String prefix) {
        StringJoiner j = new StringJoiner(", ");
        for (String c : columns) j.add(prefix + "\"" + c + "\"");
        return j.toString();
    }

    /** 把表里现有的行复制 times 遍（除主键外全部列原样）。 */
    private static int copy(String table, int times) {
        List<String> cols = columns(table);
        return jdbc.update(
                "INSERT INTO public.\""
                        + table
                        + "\" ("
                        + quoted(cols, "")
                        + ") SELECT "
                        + quoted(cols, "t.")
                        + " FROM public.\""
                        + table
                        + "\" t CROSS JOIN generate_series(1, ?) g",
                times);
    }

    private void explain(String label, String report) {
        ReportStatement s = ReportMultiSourceSqlTest.statement(f.app, report, OWNER);
        BoundSql sql = ReportMultiSourceSqlTest.bound("pivot", s);
        var meta =
                servicesContext
                        .getBean(org.apache.ibatis.session.SqlSessionFactory.class)
                        .getConfiguration()
                        .newMetaObject(s);
        List<Object> values = new ArrayList<>();
        for (var mapping : sql.getParameterMappings())
            values.add(
                    sql.hasAdditionalParameter(mapping.getProperty())
                            ? sql.getAdditionalParameter(mapping.getProperty())
                            : meta.getValue(mapping.getProperty()));
        List<String> plan =
                jdbc.query(
                        "EXPLAIN (ANALYZE, BUFFERS) " + sql.getSql(),
                        ps -> {
                            for (int i = 0; i < values.size(); i++)
                                ps.setObject(i + 1, values.get(i));
                        },
                        (rs, n) -> rs.getString(1));
        System.out.println("S1 EXPLAIN " + label + " >>>");
        plan.forEach(line -> System.out.println("S1 PLAN " + label + " | " + line));
        System.out.println("<<< S1 EXPLAIN " + label);
    }

    private void time(String label, String report) {
        for (int i = 1; i <= 5; i++) {
            long start = System.nanoTime();
            ApplicationReports.Result r = f.reports.query(f.query(report), OWNER);
            long ms = (System.nanoTime() - start) / 1_000_000;
            System.out.println(
                    "S1 TIME "
                            + label
                            + " run "
                            + i
                            + " = "
                            + ms
                            + " ms; recordCount="
                            + r.recordCount()
                            + " sources="
                            + r.sources().stream().map(x -> x.id() + ":" + x.recordCount()).toList()
                            + " rows="
                            + r.pivot().rows().size()
                            + " cells="
                            + r.pivot().cells().size());
        }
    }

    @Test
    void s1Timings() {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            try {
                                f.objects();
                                f.app(
                                                f.resource("profit", "REPORT", f.example1()),
                                                f.resource("trial", "REPORT", f.example3()))
                                        .seed();
                                RuntimeSchema schemas =
                                        servicesContext.getBean(RuntimeSchema.class);
                                String stay = schemas.main(f.stay).name(),
                                        expense = schemas.main(f.expense).name();
                                String voucher = schemas.main(f.voucher).name();
                                RuntimeSchema.Table lines = schemas.detail(f.voucher, f.lines);
                                System.out.println(
                                        "S1 copy stay +"
                                                + copy(stay, 1199)
                                                + " expense +"
                                                + copy(expense, 1499));
                                // 凭证：复制 6000 张 V1，每张挂 L1、L2、L3 三条分录的副本。
                                List<String> vcols = columns(voucher);
                                jdbc.update(
                                        "INSERT INTO public.\""
                                                + voucher
                                                + "\" ("
                                                + quoted(vcols, "")
                                                + ") SELECT "
                                                + quoted(vcols, "t.")
                                                + " FROM public.\""
                                                + voucher
                                                + "\" t CROSS JOIN generate_series(1, 6000) g WHERE"
                                                + " t.id = ?",
                                        Long.parseLong(f.key("V1")));
                                String parent = lines.binding().parentColumn();
                                List<String> lcols = new ArrayList<>(columns(lines.name()));
                                lcols.remove(parent);
                                int added =
                                        jdbc.update(
                                                "INSERT INTO public.\""
                                                        + lines.name()
                                                        + "\" ("
                                                        + quoted(lcols, "")
                                                        + ", \""
                                                        + parent
                                                        + "\") SELECT "
                                                        + quoted(lcols, "l.")
                                                        + ", v.id FROM public.\""
                                                        + lines.name()
                                                        + "\" l CROSS JOIN public.\""
                                                        + voucher
                                                        + "\" v WHERE l.id IN (?, ?, ?) AND v.id >"
                                                        + " ?",
                                                Long.parseLong(f.key("L1")),
                                                Long.parseLong(f.key("L2")),
                                                Long.parseLong(f.key("L3")),
                                                Long.parseLong(f.key("V3")));
                                System.out.println("S1 copy voucher +6000 lines +" + added);
                                for (String t : List.of(stay, expense, voucher, lines.name()))
                                    jdbc.execute("ANALYZE public.\"" + t + "\"");
                                time("例1", "profit");
                                explain("例1", "profit");
                                time("例3", "trial");
                                explain("例3", "trial");
                            } finally {
                                tx.setRollbackOnly();
                            }
                        });
    }
}
