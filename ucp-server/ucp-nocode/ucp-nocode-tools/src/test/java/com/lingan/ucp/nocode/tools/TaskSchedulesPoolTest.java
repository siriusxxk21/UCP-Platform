package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.TaskCenter.AssignmentMode;
import com.lingan.ucp.nocode.api.TaskCenter.NodeInput;
import com.lingan.ucp.nocode.api.TaskCenter.Schedule;
import com.lingan.ucp.nocode.api.TaskCenter.TimeMode;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskInstanceDO;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskSchedules;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 任务池日期语义的纯计算验收；不让未排期或单边日期变成虚构的逾期任务。 */
class TaskSchedulesPoolTest {
    private static final LocalDateTime START = LocalDateTime.of(2030, 10, 4, 9, 15);
    private final Map<String, NodeInput> definitions = new LinkedHashMap<>();

    @Test
    void unscheduledClearsExpectedDatesWithoutChangingActualExecution() {
        TaskInstanceDO task = node("root", AssignmentMode.OPEN, schedule(TimeMode.UNSCHEDULED));
        task.setExpectedStart(START);
        task.setExpectedEnd(START.plusDays(1));
        calculate(task);
        assertThat(task.getExpectedStart()).isNull();
        assertThat(task.getExpectedEnd()).isNull();
        assertThat(task.getActualStart()).isNull();
        assertThat(task.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void explicitStartOnlyDoesNotInventADeadline() {
        TaskInstanceDO task =
                node("root", AssignmentMode.OPEN, new Schedule(TimeMode.FIXED, START, 0, 0));
        calculate(task);
        assertThat(task.getExpectedStart()).isEqualTo(START);
        assertThat(task.getExpectedEnd()).isNull();
    }

    @Test
    void explicitEndOnlyDoesNotInventAStart() {
        TaskInstanceDO task =
                node("root", AssignmentMode.OPEN, new Schedule(TimeMode.FIXED, null, 0, 0, START));
        calculate(task);
        assertThat(task.getExpectedStart()).isNull();
        assertThat(task.getExpectedEnd()).isEqualTo(START);
    }

    @Test
    void explicitDeadlineWinsOverLegacyDuration() {
        LocalDateTime end = START.plusHours(5).plusMinutes(20);
        TaskInstanceDO task =
                node("root", AssignmentMode.OPEN, new Schedule(TimeMode.FIXED, START, 0, 3, end));
        calculate(task);
        assertThat(task.getExpectedEnd()).isEqualTo(end);
    }

    @Test
    void legacyFixedZeroAndPositiveDurationsKeepTheirMeaning() {
        TaskInstanceDO sameDay = node("zero", null, new Schedule(TimeMode.FIXED, START, 0, 0));
        TaskInstanceDO threeDays = node("three", null, new Schedule(TimeMode.FIXED, START, 0, 3));
        calculate(sameDay, threeDays);
        assertThat(sameDay.getExpectedEnd()).isEqualTo(START);
        assertThat(threeDays.getExpectedEnd()).isEqualTo(START.plusDays(3));
    }

    @Test
    void planStartUsesExplicitBaselineAndNaturalDaysNotCreationTime() {
        TaskInstanceDO task =
                node("root", AssignmentMode.OPEN, new Schedule(TimeMode.PLAN_START, null, 2, 3));
        task.setPlannedStart(START);
        task.setT0(START.minusYears(4));
        calculate(task);
        assertThat(task.getExpectedStart()).isEqualTo(START.plusDays(2));
        assertThat(task.getExpectedEnd()).isEqualTo(START.plusDays(5));
        assertThat(task.getActualStart()).isNull();
    }

    @Test
    void planStartRejectsMissingBaseline() {
        TaskInstanceDO task = node("root", AssignmentMode.OPEN, schedule(TimeMode.PLAN_START));
        assertThatThrownBy(() -> calculate(task)).isInstanceOf(ServiceException.class);
    }

    @Test
    void oneUnscheduledPredecessorMakesTheDependentDatesUnknown() {
        TaskInstanceDO known =
                node("known", AssignmentMode.OPEN, new Schedule(TimeMode.FIXED, START, 0, 1));
        TaskInstanceDO unknown =
                node("unknown", AssignmentMode.OPEN, schedule(TimeMode.UNSCHEDULED));
        TaskInstanceDO next =
                node(
                        "next",
                        AssignmentMode.OPEN,
                        new Schedule(TimeMode.PREDECESSOR, null, 2, 1),
                        "known",
                        "unknown");
        // 输入顺序不要求前置节点排在前面。
        calculate(next, known, unknown);
        assertThat(next.getExpectedStart()).isNull();
        assertThat(next.getExpectedEnd()).isNull();
    }

    @Test
    void completedPredecessorsUseLatestActualEndInsteadOfOldExpectedEnd() {
        TaskInstanceDO first = node("first", AssignmentMode.OPEN, schedule(TimeMode.UNSCHEDULED));
        TaskInstanceDO last = node("last", AssignmentMode.OPEN, schedule(TimeMode.UNSCHEDULED));
        first.setStatus("COMPLETED");
        first.setActualEnd(START);
        first.setExpectedEnd(START.plusYears(1));
        last.setStatus("COMPLETED");
        last.setActualEnd(START.plusHours(3));
        TaskInstanceDO next =
                node(
                        "next",
                        AssignmentMode.OPEN,
                        new Schedule(TimeMode.PREDECESSOR, null, 2, 1),
                        "first",
                        "last");
        calculate(next, first, last);
        assertThat(next.getExpectedStart()).isEqualTo(START.plusHours(3).plusDays(2));
        assertThat(next.getExpectedEnd()).isEqualTo(START.plusHours(3).plusDays(3));
    }

    @Test
    void startedTasksAreNotRescheduledByLaterCalculation() {
        TaskInstanceDO task = node("root", AssignmentMode.ASSIGNED, schedule(TimeMode.UNSCHEDULED));
        task.setStatus("RUNNING");
        task.setExpectedStart(START);
        task.setExpectedEnd(START.plusDays(1));
        task.setActualStart(START.plusHours(1));
        calculate(task);
        assertThat(task.getExpectedStart()).isEqualTo(START);
        assertThat(task.getExpectedEnd()).isEqualTo(START.plusDays(1));
        assertThat(task.getActualStart()).isEqualTo(START.plusHours(1));
    }

    private Schedule schedule(TimeMode mode) {
        return new Schedule(mode, null, 0, 1);
    }

    @Test
    void automaticLeafUsesWholeStartAndAllowsZeroDayDuration() {
        TaskInstanceDO root =
                node("root", AssignmentMode.OPEN, new Schedule(TimeMode.AUTO, null, 0, 0));
        root.setPlannedStart(START);
        calculate(root);
        assertThat(root.getExpectedStart()).isEqualTo(START);
        assertThat(root.getExpectedEnd()).isEqualTo(START);
    }

    @Test
    void automaticParentIgnoresOwnDurationAndSummarizesChildrenWithoutBaselineLoop() {
        TaskInstanceDO root =
                node("root", AssignmentMode.OPEN, new Schedule(TimeMode.AUTO, null, 99, 99));
        root.setPlannedStart(START);
        TaskInstanceDO first =
                node("first", AssignmentMode.OPEN, new Schedule(TimeMode.AUTO, null, 2, 1));
        first.setParentId("root");
        TaskInstanceDO last =
                node("last", AssignmentMode.OPEN, new Schedule(TimeMode.AUTO, null, 1, 2), "first");
        last.setParentId("root");
        calculate(last, root, first);
        assertThat(root.getExpectedStart()).isEqualTo(START.plusDays(2));
        assertThat(root.getExpectedEnd()).isEqualTo(START.plusDays(6));
    }

    @Test
    void automaticLeafInheritsAncestorPredecessorsWithoutDependingOnAncestorSummary() {
        TaskInstanceDO root = node("root", AssignmentMode.OPEN, schedule(TimeMode.AUTO));
        root.setPlannedStart(START);
        TaskInstanceDO before =
                node("before", AssignmentMode.OPEN, new Schedule(TimeMode.AUTO, null, 0, 3));
        before.setParentId("root");
        TaskInstanceDO branch =
                node("branch", AssignmentMode.OPEN, schedule(TimeMode.AUTO), "before");
        branch.setParentId("root");
        TaskInstanceDO leaf =
                node("leaf", AssignmentMode.OPEN, new Schedule(TimeMode.AUTO, null, 1, 2));
        leaf.setParentId("branch");
        calculate(root, before, branch, leaf);
        assertThat(leaf.getExpectedStart()).isEqualTo(START.plusDays(4));
        assertThat(branch.getExpectedStart()).isEqualTo(leaf.getExpectedStart());
        assertThat(root.getExpectedEnd()).isEqualTo(START.plusDays(6));
    }

    @Test
    void unscheduledParentRollupIsReadOnlyAndDoesNotReplanLegacyPredecessor() {
        TaskInstanceDO root = node("root", AssignmentMode.OPEN, schedule(TimeMode.UNSCHEDULED));
        TaskInstanceDO child =
                node("child", AssignmentMode.OPEN, new Schedule(TimeMode.FIXED, START, 0, 1));
        child.setParentId("root");
        TaskInstanceDO next =
                node("next", AssignmentMode.OPEN, schedule(TimeMode.PREDECESSOR), "root");
        calculate(root, child, next);
        assertThat(root.getExpectedEnd()).isNull();
        assertThat(next.getExpectedStart()).isNull();
        assertThat(
                        TaskSchedules.display(
                                        List.of(root, child, next),
                                        task -> definitions.get(task.getId()))
                                .get("root")
                                .end())
                .isEqualTo(START.plusDays(1));
        assertThat(root.getExpectedEnd()).isNull();
    }

    @Test
    void missingBaselineAndIncompleteSubtreeRemainExplicitlyPartial() {
        TaskInstanceDO root = node("root", AssignmentMode.OPEN, schedule(TimeMode.AUTO));
        TaskInstanceDO child = node("child", AssignmentMode.OPEN, schedule(TimeMode.AUTO));
        child.setParentId("root");
        com.lingan.ucp.nocode.api.TaskCenter.SchedulePreview preview =
                TaskSchedules.preview(List.of(root, child), task -> definitions.get(task.getId()));
        assertThat(preview.nodes()).allSatisfy(value -> assertThat(value.partial()).isTrue());
        assertThat(preview.warnings()).isNotEmpty();
        assertThat(root.getExpectedStart()).isNull();
    }

    @Test
    void cancelledChildrenDoNotExtendAutomaticSummary() {
        TaskInstanceDO root = node("root", AssignmentMode.OPEN, schedule(TimeMode.AUTO));
        root.setPlannedStart(START);
        TaskInstanceDO active = node("active", AssignmentMode.OPEN, schedule(TimeMode.AUTO));
        active.setParentId("root");
        TaskInstanceDO cancelled = node("cancelled", AssignmentMode.OPEN, schedule(TimeMode.AUTO));
        cancelled.setParentId("root");
        cancelled.setStatus("CANCELLED");
        cancelled.setExpectedEnd(START.plusYears(1));
        calculate(root, active, cancelled);
        assertThat(root.getExpectedEnd()).isEqualTo(START.plusDays(1));
    }

    @Test
    void startedParentKeepsOriginalDatesAndOnlyWarnsAboutChildDeadline() {
        TaskInstanceDO root = node("root", AssignmentMode.OPEN, schedule(TimeMode.AUTO));
        root.setStatus("RUNNING");
        root.setExpectedStart(START);
        root.setExpectedEnd(START.plusDays(1));
        TaskInstanceDO child =
                node("child", AssignmentMode.OPEN, new Schedule(TimeMode.FIXED, START, 0, 3));
        child.setParentId("root");
        calculate(root, child);
        assertThat(root.getExpectedEnd()).isEqualTo(START.plusDays(1));
        assertThat(
                        TaskSchedules.display(
                                        List.of(root, child), task -> definitions.get(task.getId()))
                                .get("root")
                                .summary()
                                .warnings())
                .isNotEmpty();
    }

    private TaskInstanceDO node(
            String id, AssignmentMode mode, Schedule schedule, String... predecessors) {
        TaskInstanceDO task = new TaskInstanceDO();
        task.setId(id);
        task.setRootId("root");
        task.setStatus("PENDING");
        task.setT0(START.minusYears(4));
        definitions.put(
                id,
                new NodeInput(
                        id,
                        null,
                        id,
                        null,
                        null,
                        null,
                        null,
                        schedule,
                        List.of(predecessors),
                        null,
                        null,
                        null,
                        mode,
                        List.of()));
        return task;
    }

    private void calculate(TaskInstanceDO... tasks) {
        TaskSchedules.calculate(List.of(tasks), task -> definitions.get(task.getId()));
    }
}
