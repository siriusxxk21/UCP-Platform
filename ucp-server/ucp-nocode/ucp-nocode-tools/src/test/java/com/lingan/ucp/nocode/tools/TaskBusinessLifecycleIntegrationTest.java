package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.module.bpm.api.event.BpmProcessInstanceStatus;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;

import org.junit.jupiter.api.*;

import java.util.*;

/** 真实开发库验证任务引用与审批终态衔接；BPM 回调使用既有契约夹具。 */
class TaskBusinessLifecycleIntegrationTest {
    private BusinessHandlingIntegrationTest approval;
    private TaskCenterService tasks;
    private String taskId;
    private final List<String> roots = new ArrayList<>();
    private static final long ACTOR = 10001L;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void closeContext() {
        close();
    }

    @BeforeEach
    void setup() {
        approval = new BusinessHandlingIntegrationTest();
        approval.setup();
        approval.policy("APPROVAL", "APPROVAL");
        tasks = servicesContext.getBean(TaskCenterService.class);
        AdminUserApi users = servicesContext.getBean(AdminUserApi.class);
        when(users.getUser(ACTOR))
                .thenAnswer(
                        invocation -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(ACTOR);
                            user.setNickname("任务审批测试");
                            user.setStatus(0);
                            return user;
                        });
        NodeInput node =
                new NodeInput(
                        null,
                        null,
                        "任务审批重提",
                        null,
                        ACTOR,
                        null,
                        null,
                        null,
                        List.of(),
                        new Binding(
                                approval.business.resource.applicationId(),
                                approval.business.resource.resourceId(),
                                null),
                        null);
        Detail created =
                tasks.create(new Create(node, null, null, null, null, null, null, key()), ACTOR);
        taskId = created.task().id();
        roots.add(taskId);
        tasks.transition(
                new Transition(taskId, created.task().revision(), Action.START, null, key()),
                ACTOR);
    }

    @AfterEach
    void cleanup() {
        for (String root : roots) {
            jdbc.update("DELETE FROM public.nocode_task_event WHERE root_id=?", root);
            jdbc.update("DELETE FROM public.nocode_task_instance WHERE root_id=?", root);
        }
        approval.cleanup();
    }

    @Test
    void rejectedAndWithdrawnTaskCanResubmitAfterApplicationPublishAndCompleteOnceEffective() {
        lifecycle();
    }

    @Test
    void fixedEntryApprovalAlsoResubmitsAndAppliesAgainstTheTasksOriginalVersion() {
        ApplicationCenter.Detail app =
                approval.business.applications.get(approval.business.resource.applicationId());
        Set<String> fields = Set.of(approval.business.nameField);
        ApplicationAuthorization.ObjectGrant grant =
                new ApplicationAuthorization.ObjectGrant(
                        approval.business.object.objectId(),
                        Set.of("READ", "CREATE"),
                        "ALL",
                        fields,
                        fields,
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of());
        TaskEntries.Config entry =
                new TaskEntries.Config(
                        approval.business.object.objectId(),
                        null,
                        approval.business.resource.resourceId(),
                        "FORM",
                        "审批测试",
                        null,
                        null,
                        1,
                        List.of(grant));
        List<ApplicationCenter.Resource> resources = new ArrayList<>(app.draft().resources());
        resources.add(
                new ApplicationCenter.Resource(
                        "approval-entry",
                        "TASK_ENTRY",
                        "approval_entry",
                        "审批入口",
                        mapper.convertValue(
                                entry,
                                new com.fasterxml.jackson.core.type.TypeReference<
                                        Map<String, Object>>() {})));
        ApplicationCenter.Detail updated =
                LegacyTaskEntryFixtures.seed(
                        approval.business.applications,
                        new ApplicationCenter.Save(
                                app.application().id(),
                                app.application().revision(),
                                app.application().code(),
                                app.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(app.draft().objects(), resources)),
                        ACTOR);
        approval.business.applications.publish(
                new ApplicationCenter.Revision(
                        app.application().id(), updated.application().revision(), "追加审批入口"),
                ACTOR);
        com.lingan.ucp.nocode.application.service.task.TaskEntryPolicyService policies =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.application.service.task.TaskEntryPolicyService
                                .class);
        TaskEntries.Policy policy = policies.get(app.application().id(), "approval-entry");
        policies.save(
                new TaskEntries.SavePolicy(
                        app.application().id(),
                        "approval-entry",
                        policy.revision(),
                        true,
                        List.of()),
                ACTOR);
        NodeInput node =
                new NodeInput(
                        null,
                        null,
                        "固定入口审批任务",
                        null,
                        ACTOR,
                        null,
                        null,
                        null,
                        List.of(),
                        new Binding(
                                app.application().id(),
                                approval.business.resource.resourceId(),
                                "approval-entry"),
                        null);
        Detail created =
                tasks.create(new Create(node, null, null, null, null, null, null, key()), ACTOR);
        taskId = created.task().id();
        roots.add(taskId);
        tasks.transition(
                new Transition(taskId, created.task().revision(), Action.START, null, key()),
                ACTOR);
        lifecycle();
    }

    private void lifecycle() {
        FormContext first = save("原始材料", key());
        assertThat(first.handling().outcome()).isEqualTo("SUBMITTED");
        BusinessHandling.Request old = first.handling().request();
        assertThatThrownBy(() -> save("审批期间再提交", key())).hasMessageContaining("尚未生效");
        approval.event(old, BpmProcessInstanceStatus.REJECTED);
        ApplicationCenter.Detail app =
                approval.business.applications.get(approval.business.resource.applicationId());
        approval.business.applications.publish(
                new ApplicationCenter.Revision(
                        app.application().id(), app.application().revision(), "重提期间重新发布"),
                ACTOR);
        assertThatThrownBy(() -> approval.service.reopen(old.id(), ACTOR))
                .hasMessageContaining("原任务");
        com.lingan.ucp.nocode.runtime.service.taskcenter.TaskFormRuntimeService forms =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.runtime.service.taskcenter.TaskFormRuntimeService
                                .class);
        assertThat(forms.handlingTask(old.id(), ACTOR)).isEqualTo(taskId);
        assertThat(forms.handlingTask(old.id(), 20002L)).isNull();
        FormContext reopened = tasks.form(taskId, ACTOR);
        assertThat(reopened.handling().outcome()).isEqualTo("REJECTED");
        assertThat(reopened.record().record().id()).isNull();
        assertThat(reopened.record().record().values())
                .containsEntry(approval.business.nameField, "原始材料");
        String retryKey = key();
        SaveBusiness command = command("修正后材料", retryKey);
        FormContext second = tasks.saveBusiness(command, ACTOR);
        assertThat(second.handling().request().id()).isNotEqualTo(old.id());
        assertThat(tasks.saveBusiness(command, ACTOR).handling().request().id())
                .isEqualTo(second.handling().request().id());
        approval.event(second.handling().request(), BpmProcessInstanceStatus.CANCELED);
        FormContext withdrawn = tasks.form(taskId, ACTOR);
        assertThat(withdrawn.handling().outcome()).isEqualTo("CANCELED");
        assertThat(withdrawn.record().record().values())
                .containsEntry(approval.business.nameField, "修正后材料");
        FormContext third = save("最终材料", key());
        approval.event(third.handling().request(), BpmProcessInstanceStatus.APPROVED);
        assertThat(tasks.form(taskId, ACTOR).record().record().values())
                .containsEntry(approval.business.nameField, "最终材料");
        Row row = tasks.detail(taskId, ACTOR).task();
        assertThat(
                        tasks.transition(
                                        new Transition(
                                                taskId,
                                                row.revision(),
                                                Action.COMPLETE,
                                                "审批通过后完成",
                                                key()),
                                        ACTOR)
                                .task()
                                .status())
                .isEqualTo("COMPLETED");
        assertThat(approval.service.detail(old.id(), null, ACTOR).material().values())
                .containsEntry(approval.business.nameField, "原始材料");
        assertThat(approval.business.recordCount()).isEqualTo(1);
    }

    @Test
    void failedResubmissionKeepsOriginalRequestAndCannotCompleteRejectedMaterial() {
        FormContext first = save("待修正材料", key());
        approval.event(first.handling().request(), BpmProcessInstanceStatus.REJECTED);
        assertThatThrownBy(() -> save("", key())).isInstanceOf(RuntimeException.class);
        assertThat(tasks.form(taskId, ACTOR).binding().requestId())
                .isEqualTo(first.binding().requestId());
        Row row = tasks.detail(taskId, ACTOR).task();
        assertThatThrownBy(
                        () ->
                                tasks.transition(
                                        new Transition(
                                                taskId,
                                                row.revision(),
                                                Action.COMPLETE,
                                                "不能完成",
                                                key()),
                                        ACTOR))
                .hasMessageContaining("审批");
        assertThat(approval.business.recordCount()).isZero();
    }

    private SaveBusiness command(String value, String requestKey) {
        ApplicationRecords.Save input =
                new ApplicationRecords.Save(
                        approval.business.resource.applicationId(),
                        approval.business.object.objectId(),
                        null,
                        null,
                        Map.of(approval.business.nameField, value),
                        Map.of(),
                        Map.of(),
                        null,
                        approval.business.resource.resourceId(),
                        requestKey,
                        null);
        return new SaveBusiness(taskId, tasks.detail(taskId, ACTOR).task().revision(), input);
    }

    private FormContext save(String value, String requestKey) {
        return tasks.saveBusiness(command(value, requestKey), ACTOR);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
