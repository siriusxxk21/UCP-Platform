package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.runtime.dal.dataobject.TaskHistoryDO;
import com.richuang.os.nocode.runtime.dal.dataobject.TaskInstanceDO;
import com.richuang.os.nocode.runtime.dal.mapper.TaskCenterMapper;
import com.richuang.os.nocode.runtime.service.taskcenter.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.*;

/** 自动收尾规则的离线失败路径；真实事务、锁和权限端到端由集成及 HTTP 验收验证。 */
class TaskRollupRulesTest {
    private final TaskCenterServiceImpl service = new TaskCenterServiceImpl();
    private final TaskCenterMapper store = mock(TaskCenterMapper.class);
    private final TaskWorkEntryService entries = mock(TaskWorkEntryService.class);
    private final TaskAssignments assignments = mock(TaskAssignments.class);
    private final TaskBusiness business = mock(TaskBusiness.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final List<TaskHistoryDO> events = new ArrayList<>();

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "store", store);
        ReflectionTestUtils.setField(service, "json", json);
        ReflectionTestUtils.setField(service, "workEntries", entries);
        ReflectionTestUtils.setField(service, "assignments", assignments);
        ReflectionTestUtils.setField(service, "business", business);
        ReflectionTestUtils.setField(service, "transactionManager", transactions);
        when(transactions.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
        ReflectionTestUtils.invokeMethod(service, "initialize");
        when(store.events(anyString())).thenReturn(events);
        when(store.updateTask(any(), anyString())).thenReturn(1);
        doAnswer(
                        call -> {
                            events.add(call.getArgument(0));
                            return null;
                        })
                .when(store)
                .appendEvent(any(), anyString());
        when(entries.snapshot(anyString(), anyLong())).thenReturn(List.of());
    }

    private TaskInstanceDO task(String id, String parent, String status, Long owner)
            throws Exception {
        TaskInstanceDO task = new TaskInstanceDO();
        task.setId(id);
        task.setRootId("root");
        task.setParentId(parent);
        task.setTitle(id);
        task.setStatus(status);
        task.setAssigneeId(owner);
        task.setLockVersion(1);
        task.setCreator("1");
        task.setConfigJson(
                json.writeValueAsString(
                        Map.of(
                                "id",
                                id,
                                "title",
                                id,
                                "sharing",
                                Map.of("mode", "INDEPENDENT", "writableFieldIds", List.of()))));
        return task;
    }

    private List<TaskInstanceDO> tree() throws Exception {
        return new ArrayList<>(
                List.of(
                        task("root", null, "RUNNING", 10L),
                        task("branch", "root", "RUNNING", 20L),
                        task("leaf", "branch", "COMPLETED", 30L)));
    }

    private void rollup(List<TaskInstanceDO> nodes) {
        ReflectionTestUtils.invokeMethod(service, "rollupParents", nodes.getLast(), nodes, 30L);
    }

    @Test
    void eachAncestorUsesItsOwnAssigneeForMaterialChecksAndTriggerActorForAudit() throws Exception {
        List<TaskInstanceDO> nodes = tree();
        rollup(nodes);
        assertThat(nodes).allMatch(n -> n.getStatus().equals("COMPLETED"));
        verify(entries).complete("branch", 20L);
        verify(entries).complete("root", 10L);
        verify(store, times(2)).updateTask(any(), eq("30"));
        assertThat(events).extracting(TaskHistoryDO::getTaskId).containsExactly("branch", "root");
        assertThat(events).allMatch(e -> e.getEventType().equals("COMPLETED"));
        rollup(nodes);
        assertThat(events).hasSize(2);
    }

    @Test
    void missingMiddleMaterialLeavesBothAncestorsOpenAndPreservesChildSuccess() throws Exception {
        List<TaskInstanceDO> nodes = tree();
        doThrow(invalid("请补齐必填反馈材料")).when(entries).complete("branch", 20L);
        rollup(nodes);
        assertThat(nodes)
                .extracting(TaskInstanceDO::getStatus)
                .containsExactly("RUNNING", "RUNNING", "COMPLETED");
        verify(transactions).rollback(any());
        verify(store, never()).updateTask(any(), anyString());
        verify(entries, never()).complete("root", 10L);
        assertThat(events)
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.getEventType()).isEqualTo("ROLLUP_BLOCKED");
                            assertThat(event.getMaterialJson()).contains("必填反馈");
                        });
        String reason =
                ReflectionTestUtils.invokeMethod(
                        service, "completionReason", nodes.get(1), nodes, events);
        assertThat(reason).contains("必填反馈");
    }

    @Test
    void revokedParentMaterialPermissionCannotBeBorrowedFromChildActor() throws Exception {
        List<TaskInstanceDO> nodes = tree();
        doThrow(invalid("当前负责人无权读取此业务材料")).when(entries).complete("branch", 20L);
        rollup(nodes);
        verify(entries).complete("branch", 20L);
        verify(entries, never()).complete("branch", 30L);
        assertThat(events.getFirst().getEventType()).isEqualTo("ROLLUP_BLOCKED");
        assertThat(nodes.get(1).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void rejectedParentNeverAutomaticallyResubmitsAfterNewChildFinishes() throws Exception {
        List<TaskInstanceDO> nodes = tree();
        TaskHistoryDO rejected = new TaskHistoryDO();
        rejected.setTaskId("branch");
        rejected.setEventType("REJECTED");
        events.add(rejected);
        rollup(nodes);
        verifyNoInteractions(entries);
        assertThat(events).hasSize(1);
        assertThat(nodes.get(1).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void cancelledSiblingIsNotSuccessfulCompletionAndNeedsManualAcknowledgment() throws Exception {
        List<TaskInstanceDO> nodes = tree();
        nodes.addFirst(task("cancelled", "branch", "CANCELLED", 20L));
        rollup(nodes);
        verifyNoInteractions(entries);
        String reason =
                ReflectionTestUtils.invokeMethod(
                        service, "completionReason", nodes.get(2), nodes, events);
        assertThat(reason).contains("取消", "确认");
    }

    @Test
    void inactiveOrMissingParentOwnerCannotSilentlyComplete() throws Exception {
        List<TaskInstanceDO> nodes = tree();
        nodes.get(1).setAssigneeId(null);
        rollup(nodes);
        assertThat(events.getFirst().getMaterialJson()).contains("负责人");
        verifyNoInteractions(entries);
    }

    @Test
    void pendingBusinessApprovalBlocksAutomaticParentButNotFinishedChild() throws Exception {
        List<TaskInstanceDO> nodes = tree();
        BusinessRef waiting = new BusinessRef(null, null, "record", "request");
        nodes.get(1).setBusinessJson(json.writeValueAsString(waiting));
        when(business.refresh(waiting, 20L)).thenReturn(waiting);
        rollup(nodes);
        assertThat(nodes.getLast().getStatus()).isEqualTo("COMPLETED");
        assertThat(nodes.get(1).getStatus()).isEqualTo("RUNNING");
        assertThat(events.getFirst().getMaterialJson()).contains("审批生效");
        verify(business, never()).read(any(), any(), anyLong());
    }
}
