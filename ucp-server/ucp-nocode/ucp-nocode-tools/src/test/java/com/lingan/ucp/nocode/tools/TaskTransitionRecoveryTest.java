package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskHistoryDO;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskInstanceDO;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskCenterMapper;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterServiceImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 原请求只读确认的纯回归，不连接数据库，不执行任务状态或业务写入。 */
class TaskTransitionRecoveryTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final TaskCenterMapper store = mock(TaskCenterMapper.class);
    private final TaskCenterServiceImpl service = spy(new TaskCenterServiceImpl());
    private final TaskInstanceDO task = new TaskInstanceDO();
    private final Transition command =
            new Transition("task", 1, Action.COMPLETE, "原备注", "original-key");

    @BeforeEach
    void setup() throws Exception {
        task.setId("task");
        task.setRootId("task");
        task.setCreator("1");
        task.setAssigneeId(2L);
        task.setStatus(State.RUNNING.name());
        task.setLockVersion(1);
        task.setConfigJson(
                json.writeValueAsString(
                        new NodeInput(
                                "task",
                                null,
                                "测试",
                                null,
                                2L,
                                Urgency.NORMAL,
                                Priority.MEDIUM,
                                new Schedule(TimeMode.T0, null, 0, 0),
                                List.of(),
                                null,
                                new Sharing(DataMode.INDEPENDENT, null, List.of()))));
        when(store.get("task", false)).thenReturn(task);
        when(store.instance("task")).thenReturn(List.of(task));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(service, "store", store);
        ReflectionTestUtils.setField(service, "json", json);
        ReflectionTestUtils.setField(service, "permissions", mock(PermissionCommonApi.class));
        ReflectionTestUtils.setField(service, "tx", new TransactionTemplate(transactions));
    }

    @Test
    void sameOrFutureRevisionWithoutReceiptRemainsUncertain() {
        assertThat(service.transitionRecovery(command, 2L))
                .isEqualTo(new TransitionRecovery(null, false));
        Transition future = new Transition("task", 3, Action.COMPLETE, "原备注", "future-key");
        assertThat(service.transitionRecovery(future, 2L))
                .isEqualTo(new TransitionRecovery(null, false));
        verify(store, times(2)).get("task", true);
        verify(store, never()).updateTask(any(), anyString());
        verify(store, never()).appendEvent(any(), anyString());
    }

    @Test
    void onlyOldRevisionWithoutReceiptCanBeReleased() {
        task.setLockVersion(2);
        assertThat(service.transitionRecovery(command, 2L))
                .isEqualTo(new TransitionRecovery(null, true));
        assertThatThrownBy(() -> service.transition(command, 2L)).hasMessageContaining("修改");
        verify(store, never()).updateTask(any(), anyString());
    }

    @Test
    void actorReceiptWinsOverChangedRevisionAndReturnsCurrentAuthorizedDetail() throws Exception {
        task.setLockVersion(2);
        TaskHistoryDO receipt = new TaskHistoryDO();
        receipt.setRequestHash(
                cn.hutool.crypto.digest.DigestUtil.sha256Hex(json.writeValueAsString(command)));
        when(store.requested("2", "original-key")).thenReturn(receipt);
        Detail applied = new Detail(null, List.of(), List.of(), List.of());
        doReturn(applied).when(service).detail("task", 2L);
        assertThat(service.transitionRecovery(command, 2L))
                .isEqualTo(new TransitionRecovery(applied, false));
        assertThatThrownBy(
                        () ->
                                service.transitionRecovery(
                                        new Transition(
                                                "task", 1, Action.CANCEL, "原备注", "original-key"),
                                        2L))
                .hasMessageContaining("不同内容");
        verify(store, never()).updateTask(any(), anyString());
    }

    @Test
    void unrelatedActorCannotProbeAnotherActorsReceipt() {
        assertThatThrownBy(() -> service.transitionRecovery(command, 3L))
                .hasMessageContaining("权限");
        verify(store, never()).requested(anyString(), anyString());
    }

    @Test
    void visibilityIsCheckedAgainAfterWaitingForRootLock() {
        TaskInstanceDO reassigned = new TaskInstanceDO();
        reassigned.setId("task");
        reassigned.setRootId("task");
        reassigned.setCreator("1");
        reassigned.setAssigneeId(3L);
        reassigned.setConfigJson(task.getConfigJson());
        when(store.get("task", false)).thenReturn(task, reassigned);
        when(store.instance("task")).thenReturn(List.of(reassigned));
        assertThatThrownBy(() -> service.transitionRecovery(command, 2L))
                .hasMessageContaining("权限");
        verify(store, never()).requested(anyString(), anyString());
    }
}
