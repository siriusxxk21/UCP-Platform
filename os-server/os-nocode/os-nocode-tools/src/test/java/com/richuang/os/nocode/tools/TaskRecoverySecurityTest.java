package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.api.work.PublishedResourceRef;
import com.richuang.os.nocode.application.service.published.ApplicationPublishedService;
import com.richuang.os.nocode.runtime.dal.dataobject.*;
import com.richuang.os.nocode.runtime.dal.mapper.*;
import com.richuang.os.nocode.runtime.service.handling.BusinessHandlingService;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.task.TaskEntryRuntimeScope;
import com.richuang.os.nocode.runtime.service.task.TaskGroupRuntime;
import com.richuang.os.nocode.runtime.service.taskcenter.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.function.Supplier;

/** 不连接数据库的授权边界回归；真实 Mapper 与事务另由集成测试验证。 */
class TaskRecoverySecurityTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Binding binding = new Binding("app", "form", null);
    private final BusinessRef pending =
            new BusinessRef(
                    new PublishedResourceRef("app", 1, "app-checksum", "form", "FORM"),
                    new ApplicationCenter.ObjectReference("object", 1, "object-checksum"),
                    null,
                    "request");

    private TaskInstanceDO task() throws Exception {
        TaskInstanceDO task = new TaskInstanceDO();
        task.setId("task");
        task.setRootId("task");
        task.setCreator("1");
        task.setAssigneeId(2L);
        task.setStatus("RUNNING");
        task.setLockVersion(1);
        task.setConfigJson(
                json.writeValueAsString(
                        new NodeInput(
                                "task",
                                null,
                                "受派任务",
                                null,
                                2L,
                                Urgency.NORMAL,
                                Priority.MEDIUM,
                                new Schedule(TimeMode.T0, null, 0, 0),
                                List.of(),
                                binding,
                                new Sharing(DataMode.INDEPENDENT, null, List.of()))));
        task.setBusinessJson(json.writeValueAsString(pending));
        return task;
    }

    @Test
    void recoveryRequiresLockedRevisionChangeAndMatchingActorReceipt() throws Exception {
        TaskCenterMapper store = mock(TaskCenterMapper.class);
        TaskInstanceDO task = task();
        when(store.get("task", false)).thenReturn(task);
        when(store.instance("task")).thenReturn(List.of(task));
        PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        TaskCenterServiceImpl service = spy(new TaskCenterServiceImpl());
        ReflectionTestUtils.setField(service, "store", store);
        ReflectionTestUtils.setField(service, "json", json);
        ReflectionTestUtils.setField(service, "permissions", mock(PermissionCommonApi.class));
        ReflectionTestUtils.setField(
                service, "workflowProtection", mock(TaskWorkflowProtection.class));
        ReflectionTestUtils.setField(service, "tx", new TransactionTemplate(transactions));
        Transition command = new Transition("task", 1, Action.COMPLETE, "原始备注", "original-key");

        // 同版本且没有回执仍可能有迟到提交，不能宣称未生效。
        assertThat(service.transitionRecovery(command, 2L))
                .isEqualTo(new TransitionRecovery(null, false));
        org.mockito.InOrder order = inOrder(store);
        order.verify(store).get("task", false);
        order.verify(store).get("task", true);
        order.verify(store).instance("task");
        order.verify(store).get("task", false);
        order.verify(store).requested("2", "original-key");

        task.setLockVersion(2);
        assertThat(service.transitionRecovery(command, 2L))
                .isEqualTo(new TransitionRecovery(null, true));
        assertThatThrownBy(() -> service.transition(command, 2L)).hasMessageContaining("修改");

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
                                                "task", 1, Action.COMPLETE, "篡改备注", "original-key"),
                                        2L))
                .hasMessageContaining("不同内容");
        assertThatThrownBy(() -> service.transitionRecovery(command, 3L))
                .hasMessageContaining("权限");
        verify(store, never()).requested(eq("3"), anyString());
        verify(store, never()).appendEvent(any(), anyString());
        verify(store, never()).updateTask(any(), anyString());
    }

    @Test
    void approvedApplicationStillRequiresApprovalAndCurrentRecordAccess() throws Exception {
        TaskCenterMapper store = mock(TaskCenterMapper.class);
        TaskInstanceDO task = task();
        when(store.get("task", false)).thenReturn(task);
        when(store.instance("task")).thenReturn(List.of(task));
        BusinessHandlingService handling = mock(BusinessHandlingService.class);
        BusinessHandling.Request approved =
                new BusinessHandling.Request(
                        "request",
                        1,
                        "APPROVED",
                        "app",
                        "应用",
                        "object",
                        "对象",
                        null,
                        "record",
                        "CREATE",
                        "申请",
                        null,
                        null,
                        "submission",
                        null,
                        null,
                        null);
        when(handling.detail("request", null, 2L))
                .thenReturn(new BusinessHandling.Detail(approved, null, null));
        RecordService records = mock(RecordService.class);
        ApplicationRecords.Aggregate aggregate =
                new ApplicationRecords.Aggregate(
                        new ApplicationRecords.Row("record", "revision", Map.of("name", "可读业务")),
                        Map.of());
        when(records.get("app", "object", "record", 2L)).thenReturn(aggregate);
        ApplicationPublishedService published = mock(ApplicationPublishedService.class);
        when(published.withVersion(any(), any()))
                .thenAnswer(
                        invocation -> {
                            Supplier<?> action = invocation.getArgument(1);
                            return action.get();
                        });
        TaskBusiness business = new TaskBusiness();
        ReflectionTestUtils.setField(business, "handling", handling);
        ReflectionTestUtils.setField(business, "records", records);
        ReflectionTestUtils.setField(business, "published", published);
        ReflectionTestUtils.setField(business, "taskScope", mock(TaskEntryRuntimeScope.class));
        DataObjectApi objects = mock(DataObjectApi.class);
        when(objects.getVersion("object", 1))
                .thenReturn(
                        new DataObjectApi.PublishedObject("object", 1, "object-checksum", null));
        ReflectionTestUtils.setField(business, "objects", objects);
        TaskBoundViews views = mock(TaskBoundViews.class);
        when(views.project(
                        eq(binding),
                        any(BusinessRef.class),
                        any(ApplicationRecords.Aggregate.class)))
                .thenAnswer(invocation -> invocation.getArgument(2));
        ReflectionTestUtils.setField(business, "boundViews", views);
        TaskCenterServiceImpl service = new TaskCenterServiceImpl();
        ReflectionTestUtils.setField(service, "store", store);
        ReflectionTestUtils.setField(service, "json", json);
        ReflectionTestUtils.setField(service, "business", business);
        ReflectionTestUtils.setField(service, "permissions", mock(PermissionCommonApi.class));
        ReflectionTestUtils.setField(service, "workEntries", mock(TaskWorkEntryService.class));
        AdminUserApi users = mock(AdminUserApi.class);
        AdminUserRespDTO worker = new AdminUserRespDTO();
        worker.setId(2L);
        worker.setStatus(0);
        when(users.getUser(2L)).thenReturn(worker);
        ReflectionTestUtils.setField(service, "users", users);

        assertThat(service.readiness("task", 2L).canComplete()).isTrue();
        verify(handling).detail("request", null, 2L);
        verify(records).get("app", "object", "record", 2L);
        when(records.get("app", "object", "record", 2L))
                .thenThrow(NocodeErrorCodes.invalid("业务记录已撤权"));
        TaskGuidance.Readiness denied = service.readiness("task", 2L);
        assertThat(denied.canComplete()).isFalse();
        assertThat(denied.checks())
                .filteredOn(check -> check.code() == TaskGuidance.CheckCode.BUSINESS)
                .allSatisfy(check -> assertThat(check.reason()).contains("权限"));
        verify(handling, times(2)).detail("request", null, 2L);
        assertThat(task.getBusinessJson()).isEqualTo(json.writeValueAsString(pending));
        verify(store, never()).updateTask(any(), anyString());
        verify(store, never()).appendEvent(any(), anyString());

        // 已审批身份不授予材料权限；旧任务同样必须经过公共审批读取边界。
        when(handling.detail("request", null, 2L)).thenThrow(NocodeErrorCodes.invalid("没有审批材料权限"));
        clearInvocations(records);
        assertThat(service.readiness("task", 2L).canComplete()).isFalse();
        verify(handling, times(3)).detail("request", null, 2L);
        verifyNoInteractions(records);
    }

    @Test
    void readableEntryCanLinkWithoutEditPermissionButNotWhenTaskIsReadonly() throws Exception {
        TaskCenterMapper tasks = mock(TaskCenterMapper.class);
        TaskWorkEntryMapper store = mock(TaskWorkEntryMapper.class);
        TaskBusiness business = mock(TaskBusiness.class);
        TaskGroupRuntime groupRuntime = mock(TaskGroupRuntime.class);
        TaskWorkflowProtection workflow = mock(TaskWorkflowProtection.class);
        TaskInstanceDO task = task();
        when(tasks.get("task", false)).thenReturn(task);
        when(tasks.instance("task")).thenReturn(List.of(task));
        TaskWorkEntries.Config config =
                new TaskWorkEntries.Config(
                        "work",
                        "办理项",
                        binding,
                        TaskWorkEntries.DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        false,
                        true);
        TaskWorkEntryDO entry = new TaskWorkEntryDO();
        entry.setEntryKey("work");
        entry.setDatasetId("shared");
        entry.setConfigJson(json.writeValueAsString(config));
        entry.setBusinessJson(json.writeValueAsString(pending));
        when(store.entries("task")).thenReturn(List.of(entry));
        when(groupRuntime.execute(eq("task"), eq("work"), anyLong(), eq(false), any()))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get());
        ApplicationRecords.Model readonly =
                new ApplicationRecords.Model(
                        null,
                        false,
                        false,
                        null,
                        null,
                        Map.of(),
                        new ApplicationAuthorization.Capabilities(
                                Set.of("READ"), Set.of("name"), Set.of(), Set.of(), Set.of()),
                        List.of());
        when(business.model(eq(pending), eq(binding), anyLong())).thenReturn(readonly);
        TaskWorkEntryServiceImpl service = new TaskWorkEntryServiceImpl();
        ReflectionTestUtils.setField(service, "tasks", tasks);
        ReflectionTestUtils.setField(service, "store", store);
        ReflectionTestUtils.setField(service, "business", business);
        ReflectionTestUtils.setField(service, "json", json);
        ReflectionTestUtils.setField(service, "permissions", mock(PermissionCommonApi.class));
        ReflectionTestUtils.setField(service, "groupRuntime", groupRuntime);
        ReflectionTestUtils.setField(service, "workflowProtection", workflow);
        ReflectionTestUtils.setField(service, "standardWork", mock(TaskStandardWork.class));

        TaskWorkEntries.Entry available = service.entries("task", 2L).getFirst();
        assertThat(available.canLink()).isTrue();
        assertThat(available.canWrite()).isFalse();
        assertThat(available.canDelete()).isFalse();
        assertThat(service.entries("task", 1L).getFirst().canLink()).isFalse();

        when(workflow.readOnlyReasons(List.of("task"))).thenReturn(Map.of("task", "来源流程已结束"));
        assertThat(service.entries("task", 2L).getFirst().canLink()).isFalse();
        when(workflow.readOnlyReasons(List.of("task"))).thenReturn(Map.of());
        for (String state :
                List.of("PENDING", "PAUSED", "PENDING_ACCEPTANCE", "COMPLETED", "CANCELLED")) {
            task.setStatus(state);
            assertThat(service.entries("task", 2L).getFirst().canLink()).as(state).isFalse();
        }
        task.setStatus("RUNNING");
        when(business.model(pending, binding, 2L)).thenThrow(NocodeErrorCodes.invalid("业务授权已撤销"));
        TaskWorkEntries.Entry revoked = service.entries("task", 2L).getFirst();
        assertThat(revoked.canLink()).isFalse();
        assertThat(revoked.canWrite()).isFalse();
        assertThat(revoked.workSummary()).isNull();
        verify(business, never()).save(any(), any(), any(), any(), anyLong());
        verify(store, never()).saveRecord(any(), anyString());
    }

    @Test
    void resubmittingFromAnotherTaskOrEntryStopsBeforeBusinessWritesOrSuperseding()
            throws Exception {
        for (String[] source :
                List.of(
                        new String[] {"other-task", "work"},
                        new String[] {"task", "other-entry"},
                        new String[] {"task", "work"})) {
            TaskCenterMapper tasks = mock(TaskCenterMapper.class);
            TaskWorkEntryMapper store = mock(TaskWorkEntryMapper.class);
            TaskBusiness business = mock(TaskBusiness.class);
            TaskInstanceDO task = task();
            when(tasks.get("task", false)).thenReturn(task);
            when(tasks.instance("task")).thenReturn(List.of(task));
            TaskWorkEntries.Config config =
                    new TaskWorkEntries.Config(
                            "work",
                            "业务办理项",
                            binding,
                            TaskWorkEntries.DataMode.ROOT_SHARED,
                            null,
                            null,
                            null,
                            null,
                            true,
                            false);
            TaskWorkEntryDO entry = new TaskWorkEntryDO();
            entry.setTaskId("task");
            entry.setEntryKey("work");
            entry.setDatasetId("shared");
            entry.setConfigJson(json.writeValueAsString(config));
            entry.setBusinessJson(json.writeValueAsString(pending));
            when(store.entry("task", "work")).thenReturn(entry);
            TaskWorkRecordDO original = new TaskWorkRecordDO();
            original.setId("contribution");
            original.setTaskId(source[0]);
            original.setEntryKey(source[1]);
            original.setCreator("2");
            original.setDatasetId("shared");
            original.setBusinessJson(json.writeValueAsString(pending));
            when(store.record("contribution")).thenReturn(original);
            boolean expiresDuringCheck = source[0].equals("task") && source[1].equals("work");
            if (expiresDuringCheck) {
                // 模拟共享记录在首次身份检查通过后被原任务清理，必须返回领域错误。
                when(store.record("contribution")).thenReturn(original, null);
            }
            TaskWorkEntryServiceImpl service = new TaskWorkEntryServiceImpl();
            ReflectionTestUtils.setField(service, "tasks", tasks);
            ReflectionTestUtils.setField(service, "store", store);
            ReflectionTestUtils.setField(service, "business", business);
            ReflectionTestUtils.setField(service, "json", json);
            ReflectionTestUtils.setField(service, "permissions", mock(PermissionCommonApi.class));
            ReflectionTestUtils.setField(service, "groupRuntime", mock(TaskGroupRuntime.class));
            ReflectionTestUtils.setField(
                    service, "workflowProtection", mock(TaskWorkflowProtection.class));
            ApplicationRecords.Save input =
                    new ApplicationRecords.Save(
                            "app",
                            "object",
                            null,
                            null,
                            Map.of("name", "重提"),
                            Map.of(),
                            Map.of(),
                            null,
                            "form",
                            UUID.randomUUID().toString(),
                            null);
            assertThatThrownBy(
                            () ->
                                    service.save(
                                            new TaskWorkEntries.Save(
                                                    "task", "work", "contribution", input),
                                            2L))
                    .hasMessageContaining(expiresDuringCheck ? "办理记录已失效" : "原任务的原办理项");
            verify(business, never()).save(any(), any(), any(), any(), anyLong());
            verify(store, never()).supersede(anyString(), anyString(), anyString());
            verify(store, never()).saveRecord(any(), anyString());
        }
    }
}
