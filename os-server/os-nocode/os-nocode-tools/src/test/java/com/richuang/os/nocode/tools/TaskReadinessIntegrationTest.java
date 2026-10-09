package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.richuang.os.module.bpm.api.event.BpmProcessInstanceStatus;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.runtime.service.taskcenter.*;

import org.junit.jupiter.api.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 当前开发库的只读预检回归；写入只用于本类唯一夹具，预检前后核对持久化完全不变。 */
class TaskReadinessIntegrationTest {
    private static final long OWNER = 10001L;
    private static final long WORKER = 20002L;
    private WorkDraftIntegrationTest business;
    private BusinessHandlingIntegrationTest approval;
    private TaskCenterService tasks;
    private TaskWorkEntryService entries;
    private final List<String> roots = new ArrayList<>();

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        business = new WorkDraftIntegrationTest();
        business.setup();
        tasks = servicesContext.getBean(TaskCenterService.class);
        entries = servicesContext.getBean(TaskWorkEntryService.class);
        when(servicesContext.getBean(AdminUserApi.class).getUser(anyLong()))
                .thenAnswer(
                        invocation -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(invocation.getArgument(0));
                            user.setNickname("预检测试成员");
                            user.setStatus(0);
                            return user;
                        });
    }

    @AfterEach
    void cleanup() {
        for (String root : roots) {
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_record WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_binding WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update("DELETE FROM public.nocode_task_event WHERE root_id=?", root);
            jdbc.update("DELETE FROM public.nocode_task_instance WHERE root_id=?", root);
        }
        if (approval == null) business.cleanup();
        else approval.cleanup();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private Binding binding() {
        return new Binding(business.resource.applicationId(), business.resource.resourceId(), null);
    }

    private TaskWorkEntries.Config feedback() {
        return new TaskWorkEntries.Config(
                "feedback",
                "施工复核反馈",
                binding(),
                TaskWorkEntries.DataMode.ROOT_SHARED,
                null,
                null,
                null,
                null,
                true,
                false);
    }

    private NodeInput node(String id, Long actor, List<String> predecessors) {
        return new NodeInput(
                id,
                null,
                "预检节点-" + key(),
                null,
                actor,
                null,
                null,
                new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                predecessors,
                null,
                null,
                null,
                actor == null ? AssignmentMode.OPEN : AssignmentMode.ASSIGNED,
                List.of());
    }

    private String create(NodeInput root, List<NodeInput> children) {
        Detail result =
                tasks.create(
                        new Create(root, null, null, null, null, null, null, key(), children),
                        OWNER);
        roots.add(result.task().id());
        return result.task().id();
    }

    private String groupedRoot() {
        NodeInput root =
                new NodeInput(
                        null,
                        null,
                        "总任务统一数据预检",
                        null,
                        OWNER,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        binding(),
                        null,
                        List.of(feedback()),
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP));
        String id = create(root, null);
        action(id, OWNER, Action.START);
        return id;
    }

    private String child(String root, long actor) {
        return tasks.create(
                        new Create(
                                node(null, actor, List.of()),
                                root,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        OWNER)
                .task()
                .id();
    }

    private Detail action(String id, long actor, Action action) {
        return tasks.transition(
                new Transition(id, tasks.detail(id, actor).task().revision(), action, null, key()),
                actor);
    }

    private ApplicationRecords.Save input(String value) {
        return new ApplicationRecords.Save(
                business.resource.applicationId(),
                business.object.objectId(),
                null,
                null,
                Map.of(business.nameField, value),
                Map.of(),
                Map.of(),
                null,
                business.resource.resourceId(),
                key(),
                null);
    }

    private TaskWorkEntries.Saved save(String task, long actor, String value) {
        return entries.save(new TaskWorkEntries.Save(task, "feedback", null, input(value)), actor);
    }

    private Map<String, Object> persisted(String root) {
        return Map.of(
                "tasks",
                jdbc.queryForList(
                        "SELECT * FROM public.nocode_task_instance WHERE root_id=? ORDER BY id",
                        root),
                "entries",
                jdbc.queryForList(
                        "SELECT * FROM public.nocode_task_entry_binding WHERE task_id IN (SELECT id"
                                + " FROM public.nocode_task_instance WHERE root_id=?) ORDER BY id",
                        root),
                "records",
                jdbc.queryForList(
                        "SELECT * FROM public.nocode_task_entry_record WHERE task_id IN (SELECT id"
                                + " FROM public.nocode_task_instance WHERE root_id=?) ORDER BY id",
                        root),
                "events",
                jdbc.queryForList(
                        "SELECT * FROM public.nocode_task_event WHERE root_id=? ORDER BY id",
                        root));
    }

    private TaskGuidance.Readiness inspect(String root, String task, long actor) {
        Map<String, Object> before = persisted(root);
        TaskGuidance.Readiness readiness = tasks.readiness(task, actor);
        assertThat(persisted(root)).isEqualTo(before);
        return readiness;
    }

    @Test
    void stateChecksAreReadOnlyAndTerminalTasksCannotCompleteOrCancel() {
        String root = create(node(null, OWNER, List.of()), null);
        TaskGuidance.Readiness pending = inspect(root, root, OWNER);
        assertThat(pending.canComplete()).isFalse();
        assertThat(pending.canCancel()).isTrue();
        assertThat(pending.checks())
                .filteredOn(c -> c.code() == TaskGuidance.CheckCode.STATE)
                .allSatisfy(c -> assertThat(c.reason()).contains("开始"));
        action(root, OWNER, Action.START);
        assertThat(inspect(root, root, OWNER).canComplete()).isTrue();
        action(root, OWNER, Action.COMPLETE);
        TaskGuidance.Readiness completed = inspect(root, root, OWNER);
        assertThat(completed.canComplete()).isFalse();
        assertThat(completed.canCancel()).isFalse();
        assertThat(completed.cancelBlockedReason()).contains("已经结束");
        String cancelled = create(node(null, OWNER, List.of()), null);
        action(cancelled, OWNER, Action.CANCEL);
        assertThat(inspect(cancelled, cancelled, OWNER).canCancel()).isFalse();
    }

    @Test
    void claimableSummaryAndDisabledAccountsDoNotGainReadinessDetails() {
        String root = create(node(null, null, List.of()), null);
        // 可领取目录中的摘要不等于任务办理身份，详情不开放执行操作或业务配置。
        Detail summary = tasks.detail(root, WORKER);
        assertThat(summary.task().canClaim()).isFalse();
        assertThat(summary.task().binding()).isNull();
        assertThatThrownBy(() -> tasks.readiness(root, WORKER)).hasMessageContaining("权限");
        AdminUserRespDTO disabled = new AdminUserRespDTO();
        disabled.setId(OWNER);
        disabled.setStatus(1);
        when(servicesContext.getBean(AdminUserApi.class).getUser(OWNER)).thenReturn(disabled);
        assertThatThrownBy(() -> tasks.readiness(root, OWNER)).hasMessageContaining("停用");
    }

    @Test
    void unfinishedChildrenBlockParentAndCancellationImpactsHidePrivateNodes() {
        String a = key(), b = key(), c = key();
        String root =
                create(
                        node(null, OWNER, List.of()),
                        List.of(
                                node(a, WORKER, List.of()),
                                node(b, 30003L, List.of(a)),
                                node(c, WORKER, List.of(b))));
        List<Row> nodes = tasks.detail(root, OWNER).nodes();
        // 创建时节点 ID 会映射为实例 ID，用前置图定位三个真实节点。
        Row first =
                nodes.stream()
                        .filter(n -> n.parentId() != null && n.predecessorIds().isEmpty())
                        .findFirst()
                        .orElseThrow();
        Row second =
                nodes.stream()
                        .filter(n -> n.predecessorIds().contains(first.id()))
                        .findFirst()
                        .orElseThrow();
        Row third =
                nodes.stream()
                        .filter(n -> n.predecessorIds().contains(second.id()))
                        .findFirst()
                        .orElseThrow();
        action(root, OWNER, Action.START);
        action(first.id(), WORKER, Action.START);
        TaskGuidance.Readiness parent = inspect(root, root, OWNER);
        assertThat(parent.canCancel()).isFalse();
        assertThat(parent.cancelBlockedReason()).contains("子任务");
        TaskGuidance.Readiness ownerView = inspect(root, first.id(), OWNER);
        assertThat(ownerView.canComplete()).isFalse();
        assertThat(ownerView.canCancel()).isTrue();
        assertThat(ownerView.cancellationImpacts())
                .extracting(TaskGuidance.CancellationImpact::taskId)
                .containsExactlyInAnyOrder(second.id(), third.id());
        TaskGuidance.Readiness workerView = inspect(root, first.id(), WORKER);
        assertThat(workerView.cancellationImpacts())
                .extracting(TaskGuidance.CancellationImpact::taskId)
                .containsExactly(third.id());
        assertThat(workerView.cancellationImpacts().getFirst().direct()).isFalse();
        assertThatThrownBy(() -> action(root, OWNER, Action.CANCEL)).hasMessageContaining("子任务");
    }

    @Test
    void groupChecksUseSharedEffectiveRecordsWithoutPersonalApplicationMembership() {
        String root = groupedRoot(), task = child(root, WORKER);
        action(task, WORKER, Action.START);
        assertThat(inspect(root, task, WORKER).canComplete()).isFalse();
        ApplicationRecords.Row valid =
                save(root, OWNER, "兄弟节点可共同使用的有效反馈").handling().result().record();
        ApplicationRecords.Row deleted = save(task, WORKER, "已删除反馈").handling().result().record();
        entries.delete(
                new TaskWorkEntries.Delete(
                        task, "feedback", deleted.id(), deleted.revision(), key()),
                WORKER);
        assertThatThrownBy(
                        () ->
                                business.records.get(
                                        business.resource.applicationId(),
                                        business.object.objectId(),
                                        valid.id(),
                                        WORKER))
                .hasMessageContaining("权限");
        assertThat(inspect(root, task, WORKER).canComplete()).isTrue();
        business.records.delete(
                new ApplicationRecords.Delete(
                        business.resource.applicationId(),
                        business.object.objectId(),
                        valid.id(),
                        valid.revision()),
                OWNER);
        TaskGuidance.Readiness missing = inspect(root, task, WORKER);
        assertThat(missing.canComplete()).isFalse();
        assertThat(missing.checks())
                .filteredOn(c -> c.code() == TaskGuidance.CheckCode.FEEDBACK)
                .allSatisfy(c -> assertThat(c.reason()).contains("有效业务记录"));
    }

    @Test
    void revokedGroupGrantIsAFailedCheckNotAnEmptyOptionalResourceList() {
        String root = groupedRoot(), task = child(root, WORKER);
        action(task, WORKER, Action.START);
        save(task, WORKER, "撤权前反馈");
        assertThat(inspect(root, task, WORKER).canComplete()).isTrue();
        AdminUserRespDTO disabledGrantor = new AdminUserRespDTO();
        disabledGrantor.setId(OWNER);
        disabledGrantor.setStatus(1);
        when(servicesContext.getBean(AdminUserApi.class).getUser(OWNER))
                .thenReturn(disabledGrantor);
        assertThat(entries.entries(task, WORKER))
                .isNotEmpty()
                .allSatisfy(
                        entry -> {
                            assertThat(entry.unavailableReason()).contains("业务授权已失效");
                            assertThat(entry.canWrite()).isFalse();
                            assertThat(entry.workSummary()).isNull();
                        });
        TaskGuidance.Readiness denied = inspect(root, task, WORKER);
        assertThat(denied.canComplete()).isFalse();
        assertThat(denied.checks())
                .filteredOn(c -> c.code() == TaskGuidance.CheckCode.FEEDBACK)
                .allSatisfy(
                        c -> {
                            assertThat(c.passed()).isFalse();
                            assertThat(c.entryKey()).isNull();
                            assertThat(c.reason()).contains("不可访问");
                        });
    }

    @Test
    void legacyBusinessStillRequiresSavedReadableRecord() {
        NodeInput legacy =
                new NodeInput(
                        null, null, "旧单业务预检", null, OWNER, null, null, null, List.of(), binding(),
                        null);
        String root = create(legacy, null);
        action(root, OWNER, Action.START);
        assertThat(inspect(root, root, OWNER).canComplete()).isFalse();
        tasks.saveBusiness(
                new SaveBusiness(root, tasks.detail(root, OWNER).task().revision(), input("已保存业务")),
                OWNER);
        assertThat(inspect(root, root, OWNER).canComplete()).isTrue();
    }

    @Test
    void feedbackOnlyProjectProjectionCannotRollBackStartOrCompletion() {
        ApplicationRecords.Row project = business.records.save(input("仅归属的项目资料"), OWNER).record();
        String task = projectTask(project, false);
        assertThat(tasks.detail(task, WORKER).task().project()).isNull();
        Detail started = action(task, WORKER, Action.START);
        assertThat(started.task().status()).isEqualTo("RUNNING");
        assertThat(started.task().project()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT actual_start IS NOT NULL FROM public.nocode_task_instance"
                                        + " WHERE id=? AND status='RUNNING'",
                                Boolean.class,
                                task))
                .isTrue();
        assertThat(inspect(task, task, WORKER).canComplete()).isFalse();
        ApplicationRecords.Row feedback =
                save(task, WORKER, "执行人的有效反馈").handling().result().record();
        assertThat(
                        entries.page(
                                        new TaskWorkEntries.Query(
                                                task, "feedback", false, false, 1, 20, null),
                                        WORKER)
                                .getList())
                .extracting(item -> item.record().id())
                .containsExactly(feedback.id());
        assertThatThrownBy(
                        () ->
                                business.records.get(
                                        business.resource.applicationId(),
                                        business.object.objectId(),
                                        project.id(),
                                        WORKER))
                .hasMessageContaining("权限");
        assertThat(inspect(task, task, WORKER).canComplete()).isTrue();
        Detail completed = action(task, WORKER, Action.COMPLETE);
        assertThat(completed.task().status()).isEqualTo("COMPLETED");
        assertThat(completed.task().project()).isNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT actual_end IS NOT NULL FROM public.nocode_task_instance"
                                        + " WHERE id=? AND status='COMPLETED'",
                                Boolean.class,
                                task))
                .isTrue();
    }

    @Test
    void optionalProjectProbeMustNotClearAnEarlierBusinessRollback() {
        ApplicationRecords.Row project = business.records.save(input("不允许越权的项目"), OWNER).record();
        String task = projectTask(project, false);
        String title = tasks.detail(task, OWNER).task().title();
        TransactionTemplate transaction =
                new TransactionTemplate(servicesContext.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(
                        () ->
                                transaction.executeWithoutResult(
                                        status -> {
                                            jdbc.update(
                                                    "UPDATE public.nocode_task_instance SET title=?"
                                                            + " WHERE id=?",
                                                    "此标题必须回滚",
                                                    task);
                                            assertThatThrownBy(
                                                            () ->
                                                                    business.records.get(
                                                                            business.resource
                                                                                    .applicationId(),
                                                                            business.object
                                                                                    .objectId(),
                                                                            project.id(),
                                                                            WORKER))
                                                    .hasMessageContaining("权限");
                                            tasks.detail(task, WORKER);
                                        }))
                .isInstanceOf(UnexpectedRollbackException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT title FROM public.nocode_task_instance WHERE id=?",
                                String.class,
                                task))
                .isEqualTo(title);
    }

    @Test
    void explicitlyAuthorizedProjectRemainsVisibleDuringCreationStartAndCompletion() {
        ApplicationRecords.Row project = business.records.save(input("本组明确关联的项目"), OWNER).record();
        String task = projectTask(project, true);
        assertThat(tasks.detail(task, WORKER).task().project().recordId()).isEqualTo(project.id());
        assertThat(action(task, WORKER, Action.START).task().project().recordId())
                .isEqualTo(project.id());
        save(task, WORKER, "已反馈");
        assertThat(inspect(task, task, WORKER).canComplete()).isTrue();
        Detail completed = action(task, WORKER, Action.COMPLETE);
        assertThat(completed.task().project().recordId()).isEqualTo(project.id());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM public.nocode_task_instance WHERE id=?",
                                String.class,
                                task))
                .isEqualTo("COMPLETED");
    }

    private String projectTask(ApplicationRecords.Row project, boolean primaryResource) {
        NodeInput root =
                new NodeInput(
                        null,
                        null,
                        "项目上下文不代替数据授权",
                        null,
                        WORKER,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        primaryResource ? binding() : null,
                        null,
                        List.of(feedback()),
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP));
        RecordRef context =
                new RecordRef(
                        business.resource.applicationId(),
                        business.object.objectId(),
                        project.id(),
                        "项目资料");
        Detail created =
                tasks.create(new Create(root, null, null, null, context, null, null, key()), OWNER);
        roots.add(created.task().id());
        assertThat(created.task().project().recordId()).isEqualTo(project.id());
        return created.task().id();
    }

    @Test
    void legacyPendingApplicationBlocksCompletionUntilEffectiveWithoutMutatingRequest() {
        business.cleanup();
        approval = new BusinessHandlingIntegrationTest();
        approval.setup();
        approval.policy("APPROVAL", "APPROVAL");
        business = approval.business;
        NodeInput legacy =
                new NodeInput(
                        null,
                        null,
                        "旧反馈审批预检",
                        null,
                        OWNER,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        List.of(feedback()));
        String root = create(legacy, null);
        action(root, OWNER, Action.START);
        TaskWorkEntries.Saved pending = save(root, OWNER, "待生效反馈");
        List<Map<String, Object>> before =
                jdbc.queryForList(
                        "SELECT * FROM public.nocode_handling_request WHERE id=CAST(? AS uuid)",
                        pending.handling().request().id());
        TaskGuidance.Readiness readiness = inspect(root, root, OWNER);
        assertThat(readiness.canComplete()).isFalse();
        assertThat(readiness.checks())
                .filteredOn(c -> c.code() == TaskGuidance.CheckCode.FEEDBACK)
                .allSatisfy(c -> assertThat(c.reason()).contains("未生效申请"));
        assertThat(
                        jdbc.queryForList(
                                "SELECT * FROM public.nocode_handling_request WHERE id=CAST(? AS"
                                        + " uuid)",
                                pending.handling().request().id()))
                .isEqualTo(before);
        approval.event(pending.handling().request(), BpmProcessInstanceStatus.APPROVED);
        assertThat(inspect(root, root, OWNER).canComplete()).isTrue();
    }
}
