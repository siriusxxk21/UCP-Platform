package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.api.DataCenter.FieldOptions;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.metadata.service.formula.FieldExpressions;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaDates;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaEvaluator;
import com.lingan.ucp.nocode.runtime.service.maintenance.ObjectDataMaintenanceService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;

import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 公式日期函数在真实库上的各使用点：本行公式（数据库生成列）与求值器逐格相同；包含计算结果的公式（读取时计算 / 保存时落库）；
 * 明细里的公式；公式默认值（金额按取整方式向下取整）；列表筛选排序；导入与对象数据维护时的重算；保存对象时的拦截文案。
 * 对象形状取民宿「入住记录」：入住日、退房日（DATE）、房费（MONEY）。只清理本测试随机前缀的夹具。
 */
class FormulaDatesIntegrationTest {
    /** 业务方在钉钉里拼的那条（[字段名] 由编辑器换成字段编码，其余一字不改）。 */
    private static final String NEXT = "IF( MONTH( c_in) = MONTH( c_out), 0, DAY( c_out)-1)";

    private static final String CURRENT = "(c_out - c_in) - " + NEXT;

    private static String ymd(String date) {
        return "YEAR(" + date + ") * 10000 + MONTH(" + date + ") * 100 + DAY(" + date + ")";
    }

    /** 本行公式（生成列），结果全部是整数；返回日期的函数套一层年月日再比。 */
    private static final Map<String, String> GENERATED = new LinkedHashMap<>();

    static {
        GENERATED.put("nights", "c_out - c_in");
        GENERATED.put("next_nights", NEXT);
        GENERATED.put("cur_nights", CURRENT);
        GENERATED.put("g_year", "YEAR(c_in)");
        GENERATED.put("g_month", "month(c_in)");
        GENERATED.put("g_day", "Day( c_in )");
        GENERATED.put("g_wd1", "WEEKDAY(c_in)");
        GENERATED.put("g_wd2", "WEEKDAY(c_in, 2)");
        GENERATED.put("g_wd3", "WEEKDAY(c_in, 3)");
        GENERATED.put("g_days", "DAYS(c_out, c_in)");
        GENERATED.put("g_dif_d", "DATEDIF(c_in, c_out, \"D\")");
        GENERATED.put("g_dif_m", "DATEDIF(c_in, c_out, \"M\")");
        GENERATED.put("g_dif_y", "DATEDIF(c_in, c_out, 'y')");
        GENERATED.put("g_eom", ymd("EOMONTH(c_in, c_n)"));
        GENERATED.put("g_eom0", ymd("EOMONTH(c_in)"));
        GENERATED.put("g_edate", ymd("EDATE(c_in, c_n)"));
        GENERATED.put("g_date", ymd("DATE(YEAR(c_in), MONTH(c_in) + c_n, DAY(c_in))"));
        GENERATED.put("g_add", ymd("(c_in + c_n)"));
        GENERATED.put("g_sub", ymd("(c_in - c_n)"));
        GENERATED.put("g_nadd", ymd("(c_n + c_in)"));
        GENERATED.put("g_at_day", "DAY(c_at)");
        GENERATED.put("g_at_days", "DAYS(c_at, c_in)");
        GENERATED.put("g_at_diff", "c_at - c_in");
        GENERATED.put("g_at_add", ymd("(c_at + 1)"));
        GENERATED.put("g_cmp", "IF(EOMONTH(c_in, 0) < c_out, 1, 0)");
        GENERATED.put("g_lit", "c_out - '2026-07-01'");
        // 带小数的天数 / 月数：两边都只取整数部分（数据库侧不能靠 ::integer 的四舍五入）。
        GENERATED.put("g_frac_add", ymd("(c_in + 1.9)"));
        GENERATED.put("g_frac_sub", ymd("(c_in - 2.5)"));
        GENERATED.put("g_frac_edate", ymd("EDATE(c_in, 1.9)"));
        GENERATED.put("g_frac_back", ymd("EDATE(c_in, 0 - 1.9)"));
        GENERATED.put("g_frac_eom", ymd("EOMONTH(c_in, c_n / 2)"));
        GENERATED.put("g_frac_date", ymd("DATE(2026.9, 7.9, 30.9)"));
    }

    private static final String CURRENT_AMOUNT = "c_fee * (" + CURRENT + ") / (c_out - c_in)";

    private NocodeIntegrationSupport fixture;
    private RecordService runtime;
    private ObjectDataMaintenanceService maintenance;
    private DataCenter.Definition definition;
    private String objectDraftId;
    private String app;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    private static FieldOptions formula(String expression, String update) {
        return FieldOptions.copyOf(FieldOptions.defaults())
                .expression(expression)
                .resultType("INTEGER")
                .calculation(
                        update == null
                                ? null
                                : new CalculationOptions(
                                        "LOCAL", update, null, null, null, null, "AND", List.of(),
                                        false, List.of(), null))
                .build();
    }

    private static FieldOptions rule(String defaultFormula) {
        return FieldOptions.defaults()
                .withRules(new FieldRules(null, null, defaultFormula, null, null, null));
    }

    private DataCenter.SaveDesign design(String suffix, Map<String, FieldOptions> extraFormulas) {
        SaveObjectDraft request = fixture.createRequest(suffix);
        List<FieldDefinition> fields = new ArrayList<>();
        Map<String, FieldOptions> options = new LinkedHashMap<>();
        fields.add(fixture.field("name", "name", "TEXT", 0));
        fields.add(fixture.field("c_in", "c_in", "DATE", fields.size()));
        fields.add(fixture.field("c_out", "c_out", "DATE", fields.size()));
        fields.add(fixture.field("c_at", "c_at", "DATETIME", fields.size()));
        fields.add(fixture.field("c_fee", "c_fee", "MONEY", fields.size()));
        fields.add(fixture.field("c_n", "c_n", "INTEGER", fields.size()));
        fields.add(fixture.field("cur_amount", "cur_amount", "MONEY", fields.size()));
        fields.add(fixture.field("next_amount", "next_amount", "MONEY", fields.size()));
        options.put("cur_amount", rule(CURRENT_AMOUNT));
        options.put("next_amount", rule("c_fee - cur_amount"));
        for (var entry : extraFormulas.entrySet()) {
            fields.add(fixture.field(entry.getKey(), entry.getKey(), "FORMULA", fields.size()));
            options.put(entry.getKey(), entry.getValue());
        }
        DataCenter.Detail detail =
                new DataCenter.Detail(
                        null,
                        "items",
                        "入住分段",
                        "biz_" + fixture.prefix + suffix + "_i",
                        "ACTIVE",
                        List.of(
                                fixture.field("d_from", "d_from", "DATE", 0),
                                fixture.field("d_to", "d_to", "DATE", 1),
                                fixture.field("d_nights", "d_nights", "FORMULA", 2),
                                fixture.field("d_month", "d_month", "FORMULA", 3)),
                        Map.of(
                                "d_nights",
                                formula("d_to - d_from", null),
                                "d_month",
                                formula("MONTH( d_to )", null)),
                        List.of());
        return new DataCenter.SaveDesign(
                new SaveObjectDraft(
                        null,
                        null,
                        request.objectCode(),
                        "入住记录（公式日期函数验证）",
                        null,
                        request.tableName(),
                        "name",
                        fields,
                        List.of()),
                DataCenter.Settings.defaults(),
                options,
                List.of(),
                List.of(),
                List.of(detail));
    }

    @BeforeEach
    void setup() {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        runtime = servicesContext.getBean(RecordService.class);
        maintenance = servicesContext.getBean(ObjectDataMaintenanceService.class);
        PermissionCommonApi permission = servicesContext.getBean(PermissionCommonApi.class);
        org.mockito.Mockito.when(permission.hasAnyPermissions(10001L, "nocode:object:query"))
                .thenReturn(true);
        org.mockito.Mockito.when(permission.hasAnyPermissions(10001L, "nocode:object:manage"))
                .thenReturn(true);
        Map<String, FieldOptions> formulas = new LinkedHashMap<>();
        GENERATED.forEach((code, expression) -> formulas.put(code, formula(expression, null)));
        formulas.put("l_live", formula("nights - next_nights", "LIVE"));
        formulas.put("l_saved", formula("nights - next_nights", "ON_SAVE"));
        formulas.put("l_today", formula("TODAY() - c_in", "LIVE"));
        formulas.put("l_month", formula("MONTH(c_out) - MONTH(c_in)", "ON_SAVE"));
        DataCenter.Design design = designs.save(design("fx_stay", formulas), 10001);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "公式日期函数测试"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        objectDraftId = design.draft().id();
        DataObjectApi objects = servicesContext.getBean(DataObjectApi.class);
        definition = objects.getPublished(objectDraftId);
        DataObjectApi.PublishedObject version = objects.getVersion(definition.objectId(), null);
        ApplicationService applications = servicesContext.getBean(ApplicationService.class);
        ApplicationCenter.Detail application =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "app",
                                "公式日期函数测试",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        version.objectId(),
                                                        version.versionNo(),
                                                        version.checksum())),
                                        List.of())),
                        10001);
        app = application.application().id();
        grantApplicationObjects(app);
        applications.publish(
                new ApplicationCenter.Revision(app, application.application().revision(), "测试"),
                10001);
    }

    @AfterEach
    void cleanup() {
        for (Long id :
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        fixture.prefix + "%")) {
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant_log WHERE application_id=?",
                    id);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant WHERE application_id=?",
                    id);
            jdbc.update("DELETE FROM public.nocode_application_access WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_version WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        }
        fixture.clean();
    }

    private String field(String code) {
        return definition.fields().stream()
                .filter(f -> code.equals(f.code()))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private Map<String, Object> input(
            String name, String in, String out, String at, String fee, Integer n) {
        Map<String, Object> values = new HashMap<>();
        values.put(field("name"), name);
        if (in != null) values.put(field("c_in"), in);
        if (out != null) values.put(field("c_out"), out);
        if (at != null) values.put(field("c_at"), at);
        if (fee != null) values.put(field("c_fee"), fee);
        if (n != null) values.put(field("c_n"), n);
        return values;
    }

    private Row create(String name, String in, String out, String at, String fee, Integer n) {
        return runtime.save(
                        new Save(
                                app,
                                definition.objectId(),
                                null,
                                null,
                                input(name, in, out, at, fee, n),
                                Map.of()),
                        10001)
                .record();
    }

    private Row stay(String in, String out, String fee) {
        return create(in + "~" + out, in, out, null, fee, null);
    }

    private static void number(Object actual, String expected) {
        assertThat(actual).as("expected " + expected).isNotNull();
        assertThat(new BigDecimal(actual.toString())).isEqualByComparingTo(expected);
    }

    private void expect(Row row, String code, String expected) {
        Object actual = row.values().get(field(code));
        if (expected == null) assertThat(actual).as(code).isNull();
        else {
            assertThat(actual).as(code).isNotNull();
            assertThat(new BigDecimal(actual.toString())).as(code).isEqualByComparingTo(expected);
        }
    }

    private List<Row> page(Map<String, Object> equal, String sort, boolean descending) {
        return runtime.page(
                        new Query(
                                app,
                                definition.objectId(),
                                1,
                                100,
                                null,
                                equal,
                                sort == null ? null : field(sort),
                                descending),
                        10001)
                .getList();
    }

    /** 业务方给的核对数：7/30–8/2 房费 30000 ⇒ 总 3 晚、次月 1、当月 2、当月金额 20000、次月金额 10000。 */
    @Test
    void lodgingNumbersMatchTheBrief() {
        Row cross = stay("2026-07-30", "2026-08-02", "30000");
        expect(cross, "nights", "3");
        expect(cross, "next_nights", "1");
        expect(cross, "cur_nights", "2");
        expect(cross, "cur_amount", "20000");
        expect(cross, "next_amount", "10000");
        expect(cross, "l_live", "2");
        expect(cross, "l_saved", "2");
        expect(cross, "l_month", "1");

        Row same = stay("2026-08-03", "2026-08-06", "30000");
        expect(same, "nights", "3");
        expect(same, "next_nights", "0");
        expect(same, "cur_nights", "3");
        expect(same, "cur_amount", "30000");
        expect(same, "next_amount", "0");

        Row year = stay("2026-12-30", "2027-01-02", "30000");
        expect(year, "nights", "3");
        expect(year, "next_nights", "1");
        expect(year, "cur_nights", "2");
        expect(year, "cur_amount", "20000");
        expect(year, "next_amount", "10000");
        expect(year, "l_month", "-11");

        // 退房日是次月 1 日：最后一晚在当月，次月 0 晚。
        Row first = stay("2026-07-30", "2026-08-01", "30000");
        expect(first, "nights", "2");
        expect(first, "next_nights", "0");
        expect(first, "cur_nights", "2");
        expect(first, "cur_amount", "30000");
        expect(first, "next_amount", "0");

        // 除不尽：10000 × 1 ÷ 3 = 3333.33… ⇒ 向下取整 3333，次月 6667；两者相加仍等于房费。
        Row uneven = stay("2026-07-31", "2026-08-03", "10000");
        expect(uneven, "cur_nights", "1");
        expect(uneven, "cur_amount", "3333");
        expect(uneven, "next_amount", "6667");
        Row uneven2 = stay("2026-07-30", "2026-08-02", "10000");
        expect(uneven2, "cur_amount", "6666");
        expect(uneven2, "next_amount", "3334");

        // 退房早于入住：天数为负，不报错。
        Row reversed = stay("2026-08-02", "2026-07-30", "30000");
        expect(reversed, "nights", "-3");
        expect(reversed, "g_days", "-3");
        expect(reversed, "next_nights", "29");
        expect(reversed, "cur_nights", "-32");

        // 日期为空：结果为空；金额等依赖齐了再算（不写 0）。
        Row empty = create("都空", null, null, null, "30000", null);
        for (String code : List.of("nights", "next_nights", "cur_nights", "cur_amount", "l_live"))
            expect(empty, code, null);
        Row noOut = create("没填退房日", "2026-07-30", null, null, "30000", null);
        for (String code : List.of("nights", "next_nights", "cur_nights", "cur_amount"))
            expect(noOut, code, null);
        // 只空入住日：IF 的条件为空走「否则」分支（现有口径），得到 DAY(退房日) − 1；生成列与求值器相同。
        Row noIn = create("没填入住日", null, "2026-08-02", null, "30000", null);
        expect(noIn, "nights", null);
        expect(noIn, "next_nights", "1");
        expect(noIn, "cur_nights", null);
        expect(noIn, "cur_amount", null);
    }

    /** 生成列（数据库算）与求值器（Java 算）逐格相同：闰年 2 月、月末、跨年、负天数、日期时间、空值。 */
    @Test
    void generatedColumnsEqualTheEvaluatorCellByCell() {
        Object[][] matrix = {
            {"2026-07-30", "2026-08-02", "2026-07-31T23:30:00", 1},
            {"2026-08-03", "2026-08-06", "2026-08-03T00:00:00", 0},
            {"2026-12-30", "2027-01-02", "2027-01-01T08:00:00", 2},
            {"2026-01-31", "2026-02-28", "2026-02-01T12:00:00", 1},
            {"2024-01-31", "2024-02-29", "2024-02-29T23:59:59", 1},
            {"2024-02-29", "2025-02-28", "2024-03-01T00:00:01", 12},
            {"2024-02-29", "2028-02-29", "2024-02-28T10:00:00", 48},
            {"2026-03-31", "2026-02-28", "2026-03-30T10:00:00", -1},
            {"2027-01-15", "2026-01-15", "2026-12-31T10:00:00", -13},
            {"2026-08-02", "2026-07-30", "2026-08-08T10:00:00", 30},
            {"2026-01-01", "2026-12-31", "2026-06-15T10:00:00", 365},
            {"2026-08-03", "2026-08-03", null, null},
            {null, "2026-08-02", "2026-08-02T10:00:00", 3},
            {"2026-07-30", null, null, 1},
            {null, null, null, null}
        };
        Map<String, String> codes = new HashMap<>();
        for (String code : List.of("c_in", "c_out", "c_at", "c_n")) codes.put(code, code);
        int cells = 0;
        for (int index = 0; index < matrix.length; index++) {
            Object[] item = matrix[index];
            Row row =
                    create(
                            "矩阵" + index,
                            (String) item[0],
                            (String) item[1],
                            (String) item[2],
                            null,
                            (Integer) item[3]);
            Map<String, Object> values = new HashMap<>();
            values.put("c_in", item[0]);
            values.put("c_out", item[1]);
            values.put("c_at", item[2]);
            values.put("c_n", item[3] == null ? null : new BigDecimal((Integer) item[3]));
            for (var entry : GENERATED.entrySet()) {
                Object expected =
                        FormulaEvaluator.evaluate(
                                FieldExpressions.parse(entry.getValue(), codes).expression(),
                                values::get);
                Object actual = row.values().get(field(entry.getKey()));
                String where = "第 " + index + " 行 " + entry.getKey() + " = " + entry.getValue();
                if (expected == null) assertThat(actual).as(where).isNull();
                else {
                    assertThat(actual).as(where).isNotNull();
                    assertThat(new BigDecimal(actual.toString()))
                            .as(where)
                            .isEqualByComparingTo((BigDecimal) expected);
                }
                cells++;
            }
        }
        assertThat(cells).isEqualTo(matrix.length * GENERATED.size());
        // 再钉几个绝对值，防止两边一起错。
        Row leap = page(Map.of(field("name"), "矩阵4"), null, false).getFirst();
        expect(leap, "g_year", "2024");
        expect(leap, "g_month", "1");
        expect(leap, "g_day", "31");
        expect(leap, "g_wd1", "4");
        expect(leap, "g_wd2", "3");
        expect(leap, "g_wd3", "2");
        expect(leap, "g_eom", "20240229");
        expect(leap, "g_eom0", "20240131");
        expect(leap, "g_edate", "20240229");
        expect(leap, "g_date", "20240302");
        expect(leap, "g_add", "20240201");
        expect(leap, "g_sub", "20240130");
        expect(leap, "g_at_day", "29");
        expect(leap, "g_at_days", "29");
        expect(leap, "g_at_add", "20240301");
        expect(leap, "g_dif_m", "0");
        expect(leap, "g_frac_add", "20240201");
        expect(leap, "g_frac_sub", "20240129");
        expect(leap, "g_frac_edate", "20240229");
        expect(leap, "g_frac_back", "20231231");
        expect(leap, "g_frac_eom", "20240131");
        expect(leap, "g_frac_date", "20260730");
        Row years = page(Map.of(field("name"), "矩阵6"), null, false).getFirst();
        expect(years, "g_dif_d", "1461");
        expect(years, "g_dif_m", "48");
        expect(years, "g_dif_y", "4");
        expect(years, "g_edate", "20280229");
        Row back = page(Map.of(field("name"), "矩阵8"), null, false).getFirst();
        expect(back, "g_dif_d", "-365");
        expect(back, "g_dif_m", "-12");
        expect(back, "g_dif_y", "-1");
        expect(back, "g_edate", "20251215");
        expect(back, "g_cmp", "0");
    }

    /** 读取时计算的公式可以用 TODAY()，每次读取按当天算；引用其它公式字段的写法照常。 */
    @Test
    void liveFormulaUsesToday() {
        LocalDate before = LocalDate.now(FormulaDates.ZONE);
        Row row = stay("2026-01-01", "2026-01-03", "1000");
        Object value =
                runtime.get(app, definition.objectId(), row.id(), 10001)
                        .record()
                        .values()
                        .get(field("l_today"));
        LocalDate after = LocalDate.now(FormulaDates.ZONE);
        assertThat(new BigDecimal(value.toString()).longValueExact())
                .isBetween(
                        ChronoUnit.DAYS.between(LocalDate.of(2026, 1, 1), before),
                        ChronoUnit.DAYS.between(LocalDate.of(2026, 1, 1), after));
    }

    /** 公式字段在列表里的筛选、排序与现有数值公式字段一样：按数值比、按数值排。 */
    @Test
    void formulaFieldsFilterAndSortAsNumbers() {
        stay("2026-07-30", "2026-08-02", "30000");
        stay("2026-08-03", "2026-08-06", "30000");
        stay("2026-07-01", "2026-07-11", "30000");
        stay("2026-07-28", "2026-08-03", "30000");
        assertThat(page(Map.of(), "nights", false))
                .extracting(
                        row ->
                                new BigDecimal(row.values().get(field("nights")).toString())
                                        .intValueExact())
                .containsExactly(3, 3, 6, 10);
        assertThat(page(Map.of(), "next_nights", true))
                .extracting(
                        row ->
                                new BigDecimal(row.values().get(field("next_nights")).toString())
                                        .intValueExact())
                .containsExactly(2, 1, 0, 0);
        assertThat(page(Map.of(field("next_nights"), 0), "nights", false))
                .extracting(row -> row.values().get(field("name")))
                .containsExactly("2026-08-03~2026-08-06", "2026-07-01~2026-07-11");
        assertThat(page(Map.of(field("nights"), 10), null, false)).hasSize(1);
    }

    /** 改日期、改房费后：生成列由数据库重算，公式默认值与保存时落库的公式由服务端重算。 */
    @Test
    void editingRecomputesEverything() {
        Row row = stay("2026-07-30", "2026-08-02", "30000");
        Row changed =
                runtime.save(
                                new Save(
                                        app,
                                        definition.objectId(),
                                        row.id(),
                                        row.revision(),
                                        Map.of(field("c_out"), "2026-08-04"),
                                        Map.of()),
                                10001)
                        .record();
        expect(changed, "nights", "5");
        expect(changed, "next_nights", "3");
        expect(changed, "cur_nights", "2");
        expect(changed, "cur_amount", "12000");
        expect(changed, "next_amount", "18000");
        expect(changed, "l_saved", "2");
        Row repriced =
                runtime.save(
                                new Save(
                                        app,
                                        definition.objectId(),
                                        changed.id(),
                                        changed.revision(),
                                        Map.of(field("c_fee"), "10000"),
                                        Map.of()),
                                10001)
                        .record();
        expect(repriced, "cur_amount", "4000");
        expect(repriced, "next_amount", "6000");
        // 客户端伪造金额不被接受。
        Row forged =
                runtime.save(
                                new Save(
                                        app,
                                        definition.objectId(),
                                        repriced.id(),
                                        repriced.revision(),
                                        Map.of(field("cur_amount"), "1"),
                                        Map.of()),
                                10001)
                        .record();
        expect(forged, "cur_amount", "4000");
    }

    /** 导入：每行同样算出天数与金额。 */
    @Test
    void importComputesTheSameValues() {
        List<Map<String, Object>> rows =
                List.of(
                        input("导入A", "2026-07-30", "2026-08-02", null, "30000", null),
                        input("导入B", "2026-07-31", "2026-08-03", null, "10000", null),
                        input("导入C", "2026-12-30", "2027-01-02", null, "30000", null));
        assertThat(runtime.importRecords(app, definition.objectId(), rows, 10001)).isEqualTo(3);
        Map<Object, Row> byName = new HashMap<>();
        for (Row row : page(Map.of(), null, false))
            byName.put(row.values().get(field("name")), row);
        expect(byName.get("导入A"), "nights", "3");
        expect(byName.get("导入A"), "next_nights", "1");
        expect(byName.get("导入A"), "cur_nights", "2");
        expect(byName.get("导入A"), "cur_amount", "20000");
        expect(byName.get("导入A"), "next_amount", "10000");
        expect(byName.get("导入A"), "l_saved", "2");
        expect(byName.get("导入B"), "cur_amount", "3333");
        expect(byName.get("导入B"), "next_amount", "6667");
        expect(byName.get("导入C"), "next_nights", "1");
        expect(byName.get("导入C"), "cur_amount", "20000");
    }

    /** 对象数据维护入口（不带应用）保存时同样重算。 */
    @Test
    void objectDataMaintenanceComputesTheSameValues() {
        ObjectDataMaintenance.Model model = maintenance.model(objectDraftId, 10001L);
        Map<String, Object> values = input("维护入口", "2026-07-31", "2026-08-03", null, "10000", null);
        values.put(field("cur_amount"), "999");
        Row saved =
                maintenance
                        .save(
                                new ObjectDataMaintenance.Save(
                                        objectDraftId,
                                        model.versionNo(),
                                        model.checksum(),
                                        null,
                                        null,
                                        values,
                                        UUID.randomUUID().toString()),
                                10001L)
                        .record();
        expect(saved, "nights", "3");
        expect(saved, "next_nights", "2");
        expect(saved, "cur_nights", "1");
        expect(saved, "cur_amount", "3333");
        expect(saved, "next_amount", "6667");
        expect(saved, "g_wd2", "5");
    }

    /** 入住日与退房日同一天：总晚数 0，「÷ 总晚数」按现有口径报除数为零（保存被拒，文案点名字段），不悄悄存 0。 */
    @Test
    void zeroNightsFollowsExistingDivideByZeroRule() {
        assertThatThrownBy(() -> stay("2026-08-03", "2026-08-03", "30000"))
                .hasMessageContaining("公式默认值无法求值：公式除数不能为零");
        // 没填房费时不触发除法（依赖为空 ⇒ 结果为空）。
        Row row = create("同日未填房费", "2026-08-03", "2026-08-03", null, null, null);
        expect(row, "nights", "0");
        expect(row, "next_nights", "0");
        expect(row, "cur_amount", null);
    }

    /** 内部明细里的公式字段。 */
    @Test
    void detailFormulaFields() {
        DataCenter.Detail detail = definition.details().getFirst();
        Map<String, String> ids = new HashMap<>();
        detail.fields().forEach(f -> ids.put(f.code(), f.id()));
        Aggregate saved =
                runtime.save(
                        new Save(
                                app,
                                definition.objectId(),
                                null,
                                null,
                                input("带分段", "2026-07-30", "2026-08-02", null, "30000", null),
                                Map.of(
                                        detail.id(),
                                        List.of(
                                                new Row(
                                                        null,
                                                        null,
                                                        Map.<String, Object>of(
                                                                ids.get("d_from"), "2026-07-30",
                                                                ids.get("d_to"), "2026-08-01")),
                                                new Row(
                                                        null,
                                                        null,
                                                        Map.<String, Object>of(
                                                                ids.get("d_from"), "2026-12-31",
                                                                ids.get("d_to"), "2027-01-02")),
                                                new Row(
                                                        null,
                                                        null,
                                                        Map.<String, Object>of(
                                                                ids.get("d_from"),
                                                                "2026-08-01"))))),
                        10001);
        List<Row> lines = saved.details().get(detail.id());
        assertThat(lines).hasSize(3);
        number(lines.get(0).values().get(ids.get("d_nights")), "2");
        number(lines.get(0).values().get(ids.get("d_month")), "8");
        number(lines.get(1).values().get(ids.get("d_nights")), "2");
        number(lines.get(1).values().get(ids.get("d_month")), "1");
        assertThat(lines.get(2).values().get(ids.get("d_nights"))).isNull();
        assertThat(lines.get(2).values().get(ids.get("d_month"))).isNull();
    }

    /** 保存对象时的拦截：落库的公式不能用 TODAY() / NOW()；结果不能是日期；参数类型不对时点名函数与字段。 */
    @Test
    void savingTheObjectRejectsWithPlainMessages() {
        String stale = "每天的结果都不一样，而这个字段的值是保存下来的";
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        design(
                                                "fx_b1",
                                                Map.of("bad", formula("TODAY() - c_in", null))),
                                        10001))
                .hasMessageContaining("公式字段「")
                .hasMessageContaining("TODAY() " + stale);
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        design(
                                                "fx_b2",
                                                Map.of(
                                                        "bad",
                                                        formula("DAYS(NOW(), c_in)", "ON_SAVE"))),
                                        10001))
                .hasMessageContaining("NOW() " + stale);
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        design(
                                                "fx_b3",
                                                Map.of("bad", formula("EOMONTH(c_in, 0)", null))),
                                        10001))
                .hasMessageContaining("这条公式最后算出来的是一个日期");
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        design("fx_b4", Map.of("bad", formula("c_in + 1", "LIVE"))),
                                        10001))
                .hasMessageContaining("这条公式最后算出来的是一个日期");
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        design(
                                                "fx_b5",
                                                Map.of("bad", formula("MONTH(c_fee)", null))),
                                        10001))
                .hasMessageContaining("MONTH 的第 1 个参数要填日期或日期时间字段")
                .hasMessageContaining("是数字");
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        design(
                                                "fx_b6",
                                                Map.of("bad", formula("MONTH([入住日])", null))),
                                        10001))
                .hasMessageContaining("字段请直接写字段编码，不带方括号");
    }
}
