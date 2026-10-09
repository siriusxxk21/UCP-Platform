package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.richuang.os.module.msg.api.IMsgSendService;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.runtime.service.taskcenter.*;

import org.junit.jupiter.api.*;

import java.time.LocalDate;
import java.util.*;

/** 当前开发库的暂停闭环：沿用真实根锁/修订/计划/业务管线，只清理本类唯一夹具。 */
class TaskPauseIntegrationTest {
    private static final long CREATOR = 10001L, WORKER = 23001L, OTHER = 23002L, REVIEWER = 23003L;
    private final String marker = "pause_" + UUID.randomUUID() + "_";
    private final Set<String> roots = new LinkedHashSet<>();
    private TaskCenterService tasks;
    private WorkDraftIntegrationTest business;

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
        tasks = servicesContext.getBean(TaskCenterService.class);
        when(servicesContext.getBean(IMsgSendService.class).send(any())).thenReturn(999L);
        when(servicesContext.getBean(AdminUserApi.class).getUser(anyLong()))
                .thenAnswer(
                        call -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(call.getArgument(0));
                            user.setNickname("暂停测试人员");
                            user.setStatus(0);
                            return user;
                        });
    }

    @AfterEach
    void cleanup() {
        for (String root : roots) {
            for (String table :
                    List.of(
                            "nocode_task_entry_record",
                            "nocode_task_entry_binding",
                            "nocode_task_plan",
                            "nocode_task_comment"))
                jdbc.update(
                        "delete from public."
                                + table
                                + " where task_id in (select id from public.nocode_task_instance"
                                + " where root_id=?)",
                        root);
            jdbc.update("delete from public.nocode_task_event where root_id=?", root);
            jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
        }
        if (business != null) business.cleanup();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private NodeInput node(String id, String parent, long owner, Long acceptor) {
        return new NodeInput(
                id,
                parent,
                marker + id,
                null,
                owner,
                null,
                null,
                new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                List.of(),
                null,
                null,
                null,
                AssignmentMode.ASSIGNED,
                List.of(),
                null,
                acceptor);
    }

    private Detail create(Long acceptor, List<NodeInput> children) {
        Detail result =
                tasks.create(
                        new Create(
                                node("root", null, WORKER, acceptor),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key(),
                                children),
                        CREATOR);
        roots.add(result.task().id());
        return result;
    }

    private String id(Detail tree, String name) {
        return tree.nodes().stream()
                .filter(n -> n.title().equals(marker + name))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private Detail action(String id, Action action, long actor) {
        return tasks.transition(
                new Transition(
                        id,
                        tasks.detail(id, actor).task().revision(),
                        action,
                        action == Action.PAUSE ? "等待外部资源" : null,
                        key()),
                actor);
    }

    private Row row(String id) {
        return tasks.detail(id, CREATOR).task();
    }

    private Query query(String status) {
        return new Query(
                "MINE",
                "ALL",
                LocalDate.now(),
                marker,
                null,
                null,
                status,
                null,
                null,
                null,
                null,
                null,
                1,
                100,
                null,
                null);
    }

    @Test
    void pauseResumePreservesExecutionDatesAndReplaysOnlyOneEvent() {
        String id = create(null, List.of()).task().id();
        Row running = action(id, Action.START, WORKER).task();
        assertThat(running.canPause()).isTrue();
        Transition pause = new Transition(id, running.revision(), Action.PAUSE, "等待材料", key());
        Row paused = tasks.transition(pause, WORKER).task();
        assertThat(paused.status()).isEqualTo("PAUSED");
        assertThat(paused.pausedByTaskId()).isEqualTo(id);
        assertThat(paused.canResume()).isTrue();
        assertThat(paused.canStart()).isFalse();
        assertThat(paused.canExecute()).isFalse();
        assertThat(paused.actualStart()).isEqualTo(running.actualStart());
        assertThat(paused.actualEnd()).isNull();
        assertThat(tasks.transition(pause, WORKER).task().revision()).isEqualTo(paused.revision());
        assertThat(tasks.detail(id, WORKER).events())
                .filteredOn(e -> "PAUSED".equals(e.type()))
                .hasSize(1);
        assertThatThrownBy(
                        () ->
                                tasks.transition(
                                        new Transition(
                                                id, running.revision(), Action.RESUME, null, key()),
                                        WORKER))
                .hasMessageContaining("其他人修改");
        Row resumed = action(id, Action.RESUME, WORKER).task();
        assertThat(resumed.status()).isEqualTo("RUNNING");
        assertThat(resumed.pausedByTaskId()).isNull();
        assertThat(resumed.actualStart()).isEqualTo(running.actualStart());
        assertThat(resumed.expectedStart()).isEqualTo(running.expectedStart());
        assertThat(resumed.expectedEnd()).isEqualTo(running.expectedEnd());
        assertThat(resumed.canResume()).isFalse();
        assertThat(action(id, Action.COMPLETE, WORKER).task().status()).isEqualTo("COMPLETED");
    }

    @Test
    void ancestorPauseBlocksWorkButPreservesIndependentPauseAndCompletedChildren() {
        Detail tree =
                create(
                        null,
                        List.of(
                                node("running", null, WORKER, null),
                                node("paused", null, WORKER, null),
                                node("pending", "running", WORKER, null),
                                node("done", null, WORKER, null)));
        action(tree.task().id(), Action.START, WORKER);
        for (String name : List.of("running", "paused", "done"))
            action(id(tree, name), Action.START, WORKER);
        action(id(tree, "paused"), Action.PAUSE, WORKER);
        action(id(tree, "done"), Action.COMPLETE, WORKER);
        action(tree.task().id(), Action.PAUSE, WORKER);
        assertThat(row(id(tree, "running")).status()).isEqualTo("RUNNING");
        assertThat(row(id(tree, "running")).pausedByTaskId()).isEqualTo(tree.task().id());
        assertThat(row(id(tree, "running")).canExecute()).isFalse();
        assertThat(row(id(tree, "pending")).canStart()).isFalse();
        assertThat(row(id(tree, "done")).status()).isEqualTo("COMPLETED");
        assertThat(row(id(tree, "done")).pausedByTaskId()).isNull();
        assertThatThrownBy(() -> action(id(tree, "pending"), Action.START, WORKER))
                .hasMessageContaining("上级任务已暂停");
        assertThatThrownBy(() -> action(id(tree, "running"), Action.COMPLETE, WORKER))
                .hasMessageContaining("上级任务已暂停");
        assertThatThrownBy(() -> action(id(tree, "paused"), Action.RESUME, WORKER))
                .hasMessageContaining("上级任务已暂停");
        assertThat(tasks.readiness(id(tree, "running"), WORKER).checks())
                .filteredOn(c -> c.code() == TaskGuidance.CheckCode.STATE)
                .allMatch(c -> !c.passed());
        action(tree.task().id(), Action.RESUME, WORKER);
        assertThat(tasks.detail(id(tree, "running"), WORKER).task().canExecute()).isTrue();
        assertThat(tasks.detail(id(tree, "pending"), WORKER).task().canStart()).isTrue();
        assertThat(row(id(tree, "paused")).status()).isEqualTo("PAUSED");
        assertThat(row(id(tree, "paused")).canResume()).isTrue();
        assertThat(row(tree.task().id()).status()).isEqualTo("RUNNING");
    }

    @Test
    void executorCannotPauseOthersButOriginalCreatorCanManage() {
        Detail tree = create(null, List.of(node("other", null, OTHER, null)));
        action(tree.task().id(), Action.START, WORKER);
        action(id(tree, "other"), Action.START, OTHER);
        assertThatThrownBy(
                        () ->
                                tasks.transition(
                                        new Transition(
                                                id(tree, "other"),
                                                row(id(tree, "other")).revision(),
                                                Action.PAUSE,
                                                null,
                                                key()),
                                        WORKER))
                .hasMessageContaining("权限");
        assertThat(action(id(tree, "other"), Action.PAUSE, CREATOR).task().status())
                .isEqualTo("PAUSED");
        assertThat(action(id(tree, "other"), Action.RESUME, CREATOR).task().status())
                .isEqualTo("RUNNING");
    }

    @Test
    void pendingAcceptanceAndTerminalStatesCannotPauseOrResume() {
        String id = create(REVIEWER, List.of()).task().id();
        assertThatThrownBy(() -> action(id, Action.PAUSE, WORKER)).hasMessageContaining("进行中");
        assertThatThrownBy(() -> action(id, Action.RESUME, WORKER)).hasMessageContaining("直接暂停");
        action(id, Action.START, WORKER);
        action(id, Action.COMPLETE, WORKER);
        assertThat(row(id).status()).isEqualTo("PENDING_ACCEPTANCE");
        assertThat(row(id).canPause()).isFalse();
        assertThatThrownBy(() -> action(id, Action.PAUSE, WORKER)).hasMessageContaining("进行中");
        action(id, Action.APPROVE, REVIEWER);
        assertThatThrownBy(() -> action(id, Action.RESUME, WORKER)).hasMessageContaining("直接暂停");
        String cancelled = create(null, List.of()).task().id();
        action(cancelled, Action.CANCEL, WORKER);
        assertThatThrownBy(() -> action(cancelled, Action.PAUSE, WORKER))
                .hasMessageContaining("进行中");
    }

    @Test
    void pausedQueriesIncludeInheritedPauseAndKeepDailyWeeklyMembership() {
        Detail tree = create(null, List.of(node("pending", null, WORKER, null)));
        String root = tree.task().id();
        action(root, Action.START, WORKER);
        action(root, Action.PAUSE, WORKER);
        assertThat(tasks.page(query("PAUSED"), WORKER).getList())
                .extracting(Row::id)
                .containsExactlyInAnyOrder(root, id(tree, "pending"));
        assertThat(tasks.page(query("PENDING"), WORKER).getList()).isEmpty();
        TaskPlanning.ChecklistContext context =
                tasks.checklistContext(
                        new TaskPlanning.ContextQuery(List.of(root), TaskPlanning.Target.SELF),
                        WORKER);
        TaskPlanning.ChecklistItem item = context.items().getFirst();
        assertThat(item.canAdd()).isTrue();
        tasks.checklist(
                new TaskPlanning.ChecklistChange(
                        List.of(root),
                        TaskPlanning.Target.SELF,
                        TaskPlanning.ChecklistAction.ADD,
                        Period.DAY,
                        context.today(),
                        null,
                        Map.of(root, item.version()),
                        key()),
                WORKER);
        assertThat(row(root).plans()).hasSize(2);
        assertThat(row(root).canPlan()).isFalse();
        assertThat(tasks.detail(root, WORKER).task().canPlan()).isTrue();
        action(root, Action.RESUME, WORKER);
        assertThat(row(root).plans()).hasSize(2);
    }

    @Test
    void pausedAncestorMakesFeedbackReadOnlyAndRejectsRealWrites() {
        business = new WorkDraftIntegrationTest();
        business.setup();
        TaskWorkEntryService entries = servicesContext.getBean(TaskWorkEntryService.class);
        Binding binding =
                new Binding(
                        business.resource.applicationId(), business.resource.resourceId(), null);
        TaskWorkEntries.Config config =
                new TaskWorkEntries.Config(
                        "feedback",
                        "反馈",
                        binding,
                        TaskWorkEntries.DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false);
        NodeInput rootNode =
                new NodeInput(
                        null,
                        null,
                        marker + "business",
                        null,
                        CREATOR,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        List.of(config));
        Detail root =
                tasks.create(
                        new Create(rootNode, null, null, null, null, null, null, key()), CREATOR);
        roots.add(root.task().id());
        Detail child =
                tasks.create(
                        new Create(
                                new NodeInput(
                                        null,
                                        null,
                                        marker + "feedback",
                                        null,
                                        CREATOR,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        null),
                                root.task().id(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        CREATOR);
        action(root.task().id(), Action.START, CREATOR);
        action(child.task().id(), Action.START, CREATOR);
        TaskWorkEntries.Save save =
                new TaskWorkEntries.Save(
                        child.task().id(),
                        "feedback",
                        null,
                        new ApplicationRecords.Save(
                                business.resource.applicationId(),
                                business.object.objectId(),
                                null,
                                null,
                                Map.of(business.nameField, "暂停后不能写入"),
                                Map.of(),
                                Map.of(),
                                null,
                                business.resource.resourceId(),
                                key(),
                                null));
        action(root.task().id(), Action.PAUSE, CREATOR);
        assertThat(entries.entries(child.task().id(), CREATOR))
                .allMatch(e -> !e.canWrite() && !e.canDelete());
        assertThatThrownBy(() -> entries.save(save, CREATOR)).hasMessageContaining("上级任务已暂停");
        assertThat(business.recordCount()).isZero();
        action(root.task().id(), Action.RESUME, CREATOR);
        entries.save(save, CREATOR);
        assertThat(business.recordCount()).isEqualTo(1);
    }
}
