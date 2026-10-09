package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskManagement;
import com.lingan.ucp.nocode.api.TaskPlanning;
import com.lingan.ucp.nocode.api.TaskPlanning.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.*;

/** 当前开发库上的个人清单继承回归；不复制下级计划，只清理本类 UUID 标识的任务组。 */
class TaskChecklistInheritanceIntegrationTest {
    private static final long OWNER = 914701L, WORKER = 914702L, OTHER = 914703L;
    private final String marker = "checklist_inherit_" + UUID.randomUUID() + "_";
    private final Set<String> roots = new LinkedHashSet<>();
    private TaskCenterService tasks;

    @BeforeAll
    static void open() throws Exception {
        try {
            connect();
        } catch (FlywayValidateException pendingMigrations) {
            // 清单继承复用现有表，不能为测试顺带执行其他功能的待应用迁移。
            Flyway.configure()
                    .configuration(databaseTool.flyway().getConfiguration())
                    .ignoreMigrationPatterns("*:pending", "*:future")
                    .load()
                    .validate();
        }
    }

    @AfterAll
    static void finish() {
        close();
    }

    @BeforeEach
    void setup() {
        tasks = servicesContext.getBean(TaskCenterService.class);
        IMsgSendService messages = servicesContext.getBean(IMsgSendService.class);
        Mockito.reset(messages);
        Mockito.when(messages.send(Mockito.any())).thenReturn(999L);
        PermissionCommonApi permissions = servicesContext.getBean(PermissionCommonApi.class);
        Mockito.when(permissions.hasAnyPermissions(OWNER, "nocode:task:manage-all"))
                .thenReturn(false);
        Mockito.when(servicesContext.getBean(AdminUserApi.class).getUser(Mockito.anyLong()))
                .thenAnswer(
                        invocation -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(invocation.getArgument(0));
                            user.setNickname("继承验证员工" + user.getId());
                            user.setStatus(0);
                            return user;
                        });
    }

    @AfterEach
    void cleanup() {
        for (String root : roots) {
            assertThat(
                            jdbc.queryForObject(
                                    "select title from public.nocode_task_instance where id=?",
                                    String.class,
                                    root))
                    .startsWith(marker);
            jdbc.update(
                    "delete from public.nocode_task_plan where task_id in"
                            + " (select id from public.nocode_task_instance where root_id=?)",
                    root);
            jdbc.update("delete from public.nocode_task_event where root_id=?", root);
            jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
        }
    }

    @Test
    void sameAssigneeDescendantsInheritAcrossForeignIntermediateWithoutCopiedRows() {
        Detail graph = mixedGraph("mixed");
        String root = graph.task().id();
        String own = row(graph, "mixed-own").id();
        String foreign = row(graph, "mixed-foreign").id();
        String grandchild = row(graph, "mixed-grandchild").id();
        Row before = tasks.detail(own, WORKER).task();
        int version = item(own, WORKER).version();
        add(root, WORKER, Period.DAY, context(root, WORKER).today());

        for (String id : List.of(own, grandchild)) {
            ChecklistItem current = item(id, WORKER);
            assertInherited(current.todayPlans(), root);
            assertInherited(current.weekPlans(), root);
            assertThat(current.history()).isEmpty();
            assertThat(planRows(id)).isZero();
            assertThat(tasks.detail(id, WORKER).task().plans())
                    .filteredOn(p -> p.mode() == Mode.CHECKLIST)
                    .hasSize(2)
                    .allSatisfy(p -> assertThat(p.inheritedFromTaskId()).isEqualTo(root));
        }
        assertThat(item(foreign, OTHER).todayPlans()).isEmpty();
        assertThat(item(foreign, OTHER).weekPlans()).isEmpty();
        assertThat(planRows(root)).isEqualTo(2);
        assertThat(item(own, WORKER).version()).isEqualTo(version);
        Row after = tasks.detail(own, WORKER).task();
        assertThat(after.revision()).isEqualTo(before.revision());
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.expectedStart()).isEqualTo(before.expectedStart());
        assertThat(after.actualStart()).isNull();
    }

    @Test
    void nearestAncestorWinsButExplicitMembershipSurvivesAncestorRemoval() {
        Detail graph =
                create(
                        "nearest-root",
                        List.of(
                                node("stage", null, "nearest-stage", WORKER),
                                node("leaf", "stage", "nearest-leaf", WORKER),
                                node("explicit", null, "nearest-explicit", WORKER)));
        String root = graph.task().id();
        String stage = row(graph, "nearest-stage").id();
        String leaf = row(graph, "nearest-leaf").id();
        String explicit = row(graph, "nearest-explicit").id();
        ChecklistContext dates = context(root, WORKER);
        add(explicit, WORKER, Period.DAY, dates.today());
        ChecklistItem explicitBefore = item(explicit, WORKER);
        add(stage, WORKER, Period.WEEK, dates.weekStart());
        add(root, WORKER, Period.DAY, dates.today());

        assertInherited(item(leaf, WORKER).todayPlans(), root);
        assertInherited(item(leaf, WORKER).weekPlans(), stage);
        ChecklistItem currentExplicit = item(explicit, WORKER);
        assertThat(currentExplicit.todayPlans())
                .extracting(Plan::id)
                .containsExactly(explicitBefore.todayPlans().getFirst().id());
        assertThat(currentExplicit.weekPlans())
                .extracting(Plan::id)
                .containsExactly(explicitBefore.weekPlans().getFirst().id());
        assertThat(currentExplicit.todayPlans().getFirst().inheritedFromTaskId()).isNull();
        assertThat(currentExplicit.weekPlans().getFirst().inheritedFromTaskId()).isNull();

        remove(
                stage,
                WORKER,
                Period.WEEK,
                dates.weekStart(),
                item(stage, WORKER).weekPlans().getFirst().id());
        assertInherited(item(leaf, WORKER).weekPlans(), root);
        remove(
                root,
                WORKER,
                Period.DAY,
                dates.today(),
                item(root, WORKER).todayPlans().getFirst().id());
        remove(
                root,
                WORKER,
                Period.WEEK,
                dates.weekStart(),
                item(root, WORKER).weekPlans().getFirst().id());
        assertThat(item(leaf, WORKER).todayPlans()).isEmpty();
        assertThat(item(leaf, WORKER).weekPlans()).isEmpty();
        assertThat(item(explicit, WORKER).todayPlans())
                .extracting(Plan::id)
                .containsExactly(explicitBefore.todayPlans().getFirst().id());
        assertThat(item(explicit, WORKER).weekPlans())
                .extracting(Plan::id)
                .containsExactly(explicitBefore.weekPlans().getFirst().id());
        assertThat(planRows(leaf)).isZero();
    }

    @Test
    void inheritedAddIsNoOpAndCannotRemoveSourceWhileRetriesKeepOriginalResult() {
        Detail graph = create("noop-root", List.of(node("child", null, "noop-child", WORKER)));
        String root = graph.task().id(), child = row(graph, "noop-child").id();
        ChecklistContext dates = context(root, WORKER);
        add(root, WORKER, Period.DAY, dates.today());
        ChecklistChange inheritedAdd =
                command(child, WORKER, ChecklistAction.ADD, Period.DAY, dates.today(), null);
        TaskPlanning.Result first = tasks.checklist(inheritedAdd, WORKER);
        assertThat(first.changed()).isEmpty();
        assertThat(first.unchanged()).containsExactly(child);
        int events = tasks.detail(root, OWNER).events().size();
        assertThat(tasks.checklist(inheritedAdd, WORKER)).isEqualTo(first);
        assertThat(tasks.detail(root, OWNER).events()).hasSize(events);
        assertThat(item(child, WORKER).version())
                .isEqualTo(inheritedAdd.expectedVersions().get(child));
        assertThat(planRows(child)).isZero();

        String sourceId = item(root, WORKER).todayPlans().getFirst().id();
        assertThatThrownBy(() -> remove(child, WORKER, Period.DAY, dates.today(), sourceId))
                .isInstanceOf(ServiceException.class);
        assertThat(item(root, WORKER).todayPlans()).extracting(Plan::id).containsExactly(sourceId);
        assertInherited(item(child, WORKER).todayPlans(), root);

        remove(root, WORKER, Period.DAY, dates.today(), sourceId);
        assertThat(tasks.checklist(inheritedAdd, WORKER)).isEqualTo(first);
        assertThat(item(child, WORKER).todayPlans()).isEmpty();
        add(child, WORKER, Period.DAY, dates.today());
        assertThat(item(child, WORKER).todayPlans())
                .singleElement()
                .satisfies(p -> assertThat(p.inheritedFromTaskId()).isNull());
        assertInherited(item(child, WORKER).weekPlans(), root);
        assertThat(planRows(child)).isEqualTo(1);

        ChecklistChange stale =
                new ChecklistChange(
                        inheritedAdd.ids(),
                        Target.SELF,
                        ChecklistAction.ADD,
                        Period.DAY,
                        dates.today(),
                        null,
                        inheritedAdd.expectedVersions(),
                        key());
        assertThatThrownBy(() -> tasks.checklist(stale, WORKER)).hasMessageContaining("已变化");
        ChecklistChange reused =
                new ChecklistChange(
                        inheritedAdd.ids(),
                        Target.SELF,
                        ChecklistAction.ADD,
                        Period.WEEK,
                        dates.weekStart(),
                        null,
                        inheritedAdd.expectedVersions(),
                        inheritedAdd.requestKey());
        assertThatThrownBy(() -> tasks.checklist(reused, WORKER)).hasMessageContaining("标识");
    }

    @Test
    void newDescendantsImmediatelyFollowCurrentAndNextWeekWithoutPlanWrites() {
        Detail graph = create("new-root", List.of());
        String root = graph.task().id();
        ChecklistContext dates = context(root, WORKER);
        add(root, WORKER, Period.DAY, dates.today());
        add(root, WORKER, Period.WEEK, dates.nextWeekStart());
        Detail created =
                tasks.split(
                        new Create(
                                node("new", null, "new-child", WORKER),
                                root,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        WORKER);
        String child = created.task().id();
        ChecklistItem current = item(child, WORKER);
        assertInherited(current.todayPlans(), root);
        assertInherited(current.weekPlans(), root);
        assertInherited(current.nextWeekPlans(), root);
        assertThat(planRows(root)).isEqualTo(3);
        assertThat(planRows(child)).isZero();
        assertThat(current.history()).isEmpty();
    }

    @Test
    void personalFiltersRootPaginationAndEmployeeTotalsUseSameEffectiveMembership() {
        Detail first = mixedGraph("counts");
        String root = first.task().id();
        String child = row(first, "counts-own").id();
        String grandchild = row(first, "counts-grandchild").id();
        Detail second = create("next-root", List.of(node("next", null, "next-child", WORKER)));
        String secondChild = row(second, "next-child").id();
        String unplanned = create("free-root", List.of()).task().id();
        ChecklistContext dates = context(root, WORKER);
        // 显式记录与祖先继承同时存在，查询和汇总必须只计一次。
        add(child, WORKER, Period.DAY, dates.today());
        add(root, WORKER, Period.DAY, dates.today());
        add(second.task().id(), WORKER, Period.WEEK, dates.nextWeekStart());

        for (String tab : List.of("TODAY", "WEEK")) {
            PageResult<Row> page = personal(tab, dates.today(), Filter.PLANNED, 1, 100);
            assertThat(page.getTotal()).isEqualTo(3);
            assertThat(page.getList())
                    .extracting(Row::id)
                    .containsExactlyInAnyOrder(root, child, grandchild);
            List<String> pagedIds = new ArrayList<>();
            for (int number = 1; number <= 3; number++) {
                PageResult<Row> one = personal(tab, dates.today(), Filter.PLANNED, number, 1);
                assertThat(one.getTotal()).isEqualTo(3);
                assertThat(one.getList()).hasSize(1);
                pagedIds.add(one.getList().getFirst().id());
            }
            assertThat(pagedIds).containsExactlyInAnyOrder(root, child, grandchild);
            PageResult<PersonalTreeNode> grouped =
                    tasks.personalTreePage(
                            query("MINE", tab, dates.today(), Filter.PLANNED, 1, 1), WORKER);
            assertThat(grouped.getTotal()).isEqualTo(1);
            assertThat(grouped.getList()).extracting(n -> n.task().id()).containsExactly(root);
        }
        PageResult<Row> next = personal("WEEK", dates.nextWeekStart(), Filter.PLANNED, 1, 100);
        assertThat(next.getTotal()).isEqualTo(2);
        assertThat(next.getList())
                .extracting(Row::id)
                .containsExactlyInAnyOrder(second.task().id(), secondChild);
        PageResult<PersonalTreeNode> nextGroups =
                tasks.personalTreePage(
                        query("MINE", "WEEK", dates.nextWeekStart(), Filter.PLANNED, 1, 1), WORKER);
        assertThat(nextGroups.getTotal()).isEqualTo(1);
        assertThat(nextGroups.getList())
                .extracting(n -> n.task().id())
                .containsExactly(second.task().id());
        PageResult<Row> free = personal("ALL", dates.today(), Filter.UNPLANNED, 1, 100);
        assertThat(free.getTotal()).isEqualTo(1);
        assertThat(free.getList()).extracting(Row::id).containsExactly(unplanned);

        TaskManagement.Employee employee = employee(dates.today());
        assertThat(employee.todayCount()).isEqualTo(2);
        assertThat(employee.weekCount()).isEqualTo(2);
        assertThat(employee(dates.nextWeekStart()).weekCount()).isEqualTo(1);
        for (TaskManagement.EmployeeMetric metric :
                List.of(TaskManagement.EmployeeMetric.TODAY, TaskManagement.EmployeeMetric.WEEK)) {
            PageResult<Row> drilldown =
                    tasks.managementPage(
                            new TaskManagement.Query(
                                    query("MANAGE", "ALL", dates.today(), null, 1, 100),
                                    TaskManagement.Focus.ALL,
                                    WORKER,
                                    metric),
                            OWNER);
            assertThat(drilldown.getTotal()).isEqualTo(2);
            assertThat(drilldown.getList())
                    .extracting(Row::id)
                    .containsExactlyInAnyOrder(child, grandchild);
            PageResult<Row> groups =
                    tasks.managementPage(
                            new TaskManagement.Query(
                                    query("MANAGE", "ALL", dates.today(), null, 1, 1),
                                    TaskManagement.Focus.ALL,
                                    WORKER,
                                    metric,
                                    true),
                            OWNER);
            assertThat(groups.getTotal()).isEqualTo(1);
            assertThat(groups.getList()).extracting(Row::id).containsExactly(root);
        }
        assertThat(item(row(first, "counts-foreign").id(), OTHER).todayPlans()).isEmpty();
    }

    @Test
    void reassignmentArchivesSourceAndNeverRevivesItsInheritance() {
        Detail graph =
                create(
                        "archive-root",
                        List.of(
                                node("explicit", null, "archive-explicit", WORKER),
                                node("inherited", null, "archive-inherited", WORKER)));
        String root = graph.task().id();
        String explicit = row(graph, "archive-explicit").id();
        String inherited = row(graph, "archive-inherited").id();
        ChecklistContext dates = context(root, WORKER);
        add(explicit, WORKER, Period.DAY, dates.today());
        add(root, WORKER, Period.DAY, dates.today());
        assertInherited(item(inherited, WORKER).todayPlans(), root);

        assign(root, OTHER);
        assertThat(tasks.detail(inherited, OWNER).task().assigneeId()).isEqualTo(WORKER);
        assertThat(item(inherited, WORKER).todayPlans()).isEmpty();
        assertThat(item(inherited, WORKER).weekPlans()).isEmpty();
        assertThat(item(explicit, WORKER).todayPlans()).hasSize(1);
        assertThat(item(root, OTHER).todayPlans()).isEmpty();
        assertThat(
                        tasks.checklistContext(
                                        new ContextQuery(List.of(root), Target.ASSIGNEE), OWNER)
                                .items()
                                .getFirst()
                                .history())
                .hasSize(2)
                .allSatisfy(p -> assertThat(p.historyReason()).isEqualTo("REASSIGNED"));
        assign(root, WORKER);
        assertThat(item(root, WORKER).todayPlans()).isEmpty();
        assertThat(item(inherited, WORKER).todayPlans()).isEmpty();
        assertThat(planRows(inherited)).isZero();
    }

    @Test
    void expiredAncestorMembershipNeverFabricatesDescendantHistory() {
        Detail graph =
                create("past-root", List.of(node("child", null, "past-existing-child", WORKER)));
        String root = graph.task().id(), child = row(graph, "past-existing-child").id();
        ChecklistContext dates = context(root, WORKER);
        add(root, WORKER, Period.DAY, dates.today());
        // 仅将本例自有根的两条记录移动到过去，不改变全局时钟或用户计划。
        assertThat(roots).contains(root);
        assertThat(
                        jdbc.update(
                                "update public.nocode_task_plan set plan_date=plan_date-14,"
                                        + " end_date=end_date-14 where task_id=?",
                                root))
                .isEqualTo(2);
        Detail added =
                tasks.split(
                        new Create(
                                node("new", null, "past-new-child", WORKER),
                                root,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        WORKER);
        for (String id : List.of(child, added.task().id())) {
            ChecklistItem current = item(id, WORKER);
            assertThat(current.todayPlans()).isEmpty();
            assertThat(current.weekPlans()).isEmpty();
            assertThat(current.history()).isEmpty();
            assertThat(planRows(id)).isZero();
        }
        assertThat(item(root, WORKER).history()).hasSize(2);
        assertThat(personal("TODAY", dates.today().minusDays(14), Filter.PLANNED, 1, 100).getList())
                .extracting(Row::id)
                .containsExactly(root);
        assertThat(personal("ALL", dates.today(), Filter.UNPLANNED, 1, 100).getList())
                .extracting(Row::id)
                .containsExactlyInAnyOrder(root, child, added.task().id());
    }

    @Test
    void childFirstBatchAddsOnlyAncestorMembershipAndReplaysSameReceipt() {
        Detail graph = create("batch-root", List.of(node("child", null, "batch-child", WORKER)));
        String root = graph.task().id(), child = row(graph, "batch-child").id();
        ChecklistContext dates = context(root, WORKER);
        ChecklistChange command =
                new ChecklistChange(
                        List.of(child, root),
                        Target.SELF,
                        ChecklistAction.ADD,
                        Period.DAY,
                        dates.today(),
                        null,
                        Map.of(
                                root, item(root, WORKER).version(),
                                child, item(child, WORKER).version()),
                        key());

        TaskPlanning.Result result = tasks.checklist(command, WORKER);
        assertThat(result.changed()).containsExactly(root);
        assertThat(result.unchanged()).containsExactly(child);
        assertThat(planRows(root)).isEqualTo(2);
        assertThat(planRows(child)).isZero();
        assertInherited(item(child, WORKER).todayPlans(), root);
        assertInherited(item(child, WORKER).weekPlans(), root);
        assertThat(item(child, WORKER).version()).isEqualTo(command.expectedVersions().get(child));
        int events = tasks.detail(root, OWNER).events().size();
        assertThat(tasks.checklist(command, WORKER)).isEqualTo(result);
        assertThat(tasks.detail(root, OWNER).events()).hasSize(events);
        assertThat(planRows(root)).isEqualTo(2);
        assertThat(planRows(child)).isZero();
    }

    @Test
    void legacyParentWithoutPlanAnchorStillAllowsAutomaticChildSplit() {
        Detail graph = create("unanchored-root", List.of());
        assertThat(graph.task().schedule().mode()).isEqualTo(TimeMode.UNSCHEDULED);
        assertThat(graph.task().plannedStart()).isNull();
        NodeInput automatic =
                new NodeInput(
                        "auto-child",
                        null,
                        marker + "unanchored-child",
                        null,
                        WORKER,
                        null,
                        null,
                        new Schedule(TimeMode.AUTO, null, 0, 1),
                        List.of(),
                        null,
                        null,
                        null,
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        null);
        Detail split =
                tasks.split(
                        new Create(
                                automatic, graph.task().id(), null, null, null, null, null, key()),
                        WORKER);
        Row child = tasks.detail(split.task().id(), WORKER).task();
        assertThat(child.parentId()).isEqualTo(graph.task().id());
        assertThat(child.schedule().mode()).isEqualTo(TimeMode.AUTO);
        assertThat(child.plannedStart()).isNull();
        assertThat(child.expectedStart()).isNull();
        assertThat(child.expectedEnd()).isNull();
        assertThat(child.scheduleSummary().partial()).isTrue();
        assertThat(child.status()).isEqualTo(State.PENDING.name());
        // 缺起点只在排期预览提示，不让历史任务失去拆分能力。
        SchedulePreview preview =
                tasks.schedulePreview(new SchedulePreviewQuery(List.of(automatic), null), WORKER);
        assertThat(preview.warnings()).isNotEmpty();
        assertThat(preview.nodes())
                .singleElement()
                .satisfies(
                        n -> {
                            assertThat(n.expectedStart()).isNull();
                            assertThat(n.expectedEnd()).isNull();
                        });
    }

    @Test
    void legacyScheduleNeverBecomesAnInheritedChecklist() {
        Detail graph = create("legacy-root", List.of(node("child", null, "legacy-child", WORKER)));
        String root = graph.task().id(), child = row(graph, "legacy-child").id();
        ChecklistContext dates = context(root, WORKER);
        tasks.plan(new SavePlan(List.of(root), Period.DAY, dates.today(), true), WORKER);
        tasks.plan(new SavePlan(List.of(root), Period.WEEK, dates.weekStart(), true), WORKER);
        assertThat(item(child, WORKER).todayPlans()).isEmpty();
        assertThat(item(child, WORKER).weekPlans()).isEmpty();
        assertThat(item(child, WORKER).history()).isEmpty();
        assertThat(personal("TODAY", dates.today(), Filter.PLANNED, 1, 100).getTotal()).isZero();
        assertThat(personal("WEEK", dates.today(), Filter.PLANNED, 1, 100).getTotal()).isZero();
        assertThat(personal("ALL", dates.today(), Filter.UNPLANNED, 1, 100).getList())
                .extracting(Row::id)
                .containsExactlyInAnyOrder(root, child);
        assertThat(employee(dates.today()).todayCount()).isZero();
        assertThat(employee(dates.today()).weekCount()).isZero();
        assertThat(planRows(child)).isZero();
    }

    private Detail mixedGraph(String prefix) {
        return create(
                prefix + "-root",
                List.of(
                        node("own", null, prefix + "-own", WORKER),
                        node("foreign", null, prefix + "-foreign", OTHER),
                        node("grandchild", "foreign", prefix + "-grandchild", WORKER)));
    }

    private Detail create(String title, List<NodeInput> children) {
        Detail result =
                tasks.create(
                        new Create(
                                node("root", null, title, WORKER),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key(),
                                children),
                        OWNER);
        roots.add(result.task().rootId());
        return result;
    }

    private NodeInput node(String id, String parent, String title, Long assignee) {
        return new NodeInput(
                id,
                parent,
                marker + title,
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
                null);
    }

    private Row row(Detail detail, String title) {
        return detail.nodes().stream()
                .filter(n -> n.title().equals(marker + title))
                .findFirst()
                .orElseThrow();
    }

    private ChecklistContext context(String id, long actor) {
        return tasks.checklistContext(new ContextQuery(List.of(id), Target.SELF), actor);
    }

    private ChecklistItem item(String id, long actor) {
        return context(id, actor).items().getFirst();
    }

    private ChecklistChange command(
            String id,
            long actor,
            ChecklistAction action,
            Period period,
            LocalDate date,
            List<String> planIds) {
        return new ChecklistChange(
                List.of(id),
                Target.SELF,
                action,
                period,
                date,
                planIds,
                Map.of(id, item(id, actor).version()),
                key());
    }

    private TaskPlanning.Result add(String id, long actor, Period period, LocalDate date) {
        return tasks.checklist(command(id, actor, ChecklistAction.ADD, period, date, null), actor);
    }

    private void remove(String id, long actor, Period period, LocalDate date, String planId) {
        tasks.checklist(
                command(id, actor, ChecklistAction.REMOVE, period, date, List.of(planId)), actor);
    }

    private void assertInherited(List<Plan> plans, String ancestor) {
        assertThat(plans)
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.inherited()).isTrue();
                            assertThat(p.id()).isNull();
                            assertThat(p.inheritedFromTaskId()).isEqualTo(ancestor);
                            assertThat(p.inheritedFromTitle()).startsWith(marker);
                            assertThat(p.canCancel()).isFalse();
                            assertThat(p.userId()).isEqualTo(WORKER);
                            assertThat(p.mode()).isEqualTo(Mode.CHECKLIST);
                            assertThat(p.active()).isTrue();
                        });
    }

    private Integer planRows(String id) {
        return jdbc.queryForObject(
                "select count(*) from public.nocode_task_plan where task_id=?", Integer.class, id);
    }

    private void assign(String id, long assignee) {
        tasks.assign(
                new Assign(
                        id,
                        tasks.detail(id, OWNER).task().revision(),
                        AssignmentMode.ASSIGNED,
                        assignee,
                        List.of(),
                        key(),
                        "继承回归改派"),
                OWNER);
    }

    private PageResult<Row> personal(
            String tab, LocalDate date, Filter filter, int page, int size) {
        return tasks.page(query("MINE", tab, date, filter, page, size), WORKER);
    }

    private Query query(
            String scope, String tab, LocalDate date, Filter filter, int page, int size) {
        return new Query(
                scope,
                tab,
                date,
                marker,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                page,
                size,
                null,
                null,
                null,
                null,
                "MINE".equals(scope) ? Scope.PERSONAL : Scope.TEAM,
                null,
                filter,
                Mode.CHECKLIST);
    }

    private TaskManagement.Employee employee(LocalDate date) {
        return tasks
                .managementEmployees(new TaskManagement.Employees(null, date, 1, 100), OWNER)
                .getList()
                .stream()
                .filter(e -> e.userId().equals(WORKER))
                .findFirst()
                .orElseThrow();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
