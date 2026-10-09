package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskCenter.Action;
import com.lingan.ucp.nocode.api.TaskPlanning;
import com.lingan.ucp.nocode.api.TaskPlanning.*;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskPlanDO;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskCenterMapper;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/** 真实开发库清单验证；只操作 UUID 前缀并记录精确根身份的自有任务。 */
class TaskChecklistIntegrationTest {
    private static final long OWNER = 10001L, WORKER = 21001L, OTHER = 21002L;
    private final Set<String> roots = new LinkedHashSet<>();
    private final String marker = "checklist_" + UUID.randomUUID();
    private TaskCenterService tasks;
    private TaskCenterMapper store;

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
        store = servicesContext.getBean(TaskCenterMapper.class);
        IMsgSendService messages = servicesContext.getBean(IMsgSendService.class);
        Mockito.reset(messages);
        Mockito.when(messages.send(Mockito.any())).thenReturn(999L);
        AdminUserApi users = servicesContext.getBean(AdminUserApi.class);
        Mockito.when(users.getUser(Mockito.anyLong()))
                .thenAnswer(
                        i -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(i.getArgument(0));
                            user.setNickname("清单测试" + user.getId());
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
    void addingDayKeepsExistingWeekAndRemovingEitherNeverRemovesTheOther() {
        Row row = create(WORKER, OWNER).task();
        add(row.id(), WORKER, Target.SELF, Period.WEEK);
        String weekId = item(row.id(), WORKER, Target.SELF).weekPlans().getFirst().id();
        add(row.id(), WORKER, Target.SELF, Period.DAY);
        ChecklistItem current = item(row.id(), WORKER, Target.SELF);
        assertThat(current.weekPlans()).extracting(Plan::id).containsExactly(weekId);
        remove(row.id(), WORKER, Target.SELF, Period.DAY, current.todayPlans().getFirst().id());
        assertThat(item(row.id(), WORKER, Target.SELF).weekPlans()).hasSize(1);
        add(row.id(), WORKER, Target.SELF, Period.DAY);
        remove(row.id(), WORKER, Target.SELF, Period.WEEK, weekId);
        assertThat(item(row.id(), WORKER, Target.SELF).todayPlans()).hasSize(1);
        assertThat(page("TODAY", today(), null, false, WORKER)).containsExactly(row.id());
        assertThat(page("WEEK", today(), null, false, WORKER)).isEmpty();
        assertThat(page("TODO", today(), Filter.UNPLANNED, false, WORKER)).isEmpty();
        assertThat(item(row.id(), WORKER, Target.SELF).history())
                .filteredOn(p -> "REMOVED_FROM_CHECKLIST".equals(p.historyReason()))
                .hasSize(2);
    }

    @Test
    void dayAutomaticallyAddsWeekOnceWithoutChangingTaskFacts() {
        Row row = create(WORKER, OWNER).task();
        int version = item(row.id(), WORKER, Target.SELF).version();
        add(row.id(), WORKER, Target.SELF, Period.DAY);
        ChecklistItem current = item(row.id(), WORKER, Target.SELF);
        assertThat(current.todayPlans()).hasSize(1);
        assertThat(current.weekPlans()).hasSize(1);
        assertThat(current.version()).isEqualTo(version + 1);
        Row after = tasks.detail(row.id(), WORKER).task();
        assertThat(after.status()).isEqualTo(row.status());
        assertThat(after.assigneeId()).isEqualTo(row.assigneeId());
        assertThat(after.revision()).isEqualTo(row.revision());
        assertThat(after.expectedStart()).isEqualTo(row.expectedStart());
        assertThat(after.expectedEnd()).isEqualTo(row.expectedEnd());
        assertThat(after.actualStart()).isNull();
        assertThat(after.actualEnd()).isNull();
    }

    @Test
    void nextWeekHasIndependentMembershipReplayAndCurrentRemovalCapabilities() {
        Row row = create(WORKER, OWNER).task();
        ChecklistContext before = context(row.id(), WORKER, Target.SELF);
        assertThat(before.nextWeekStart()).isEqualTo(before.weekStart().plusWeeks(1));
        assertThat(before.nextWeekEnd()).isEqualTo(before.weekEnd().plusWeeks(1));
        ChecklistChange nextWeek =
                datedCommand(row.id(), ChecklistAction.ADD, before.nextWeekStart(), null);
        TaskPlanning.Result added = tasks.checklist(nextWeek, WORKER);
        assertThat(tasks.checklist(nextWeek, WORKER)).isEqualTo(added);
        ChecklistItem current = item(row.id(), WORKER, Target.SELF);
        assertThat(current.todayPlans()).isEmpty();
        assertThat(current.weekPlans()).isEmpty();
        assertThat(current.history()).isEmpty();
        assertThat(current.nextWeekPlans())
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.date()).isEqualTo(before.nextWeekStart());
                            assertThat(p.endDate()).isEqualTo(before.nextWeekEnd());
                            assertThat(p.canCancel()).isTrue();
                        });
        assertThat(tasks.detail(row.id(), WORKER).task().plans())
                .singleElement()
                .satisfies(p -> assertThat(p.canCancel()).isTrue());
        assertThat(item(row.id(), OWNER, Target.ASSIGNEE).nextWeekPlans())
                .singleElement()
                .satisfies(p -> assertThat(p.canCancel()).isFalse());
        assertThat(page("WEEK", before.nextWeekStart(), Filter.PLANNED, false, WORKER))
                .containsExactly(row.id());
        assertThat(page("WEEK", before.weekStart(), Filter.PLANNED, false, WORKER)).isEmpty();
        assertThat(page("ALL", today(), Filter.UNPLANNED, false, WORKER)).isEmpty();
        // 管理视图的未安排仍沿用本周口径，不因个人新增下周能力静默改变。
        assertThat(page("ALL", today(), Filter.UNPLANNED, true, OWNER)).containsExactly(row.id());
        assertThat(tasks.detail(row.id(), WORKER).events())
                .anySatisfy(e -> assertThat(e.note()).isEqualTo("加入下周清单"));
        add(row.id(), WORKER, Target.SELF, Period.DAY);
        ChecklistChange remove =
                datedCommand(
                        row.id(),
                        ChecklistAction.REMOVE,
                        before.nextWeekStart(),
                        List.of(current.nextWeekPlans().getFirst().id()));
        TaskPlanning.Result removed = tasks.checklist(remove, WORKER);
        assertThat(tasks.checklist(remove, WORKER)).isEqualTo(removed);
        ChecklistItem after = item(row.id(), WORKER, Target.SELF);
        assertThat(after.nextWeekPlans()).isEmpty();
        assertThat(after.todayPlans()).hasSize(1);
        assertThat(after.weekPlans()).hasSize(1);
        assertThat(after.history())
                .singleElement()
                .satisfies(p -> assertThat(p.historyReason()).isEqualTo("REMOVED_FROM_CHECKLIST"));
        assertThat(tasks.detail(row.id(), WORKER).task().actualStart()).isNull();
    }

    @Test
    void nextWeekKeepsDateRevisionIdentityAndAssigneeBoundaries() {
        Row row = create(WORKER, OWNER).task();
        ChecklistContext before = context(row.id(), WORKER, Target.SELF);
        ChecklistChange next =
                datedCommand(row.id(), ChecklistAction.ADD, before.nextWeekStart(), null);
        assertThatThrownBy(() -> tasks.checklist(next, OWNER)).hasMessageContaining("本人");
        assertThatThrownBy(() -> tasks.checklist(next, OTHER)).hasMessageContaining("权限");
        for (LocalDate invalidDate :
                List.of(
                        before.weekStart().minusWeeks(1),
                        before.nextWeekStart().plusDays(1),
                        before.nextWeekStart().plusWeeks(1))) {
            assertThatThrownBy(
                            () ->
                                    tasks.checklist(
                                            datedCommand(
                                                    row.id(),
                                                    ChecklistAction.ADD,
                                                    invalidDate,
                                                    null),
                                            WORKER))
                    .hasMessageContaining("日期已变化");
        }
        tasks.checklist(next, WORKER);
        ChecklistChange stale =
                new ChecklistChange(
                        next.ids(),
                        next.target(),
                        next.action(),
                        next.period(),
                        next.date(),
                        null,
                        next.expectedVersions(),
                        key());
        assertThatThrownBy(() -> tasks.checklist(stale, WORKER)).hasMessageContaining("已变化");
        ChecklistChange reused =
                new ChecklistChange(
                        next.ids(),
                        next.target(),
                        next.action(),
                        next.period(),
                        before.weekStart(),
                        null,
                        next.expectedVersions(),
                        next.requestKey());
        assertThatThrownBy(() -> tasks.checklist(reused, WORKER)).hasMessageContaining("标识");
        transition(row.id(), Action.START, WORKER);
        transition(row.id(), Action.COMPLETE, WORKER);
        ChecklistItem completed = item(row.id(), WORKER, Target.SELF);
        assertThat(completed.nextWeekPlans())
                .singleElement()
                .satisfies(p -> assertThat(p.canCancel()).isFalse());
        assertThatThrownBy(
                        () ->
                                tasks.checklist(
                                        datedCommand(
                                                row.id(),
                                                ChecklistAction.REMOVE,
                                                before.nextWeekStart(),
                                                List.of(completed.nextWeekPlans().getFirst().id())),
                                        WORKER))
                .hasMessageContaining("待验收");
    }

    @Test
    void personalUnplannedExcludesAnyEffectiveChecklistButNotPastOrLegacySchedules() {
        Row past = create(WORKER, OWNER).task();
        Row future = create(WORKER, OWNER).task();
        Row legacy = create(WORKER, OWNER).task();
        LocalDate week = context(past.id(), WORKER, Target.SELF).weekStart();
        membership(past.id(), Period.WEEK, week.minusWeeks(1), WORKER, "SELF");
        membership(future.id(), Period.WEEK, week.plusWeeks(3), WORKER, "SELF");
        tasks.plan(new SavePlan(List.of(legacy.id()), Period.WEEK, week, true), WORKER);
        assertThat(page("ALL", today(), Filter.UNPLANNED, false, WORKER))
                .containsExactlyInAnyOrder(past.id(), legacy.id());
    }

    @Test
    void sameKeyRecoversOriginalResultAndNewKeyRequiresCurrentVersion() {
        Row row = create(WORKER, OWNER).task();
        ChecklistChange first =
                command(row.id(), WORKER, Target.SELF, ChecklistAction.ADD, Period.DAY, null);
        TaskPlanning.Result result = tasks.checklist(first, WORKER);
        int events = tasks.detail(row.id(), WORKER).events().size();
        assertThat(tasks.checklist(first, WORKER)).isEqualTo(result);
        assertThat(tasks.detail(row.id(), WORKER).events()).hasSize(events);
        assertThat(add(row.id(), WORKER, Target.SELF, Period.DAY).unchanged())
                .containsExactly(row.id());
        assertThat(item(row.id(), WORKER, Target.SELF).version())
                .isEqualTo(first.expectedVersions().get(row.id()) + 1);
        ChecklistChange stale =
                new ChecklistChange(
                        first.ids(),
                        first.target(),
                        first.action(),
                        first.period(),
                        first.date(),
                        null,
                        first.expectedVersions(),
                        key());
        assertThatThrownBy(() -> tasks.checklist(stale, WORKER)).hasMessageContaining("已变化");
        ChecklistChange reused =
                new ChecklistChange(
                        first.ids(),
                        first.target(),
                        first.action(),
                        Period.WEEK,
                        context(row.id(), WORKER, Target.SELF).weekStart(),
                        null,
                        first.expectedVersions(),
                        first.requestKey());
        assertThatThrownBy(() -> tasks.checklist(reused, WORKER)).hasMessageContaining("标识");
    }

    @Test
    void removalRetryRecoversButMissingOrWrongPeriodCannotReportSuccess() {
        Row row = create(WORKER, OWNER).task();
        add(row.id(), WORKER, Target.SELF, Period.DAY);
        String id = item(row.id(), WORKER, Target.SELF).todayPlans().getFirst().id();
        ChecklistChange remove =
                command(
                        row.id(),
                        WORKER,
                        Target.SELF,
                        ChecklistAction.REMOVE,
                        Period.DAY,
                        List.of(id));
        TaskPlanning.Result result = tasks.checklist(remove, WORKER);
        assertThat(tasks.checklist(remove, WORKER)).isEqualTo(result);
        assertThatThrownBy(() -> remove(row.id(), WORKER, Target.SELF, Period.DAY, id))
                .hasMessageContaining("没有所选");
        String weekId = item(row.id(), WORKER, Target.SELF).weekPlans().getFirst().id();
        assertThatThrownBy(() -> remove(row.id(), WORKER, Target.SELF, Period.DAY, weekId))
                .hasMessageContaining("没有所选");
        assertThatThrownBy(
                        () ->
                                tasks.checklist(
                                        command(
                                                row.id(),
                                                WORKER,
                                                Target.SELF,
                                                ChecklistAction.REMOVE,
                                                Period.DAY,
                                                List.of()),
                                        WORKER))
                .hasMessageContaining("真实清单");
        assertThat(item(row.id(), WORKER, Target.SELF).weekPlans()).hasSize(1);
    }

    @Test
    void oldSchedulesStaySeparateAndCannotCancelChecklistOrConstrainIt() {
        Row row = create(WORKER, OWNER).task();
        tasks.plan(
                new SavePlan(List.of(row.id()), Period.DAY, today().plusDays(20), true, "ASSIGNEE"),
                WORKER);
        add(row.id(), WORKER, Target.SELF, Period.DAY);
        ChecklistItem current = item(row.id(), WORKER, Target.SELF);
        assertThat(current.history())
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.mode()).isEqualTo(Mode.SCHEDULE);
                            assertThat(p.active()).isTrue();
                            assertThat(p.canCancel()).isFalse();
                        });
        assertThat(
                        tasks.planContext(
                                        new ContextQuery(List.of(row.id()), Target.ASSIGNEE), OWNER)
                                .items()
                                .getFirst()
                                .plans())
                .hasSize(1);
        assertThatThrownBy(
                        () ->
                                tasks.plan(
                                        new SavePlan(List.of(row.id()), Period.DAY, today(), false),
                                        WORKER))
                .hasMessageContaining("没有");
        assertThatThrownBy(
                        () ->
                                remove(
                                        row.id(),
                                        WORKER,
                                        Target.SELF,
                                        Period.DAY,
                                        current.history().getFirst().id()))
                .hasMessageContaining("没有所选");
        assertThat(page("TODAY", today().plusDays(20), null, false, WORKER)).isEmpty();
        Query old =
                new Query(
                        "MINE",
                        "TODAY",
                        today().plusDays(20),
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
        assertThat(tasks.page(old, WORKER).getList()).extracting(Row::id).containsExactly(row.id());
    }

    @Test
    void workerCanRemoveHistoricalManagerMembershipWithoutChangingItsAuditSource() {
        Row row = create(WORKER, OWNER).task();
        String original = membership(row.id(), Period.DAY, today(), OWNER, "MANAGER");
        ChecklistItem manager = item(row.id(), WORKER, Target.SELF);
        assertThat(manager.canAdd()).isTrue();
        assertThat(manager.todayPlans().getFirst().canCancel()).isTrue();
        assertThat(tasks.detail(row.id(), WORKER).task().plans().getFirst().canCancel()).isTrue();
        add(row.id(), WORKER, Target.SELF, Period.DAY);
        ChecklistItem own = item(row.id(), WORKER, Target.SELF);
        assertThat(own.todayPlans()).extracting(Plan::id).containsExactly(original);
        assertThat(own.weekPlans()).hasSize(1);
        remove(row.id(), WORKER, Target.ASSIGNEE, Period.DAY, original);
        ChecklistItem removed = item(row.id(), WORKER, Target.SELF);
        assertThat(removed.todayPlans()).isEmpty();
        assertThat(removed.history())
                .singleElement()
                .satisfies(
                        p -> {
                            assertThat(p.source()).isEqualTo("MANAGER");
                            assertThat(p.arrangedById()).isEqualTo(OWNER);
                            assertThat(p.historyReason()).isEqualTo("REMOVED_FROM_CHECKLIST");
                        });
        add(row.id(), WORKER, Target.SELF, Period.DAY);
        assertThat(item(row.id(), WORKER, Target.SELF).todayPlans())
                .singleElement()
                .satisfies(p -> assertThat(p.source()).isEqualTo("SELF"));
    }

    @Test
    void creatorSuperAdminAndManageAllCanReadButNeverModifyEmployeeChecklist() {
        Row row = create(WORKER, OWNER).task();
        add(row.id(), WORKER, Target.SELF, Period.DAY);
        PermissionCommonApi permissions = servicesContext.getBean(PermissionCommonApi.class);
        for (int role = 0; role < 3; role++) {
            long actor = role == 0 ? OWNER : OTHER;
            Mockito.when(permissions.hasAnyRoles(OTHER, "super_admin")).thenReturn(role == 1);
            Mockito.when(permissions.hasAnyPermissions(OTHER, "nocode:task:manage-all"))
                    .thenReturn(role == 2);
            try {
                ChecklistItem current = item(row.id(), actor, Target.ASSIGNEE);
                assertThat(current.canAdd()).isFalse();
                assertThat(current.todayPlans())
                        .hasSize(1)
                        .allSatisfy(p -> assertThat(p.canCancel()).isFalse());
                Row view = tasks.detail(row.id(), actor).task();
                assertThat(view.canPlan()).isFalse();
                assertThat(view.plans())
                        .hasSize(2)
                        .allSatisfy(p -> assertThat(p.canCancel()).isFalse());
                for (Target target : Target.values()) {
                    assertThatThrownBy(() -> add(row.id(), actor, target, Period.DAY))
                            .hasMessageContaining("本人");
                    assertThatThrownBy(
                                    () ->
                                            remove(
                                                    row.id(),
                                                    actor,
                                                    target,
                                                    Period.DAY,
                                                    current.todayPlans().getFirst().id()))
                            .hasMessageContaining("本人");
                }
                assertThat(item(row.id(), WORKER, Target.SELF).version())
                        .isEqualTo(current.version());
            } finally {
                Mockito.when(permissions.hasAnyRoles(OTHER, "super_admin")).thenReturn(false);
                Mockito.when(permissions.hasAnyPermissions(OTHER, "nocode:task:manage-all"))
                        .thenReturn(false);
            }
        }
        // 角色不影响本人维护自己的任务，且新记录不会再产生 MANAGER 来源。
        Mockito.when(permissions.hasAnyRoles(WORKER, "super_admin")).thenReturn(true);
        try {
            remove(
                    row.id(),
                    WORKER,
                    Target.SELF,
                    Period.DAY,
                    item(row.id(), WORKER, Target.SELF).todayPlans().getFirst().id());
            add(row.id(), WORKER, Target.ASSIGNEE, Period.DAY);
            assertThat(item(row.id(), WORKER, Target.SELF).todayPlans())
                    .singleElement()
                    .satisfies(p -> assertThat(p.source()).isEqualTo("SELF"));
        } finally {
            Mockito.when(permissions.hasAnyRoles(WORKER, "super_admin")).thenReturn(false);
        }
    }

    @Test
    void ancestorCanReadChildChecklistButCannotAddOrRemoveIt() {
        // 旧实例参与者原本可查看整树；新协议的节点可见边界不在本次放宽。
        NodeInput legacyRoot =
                new NodeInput(
                        "whole",
                        null,
                        marker + "whole",
                        null,
                        OTHER,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        null,
                        null);
        Detail detail = create(legacyRoot, List.of(node("child", WORKER, null)), OWNER);
        String child =
                detail.nodes().stream()
                        .filter(n -> n.parentId() != null)
                        .findFirst()
                        .orElseThrow()
                        .id();
        add(child, WORKER, Target.SELF, Period.DAY);
        ChecklistItem context = item(child, OTHER, Target.ASSIGNEE);
        assertThat(context.canAdd()).isFalse();
        assertThat(context.todayPlans())
                .singleElement()
                .satisfies(p -> assertThat(p.canCancel()).isFalse());
        assertThatThrownBy(() -> add(child, OTHER, Target.ASSIGNEE, Period.WEEK))
                .hasMessageContaining("本人");
        assertThatThrownBy(
                        () ->
                                remove(
                                        child,
                                        OTHER,
                                        Target.ASSIGNEE,
                                        Period.DAY,
                                        context.todayPlans().getFirst().id()))
                .hasMessageContaining("本人");
    }

    @Test
    void previousPeriodsOnlyAppearAsCarryoverAndNeverRollForwardAutomatically() {
        Row row = create(WORKER, OWNER).task();
        past(row.id(), Period.DAY, today().minusDays(1));
        past(
                row.id(),
                Period.WEEK,
                context(row.id(), WORKER, Target.SELF).weekStart().minusWeeks(1));
        assertThat(item(row.id(), WORKER, Target.SELF).history())
                .hasSize(2)
                .allSatisfy(p -> assertThat(p.canCancel()).isFalse());
        assertThat(page("TODAY", today(), null, false, WORKER)).isEmpty();
        assertThat(page("WEEK", today(), null, false, WORKER)).isEmpty();
        assertThat(page("TODAY", today(), Filter.CARRYOVER, false, WORKER))
                .containsExactly(row.id());
        assertThat(page("WEEK", today(), Filter.CARRYOVER, false, WORKER))
                .containsExactly(row.id());
        add(row.id(), WORKER, Target.SELF, Period.DAY);
        assertThat(page("TODAY", today(), Filter.CARRYOVER, false, WORKER)).isEmpty();
        assertThat(page("WEEK", today(), Filter.CARRYOVER, false, WORKER)).isEmpty();
        assertThat(store.planHistory(row.id())).hasSize(4);
        assertThat(page("TODAY", today().plusDays(1), null, false, WORKER)).isEmpty();
        assertThat(page("WEEK", today().plusWeeks(1), null, false, WORKER)).isEmpty();
    }

    @Test
    void completedAndPendingAcceptanceKeepMembershipButMakeAllChangesReadOnly() {
        for (Long acceptor : Arrays.asList(null, OTHER)) {
            Detail detail = create(node(key(), WORKER, acceptor), List.of(), OWNER);
            String id = detail.task().id();
            add(id, WORKER, Target.SELF, Period.DAY);
            transition(id, Action.START, WORKER);
            transition(id, Action.COMPLETE, WORKER);
            ChecklistItem current = item(id, WORKER, Target.SELF);
            assertThat(current.status())
                    .isEqualTo(acceptor == null ? "COMPLETED" : "PENDING_ACCEPTANCE");
            assertThat(current.canAdd()).isFalse();
            assertThat(current.todayPlans()).allSatisfy(p -> assertThat(p.canCancel()).isFalse());
            assertThat(page("TODAY", today(), null, false, WORKER)).contains(id);
            assertThat(tasks.detail(id, WORKER).task().plans())
                    .allSatisfy(p -> assertThat(p.canCancel()).isFalse());
            assertThatThrownBy(() -> add(id, WORKER, Target.SELF, Period.WEEK))
                    .hasMessageContaining("待验收");
            assertThatThrownBy(
                            () ->
                                    remove(
                                            id,
                                            WORKER,
                                            Target.SELF,
                                            Period.DAY,
                                            current.todayPlans().getFirst().id()))
                    .hasMessageContaining("待验收");
        }
    }

    @Test
    void reassignmentArchivesAllModesWithoutLeakingPreviousEmployeeHistory() {
        Row row = create(WORKER, OWNER).task();
        add(row.id(), WORKER, Target.SELF, Period.DAY);
        tasks.plan(new SavePlan(List.of(row.id()), Period.DAY, today(), true), WORKER);
        Row latest = tasks.detail(row.id(), OWNER).task();
        tasks.assign(
                new Assign(
                        row.id(),
                        latest.revision(),
                        AssignmentMode.ASSIGNED,
                        OTHER,
                        List.of(),
                        key(),
                        null),
                OWNER);
        ChecklistItem manager = item(row.id(), OWNER, Target.ASSIGNEE);
        assertThat(manager.todayPlans()).isEmpty();
        assertThat(manager.weekPlans()).isEmpty();
        assertThat(manager.history())
                .hasSize(3)
                .allSatisfy(
                        p -> {
                            assertThat(p.historyReason()).isEqualTo("REASSIGNED");
                            assertThat(p.userId()).isEqualTo(WORKER);
                        });
        assertThat(item(row.id(), OTHER, Target.SELF).history()).isEmpty();
        assertThat(page("TODAY", today(), null, false, WORKER)).isEmpty();
        assertThat(page("TODAY", today(), null, false, OTHER)).isEmpty();
        assertThatThrownBy(() -> context(row.id(), WORKER, Target.SELF)).hasMessageContaining("权限");
    }

    @Test
    void teamReadsSameRowsWithinExistingManagementAuthority() {
        Row managed = create(WORKER, OWNER).task(), hidden = create(WORKER, OTHER).task();
        add(managed.id(), WORKER, Target.SELF, Period.DAY);
        add(hidden.id(), WORKER, Target.SELF, Period.DAY);
        assertThat(page("TODAY", today(), null, true, OWNER)).containsExactly(managed.id());
        assertThat(page("TODAY", today(), null, true, OTHER)).containsExactly(hidden.id());
        assertThat(page("TODAY", today(), null, true, WORKER)).isEmpty();
        assertThatThrownBy(() -> context(hidden.id(), OWNER, Target.ASSIGNEE))
                .hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                tasks.page(
                                        query("MINE", "TODAY", today(), null, Scope.TEAM, WORKER),
                                        WORKER))
                .hasMessageContaining("团队");
        assertThatThrownBy(
                        () ->
                                tasks.page(
                                        query(
                                                "MINE",
                                                "TODAY",
                                                today(),
                                                null,
                                                Scope.PERSONAL,
                                                OTHER),
                                        WORKER))
                .hasMessageContaining("负责人");
    }

    @Test
    void parentWeeklyAndChildDailyStayIndependentAndNoPlanIsRequiredToStart() {
        Detail detail =
                create(node("whole", WORKER, null), List.of(node("child", WORKER, null)), OWNER);
        String parent = detail.task().id();
        String child =
                detail.nodes().stream()
                        .filter(n -> n.parentId() != null)
                        .findFirst()
                        .orElseThrow()
                        .id();
        add(parent, WORKER, Target.SELF, Period.WEEK);
        add(child, WORKER, Target.SELF, Period.DAY);
        assertThat(item(parent, WORKER, Target.SELF).todayPlans()).isEmpty();
        assertThat(item(child, WORKER, Target.SELF).weekPlans()).hasSize(1);
        assertThat(tasks.detail(child, WORKER).task().canStart()).isFalse();
        remove(
                parent,
                WORKER,
                Target.SELF,
                Period.WEEK,
                item(parent, WORKER, Target.SELF).weekPlans().getFirst().id());
        assertThat(item(child, WORKER, Target.SELF).todayPlans()).hasSize(1);
        transition(parent, Action.START, WORKER);
        assertThat(tasks.detail(child, WORKER).task().canStart()).isTrue();
        Row free = create(WORKER, OWNER).task();
        transition(free.id(), Action.START, WORKER);
        assertThat(item(free.id(), WORKER, Target.SELF).todayPlans()).isEmpty();
    }

    @Test
    void staleDateAndMonthCannotCreateHiddenHistoricalOrMonthlyChecklist() {
        Row row = create(WORKER, OWNER).task();
        ChecklistChange current =
                command(row.id(), WORKER, Target.SELF, ChecklistAction.ADD, Period.DAY, null);
        for (LocalDate date : List.of(today().minusDays(1), today().plusDays(1))) {
            ChecklistChange bad =
                    new ChecklistChange(
                            current.ids(),
                            current.target(),
                            current.action(),
                            current.period(),
                            date,
                            null,
                            current.expectedVersions(),
                            key());
            assertThatThrownBy(() -> tasks.checklist(bad, WORKER)).hasMessageContaining("日期已变化");
        }
        ChecklistChange monthly =
                new ChecklistChange(
                        current.ids(),
                        current.target(),
                        current.action(),
                        Period.MONTH,
                        today(),
                        null,
                        current.expectedVersions(),
                        key());
        assertThatThrownBy(() -> tasks.checklist(monthly, WORKER)).hasMessageContaining("今日和本周");
        assertThatThrownBy(() -> page("MONTH", today(), null, false, WORKER))
                .hasMessageContaining("今日和本周");
        assertThatThrownBy(() -> page("TODAY", today(), Filter.COARSE, false, WORKER))
                .hasMessageContaining("今日和本周");
        assertThat(store.planHistory(row.id())).isEmpty();
    }

    @Test
    void failedMixedBatchRollsBackMembershipRevisionAndReceipts() {
        Row one = create(WORKER, OWNER).task(), two = create(null, OWNER).task();
        Map<String, Integer> versions =
                Map.of(
                        one.id(),
                        item(one.id(), OWNER, Target.ASSIGNEE).version(),
                        two.id(),
                        item(two.id(), OWNER, Target.ASSIGNEE).version());
        ChecklistChange batch =
                new ChecklistChange(
                        List.of(one.id(), two.id()),
                        Target.ASSIGNEE,
                        ChecklistAction.ADD,
                        Period.DAY,
                        today(),
                        null,
                        versions,
                        key());
        assertThatThrownBy(() -> tasks.checklist(batch, OWNER)).hasMessageContaining("本人");
        assertThat(store.planHistory(one.id())).isEmpty();
        assertThat(item(one.id(), OWNER, Target.ASSIGNEE).version())
                .isEqualTo(versions.get(one.id()));
        assertThat(store.requested(Long.toString(OWNER), batch.requestKey())).isNull();
        add(one.id(), WORKER, Target.SELF, Period.DAY);
        Row three = create(WORKER, OWNER).task();
        add(three.id(), WORKER, Target.SELF, Period.DAY);
        ChecklistItem a = item(one.id(), WORKER, Target.SELF),
                b = item(three.id(), WORKER, Target.SELF);
        ChecklistChange invalidRemove =
                new ChecklistChange(
                        List.of(one.id(), three.id()),
                        Target.SELF,
                        ChecklistAction.REMOVE,
                        Period.DAY,
                        today(),
                        List.of(a.todayPlans().getFirst().id(), "missing"),
                        Map.of(one.id(), a.version(), three.id(), b.version()),
                        key());
        assertThatThrownBy(() -> tasks.checklist(invalidRemove, WORKER))
                .hasMessageContaining("没有所选");
        assertThat(item(one.id(), WORKER, Target.SELF).todayPlans()).hasSize(1);
        assertThat(item(one.id(), WORKER, Target.SELF).version()).isEqualTo(a.version());
    }

    @Test
    void concurrentDifferentKeysAtSameRevisionHaveOnlyOneWinner() throws Exception {
        Row row = create(WORKER, OWNER).task();
        ChecklistChange first =
                command(row.id(), WORKER, Target.SELF, ChecklistAction.ADD, Period.DAY, null);
        ChecklistChange second =
                new ChecklistChange(
                        first.ids(),
                        first.target(),
                        first.action(),
                        first.period(),
                        first.date(),
                        null,
                        first.expectedVersions(),
                        key());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> concurrent(first, start)),
                    b = pool.submit(() -> concurrent(second, start));
            start.countDown();
            assertThat(a.get(20, TimeUnit.SECONDS) ^ b.get(20, TimeUnit.SECONDS)).isTrue();
            assertThat(store.planHistory(row.id())).hasSize(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void mixingOwnAndManagedTasksCannotPartiallyApplyChecklistChanges() {
        Row own = create(OWNER, OWNER).task(), managed = create(WORKER, OWNER).task();
        ChecklistChange batch =
                new ChecklistChange(
                        List.of(own.id(), managed.id()),
                        Target.ASSIGNEE,
                        ChecklistAction.ADD,
                        Period.DAY,
                        today(),
                        null,
                        Map.of(
                                own.id(),
                                item(own.id(), OWNER, Target.ASSIGNEE).version(),
                                managed.id(),
                                item(managed.id(), OWNER, Target.ASSIGNEE).version()),
                        key());
        assertThatThrownBy(() -> tasks.checklist(batch, OWNER)).hasMessageContaining("本人");
        assertThat(store.planHistory(own.id())).isEmpty();
        assertThat(store.planHistory(managed.id())).isEmpty();
        assertThat(item(own.id(), OWNER, Target.SELF).version())
                .isEqualTo(batch.expectedVersions().get(own.id()));
        assertThat(store.requested(Long.toString(OWNER), batch.requestKey())).isNull();
    }

    @Test
    void batchReceiptsDoNotExposeOtherTaskIdentitiesAndAllRetryAtomically() {
        Row one = create(WORKER, OWNER).task(), two = create(WORKER, OWNER).task();
        ChecklistChange batch =
                new ChecklistChange(
                        List.of(one.id(), two.id()),
                        Target.ASSIGNEE,
                        ChecklistAction.ADD,
                        Period.DAY,
                        today(),
                        null,
                        Map.of(
                                one.id(),
                                item(one.id(), OWNER, Target.ASSIGNEE).version(),
                                two.id(),
                                item(two.id(), OWNER, Target.ASSIGNEE).version()),
                        key());
        TaskPlanning.Result first = tasks.checklist(batch, WORKER);
        assertThat(first.changed()).containsExactly(one.id(), two.id());
        assertThat(tasks.checklist(batch, WORKER)).isEqualTo(first);
        assertThat(store.events(one.rootId()))
                .filteredOn(e -> "PLANNED".equals(e.getEventType()))
                .allSatisfy(e -> assertThat(e.getMaterialJson()).doesNotContain(two.id()));
    }

    private boolean concurrent(ChecklistChange command, CountDownLatch start)
            throws InterruptedException {
        start.await();
        try {
            tasks.checklist(command, WORKER);
            return true;
        } catch (com.lingan.ucp.framework.common.exception.ServiceException conflict) {
            return false;
        }
    }

    private ChecklistContext context(String id, long actor, Target target) {
        return tasks.checklistContext(new ContextQuery(List.of(id), target), actor);
    }

    private ChecklistItem item(String id, long actor, Target target) {
        return context(id, actor, target).items().getFirst();
    }

    private ChecklistChange command(
            String id,
            long actor,
            Target target,
            ChecklistAction action,
            Period period,
            List<String> planIds) {
        ChecklistContext context = context(id, actor, target);
        return new ChecklistChange(
                List.of(id),
                target,
                action,
                period,
                period == Period.DAY ? context.today() : context.weekStart(),
                planIds,
                Map.of(id, context.items().getFirst().version()),
                key());
    }

    private TaskPlanning.Result add(String id, long actor, Target target, Period period) {
        return tasks.checklist(
                command(id, actor, target, ChecklistAction.ADD, period, null), actor);
    }

    private ChecklistChange datedCommand(
            String id, ChecklistAction action, LocalDate date, List<String> planIds) {
        return new ChecklistChange(
                List.of(id),
                Target.SELF,
                action,
                Period.WEEK,
                date,
                planIds,
                Map.of(id, item(id, WORKER, Target.SELF).version()),
                key());
    }

    private void remove(String id, long actor, Target target, Period period, String planId) {
        tasks.checklist(
                command(id, actor, target, ChecklistAction.REMOVE, period, List.of(planId)), actor);
    }

    private Detail create(Long assignee, long creator) {
        return create(node(key(), assignee, null), List.of(), creator);
    }

    private Detail create(NodeInput task, List<NodeInput> nodes, long creator) {
        Detail detail =
                tasks.create(
                        new Create(task, null, null, null, null, null, null, key(), nodes),
                        creator);
        roots.add(detail.task().rootId());
        return detail;
    }

    private NodeInput node(String id, Long assignee, Long acceptor) {
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
                List.of(),
                null,
                acceptor);
    }

    private void transition(String id, Action action, long actor) {
        tasks.transition(
                new Transition(id, tasks.detail(id, actor).task().revision(), action, null, key()),
                actor);
    }

    private List<String> page(String tab, LocalDate date, Filter filter, boolean team, long actor) {
        return tasks
                .page(
                        query(
                                team ? "MANAGE" : "MINE",
                                tab,
                                date,
                                filter,
                                team ? Scope.TEAM : Scope.PERSONAL,
                                team ? WORKER : null),
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
            Filter filter,
            Scope planScope,
            Long assignee) {
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
                1,
                100,
                null,
                null,
                null,
                null,
                planScope,
                assignee,
                filter,
                Mode.CHECKLIST);
    }

    /** 时间跨期夹具仅写本类创建的精确任务，不修改全局时钟或任何用户数据。 */
    private void past(String taskId, Period period, LocalDate date) {
        membership(taskId, period, date, WORKER, "SELF");
    }

    /** 直接构造升级前记录，以验证历史来源审计兼容，不开放代办写入入口。 */
    private String membership(
            String taskId, Period period, LocalDate date, long arranger, String source) {
        assertThat(roots).contains(taskId);
        TaskPlanDO plan = new TaskPlanDO();
        plan.setId(key());
        plan.setTaskId(taskId);
        plan.setUserId(WORKER);
        plan.setPeriod(period.name());
        plan.setPlanMode(Mode.CHECKLIST.name());
        plan.setPlanDate(date);
        plan.setEndDate(period == Period.DAY ? date : date.plusDays(6));
        plan.setSource(source);
        plan.setArrangedById(arranger);
        plan.setArrangedAt(LocalDateTime.now());
        store.savePlan(plan, Long.toString(arranger));
        return plan.getId();
    }

    private LocalDate today() {
        return LocalDate.now();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
