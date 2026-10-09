package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

/** 记录范围（DataScope：视图固定范围、记录权限、业务动作条件、计算匹配条件）里的相对日期：保存校验与内存判断（不查库）。 「今天」用系统属性钉在 2026-10-03（星期六）。 */
class RelativeDateScopeTest {
    private static FieldDefinition field(String id, String type) {
        return new FieldDefinition(id, id, id, "字段" + id, type, null, null, null, false, false, 0);
    }

    private static final DataCenter.Definition D =
            new DataCenter.Definition(
                    "900",
                    "stay",
                    "入住记录",
                    null,
                    "public",
                    "biz_stay",
                    "GENERATED",
                    false,
                    "name",
                    DataCenter.Settings.defaults(),
                    List.of(
                            field("name", "TEXT"),
                            field("checkout", "DATE"),
                            field("arrived", "DATETIME"),
                            field("nights", "INTEGER"),
                            field("tags", "MULTI_SELECT")),
                    Map.of(),
                    List.of(),
                    List.of(),
                    List.of());

    @BeforeEach
    void pinToday() {
        System.setProperty(RelativeDates.TODAY_PROPERTY, "2026-10-03");
    }

    @AfterEach
    void clearToday() {
        System.clearProperty(RelativeDates.TODAY_PROPERTY);
    }

    private static Map<String, Object> rel(String code) {
        return Map.of("relative", code);
    }

    private static DataScope scope(DataScope.Condition... conditions) {
        return new DataScope("AND", List.of(conditions), List.of());
    }

    private static DataScope.Condition c(String field, String op, Object value) {
        return new DataScope.Condition(field, op, value);
    }

    private static boolean matches(DataScope scope, Object checkout) {
        var values = new HashMap<String, Object>();
        values.put("checkout", checkout);
        return scope.matches(D, values, Map.of());
    }

    @Test
    void validateAcceptsRelativeOnDateFieldsOnly() {
        for (String op : List.of("eq", "neq", "gt", "gte", "lt", "lte"))
            assertThatCode(() -> scope(c("checkout", op, rel("THIS_WEEK"))).validate(D, false))
                    .as(op)
                    .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                scope(c("arrived", "eq", Map.of("relative", "PAST_N_DAYS", "n", 7)))
                                        .validate(D, true))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> scope(c("checkout", "in", rel("TODAY"))).validate(D, false))
                .hasMessageContaining("比较方式");
        assertThatThrownBy(() -> scope(c("nights", "eq", rel("TODAY"))).validate(D, false))
                .hasMessageContaining("日期或日期时间字段");
        assertThatThrownBy(() -> scope(c("name", "eq", rel("TODAY"))).validate(D, false))
                .hasMessageContaining("日期或日期时间字段");
        assertThatThrownBy(() -> scope(c("checkout", "eq", rel("NEVER"))).validate(D, false))
                .hasMessageContaining("NEVER");
    }

    @Test
    void storedConcreteValuesAreValidatedExactlyAsBefore() {
        // 存量形状：字符串日期照旧通过；对象但不是相对日期照旧拒绝（基线文案「范围值格式无效」）。
        assertThatCode(() -> scope(c("checkout", "eq", "2026-10-03")).validate(D, false))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () -> scope(c("checkout", "eq", Map.of("value", "x"))).validate(D, false))
                .hasMessageContaining("范围值格式无效");
        assertThatThrownBy(() -> scope(c("checkout", "eq", "not-a-date")).validate(D, false))
                .hasMessageContaining("查询值格式无效");
    }

    @Test
    void matchesFollowsToday() {
        var today = scope(c("checkout", "eq", rel("TODAY")));
        assertThat(matches(today, "2026-10-03")).isTrue();
        assertThat(matches(today, "2026-10-02")).isFalse();
        assertThat(matches(today, null)).isFalse();
        var before = scope(c("checkout", "lt", rel("TODAY")));
        assertThat(matches(before, "2026-10-02")).isTrue();
        assertThat(matches(before, "2026-10-03")).isFalse();
        var week = scope(c("checkout", "neq", rel("THIS_WEEK")));
        assertThat(matches(week, "2026-09-28")).isFalse();
        assertThat(matches(week, "2026-10-05")).isTrue();
        // 换日：同一份配置，「今天」变了，结果跟着变（不存换算结果）。
        System.setProperty(RelativeDates.TODAY_PROPERTY, "2026-10-04");
        assertThat(matches(today, "2026-10-03")).isFalse();
        assertThat(matches(today, "2026-10-04")).isTrue();
        assertThat(matches(before, "2026-10-03")).isTrue();
    }

    @Test
    void concreteValuesMatchExactlyAsBefore() {
        var fixed = scope(c("checkout", "eq", "2026-10-03"));
        assertThat(matches(fixed, "2026-10-03")).isTrue();
        assertThat(matches(fixed, "2026-10-04")).isFalse();
        System.setProperty(RelativeDates.TODAY_PROPERTY, "2030-01-01");
        assertThat(matches(fixed, "2026-10-03")).isTrue();
    }

    @Test
    void relativeIsFoundInNestedGroups() {
        var nested =
                new DataScope(
                        "OR",
                        List.of(c("name", "eq", "甲")),
                        List.of(scope(c("checkout", "eq", rel("TODAY")))));
        assertThat(DataScope.usesRelativeDate(nested)).isTrue();
        assertThat(DataScope.usesRelativeDate(scope(c("checkout", "eq", "2026-10-03")))).isFalse();
        assertThat(DataScope.usesRelativeDate(null)).isFalse();
    }

    @Test
    void relativeValuesDoNotNarrowViewCandidates() {
        var options =
                new ViewQueryOptions(
                        List.of(c("checkout", "eq", rel("TODAY")), c("name", "eq", "甲")),
                        null,
                        null);
        var visible = options.visible(Set.of("checkout", "name"), null);
        assertThat(visible.candidates()).containsOnlyKeys("name");
        assertThat(visible.candidates().get("name")).containsExactly("甲");
    }

    @Test
    void defaultQueryKeepsRelativeDatesAsIs() {
        var month = new ViewQueryOptions(List.of(), Map.of("checkout", rel("THIS_MONTH")), null);
        assertThatCode(() -> month.validate(D)).doesNotThrowAnyException();
        // 下发给运行端的默认查询仍是相对表达，查询时由服务端按当天换算，不在保存或下发时换成具体日期。
        assertThat(month.visible(Set.of("checkout"), null).defaults())
                .containsEntry("checkout", rel("THIS_MONTH"));
        assertThatThrownBy(
                        () ->
                                new ViewQueryOptions(List.of(), Map.of("name", rel("TODAY")), null)
                                        .validate(D))
                .hasMessageContaining("日期或日期时间字段");
        assertThatThrownBy(
                        () ->
                                new ViewQueryOptions(
                                                List.of(),
                                                Map.of("checkout", Map.of("relative", "SOMEDAY")),
                                                null)
                                        .validate(D))
                .hasMessageContaining("SOMEDAY");
        // 存量具体日期默认值照旧。
        assertThatCode(
                        () ->
                                new ViewQueryOptions(
                                                List.of(), Map.of("checkout", "2026-10-03"), null)
                                        .validate(D))
                .doesNotThrowAnyException();
    }
}
