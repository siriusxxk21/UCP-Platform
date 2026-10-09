package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskPlanning;
import com.lingan.ucp.nocode.api.TaskPlanning.Target;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskPlanDO;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskCenterMapper;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskScheduling;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/** 开发库统一计划验收；仅创建和按精确 rootId 清理本类独立任务，不操作体验业务。 */
class TaskSchedulingIntegrationTest {
    private static final long OWNER = 10001L, WORKER = 21001L, OTHER = 21002L;
    private static final LocalDate DATE = LocalDate.of(2031, 3, 12);
    private final Set<String> roots = new LinkedHashSet<>();
    private final String marker = "schedule_" + UUID.randomUUID();
    private TaskCenterService tasks;

    @BeforeAll
    static void open() throws Exception {
        connect();
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
        AdminUserApi users = servicesContext.getBean(AdminUserApi.class);
        Mockito.when(users.getUser(Mockito.anyLong()))
                .thenAnswer(
                        i -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(i.getArgument(0));
                            user.setNickname("计划测试" + user.getId());
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
        roots.clear();
    }

    @Test
    void claimAndAssignmentEnterTodoWithoutSchedulingOrStarting() {
        Row open = create(null, OWNER).task();
        Row claimed = tasks.claim(new Claim(open.id(), open.revision(), key()), WORKER).task();
        assertThat(claimed.status()).isEqualTo("PENDING");
        assertThat(context(claimed.id(), WORKER, Target.SELF).plans()).isEmpty();
        assertThat(page("POOL", DATE, null, false, null, WORKER)).contains(claimed.id());
        assertThat(page("TODAY", DATE, null, false, null, WORKER)).doesNotContain(claimed.id());
        assertThat(claimed.actualStart()).isNull();
    }

    @Test
    void dateRangeProjectsAcrossDayWeekMonthAndCompletionRemainsVisible() {
        Row row = create(WORKER, OWNER).task();
        arrange(row.id(), WORKER, Target.SELF, Period.DAY, DATE, DATE.plusDays(2));
        for (String tab : List.of("TODAY", "WEEK", "MONTH"))
            assertThat(page(tab, DATE.plusDays(1), null, false, null, WORKER))
                    .containsExactly(row.id());
        assertThat(page("TODAY", DATE.minusDays(1), null, false, null, WORKER)).isEmpty();
        assertThat(page("TODAY", DATE.plusDays(3), null, false, null, WORKER)).isEmpty();
        Row before = tasks.detail(row.id(), WORKER).task();
        assertThat(before.expectedStart()).isNull();
        assertThat(before.expectedEnd()).isNull();
        transition(row.id(), Action.START, WORKER);
        transition(row.id(), Action.COMPLETE, WORKER);
        assertThat(page("TODAY", DATE, null, false, null, WORKER)).containsExactly(row.id());
        TaskPlanning.Item context = context(row.id(), WORKER, Target.SELF);
        assertThat(context.status()).isEqualTo("COMPLETED");
        assertThat(context.readOnly()).isTrue();
        assertThat(context.canArrange()).isFalse();
        assertThat(context.plans()).allSatisfy(p -> assertThat(p.canCancel()).isFalse());
        assertThatThrownBy(
                        () ->
                                tasks.plan(
                                        new SavePlan(List.of(row.id()), Period.DAY, DATE, false),
                                        WORKER))
                .hasMessageContaining("仅供查看");
        assertThatThrownBy(
                        () ->
                                cancel(
                                        row.id(),
                                        WORKER,
                                        Target.SELF,
                                        context.plans().getFirst().id()))
                .hasMessageContaining("已结束");
        assertThat(
                        page(
                                "TODAY",
                                DATE.plusDays(3),
                                TaskPlanning.Filter.CARRYOVER,
                                false,
                                null,
                                WORKER))
                .isEmpty();
    }

    @Test
    void dateRangeProjectsIntoBothMonthsWithoutDuplicatingPlanRows() {
        Row row = create(WORKER, OWNER).task();
        LocalDate from = LocalDate.of(2031, 3, 30), to = LocalDate.of(2031, 4, 2);
        arrange(row.id(), WORKER, Target.SELF, Period.DAY, from, to);
        assertThat(page("MONTH", from, null, false, null, WORKER)).containsExactly(row.id());
        assertThat(page("MONTH", to, null, false, null, WORKER)).containsExactly(row.id());
        assertThat(context(row.id(), WORKER, Target.SELF).plans()).hasSize(1);
    }

    @Test
    void coarseWeekAndMonthAreNotInventedDailyAppointments() {
        Row week = create(WORKER, OWNER).task();
        Row month = create(WORKER, OWNER).task();
        arrange(week.id(), WORKER, Target.SELF, Period.WEEK, DATE, null);
        arrange(month.id(), WORKER, Target.SELF, Period.MONTH, DATE, null);
        assertThat(page("TODAY", DATE, null, false, null, WORKER)).isEmpty();
        assertThat(page("TODAY", DATE, TaskPlanning.Filter.COARSE, false, null, WORKER))
                .containsExactlyInAnyOrder(week.id(), month.id());
        assertThat(page("WEEK", DATE, null, false, null, WORKER)).containsExactly(week.id());
        assertThat(page("WEEK", DATE, TaskPlanning.Filter.COARSE, false, null, WORKER))
                .containsExactly(month.id());
        assertThat(page("MONTH", DATE, null, false, null, WORKER))
                .containsExactlyInAnyOrder(week.id(), month.id());
    }

    @Test
    void managerDatesAreProtectedEvenWhenEmployeeUsesAssigneeTargetOrLegacyEndpoint() {
        Row row = create(WORKER, OWNER).task();
        historicalManagerPlan(row.id(), OWNER, Period.DAY, DATE, DATE.plusDays(1));
        TaskPlanning.Item employee = context(row.id(), WORKER, Target.ASSIGNEE);
        assertThat(employee.readOnly()).isTrue();
        assertThat(employee.canArrange()).isFalse();
        assertThat(employee.plans().getFirst().canCancel()).isFalse();
        assertThatThrownBy(
                        () ->
                                arrange(
                                        row.id(),
                                        WORKER,
                                        Target.ASSIGNEE,
                                        Period.DAY,
                                        DATE.plusDays(3),
                                        null))
                .hasMessageContaining("主管");
        assertThatThrownBy(
                        () ->
                                cancel(
                                        row.id(),
                                        WORKER,
                                        Target.ASSIGNEE,
                                        employee.plans().getFirst().id()))
                .hasMessageContaining("主管");
        assertThatThrownBy(
                        () ->
                                tasks.plan(
                                        new SavePlan(
                                                List.of(row.id()),
                                                Period.DAY,
                                                DATE,
                                                false,
                                                "ASSIGNEE"),
                                        WORKER))
                .hasMessageContaining("有效计划");
        // 旧端点不能用单日参数模糊定位日期区间；真正匹配主管单日安排时仍明确执行来源保护。
        historicalManagerPlan(row.id(), OWNER, Period.DAY, DATE, null);
        assertThatThrownBy(
                        () ->
                                tasks.plan(
                                        new SavePlan(
                                                List.of(row.id()),
                                                Period.DAY,
                                                DATE,
                                                false,
                                                "ASSIGNEE"),
                                        WORKER))
                .hasMessageContaining("主管");
        assertThat(context(row.id(), OWNER, Target.ASSIGNEE).plans()).hasSize(2);
    }

    @Test
    void employeeRefinesInsideManagerRangeAndCancelOnlyRemovesChosenOwnPlan() {
        Row row = create(WORKER, OWNER).task();
        historicalManagerPlan(row.id(), OWNER, Period.MONTH, DATE, null);
        String constraintId = context(row.id(), WORKER, Target.SELF).constraints().getFirst().id();
        arrange(row.id(), WORKER, Target.SELF, Period.DAY, DATE, DATE.plusDays(1));
        TaskPlanning.Item employee = context(row.id(), WORKER, Target.SELF);
        assertThat(employee.constraints()).extracting(Plan::id).containsExactly(constraintId);
        assertThat(employee.plans()).hasSize(2);
        assertThat(page("TODAY", DATE, TaskPlanning.Filter.COARSE, false, null, WORKER)).isEmpty();
        assertThatThrownBy(
                        () ->
                                arrange(
                                        row.id(),
                                        WORKER,
                                        Target.SELF,
                                        Period.DAY,
                                        DATE.withDayOfMonth(1).minusDays(1),
                                        DATE))
                .hasMessageContaining("范围");
        String ownId =
                employee.plans().stream()
                        .filter(p -> "SELF".equals(p.source()))
                        .findFirst()
                        .orElseThrow()
                        .id();
        cancel(row.id(), WORKER, Target.SELF, ownId);
        TaskPlanning.Item restored = context(row.id(), WORKER, Target.SELF);
        assertThat(restored.plans()).extracting(Plan::id).containsExactly(constraintId);
        assertThat(restored.history())
                .anySatisfy(
                        p -> {
                            assertThat(p.id()).isEqualTo(ownId);
                            assertThat(p.historyReason()).isEqualTo("CANCELLED");
                        });
    }

    @Test
    void managementRolesCanViewButCannotUseEitherLegacyEndpointToWriteEmployeePlans() {
        Row row = create(WORKER, OWNER).task();
        arrange(row.id(), WORKER, Target.SELF, Period.DAY, DATE, null);
        PermissionCommonApi permissions = servicesContext.getBean(PermissionCommonApi.class);
        for (int role = 0; role < 3; role++) {
            long actor = role == 0 ? OWNER : OTHER;
            Mockito.when(permissions.hasAnyRoles(OTHER, "super_admin")).thenReturn(role == 1);
            Mockito.when(permissions.hasAnyPermissions(OTHER, "nocode:task:manage-all"))
                    .thenReturn(role == 2);
            try {
                TaskPlanning.Item current = context(row.id(), actor, Target.ASSIGNEE);
                assertThat(current.plans()).hasSize(1);
                assertThat(current.canArrange()).isFalse();
                assertThat(current.canCancel()).isFalse();
                assertThat(current.readOnly()).isTrue();
                for (Target target : Target.values()) {
                    assertThatThrownBy(
                                    () -> arrange(row.id(), actor, target, Period.DAY, DATE, null))
                            .hasMessageContaining("本人");
                    assertThatThrownBy(
                                    () ->
                                            cancel(
                                                    row.id(),
                                                    actor,
                                                    target,
                                                    current.plans().getFirst().id()))
                            .hasMessageContaining("本人");
                    for (boolean include : List.of(true, false))
                        assertThatThrownBy(
                                        () ->
                                                tasks.plan(
                                                        new SavePlan(
                                                                List.of(row.id()),
                                                                Period.DAY,
                                                                DATE,
                                                                include,
                                                                target.name()),
                                                        actor))
                                .hasMessageContaining("本人");
                }
                assertThat(context(row.id(), WORKER, Target.SELF).version())
                        .isEqualTo(current.version());
                assertThat(tasks.detail(row.id(), actor).task().plans())
                        .singleElement()
                        .satisfies(p -> assertThat(p.canCancel()).isFalse());
            } finally {
                Mockito.when(permissions.hasAnyRoles(OTHER, "super_admin")).thenReturn(false);
                Mockito.when(permissions.hasAnyPermissions(OTHER, "nocode:task:manage-all"))
                        .thenReturn(false);
            }
        }
        assertThat(tasks.detail(row.id(), WORKER).task().plans())
                .singleElement()
                .satisfies(p -> assertThat(p.canCancel()).isFalse());
    }

    @Test
    void cancellationOfMissingOrStalePlanCannotSucceedAndBatchIsAtomic() {
        Row one = create(WORKER, OWNER).task(), two = create(WORKER, OWNER).task();
        assertThatThrownBy(
                        () ->
                                tasks.plan(
                                        new SavePlan(List.of(one.id()), Period.DAY, DATE, false),
                                        WORKER))
                .hasMessageContaining("没有");
        arrange(one.id(), WORKER, Target.SELF, Period.DAY, DATE, null);
        TaskPlanning.Item before = context(one.id(), WORKER, Target.SELF);
        Map<String, Integer> versions =
                Map.of(
                        one.id(),
                        before.version(),
                        two.id(),
                        context(two.id(), WORKER, Target.SELF).version());
        assertThatThrownBy(
                        () ->
                                tasks.schedule(
                                        new TaskPlanning.Change(
                                                List.of(one.id(), two.id()),
                                                Target.SELF,
                                                TaskPlanning.Action.CANCEL,
                                                null,
                                                null,
                                                null,
                                                List.of(before.plans().getFirst().id(), "missing"),
                                                versions),
                                        WORKER))
                .hasMessageContaining("有效计划");
        assertThat(context(one.id(), WORKER, Target.SELF).plans()).hasSize(1);
        assertThat(context(one.id(), WORKER, Target.SELF).version()).isEqualTo(before.version());
        assertThatThrownBy(
                        () ->
                                tasks.schedule(
                                        new TaskPlanning.Change(
                                                List.of(one.id()),
                                                Target.SELF,
                                                TaskPlanning.Action.CANCEL,
                                                null,
                                                null,
                                                null,
                                                List.of(),
                                                Map.of(one.id(), before.version())),
                                        WORKER))
                .hasMessageContaining("真实计划");
    }

    @Test
    void samePayloadIsUnchangedButStaleRevisionCannotOverwriteNewArrangement() {
        Row row = create(WORKER, OWNER).task();
        int old = context(row.id(), WORKER, Target.SELF).version();
        arrange(row.id(), WORKER, Target.SELF, Period.DAY, DATE, null);
        TaskPlanning.Item current = context(row.id(), WORKER, Target.SELF);
        TaskPlanning.Result replay = arrange(row.id(), WORKER, Target.SELF, Period.DAY, DATE, null);
        assertThat(replay.changed()).isEmpty();
        assertThat(replay.unchanged()).containsExactly(row.id());
        assertThat(context(row.id(), WORKER, Target.SELF).version()).isEqualTo(current.version());
        assertThatThrownBy(
                        () ->
                                tasks.schedule(
                                        new TaskPlanning.Change(
                                                List.of(row.id()),
                                                Target.SELF,
                                                TaskPlanning.Action.ARRANGE,
                                                Period.DAY,
                                                DATE.plusDays(1),
                                                null,
                                                null,
                                                Map.of(row.id(), old)),
                                        WORKER))
                .hasMessageContaining("已变化");
    }

    @Test
    void concurrentSchedulesAtSameVersionHaveOneWinner() throws Exception {
        Row row = create(WORKER, OWNER).task();
        int version = context(row.id(), WORKER, Target.SELF).version();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> concurrent(start, row.id(), version, DATE));
            Future<Boolean> second =
                    pool.submit(() -> concurrent(start, row.id(), version, DATE.plusDays(1)));
            start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS) ^ second.get(15, TimeUnit.SECONDS)).isTrue();
            assertThat(context(row.id(), WORKER, Target.SELF).plans()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void carryoverIsSeparateAndReassignmentKeepsHistoryWithoutMovingOldActivePlans() {
        Row row = create(WORKER, OWNER).task();
        arrange(row.id(), WORKER, Target.SELF, Period.DAY, DATE.minusDays(1), null);
        TaskPlanning.Item before = context(row.id(), WORKER, Target.SELF);
        assertThat(page("TODAY", DATE, null, false, null, WORKER)).isEmpty();
        assertThat(page("TODAY", DATE, TaskPlanning.Filter.CARRYOVER, false, null, WORKER))
                .containsExactly(row.id());
        Row current = tasks.detail(row.id(), OWNER).task();
        tasks.assign(
                new Assign(
                        row.id(),
                        current.revision(),
                        AssignmentMode.ASSIGNED,
                        OTHER,
                        List.of(),
                        key(),
                        null),
                OWNER);
        TaskPlanning.Item manager = context(row.id(), OWNER, Target.ASSIGNEE);
        assertThat(manager.version()).isGreaterThan(before.version());
        assertThat(manager.plans()).isEmpty();
        assertThat(manager.history())
                .anySatisfy(
                        p -> {
                            assertThat(p.id()).isEqualTo(before.plans().getFirst().id());
                            assertThat(p.historyReason()).isEqualTo("REASSIGNED");
                            assertThat(p.userId()).isEqualTo(WORKER);
                        });
        assertThat(page("POOL", DATE, null, false, null, WORKER)).isEmpty();
        assertThat(page("POOL", DATE, TaskPlanning.Filter.UNPLANNED, false, null, OTHER))
                .containsExactly(row.id());
    }

    @Test
    void teamQueryUsesRootManagementAuthorityNotEmployeeOrCallerSuppliedOwner() {
        Row own = create(WORKER, OWNER).task(), privateRoot = create(WORKER, OTHER).task();
        arrange(own.id(), WORKER, Target.SELF, Period.DAY, DATE, null);
        arrange(privateRoot.id(), WORKER, Target.SELF, Period.DAY, DATE, null);
        assertThat(page("TODAY", DATE, null, true, WORKER, OWNER)).containsExactly(own.id());
        assertThat(page("TODAY", DATE, null, true, WORKER, OTHER))
                .containsExactly(privateRoot.id());
        assertThat(page("TODAY", DATE, null, true, WORKER, WORKER)).isEmpty();
        assertThatThrownBy(
                        () ->
                                tasks.page(
                                        query(
                                                "MINE",
                                                "TODAY",
                                                DATE,
                                                null,
                                                TaskPlanning.Scope.TEAM,
                                                WORKER),
                                        WORKER))
                .hasMessageContaining("团队计划");
        assertThatThrownBy(
                        () ->
                                tasks.page(
                                        query(
                                                "MINE",
                                                "TODAY",
                                                DATE,
                                                null,
                                                TaskPlanning.Scope.PERSONAL,
                                                OTHER),
                                        WORKER))
                .hasMessageContaining("负责人");
        assertThatThrownBy(() -> context(privateRoot.id(), OWNER, Target.ASSIGNEE))
                .hasMessageContaining("权限");
    }

    @Test
    void plansDoNotCascadeOrRemoveTheRootStartGate() {
        Detail detail =
                tasks.create(
                        new Create(
                                node("whole", OWNER),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key(),
                                List.of(node("child", WORKER))),
                        OWNER);
        roots.add(detail.task().rootId());
        Row child =
                detail.nodes().stream().filter(n -> n.parentId() != null).findFirst().orElseThrow();
        arrange(detail.task().id(), OWNER, Target.SELF, Period.DAY, DATE, null);
        assertThat(context(child.id(), WORKER, Target.SELF).plans()).isEmpty();
        arrange(child.id(), WORKER, Target.SELF, Period.DAY, DATE, null);
        assertThat(context(child.id(), WORKER, Target.SELF).warnings())
                .anyMatch(w -> w.contains("上级"));
        assertThat(tasks.detail(child.id(), WORKER).task().canStart()).isFalse();
        assertThat(page("TODAY", DATE, null, false, null, OWNER))
                .containsExactly(detail.task().id());
        transition(detail.task().id(), Action.START, OWNER);
        assertThat(tasks.detail(child.id(), WORKER).task().canStart()).isTrue();
    }

    @Test
    void protectedManagerConstraintsSurviveAnOutOfRangeBatchFailure() {
        Row one = create(WORKER, OWNER).task(), two = create(WORKER, OWNER).task();
        historicalManagerPlan(two.id(), OWNER, Period.WEEK, DATE.plusWeeks(1), null);
        Map<String, Integer> versions =
                Map.of(
                        one.id(),
                        context(one.id(), WORKER, Target.SELF).version(),
                        two.id(),
                        context(two.id(), WORKER, Target.SELF).version());
        assertThatThrownBy(
                        () ->
                                tasks.schedule(
                                        new TaskPlanning.Change(
                                                List.of(one.id(), two.id()),
                                                Target.SELF,
                                                TaskPlanning.Action.ARRANGE,
                                                Period.DAY,
                                                DATE,
                                                null,
                                                null,
                                                versions),
                                        WORKER))
                .hasMessageContaining("范围");
        assertThat(context(one.id(), WORKER, Target.SELF).plans()).isEmpty();
        assertThat(context(one.id(), WORKER, Target.SELF).version())
                .isEqualTo(versions.get(one.id()));
        assertThat(context(two.id(), WORKER, Target.SELF).constraints()).hasSize(1);
    }

    @Test
    void employeeCanContactOnlyAnActiveVisibleManagerArranger() {
        PermissionCommonApi permissions = servicesContext.getBean(PermissionCommonApi.class);
        Mockito.when(permissions.hasAnyRoles(OTHER, "super_admin")).thenReturn(true);
        try {
            Row row = create(WORKER, OWNER).task();
            historicalManagerPlan(row.id(), OTHER, Period.DAY, DATE, null);
            Comment comment =
                    tasks.comment(
                            new AddComment(row.id(), null, "请调整到明日", List.of(OTHER), key()),
                            WORKER);
            assertThat(comment.mentionedUserIds()).containsExactly(OTHER);
            Mockito.when(permissions.hasAnyRoles(OTHER, "super_admin")).thenReturn(false);
            assertThatThrownBy(
                            () ->
                                    tasks.comment(
                                            new AddComment(
                                                    row.id(),
                                                    null,
                                                    "权限撤销后不可再通知",
                                                    List.of(OTHER),
                                                    key()),
                                            WORKER))
                    .hasMessage("@只能选择有权查看当前任务的成员");
            Mockito.when(permissions.hasAnyRoles(OTHER, "super_admin")).thenReturn(true);
            String planId = context(row.id(), OTHER, Target.ASSIGNEE).plans().getFirst().id();
            assertThatThrownBy(() -> cancel(row.id(), OTHER, Target.ASSIGNEE, planId))
                    .hasMessageContaining("本人");
            // 模拟升级前记录归档，仅触碰本类夹具；现接口不再允许主管代办。
            servicesContext
                    .getBean(TaskCenterMapper.class)
                    .archivePlan(planId, "CANCELLED", Long.toString(OTHER));
            assertThatThrownBy(
                            () ->
                                    tasks.comment(
                                            new AddComment(
                                                    row.id(),
                                                    null,
                                                    "不再是当前安排人",
                                                    List.of(OTHER),
                                                    key()),
                                            WORKER))
                    .hasMessage("@只能选择有权查看当前任务的成员");
            assertThat(tasks.detail(row.id(), WORKER).comments()).hasSize(1);
        } finally {
            Mockito.when(permissions.hasAnyRoles(OTHER, "super_admin")).thenReturn(false);
        }
    }

    /** 升级前主管排期夹具，只写本类任务，当前业务入口已禁止产生该来源。 */
    private void historicalManagerPlan(
            String id, long arranger, Period period, LocalDate date, LocalDate end) {
        assertThat(roots).contains(id);
        TaskScheduling.Range range = TaskScheduling.range(period, date, end);
        TaskPlanDO plan = new TaskPlanDO();
        plan.setId(key());
        plan.setTaskId(id);
        plan.setUserId(WORKER);
        plan.setPlanMode(TaskPlanning.Mode.SCHEDULE.name());
        plan.setPeriod(period.name());
        plan.setPlanDate(range.date());
        plan.setEndDate(range.endDate());
        plan.setArrangedById(arranger);
        plan.setSource("MANAGER");
        plan.setArrangedAt(LocalDateTime.now());
        servicesContext.getBean(TaskCenterMapper.class).savePlan(plan, Long.toString(arranger));
    }

    private boolean concurrent(CountDownLatch start, String id, int version, LocalDate date)
            throws Exception {
        start.await();
        try {
            tasks.schedule(
                    new TaskPlanning.Change(
                            List.of(id),
                            Target.SELF,
                            TaskPlanning.Action.ARRANGE,
                            Period.DAY,
                            date,
                            null,
                            null,
                            Map.of(id, version)),
                    WORKER);
            return true;
        } catch (com.lingan.ucp.framework.common.exception.ServiceException ex) {
            return false;
        }
    }

    private TaskPlanning.Item context(String id, long actor, Target target) {
        return tasks.planContext(new TaskPlanning.ContextQuery(List.of(id), target), actor)
                .items()
                .getFirst();
    }

    private TaskPlanning.Result arrange(
            String id, long actor, Target target, Period period, LocalDate date, LocalDate end) {
        return tasks.schedule(
                new TaskPlanning.Change(
                        List.of(id),
                        target,
                        TaskPlanning.Action.ARRANGE,
                        period,
                        date,
                        end,
                        null,
                        Map.of(id, context(id, actor, target).version())),
                actor);
    }

    private void cancel(String id, long actor, Target target, String planId) {
        tasks.schedule(
                new TaskPlanning.Change(
                        List.of(id),
                        target,
                        TaskPlanning.Action.CANCEL,
                        null,
                        null,
                        null,
                        List.of(planId),
                        Map.of(id, context(id, actor, target).version())),
                actor);
    }

    private Detail create(Long assignee, long creator) {
        Detail detail =
                tasks.create(
                        new Create(
                                node(key(), assignee), null, null, null, null, null, null, key()),
                        creator);
        roots.add(detail.task().rootId());
        return detail;
    }

    private NodeInput node(String id, Long assignee) {
        return new NodeInput(
                id,
                null,
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
                assignee == null ? AssignmentMode.OPEN : AssignmentMode.ASSIGNED,
                List.of());
    }

    private List<String> page(
            String tab,
            LocalDate date,
            TaskPlanning.Filter filter,
            boolean team,
            Long assignee,
            long actor) {
        return tasks
                .page(
                        query(
                                team ? "MANAGE" : "MINE",
                                tab,
                                date,
                                filter,
                                team ? TaskPlanning.Scope.TEAM : TaskPlanning.Scope.PERSONAL,
                                assignee),
                        actor)
                .getList()
                .stream()
                .map(Row::id)
                .toList();
    }

    private Query query(
            String scope,
            String tab,
            LocalDate date,
            TaskPlanning.Filter filter,
            TaskPlanning.Scope planScope,
            Long assignee) {
        return new Query(
                scope, tab, date, marker, null, null, null, null, null, null, null, null, 1, 100,
                null, null, null, null, planScope, assignee, filter);
    }

    private void transition(String id, Action action, long actor) {
        tasks.transition(
                new Transition(id, tasks.detail(id, actor).task().revision(), action, null, key()),
                actor);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
