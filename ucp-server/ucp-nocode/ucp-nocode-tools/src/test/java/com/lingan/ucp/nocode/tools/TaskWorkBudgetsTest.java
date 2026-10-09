package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.TaskCenter;
import com.lingan.ucp.nocode.api.TaskWorkEntries.*;
import com.lingan.ucp.nocode.api.TaskWorkTimes;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskGraph;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskWorkBudgets;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

/** 预算不是计量事实；缺省兼容、预计量完整性及一次舍入均显式验证。 */
class TaskWorkBudgetsTest {
    private Config config(String key, WorkRuleMode mode, int rate, String quantity) {
        return new Config(
                key,
                key,
                null,
                DataMode.ROOT_SHARED,
                null,
                null,
                null,
                null,
                false,
                false,
                new WorkRule(
                        mode,
                        rate,
                        "q",
                        "state",
                        "done",
                        null,
                        quantity == null ? null : new BigDecimal(quantity)));
    }

    @Test
    void aggregateBeforeRoundingAndDoNotUseRateAsUnspecifiedQuantity() {
        assertThat(
                        TaskWorkBudgets.total(
                                List.of(
                                        config("a", WorkRuleMode.QUANTITY, 1, "0.1"),
                                        config("b", WorkRuleMode.QUANTITY, 1, "0.1"))))
                .isEqualTo(1);
        assertThat(TaskWorkBudgets.total(List.of(config("a", WorkRuleMode.RECORD_ONCE, 30, null))))
                .isNull();
        assertThat(TaskWorkBudgets.total(List.of(config("a", WorkRuleMode.CONDITION, 30, "0"))))
                .isNull();
        assertThat(TaskWorkBudgets.total(List.of())).isNull();
        assertThat(
                        TaskWorkBudgets.total(
                                List.of(
                                        config("a", WorkRuleMode.CONDITION, 30, "0"),
                                        config("b", WorkRuleMode.RECORD_ONCE, 10, "1"))))
                .isEqualTo(10);
    }

    @Test
    void partialBudgetDoesNotBecomeMisleadingTotalAndStillValidatesOtherEntries() {
        Config unconfigured =
                new Config(
                        "none",
                        "未计时",
                        null,
                        DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false);
        assertThat(
                        TaskWorkBudgets.total(
                                List.of(
                                        unconfigured,
                                        config("priced", WorkRuleMode.RECORD_ONCE, 30, "2"))))
                .isNull();
        assertThat(
                        TaskWorkBudgets.total(
                                List.of(
                                        config("zero", WorkRuleMode.QUANTITY, 0, "2"),
                                        config("priced", WorkRuleMode.RECORD_ONCE, 30, "2"))))
                .isNull();
        assertThatThrownBy(
                        () ->
                                TaskWorkBudgets.total(
                                        List.of(
                                                unconfigured,
                                                config(
                                                        "invalid",
                                                        WorkRuleMode.RECORD_ONCE,
                                                        30,
                                                        "0.5"))))
                .hasMessageContaining("必须为整数");
    }

    @Test
    void validateQuantityAndTotalBounds() {
        for (WorkRuleMode mode : List.of(WorkRuleMode.RECORD_ONCE, WorkRuleMode.CONDITION))
            assertThatThrownBy(
                            () -> TaskWorkBudgets.validate(config("a", mode, 30, "0.5").workRule()))
                    .hasMessageContaining("必须为整数");
        for (String quantity : List.of("-1", "1000000", "0.0000001"))
            assertThatThrownBy(
                            () ->
                                    TaskWorkBudgets.validate(
                                            config("a", WorkRuleMode.QUANTITY, 30, quantity)
                                                    .workRule()))
                    .hasMessageContaining("预计工作量");
        assertThatThrownBy(
                        () ->
                                TaskWorkBudgets.total(
                                        List.of(
                                                config(
                                                        "a",
                                                        WorkRuleMode.RECORD_ONCE,
                                                        300000,
                                                        "2"))))
                .hasMessageContaining("不能超过");
    }

    @Test
    void graphComputesAutoBudgetAndPreservesLegacyJsonHashShape() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        WorkRule legacy = new WorkRule(WorkRuleMode.RECORD_ONCE, 10, null, null, null);
        assertThat(json.writeValueAsString(legacy)).doesNotContain("plannedQuantity");
        TaskCenter.NodeInput node =
                new TaskCenter.NodeInput(
                        "root",
                        null,
                        "预算",
                        null,
                        1L,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        List.of(config("a", WorkRuleMode.RECORD_ONCE, 30, "3")),
                        TaskCenter.AssignmentMode.ASSIGNED,
                        null,
                        null,
                        null,
                        999,
                        TaskWorkTimes.TotalMode.AUTO);
        assertThat(TaskGraph.normalize(List.of(node), 1).getFirst().effectiveWorkMinutes())
                .isEqualTo(90);
        TaskCenter.NodeInput child =
                new TaskCenter.NodeInput(
                        "child",
                        "root",
                        "旧协议独立配置",
                        null,
                        1L,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        List.of(config("b", WorkRuleMode.RECORD_ONCE, 20, "2")));
        assertThat(TaskGraph.normalize(List.of(node, child), 1).getFirst().effectiveWorkMinutes())
                .isEqualTo(130);
        TaskCenter.NodeInput old =
                new TaskCenter.NodeInput(
                        "root",
                        null,
                        "预算",
                        null,
                        1L,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        node.entries(),
                        TaskCenter.AssignmentMode.ASSIGNED,
                        null,
                        null,
                        null,
                        999);
        assertThat(TaskGraph.normalize(List.of(old), 1).getFirst().effectiveWorkMinutes())
                .isEqualTo(999);
        assertThat(json.writeValueAsString(old)).doesNotContain("workTotalMode");
    }
}
