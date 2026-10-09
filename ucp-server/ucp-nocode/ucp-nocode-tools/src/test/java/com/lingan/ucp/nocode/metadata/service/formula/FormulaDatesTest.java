package com.lingan.ucp.nocode.metadata.service.formula;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.FieldOptions;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaDates.Context;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaDates.Kind;
import com.lingan.ucp.nocode.metadata.service.object.FieldRuleValidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 公式的日期函数（不连库）：解析、求值、空值与边界、钉钉写法、保存时的类型检查文案、落库改写。 数据库生成列与这里的求值逐项相同由 FormulaDatesIntegrationTest 另测。
 */
class FormulaDatesTest {
    private static final Map<String, String> COLUMNS =
            Map.of(
                    "c_in", "c_in",
                    "c_out", "c_out",
                    "c_at", "c_at",
                    "c_fee", "c_fee",
                    "c_n", "c_n",
                    "c_memo", "c_memo");
    private static final Map<String, Kind> KINDS =
            Map.of(
                    "c_in", Kind.DATE,
                    "c_out", Kind.DATE,
                    "c_at", Kind.DATETIME,
                    "c_fee", Kind.NUMBER,
                    "c_n", Kind.NUMBER,
                    "c_memo", Kind.TEXT);
    private static final Map<String, String> LABELS =
            Map.of(
                    "c_in", "入住日",
                    "c_out", "退房日",
                    "c_at", "登记时间",
                    "c_fee", "房费",
                    "c_n", "天数",
                    "c_memo", "备注");

    /** 业务方在钉钉里拼的那条（方括号由编辑器换成字段编码，其余一字不改）。 */
    private static final String NEXT_MONTH_NIGHTS =
            "IF( MONTH( c_in) = MONTH( c_out), 0, DAY( c_out)-1)";

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }

    private static Object eval(String formula, Map<String, Object> values) {
        return FormulaEvaluator.evaluate(
                FieldExpressions.parse(formula, COLUMNS).expression(), values::get);
    }

    private static Object eval(String formula, String in, String out) {
        return eval(formula, row("c_in", in, "c_out", out));
    }

    private static void number(Object actual, String expected) {
        assertThat(actual).isInstanceOf(BigDecimal.class);
        assertThat((BigDecimal) actual).isEqualByComparingTo(expected);
    }

    private static Context context(boolean volatileAllowed, boolean generated) {
        return new Context(KINDS, LABELS, volatileAllowed, generated);
    }

    private static void checkField(String formula, boolean volatileAllowed, boolean generated) {
        FormulaDates.checkField(
                "次月天数",
                FieldExpressions.parse(formula, COLUMNS).expression(),
                context(volatileAllowed, generated));
    }

    // ── 钉钉那条公式与民宿五个数 ──

    @ParameterizedTest
    @CsvSource({
        // 入住, 退房, 总晚数, 次月天数, 当月天数
        "2026-07-30, 2026-08-02, 3, 1, 2",
        "2026-08-03, 2026-08-06, 3, 0, 3",
        "2026-12-30, 2027-01-02, 3, 1, 2",
        "2026-07-30, 2026-08-01, 2, 0, 2",
        "2026-08-02, 2026-07-30, -3, 29, -32"
    })
    void dingtalkFormulaGivesNights(String in, String out, String total, String next, String cur) {
        number(eval("c_out - c_in", in, out), total);
        number(eval("DAYS(c_out, c_in)", in, out), total);
        number(eval("DATEDIF(c_in, c_out, \"D\")", in, out), total);
        number(eval(NEXT_MONTH_NIGHTS, in, out), next);
        number(eval("(c_out - c_in) - (" + NEXT_MONTH_NIGHTS + ")", in, out), cur);
    }

    /** 金额口径不变：公式不取整，取整只在规则的取整方式上（缺省向下取整）。 */
    @ParameterizedTest
    @CsvSource({
        "30000, 2026-07-30, 2026-08-02, 20000, 10000",
        "10000, 2026-07-30, 2026-08-01, 10000, 0",
        "10000, 2026-07-31, 2026-08-03, 3333, 6667",
        "10000, 2026-07-30, 2026-08-02, 6666, 3334"
    })
    void monthAmountFloorsOnlyThroughRoundingMode(
            String fee, String in, String out, String current, String next) {
        Object raw =
                eval(
                        "c_fee * ((c_out - c_in) - (" + NEXT_MONTH_NIGHTS + ")) / (c_out - c_in)",
                        row("c_fee", new BigDecimal(fee), "c_in", in, "c_out", out));
        BigDecimal settled = MoneyRounding.settle((BigDecimal) raw, RoundingMode.FLOOR);
        assertThat(settled).isEqualByComparingTo(current);
        assertThat(new BigDecimal(fee).subtract(settled)).isEqualByComparingTo(next);
    }

    @Test
    void emptyDatesFollowExistingNullRules() {
        // 两个日期都空 ⇒ 空；IF 的条件为空走「否则」分支（现有口径），所以只空入住日时得到 DAY(退房日)-1。
        assertThat(eval(NEXT_MONTH_NIGHTS, null, null)).isNull();
        number(eval(NEXT_MONTH_NIGHTS, null, "2026-08-02"), "1");
        assertThat(eval(NEXT_MONTH_NIGHTS, "2026-07-30", null)).isNull();
        assertThat(eval("c_out - c_in", "2026-07-30", null)).isNull();
        assertThat(eval("c_out - c_in", null, "2026-08-02")).isNull();
        for (String formula :
                List.of(
                        "YEAR(c_in)",
                        "MONTH(c_in)",
                        "DAY(c_in)",
                        "WEEKDAY(c_in)",
                        "WEEKDAY(c_in, 2)",
                        "DAYS(c_out, c_in)",
                        "DAYS(c_in, c_out)",
                        "DATEDIF(c_in, c_out, 'M')",
                        "EOMONTH(c_in, 0)",
                        "EOMONTH(c_in)",
                        "EDATE(c_in, 1)",
                        "EDATE(c_out, c_n)",
                        "DATE(c_n, 1, 1)",
                        "DATE(2026, c_n, 1)",
                        "c_in + 1",
                        "c_in - 1",
                        "c_n + c_out"))
            assertThat(eval(formula, null, "2026-08-02")).as(formula).isNull();
    }

    // ── 逐个函数 ──

    @Test
    void yearMonthDay() {
        number(eval("YEAR(c_in)", "2026-07-30", null), "2026");
        number(eval("MONTH(c_in)", "2026-07-30", null), "7");
        number(eval("DAY(c_in)", "2026-07-30", null), "30");
        number(eval("MONTH(c_in)", "2026-01-01", null), "1");
        number(eval("MONTH(c_in)", "2026-12-31", null), "12");
        number(eval("DAY(c_in)", "2024-02-29", null), "29");
    }

    /** 日期时间按写出来的那一天算，不做时区换算；对象、文本两种形态相同。 */
    @Test
    void datetimeUsesItsDatePart() {
        for (Object at :
                List.of(
                        "2026-07-31T23:59:59",
                        "2026-07-31 00:00",
                        "2026-07-31T08:30+09:00",
                        LocalDateTime.of(2026, 7, 31, 23, 59),
                        LocalDate.of(2026, 7, 31),
                        java.sql.Date.valueOf("2026-07-31"),
                        java.sql.Timestamp.valueOf("2026-07-31 23:59:59"))) {
            number(eval("DAY(c_at)", row("c_at", at)), "31");
            number(eval("MONTH(c_at)", row("c_at", at)), "7");
            number(eval("DAYS(c_at, c_in)", row("c_at", at, "c_in", "2026-07-30")), "1");
            assertThat(eval("c_at + 1", row("c_at", at))).isEqualTo("2026-08-01");
        }
    }

    @ParameterizedTest
    @CsvSource({
        // 2026-08-02 是周日，2026-08-03 周一，2026-08-08 周六
        "2026-08-02, 1, 7, 6",
        "2026-08-03, 2, 1, 0",
        "2026-08-08, 7, 6, 5"
    })
    void weekdayThreeConventions(String date, String sundayFirst, String mondayFirst, String zero) {
        number(eval("WEEKDAY(c_in)", date, null), sundayFirst);
        number(eval("WEEKDAY(c_in, 1)", date, null), sundayFirst);
        number(eval("WEEKDAY(c_in, 2)", date, null), mondayFirst);
        number(eval("WEEKDAY(c_in, 3)", date, null), zero);
    }

    @Test
    void daysIsEndMinusStartAndMayBeNegative() {
        number(eval("DAYS(c_out, c_in)", "2026-07-30", "2026-08-02"), "3");
        number(eval("DAYS(c_in, c_out)", "2026-07-30", "2026-08-02"), "-3");
        number(eval("c_in - c_out", "2026-07-30", "2026-08-02"), "-3");
        number(eval("DAYS(c_out, c_in)", "2026-12-30", "2027-01-02"), "3");
        number(eval("DAYS(c_out, c_in)", "2024-02-28", "2024-03-01"), "2");
        number(eval("DAYS(c_out, c_in)", "2026-02-28", "2026-03-01"), "1");
        number(eval("DAYS(c_out, c_in)", "2026-07-30", "2026-07-30"), "0");
    }

    @ParameterizedTest
    @CsvSource({
        "2026-01-31, 2026-02-28, 28, 0, 0",
        "2026-01-31, 2026-03-31, 59, 2, 0",
        "2026-01-15, 2027-01-14, 364, 11, 0",
        "2026-01-15, 2027-01-15, 365, 12, 1",
        "2024-02-29, 2025-02-28, 365, 11, 0",
        "2024-02-29, 2028-02-29, 1461, 48, 4",
        "2027-01-15, 2026-01-15, -365, -12, -1",
        "2026-03-31, 2026-02-28, -31, -1, 0"
    })
    void datedifDayMonthYear(String start, String end, String d, String m, String y) {
        number(eval("DATEDIF(c_in, c_out, \"D\")", start, end), d);
        number(eval("DATEDIF(c_in, c_out, 'M')", start, end), m);
        number(eval("datedif(c_in, c_out, 'y')", start, end), y);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-07-30, 0, 2026-07-31",
        "2026-07-30, 1, 2026-08-31",
        "2026-01-31, 1, 2026-02-28",
        "2024-01-31, 1, 2024-02-29",
        "2026-12-15, 1, 2027-01-31",
        "2026-01-15, -1, 2025-12-31",
        "2026-03-31, -1, 2026-02-28",
        "2026-07-30, 1.9, 2026-08-31"
    })
    void eomonth(String date, String months, String expected) {
        assertThat(eval("EOMONTH(c_in, " + months + ")", date, null)).isEqualTo(expected);
    }

    @Test
    void eomonthMonthsDefaultToZero() {
        assertThat(eval("EOMONTH(c_in)", "2024-02-10", null)).isEqualTo("2024-02-29");
        number(eval("DAY(EOMONTH(c_in))", "2026-02-10", null), "28");
    }

    @ParameterizedTest
    @CsvSource({
        "2026-07-30, 1, 2026-08-30",
        "2026-01-31, 1, 2026-02-28",
        "2024-01-31, 1, 2024-02-29",
        "2026-03-31, -1, 2026-02-28",
        "2026-12-30, 2, 2027-02-28",
        "2026-07-30, 0, 2026-07-30",
        "2024-02-29, 12, 2025-02-28",
        "2026-07-30, -1.9, 2026-06-30"
    })
    void edateKeepsDayOrClampsToMonthEnd(String date, String months, String expected) {
        assertThat(eval("EDATE(c_in, " + months + ")", date, null)).isEqualTo(expected);
    }

    @Test
    void dateBuildsAndRollsOverLikeExcel() {
        assertThat(eval("DATE(2026, 7, 30)", Map.of())).isEqualTo("2026-07-30");
        assertThat(eval("DATE(2024, 2, 29)", Map.of())).isEqualTo("2024-02-29");
        // 月、日越界时顺延（Excel 同样）：13 月 = 次年 1 月；0 日 = 上月最后一天；2 月 30 日 = 3 月 2 日（平年）。
        assertThat(eval("DATE(2026, 13, 1)", Map.of())).isEqualTo("2027-01-01");
        assertThat(eval("DATE(2026, 3, 0)", Map.of())).isEqualTo("2026-02-28");
        assertThat(eval("DATE(2026, 2, 30)", Map.of())).isEqualTo("2026-03-02");
        assertThat(eval("DATE(YEAR(c_in), MONTH(c_in) + 1, 1)", "2026-12-30", null))
                .isEqualTo("2027-01-01");
        assertThatThrownBy(() -> eval("DATE(0, 1, 1)", Map.of()))
                .hasMessageContaining("DATE 的年份须在 1 到 9999 之间");
        assertThatThrownBy(() -> eval("DATE(10000, 1, 1)", Map.of()))
                .hasMessageContaining("DATE 的年份须在 1 到 9999 之间");
    }

    @Test
    void datePlusMinusDays() {
        assertThat(eval("c_in + 3", "2026-07-30", null)).isEqualTo("2026-08-02");
        assertThat(eval("3 + c_in", "2026-07-30", null)).isEqualTo("2026-08-02");
        assertThat(eval("c_in - 30", "2026-07-30", null)).isEqualTo("2026-06-30");
        assertThat(eval("c_in + 1", "2026-12-31", null)).isEqualTo("2027-01-01");
        assertThat(eval("c_in + 1", "2024-02-28", null)).isEqualTo("2024-02-29");
        assertThat(eval("c_in + 1", "2026-02-28", null)).isEqualTo("2026-03-01");
        // 天数只取整数部分。
        assertThat(eval("c_in + 1.9", "2026-07-30", null)).isEqualTo("2026-07-31");
        assertThat(eval("c_in + c_n", row("c_in", "2026-07-30", "c_n", new BigDecimal("2"))))
                .isEqualTo("2026-08-01");
        // 算出来的日期可以继续算、继续比。
        number(eval("DAY(c_in + 3)", "2026-07-30", null), "2");
        assertThat(eval("c_in + 3 = c_out", "2026-07-30", "2026-08-02")).isEqualTo(true);
        assertThat(eval("EOMONTH(c_in, 0) < c_out", "2026-07-30", "2026-08-02")).isEqualTo(true);
        assertThat(eval("c_out > c_in", "2026-07-30", "2026-08-02")).isEqualTo(true);
        assertThatThrownBy(() -> eval("c_in + c_out", "2026-07-30", "2026-08-02"))
                .hasMessageContaining("两个日期不能相加");
        assertThatThrownBy(() -> eval("3 - c_in", "2026-07-30", null))
                .hasMessageContaining("数字不能减日期");
    }

    @Test
    void todayAndNowUseTheSystemZone() {
        LocalDate before = LocalDate.now(FormulaDates.ZONE);
        Object today = eval("TODAY()", Map.of());
        Object now = eval("NOW()", Map.of());
        Object sinceToday = eval("today( ) - c_in", "2026-01-01", null);
        LocalDate after = LocalDate.now(FormulaDates.ZONE);
        assertThat(today).isIn(before.toString(), after.toString());
        assertThat(now.toString()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2})?");
        assertThat(FormulaDates.dateOrNull(now)).isIn(before, after);
        assertThat(((BigDecimal) sinceToday).longValueExact())
                .isBetween(
                        java.time.temporal.ChronoUnit.DAYS.between(
                                LocalDate.of(2026, 1, 1), before),
                        java.time.temporal.ChronoUnit.DAYS.between(
                                LocalDate.of(2026, 1, 1), after));
        assertThat(FormulaDates.ZONE.getId()).isEqualTo("Asia/Shanghai");
        assertThatThrownBy(() -> FieldExpressions.parse("TODAY(1)", COLUMNS))
                .hasMessageContaining("TODAY() 不带参数");
    }

    // ── 写法兼容 ──

    @Test
    void namesAreCaseInsensitiveAndSpacesAreFree() {
        for (String formula :
                List.of("month(c_in)", "Month ( c_in )", "MONTH(  c_in)", "\n MONTH\t(c_in\n)"))
            number(eval(formula, "2026-07-30", null), "7");
        number(
                eval("if( month( c_in)=month( c_out),0,day( c_out)-1)", "2026-07-30", "2026-08-02"),
                "1");
        // 「=」与「==」等价（存量写法都还在）。
        assertThat(eval("MONTH(c_in) == MONTH(c_out)", "2026-07-30", "2026-08-02"))
                .isEqualTo(false);
        assertThat(eval("MONTH(c_in) = MONTH(c_out)", "2026-07-01", "2026-07-02")).isEqualTo(true);
        assertThat(eval("MONTH(c_in) != MONTH(c_out)", "2026-07-30", "2026-08-02")).isEqualTo(true);
        assertThat(eval("MONTH(c_in) <> MONTH(c_out)", "2026-07-30", "2026-08-02")).isEqualTo(true);
    }

    @Test
    void doubleQuotedTextIsTheSameAsSingleQuoted() {
        var doubled =
                FieldExpressions.parse("c_memo || \"a'b\" || \"x\"\"y\"", COLUMNS).expression();
        var single = FieldExpressions.parse("c_memo || 'a''b' || 'x\"y'", COLUMNS).expression();
        assertThat(doubled).isEqualTo(single);
        assertThatThrownBy(() -> FieldExpressions.parse("\"abc", COLUMNS)).hasMessage("公式文本未闭合");
    }

    @Test
    void bracketReferenceGetsARewriteHint() {
        assertThatThrownBy(() -> FieldExpressions.parse("MONTH([入住日])", COLUMNS))
                .hasMessage("字段请直接写字段编码，不带方括号；[字段名] 的写法请在公式编辑器里粘贴，编辑器会自动换成字段编码");
    }

    @Test
    void arityErrorsNameTheFunction() {
        assertThatThrownBy(() -> FieldExpressions.parse("MONTH(c_in, 1)", COLUMNS))
                .hasMessage("MONTH 的参数个数不对：需要 1 个，写了 2 个");
        assertThatThrownBy(() -> FieldExpressions.parse("DAYS(c_in)", COLUMNS))
                .hasMessage("DAYS 的参数个数不对：需要 2 个，写了 1 个");
        assertThatThrownBy(() -> FieldExpressions.parse("WEEKDAY(c_in, 1, 2)", COLUMNS))
                .hasMessage("WEEKDAY 的参数个数不对：需要 1 到 2 个，写了 3 个");
        assertThatThrownBy(() -> FieldExpressions.parse("date_add(c_in, 1)", COLUMNS))
                .hasMessage("不支持公式函数：date_add");
        assertThatThrownBy(() -> FieldExpressions.parse("FLOOR(c_fee)", COLUMNS))
                .hasMessage("不支持公式函数：floor");
    }

    @Test
    void existingFormulasParseAndEvaluateAsBefore() {
        Map<String, Object> values =
                row("c_fee", new BigDecimal("30000"), "c_n", new BigDecimal("3"), "c_memo", "ab");
        number(eval("round(c_fee / c_n, 2)", values), "10000.00");
        number(eval("c_fee - c_n * 2", values), "29994");
        number(eval("abs(c_n - c_fee)", values), "29997");
        number(eval("if(c_fee >= 100, c_fee * 0.9, c_fee) + coalesce(c_n, 0)", values), "27003.0");
        assertThat(eval("upper(c_memo) || '-' || c_n", values)).isEqualTo("AB-3");
        assertThat(eval("and(c_fee > 1, not(isblank(c_memo)))", values)).isEqualTo(true);
        // 文本看起来不像日期时，加减仍按数字规则报错。
        assertThatThrownBy(() -> eval("c_memo + 1", values)).hasMessage("公式操作数须为数值");
        assertThatThrownBy(() -> eval("c_memo - c_fee", values)).hasMessage("公式操作数须为数值");
    }

    @Test
    void wrongValueAtRuntimeNamesTheFunction() {
        assertThatThrownBy(() -> eval("MONTH(c_memo)", row("c_memo", "七月")))
                .hasMessage("MONTH 需要日期，实际是「七月」");
        assertThatThrownBy(() -> eval("MONTH(c_fee)", row("c_fee", new BigDecimal("7"))))
                .hasMessage("MONTH 需要日期，实际是「7」");
        assertThatThrownBy(
                        () ->
                                eval(
                                        "WEEKDAY(c_in, c_n)",
                                        row("c_in", "2026-07-30", "c_n", new BigDecimal("4"))))
                .hasMessage("WEEKDAY 的第二个参数只能是 1、2 或 3");
        assertThatThrownBy(
                        () ->
                                eval(
                                        "DATEDIF(c_in, c_out, c_memo)",
                                        row(
                                                "c_in",
                                                "2026-07-30",
                                                "c_out",
                                                "2026-08-02",
                                                "c_memo",
                                                "W")))
                .hasMessageContaining("DATEDIF 的第三个参数只能是");
        assertThatThrownBy(() -> eval("MONTH(c_in)", "2026-02-30", null))
                .hasMessage("MONTH 需要日期，实际是「2026-02-30」");
    }

    // ── 保存时的类型检查 ──

    @Test
    void dingtalkFormulaPassesAsStoredField() {
        checkField(NEXT_MONTH_NIGHTS, false, true);
        checkField("c_out - c_in", false, true);
        checkField("(c_out - c_in) - (" + NEXT_MONTH_NIGHTS + ")", false, true);
        checkField(
                "DAYS(c_out, c_at) + WEEKDAY(c_in, 2) + DATEDIF(c_in, c_out, \"m\")", false, true);
        checkField("IF(EOMONTH(c_in, 0) < c_out, DAY(c_out) - 1, 0)", false, true);
        checkField("YEAR(DATE(2026, c_n, 1) + 1) + MONTH('2026-07-30')", false, true);
        checkField("c_fee * 2 + c_n", false, true);
    }

    @Test
    void typeErrorsNameTheFunctionAndTheField() {
        assertThatThrownBy(() -> checkField("MONTH(c_fee)", false, true))
                .hasMessage("公式字段「次月天数」：MONTH 的第 1 个参数要填日期或日期时间字段（或其它日期函数的结果），「房费」是数字");
        assertThatThrownBy(() -> checkField("DAYS(c_out, c_memo)", false, true))
                .hasMessage("公式字段「次月天数」：DAYS 的第 2 个参数要填日期或日期时间字段（或其它日期函数的结果），「备注」是文字");
        assertThatThrownBy(() -> checkField("DAY('七月')", false, true))
                .hasMessageContaining("DAY 的第 1 个参数要填日期")
                .hasMessageContaining("日期请写成 '2026-07-30' 这样");
        assertThatThrownBy(() -> checkField("EDATE(c_in, c_memo)", false, true))
                .hasMessage("公式字段「次月天数」：EDATE 的第 2 个参数要填数字，「备注」是文字");
        assertThatThrownBy(() -> checkField("DATE(c_in, 1, 1)", false, true))
                .hasMessage("公式字段「次月天数」：DATE 的第 1 个参数要填数字，「入住日」是日期");
        assertThatThrownBy(() -> checkField("WEEKDAY(c_in, c_n)", false, true))
                .hasMessageContaining("WEEKDAY 的第二个参数请直接写 1、2 或 3");
        assertThatThrownBy(() -> checkField("WEEKDAY(c_in, 4)", false, true))
                .hasMessageContaining("WEEKDAY 的第二个参数请直接写 1、2 或 3");
        assertThatThrownBy(() -> checkField("DATEDIF(c_in, c_out, 'W')", false, true))
                .hasMessageContaining("DATEDIF 的第三个参数请直接写");
        assertThatThrownBy(() -> checkField("DATEDIF(c_in, c_out, c_memo)", false, true))
                .hasMessageContaining("DATEDIF 的第三个参数请直接写");
        assertThatThrownBy(() -> checkField("c_in + c_out", false, true))
                .hasMessageContaining("两个日期不能相加");
        assertThatThrownBy(() -> checkField("c_n - c_in", false, true))
                .hasMessageContaining("数字不能减日期");
        assertThatThrownBy(() -> checkField("c_in * 2", false, true))
                .hasMessageContaining("「入住日」是日期，不能做乘除");
        assertThatThrownBy(() -> checkField("c_fee / EOMONTH(c_in, 0)", false, true))
                .hasMessageContaining("EOMONTH 的结果是日期，不能做乘除");
        assertThatThrownBy(() -> checkField("c_in + c_memo", false, true))
                .hasMessageContaining("日期只能加减天数（数字）或减另一个日期，「备注」是文字");
        assertThatThrownBy(() -> checkField("IF(c_in > 5, 1, 0)", false, true))
                .hasMessageContaining("「入住日」是日期，不能直接和数字比较（另一边是数字 5）");
        assertThatThrownBy(() -> checkField("ABS(c_in)", false, true))
                .hasMessageContaining("ABS 只能用在数字上，「入住日」是日期");
        assertThatThrownBy(() -> checkField("IF(c_n > 1, c_in + 1, 0)", false, true))
                .hasMessageContaining("IF 的几个结果里有的是日期、有的是数字");
    }

    /** 公式字段的结果类型里没有日期：日期只能当中间值或用于比较。 */
    @Test
    void dateResultIsRejectedForFormulaFields() {
        for (String formula :
                List.of(
                        "EOMONTH(c_in, 0)",
                        "EDATE(c_in, 1)",
                        "DATE(2026, 1, 1)",
                        "c_in + 1",
                        "IF(c_n > 1, c_in + 1, c_out)",
                        "COALESCE(EDATE(c_in, 1), c_out)"))
            for (boolean generated : List.of(true, false))
                assertThatThrownBy(() -> checkField(formula, true, generated))
                        .as(formula)
                        .hasMessageStartingWith("公式字段「次月天数」：这条公式最后算出来的是一个日期")
                        .hasMessageContaining("再套一层 YEAR / MONTH / DAY");
        // 存量写法（字段本身是日期）不在本次检查范围，照旧。
        checkField("COALESCE(c_in, c_out)", false, false);
        checkField("c_in", false, false);
        checkField("IF(ISBLANK(c_in), 0, c_in)", false, false);
        checkField("COALESCE(c_in, 0)", false, false);
    }

    /** TODAY() / NOW()：落库的值会过期，只允许读取时计算与公式默认值。 */
    @Test
    void volatileFunctionsOnlyWhereTheValueIsNotStored() {
        for (String formula :
                List.of("TODAY() - c_in", "DAYS(NOW(), c_in)", "IF(c_out < today(), 1, 0)")) {
            assertThatThrownBy(() -> checkField(formula, false, true))
                    .as(formula)
                    .hasMessageContaining("每天的结果都不一样，而这个字段的值是保存下来的")
                    .hasMessageContaining("读取时计算");
            assertThatThrownBy(() -> checkField(formula, false, false))
                    .as(formula)
                    .hasMessageContaining("每天的结果都不一样");
            checkField(formula, true, false);
        }
    }

    @Test
    void zonedDatetimeIsRejectedOnlyInGeneratedColumns() {
        Map<String, Kind> kinds = new HashMap<>(KINDS);
        kinds.put("c_at", Kind.ZONED);
        var expression = FieldExpressions.parse("DAY(c_at) + (c_at - c_in)", COLUMNS).expression();
        assertThatThrownBy(
                        () ->
                                FormulaDates.checkField(
                                        "天", expression, new Context(kinds, LABELS, false, true)))
                .hasMessageContaining("「登记时间」是带时区的日期时间字段，本行公式（落库）里不能对它用 DAY");
        FormulaDates.checkField("天", expression, new Context(kinds, LABELS, false, false));
    }

    /** 没有字段类型信息时（例如试算没带类型）不下结论、不误报；试算的结果类型判断不抛类型错误。 */
    @Test
    void unknownFieldTypesAreNotGuessed() {
        Context unknown = new Context(Map.of(), Map.of(), true, false);
        for (String formula :
                List.of(
                        "YEAR(c_in + 1)",
                        ymd("(c_in + c_n)"),
                        "DAY(c_at - 1) + (c_out - c_in)",
                        "MONTH(c_memo)",
                        "c_in * 2"))
            FormulaDates.checkField(
                    "天", FieldExpressions.parse(formula, COLUMNS).expression(), unknown);
        assertThat(
                        FormulaDates.dateResult(
                                FieldExpressions.parse("EDATE(c_in, 1)", COLUMNS).expression(),
                                unknown))
                .isTrue();
        assertThat(
                        FormulaDates.dateResult(
                                FieldExpressions.parse("c_in + 1", COLUMNS).expression(), unknown))
                .isFalse();
        assertThat(
                        FormulaDates.dateResult(
                                FieldExpressions.parse("c_in + 1", COLUMNS).expression(),
                                context(true, false)))
                .isTrue();
        // 只有一边知道是日期、另一边没有类型信息：既可能得天数也可能得日期，同样不下结论。
        Context half = new Context(Map.of("c_out", Kind.DATE), Map.of(), true, false);
        for (String formula :
                List.of(
                        "DAY(c_out) + (c_out - c_in)",
                        "c_out - c_in",
                        "c_in - c_out",
                        "c_in + c_out"))
            FormulaDates.checkField(
                    "天", FieldExpressions.parse(formula, COLUMNS).expression(), half);
        // 类型不对的公式：试算的结果类型判断只回答「不是日期」，不抛错。
        assertThat(
                        FormulaDates.dateResult(
                                FieldExpressions.parse("MONTH(c_fee)", COLUMNS).expression(),
                                context(true, false)))
                .isFalse();
    }

    private static String ymd(String date) {
        return "YEAR(" + date + ") * 10000 + MONTH(" + date + ") * 100 + DAY(" + date + ")";
    }

    @Test
    void fieldKindsComeFromFieldTypes() {
        assertThat(FormulaDates.kind("DATE")).isEqualTo(Kind.DATE);
        assertThat(FormulaDates.kind("DATETIME")).isEqualTo(Kind.DATETIME);
        for (String type : List.of("INTEGER", "DECIMAL", "MONEY", "PERCENT"))
            assertThat(FormulaDates.kind(type)).isEqualTo(Kind.NUMBER);
        assertThat(FormulaDates.kind("TEXT")).isEqualTo(Kind.TEXT);
        assertThat(FormulaDates.kind("REFERENCE")).isEqualTo(Kind.OTHER);
        assertThat(FormulaDates.kind("FORMULA")).isEqualTo(Kind.UNKNOWN);
        assertThat(FormulaDates.kind((String) null)).isEqualTo(Kind.UNKNOWN);
        var zoned =
                FieldOptions.copyOf(FieldOptions.defaults())
                        .nativeType("timestamp with time zone")
                        .build();
        assertThat(FormulaDates.kind(field("1", "c_at", "登记时间", "DATETIME"), zoned))
                .isEqualTo(Kind.ZONED);
        var formula = FieldOptions.copyOf(FieldOptions.defaults()).resultType("INTEGER").build();
        assertThat(FormulaDates.kind(field("2", "c_f", "晚数", "FORMULA"), formula))
                .isEqualTo(Kind.NUMBER);
    }

    // ── 公式默认值（规则层复用同一套解析求值） ──

    private static FieldDefinition field(String id, String code, String name, String type) {
        return new FieldDefinition(id, id, code, name, type, null, null, null, false, false, 0);
    }

    private static void publishDefault(String targetType, String formula) {
        var options = new HashMap<String, FieldOptions>();
        options.put(
                "9",
                FieldOptions.defaults()
                        .withRules(new FieldRules(null, null, formula, null, null, null)));
        FieldRuleValidator.validate(
                new DataCenter.Definition(
                        "1",
                        "stay",
                        "入住记录",
                        null,
                        "public",
                        "biz_stay",
                        "GENERATED",
                        false,
                        "1",
                        DataCenter.Settings.defaults(),
                        List.of(
                                field("1", "c_in", "入住日", "DATE"),
                                field("2", "c_out", "退房日", "DATE"),
                                field("3", "c_fee", "房费", "MONEY"),
                                field("9", "c_target", "目标", targetType)),
                        options,
                        List.of(),
                        List.of(),
                        List.of()),
                id -> null,
                id -> "「?」未发布或已停用");
    }

    @Test
    void defaultFormulaAcceptsDateFunctionsAndToday() {
        publishDefault(
                "MONEY",
                "c_fee * ((c_out - c_in) - IF(MONTH(c_in) = MONTH(c_out), 0, DAY(c_out) - 1))"
                        + " / (c_out - c_in)");
        publishDefault("INTEGER", "TODAY() - c_in");
        publishDefault("TEXT", "EDATE(c_in, 1)");
        publishDefault("TEXT", "TODAY()");
        // 现状不变：日期字段本来就不开放公式默认值（FieldRuleMatrix），本次不扩大范围。
        assertThatThrownBy(() -> publishDefault("DATE", "EOMONTH(c_in, 0)"))
                .hasMessage("字段「目标」不支持公式默认值");
        assertThatThrownBy(() -> publishDefault("INTEGER", "EOMONTH(c_in, 0)"))
                .hasMessage("字段「目标」的公式默认值：公式算出来的是一个日期，不能填进数值字段；要天数请用两个日期相减或 DAYS");
        assertThatThrownBy(() -> publishDefault("MONEY", "c_fee * MONTH(c_fee)"))
                .hasMessage("字段「目标」的公式默认值：MONTH 的第 1 个参数要填日期或日期时间字段（或其它日期函数的结果），「房费」是数字");
        // 金额目标的取整仍只有「取整方式」一个家：公式里写 round 照旧被拦。
        assertThatThrownBy(() -> publishDefault("MONEY", "round(c_fee * DAY(c_out) / 30)"))
                .hasMessageContaining("公式里不能写 round()");
    }

    // ── 落库改写与渲染 ──

    private static Expression lowered(String formula) {
        return FormulaDates.lower(
                FieldExpressions.parse(formula, COLUMNS).expression(), context(false, true));
    }

    private static FunctionValue call(String name, Expression... args) {
        return new FunctionValue(name, List.of(args));
    }

    @Test
    void storedFormulaRewritesDateArithmeticIntoFunctions() {
        var in = new FieldValue("c_in");
        var out = new FieldValue("c_out");
        var n = new FieldValue("c_n");
        assertThat(lowered("c_out - c_in")).isEqualTo(call("days", out, in));
        assertThat(lowered("c_in + c_n")).isEqualTo(call("date_add", in, n));
        assertThat(lowered("c_n + c_in")).isEqualTo(call("date_add", in, n));
        assertThat(lowered("c_in - c_n"))
                .isEqualTo(
                        call(
                                "date_add",
                                in,
                                new BinaryValue("-", new NumberValue(BigDecimal.ZERO), n)));
        assertThat(lowered("c_out - '2026-07-01'"))
                .isEqualTo(call("days", out, new TextValue("2026-07-01")));
        assertThat(lowered("EOMONTH(c_in)"))
                .isEqualTo(call("eomonth", in, new NumberValue(BigDecimal.ZERO)));
        assertThat(lowered("DAY(c_in + 1) - 1"))
                .isEqualTo(
                        new BinaryValue(
                                "-",
                                call("day", call("date_add", in, new NumberValue(BigDecimal.ONE))),
                                new NumberValue(BigDecimal.ONE)));
        // 不涉及日期的公式改写前后是同一棵树。
        for (String formula :
                List.of(
                        "c_fee - c_n * 2",
                        "round(c_fee / c_n, 2)",
                        "if(c_fee >= 100, c_fee * 0.9, c_fee) + coalesce(c_n, 0)",
                        "upper(c_memo) || '-' || c_n",
                        NEXT_MONTH_NIGHTS))
            assertThat(lowered(formula))
                    .as(formula)
                    .isEqualTo(FieldExpressions.parse(formula, COLUMNS).expression());
    }
}
