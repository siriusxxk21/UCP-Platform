package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.runtime.dal.dataobject.TaskInstanceDO;
import com.richuang.os.nocode.runtime.dal.mapper.TaskCenterMapper;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskCenterServiceImpl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** 模拟锁前缓存与锁后负责人变化，确保员工拆分使用锁后的真实身份而非旧视图。 */
class TaskEmployeeSplitConcurrencyTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final TaskCenterMapper store = mock(TaskCenterMapper.class);
    private final TaskCenterServiceImpl tasks = new TaskCenterServiceImpl();
    private TaskInstanceDO original;

    @BeforeEach
    void setup() throws Exception {
        original = task(2L, State.RUNNING);
        when(store.get("task", false)).thenReturn(original);
        when(store.get("task", true)).thenReturn(original);
        when(store.instance("task")).thenReturn(List.of(original));
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ReflectionTestUtils.setField(tasks, "store", store);
        ReflectionTestUtils.setField(tasks, "json", json);
        ReflectionTestUtils.setField(tasks, "permissions", mock(PermissionCommonApi.class));
        ReflectionTestUtils.setField(tasks, "tx", new TransactionTemplate(transactionManager));
    }

    @Test
    void reassignmentWhileWaitingForRootLockRevokesSplit() throws Exception {
        TaskInstanceDO reassigned = task(3L, State.RUNNING);
        when(store.planningTask("task")).thenReturn(reassigned);
        assertThatThrownBy(() -> tasks.split(command(), 2L)).hasMessageContaining("本人负责");
        InOrder order = inOrder(store);
        order.verify(store).get("task", true);
        order.verify(store).planningTask("task");
        verify(store, never()).insertTask(any(), anyString());
    }

    @Test
    void completionWhileWaitingForRootLockRevokesSplit() throws Exception {
        when(store.planningTask("task")).thenReturn(task(2L, State.COMPLETED));
        assertThatThrownBy(() -> tasks.split(command(), 2L)).hasMessageContaining("不能拆分");
        verify(store, never()).insertTask(any(), anyString());
    }

    private TaskInstanceDO task(Long assignee, State state) throws Exception {
        TaskInstanceDO task = new TaskInstanceDO();
        task.setId("task");
        task.setRootId("task");
        task.setTitle("自己任务");
        task.setAssigneeId(assignee);
        task.setCreator("1");
        task.setStatus(state.name());
        task.setConfigJson(json.writeValueAsString(node("root", assignee)));
        return task;
    }

    private Create command() {
        return new Create(node("new", null), "task", null, null, null, null, null, "split-request");
    }

    private NodeInput node(String id, Long assignee) {
        return new NodeInput(
                id,
                null,
                "细分工作",
                null,
                assignee,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                null,
                AssignmentMode.ASSIGNED,
                List.of());
    }
}
