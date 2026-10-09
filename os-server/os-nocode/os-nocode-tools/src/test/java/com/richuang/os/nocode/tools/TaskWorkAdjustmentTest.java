package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.api.TaskWorkEntries.*;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskDataPolicies;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskStandardWork;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

/** 发起只开放工时差额，不开放模板资源和计量基准；旧JSON继续兼容。 */
class TaskWorkAdjustmentTest {
    private final TaskDataPolicies policies = new TaskDataPolicies();

    TaskWorkAdjustmentTest() {
        ReflectionTestUtils.setField(policies, "standardWork", new TaskStandardWork());
    }

    private Config entry(int minutes, Integer adjustment, DataAccessMode scope) {
        return new Config(
                "room",
                "房间办理",
                new Binding("app", "form", null),
                com.richuang.os.nocode.api.TaskWorkEntries.DataMode.ROOT_SHARED,
                null,
                null,
                List.of("room"),
                List.of("room"),
                false,
                false,
                new WorkRule(WorkRuleMode.RECORD_ONCE, minutes, null, null, null, adjustment),
                scope);
    }

    @Test
    void onlyDeltaMayDifferFromApprovedTemplate() {
        List<Config> baseline = List.of(entry(15, null, DataAccessMode.GROUP));
        assertThatCode(
                        () ->
                                policies.requireLaunchEntries(
                                        List.of(entry(15, 5, DataAccessMode.GROUP)), baseline))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                policies.requireLaunchEntries(
                                        List.of(entry(15, -5, DataAccessMode.GROUP)), baseline))
                .doesNotThrowAnyException();
        assertThatThrownBy(
                        () ->
                                policies.requireLaunchEntries(
                                        List.of(entry(20, null, DataAccessMode.GROUP)), baseline))
                .hasMessageContaining("基准工时");
        assertThatThrownBy(
                        () ->
                                policies.requireLaunchEntries(
                                        List.of(entry(15, 5, DataAccessMode.ALL)), baseline))
                .hasMessageContaining("业务资源");
        assertThatThrownBy(
                        () ->
                                policies.requireLaunchEntries(
                                        List.of(entry(15, -15, DataAccessMode.GROUP)), baseline))
                .hasMessageContaining("调整后的标准工时");
        assertThatThrownBy(
                        () ->
                                policies.requireLaunchEntries(
                                        Collections.singletonList(null), baseline))
                .hasMessageContaining("办理项不能为空");
    }

    @Test
    void oldJsonKeepsBaselineAndOverflowCannotBypassValidation() throws Exception {
        WorkRule rule =
                new ObjectMapper()
                        .readValue("{\"mode\":\"RECORD_ONCE\",\"minutes\":15}", WorkRule.class);
        assertThat(rule.adjustmentMinutes()).isNull();
        assertThat(rule.effectiveMinutes()).isEqualTo(15);
        assertThatThrownBy(
                        () ->
                                new TaskStandardWork()
                                        .validate(
                                                new WorkRule(
                                                        WorkRuleMode.RECORD_ONCE,
                                                        15,
                                                        null,
                                                        null,
                                                        null,
                                                        Integer.MAX_VALUE)))
                .hasMessageContaining("调整后的标准工时");
    }
}
