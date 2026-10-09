package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.ApplicationRecords;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskPlanning;
import com.lingan.ucp.nocode.api.TaskWorkEntries;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskLaunchDraftMapper;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterServiceImpl;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskLaunchDraftService;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskLaunchDraftServiceImpl;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskPageQueries;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskWorkEntryService;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/** 在当前开发库验证可选验收的真实状态、权限和幂等，仅清理本类创建的实例。 */
class TaskAcceptanceIntegrationTest {
    private static final long OWNER = 31001L, WORKER = 31002L, ACCEPTOR = 31003L, OTHER = 31004L;
    private TaskCenterService tasks;
    private static TaskLaunchDraftService drafts;
    private static AnnotationConfigApplicationContext draftContext;
    private WorkDraftIntegrationTest businessFixture;
    private final Set<String> roots = new LinkedHashSet<>();
    private final Set<String> templates = new LinkedHashSet<>();
    private final Set<String> draftIds = new LinkedHashSet<>();
    private final String marker = "acceptance_" + UUID.randomUUID();

    @BeforeAll
    static void open() throws Exception {
        connect();
        draftContext = new AnnotationConfigApplicationContext();
        draftContext.setParent(servicesContext);
        draftContext.registerBean(
                TaskLaunchDraftMapper.class, () -> session.getMapper(TaskLaunchDraftMapper.class));
        draftContext.register(TaskLaunchDraftServiceImpl.class);
        draftContext.refresh();
        drafts = draftContext.getBean(TaskLaunchDraftService.class);
    }

    @AfterAll
    static void end() {
        if (draftContext != null) draftContext.close();
        close();
    }

    @BeforeEach
    void setup() {
        tasks = servicesContext.getBean(TaskCenterService.class);
        IMsgSendService messages = servicesContext.getBean(IMsgSendService.class);
        reset(messages);
        when(messages.send(any()))
                .thenAnswer(
                        invocation -> {
                            com.lingan.ucp.module.msg.api.MsgSendParam message =
                                    invocation.getArgument(0);
                            // 消息投递替身也必须使用开发库真实注册模板，不能把缺失部署配置掩盖成成功。
                            assertThat(messageTemplate(message.getMsgCode())).isNotEmpty();
                            return 999L;
                        });
        when(servicesContext.getBean(AdminUserApi.class).getUser(anyLong()))
                .thenAnswer(invocation -> user(invocation.getArgument(0), 0));
    }

    @Test
    void acceptanceMessageTemplateIsRegisteredWithTaskDetailNavigation() {
        Map<String, Object> template = messageTemplate("nocode-task-acceptance");
        assertThat(template)
                .containsEntry("subscribe_able", 0)
                .containsEntry("template_url", "/nocode-app/task-center?taskId=${taskId}");
    }

    private Map<String, Object> messageTemplate(String code) {
        String query = "select * from public.sys_msg_template where code=? and deleted=0";
        return jdbc.queryForMap(query, code);
    }

    @Test
    void unifiedTodoPaginatesExecutionAndAcceptanceTogetherWithoutMixingSubmittedTasks() {
        Set<String> expected = new LinkedHashSet<>();
        for (int index = 0; index < 2; index++)
            expected.add(create(node("own-" + index, null, ACCEPTOR, null), List.of()).task().id());
        for (int index = 0; index < 3; index++) {
            Detail waiting = create(node("review-" + index, null, WORKER, ACCEPTOR), List.of());
            transition(waiting.task().id(), Action.START, WORKER, null);
            transition(waiting.task().id(), Action.COMPLETE, WORKER, null);
            expected.add(waiting.task().id());
        }
        Detail submitted = create(node("my-submission", null, ACCEPTOR, WORKER), List.of());
        transition(submitted.task().id(), Action.START, ACCEPTOR, null);
        transition(submitted.task().id(), Action.COMPLETE, ACCEPTOR, null);
        create(node("someone-else", null, OTHER, null), List.of());
        Set<String> actual = new LinkedHashSet<>();
        for (int page = 1; page <= 3; page++) {
            com.lingan.ucp.framework.common.pojo.PageResult<Row> rows =
                    tasks.page(todo(null, page, 2), ACCEPTOR);
            assertThat(rows.getTotal()).isEqualTo(5);
            assertThat(rows.getList()).hasSize(page == 3 ? 1 : 2);
            for (Row row : rows.getList()) assertThat(actual.add(row.id())).isTrue();
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(tasks.page(query("MINE", "POOL", null), ACCEPTOR).getTotal()).isEqualTo(2);
        assertThat(tasks.page(query("MINE", "ACCEPTANCE", null), ACCEPTOR).getTotal()).isEqualTo(3);
        assertThat(tasks.page(query("MINE", "ALL", "PENDING_ACCEPTANCE"), ACCEPTOR).getList())
                .extracting(Row::id)
                .containsExactly(submitted.task().id());
        assertThat(tasks.page(todo(null, 1, 100), OWNER).getTotal()).isZero();
        for (String scope : List.of("MANAGE", "VISIBLE"))
            assertThatThrownBy(() -> tasks.page(query(scope, "TODO", null), ACCEPTOR))
                    .hasMessageContaining("个人任务视图");
        TaskCenterServiceImpl embedded = new TaskCenterServiceImpl();
        TaskPageQueries pages = mock(TaskPageQueries.class);
        ReflectionTestUtils.setField(embedded, "pageQueries", pages);
        when(pages.resolve(any(), eq(ACCEPTOR), eq(false)))
                .thenReturn(com.lingan.ucp.nocode.runtime.dal.query.TaskQueryScope.empty());
        assertThatThrownBy(
                        () ->
                                embedded.pageTasks(
                                        new PageQuery(
                                                "app", "page", "node", null, todo(null, 1, 20)),
                                        ACCEPTOR))
                .hasMessageContaining("个人任务视图");
    }

    @Test
    void unifiedTodoMovesBetweenResponsiblePersonAndAcceptorOnEveryReviewRound() {
        Detail root = create(node("round-todo", null, WORKER, ACCEPTOR), List.of());
        assertThat(tasks.page(todo(null, 1, 100), WORKER).getTotal()).isEqualTo(1);
        assertThat(tasks.page(todo(null, 1, 100), ACCEPTOR).getTotal()).isZero();
        transition(root.task().id(), Action.START, WORKER, null);
        transition(root.task().id(), Action.COMPLETE, WORKER, null);
        assertThat(tasks.page(todo(null, 1, 100), WORKER).getTotal()).isZero();
        assertThat(tasks.page(todo("PENDING_ACCEPTANCE", 1, 100), ACCEPTOR).getList())
                .extracting(Row::id)
                .containsExactly(root.task().id());
        assertThat(tasks.page(todo("RUNNING", 1, 100), ACCEPTOR).getTotal()).isZero();
        transition(root.task().id(), Action.REJECT, ACCEPTOR, "补充交付内容");
        assertThat(tasks.page(todo(null, 1, 100), WORKER).getTotal()).isEqualTo(1);
        assertThat(tasks.page(todo(null, 1, 100), ACCEPTOR).getTotal()).isZero();
        transition(root.task().id(), Action.COMPLETE, WORKER, null);
        assertThat(tasks.page(todo(null, 1, 100), ACCEPTOR).getTotal()).isEqualTo(1);
        transition(root.task().id(), Action.APPROVE, ACCEPTOR, null);
        assertThat(tasks.page(todo(null, 1, 100), WORKER).getTotal()).isZero();
        assertThat(tasks.page(todo(null, 1, 100), ACCEPTOR).getTotal()).isZero();
    }

    @Test
    void unifiedTodoTreeSelectsMatchedGroupsAndPreservesCompleteReadonlyStructure() {
        Detail own =
                create(
                        node("own-tree", null, ACCEPTOR, null),
                        List.of(
                                node("own-child", null, ACCEPTOR, null),
                                node("private-child", null, OTHER, null)));
        Row ownChild =
                own.nodes().stream()
                        .filter(n -> n.title().equals(marker + "own-child"))
                        .findFirst()
                        .orElseThrow();
        Detail review =
                create(
                        node("review-tree", null, WORKER, ACCEPTOR),
                        List.of(node("finished-child", null, ACCEPTOR, null)));
        Row finishedChild =
                review.nodes().stream().filter(n -> n.parentId() != null).findFirst().orElseThrow();
        transition(review.task().id(), Action.START, WORKER, null);
        transition(finishedChild.id(), Action.START, ACCEPTOR, null);
        transition(finishedChild.id(), Action.COMPLETE, ACCEPTOR, null);
        assertThat(tasks.detail(review.task().id(), WORKER).task().status())
                .isEqualTo("PENDING_ACCEPTANCE");
        Detail hidden =
                create(
                        node("hidden-root", null, OTHER, null),
                        List.of(node("visible-leaf", null, ACCEPTOR, null)));
        Row leaf =
                hidden.nodes().stream().filter(n -> n.parentId() != null).findFirst().orElseThrow();
        Set<String> expected = Set.of(own.task().id(), review.task().id(), hidden.task().id());
        Set<String> actual = new LinkedHashSet<>();
        for (int page = 1; page <= 3; page++) {
            com.lingan.ucp.framework.common.pojo.PageResult<PersonalTreeNode> rows =
                    tasks.personalTreePage(todo(null, page, 1), ACCEPTOR);
            assertThat(rows.getTotal()).isEqualTo(3);
            assertThat(rows.getList()).hasSize(1);
            assertThat(actual.add(rows.getList().getFirst().task().id())).isTrue();
        }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(
                        tasks.personalTreeChildren(
                                new PersonalTreeChildren(todo(null, 1, 100), own.task().id()),
                                ACCEPTOR))
                .extracting(n -> n.task().id())
                .contains(ownChild.id())
                .hasSize(2);
        assertThat(
                        tasks.personalTreeChildren(
                                new PersonalTreeChildren(todo(null, 1, 100), review.task().id()),
                                ACCEPTOR))
                .hasSize(1)
                .allMatch(PersonalTreeNode::contextOnly);
        Row projectedLeaf =
                tasks
                        .personalTreeChildren(
                                new PersonalTreeChildren(todo(null, 1, 100), hidden.task().id()),
                                ACCEPTOR)
                        .stream()
                        .map(PersonalTreeNode::task)
                        .filter(n -> n.id().equals(leaf.id()))
                        .findFirst()
                        .orElseThrow();
        assertThat(projectedLeaf.ancestorContext()).hasSize(1).allMatch(n -> !n.detailVisible());
        assertThatThrownBy(
                        () ->
                                tasks.personalTreeChildren(
                                        new PersonalTreeChildren(
                                                todo("PENDING_ACCEPTANCE", 1, 100),
                                                own.task().id()),
                                        ACCEPTOR))
                .hasMessageContaining("当前筛选");
        assertThatThrownBy(() -> tasks.detail(hidden.task().id(), ACCEPTOR))
                .hasMessageContaining("权限");
    }

    @Test
    void unifiedTodoKeepsStatusSearchDatesAndPlanFiltersAsIntersections() {
        LocalDate today = LocalDate.now();
        NodeInput dated =
                new NodeInput(
                        "dated",
                        null,
                        marker + "dated",
                        null,
                        ACCEPTOR,
                        null,
                        null,
                        new Schedule(
                                TimeMode.FIXED,
                                LocalDateTime.now().minusDays(1),
                                0,
                                0,
                                today.plusDays(1).atStartOfDay()),
                        List.of(),
                        null,
                        null,
                        null,
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        null,
                        null);
        Detail own = create(dated, List.of());
        Detail review = create(node("review-filter", null, WORKER, ACCEPTOR), List.of());
        transition(review.task().id(), Action.START, WORKER, null);
        transition(review.task().id(), Action.COMPLETE, WORKER, null);
        tasks.plan(new SavePlan(List.of(own.task().id()), Period.DAY, today, true), ACCEPTOR);
        Query filtered =
                new Query(
                        "MINE",
                        "TODO",
                        today,
                        marker,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        today,
                        today.plusDays(2),
                        1,
                        100,
                        null,
                        null,
                        null,
                        null,
                        TaskPlanning.Scope.PERSONAL,
                        null,
                        TaskPlanning.Filter.PLANNED);
        assertThat(tasks.page(filtered, ACCEPTOR).getList())
                .extracting(Row::id)
                .containsExactly(own.task().id());
        assertThat(tasks.personalTreePage(filtered, ACCEPTOR).getList())
                .extracting(n -> n.task().id())
                .containsExactly(own.task().id());
        Query search =
                new Query(
                        "MINE",
                        "TODO",
                        today,
                        marker + "review-filter",
                        null,
                        null,
                        "PENDING_ACCEPTANCE",
                        null,
                        null,
                        null,
                        null,
                        null,
                        1,
                        100);
        assertThat(tasks.page(search, ACCEPTOR).getList())
                .extracting(Row::id)
                .containsExactly(review.task().id());
    }

    private Query todo(String status, int page, int size) {
        return new Query(
                "MINE", "TODO", null, marker, null, null, status, null, null, null, null, null,
                page, size);
    }

    @AfterEach
    void cleanup() {
        for (String id : draftIds)
            jdbc.update("delete from public.nocode_task_launch_draft where id=?", id);
        for (String root : roots) {
            jdbc.update(
                    "delete from public.nocode_task_entry_record where task_id in(select id from"
                            + " public.nocode_task_instance where root_id=?)",
                    root);
            jdbc.update(
                    "delete from public.nocode_task_entry_binding where task_id in(select id from"
                            + " public.nocode_task_instance where root_id=?)",
                    root);
            jdbc.update(
                    "delete from public.nocode_task_comment where task_id in(select id from"
                            + " public.nocode_task_instance where root_id=?)",
                    root);
            jdbc.update(
                    "delete from public.nocode_task_plan where task_id in(select id from"
                            + " public.nocode_task_instance where root_id=?)",
                    root);
            jdbc.update(
                    "delete from public.nocode_task_record_link where task_id in(select id from"
                            + " public.nocode_task_instance where root_id=?)",
                    root);
            jdbc.update("delete from public.nocode_task_event where root_id=?", root);
            jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
        }
        for (String template : templates) {
            jdbc.update(
                    "delete from public.nocode_task_template_version where template_id=?",
                    template);
            jdbc.update("delete from public.nocode_task_template where id=?", template);
        }
        if (businessFixture != null) businessFixture.cleanup();
    }

    @Test
    void childMaterialsAreFrozenPerSubmissionAndRemainReadableAfterRework() {
        businessFixture = new WorkDraftIntegrationTest();
        businessFixture.setup();
        TaskWorkEntryService entries = servicesContext.getBean(TaskWorkEntryService.class);
        Binding binding =
                new Binding(
                        businessFixture.resource.applicationId(),
                        businessFixture.resource.resourceId(),
                        null);
        TaskWorkEntries.Config feedback =
                new TaskWorkEntries.Config(
                        "work",
                        "验收资料",
                        binding,
                        TaskWorkEntries.DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        true,
                        false);
        NodeInput rootInput =
                new NodeInput(
                        "root",
                        null,
                        marker + "材料总任务",
                        null,
                        10001L,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        null,
                        null,
                        List.of(feedback),
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP),
                        ACCEPTOR);
        Detail root =
                tasks.create(
                        command(rootInput, List.of(node("material-child", null, WORKER, null))),
                        10001L);
        roots.add(root.task().id());
        Row child =
                root.nodes().stream().filter(n -> n.parentId() != null).findFirst().orElseThrow();
        transition(root.task().id(), Action.START, 10001L, null);
        transition(child.id(), Action.START, WORKER, null);
        entries.save(
                new TaskWorkEntries.Save(
                        child.id(), "work", null, materialInput(null, null, "第一轮子任务资料")),
                WORKER);
        TaskWorkEntries.Item original =
                entries.page(
                                new TaskWorkEntries.Query(
                                        child.id(), "work", false, false, 1, 20, null),
                                WORKER)
                        .getList()
                        .getFirst();
        Detail childDone = transition(child.id(), Action.COMPLETE, WORKER, null);
        Event childEvent =
                childDone.events().stream()
                        .filter(e -> e.taskId().equals(child.id()) && e.type().equals("COMPLETED"))
                        .findFirst()
                        .orElseThrow();
        Detail first = tasks.detail(root.task().id(), 10001L);
        assertThat(first.task().status()).isEqualTo("PENDING_ACCEPTANCE");
        Event firstEvent =
                first.events().stream()
                        .filter(e -> e.type().equals("SUBMITTED_FOR_ACCEPTANCE"))
                        .findFirst()
                        .orElseThrow();
        CompletionMaterial firstMaterial =
                tasks.material(new MaterialRef(root.task().id(), firstEvent.id()), ACCEPTOR);
        assertThat(materialValues(firstMaterial)).containsExactly("第一轮子任务资料");
        assertThat(firstMaterial.entries().getFirst().model().writable()).isFalse();
        assertThat(firstMaterial.entries().getFirst().binding().resource().applicationId())
                .isEqualTo(binding.applicationId());
        assertThat(
                        materialValues(
                                tasks.material(
                                        new MaterialRef(child.id(), childEvent.id()), ACCEPTOR)))
                .containsExactly("第一轮子任务资料");
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        new TaskWorkEntries.Save(
                                                root.task().id(),
                                                "work",
                                                null,
                                                materialInput(
                                                        original.record().id(),
                                                        original.record().revision(),
                                                        "偷改")),
                                        10001L))
                .hasMessageContaining("进行中");
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        new TaskWorkEntries.Save(
                                                root.task().id(),
                                                "work",
                                                null,
                                                materialInput(
                                                        original.record().id(),
                                                        original.record().revision(),
                                                        "验收人偷改")),
                                        ACCEPTOR))
                .hasMessageContaining("进行中");
        transition(root.task().id(), Action.REJECT, ACCEPTOR, "补充资料");
        entries.save(
                new TaskWorkEntries.Save(
                        root.task().id(),
                        "work",
                        null,
                        materialInput(
                                original.record().id(), original.record().revision(), "第二轮已补充资料")),
                10001L);
        Detail second = transition(root.task().id(), Action.COMPLETE, 10001L, "再次提交");
        Event secondEvent =
                second.events().stream()
                        .filter(
                                e ->
                                        e.type().equals("SUBMITTED_FOR_ACCEPTANCE")
                                                && !e.id().equals(firstEvent.id()))
                        .findFirst()
                        .orElseThrow();
        // 总负责人补充与子任务原交付均保留各自办理快照；不能覆盖已经完成的子任务材料。
        assertThat(
                        materialValues(
                                tasks.material(
                                        new MaterialRef(root.task().id(), secondEvent.id()),
                                        ACCEPTOR)))
                .containsExactlyInAnyOrder("第二轮已补充资料", "第一轮子任务资料");
        assertThat(
                        materialValues(
                                tasks.material(
                                        new MaterialRef(root.task().id(), firstEvent.id()),
                                        ACCEPTOR)))
                .containsExactly("第一轮子任务资料");
        assertThat(
                        materialValues(
                                tasks.material(
                                        new MaterialRef(child.id(), childEvent.id()), ACCEPTOR)))
                .containsExactly("第一轮子任务资料");
        transition(root.task().id(), Action.APPROVE, ACCEPTOR, "资料齐全");
    }

    /**
     * R8 冒烟缺陷（2026-10-04）：任务挂了业务记录、负责人与验收人对该记录都没有查看权时，开始 / 完成 / 退回 / 验收都必须成功，
     * 详情里不出现该关联记录（不放宽权限）；此前组装详情时读关联记录的权限失败被吞掉，但已把外层事务标成 rollback-only， 命令返回
     * UnexpectedRollbackException（500），状态、事件、验收通知全部回滚。
     */
    @Test
    void transitionsSucceedWhenTaskLinksARecordTheActorCannotRead() {
        businessFixture = new WorkDraftIntegrationTest();
        businessFixture.setup();
        com.lingan.ucp.nocode.runtime.service.record.RecordService records =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.runtime.service.record.RecordService.class);
        // 记录由应用创建人 10001 建：他读得到，负责人与验收人都不是应用成员、读不到。
        String recordId =
                records.save(materialInput(null, null, "验收人看不到的记录"), 10001L).record().id();
        String app = businessFixture.resource.applicationId();
        String object = businessFixture.object.objectId();
        assertThat(records.get(app, object, recordId, 10001L).record().id()).isEqualTo(recordId);
        assertThatThrownBy(() -> records.get(app, object, recordId, ACCEPTOR))
                .isInstanceOf(com.lingan.ucp.framework.common.exception.ServiceException.class);
        assertThatThrownBy(() -> records.get(app, object, recordId, WORKER))
                .isInstanceOf(com.lingan.ucp.framework.common.exception.ServiceException.class);
        Detail root = create(node("linked-review", null, WORKER, ACCEPTOR), List.of());
        String id = root.task().id();
        jdbc.update(
                "insert into public.nocode_task_record_link(id,task_id,application_id,object_id,"
                        + "record_id,label,creator,updater) values(?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),
                id,
                app,
                object,
                recordId,
                "不可见记录",
                "10001",
                "10001");
        // 负责人开始、完成（都会在同一事务末尾组装详情），验收人退回、负责人再提交、验收人通过。
        assertThat(transition(id, Action.START, WORKER, null).links()).isEmpty();
        assertThat(transition(id, Action.COMPLETE, WORKER, null).task().status())
                .isEqualTo("PENDING_ACCEPTANCE");
        assertThat(tasks.detail(id, ACCEPTOR).links()).as("验收人看详情时关联记录不可见").isEmpty();
        Detail rejected = transition(id, Action.REJECT, ACCEPTOR, "补充资料");
        assertThat(rejected.task().status()).isEqualTo("RUNNING");
        assertThat(rejected.links()).isEmpty();
        transition(id, Action.COMPLETE, WORKER, null);
        Detail approved = transition(id, Action.APPROVE, ACCEPTOR, "通过");
        assertThat(approved.task().status()).isEqualTo("COMPLETED");
        assertThat(approved.links()).as("验收通过后关联记录仍不可见（权限没有放宽）").isEmpty();
        assertThat(approved.events()).extracting(Event::type).contains("ACCEPTED", "REJECTED");
        assertThat(
                        jdbc.queryForObject(
                                "select status from public.nocode_task_instance where id=?",
                                String.class,
                                id))
                .isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForList(
                                "select event_type from public.nocode_task_event where task_id=?"
                                        + " order by create_time, id",
                                String.class,
                                id))
                .as("每一步的事件都已提交，没有被回滚")
                .contains("STARTED", "SUBMITTED_FOR_ACCEPTANCE", "REJECTED", "ACCEPTED");
        jdbc.update("delete from public.nocode_task_record_link where task_id=?", id);
    }

    private ApplicationRecords.Save materialInput(String id, String revision, String value) {
        return new ApplicationRecords.Save(
                businessFixture.resource.applicationId(),
                businessFixture.object.objectId(),
                id,
                revision,
                Map.of(businessFixture.nameField, value),
                Map.of(),
                Map.of(),
                null,
                businessFixture.resource.resourceId(),
                key(),
                null);
    }

    private List<Object> materialValues(CompletionMaterial material) {
        return material.entries().stream()
                .flatMap(entry -> entry.records().stream())
                .map(item -> item.record().values().get(businessFixture.nameField))
                .toList();
    }

    @Test
    void optionalAcceptanceRetainsLegacyCompletionAndFinalEndTime() {
        Detail simple = create(node("direct", null, WORKER, null), List.of());
        transition(simple.task().id(), Action.START, WORKER, null);
        assertThat(transition(simple.task().id(), Action.COMPLETE, WORKER, null).task().status())
                .isEqualTo("COMPLETED");
        Detail root = create(node("review", null, WORKER, ACCEPTOR), List.of());
        transition(root.task().id(), Action.START, WORKER, null);
        Detail waiting = transition(root.task().id(), Action.COMPLETE, WORKER, "准备验收");
        assertThat(waiting.task().status()).isEqualTo("PENDING_ACCEPTANCE");
        assertThat(waiting.task().actualEnd()).isNull();
        assertThat(waiting.task().canExecute()).isFalse();
        assertThat(waiting.task().canPlan()).isFalse();
        assertThat(tasks.detail(root.task().id(), ACCEPTOR).task().canAccept()).isTrue();
        Detail done = transition(root.task().id(), Action.APPROVE, ACCEPTOR, "通过");
        assertThat(done.task().status()).isEqualTo("COMPLETED");
        assertThat(done.task().actualEnd()).isNotNull();
        assertThat(done.task().canAccept()).isFalse();
        assertThat(done.events())
                .extracting(Event::type)
                .contains("SUBMITTED_FOR_ACCEPTANCE", "ACCEPTED");
    }

    @Test
    void rejectionRequiresReasonAndPreservesEachSubmissionAndIdempotentDecision() {
        Detail root = create(node("rounds", null, WORKER, ACCEPTOR), List.of());
        transition(root.task().id(), Action.START, WORKER, null);
        Detail first = transition(root.task().id(), Action.COMPLETE, WORKER, "第一轮");
        Event firstSubmission =
                first.events().stream()
                        .filter(e -> e.type().equals("SUBMITTED_FOR_ACCEPTANCE"))
                        .findFirst()
                        .orElseThrow();
        assertThatThrownBy(() -> transition(root.task().id(), Action.REJECT, ACCEPTOR, " "))
                .hasMessageContaining("退回原因");
        Detail rejected = transition(root.task().id(), Action.REJECT, ACCEPTOR, "请补充说明");
        assertThat(rejected.task().status()).isEqualTo("RUNNING");
        assertThat(rejected.task().actualEnd()).isNull();
        Detail second = transition(root.task().id(), Action.COMPLETE, WORKER, "第二轮");
        assertThat(second.events())
                .filteredOn(e -> e.type().equals("SUBMITTED_FOR_ACCEPTANCE"))
                .hasSize(2);
        assertThat(
                        tasks.material(
                                        new MaterialRef(root.task().id(), firstSubmission.id()),
                                        ACCEPTOR)
                                .eventId())
                .isEqualTo(firstSubmission.id());
        String request = key();
        Transition decision =
                new Transition(
                        root.task().id(), second.task().revision(), Action.APPROVE, "已补齐", request);
        tasks.transition(decision, ACCEPTOR);
        Detail replay = tasks.transition(decision, ACCEPTOR);
        assertThat(replay.events()).filteredOn(e -> e.type().equals("ACCEPTED")).hasSize(1);
        assertThatThrownBy(
                        () ->
                                tasks.transition(
                                        new Transition(
                                                root.task().id(),
                                                second.task().revision(),
                                                Action.REJECT,
                                                "修改决定",
                                                request),
                                        ACCEPTOR))
                .hasMessageContaining("请求");
    }

    @Test
    void onlyNamedAcceptorCanDecideAndPersonalScopesDoNotMixRoles() {
        Detail root = create(node("scope", null, WORKER, ACCEPTOR), List.of());
        transition(root.task().id(), Action.START, WORKER, null);
        transition(root.task().id(), Action.COMPLETE, WORKER, null);
        assertThat(tasks.page(query("MINE", "ACCEPTANCE", null), ACCEPTOR).getList())
                .extracting(Row::id)
                .containsExactly(root.task().id());
        assertThat(tasks.page(query("MINE", "ALL", "PENDING_ACCEPTANCE"), ACCEPTOR).getTotal())
                .isZero();
        assertThat(tasks.page(query("MINE", "ALL", "PENDING_ACCEPTANCE"), WORKER).getList())
                .extracting(Row::id)
                .containsExactly(root.task().id());
        assertThat(tasks.page(query("MINE", "POOL", null), WORKER).getTotal()).isZero();
        assertThat(tasks.page(query("MANAGE", "ALL", "PENDING_ACCEPTANCE"), OWNER).getTotal())
                .isEqualTo(1);
        assertThatThrownBy(() -> transition(root.task().id(), Action.APPROVE, WORKER, null))
                .hasMessageContaining("指定验收人");
        when(servicesContext
                        .getBean(PermissionCommonApi.class)
                        .hasAnyPermissions(OTHER, "nocode:task:manage-all"))
                .thenReturn(true);
        assertThatThrownBy(() -> transition(root.task().id(), Action.APPROVE, OTHER, null))
                .hasMessageContaining("指定验收人");
        when(servicesContext
                        .getBean(PermissionCommonApi.class)
                        .hasAnyPermissions(OTHER, "nocode:task:manage-all"))
                .thenReturn(false);
        assertThatThrownBy(() -> tasks.detail(root.task().id(), OTHER)).hasMessageContaining("权限");
        when(servicesContext.getBean(AdminUserApi.class).getUser(ACCEPTOR))
                .thenReturn(user(ACCEPTOR, 1));
        assertThat(tasks.detail(root.task().id(), ACCEPTOR).task().canAccept()).isFalse();
        assertThatThrownBy(() -> transition(root.task().id(), Action.APPROVE, ACCEPTOR, null))
                .hasMessageContaining("指定验收人");
    }

    @Test
    void childCompletionIsDirectAndRootWaitsForEveryChild() {
        Detail root =
                create(
                        node("parent", null, WORKER, ACCEPTOR),
                        List.of(node("child", null, WORKER, null)));
        Row child =
                root.nodes().stream().filter(n -> n.parentId() != null).findFirst().orElseThrow();
        assertThat(tasks.detail(root.task().id(), ACCEPTOR).nodes()).hasSize(2);
        assertThat(child.acceptorId()).isNull();
        transition(root.task().id(), Action.START, WORKER, null);
        assertThatThrownBy(() -> transition(root.task().id(), Action.COMPLETE, WORKER, null))
                .hasMessageContaining("子任务");
        transition(child.id(), Action.START, WORKER, null);
        assertThat(transition(child.id(), Action.COMPLETE, WORKER, null).task().status())
                .isEqualTo("COMPLETED");
        assertThat(tasks.detail(root.task().id(), WORKER).task().status())
                .isEqualTo("PENDING_ACCEPTANCE");
        transition(root.task().id(), Action.REJECT, ACCEPTOR, "总任务补充材料");
        assertThat(tasks.detail(child.id(), WORKER).task().status()).isEqualTo("COMPLETED");
    }

    @Test
    void pendingAcceptanceFreezesArrangementSplittingAndPlans() {
        Detail root = create(node("freeze", null, WORKER, ACCEPTOR), List.of());
        transition(root.task().id(), Action.START, WORKER, null);
        transition(root.task().id(), Action.COMPLETE, WORKER, null);
        assertThatThrownBy(
                        () ->
                                tasks.create(
                                        new Create(
                                                node("late", null, WORKER, null),
                                                root.task().id(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                key()),
                                        OWNER))
                .hasMessageContaining("拆分");
        Row current = tasks.detail(root.task().id(), OWNER).task();
        assertThatThrownBy(
                        () ->
                                tasks.assign(
                                        new Assign(
                                                current.id(),
                                                current.revision(),
                                                AssignmentMode.ASSIGNED,
                                                WORKER,
                                                List.of(),
                                                key(),
                                                null,
                                                new Acceptance(null)),
                                        OWNER))
                .hasMessageContaining("未开始");
        assertThatThrownBy(
                        () ->
                                tasks.preview(
                                        new Adjust(
                                                current.id(),
                                                current.revision(),
                                                List.of(node(current.id(), null, WORKER, ACCEPTOR)),
                                                "调整"),
                                        OWNER))
                .hasMessageContaining("待验收");
        assertThatThrownBy(
                        () ->
                                tasks.plan(
                                        new SavePlan(
                                                List.of(current.id()),
                                                Period.DAY,
                                                LocalDate.now(),
                                                true),
                                        WORKER))
                .hasMessageContaining("待验收");
    }

    @Test
    void identitiesAreValidatedAcrossCreationClaimingAndAssignment() {
        assertThatThrownBy(() -> create(node("self", null, WORKER, WORKER), List.of()))
                .hasMessageContaining("同一人");
        assertThatThrownBy(
                        () ->
                                create(
                                        node("root", null, WORKER, ACCEPTOR),
                                        List.of(node("child", null, WORKER, ACCEPTOR))))
                .hasMessageContaining("总任务");
        NodeInput open =
                new NodeInput(
                        "open",
                        null,
                        marker + "open",
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        AssignmentMode.OPEN,
                        List.of(),
                        null,
                        ACCEPTOR);
        Detail root = create(open, List.of());
        assertThat(tasks.page(query("MINE", "CLAIMABLE", null), ACCEPTOR).getTotal()).isZero();
        assertThatThrownBy(
                        () ->
                                tasks.claim(
                                        new Claim(root.task().id(), root.task().revision(), key()),
                                        ACCEPTOR))
                .hasMessageContaining("范围");
        Detail claimed =
                tasks.claim(new Claim(root.task().id(), root.task().revision(), key()), WORKER);
        assertThat(claimed.task().acceptorId()).isEqualTo(ACCEPTOR);
        assertThatThrownBy(
                        () ->
                                tasks.assign(
                                        new Assign(
                                                claimed.task().id(),
                                                claimed.task().revision(),
                                                AssignmentMode.ASSIGNED,
                                                ACCEPTOR,
                                                List.of(),
                                                key(),
                                                null),
                                        OWNER))
                .hasMessageContaining("同一人");
        Detail cleared =
                tasks.assign(
                        new Assign(
                                claimed.task().id(),
                                claimed.task().revision(),
                                AssignmentMode.ASSIGNED,
                                ACCEPTOR,
                                List.of(),
                                key(),
                                null,
                                new Acceptance(null)),
                        OWNER);
        assertThat(cleared.task().acceptorId()).isNull();
    }

    @Test
    void disabledAcceptorAndFailedNotificationRollBackSubmission() {
        Detail root = create(node("failure", null, WORKER, ACCEPTOR), List.of());
        transition(root.task().id(), Action.START, WORKER, null);
        when(servicesContext.getBean(AdminUserApi.class).getUser(ACCEPTOR))
                .thenReturn(user(ACCEPTOR, 1));
        assertThatThrownBy(() -> transition(root.task().id(), Action.COMPLETE, WORKER, null))
                .hasMessageContaining("停用");
        when(servicesContext.getBean(AdminUserApi.class).getUser(ACCEPTOR))
                .thenReturn(user(ACCEPTOR, 0));
        doReturn(null).when(servicesContext.getBean(IMsgSendService.class)).send(any());
        assertThatThrownBy(() -> transition(root.task().id(), Action.COMPLETE, WORKER, null))
                .hasMessageContaining("提醒");
        Detail current = tasks.detail(root.task().id(), WORKER);
        assertThat(current.task().status()).isEqualTo("RUNNING");
        assertThat(current.events()).noneMatch(e -> e.type().equals("SUBMITTED_FOR_ACCEPTANCE"));
    }

    @Test
    void draftAndPublishedTemplateKeepOptionalAcceptor() {
        Create command = command(node("draft", null, WORKER, ACCEPTOR), List.of());
        Draft draft = drafts.save(new DraftSave(null, null, command), OWNER);
        draftIds.add(draft.id());
        assertThat(drafts.get(draft.id(), OWNER).content().task().acceptorId()).isEqualTo(ACCEPTOR);
        Detail published =
                drafts.publish(new DraftPublish(draft.id(), draft.revision(), key()), OWNER);
        roots.add(published.task().rootId());
        assertThat(published.task().acceptorId()).isEqualTo(ACCEPTOR);
        Template template =
                tasks.saveTemplate(
                        new SaveTemplate(
                                null,
                                null,
                                marker,
                                null,
                                List.of(),
                                null,
                                node("template", null, WORKER, ACCEPTOR)),
                        OWNER);
        templates.add(template.id());
        TemplateVersion version =
                tasks.publish(new PublishTemplate(template.id(), template.revision()), OWNER);
        assertThat(version.task().acceptorId()).isEqualTo(ACCEPTOR);
        Detail fromTemplate =
                tasks.create(
                        new Create(
                                version.task(),
                                null,
                                template.id(),
                                version.version(),
                                null,
                                null,
                                null,
                                key()),
                        OWNER);
        roots.add(fromTemplate.task().rootId());
        assertThat(fromTemplate.task().acceptorId()).isEqualTo(ACCEPTOR);
    }

    @Test
    void concurrentDecisionsHaveOnlyOneWinner() throws Exception {
        Detail root = create(node("race", null, WORKER, ACCEPTOR), List.of());
        transition(root.task().id(), Action.START, WORKER, null);
        Detail waiting = transition(root.task().id(), Action.COMPLETE, WORKER, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first =
                    executor.submit(() -> decide(waiting.task(), Action.APPROVE, start));
            Future<Boolean> second =
                    executor.submit(() -> decide(waiting.task(), Action.REJECT, start));
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS) ^ second.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(tasks.detail(root.task().id(), ACCEPTOR).events())
                    .filteredOn(e -> Set.of("ACCEPTED", "REJECTED").contains(e.type()))
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private boolean decide(Row task, Action action, CountDownLatch start)
            throws InterruptedException {
        start.await();
        try {
            tasks.transition(
                    new Transition(task.id(), task.revision(), action, "并发决定", key()), ACCEPTOR);
            return true;
        } catch (com.lingan.ucp.framework.common.exception.ServiceException expected) {
            return false;
        }
    }

    private AdminUserRespDTO user(long id, int status) {
        AdminUserRespDTO value = new AdminUserRespDTO();
        value.setId(id);
        value.setNickname("验收测试" + id);
        value.setStatus(status);
        return value;
    }

    private Detail create(NodeInput root, List<NodeInput> nodes) {
        Detail result = tasks.create(command(root, nodes), OWNER);
        roots.add(result.task().rootId());
        return result;
    }

    private Create command(NodeInput root, List<NodeInput> nodes) {
        return new Create(root, null, null, null, null, null, null, key(), nodes);
    }

    private NodeInput node(String id, String parent, long assignee, Long acceptor) {
        return new NodeInput(
                id,
                parent,
                marker + id,
                null,
                assignee,
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

    private Detail transition(String id, Action action, long actor, String note) {
        Row current = tasks.detail(id, actor).task();
        return tasks.transition(new Transition(id, current.revision(), action, note, key()), actor);
    }

    private Query query(String scope, String tab, String status) {
        return new Query(
                scope, tab, null, marker, null, null, status, null, null, null, null, null, 1, 100);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
