package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.RelativeDateEnum;
import com.richuang.os.nocode.metadata.service.formula.FormulaDates;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

/** 相对日期的识别、校验与换算（不查库）。区间左闭右开，周一起；「过去 / 未来 N 天」含今天、共 N 天。 */
class RelativeDatesTest {
    /** 2026-10-03 是星期六。 */
    private static final LocalDate SAT = LocalDate.of(2026, 10, 3);

    private static final FieldDefinition DAY =
            new FieldDefinition("d", "d", "d", "退房日", "DATE", null, null, null, false, false, 0);
    private static final FieldDefinition STAMP =
            new FieldDefinition(
                    "t", "t", "t", "到达时间", "DATETIME", null, null, null, false, false, 0);
    private static final FieldDefinition TEXT =
            new FieldDefinition("x", "x", "x", "备注", "TEXT", null, null, null, false, false, 0);
    private static final FieldDefinition TIME =
            new FieldDefinition("h", "h", "h", "时刻", "TIME", null, null, null, false, false, 0);

    @AfterEach
    void clearClock() {
        System.clearProperty(RelativeDates.TODAY_PROPERTY);
    }

    private static Map<String, Object> rel(String code) {
        return Map.of("relative", code);
    }

    private static Map<String, Object> rel(String code, Object n) {
        var map = new LinkedHashMap<String, Object>();
        map.put("relative", code);
        map.put("n", n);
        return map;
    }

    private static RelativeDates.Range range(Map<String, Object> value, LocalDate today) {
        return RelativeDates.range(RelativeDates.parse(value), today);
    }

    private static RelativeDates.Range r(String start, String end) {
        return new RelativeDates.Range(LocalDate.parse(start), LocalDate.parse(end));
    }

    @Test
    void zoneIsTheSameAsFormulaToday() {
        assertThat(RelativeDates.ZONE).isEqualTo(FormulaDates.ZONE);
    }

    @Test
    void rangesOnASaturday() {
        assertThat(range(rel("TODAY"), SAT)).isEqualTo(r("2026-10-03", "2026-10-04"));
        assertThat(range(rel("YESTERDAY"), SAT)).isEqualTo(r("2026-10-02", "2026-10-03"));
        assertThat(range(rel("TOMORROW"), SAT)).isEqualTo(r("2026-10-04", "2026-10-05"));
        // 周一起：10-03 星期六所在的周是 09-28（一）~ 10-04（日）。
        assertThat(range(rel("THIS_WEEK"), SAT)).isEqualTo(r("2026-09-28", "2026-10-05"));
        assertThat(range(rel("LAST_WEEK"), SAT)).isEqualTo(r("2026-09-21", "2026-09-28"));
        assertThat(range(rel("NEXT_WEEK"), SAT)).isEqualTo(r("2026-10-05", "2026-10-12"));
        assertThat(range(rel("THIS_MONTH"), SAT)).isEqualTo(r("2026-10-01", "2026-11-01"));
        assertThat(range(rel("LAST_MONTH"), SAT)).isEqualTo(r("2026-09-01", "2026-10-01"));
        assertThat(range(rel("NEXT_MONTH"), SAT)).isEqualTo(r("2026-11-01", "2026-12-01"));
        assertThat(range(rel("THIS_YEAR"), SAT)).isEqualTo(r("2026-01-01", "2027-01-01"));
        assertThat(range(rel("LAST_YEAR"), SAT)).isEqualTo(r("2025-01-01", "2026-01-01"));
        // 过去 / 未来 N 天：含今天、共 N 天。
        assertThat(range(rel("PAST_N_DAYS", 7), SAT)).isEqualTo(r("2026-09-27", "2026-10-04"));
        assertThat(range(rel("NEXT_N_DAYS", 7), SAT)).isEqualTo(r("2026-10-03", "2026-10-10"));
        assertThat(range(rel("PAST_N_DAYS", 1), SAT)).isEqualTo(r("2026-10-03", "2026-10-04"));
        assertThat(range(rel("PAST_N_DAYS", 30), SAT)).isEqualTo(r("2026-09-04", "2026-10-04"));
    }

    @Test
    void weekStartsOnMondayOnTheEdges() {
        // 星期一当天：本周从当天开始；星期日当天：本周到当天为止。
        assertThat(range(rel("THIS_WEEK"), LocalDate.of(2026, 9, 28)))
                .isEqualTo(r("2026-09-28", "2026-10-05"));
        assertThat(range(rel("THIS_WEEK"), LocalDate.of(2026, 10, 4)))
                .isEqualTo(r("2026-09-28", "2026-10-05"));
    }

    @Test
    void monthAndYearBoundaries() {
        LocalDate jan31 = LocalDate.of(2027, 1, 31);
        assertThat(range(rel("LAST_MONTH"), jan31)).isEqualTo(r("2026-12-01", "2027-01-01"));
        assertThat(range(rel("NEXT_MONTH"), jan31)).isEqualTo(r("2027-02-01", "2027-03-01"));
        assertThat(range(rel("LAST_YEAR"), jan31)).isEqualTo(r("2026-01-01", "2027-01-01"));
        LocalDate leap = LocalDate.of(2028, 3, 1);
        assertThat(range(rel("YESTERDAY"), leap)).isEqualTo(r("2028-02-29", "2028-03-01"));
        assertThat(range(rel("LAST_MONTH"), leap)).isEqualTo(r("2028-02-01", "2028-03-01"));
    }

    @Test
    void everyCodeHasARange() {
        for (var kind : RelativeDateEnum.values()) {
            var value = kind.counted() ? rel(kind.getCode(), 3) : rel(kind.getCode());
            var range = range(value, SAT);
            assertThat(range.start()).as(kind.getCode()).isBefore(range.end());
        }
    }

    @Test
    void parseRejectsAnythingItDoesNotKnow() {
        assertThatThrownBy(() -> RelativeDates.parse(rel("THIS_QUARTER")))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("THIS_QUARTER");
        assertThatThrownBy(() -> RelativeDates.parse(Map.of("relative", "TODAY", "x", 1)))
                .hasMessageContaining("多余的键");
        assertThatThrownBy(() -> RelativeDates.parse(rel("TODAY", 3)))
                .hasMessageContaining("不需要天数");
        assertThatThrownBy(() -> RelativeDates.parse(rel("PAST_N_DAYS")))
                .hasMessageContaining("天数");
        for (Object bad : new Object[] {0, -1, 3651, 1.5, "7", null})
            assertThatThrownBy(() -> RelativeDates.parse(rel("PAST_N_DAYS", bad)))
                    .as(String.valueOf(bad))
                    .hasMessageContaining("1–3650");
        assertThat(RelativeDates.parse(rel("NEXT_N_DAYS", 3650)).n()).isEqualTo(3650);
        assertThat(RelativeDates.parse(rel("NEXT_N_DAYS", 7L)).n()).isEqualTo(7);
        assertThat(RelativeDates.parse(rel("NEXT_N_DAYS", new java.math.BigDecimal("7.0"))).n())
                .isEqualTo(7);
    }

    @Test
    void onlyObjectsWithTheKeyAreRelative() {
        assertThat(RelativeDates.isRelative(rel("TODAY"))).isTrue();
        assertThat(RelativeDates.isRelative("2026-10-03")).isFalse();
        assertThat(RelativeDates.isRelative(List.of("2026-10-01", "2026-10-31"))).isFalse();
        assertThat(RelativeDates.isRelative(Map.of("value", "TODAY"))).isFalse();
        assertThat(RelativeDates.isRelative(null)).isFalse();
    }

    @Test
    void checkRejectsWrongFieldTypesAndOperators() {
        assertThatThrownBy(() -> RelativeDates.check(TEXT, "eq", rel("TODAY")))
                .hasMessageContaining("日期或日期时间字段");
        assertThatThrownBy(() -> RelativeDates.check(TIME, "eq", rel("TODAY")))
                .hasMessageContaining("日期或日期时间字段");
        for (String op : List.of("in", "containsAny", "like", "isNull"))
            assertThatThrownBy(() -> RelativeDates.check(DAY, op, rel("TODAY")))
                    .as(op)
                    .hasMessageContaining("比较方式");
        for (String op : RelativeDates.OPERATORS)
            assertThat(RelativeDates.check(STAMP, op, rel("THIS_WEEK")).kind())
                    .isEqualTo(RelativeDateEnum.THIS_WEEK);
    }

    @Test
    void operatorsBecomeBounds() {
        var week = range(rel("THIS_WEEK"), SAT);
        var s = week.start();
        var e = week.end();
        assertThat(RelativeDates.bounds("eq", week))
                .isEqualTo(
                        new RelativeDates.Bounds(
                                false,
                                List.of(
                                        new RelativeDates.Bound("gte", s),
                                        new RelativeDates.Bound("lt", e))));
        assertThat(RelativeDates.bounds("between", week))
                .isEqualTo(RelativeDates.bounds("eq", week));
        assertThat(RelativeDates.bounds("neq", week))
                .isEqualTo(
                        new RelativeDates.Bounds(
                                true,
                                List.of(
                                        new RelativeDates.Bound("lt", s),
                                        new RelativeDates.Bound("gte", e))));
        assertThat(RelativeDates.bounds("lt", week).parts())
                .containsExactly(new RelativeDates.Bound("lt", s));
        assertThat(RelativeDates.bounds("lte", week).parts())
                .containsExactly(new RelativeDates.Bound("lt", e));
        assertThat(RelativeDates.bounds("gt", week).parts())
                .containsExactly(new RelativeDates.Bound("gte", e));
        assertThat(RelativeDates.bounds("gte", week).parts())
                .containsExactly(new RelativeDates.Bound("gte", s));
    }

    @Test
    void boundValuesFollowTheColumnType() {
        var day = LocalDate.of(2026, 10, 3);
        assertThat(RelativeDates.boundValue(DAY, DataCenter.FieldOptions.defaults(), day))
                .isEqualTo(day);
        assertThat(RelativeDates.boundValue(STAMP, DataCenter.FieldOptions.defaults(), day))
                .isEqualTo(LocalDateTime.of(2026, 10, 3, 0, 0));
        var zoned = withNative("timestamp with time zone");
        assertThat(RelativeDates.boundValue(STAMP, zoned, day))
                .isEqualTo(OffsetDateTime.of(2026, 10, 3, 0, 0, 0, 0, ZoneOffset.ofHours(8)));
    }

    private static DataCenter.FieldOptions withNative(String nativeType) {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, Object> raw =
                mapper.convertValue(
                        DataCenter.FieldOptions.defaults(),
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {});
        raw.put("nativeType", nativeType);
        return mapper.convertValue(raw, DataCenter.FieldOptions.class);
    }

    @Test
    void expandBuildsAGroupWithTypedBounds() {
        var group =
                RelativeDates.expand(
                        DAY, DataCenter.FieldOptions.defaults(), "neq", rel("TODAY"), SAT);
        assertThat(group.isGroup()).isTrue();
        assertThat(group.getGroupLogic()).isEqualTo(DynamicConditionDTO.Logic.OR);
        assertThat(group.getGroupItems())
                .extracting(
                        DynamicConditionDTO.Item::getField,
                        DynamicConditionDTO.Item::getOperator,
                        DynamicConditionDTO.Item::getValue)
                .containsExactly(
                        tuple("d", "lt", LocalDate.of(2026, 10, 3)),
                        tuple("d", "gte", LocalDate.of(2026, 10, 4)));
        var month =
                RelativeDates.expand(
                        STAMP, DataCenter.FieldOptions.defaults(), "eq", rel("THIS_MONTH"), SAT);
        assertThat(month.getGroupLogic()).isEqualTo(DynamicConditionDTO.Logic.AND);
        assertThat(month.getGroupItems())
                .extracting(
                        DynamicConditionDTO.Item::getOperator, DynamicConditionDTO.Item::getValue)
                .containsExactly(
                        tuple("gte", LocalDateTime.of(2026, 10, 1, 0, 0)),
                        tuple("lt", LocalDateTime.of(2026, 11, 1, 0, 0)));
    }

    @Test
    void inMemoryMatchesUseTheSameBounds() {
        var opts = DataCenter.FieldOptions.defaults();
        var today = rel("TODAY");
        assertThat(RelativeDates.matches(DAY, opts, "eq", today, SAT, SAT)).isTrue();
        assertThat(RelativeDates.matches(DAY, opts, "eq", today, SAT.minusDays(1), SAT)).isFalse();
        assertThat(RelativeDates.matches(DAY, opts, "lt", today, SAT.minusDays(1), SAT)).isTrue();
        assertThat(RelativeDates.matches(DAY, opts, "lt", today, SAT, SAT)).isFalse();
        assertThat(RelativeDates.matches(DAY, opts, "lte", today, SAT, SAT)).isTrue();
        assertThat(RelativeDates.matches(DAY, opts, "gt", today, SAT, SAT)).isFalse();
        assertThat(RelativeDates.matches(DAY, opts, "gt", today, SAT.plusDays(1), SAT)).isTrue();
        assertThat(RelativeDates.matches(DAY, opts, "gte", today, SAT, SAT)).isTrue();
        assertThat(RelativeDates.matches(DAY, opts, "neq", today, SAT, SAT)).isFalse();
        assertThat(RelativeDates.matches(DAY, opts, "neq", today, SAT.plusDays(9), SAT)).isTrue();
        assertThat(RelativeDates.matches(DAY, opts, "eq", today, null, SAT)).isFalse();
        // 日期时间：当天最后一刻仍是「今天」，次日 00:00 不是。
        var lastMoment = LocalDateTime.of(2026, 10, 3, 23, 59, 59, 999_999_000);
        assertThat(RelativeDates.matches(STAMP, opts, "eq", today, lastMoment, SAT)).isTrue();
        assertThat(RelativeDates.matches(STAMP, opts, "eq", today, lastMoment.plusNanos(1000), SAT))
                .isFalse();
        // 带时区：东京 10-04 00:30 = 上海 10-03 23:30，仍是上海的「今天」。
        var zoned = withNative("timestamp with time zone");
        var tokyo = OffsetDateTime.of(2026, 10, 4, 0, 30, 0, 0, ZoneOffset.ofHours(9));
        assertThat(RelativeDates.matches(STAMP, zoned, "eq", today, tokyo, SAT)).isTrue();
    }

    @Test
    void theClockCanOnlyBeMovedByTheExplicitProperty() {
        assertThat(RelativeDates.today()).isEqualTo(LocalDate.now(RelativeDates.ZONE));
        System.setProperty(RelativeDates.TODAY_PROPERTY, "2026-10-31");
        assertThat(RelativeDates.today()).isEqualTo(LocalDate.of(2026, 10, 31));
    }
}
