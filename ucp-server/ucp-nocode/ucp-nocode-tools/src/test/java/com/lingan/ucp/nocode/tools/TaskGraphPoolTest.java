package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.TaskCenter.AssignmentMode;
import com.lingan.ucp.nocode.api.TaskCenter.Create;
import com.lingan.ucp.nocode.api.TaskCenter.DataAccessMode;
import com.lingan.ucp.nocode.api.TaskCenter.DataPolicy;
import com.lingan.ucp.nocode.api.TaskCenter.NodeInput;
import com.lingan.ucp.nocode.api.TaskCenter.Schedule;
import com.lingan.ucp.nocode.api.TaskCenter.TimeMode;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskGraph;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/** 任务池输入与旧协议兼容的纯规则回归；不启动服务或修改开发数据库。 */
class TaskGraphPoolTest {
    @Test
    void explicitOpenAndUnassignedKeepExecutorEmpty() {
        for (AssignmentMode mode : List.of(AssignmentMode.OPEN, AssignmentMode.UNASSIGNED)) {
            NodeInput node = normalize(input(mode, null, List.of(), null));
            assertThat(node.assigneeId()).isNull();
            assertThat(node.assignmentMode()).isEqualTo(mode);
            assertThat(node.schedule().mode()).isEqualTo(TimeMode.UNSCHEDULED);
        }
    }

    @Test
    void omittedAssignmentModeRetainsLegacyCreatorAndTemplateSemantics() {
        NodeInput input = input(null, null, null, null);
        assertThat(normalize(input).assigneeId()).isEqualTo(101L);
        assertThat(normalize(input).schedule().mode()).isEqualTo(TimeMode.T0);
        assertThat(TaskGraph.template(List.of(input), 101L).get(0).assigneeId()).isNull();
    }

    @Test
    void explicitDataPolicyCannotAccidentallySelectLegacyTreeVisibility() {
        NodeInput node =
                new NodeInput(
                        "root",
                        null,
                        "总任务授权",
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP));
        assertThatThrownBy(() -> normalize(node))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("明确选择人员安排");
        assertThatThrownBy(() -> TaskGraph.template(List.of(node), 101L))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("明确选择人员安排");
        assertThat(normalize(input(null, null, null, null)).assigneeId()).isEqualTo(101L);
    }

    @Test
    void absentNewFieldsDoNotChangeLegacyRequestHashInput() throws Exception {
        NodeInput input = input(null, null, null, new Schedule(TimeMode.T0, null, 0, 1));
        Create command = new Create(input, null, null, null, null, null, null, "legacy-key");
        String serialized = new ObjectMapper().writeValueAsString(command);
        assertThat(serialized)
                .doesNotContain("assignmentMode", "candidateUserIds", "plannedStart", "fixedEnd");
    }

    @Test
    void explicitAssignedRequiresExecutorAndKeepsChosenPerson() {
        assertThatThrownBy(() -> normalize(input(AssignmentMode.ASSIGNED, null, null, null)))
                .isInstanceOf(ServiceException.class);
        assertThat(normalize(input(AssignmentMode.ASSIGNED, 202L, null, null)).assigneeId())
                .isEqualTo(202L);
    }

    @Test
    void openAndUnassignedCannotSmuggleAnExecutor() {
        for (AssignmentMode mode : List.of(AssignmentMode.OPEN, AssignmentMode.UNASSIGNED)) {
            assertThatThrownBy(() -> normalize(input(mode, 202L, null, null)))
                    .isInstanceOf(ServiceException.class);
        }
    }

    @Test
    void candidateRangeIsOnlyAllowedForOpenTasks() {
        assertThat(
                        normalize(input(AssignmentMode.OPEN, null, List.of(202L, 303L), null))
                                .candidateUserIds())
                .containsExactly(202L, 303L);
        assertThatThrownBy(
                        () ->
                                normalize(
                                        input(
                                                AssignmentMode.UNASSIGNED,
                                                null,
                                                List.of(202L),
                                                null)))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(
                        () -> normalize(input(AssignmentMode.ASSIGNED, 202L, List.of(202L), null)))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void invalidCandidateIdsReturnBusinessErrorsInsteadOfNullPointerErrors() {
        for (List<Long> candidates :
                List.of(
                        List.of(202L, 202L),
                        List.of(0L),
                        List.of(-1L),
                        Arrays.asList(202L, null))) {
            assertThatThrownBy(() -> normalize(input(AssignmentMode.OPEN, null, candidates, null)))
                    .isInstanceOf(ServiceException.class);
        }
    }

    @Test
    void fixedScheduleAllowsDeadlineOnlyAndPreservesExactTime() {
        LocalDateTime end = LocalDateTime.of(2026, 10, 8, 16, 45);
        Schedule schedule = new Schedule(TimeMode.FIXED, null, 0, 0, end);
        Schedule normalized =
                normalize(input(AssignmentMode.OPEN, null, null, schedule)).schedule();
        assertThat(normalized.fixedStart()).isNull();
        assertThat(normalized.fixedEnd()).isEqualTo(end);
    }

    @Test
    void fixedScheduleRejectsMissingAndReversedDates() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 8, 16, 45);
        for (Schedule schedule :
                List.of(
                        new Schedule(TimeMode.FIXED, null, 0, 0, null),
                        new Schedule(TimeMode.FIXED, start, 0, 0, start.minusMinutes(1)))) {
            assertThatThrownBy(() -> normalize(input(AssignmentMode.OPEN, null, null, schedule)))
                    .isInstanceOf(ServiceException.class);
        }
    }

    @Test
    void predecessorScheduleNeedsAnExplicitDependency() {
        Schedule schedule = new Schedule(TimeMode.PREDECESSOR, null, 2, 1);
        assertThatThrownBy(() -> normalize(input(AssignmentMode.OPEN, null, null, schedule)))
                .isInstanceOf(ServiceException.class);
    }

    private NodeInput normalize(NodeInput node) {
        return TaskGraph.normalize(List.of(node), 101L).get(0);
    }

    private NodeInput input(
            AssignmentMode mode, Long executor, List<Long> candidates, Schedule schedule) {
        return new NodeInput(
                "root",
                null,
                "任务池规则测试",
                null,
                executor,
                null,
                null,
                schedule,
                List.of(),
                null,
                null,
                null,
                mode,
                candidates);
    }
}
