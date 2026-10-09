package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.ApplicationRecords;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskWorkEntries;
import com.lingan.ucp.nocode.controller.admin.task.TaskCenterController;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskWorkEntryService;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 员工拆分走同一任务实例和执行守卫，当前开发库只清理本类登记的随机夹具。 */
class TaskEmployeeSplitIntegrationTest {
    private static final long BOSS = 10001L, WORKER = 21001L, COLLEAGUE = 21002L;
    private final String marker = "employee_split_" + UUID.randomUUID() + "_";
    private final Set<String> roots = new LinkedHashSet<>();
    private TaskCenterService tasks;
    private WorkDraftIntegrationTest businessFixture;

    @BeforeAll
    static void open() throws Exception {
        try {
            connect();
        } catch (FlywayValidateException pendingMigrations) {
            // 本专项使用已装配的开发数据源；待执行迁移由主流程按既定流程处理。
            Flyway.configure()
                    .configuration(databaseTool.flyway().getConfiguration())
                    .ignoreMigrationPatterns("*:pending", "*:future")
                    .load()
                    .validate();
        }
    }

    @AfterAll
    static void closeContext() {
        close();
    }

    @BeforeEach
    void setup() {
        tasks = servicesContext.getBean(TaskCenterService.class);
        Mockito.when(servicesContext.getBean(IMsgSendService.class).send(Mockito.any()))
                .thenReturn(999L);
        Mockito.when(servicesContext.getBean(AdminUserApi.class).getUser(Mockito.anyLong()))
                .thenAnswer(
                        call -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(call.getArgument(0));
                            user.setNickname("拆分验证" + user.getId());
                            user.setStatus(0);
                            return user;
                        });
    }

    @AfterEach
    void cleanup() {
        for (String root : roots) {
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from public.nocode_task_instance where id=?"
                                            + " and title like ?",
                                    Integer.class,
                                    root,
                                    marker + "%"))
                    .isEqualTo(1);
            jdbc.update(
                    "delete from public.nocode_task_entry_record where task_id in(select id from"
                            + " public.nocode_task_instance where root_id=?)",
                    root);
            jdbc.update(
                    "delete from public.nocode_task_entry_binding where task_id in(select id from"
                            + " public.nocode_task_instance where root_id=?)",
                    root);
            jdbc.update("delete from public.nocode_task_event where root_id=?", root);
            jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
        }
        if (businessFixture != null) businessFixture.cleanup();
    }

    @Test
    void personalSplitDoesNotRequireRootCreationPermission() throws Exception {
        assertThat(
                        TaskCenterController.class
                                .getMethod("split", JsonNode.class)
                                .getAnnotation(PreAuthorize.class)
                                .value())
                .isEqualTo("@nocodeAccess.taskQuery()");
        assertThat(
                        TaskCenterController.class
                                .getMethod("create", JsonNode.class)
                                .getAnnotation(PreAuthorize.class)
                                .value())
                .isEqualTo("@nocodeAccess.taskCreate()");
        assertThatThrownBy(() -> tasks.split(command(null, node("new", "非法总任务", null)), WORKER))
                .hasMessageContaining("要拆分的本人任务");
    }

    @Test
    void pendingAssignedTaskSplitsForWorkerAndBossSeesTheSameChild() {
        Detail root =
                create(
                        node("root", "总任务", BOSS),
                        List.of(node("a", "我的任务", WORKER), node("b", "同事任务", COLLEAGUE)));
        Row parent = row(root, "我的任务"), colleague = row(root, "同事任务");
        Detail split = tasks.split(command(parent.id(), node("new", "我的细项", null)), WORKER);

        assertThat(split.task().rootId()).isEqualTo(root.task().id());
        assertThat(split.task().parentId()).isEqualTo(parent.id());
        assertThat(split.task().assigneeId()).isEqualTo(WORKER);
        assertThat(split.task().assignmentMode()).isEqualTo(AssignmentMode.ASSIGNED);
        assertThat(split.task().status()).isEqualTo(State.PENDING.name());
        assertThat(split.task().actualStart()).isNull();
        Detail boss = tasks.detail(root.task().id(), BOSS);
        assertThat(boss.nodes()).extracting(Row::id).contains(split.task().id());
        assertThat(row(boss, "同事任务").assigneeId()).isEqualTo(colleague.assigneeId());
        assertThat(row(boss, "同事任务").revision()).isEqualTo(colleague.revision());
        assertThat(boss.task().status()).isEqualTo(State.PENDING.name());
        assertThat(
                        tasks.personalTreeChildren(
                                new PersonalTreeChildren(query(), parent.id()), WORKER))
                .extracting(child -> child.task().id())
                .contains(split.task().id());
        assertThat(split.task().canAssign()).isFalse();
    }

    @Test
    void runningTaskKeepsItsStartAndMustFinishNewChildrenBeforeCompletion() {
        Detail root = create(node("root", "总任务", BOSS), List.of(node("a", "执行项", WORKER)));
        Row parent = row(root, "执行项");
        transition(root.task().id(), Action.START, BOSS);
        LocalDateTime started = transition(parent.id(), Action.START, WORKER).task().actualStart();
        Detail split = tasks.split(command(parent.id(), node("new", "执行中细分", WORKER)), WORKER);

        assertThat(tasks.detail(parent.id(), WORKER).task().actualStart()).isEqualTo(started);
        assertThatThrownBy(() -> transition(parent.id(), Action.COMPLETE, WORKER))
                .hasMessageContaining("未完成的子任务");
        transition(split.task().id(), Action.START, WORKER);
        transition(split.task().id(), Action.COMPLETE, WORKER);
        assertThat(tasks.detail(parent.id(), WORKER).task().status())
                .isEqualTo(State.COMPLETED.name());
        assertThat(tasks.detail(root.task().id(), BOSS).task().status())
                .isEqualTo(State.COMPLETED.name());
    }

    @Test
    void splittingBlockedStepDoesNotBypassPredecessorOrParentStart() {
        NodeInput first = node("a", "A", WORKER);
        NodeInput next = withPredecessors(node("b", "B", WORKER), List.of("a"));
        Detail root = create(node("root", "总任务", BOSS), List.of(first, next));
        Row a = row(root, "A"), b = row(root, "B");
        transition(root.task().id(), Action.START, BOSS);
        Detail split = tasks.split(command(b.id(), node("new", "B内细项", null)), WORKER);

        assertThatThrownBy(() -> transition(b.id(), Action.START, WORKER))
                .hasMessageContaining("前置");
        assertThatThrownBy(() -> transition(split.task().id(), Action.START, WORKER))
                .hasMessageContaining("上级任务");
        transition(a.id(), Action.START, WORKER);
        transition(a.id(), Action.COMPLETE, WORKER);
        assertThatThrownBy(() -> transition(split.task().id(), Action.START, WORKER))
                .hasMessageContaining("上级任务");
        transition(b.id(), Action.START, WORKER);
        assertThat(transition(split.task().id(), Action.START, WORKER).task().status())
                .isEqualTo(State.RUNNING.name());
    }

    @Test
    void cannotSplitColleagueOrManageWholeGroupThroughPersonalEndpoint() {
        Detail root =
                create(
                        node("root", "总任务", BOSS),
                        List.of(node("a", "我的任务", WORKER), node("b", "同事任务", COLLEAGUE)));
        assertThatThrownBy(
                        () ->
                                tasks.split(
                                        command(row(root, "同事任务").id(), node("x", "越权", null)),
                                        WORKER))
                .hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                tasks.split(
                                        command(root.task().id(), node("x", "越权总任务", null)),
                                        WORKER))
                .hasMessageContaining("权限");
        // 即使是总任务创建者，这个入口也仅用于本人细分；管理者仍使用原管理入口。
        assertThatThrownBy(
                        () ->
                                tasks.split(
                                        command(row(root, "我的任务").id(), node("x", "管理者误用", null)),
                                        BOSS))
                .hasMessageContaining("本人负责");
        assertThat(tasks.detail(root.task().id(), BOSS).nodes()).hasSize(3);
    }

    @Test
    void endedAndPendingAcceptanceTasksCannotGrowNewChildren() {
        for (Action action : List.of(Action.COMPLETE, Action.CANCEL)) {
            Detail root = create(node("root", "结束" + action, WORKER), List.of());
            transition(root.task().id(), Action.START, WORKER);
            transition(root.task().id(), action, WORKER);
            assertThatThrownBy(
                            () ->
                                    tasks.split(
                                            command(root.task().id(), node("new", "非法追加", null)),
                                            WORKER))
                    .hasMessageContaining("不能拆分");
        }
        NodeInput rootNode = node("root", "待验收", WORKER);
        NodeInput accepted =
                new NodeInput(
                        rootNode.id(),
                        null,
                        rootNode.title(),
                        null,
                        WORKER,
                        null,
                        null,
                        rootNode.schedule(),
                        List.of(),
                        null,
                        null,
                        null,
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        null,
                        BOSS);
        Detail root = create(accepted, List.of());
        transition(root.task().id(), Action.START, WORKER);
        assertThat(transition(root.task().id(), Action.COMPLETE, WORKER).task().status())
                .isEqualTo(State.PENDING_ACCEPTANCE.name());
        assertThatThrownBy(
                        () ->
                                tasks.split(
                                        command(root.task().id(), node("new", "非法追加", null)),
                                        WORKER))
                .hasMessageContaining("拆分");
    }

    @Test
    void retryIsIdempotentAndCannotReuseKeyWithDifferentChild() {
        Detail root = create(node("root", "总任务", WORKER), List.of());
        Create command = command(root.task().id(), node("new", "细项", null));
        String id = tasks.split(command, WORKER).task().id();
        assertThat(tasks.split(command, WORKER).task().id()).isEqualTo(id);
        assertThat(tasks.detail(root.task().id(), BOSS).nodes()).hasSize(2);
        Create changed =
                new Create(
                        node("new", "不同细项", null),
                        root.task().id(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        command.requestKey());
        assertThatThrownBy(() -> tasks.split(changed, WORKER)).hasMessageContaining("不同内容");
    }

    @Test
    void splitCannotAssignOthersChangeDependenciesOrBroadenDataAuthorization() {
        Detail root = create(node("root", "总任务", WORKER), List.of());
        NodeInput child = node("new", "细项", null);
        assertThatThrownBy(
                        () ->
                                tasks.split(
                                        command(root.task().id(), node("new", "派给同事", COLLEAGUE)),
                                        WORKER))
                .hasMessageContaining("本人负责");
        assertThatThrownBy(
                        () ->
                                tasks.split(
                                        command(
                                                root.task().id(),
                                                withPredecessors(child, List.of(root.task().id()))),
                                        WORKER))
                .hasMessageContaining("依赖或数据资源");
        NodeInput injected =
                new NodeInput(
                        child.id(),
                        null,
                        child.title(),
                        null,
                        WORKER,
                        null,
                        null,
                        child.schedule(),
                        List.of(),
                        new Binding("other-app", "form", null),
                        null,
                        null,
                        AssignmentMode.ASSIGNED,
                        List.of());
        assertThatThrownBy(() -> tasks.split(command(root.task().id(), injected), WORKER))
                .hasMessageContaining("依赖或数据资源");
        NodeInput policy =
                withPolicy(child, new DataPolicy(1, DataAccessMode.ALL, DataAccessMode.ALL));
        assertThatThrownBy(() -> tasks.split(command(root.task().id(), policy), WORKER))
                .hasMessageContaining("依赖或数据资源");
        Create graph =
                new Create(
                        child,
                        root.task().id(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        key(),
                        List.of(child));
        assertThatThrownBy(() -> tasks.split(graph, WORKER)).hasMessageContaining("不能另行发起");
        Create template =
                new Create(child, root.task().id(), "template", 1, null, null, null, key());
        assertThatThrownBy(() -> tasks.split(template, WORKER)).hasMessageContaining("不能另行发起");
        assertThat(tasks.detail(root.task().id(), BOSS).nodes()).hasSize(1);
    }

    @Test
    void unifiedRootPolicyRemainsTheOnlyAuthorityAfterWorkerSplit() {
        DataPolicy policy = new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP);
        Detail root =
                create(
                        withPolicy(node("root", "授权总任务", BOSS), policy),
                        List.of(node("a", "授权执行项", WORKER)));
        Detail split =
                tasks.split(command(row(root, "授权执行项").id(), node("new", "授权细分", null)), WORKER);

        assertThat(split.task().rootId()).isEqualTo(root.task().id());
        assertThat(tasks.detail(root.task().id(), BOSS).task().dataPolicy()).isEqualTo(policy);
        assertThat(
                        jdbc.queryForObject(
                                "select authorization_json from public.nocode_task_instance where"
                                        + " id=?",
                                String.class,
                                split.task().id()))
                .isNull();
        assertThat(
                        jdbc.queryForObject(
                                "select config_json::jsonb->>'dataPolicy' from"
                                        + " public.nocode_task_instance where id=?",
                                String.class,
                                split.task().id()))
                .isNull();
    }

    @Test
    void splitChildInheritsPublishedFeedbackAndBossSeesItsRealContribution() {
        // 应用发布依赖其他工作的 V062～V065；任务专项不能为测试顺带升级开发数据库。
        Assumptions.assumeTrue(
                Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "select to_regclass('public.nocode_date_trigger_state') is not null"
                                        + " and to_regclass('public.nocode_linkage_trigger') is not"
                                        + " null",
                                Boolean.class)),
                "当前开发库未安装应用发布所需迁移，反馈联动专项待数据库升级后执行");
        businessFixture = new WorkDraftIntegrationTest();
        businessFixture.setup();
        TaskWorkEntryService entries = servicesContext.getBean(TaskWorkEntryService.class);
        TaskWorkEntries.Config feedback =
                new TaskWorkEntries.Config(
                        "work",
                        "拆分反馈",
                        new Binding(
                                businessFixture.resource.applicationId(),
                                businessFixture.resource.resourceId(),
                                null),
                        TaskWorkEntries.DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false);
        NodeInput original = node("root", "业务总任务", BOSS);
        NodeInput configured =
                new NodeInput(
                        original.id(),
                        null,
                        original.title(),
                        null,
                        BOSS,
                        original.urgency(),
                        original.priority(),
                        original.schedule(),
                        List.of(),
                        null,
                        null,
                        List.of(feedback),
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP),
                        null);
        Detail root = create(configured, List.of(node("a", "业务执行项", WORKER)));
        Row parent = row(root, "业务执行项");
        transition(root.task().id(), Action.START, BOSS);
        transition(parent.id(), Action.START, WORKER);
        Detail split = tasks.split(command(parent.id(), node("new", "业务细分", null)), WORKER);
        transition(split.task().id(), Action.START, WORKER);
        entries.save(
                new TaskWorkEntries.Save(
                        split.task().id(),
                        "work",
                        null,
                        new ApplicationRecords.Save(
                                businessFixture.resource.applicationId(),
                                businessFixture.object.objectId(),
                                null,
                                null,
                                Map.of(businessFixture.nameField, marker + "员工细分提交"),
                                Map.of(),
                                Map.of(),
                                null,
                                businessFixture.resource.resourceId(),
                                key(),
                                null)),
                WORKER);

        assertThat(
                        entries.page(
                                        new TaskWorkEntries.Query(
                                                root.task().id(),
                                                "work",
                                                false,
                                                false,
                                                1,
                                                20,
                                                null),
                                        BOSS)
                                .getList())
                .singleElement()
                .satisfies(
                        item -> {
                            assertThat(item.record().values())
                                    .containsEntry(businessFixture.nameField, marker + "员工细分提交");
                            assertThat(item.sources())
                                    .extracting(TaskWorkEntries.Source::taskId)
                                    .contains(split.task().id());
                        });
        assertThatThrownBy(
                        () ->
                                entries.page(
                                        new TaskWorkEntries.Query(
                                                split.task().id(),
                                                "work",
                                                false,
                                                false,
                                                1,
                                                20,
                                                null),
                                        COLLEAGUE))
                .hasMessageContaining("任务业务数据");
    }

    private Detail create(NodeInput root, List<NodeInput> children) {
        Detail detail =
                tasks.create(
                        new Create(root, null, null, null, null, null, null, key(), children),
                        BOSS);
        roots.add(detail.task().id());
        return detail;
    }

    private Create command(String parent, NodeInput child) {
        return new Create(child, parent, null, null, null, null, null, key());
    }

    private NodeInput node(String id, String title, Long assignee) {
        return new NodeInput(
                id,
                null,
                marker + title,
                null,
                assignee,
                Urgency.NORMAL,
                Priority.MEDIUM,
                new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                List.of(),
                null,
                null,
                null,
                assignee == null ? null : AssignmentMode.ASSIGNED,
                List.of());
    }

    private NodeInput withPredecessors(NodeInput node, List<String> predecessors) {
        return new NodeInput(
                node.id(),
                node.parentId(),
                node.title(),
                node.description(),
                node.assigneeId(),
                node.urgency(),
                node.priority(),
                node.schedule(),
                predecessors,
                node.binding(),
                node.sharing(),
                node.entries(),
                node.assignmentMode(),
                node.candidateUserIds(),
                node.dataPolicy(),
                node.acceptorId());
    }

    private NodeInput withPolicy(NodeInput node, DataPolicy policy) {
        return new NodeInput(
                node.id(),
                node.parentId(),
                node.title(),
                node.description(),
                node.assigneeId(),
                node.urgency(),
                node.priority(),
                node.schedule(),
                node.predecessorIds(),
                node.binding(),
                node.sharing(),
                node.entries(),
                node.assignmentMode(),
                node.candidateUserIds(),
                policy,
                node.acceptorId());
    }

    private Row row(Detail detail, String suffix) {
        return detail.nodes().stream()
                .filter(node -> node.title().equals(marker + suffix))
                .findFirst()
                .orElseThrow();
    }

    private Detail transition(String id, Action action, long actor) {
        Row current = tasks.detail(id, actor).task();
        return tasks.transition(
                new Transition(id, current.revision(), action, "专项验证", key()), actor);
    }

    private Query query() {
        return new Query(
                "MINE",
                "TODO",
                LocalDate.now(),
                marker,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                100);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
