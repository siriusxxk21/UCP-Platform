package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.TaskCenter;
import com.lingan.ucp.nocode.api.TaskManagement.*;
import com.lingan.ucp.nocode.api.TaskPlanning;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;

import org.junit.jupiter.api.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/** 当前开发库上的管理汇总及下钻回归；仅创建和清理本类 UUID 夹具。 */
class TaskManagementIntegrationTest {
    private static final long BOSS = 902701L;
    private static final long OTHER_BOSS = 902702L;
    private static final long WORKER = 902703L;
    private static final long COLLEAGUE = 902704L;
    private static final LocalDate DATE = LocalDate.now();
    private static final LocalDate WEEK =
            DATE.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    private final List<String> roots = new ArrayList<>();
    private TaskCenterService tasks;
    private PermissionCommonApi permissions;

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
        permissions = servicesContext.getBean(PermissionCommonApi.class);
        when(permissions.hasAnyPermissions(BOSS, "nocode:task:manage-all")).thenReturn(false);
        AdminUserApi users = servicesContext.getBean(AdminUserApi.class);
        when(users.getUser(anyLong()))
                .thenAnswer(
                        call -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(call.getArgument(0));
                            user.setNickname("管理查询夹具员工 " + user.getId());
                            user.setStatus(0);
                            return user;
                        });
    }

    @AfterEach
    void cleanup() {
        when(permissions.hasAnyPermissions(BOSS, "nocode:task:manage-all")).thenReturn(false);
        for (String root : roots) {
            jdbc.update(
                    "DELETE FROM public.nocode_task_plan WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update("DELETE FROM public.nocode_task_instance WHERE root_id=?", root);
        }
    }

    @Test
    void employeeAggregationPrecedesPaginationAndDoesNotDoubleCountParents() {
        String root = task(null, BOSS, WORKER, "RUNNING", "协调根", null);
        String pending = task(root, BOSS, WORKER, "PENDING", "待办", DATE.minusDays(1));
        String running = task(root, BOSS, WORKER, "RUNNING", "进行", DATE.plusDays(1));
        String completed = task(root, BOSS, WORKER, "COMPLETED", "已完成", DATE.minusDays(2));
        String cancelled = task(root, BOSS, WORKER, "CANCELLED", "已取消", DATE.minusDays(2));
        task(null, BOSS, WORKER, "PENDING", "另一根", null);
        task(null, BOSS, COLLEAGUE, "PENDING", "同事工作", null);
        task(null, OTHER_BOSS, WORKER, "RUNNING", "不可见", DATE.minusDays(1));
        plan(pending, WORKER, "DAY", DATE, "CHECKLIST", false);
        plan(pending, WORKER, "WEEK", WEEK, "CHECKLIST", false);
        plan(completed, WORKER, "DAY", DATE, "CHECKLIST", false);
        plan(completed, WORKER, "WEEK", WEEK, "CHECKLIST", false);
        plan(root, WORKER, "DAY", DATE, "CHECKLIST", false);
        plan(cancelled, WORKER, "DAY", DATE, "CHECKLIST", false);
        plan(running, COLLEAGUE, "DAY", DATE, "CHECKLIST", false);
        plan(running, WORKER, "DAY", DATE, "SCHEDULE", false);
        plan(running, WORKER, "WEEK", WEEK, "CHECKLIST", true);

        PageResult<Employee> first =
                tasks.managementEmployees(new Employees(null, DATE, 1, 1), BOSS);
        assertThat(first.getTotal()).isEqualTo(2);
        TaskCenter.Row rootProjection =
                tasks.managementPage(
                                new Query(query("协调根", 1, 20, null, null), Focus.ALL, null, null),
                                BOSS)
                        .getList()
                        .getFirst();
        assertThat(rootProjection.childCount()).isEqualTo(4);
        assertThat(rootProjection.completedChildCount()).isEqualTo(1);
        Employee employee = first.getList().getFirst();
        assertThat(employee.userId()).isEqualTo(WORKER);
        assertThat(employee.pendingCount()).isEqualTo(2);
        assertThat(employee.runningCount()).isEqualTo(1);
        assertThat(employee.overdueCount()).isEqualTo(1);
        assertThat(employee.todayCount()).isEqualTo(3);
        assertThat(employee.weekCount()).isEqualTo(2);
        assertThat(employee.coordinationCount()).isEqualTo(1);
        Employee historicalChecklist =
                tasks
                        .managementEmployees(new Employees(null, DATE.minusWeeks(2), 1, 20), BOSS)
                        .getList()
                        .stream()
                        .filter(value -> value.userId().equals(WORKER))
                        .findFirst()
                        .orElseThrow();
        assertThat(historicalChecklist.overdueCount()).isEqualTo(1);
        assertThat(historicalChecklist.todayCount()).isZero();
        assertThat(tasks.managementEmployees(new Employees(null, DATE, 2, 1), BOSS).getList())
                .extracting(Employee::userId)
                .containsExactly(COLLEAGUE);
        assertThat(
                        tasks.managementEmployees(
                                        new Employees(Long.toString(WORKER), DATE, 1, 20), BOSS)
                                .getTotal())
                .isEqualTo(1);
        Map<EmployeeMetric, Long> counts =
                Map.of(
                        EmployeeMetric.PENDING,
                        2L,
                        EmployeeMetric.RUNNING,
                        1L,
                        EmployeeMetric.OVERDUE,
                        1L,
                        EmployeeMetric.TODAY,
                        3L,
                        EmployeeMetric.WEEK,
                        2L,
                        EmployeeMetric.COORDINATION,
                        1L,
                        EmployeeMetric.ALL,
                        3L);
        for (Map.Entry<EmployeeMetric, Long> metric : counts.entrySet()) {
            PageResult<TaskCenter.Row> page =
                    tasks.managementPage(
                            new Query(
                                    query(null, 1, 1, null, null),
                                    Focus.ALL,
                                    WORKER,
                                    metric.getKey()),
                            BOSS);
            assertThat(page.getTotal()).as(metric.getKey().name()).isEqualTo(metric.getValue());
            assertThat(page.getList()).hasSize(1);
        }
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query(null, 1, 20, null, null),
                                                Focus.ALL,
                                                WORKER,
                                                EmployeeMetric.TODAY),
                                        BOSS)
                                .getList())
                .extracting(TaskCenter.Row::id)
                .containsExactlyInAnyOrder(pending, running, completed);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_task_plan WHERE task_id=?",
                                Long.class,
                                pending))
                .isEqualTo(2);
    }

    @Test
    void managementFiltersMatchChildrenAndGroupBeforePagination() {
        String root = task(null, BOSS, COLLEAGUE, "PENDING", "主任务", null);
        task(root, BOSS, WORKER, "RUNNING", "唯一子项标记", DATE.minusDays(1));
        task(root, BOSS, null, "PENDING", "唯一子项标记二", DATE.minusDays(2));
        String other = task(null, BOSS, COLLEAGUE, "PENDING", "第二组", null);
        task(other, BOSS, WORKER, "PENDING", "唯一子项标记三", DATE.minusDays(1));
        task(null, OTHER_BOSS, WORKER, "RUNNING", "唯一子项标记四", DATE.minusDays(1));
        Query match = new Query(query("唯一子项标记", 1, 1, null, null), Focus.ACTIVE, null, null);
        assertThat(tasks.managementPage(match, BOSS).getTotal()).isEqualTo(2);
        assertThat(tasks.managementPage(match, BOSS).getList()).hasSize(1);
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query(null, 1, 20, WORKER, null),
                                                Focus.ALL,
                                                null,
                                                null),
                                        BOSS)
                                .getList())
                .extracting(TaskCenter.Row::id)
                .containsExactlyInAnyOrder(root, other);
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query(null, 1, 20, null, null),
                                                Focus.UNASSIGNED,
                                                null,
                                                null),
                                        BOSS)
                                .getList())
                .extracting(TaskCenter.Row::id)
                .containsExactly(root);
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query(null, 1, 20, null, null),
                                                Focus.OVERDUE,
                                                null,
                                                null),
                                        BOSS)
                                .getTotal())
                .isEqualTo(2);
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query(null, 1, 20, null, "RUNNING"),
                                                Focus.ALL,
                                                null,
                                                null),
                                        BOSS)
                                .getList())
                .extracting(TaskCenter.Row::id)
                .containsExactly(root);
    }

    @Test
    void relatedEmployeeGroupsIncludeOwnRootAndOwnChildInMixedAssignments() {
        String ownRoot = task(null, BOSS, WORKER, "RUNNING", "员工负责总任务", null);
        String colleagueChild = task(ownRoot, BOSS, COLLEAGUE, "PENDING", "同事执行下级", null);
        String colleagueRoot = task(null, BOSS, COLLEAGUE, "RUNNING", "同事负责总任务", null);
        String ownChild = task(colleagueRoot, BOSS, WORKER, "PENDING", "员工执行下级", null);
        String unrelated = task(null, BOSS, COLLEAGUE, "RUNNING", "无关总任务", null);
        task(unrelated, BOSS, COLLEAGUE, "PENDING", "无关下级", null);

        PageResult<TaskCenter.Row> grouped =
                tasks.managementPage(
                        new Query(
                                query(null, 1, 20, null, null),
                                Focus.ACTIVE,
                                WORKER,
                                EmployeeMetric.RELATED,
                                true),
                        BOSS);
        assertThat(grouped.getTotal()).isEqualTo(2);
        assertThat(grouped.getList())
                .extracting(TaskCenter.Row::id)
                .containsExactlyInAnyOrder(ownRoot, colleagueRoot);
        assertThat(grouped.getList())
                .allSatisfy(
                        row -> {
                            assertThat(row.parentId()).isNull();
                            assertThat(row.childCount()).isEqualTo(1);
                        });
        // 员工筛选只决定入组；展开仍复用原详情授权树，管理人不会看见一棵被负责人条件截断的树。
        assertThat(tasks.detail(ownRoot, BOSS).nodes())
                .extracting(TaskCenter.Row::id)
                .contains(ownRoot, colleagueChild);
        assertThat(tasks.detail(colleagueRoot, BOSS).nodes())
                .extracting(TaskCenter.Row::id)
                .contains(colleagueRoot, ownChild);
    }

    @Test
    void employeeGroupedSearchMatchesRootOrOwnedNodeButNotColleagueOnlyNode() {
        String root = task(null, BOSS, COLLEAGUE, "RUNNING", "查找总任务名称", null);
        task(root, BOSS, WORKER, "PENDING", "查找员工办理节点", null);
        task(root, BOSS, COLLEAGUE, "PENDING", "查找同事专属节点", null);
        String foreign = task(null, OTHER_BOSS, COLLEAGUE, "RUNNING", "查找总任务名称", null);
        task(foreign, OTHER_BOSS, WORKER, "PENDING", "查找员工办理节点", null);
        for (String search : List.of("查找总任务名称", "查找员工办理节点", root)) {
            assertThat(
                            tasks.managementPage(
                                            new Query(
                                                    query(search, 1, 20, null, "PENDING"),
                                                    Focus.ACTIVE,
                                                    WORKER,
                                                    EmployeeMetric.RELATED,
                                                    true),
                                            BOSS)
                                    .getList())
                    .extracting(TaskCenter.Row::id)
                    .containsExactly(root);
        }
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query("查找同事专属节点", 1, 20, null, null),
                                                Focus.ACTIVE,
                                                WORKER,
                                                EmployeeMetric.RELATED,
                                                true),
                                        BOSS)
                                .getList())
                .isEmpty();
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query("查找总任务名称", 1, 20, null, "RUNNING"),
                                                Focus.ACTIVE,
                                                WORKER,
                                                EmployeeMetric.RELATED,
                                                true),
                                        BOSS)
                                .getList())
                .isEmpty();
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query("查找总任务名称", 1, 20, null, null),
                                                Focus.ACTIVE,
                                                WORKER,
                                                EmployeeMetric.RELATED),
                                        BOSS)
                                .getList())
                .isEmpty();
    }

    @Test
    void employeeRootGroupingDeduplicatesBeforePaginationAndKeepsChecklistMetrics() {
        String first = task(null, BOSS, WORKER, "RUNNING", "员工相关第一组", DATE);
        String childOne = task(first, BOSS, WORKER, "PENDING", "待执行一", null);
        String childTwo = task(first, BOSS, WORKER, "PENDING", "待执行二", null);
        String second = task(null, BOSS, COLLEAGUE, "RUNNING", "员工相关第二组", DATE.plusDays(1));
        String childThree = task(second, BOSS, WORKER, "PENDING", "待执行三", null);
        plan(childOne, WORKER, "DAY", DATE, "CHECKLIST", false);
        plan(childTwo, WORKER, "DAY", DATE, "CHECKLIST", false);
        plan(childThree, WORKER, "DAY", DATE, "CHECKLIST", false);

        for (EmployeeMetric metric :
                List.of(EmployeeMetric.RELATED, EmployeeMetric.PENDING, EmployeeMetric.TODAY)) {
            PageResult<TaskCenter.Row> pageOne =
                    tasks.managementPage(
                            new Query(
                                    query(null, 1, 1, null, null),
                                    Focus.ACTIVE,
                                    WORKER,
                                    metric,
                                    true),
                            BOSS);
            PageResult<TaskCenter.Row> pageTwo =
                    tasks.managementPage(
                            new Query(
                                    query(null, 2, 1, null, null),
                                    Focus.ACTIVE,
                                    WORKER,
                                    metric,
                                    true),
                            BOSS);
            assertThat(pageOne.getTotal()).as(metric.name()).isEqualTo(2);
            assertThat(pageTwo.getTotal()).as(metric.name()).isEqualTo(2);
            assertThat(pageOne.getList()).extracting(TaskCenter.Row::id).containsExactly(first);
            assertThat(pageTwo.getList()).extracting(TaskCenter.Row::id).containsExactly(second);
        }
        Employee employee =
                tasks.managementEmployees(new Employees(Long.toString(WORKER), DATE, 1, 20), BOSS)
                        .getList()
                        .getFirst();
        assertThat(employee.pendingCount()).isEqualTo(3);
        assertThat(employee.todayCount()).isEqualTo(3);
        assertThat(employee.coordinationCount()).isEqualTo(1);
    }

    @Test
    void groupedEmployeeStatusFiltersOwnedNodesRatherThanRootOrColleagueState() {
        String runningRoot = task(null, BOSS, COLLEAGUE, "RUNNING", "进行中的总任务", null);
        task(runningRoot, BOSS, WORKER, "COMPLETED", "员工已完成下级", null);
        String completedRoot = task(null, BOSS, COLLEAGUE, "COMPLETED", "已完成总任务", null);
        task(completedRoot, BOSS, WORKER, "COMPLETED", "员工完成下级二", null);
        String ownPending = task(null, BOSS, WORKER, "PENDING", "本人未开始总任务", null);
        task(ownPending, BOSS, COLLEAGUE, "RUNNING", "同事已开工", null);
        String cancelled = task(null, BOSS, WORKER, "CANCELLED", "本人取消任务", null);

        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query(null, 1, 20, null, "COMPLETED"),
                                                Focus.ACTIVE,
                                                WORKER,
                                                EmployeeMetric.RELATED,
                                                true),
                                        BOSS)
                                .getList())
                .extracting(TaskCenter.Row::id)
                .containsExactlyInAnyOrder(runningRoot, completedRoot);
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query(null, 1, 20, null, "RUNNING"),
                                                Focus.ACTIVE,
                                                WORKER,
                                                EmployeeMetric.RELATED,
                                                true),
                                        BOSS)
                                .getList())
                .isEmpty();
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query(null, 1, 20, null, null),
                                                Focus.ACTIVE,
                                                WORKER,
                                                EmployeeMetric.RELATED,
                                                true),
                                        BOSS)
                                .getList())
                .extracting(TaskCenter.Row::id)
                .containsExactlyInAnyOrder(runningRoot, completedRoot, ownPending, cancelled);
    }

    @Test
    void optionalEmployeeGroupingPreservesLegacyFlatQueriesAndJson() throws Exception {
        String root = task(null, BOSS, WORKER, "RUNNING", "本人协调任务", null);
        String pending = task(root, BOSS, WORKER, "PENDING", "本人具体任务", null);
        String completed = task(root, BOSS, WORKER, "COMPLETED", "本人完成任务", null);
        TaskCenter.Query base = query(null, 1, 20, null, null);
        Query legacy = new Query(base, Focus.ACTIVE, WORKER, EmployeeMetric.ALL);
        assertThat(legacy.groupByRoot()).isFalse();
        for (Query request :
                List.of(
                        legacy,
                        new Query(base, Focus.ACTIVE, WORKER, EmployeeMetric.ALL, null),
                        new Query(base, Focus.ACTIVE, WORKER, null, true))) {
            assertThat(tasks.managementPage(request, BOSS).getList())
                    .extracting(TaskCenter.Row::id)
                    .containsExactly(Boolean.TRUE.equals(request.groupByRoot()) ? root : pending);
        }
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                base, Focus.ACTIVE, WORKER, EmployeeMetric.RELATED),
                                        BOSS)
                                .getList())
                .extracting(TaskCenter.Row::id)
                .containsExactlyInAnyOrder(root, pending, completed);
        ObjectMapper mapper = new ObjectMapper();
        Query oldJson =
                mapper.readValue("{\"employeeId\":902703,\"employeeMetric\":\"ALL\"}", Query.class);
        assertThat(oldJson.groupByRoot()).isNull();
        assertThat(tasks.managementPage(oldJson, BOSS).getList())
                .extracting(TaskCenter.Row::id)
                .containsExactly(pending);
        Query newJson =
                mapper.readValue(
                        "{\"employeeId\":902703,\"employeeMetric\":\"RELATED\",\"groupByRoot\":true}",
                        Query.class);
        assertThat(tasks.managementPage(newJson, BOSS).getList())
                .extracting(TaskCenter.Row::id)
                .containsExactly(root);
    }

    @Test
    void employeeGroupingKeepsManagerVisibilityAndDoesNotGrantExecutorAccess() {
        String own = task(null, BOSS, COLLEAGUE, "RUNNING", "自己发起的任务组", null);
        task(own, BOSS, WORKER, "PENDING", "本人管理范围内员工任务", null);
        String foreign = task(null, OTHER_BOSS, COLLEAGUE, "RUNNING", "其他老板的任务组", null);
        task(foreign, OTHER_BOSS, WORKER, "PENDING", "相同员工但无管理权", null);
        Query request =
                new Query(
                        query(null, 1, 20, null, null),
                        Focus.ACTIVE,
                        WORKER,
                        EmployeeMetric.RELATED,
                        true);
        assertThat(tasks.managementPage(request, BOSS).getList())
                .extracting(TaskCenter.Row::id)
                .containsExactly(own);
        assertThat(tasks.managementPage(request, WORKER).getList()).isEmpty();
        assertThat(tasks.managementPage(request, COLLEAGUE).getList()).isEmpty();
        when(permissions.hasAnyPermissions(BOSS, "nocode:task:manage-all")).thenReturn(true);
        assertThat(tasks.managementPage(request, BOSS).getList())
                .extracting(TaskCenter.Row::id)
                .containsExactlyInAnyOrder(own, foreign);
        when(permissions.hasAnyPermissions(BOSS, "nocode:task:manage-all")).thenReturn(false);
        assertThat(tasks.managementPage(request, BOSS).getList())
                .extracting(TaskCenter.Row::id)
                .containsExactly(own);
    }

    @Test
    void managementVisibilityCannotBeObtainedByPassingAnotherEmployeeId() {
        String root = task(null, BOSS, WORKER, "RUNNING", "老板工作", null);
        task(root, BOSS, COLLEAGUE, "PENDING", "同事私有工作", null);
        String foreign = task(null, OTHER_BOSS, WORKER, "PENDING", "其他老板工作", null);
        Query request =
                new Query(query(null, 1, 20, null, null), Focus.ALL, WORKER, EmployeeMetric.ALL);
        assertThat(tasks.managementPage(request, WORKER).getList()).isEmpty();
        assertThat(tasks.managementEmployees(new Employees(null, DATE, 1, 20), WORKER).getList())
                .isEmpty();
        assertThat(tasks.managementPage(request, BOSS).getList()).isEmpty();
        when(permissions.hasAnyPermissions(BOSS, "nocode:task:manage-all")).thenReturn(true);
        assertThat(tasks.managementPage(request, BOSS).getList())
                .extracting(TaskCenter.Row::id)
                .contains(foreign);
        when(permissions.hasAnyPermissions(BOSS, "nocode:task:manage-all")).thenReturn(false);
        assertThat(tasks.managementPage(request, BOSS).getList()).isEmpty();
    }

    @Test
    void pausedAncestorsAffectEmployeeStatusWithoutChangingChecklistMembership() {
        String root = task(null, BOSS, WORKER, "PAUSED", "暂停组", null);
        String child = task(root, BOSS, WORKER, "PENDING", "暂停下级", DATE.minusDays(1));
        plan(child, WORKER, "DAY", DATE, "CHECKLIST", false);
        Employee employee =
                tasks.managementEmployees(new Employees(null, DATE, 1, 20), BOSS)
                        .getList()
                        .getFirst();
        assertThat(employee.pendingCount()).isZero();
        assertThat(employee.runningCount()).isZero();
        assertThat(employee.overdueCount()).isEqualTo(1);
        assertThat(employee.todayCount()).isEqualTo(1);
        assertThat(employee.coordinationCount()).isEqualTo(1);
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query(null, 1, 20, null, null),
                                                Focus.ALL,
                                                WORKER,
                                                EmployeeMetric.ALL),
                                        BOSS)
                                .getTotal())
                .isEqualTo(1);
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query(null, 1, 20, null, null),
                                                Focus.ALL,
                                                WORKER,
                                                EmployeeMetric.TODAY),
                                        BOSS)
                                .getTotal())
                .isEqualTo(1);
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                new TaskCenter.Query(
                                                        "MANAGE",
                                                        "ALL",
                                                        DATE.plusDays(1),
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        1,
                                                        20),
                                                Focus.ALL,
                                                WORKER,
                                                EmployeeMetric.TODAY),
                                        BOSS)
                                .getTotal())
                .isZero();
    }

    @Test
    void managementQueryRejectsPersonalScopesAndInvalidDrilldowns() {
        assertThatThrownBy(
                        () ->
                                tasks.managementPage(
                                        new Query(
                                                query(null, 1, 20, null, null),
                                                Focus.ALL,
                                                null,
                                                EmployeeMetric.TODAY),
                                        BOSS))
                .hasMessageContaining("先选择员工");
        assertThatThrownBy(
                        () ->
                                tasks.managementPage(
                                        new Query(
                                                query(null, 1, 20, null, null),
                                                Focus.ALL,
                                                -1L,
                                                EmployeeMetric.ALL),
                                        BOSS))
                .hasMessageContaining("员工身份");
        TaskCenter.Query mine =
                new TaskCenter.Query(
                        "MINE", "ALL", DATE, null, null, null, null, null, null, null, null, null,
                        1, 20);
        assertThatThrownBy(
                        () -> tasks.managementPage(new Query(mine, Focus.ACTIVE, null, null), BOSS))
                .hasMessageContaining("管理总览");
    }

    @Test
    void readingEmployeePlansDoesNotGiveBossChecklistWritePermission() {
        String task = task(null, BOSS, WORKER, "PENDING", "员工自主管理清单", null);
        plan(task, WORKER, "DAY", DATE, "CHECKLIST", false);
        assertThat(
                        tasks.managementPage(
                                        new Query(
                                                query(null, 1, 20, null, null),
                                                Focus.ALL,
                                                WORKER,
                                                EmployeeMetric.TODAY),
                                        BOSS)
                                .getTotal())
                .isEqualTo(1);
        TaskPlanning.ChecklistItem item =
                tasks.checklistContext(
                                new TaskPlanning.ContextQuery(
                                        List.of(task), TaskPlanning.Target.ASSIGNEE),
                                BOSS)
                        .items()
                        .getFirst();
        assertThat(item.canAdd()).isFalse();
        assertThatThrownBy(
                        () ->
                                tasks.checklist(
                                        new TaskPlanning.ChecklistChange(
                                                List.of(task),
                                                TaskPlanning.Target.ASSIGNEE,
                                                TaskPlanning.ChecklistAction.ADD,
                                                TaskCenter.Period.DAY,
                                                LocalDate.now(),
                                                List.of(),
                                                Map.of(task, item.version()),
                                                UUID.randomUUID().toString()),
                                        BOSS))
                .hasMessageContaining("负责人本人");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_task_plan WHERE task_id=?",
                                Long.class,
                                task))
                .isEqualTo(1);
    }

    private TaskCenter.Query query(
            String search, int page, int size, Long employee, String status) {
        return new TaskCenter.Query(
                "MANAGE",
                "ALL",
                DATE,
                search,
                null,
                null,
                status,
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
                false,
                employee == null ? null : TaskPlanning.Scope.TEAM,
                employee,
                null,
                null,
                null);
    }

    private String task(
            String root, long creator, Long employee, String status, String title, LocalDate due) {
        String id = UUID.randomUUID().toString();
        if (root == null) roots.add(id);
        jdbc.update(
                "INSERT INTO"
                    + " public.nocode_task_instance(id,root_id,parent_id,title,assignee_id,status,config_json,t0,expected_end,actual_start,creator,updater)"
                    + " VALUES(?,?,?,?,?,?,?,now(),CAST(? AS timestamp),CASE WHEN ?='RUNNING' THEN"
                    + " now() ELSE NULL END,?,?)",
                id,
                root == null ? id : root,
                root,
                title,
                employee,
                status,
                "{\"assignmentMode\":\"ASSIGNED\",\"urgency\":\"NORMAL\",\"priority\":\"MEDIUM\",\"predecessorIds\":[],\"entries\":[],\"candidateUserIds\":[],\"sharing\":{\"mode\":\"INDEPENDENT\",\"writableFieldIds\":[]},\"schedule\":{\"mode\":\"UNSCHEDULED\"}}",
                due == null ? null : due + " 18:00:00",
                status,
                Long.toString(creator),
                Long.toString(creator));
        return id;
    }

    private void plan(
            String task,
            long employee,
            String period,
            LocalDate date,
            String mode,
            boolean deleted) {
        jdbc.update(
                "INSERT INTO"
                    + " public.nocode_task_plan(id,task_id,user_id,period,plan_date,end_date,plan_mode,source,arranged_by_id,arranged_at,deleted,creator,updater)"
                    + " VALUES(?,?,?,?,?,?,?,'SELF',?,now(),?,?,?)",
                UUID.randomUUID().toString(),
                task,
                employee,
                period,
                date,
                period.equals("WEEK") ? date.plusDays(6) : date,
                mode,
                employee,
                deleted ? 1 : 0,
                Long.toString(employee),
                Long.toString(employee));
    }
}
