package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.module.msg.api.*;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.*;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/** 当前开发库上的真实任务事务/Mapper测试，只清理本类记录；消息传输另外由真实HTTP验收。 */
class TaskCenterIntegrationTest {
    private TaskCenterService tasks;
    private IMsgSendService messages;
    private final Set<String> roots = new LinkedHashSet<>();
    private final Set<String> templates = new LinkedHashSet<>();
    private static final long OWNER = 10001L;
    private static final long WORKER = 21001L;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void end() {
        close();
    }

    @BeforeEach
    void setup() {
        tasks = servicesContext.getBean(TaskCenterService.class);
        messages = servicesContext.getBean(IMsgSendService.class);
        Mockito.reset(messages);
        Mockito.when(messages.send(Mockito.any())).thenReturn(999L);
        AdminUserApi users = servicesContext.getBean(AdminUserApi.class);
        Mockito.when(users.getUser(Mockito.anyLong()))
                .thenAnswer(
                        i -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(i.getArgument(0));
                            user.setNickname("测试成员" + user.getId());
                            user.setStatus(0);
                            return user;
                        });
    }

    @AfterEach
    void cleanup() {
        for (String root : roots) {
            jdbc.update(
                    "delete from public.nocode_task_comment where task_id in(select id from"
                            + " public.nocode_task_instance where root_id=?)",
                    root);
            jdbc.update(
                    "delete from public.nocode_task_plan where task_id in(select id from"
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
        roots.clear();
        templates.clear();
    }

    @Test
    void inlineChildrenExecutionAndRevisionProtectTheSameInstance() {
        Detail root = create("根任务", OWNER);
        Detail child =
                tasks.create(
                        new Create(
                                node(null, null, "列表子任务", OWNER),
                                root.task().id(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        OWNER);
        assertThat(child.task().rootId()).isEqualTo(root.task().id());
        assertThat(child.task().kind()).isEqualTo(Kind.ORDINARY);
        assertThat(child.task().role()).isEqualTo(NodeRole.NODE);
        assertThat(child.task().canStart()).isFalse();
        assertThatThrownBy(() -> start(child.task().id(), OWNER)).hasMessageContaining("上级任务");
        assertThat(tasks.detail(root.task().id(), OWNER).task().childCount()).isEqualTo(1);
        Detail started = start(root.task().id(), OWNER);
        assertThatThrownBy(() -> complete(started.task(), OWNER, key()))
                .hasMessageContaining("子任务");
        Detail childStarted = start(child.task().id(), OWNER);
        Detail childDone = complete(childStarted.task(), OWNER, key());
        assertThat(childDone.task().actualStart()).isNotNull();
        assertThat(childDone.task().actualEnd()).isNotNull();
        assertThatThrownBy(() -> complete(started.task(), OWNER, key())).hasMessageContaining("修改");
        Detail done = tasks.detail(root.task().id(), OWNER);
        assertThat(done.task().status()).isEqualTo("COMPLETED");
        assertThatThrownBy(
                        () ->
                                tasks.create(
                                        new Create(
                                                node(null, null, "非法追加", OWNER),
                                                root.task().id(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                key()),
                                        OWNER))
                .hasMessageContaining("不能拆分");
    }

    @Test
    void planIsIdempotentAndIndependentOfExecutionState() {
        Detail task = create("个人计划", OWNER);
        LocalDate today = LocalDate.now();
        SavePlan daily = new SavePlan(List.of(task.task().id()), Period.DAY, today, true);
        tasks.plan(daily, OWNER);
        tasks.plan(daily, OWNER);
        tasks.plan(new SavePlan(List.of(task.task().id()), Period.WEEK, today, true), OWNER);
        assertThat(tasks.detail(task.task().id(), OWNER).task().plans()).hasSize(2);
        assertThat(
                        tasks.detail(task.task().id(), OWNER).events().stream()
                                .filter(e -> e.type().equals("PLANNED")))
                .hasSize(2);
        tasks.plan(new SavePlan(List.of(task.task().id()), Period.DAY, today, false), OWNER);
        assertThat(tasks.detail(task.task().id(), OWNER).task().plans())
                .extracting(Plan::period)
                .containsExactly(Period.WEEK);
        assertThat(tasks.detail(task.task().id(), OWNER).task().status()).isEqualTo("PENDING");
    }

    @Test
    void recentUsesActorEventDatesAndInclusiveDayWeekMonthBoundaries() {
        String prefix = key();
        Detail first = create(prefix + "day", OWNER);
        Detail second = create(prefix + "week", OWNER);
        Detail third = create(prefix + "next", OWNER);
        jdbc.update(
                "UPDATE public.nocode_task_event SET create_time=timestamp '2026-09-28 00:00:00'"
                        + " WHERE root_id=?",
                first.task().id());
        jdbc.update(
                "UPDATE public.nocode_task_event SET create_time=timestamp '2026-10-04 23:59:59'"
                        + " WHERE root_id=?",
                second.task().id());
        jdbc.update(
                "UPDATE public.nocode_task_event SET create_time=timestamp '2026-10-05 00:00:00'"
                        + " WHERE root_id=?",
                third.task().id());
        LocalDate date = LocalDate.of(2026, 9, 28);
        Query daily = recent(prefix, date, Period.DAY, null, null);
        assertThat(tasks.page(daily, OWNER).getList())
                .extracting(Row::id)
                .containsExactly(first.task().id());
        assertThat(tasks.page(daily, OWNER).getList().getFirst().lastHandledAt())
                .isEqualTo(date.atStartOfDay());
        assertThat(tasks.page(recent(prefix, date, Period.WEEK, null, null), OWNER).getList())
                .extracting(Row::id)
                .containsExactly(second.task().id(), first.task().id());
        assertThat(tasks.page(recent(prefix, date, Period.MONTH, null, null), OWNER).getTotal())
                .isEqualTo(1);
        assertThat(
                        tasks.page(
                                        recent(
                                                prefix,
                                                date,
                                                Period.DAY,
                                                date.plusDays(6),
                                                date.plusDays(7)),
                                        OWNER)
                                .getTotal())
                .isEqualTo(2);
        assertThat(
                        tasks.page(
                                        recent(
                                                prefix,
                                                LocalDate.of(2099, 1, 1),
                                                Period.DAY,
                                                null,
                                                null),
                                        OWNER)
                                .getTotal())
                .isZero();
    }

    @Test
    void creatorAndAncestorCanReadAssigneePlansButOnlyCurrentAssigneeCanMaintainThem() {
        Detail root = create("代安排实例", OWNER);
        Detail parent =
                tasks.create(
                        new Create(
                                node(null, null, "负责人子树", WORKER),
                                root.task().id(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        OWNER);
        Detail child =
                tasks.create(
                        new Create(
                                node(null, null, "成员任务", 21002L),
                                parent.task().id(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        OWNER);
        Detail sibling =
                tasks.create(
                        new Create(
                                node(null, null, "其他子树任务", 21003L),
                                root.task().id(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        OWNER);
        LocalDate date = LocalDate.now();
        SavePlan command =
                new SavePlan(List.of(child.task().id()), Period.DAY, date, true, "ASSIGNEE");
        assertThatThrownBy(() -> tasks.plan(command, WORKER)).hasMessageContaining("本人");
        assertThatThrownBy(() -> tasks.plan(command, OWNER)).hasMessageContaining("本人");
        tasks.plan(command, 21002L);
        tasks.plan(command, 21002L);
        Plan plan = tasks.detail(child.task().id(), 21002L).task().plans().getFirst();
        assertThat(plan.userId()).isEqualTo(21002L);
        assertThat(plan.arrangedById()).isEqualTo(21002L);
        assertThat(plan.source()).isEqualTo("SELF");
        assertThat(plan.arrangedAt()).isNotNull();
        assertThat(tasks.detail(child.task().id(), WORKER).task().canPlan()).isFalse();
        assertThat(tasks.detail(child.task().id(), WORKER).task().plans()).hasSize(1);
        assertThat(tasks.detail(sibling.task().id(), WORKER).task().canPlan()).isFalse();
        assertThatThrownBy(
                        () ->
                                tasks.plan(
                                        new SavePlan(
                                                List.of(sibling.task().id()),
                                                Period.DAY,
                                                date,
                                                true,
                                                "ASSIGNEE"),
                                        WORKER))
                .hasMessageContaining("本人");
        tasks.plan(new SavePlan(List.of(child.task().id()), Period.DAY, date, true), 21002L);
        assertThat(tasks.detail(child.task().id(), 21002L).task().plans().getFirst().arrangedById())
                .isEqualTo(21002L);
        assertThatThrownBy(
                        () ->
                                tasks.plan(
                                        new SavePlan(
                                                List.of(sibling.task().id()),
                                                Period.WEEK,
                                                date,
                                                true,
                                                "ASSIGNEE"),
                                        OWNER))
                .hasMessageContaining("本人");
        assertThat(tasks.detail(child.task().id(), OWNER).task().status()).isEqualTo("PENDING");
        assertThatThrownBy(
                        () ->
                                tasks.plan(
                                        new SavePlan(
                                                List.of(child.task().id()),
                                                Period.DAY,
                                                date,
                                                false,
                                                "ASSIGNEE"),
                                        WORKER))
                .hasMessageContaining("本人");
        tasks.plan(new SavePlan(List.of(child.task().id()), Period.DAY, date, false), 21002L);
        assertThat(tasks.detail(child.task().id(), 21002L).task().plans()).isEmpty();
    }

    private Query recent(
            String search, LocalDate date, Period period, LocalDate from, LocalDate to) {
        return new Query(
                "MINE", "RECENT", date, search, null, null, null, null, null, null, from, to, 1,
                100, period);
    }

    @Test
    void pagingKeepsLimitOffsetBindingOrderUnderTheRealDataPermissionInterceptor() {
        create("分页参数回归A", OWNER);
        create("分页参数回归B", OWNER);
        Query first =
                new Query(
                        "MANAGE",
                        "POOL",
                        LocalDate.now(),
                        "分页参数回归",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        1,
                        1);
        Query second =
                new Query(
                        "MANAGE",
                        "POOL",
                        LocalDate.now(),
                        "分页参数回归",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        2,
                        1);
        com.lingan.ucp.framework.common.pojo.PageResult<Row> page1 = tasks.page(first, OWNER);
        com.lingan.ucp.framework.common.pojo.PageResult<Row> page2 = tasks.page(second, OWNER);
        assertThat(page1.getTotal()).isEqualTo(2);
        assertThat(page1.getList()).hasSize(1);
        assertThat(page2.getList()).hasSize(1);
        assertThat(page1.getList().getFirst().id()).isNotEqualTo(page2.getList().getFirst().id());
    }

    @Test
    void permissionAndDependencyFailuresDoNotAdvanceExecution() {
        Detail root =
                tasks.create(
                        new Create(
                                node(null, null, "分派任务", WORKER),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        OWNER);
        roots.add(root.task().rootId());
        assertThatThrownBy(() -> tasks.detail(root.task().id(), 99999)).hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                tasks.transition(
                                        new Transition(
                                                root.task().id(),
                                                root.task().revision(),
                                                Action.START,
                                                null,
                                                key()),
                                        OWNER))
                .hasMessageContaining("负责人");
        Detail started = start(root.task().id(), WORKER);
        assertThat(started.task().status()).isEqualTo("RUNNING");
    }

    @Test
    void earlyCompletionRecalculatesRelativeButKeepsFixedAndStartedNodes() {
        Schedule future = new Schedule(TimeMode.FIXED, LocalDateTime.now().minusDays(1), 0, 10);
        NodeInput a =
                new NodeInput(
                        "a", null, "前置A", null, OWNER, null, null, future, List.of(), null, null);
        NodeInput b =
                new NodeInput(
                        "b",
                        null,
                        "后继B",
                        null,
                        OWNER,
                        null,
                        null,
                        new Schedule(TimeMode.PREDECESSOR, null, 0, 2),
                        List.of("a"),
                        null,
                        null);
        NodeInput fixed =
                new NodeInput(
                        "fixed",
                        null,
                        "固定节点",
                        null,
                        OWNER,
                        null,
                        null,
                        future,
                        List.of("a"),
                        null,
                        null);
        Detail instance =
                tasks.create(
                        new Create(
                                node(null, null, "直接编排", OWNER),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key(),
                                List.of(a, b, fixed)),
                        OWNER);
        roots.add(instance.task().id());
        // 没有产品类型参数仍完整运行依赖和相对时间，旧存储默认值不由 DAG 形状推断。
        assertThat(instance.nodes()).allMatch(row -> row.kind() == Kind.ORDINARY);
        Row first = byTitle(instance, "前置A"),
                next = byTitle(instance, "后继B"),
                unchanged = byTitle(instance, "固定节点");
        start(instance.task().id(), OWNER);
        assertThatThrownBy(() -> start(next.id(), OWNER)).hasMessageContaining("前置");
        complete(start(first.id(), OWNER).task(), OWNER, key());
        Detail result = tasks.detail(instance.task().id(), OWNER);
        Row updated = byTitle(result, "后继B");
        assertThat(updated.expectedStart()).isBefore(next.expectedStart());
        assertThat(updated.baselineStart()).isEqualTo(next.baselineStart());
        assertThat(byTitle(result, "固定节点").expectedStart()).isEqualTo(unchanged.expectedStart());
        start(updated.id(), OWNER);
        assertThat(tasks.detail(updated.id(), OWNER).task().actualStart()).isNotNull();
    }

    @Test
    void graphRejectsCycleAndParentChildDeadlock() {
        NodeInput parent = node("p", null, "父任务", OWNER);
        NodeInput child =
                new NodeInput(
                        "c", "p", "子任务", null, OWNER, null, null, null, List.of("p"), null, null);
        assertThatThrownBy(() -> TaskGraph.normalize(List.of(parent, child), OWNER))
                .hasMessageContaining("死锁");
        NodeInput a =
                new NodeInput(
                        "a", null, "A", null, OWNER, null, null, null, List.of("b"), null, null);
        NodeInput b =
                new NodeInput(
                        "b", null, "B", null, OWNER, null, null, null, List.of("a"), null, null);
        assertThatThrownBy(() -> TaskGraph.template(List.of(a, b), OWNER))
                .hasMessageContaining("环");
        NodeInput gatedParent =
                new NodeInput(
                        "a",
                        null,
                        "等待B的父工序",
                        null,
                        OWNER,
                        null,
                        null,
                        null,
                        List.of("b"),
                        null,
                        null);
        NodeInput gatedSibling =
                new NodeInput(
                        "b",
                        null,
                        "等待C的工序",
                        null,
                        OWNER,
                        null,
                        null,
                        null,
                        List.of("c"),
                        null,
                        null);
        NodeInput nested = node("c", "a", "须A先开始的子任务", OWNER);
        assertThatThrownBy(
                        () -> TaskGraph.template(List.of(gatedParent, gatedSibling, nested), OWNER))
                .hasMessageContaining("死锁");
    }

    @Test
    void multiplePredecessorsRequireAllCompletionsAndUseTheLatestActualFinish() {
        NodeInput a = node("a", null, "并行A", OWNER);
        NodeInput c = node("c", null, "并行C", OWNER);
        NodeInput b =
                new NodeInput(
                        "b",
                        null,
                        "汇合B",
                        null,
                        OWNER,
                        null,
                        null,
                        new Schedule(TimeMode.PREDECESSOR, null, 0, 1),
                        List.of("a", "c"),
                        null,
                        null);
        Detail instance =
                tasks.create(
                        new Create(
                                node(null, null, "汇合实例", OWNER),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key(),
                                List.of(a, b, c),
                                Kind.ORDINARY),
                        OWNER);
        roots.add(instance.task().id());
        Row first = byTitle(instance, "并行A"),
                second = byTitle(instance, "并行C"),
                successor = byTitle(instance, "汇合B");
        start(instance.task().id(), OWNER);
        complete(start(first.id(), OWNER).task(), OWNER, key());
        assertThatThrownBy(() -> start(successor.id(), OWNER)).hasMessageContaining("前置");
        Row lastCompleted = complete(start(second.id(), OWNER).task(), OWNER, key()).task();
        Row ready = tasks.detail(successor.id(), OWNER).task();
        assertThat(ready.expectedStart()).isEqualTo(lastCompleted.actualEnd());
        assertThat(ready.canStart()).isTrue();
    }

    @Test
    void templateVersionsAndDefaultAssigneeRemainFrozenDespiteDifferentLegacyLaunchLabel() {
        Template draft =
                tasks.saveTemplate(
                        new SaveTemplate(
                                null,
                                null,
                                "测试模板",
                                null,
                                List.of(node("a", null, "模板v1节点", null)),
                                Kind.PROCESS),
                        OWNER);
        templates.add(draft.id());
        TemplateVersion v1 =
                tasks.publish(new PublishTemplate(draft.id(), draft.revision()), OWNER);
        Detail instance =
                tasks.create(
                        new Create(
                                node(null, null, "模板实例", WORKER),
                                null,
                                draft.id(),
                                v1.version(),
                                null,
                                null,
                                null,
                                key(),
                                null,
                                Kind.ORDINARY),
                        WORKER);
        roots.add(instance.task().id());
        assertThat(v1.kind()).isEqualTo(Kind.PROCESS);
        assertThat(instance.task().kind()).isEqualTo(Kind.PROCESS);
        assertThat(byTitle(instance, "模板v1节点").assigneeId()).isEqualTo(WORKER);
        Template latest =
                tasks.templates(OWNER).stream()
                        .filter(t -> t.id().equals(draft.id()))
                        .findFirst()
                        .orElseThrow();
        Template changed =
                tasks.saveTemplate(
                        new SaveTemplate(
                                latest.id(),
                                latest.revision(),
                                "新模板",
                                null,
                                List.of(node("a", null, "模板v2节点", null)),
                                Kind.ORDINARY),
                        OWNER);
        tasks.publish(new PublishTemplate(changed.id(), changed.revision()), OWNER);
        assertThat(tasks.detail(instance.task().id(), WORKER).nodes())
                .extracting(Row::title)
                .contains("模板v1节点")
                .doesNotContain("模板v2节点");
        assertThat(tasks.detail(instance.task().id(), WORKER).task().templateVersion())
                .isEqualTo(1);
        assertThat(tasks.detail(instance.task().id(), WORKER).nodes())
                .allMatch(row -> row.kind() == Kind.PROCESS);
        assertThat(tasks.version(draft.id(), 1, WORKER).kind()).isEqualTo(Kind.PROCESS);
        assertThat(tasks.version(draft.id(), 2, WORKER).kind()).isEqualTo(Kind.ORDINARY);
    }

    @Test
    void templateWithoutLegacyLabelRunsDependenciesAndPredecessorTimeAsOneDag() {
        NodeInput first = node("first", null, "模板前置", null);
        NodeInput next =
                new NodeInput(
                        "next",
                        null,
                        "模板后继",
                        null,
                        null,
                        null,
                        null,
                        new Schedule(TimeMode.PREDECESSOR, null, 0, 1),
                        List.of("first"),
                        null,
                        null);
        Template draft =
                tasks.saveTemplate(
                        new SaveTemplate(null, null, "统一任务模板", null, List.of(first, next)), OWNER);
        templates.add(draft.id());
        TemplateVersion version =
                tasks.publish(new PublishTemplate(draft.id(), draft.revision()), OWNER);
        Detail instance =
                tasks.create(
                        new Create(
                                node(null, null, "统一模板实例", WORKER),
                                null,
                                draft.id(),
                                version.version(),
                                null,
                                null,
                                null,
                                key()),
                        WORKER);
        roots.add(instance.task().id());
        assertThat(instance.nodes()).allMatch(row -> row.kind() == Kind.ORDINARY);
        assertThat(byTitle(instance, "模板前置").role()).isEqualTo(NodeRole.NODE);
        Row before = byTitle(instance, "模板前置");
        Row after = byTitle(instance, "模板后继");
        start(instance.task().id(), WORKER);
        assertThatThrownBy(() -> start(after.id(), WORKER)).hasMessageContaining("前置");
        Row done = complete(start(before.id(), WORKER).task(), WORKER, key()).task();
        assertThat(tasks.detail(after.id(), WORKER).task().expectedStart())
                .isEqualTo(done.actualEnd());
        complete(start(after.id(), WORKER).task(), WORKER, key());
        assertThat(tasks.detail(instance.task().id(), WORKER).task().status())
                .isEqualTo(State.COMPLETED.name());
    }

    @Test
    void legacyLabelsDoNotChangeInstancePositionOrRejectSplittingAndRemainFilterCompatible() {
        String marker = "任务类型-" + key();
        Detail ordinary = create(marker + "普通", OWNER);
        Detail process =
                tasks.create(
                        new Create(
                                node(null, null, marker + "流程", OWNER),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key(),
                                List.of(
                                        node("a", null, "并行工序A", OWNER),
                                        node("b", null, "并行工序B", OWNER)),
                                Kind.PROCESS),
                        OWNER);
        roots.add(process.task().id());
        Row stage = byTitle(process, "并行工序A");
        Detail split =
                tasks.create(
                        new Create(
                                node(null, null, "工序内子任务", OWNER),
                                stage.id(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        OWNER);
        assertThat(split.task().kind()).isEqualTo(Kind.PROCESS);
        assertThat(split.task().role()).isEqualTo(NodeRole.SUBTASK);
        assertThat(split.task().predecessorIds()).isEmpty();
        assertThat(split.nodes())
                .hasSize(4)
                .extracting(Row::id)
                .contains(process.task().id(), stage.id(), byTitle(process, "并行工序B").id());
        assertThat(
                        split.nodes().stream()
                                .filter(row -> row.id().equals(stage.id()))
                                .findFirst()
                                .orElseThrow()
                                .role())
                .isEqualTo(NodeRole.NODE);
        assertThat(process.task().role()).isEqualTo(NodeRole.ROOT);
        Detail nested =
                tasks.create(
                        new Create(
                                node(null, null, "旧标签不阻止拆分", OWNER),
                                stage.id(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                key(),
                                null,
                                Kind.ORDINARY),
                        OWNER);
        assertThat(nested.task().kind()).isEqualTo(Kind.PROCESS);
        assertThat(nested.task().role()).isEqualTo(NodeRole.SUBTASK);
        Detail direct =
                tasks.create(
                        new Create(
                                node(null, null, "直属节点位置", OWNER),
                                ordinary.task().id(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                key(),
                                null,
                                Kind.PROCESS),
                        OWNER);
        assertThat(direct.task().kind()).isEqualTo(Kind.ORDINARY);
        assertThat(direct.task().role()).isEqualTo(NodeRole.NODE);
        assertThat(
                        tasks.page(
                                        new Query(
                                                "MINE",
                                                "ALL",
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
                                                50),
                                        OWNER)
                                .getList())
                .extracting(Row::id)
                .containsExactlyInAnyOrder(ordinary.task().id(), process.task().id());
        for (Kind kind : Kind.values()) {
            Query query =
                    new Query(
                            "MINE",
                            "ALL",
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
                            50,
                            Period.DAY,
                            kind);
            assertThat(tasks.page(query, OWNER).getTotal()).isEqualTo(1);
            assertThat(tasks.page(query, OWNER).getList())
                    .singleElement()
                    .extracting(Row::id)
                    .isEqualTo(kind == Kind.PROCESS ? process.task().id() : ordinary.task().id());
        }
    }

    @Test
    void hierarchyStartsTopDownAndCompletesBottomUpWhileSiblingsRemainParallel() {
        Detail root =
                tasks.create(
                        new Create(
                                node(null, null, "层级推进", OWNER),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key(),
                                List.of(
                                        node("a", null, "父工序", OWNER),
                                        node("b", null, "并行工序", OWNER),
                                        node("c", "a", "执行子任务", OWNER)),
                                Kind.PROCESS),
                        OWNER);
        roots.add(root.task().id());
        Row stage = byTitle(root, "父工序"),
                sibling = byTitle(root, "并行工序"),
                child = byTitle(root, "执行子任务");
        assertThatThrownBy(() -> start(child.id(), OWNER)).hasMessageContaining("上级任务");
        start(root.task().id(), OWNER);
        assertThat(tasks.detail(stage.id(), OWNER).task().canStart()).isTrue();
        assertThat(tasks.detail(sibling.id(), OWNER).task().canStart()).isTrue();
        assertThat(tasks.detail(child.id(), OWNER).task().canStart()).isFalse();
        Row stageStarted = start(stage.id(), OWNER).task();
        assertThatThrownBy(() -> complete(stageStarted, OWNER, key())).hasMessageContaining("子任务");
        complete(start(child.id(), OWNER).task(), OWNER, key());
        assertThat(tasks.detail(stage.id(), OWNER).task().status()).isEqualTo("COMPLETED");
        assertThatThrownBy(
                        () -> complete(tasks.detail(root.task().id(), OWNER).task(), OWNER, key()))
                .hasMessageContaining("子任务");
        complete(start(sibling.id(), OWNER).task(), OWNER, key());
        assertThat(tasks.detail(root.task().id(), OWNER).task().status()).isEqualTo("COMPLETED");
    }

    @Test
    void instanceAdjustmentPreservesBeforeSnapshotAndFreezesStartedNodes() {
        Detail root = create("待调整", OWNER);
        Row original = root.task();
        NodeInput changed = node(original.id(), null, "修改后", OWNER);
        Adjust command =
                new Adjust(original.id(), original.instanceRevision(), List.of(changed), "调整任务名称");
        assertThat(tasks.preview(command, OWNER).changedIds()).contains(original.id());
        Detail adjusted = tasks.adjust(command, OWNER);
        assertThat(adjusted.task().title()).isEqualTo("修改后");
        String material =
                jdbc.queryForObject(
                        "select material_json from public.nocode_task_event where root_id=? and"
                                + " event_type='ADJUSTED'",
                        String.class,
                        original.id());
        assertThat(material).contains("待调整").contains("修改后");
        Row started = start(original.id(), OWNER).task();
        assertThatThrownBy(
                        () ->
                                tasks.adjust(
                                        new Adjust(
                                                original.id(),
                                                started.instanceRevision(),
                                                List.of(node(original.id(), null, "非法改动", OWNER)),
                                                "非法"),
                                        OWNER))
                .hasMessageContaining("冻结");
    }

    @Test
    void commentsReplyMentionAndRetriesProduceOneSafeNotification() {
        Detail task =
                tasks.create(
                        new Create(
                                node(null, null, "评论任务", WORKER),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        OWNER);
        roots.add(task.task().id());
        AddComment command =
                new AddComment(
                        task.task().id(),
                        null,
                        "<script>alert(1)</script>",
                        List.of(WORKER),
                        key());
        Comment first = tasks.comment(command, OWNER);
        Comment retry = tasks.comment(command, OWNER);
        assertThat(retry.id()).isEqualTo(first.id());
        ArgumentCaptor<MsgSendParam> sent = ArgumentCaptor.forClass(MsgSendParam.class);
        Mockito.verify(messages, Mockito.times(1)).send(sent.capture());
        assertThat(sent.getValue().getContent())
                .contains("&lt;script&gt;")
                .doesNotContain("<script>");
        assertThat(sent.getValue().getTargets())
                .extracting(MsgTarget::getTargetId)
                .containsExactly(Long.toString(WORKER));
        assertThatThrownBy(
                        () ->
                                tasks.comment(
                                        new AddComment(
                                                task.task().id(),
                                                null,
                                                "越权@",
                                                List.of(99999L),
                                                key()),
                                        OWNER))
                .hasMessageContaining("只能选择");
        Mockito.when(messages.send(Mockito.any())).thenThrow(new IllegalStateException("发送失败"));
        long before =
                jdbc.queryForObject(
                        "select count(*) from public.nocode_task_comment where task_id=?",
                        Long.class,
                        task.task().id());
        assertThatThrownBy(
                        () ->
                                tasks.comment(
                                        new AddComment(
                                                task.task().id(),
                                                first.id(),
                                                "回复",
                                                List.of(OWNER),
                                                key()),
                                        WORKER))
                .hasMessageContaining("发送失败");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_comment where task_id=?",
                                Long.class,
                                task.task().id()))
                .isEqualTo(before);
    }

    @Test
    void concurrentCompletionsHaveOneWinnerAndRequestRetryIsSafe() throws Exception {
        Detail task = create("并发完成", OWNER);
        Row started = start(task.task().id(), OWNER).task();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> results =
                    executor.invokeAll(
                            List.of(
                                    () -> attemptComplete(started),
                                    () -> attemptComplete(started)));
            int success = 0;
            for (Future<Boolean> result : results) if (result.get()) success++;
            assertThat(success).isEqualTo(1);
            assertThat(
                            tasks.detail(started.id(), OWNER).events().stream()
                                    .filter(e -> e.type().equals("COMPLETED")))
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
        Detail retryTask = create("请求重试", OWNER);
        Transition command =
                new Transition(
                        retryTask.task().id(),
                        retryTask.task().revision(),
                        Action.START,
                        null,
                        key());
        Detail first = tasks.transition(command, OWNER);
        Detail retry = tasks.transition(command, OWNER);
        assertThat(retry.task().revision()).isEqualTo(first.task().revision());
    }

    private boolean attemptComplete(Row row) {
        try {
            complete(row, OWNER, key());
            return true;
        } catch (com.lingan.ucp.framework.common.exception.ServiceException expected) {
            return false;
        }
    }

    private Detail create(String title, long actor) {
        Detail result =
                tasks.create(
                        new Create(
                                node(null, null, title, actor),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        actor);
        roots.add(result.task().id());
        return result;
    }

    private Detail start(String id, long actor) {
        Row task = tasks.detail(id, actor).task();
        return tasks.transition(
                new Transition(id, task.revision(), Action.START, null, key()), actor);
    }

    private Detail complete(Row row, long actor, String key) {
        return tasks.transition(
                new Transition(row.id(), row.revision(), Action.COMPLETE, "完成记录", key), actor);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static NodeInput node(String id, String parent, String title, Long actor) {
        return new NodeInput(
                id, parent, title, null, actor, null, null, null, List.of(), null, null);
    }

    private static Row byTitle(Detail detail, String title) {
        return detail.nodes().stream()
                .filter(n -> n.title().equals(title))
                .findFirst()
                .orElseThrow();
    }
}
