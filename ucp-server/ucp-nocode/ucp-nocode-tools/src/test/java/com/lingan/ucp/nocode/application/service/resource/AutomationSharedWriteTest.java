package com.lingan.ucp.nocode.application.service.resource;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.ApplicationAutomations;

import org.junit.jupiter.api.Test;

import java.util.*;

/**
 * 放宽后的「一个字段几条规则」（纯函数，ApplicationAutomationValidator.graph）：一次性赋值（事件赋值、按日期）可以多条写同一字段，
 * 不论来源是否相同、事件是否重叠；持续维护独占， 不能与任何规则共写。按日期规则不由数据变化触发，不计入对象回写环。
 */
class AutomationSharedWriteTest {
    private final ApplicationAutomationValidator validator = new ApplicationAutomationValidator();

    private static ApplicationAutomations.Config rule(
            String mode, String source, String target, Set<String> events, String field) {
        return new ApplicationAutomations.Config(
                source,
                target,
                true,
                mode,
                events,
                null,
                new ApplicationAutomations.Binding("r", "OUTGOING"),
                List.of(new ApplicationAutomations.Assignment(field, "VALUE", null, "x", null)),
                "DATE".equals(mode) ? "d" : null,
                "DATE".equals(mode) ? 0 : null);
    }

    private static final Set<String> CREATE = Set.of("CREATE");
    private static final Set<String> ALL = Set.of("CREATE", "UPDATE", "DELETE");

    @Test
    void oneShotRulesMayShareATargetField() {
        assertThatCode(
                        () ->
                                validator.graph(
                                        List.of(
                                                rule("EVENT", "1", "9", CREATE, "status"),
                                                rule("EVENT", "1", "9", CREATE, "status"),
                                                rule("EVENT", "2", "9", ALL, "status"),
                                                rule("DATE", "3", "9", Set.of(), "status"),
                                                rule("DATE", "3", "9", Set.of(), "status"))))
                .doesNotThrowAnyException();
    }

    @Test
    void maintainedFieldsStayExclusiveInEitherOrder() {
        for (String other : List.of("EVENT", "DATE", "MAINTAIN")) {
            var maintain = rule("MAINTAIN", "1", "9", ALL, "status");
            var second = rule(other, "2", "9", "EVENT".equals(other) ? CREATE : ALL, "status");
            assertThatThrownBy(() -> validator.graph(List.of(maintain, second)))
                    .as("持续维护在前，再加 " + other)
                    .hasMessageContaining("同一目标字段");
            assertThatThrownBy(() -> validator.graph(List.of(second, maintain)))
                    .as(other + " 在前，再加持续维护")
                    .hasMessageContaining("同一目标字段");
        }
        // 不同字段互不影响。
        assertThatCode(
                        () ->
                                validator.graph(
                                        List.of(
                                                rule("MAINTAIN", "1", "9", ALL, "count"),
                                                rule("DATE", "2", "9", Set.of(), "status"))))
                .doesNotThrowAnyException();
    }

    @Test
    void sharedWriteMatrix() {
        var event = rule("EVENT", "1", "9", CREATE, "f");
        var date = rule("DATE", "1", "9", Set.of(), "f");
        var maintain = rule("MAINTAIN", "1", "9", ALL, "f");
        assertThat(validator.allowsSharedWrite(event, event)).isTrue();
        assertThat(validator.allowsSharedWrite(event, date)).isTrue();
        assertThat(validator.allowsSharedWrite(date, event)).isTrue();
        assertThat(validator.allowsSharedWrite(date, date)).isTrue();
        assertThat(validator.allowsSharedWrite(maintain, event)).isFalse();
        assertThat(validator.allowsSharedWrite(event, maintain)).isFalse();
        assertThat(validator.allowsSharedWrite(maintain, date)).isFalse();
        assertThat(validator.allowsSharedWrite(date, maintain)).isFalse();
    }

    @Test
    void dateRulesDoNotFormWriteBackCycles() {
        assertThatCode(
                        () ->
                                validator.graph(
                                        List.of(
                                                rule("DATE", "1", "2", Set.of(), "a"),
                                                rule("EVENT", "2", "1", CREATE, "b"))))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                validator.graph(
                                        List.of(
                                                rule("EVENT", "1", "2", CREATE, "a"),
                                                rule("EVENT", "2", "1", CREATE, "b"))))
                .hasMessageContaining("循环");
    }
}
