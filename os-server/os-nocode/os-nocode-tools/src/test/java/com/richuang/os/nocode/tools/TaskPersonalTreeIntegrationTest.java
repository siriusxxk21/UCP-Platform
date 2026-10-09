package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.module.msg.api.IMsgSendService;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.api.TaskPlanning;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskCenterService;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** 当前开发库验证个人清单的归并、分页和权限；仅清理本类创建的独立任务夹具。 */
class TaskPersonalTreeIntegrationTest {
    private static final long OWNER = 10001L, WORKER = 21001L, OTHER = 21002L;
    private static final LocalDate DATE = LocalDate.of(2031, 3, 12);
    private final Set<String> roots = new LinkedHashSet<>();
    private final String marker = "personal_tree_" + UUID.randomUUID() + "_";
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
                        invocation -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(invocation.getArgument(0));
                            user.setNickname("个人树测试" + user.getId());
                            user.setStatus(0);
                            return user;
                        });
        Mockito.when(users.getUserList(Mockito.anyCollection()))
                .thenAnswer(
                        invocation -> {
                            Collection<Long> ids = invocation.getArgument(0);
                            return ids.stream()
                                    .map(
                                            id -> {
                                                AdminUserRespDTO user = new AdminUserRespDTO();
                                                user.setId(id);
                                                user.setNickname("个人树测试" + id);
                                                user.setStatus(0);
                                                return user;
                                            })
                                    .toList();
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
    void groupingPrecedesPaginationAndChildrenNeverOccupyAnotherEntryPage() {
        Detail tree =
                create(
                        "root",
                        WORKER,
                        List.of(
                                node("one", null, WORKER),
                                node("two", null, WORKER),
                                node("three", null, WORKER),
                                node("four", null, WORKER),
                                node("five", null, WORKER)));
        Detail second = create("second", WORKER, List.of());
        Detail third = create("third", WORKER, List.of());
        List<String> entries = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            PageResult<PersonalTreeNode> result = tasks.personalTreePage(query(page, 1), WORKER);
            assertThat(result.getTotal()).isEqualTo(3);
            assertThat(result.getList()).hasSize(1);
            entries.add(result.getList().getFirst().task().id());
        }
        assertThat(entries)
                .containsExactly(tree.task().id(), second.task().id(), third.task().id());
        PersonalTreeNode root = tasks.personalTreePage(query(1, 1), WORKER).getList().getFirst();
        assertThat(root.matchingChildCount()).isEqualTo(5);
        List<PersonalTreeNode> children = children(query(1, 1), root.task().id(), WORKER);
        assertThat(children).hasSize(5).allMatch(n -> n.matchingChildCount() == 0);
        assertThat(children).extracting(n -> n.task().id()).doesNotHaveDuplicates();
        assertThat(tasks.personalTreePage(query(4, 1), WORKER).getList()).isEmpty();
        // 原逐节点接口保持兼容，不被个人树分页静默改写。
        assertThat(tasks.page(query(1, 100), WORKER).getTotal()).isEqualTo(8);
    }

    @Test
    void completeGroupPreservesColleagueBranchesWithoutChangingTheirDetailPermissions() {
        Detail tree =
                create(
                        "root",
                        WORKER,
                        List.of(
                                node("mine", null, WORKER),
                                node("mine-leaf", "mine", WORKER),
                                node("other", null, OTHER),
                                node("deep-mine", "other", WORKER),
                                node("other-leaf", "other", OTHER)));
        Query query = query(1, 100);
        List<PersonalTreeNode> entries = tasks.personalTreePage(query, WORKER).getList();
        assertThat(entries).extracting(n -> n.task().id()).containsExactly(tree.task().id());
        assertThat(entries.getFirst().matchingChildCount()).isEqualTo(2);
        List<PersonalTreeNode> children = children(query, tree.task().id(), WORKER);
        assertThat(children)
                .extracting(n -> n.task().id())
                .containsExactlyInAnyOrder(row(tree, "mine").id(), row(tree, "other").id());
        PersonalTreeNode deep =
                children(query, row(tree, "other").id(), WORKER).stream()
                        .filter(n -> n.task().id().equals(row(tree, "deep-mine").id()))
                        .findFirst()
                        .orElseThrow();
        assertThat(deep.task().parentId()).isEqualTo(row(tree, "other").id());
        assertThat(deep.task().rootId()).isEqualTo(tree.task().id());
        assertThat(children(query, row(tree, "mine").id(), WORKER))
                .extracting(n -> n.task().id())
                .containsExactly(row(tree, "mine-leaf").id());
        PersonalTreeNode other =
                children.stream().filter(n -> !n.detailVisible()).findFirst().orElseThrow();
        assertThat(other.contextOnly()).isTrue();
        assertThat(other.structure().parentId()).isEqualTo(tree.task().id());
        assertThat(other.anchorTaskId()).isEqualTo(tree.task().id());
        assertThat(other.task().canStart()).isFalse();
        assertThat(other.task().description()).isNull();
        assertThatThrownBy(() -> tasks.detail(other.task().id(), WORKER))
                .hasMessageContaining("权限");
    }

    @Test
    void entryIsAlwaysTheRealRootEvenWhenOnlyDescendantMatches() {
        Detail tree =
                create(
                        "root",
                        OTHER,
                        List.of(
                                node("mine", null, WORKER),
                                node("mine-leaf", "mine", WORKER),
                                node("other", null, OTHER)));
        PermissionCommonApi permissions = servicesContext.getBean(PermissionCommonApi.class);
        Mockito.when(permissions.hasAnyRoles(WORKER, "super_admin")).thenReturn(true);
        try {
            List<PersonalTreeNode> entries =
                    tasks.personalTreePage(query(1, 100), WORKER).getList();
            assertThat(entries).extracting(n -> n.task().id()).containsExactly(tree.task().id());
            assertThat(entries.getFirst().matchingChildCount()).isEqualTo(2);
            assertThat(entries.getFirst().contextOnly()).isTrue();
            assertThat(children(query(1, 100), row(tree, "mine").id(), WORKER))
                    .extracting(n -> n.task().id())
                    .containsExactly(row(tree, "mine-leaf").id());
            assertThat(children(query(1, 100), tree.task().id(), WORKER)).hasSize(2);
        } finally {
            Mockito.when(permissions.hasAnyRoles(WORKER, "super_admin")).thenReturn(false);
        }
    }

    @Test
    void searchAndStatusSelectGroupsWithoutDroppingTheirOtherNodes() {
        Detail tree =
                create(
                        "root",
                        WORKER,
                        List.of(
                                node("needle", null, WORKER),
                                node("needle-leaf", "needle", WORKER)));
        Query search = query("POOL", marker + "needle", null, 1, 100, null);
        assertThat(tasks.personalTreePage(search, WORKER).getList())
                .extracting(n -> n.task().id())
                .containsExactly(tree.task().id());
        assertThat(children(search, row(tree, "needle").id(), WORKER)).hasSize(1);
        assertThat(children(search, tree.task().id(), WORKER)).hasSize(1);
        assertThat(tasks.personalTreePage(search, WORKER).getList().getFirst().contextOnly())
                .isTrue();
        tasks.transition(
                new Transition(tree.task().id(), tree.task().revision(), Action.START, null, key()),
                WORKER);
        Query pending = query("POOL", marker, "PENDING", 1, 100, null);
        assertThat(tasks.personalTreePage(pending, WORKER).getList())
                .extracting(n -> n.task().id())
                .containsExactly(tree.task().id());
        Query running = query("POOL", marker, "RUNNING", 1, 100, null);
        assertThat(
                        tasks.personalTreePage(running, WORKER)
                                .getList()
                                .getFirst()
                                .matchingChildCount())
                .isEqualTo(1);
        assertThat(children(running, tree.task().id(), WORKER))
                .hasSize(1)
                .allMatch(PersonalTreeNode::contextOnly);
    }

    @Test
    void matchingGrandchildRemainsReachableAcrossAnUnmatchedIntermediateTask() {
        Detail tree =
                create(
                        "needle-root",
                        WORKER,
                        List.of(
                                node("bridge", null, WORKER),
                                node("needle-leaf", "bridge", WORKER)));
        Query search = query("POOL", marker + "needle", null, 1, 100, null);
        PageResult<PersonalTreeNode> page = tasks.personalTreePage(search, WORKER);
        assertThat(page.getTotal()).isEqualTo(1);
        assertThat(page.getList().getFirst().matchingChildCount()).isEqualTo(1);
        List<PersonalTreeNode> children = children(search, tree.task().id(), WORKER);
        assertThat(children)
                .extracting(n -> n.task().id())
                .containsExactly(row(tree, "bridge").id());
        assertThat(children.getFirst().contextOnly()).isTrue();
        assertThat(children(search, row(tree, "bridge").id(), WORKER))
                .extracting(n -> n.task().id())
                .containsExactly(row(tree, "needle-leaf").id());
    }

    @Test
    void completedViewSelectsTheGroupAndMarksUnfinishedContext() {
        Detail tree =
                create(
                        "root",
                        WORKER,
                        List.of(node("child", null, WORKER), node("remaining", null, WORKER)));
        String root = tree.task().id(), child = row(tree, "child").id();
        transition(root, Action.START);
        transition(child, Action.START);
        transition(child, Action.COMPLETE);
        Query completed = query("ALL", marker, "COMPLETED", 1, 100, null);
        assertThat(tasks.personalTreePage(completed, WORKER).getList())
                .extracting(n -> n.task().id())
                .containsExactly(root);
        assertThat(children(completed, root, WORKER)).hasSize(2);
        assertThat(children(completed, root, WORKER).getLast().contextOnly()).isTrue();
        // 当前父任务自动汇总规则下，保留一项未完成子任务后再验证全部完成的归并。
        transition(row(tree, "remaining").id(), Action.START);
        transition(row(tree, "remaining").id(), Action.COMPLETE);
        assertThat(tasks.personalTreePage(completed, WORKER).getList())
                .extracting(n -> n.task().id())
                .containsExactly(root);
        assertThat(children(completed, root, WORKER))
                .extracting(n -> n.task().id())
                .containsExactly(child, row(tree, "remaining").id());
        assertThat(tasks.personalTreePage(query(1, 100), WORKER).getTotal()).isZero();
    }

    @Test
    void matchingPersonalPlansSelectCompleteGroupsAcrossDayWeekAndMonthViews() {
        Detail tree =
                create(
                        "root",
                        WORKER,
                        List.of(
                                node("planned", null, WORKER),
                                node("planned-leaf", "planned", WORKER),
                                node("unplanned", null, WORKER)));
        tasks.plan(
                new SavePlan(
                        List.of(row(tree, "planned").id(), row(tree, "planned-leaf").id()),
                        Period.DAY,
                        DATE,
                        true,
                        "SELF"),
                WORKER);
        for (String tab : List.of("TODAY", "WEEK", "MONTH")) {
            Query plan = query(tab, marker, null, 1, 100, TaskPlanning.Scope.PERSONAL);
            PageResult<PersonalTreeNode> page = tasks.personalTreePage(plan, WORKER);
            assertThat(page.getTotal()).isEqualTo(1);
            assertThat(page.getList())
                    .extracting(n -> n.task().id())
                    .containsExactly(tree.task().id());
            assertThat(page.getList().getFirst().matchingChildCount()).isEqualTo(2);
            assertThat(children(plan, row(tree, "planned").id(), WORKER))
                    .extracting(n -> n.task().id())
                    .containsExactly(row(tree, "planned-leaf").id());
            assertThat(children(plan, tree.task().id(), WORKER)).hasSize(2);
        }
    }

    @Test
    void unifiedChecklistViewsKeepVisibleChildrenWithoutCreatingTheirMemberships() {
        Detail tree =
                create(
                        "root",
                        WORKER,
                        List.of(node("finished", null, WORKER), node("remaining", null, WORKER)));
        String root = tree.task().id();
        String finished = row(tree, "finished").id();
        String remaining = row(tree, "remaining").id();
        transition(root, Action.START);
        transition(finished, Action.START);
        transition(finished, Action.COMPLETE);
        addChecklist(root, Period.DAY, WORKER);
        List<Plan> rootPlans = tasks.detail(root, WORKER).task().plans();

        for (Query view :
                List.of(
                        checklistQuery("ALL", null),
                        checklistQuery("WEEK", TaskPlanning.Filter.PLANNED),
                        checklistQuery("TODAY", TaskPlanning.Filter.PLANNED))) {
            PageResult<PersonalTreeNode> page = tasks.personalTreePage(view, WORKER);
            assertThat(page.getTotal()).isEqualTo(1);
            PersonalTreeNode header = page.getList().getFirst();
            assertThat(header.task().id()).isEqualTo(root);
            assertThat(header.matchingChildCount()).isEqualTo(2);
            assertThat(header.completedChildCount()).isEqualTo(1);
            assertThat(header.contextOnly()).isFalse();
            List<PersonalTreeNode> children = children(view, root, WORKER);
            assertThat(children)
                    .extracting(n -> n.task().id())
                    .containsExactly(finished, remaining);
            assertThat(children).allMatch(PersonalTreeNode::detailVisible);
            assertThat(children).noneMatch(PersonalTreeNode::contextOnly);
        }
        assertThat(
                        tasks.personalTreePage(
                                        checklistQuery("ALL", TaskPlanning.Filter.UNPLANNED),
                                        WORKER)
                                .getTotal())
                .isZero();
        assertThat(tasks.detail(root, WORKER).task().plans()).isEqualTo(rootPlans);
        for (String child : List.of(finished, remaining)) {
            assertThat(tasks.detail(child, WORKER).task().plans())
                    .hasSize(2)
                    .allSatisfy(
                            plan -> {
                                assertThat(plan.inherited()).isTrue();
                                assertThat(plan.inheritedFromTaskId()).isEqualTo(root);
                                assertThat(plan.id()).isNull();
                                assertThat(plan.canCancel()).isFalse();
                            });
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.nocode_task_plan WHERE task_id=?",
                                    Long.class,
                                    child))
                    .isZero();
        }
    }

    @Test
    void nextWeekPlanKeepsCompleteStructureAndOnlyOwnedMembershipQualifiesTheGroup() {
        Detail tree =
                create(
                        "root",
                        OTHER,
                        List.of(
                                node("mine", null, WORKER),
                                node("colleague", null, OTHER),
                                configured(
                                        "open",
                                        "colleague",
                                        null,
                                        AssignmentMode.OPEN,
                                        List.of(),
                                        null)));
        Detail unrelated =
                create("unrelated", OTHER, List.of(node("unrelated-child", null, OTHER)));
        String mine = row(tree, "mine").id();
        TaskPlanning.ChecklistContext context =
                tasks.checklistContext(
                        new TaskPlanning.ContextQuery(List.of(mine), TaskPlanning.Target.SELF),
                        WORKER);
        tasks.checklist(
                new TaskPlanning.ChecklistChange(
                        List.of(mine),
                        TaskPlanning.Target.SELF,
                        TaskPlanning.ChecklistAction.ADD,
                        Period.WEEK,
                        context.nextWeekStart(),
                        null,
                        Map.of(mine, context.items().getFirst().version()),
                        key()),
                WORKER);
        Query next =
                checklistQuery("WEEK", TaskPlanning.Filter.PLANNED, 1, 1, context.nextWeekStart());
        PersonalTreeNode header = tasks.personalTreePage(next, WORKER).getList().getFirst();
        assertThat(tasks.personalTreePage(next, WORKER).getTotal()).isEqualTo(1);
        assertThat(header.task().id()).isEqualTo(tree.task().id());
        assertThat(header.contextOnly()).isTrue();
        assertThat(header.detailVisible()).isFalse();
        assertThat(header.anchorTaskId()).isEqualTo(mine);
        assertThat(header.myPendingCount()).isEqualTo(1);
        assertThat(header.matchingChildCount()).isEqualTo(2);
        List<PersonalTreeNode> branch = children(next, tree.task().id(), WORKER);
        assertThat(branch).hasSize(2);
        assertThat(branch.stream().filter(n -> !n.contextOnly()))
                .extracting(n -> n.task().id())
                .containsExactly(mine);
        assertThat(branch.stream().filter(n -> !n.detailVisible()))
                .allSatisfy(
                        n -> {
                            assertThat(n.task().canStart()).isFalse();
                            assertThat(n.task().canExecute()).isFalse();
                            assertThat(n.task().canPlan()).isFalse();
                            assertThat(n.task().description()).isNull();
                            assertThat(n.task().binding()).isNull();
                        });
        assertThat(children(next, row(tree, "colleague").id(), WORKER)).hasSize(1);
        assertThatThrownBy(() -> children(next, unrelated.task().id(), WORKER))
                .hasMessageContaining("当前筛选");
        assertThat(
                        tasks.personalTreePage(
                                        checklistQuery("WEEK", TaskPlanning.Filter.PLANNED), WORKER)
                                .getTotal())
                .isZero();
        assertThat(
                        tasks.personalTreePage(
                                        checklistQuery("ALL", TaskPlanning.Filter.UNPLANNED),
                                        WORKER)
                                .getTotal())
                .isZero();
        assertThat(tasks.detail(mine, WORKER).task().plans()).hasSize(1);
        assertThat(tasks.detail(row(tree, "colleague").id(), OTHER).task().plans()).isEmpty();
        assertThat(tasks.detail(header.anchorTaskId(), WORKER).structure()).hasSize(4);
    }

    @Test
    void unifiedChecklistContextNeverExposesPrivateSiblingsAndRechecksAssignmentOnExpansion() {
        Detail tree =
                create(
                        "private-root",
                        OTHER,
                        List.of(
                                node("private-stage", null, OTHER),
                                node("mine", "private-stage", WORKER),
                                node("private-sibling", null, OTHER)));
        String root = tree.task().id();
        String mine = row(tree, "mine").id();
        addChecklist(mine, Period.WEEK, WORKER);
        Query view = checklistQuery("WEEK", TaskPlanning.Filter.PLANNED);
        PersonalTreeNode header = tasks.personalTreePage(view, WORKER).getList().getFirst();
        assertThat(header.task().id()).isEqualTo(root);
        assertThat(header.detailVisible()).isFalse();
        assertThat(header.contextOnly()).isTrue();
        assertThat(header.task().canStart()).isFalse();
        assertThat(header.task().canPlan()).isFalse();
        assertThat(header.task().description()).isNull();
        assertThat(children(view, root, WORKER))
                .extracting(n -> n.task().id())
                .containsExactly(
                        row(tree, "private-stage").id(), row(tree, "private-sibling").id());
        assertThat(header.anchorTaskId()).isEqualTo(mine);
        assertThatThrownBy(() -> tasks.detail(root, WORKER)).hasMessageContaining("权限");
        assertThat(children(view, row(tree, "private-stage").id(), WORKER))
                .extracting(n -> n.task().id())
                .containsExactly(mine);
        assertThat(children(view, row(tree, "private-sibling").id(), WORKER)).isEmpty();
        tasks.assign(
                new Assign(
                        mine,
                        tasks.detail(mine, OWNER).task().revision(),
                        AssignmentMode.ASSIGNED,
                        OTHER,
                        List.of(),
                        key(),
                        "验证展开时重新校验当前归属"),
                OWNER);
        assertThat(tasks.personalTreePage(view, WORKER).getTotal()).isZero();
        assertThatThrownBy(() -> children(view, root, WORKER)).hasMessageContaining("当前筛选");
    }

    @Test
    void unifiedAllTasksKeepsPendingAcceptanceForTheAcceptorWithoutAddingItToTheirPlans() {
        NodeInput input = node("acceptance", null, OTHER);
        Detail tree =
                create(
                        new NodeInput(
                                input.id(),
                                input.parentId(),
                                input.title(),
                                input.description(),
                                input.assigneeId(),
                                input.urgency(),
                                input.priority(),
                                input.schedule(),
                                input.predecessorIds(),
                                input.binding(),
                                input.sharing(),
                                input.entries(),
                                input.assignmentMode(),
                                input.candidateUserIds(),
                                null,
                                WORKER),
                        List.of(node("acceptance-child", null, OTHER)));
        String root = tree.task().id();
        assertThat(tasks.personalTreePage(checklistQuery("ALL", null), WORKER).getTotal()).isZero();
        transition(root, Action.START, OTHER);
        transition(row(tree, "acceptance-child").id(), Action.START, OTHER);
        transition(row(tree, "acceptance-child").id(), Action.COMPLETE, OTHER);
        PersonalTreeNode pending =
                tasks.personalTreePage(checklistQuery("ALL", null), WORKER).getList().getFirst();
        assertThat(pending.task().id()).isEqualTo(root);
        assertThat(pending.task().status()).isEqualTo("PENDING_ACCEPTANCE");
        assertThat(pending.contextOnly()).isFalse();
        assertThat(pending.detailVisible()).isTrue();
        assertThat(pending.task().canAccept()).isTrue();
        assertThat(pending.task().canPlan()).isFalse();
        Query acceptance = query("ACCEPTANCE", marker, null, 1, 100, null);
        PageResult<PersonalTreeNode> acceptanceGroups = tasks.personalTreePage(acceptance, WORKER);
        assertThat(acceptanceGroups.getTotal()).isEqualTo(1);
        assertThat(acceptanceGroups.getList().getFirst().task().canAccept()).isTrue();
        assertThat(acceptanceGroups.getList().getFirst().matchingChildCount()).isEqualTo(1);
        assertThat(acceptanceGroups.getList().getFirst().completedChildCount()).isEqualTo(1);
        assertThat(children(acceptance, root, WORKER))
                .hasSize(1)
                .allMatch(PersonalTreeNode::contextOnly);
        assertThat(tasks.personalTreePage(acceptance, OTHER).getTotal()).isZero();
        assertThatThrownBy(() -> children(acceptance, root, OTHER)).hasMessageContaining("当前筛选");
        for (Query view :
                List.of(
                        checklistQuery("ALL", TaskPlanning.Filter.UNPLANNED),
                        checklistQuery("WEEK", TaskPlanning.Filter.PLANNED),
                        checklistQuery("TODAY", TaskPlanning.Filter.PLANNED)))
            assertThat(tasks.personalTreePage(view, WORKER).getTotal()).isZero();
        assertThat(
                        tasks.personalTreePage(query("ALL", marker, null, 1, 100, null), WORKER)
                                .getTotal())
                .isZero();
    }

    @Test
    void expansionRechecksOwnershipAndRejectsUnrelatedScopes() {
        Detail tree = create("root", WORKER, List.of(node("child", null, WORKER)));
        Query query = query(1, 100);
        tasks.assign(
                new Assign(
                        tree.task().id(),
                        tree.task().revision(),
                        AssignmentMode.ASSIGNED,
                        OTHER,
                        List.of(),
                        key(),
                        "测试改派"),
                OWNER);
        assertThat(children(query, tree.task().id(), WORKER)).hasSize(1);
        assertThat(tasks.personalTreePage(query, WORKER).getList())
                .extracting(n -> n.task().id())
                .containsExactly(tree.task().id());
        for (String tab : List.of("RECENT", "CLAIMABLE"))
            assertThatThrownBy(
                            () ->
                                    tasks.personalTreePage(
                                            query(tab, marker, null, 1, 100, null), WORKER))
                    .hasMessageContaining("个人任务树");
        for (String scope : List.of("VISIBLE", "MANAGE")) {
            Query otherScope =
                    new Query(
                            scope, "ALL", DATE, marker, null, null, null, null, null, null, null,
                            null, 1, 100);
            assertThatThrownBy(() -> tasks.personalTreePage(otherScope, WORKER))
                    .hasMessageContaining("个人任务树");
        }
        Query rootsOnly =
                new Query(
                        "MANAGE", "ALL", DATE, marker, null, null, null, null, null, null, null,
                        null, 1, 100, null, null, null, true);
        assertThatThrownBy(() -> tasks.personalTreePage(rootsOnly, WORKER))
                .hasMessageContaining("个人任务树");
        Query team =
                new Query(
                        "MANAGE",
                        "ALL",
                        DATE,
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
                        false,
                        TaskPlanning.Scope.TEAM,
                        null,
                        null);
        assertThatThrownBy(() -> tasks.personalTreePage(team, WORKER))
                .hasMessageContaining("个人任务树");
    }

    @Test
    void assignedChildHasReadonlyAncestorContextBeforeStartWithoutPrivateDetails() {
        Detail tree =
                create(
                        "root",
                        OWNER,
                        List.of(
                                node("stage", null, OTHER),
                                node("leaf", "stage", WORKER),
                                node("secret-sibling", null, OTHER)));
        String leaf = row(tree, "leaf").id(), stage = row(tree, "stage").id();
        jdbc.update(
                "update public.nocode_task_instance set config_json=jsonb_set(config_json::jsonb,"
                        + " '{description}', to_jsonb(?::text))::text where id=?",
                "祖先私有任务内容",
                stage);
        tasks.comment(new AddComment(tree.task().id(), null, "祖先私有评论", List.of(), key()), OWNER);
        tasks.comment(
                new AddComment(row(tree, "secret-sibling").id(), null, "兄弟私有评论", List.of(), key()),
                OWNER);
        Detail visible = tasks.detail(leaf, WORKER);
        assertThat(visible.nodes()).extracting(Row::id).containsExactly(leaf);
        assertThat(visible.comments()).isEmpty();
        assertThat(visible.events()).allMatch(event -> event.taskId().equals(leaf));
        assertThat(visible.task().canStart()).isFalse();
        assertThat(visible.task().blockedReason()).contains(marker + "root", "个人树测试" + OWNER);
        assertThat(visible.task().ancestorContext())
                .extracting(AncestorContext::id)
                .containsExactly(tree.task().id(), stage);
        assertThat(visible.task().ancestorContext()).allMatch(context -> !context.detailVisible());
        assertThat(visible.task().ancestorContext())
                .extracting(AncestorContext::assigneeName)
                .containsExactly("个人树测试" + OWNER, "个人树测试" + OTHER);
        assertThat(visible.task().ancestorContext())
                .extracting(AncestorContext::status)
                .containsOnly("PENDING");
        ObjectMapper json = servicesContext.getBean(ObjectMapper.class);
        for (AncestorContext context : visible.task().ancestorContext()) {
            JsonNode summary = json.valueToTree(context);
            assertThat(summary.properties())
                    .extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder(
                            "id", "parentId", "title", "assigneeName", "status", "detailVisible");
        }
        assertThat(json.valueToTree(visible).toString())
                .doesNotContain("祖先私有评论", "兄弟私有评论", "祖先私有任务内容");
        assertThat(visible.structure())
                .extracting(StructureNode::id)
                .containsExactlyElementsOf(tree.nodes().stream().map(Row::id).toList());
        assertThat(visible.structure().stream().filter(StructureNode::detailVisible))
                .extracting(StructureNode::id)
                .containsExactly(leaf);
        assertThat(visible.structure().stream().filter(node -> node.id().equals(leaf)))
                .singleElement()
                .satisfies(node -> assertThat(node.parentId()).isEqualTo(stage));
        for (StructureNode node : visible.structure()) {
            JsonNode summary = json.valueToTree(node);
            assertThat(summary.properties())
                    .extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder(
                            "id",
                            "rootId",
                            "parentId",
                            "title",
                            "status",
                            "assigneeName",
                            "predecessorIds",
                            "expectedStart",
                            "expectedEnd",
                            "detailVisible",
                            "scheduleSummary");
            assertThat(summary.get("scheduleSummary").properties())
                    .extracting(Map.Entry::getKey)
                    .containsExactlyInAnyOrder("source", "partial", "warnings");
            assertThat(node.scheduleSummary().source())
                    .isIn(ScheduleSource.UNSCHEDULED, ScheduleSource.ROLLUP);
            assertThat(node.scheduleSummary().partial()).isTrue();
            // 只读结构可知是否排完整日期，但不能借排期摘要带出私有内容或关联材料。
            assertThat(node.scheduleSummary().warnings())
                    .allMatch(warning -> warning.equals("部分下级尚未排完整日期，当前显示已知范围"));
        }
        Row entry = tasks.personalTreePage(query(1, 100), WORKER).getList().getFirst().task();
        assertThat(entry.id()).isEqualTo(tree.task().id());
        PersonalTreeNode leafEntry = children(query(1, 100), stage, WORKER).getFirst();
        assertThat(leafEntry.task().ancestorContext()).isEqualTo(visible.task().ancestorContext());
        assertThat(leafEntry.anchorTaskId()).isEqualTo(leaf);
        for (String ancestor : List.of(tree.task().id(), stage)) {
            assertThatThrownBy(() -> tasks.detail(ancestor, WORKER)).hasMessageContaining("权限");
            assertThatThrownBy(() -> tasks.readiness(ancestor, WORKER)).hasMessageContaining("权限");
            assertThatThrownBy(() -> tasks.form(ancestor, WORKER)).hasMessageContaining("权限");
        }
        AdminUserApi users = servicesContext.getBean(AdminUserApi.class);
        Mockito.clearInvocations(users);
        tasks.detail(tree.task().id(), OWNER);
        Mockito.verify(users, Mockito.times(1)).getUserList(Mockito.anyCollection());
    }

    @Test
    void structureShowsUnclaimedAndColleagueDependenciesWithoutGrantingTheirDetailsOrActions() {
        Schedule dates =
                new Schedule(TimeMode.FIXED, LocalDate.now().plusDays(1).atStartOfDay(), 0, 2);
        NodeInput predecessor =
                new NodeInput(
                        "survey",
                        null,
                        marker + "survey",
                        "<p>勘察私有内容</p>",
                        null,
                        null,
                        null,
                        dates,
                        List.of(),
                        null,
                        null,
                        null,
                        AssignmentMode.OPEN,
                        List.of(OTHER));
        Detail tree =
                create(
                        "root",
                        WORKER,
                        List.of(
                                predecessor,
                                node("colleague", null, OTHER),
                                configured(
                                        "mine",
                                        null,
                                        WORKER,
                                        AssignmentMode.ASSIGNED,
                                        List.of("survey", "colleague"),
                                        dates)));
        String root = tree.task().id(),
                survey = row(tree, "survey").id(),
                colleague = row(tree, "colleague").id(),
                mine = row(tree, "mine").id();
        tasks.comment(new AddComment(survey, null, "勘察私有评论", List.of(), key()), OWNER);
        transition(root, Action.START, WORKER);
        AdminUserApi users = servicesContext.getBean(AdminUserApi.class);
        Mockito.clearInvocations(users);
        Detail waiting = tasks.detail(mine, WORKER);
        Mockito.verify(users, Mockito.times(1)).getUserList(Mockito.anyCollection());
        Mockito.verify(users, Mockito.never()).getUser(OTHER);
        assertThat(waiting.nodes()).extracting(Row::id).containsExactly(root, mine);
        assertThat(waiting.structure()).hasSize(4);
        assertThat(waiting.structure()).allMatch(node -> node.rootId().equals(root));
        StructureNode surveySummary =
                waiting.structure().stream()
                        .filter(node -> node.id().equals(survey))
                        .findFirst()
                        .orElseThrow();
        assertThat(surveySummary.parentId()).isEqualTo(root);
        assertThat(surveySummary.assigneeName()).isEqualTo("待领取");
        assertThat(surveySummary.status()).isEqualTo("PENDING");
        assertThat(surveySummary.expectedStart()).isEqualTo(row(tree, "survey").expectedStart());
        assertThat(surveySummary.expectedEnd()).isEqualTo(row(tree, "survey").expectedEnd());
        assertThat(surveySummary.detailVisible()).isFalse();
        assertThat(waiting.structure().stream().filter(node -> node.id().equals(colleague)))
                .singleElement()
                .satisfies(
                        node -> {
                            assertThat(node.assigneeName()).isEqualTo("个人树测试" + OTHER);
                            assertThat(node.detailVisible()).isFalse();
                        });
        assertThat(waiting.structure().stream().filter(node -> node.id().equals(mine)))
                .singleElement()
                .satisfies(
                        node ->
                                assertThat(node.predecessorIds())
                                        .containsExactly(survey, colleague));
        assertThat(waiting.task().canStart()).isFalse();
        assertThat(waiting.task().blockedReason()).contains(marker + "survey", "待领取");
        assertThat(servicesContext.getBean(ObjectMapper.class).valueToTree(waiting).toString())
                .doesNotContain("勘察私有内容", "勘察私有评论");
        for (String hidden : List.of(survey, colleague)) {
            assertThatThrownBy(() -> tasks.detail(hidden, WORKER)).hasMessageContaining("权限");
            assertThatThrownBy(() -> tasks.readiness(hidden, WORKER)).hasMessageContaining("权限");
            assertThatThrownBy(() -> tasks.form(hidden, WORKER)).hasMessageContaining("权限");
            assertThatThrownBy(
                            () ->
                                    tasks.transition(
                                            new Transition(hidden, 0, Action.START, null, key()),
                                            WORKER))
                    .hasMessageContaining("权限");
        }
        tasks.claim(new Claim(survey, row(tree, "survey").revision(), key()), OTHER);
        transition(survey, Action.START, OTHER);
        tasks.transition(
                new Transition(
                        root,
                        tasks.detail(root, WORKER).task().revision(),
                        Action.PAUSE,
                        "等待共同资源",
                        key()),
                WORKER);
        assertThat(tasks.detail(mine, WORKER).structure())
                .allMatch(node -> node.status().equals("PAUSED"));
        assertThat(tasks.detail(survey, OTHER).task().status()).isEqualTo("RUNNING");
        transition(root, Action.RESUME, WORKER);
        transition(survey, Action.COMPLETE, OTHER);
        Detail firstCompleted = tasks.detail(mine, WORKER);
        assertThat(firstCompleted.task().blockedReason()).contains(marker + "colleague");
        assertThat(firstCompleted.structure().stream().filter(node -> node.id().equals(survey)))
                .singleElement()
                .satisfies(
                        node -> {
                            assertThat(node.status()).isEqualTo("COMPLETED");
                            assertThat(node.assigneeName()).isEqualTo("个人树测试" + OTHER);
                            assertThat(node.detailVisible()).isFalse();
                        });
        transition(colleague, Action.START, OTHER);
        transition(colleague, Action.COMPLETE, OTHER);
        assertThat(tasks.detail(mine, WORKER).task().canStart()).isTrue();
        transition(mine, Action.START, WORKER);
        assertThat(tasks.detail(mine, WORKER).task().status()).isEqualTo("RUNNING");
    }

    @Test
    void structureExplainsUnassignedAndFollowRootWithoutChangingAssignment() {
        Detail tree =
                create(
                        configured("root", null, null, AssignmentMode.OPEN, List.of(), null),
                        List.of(
                                configured(
                                        "follow",
                                        null,
                                        null,
                                        AssignmentMode.FOLLOW_ROOT,
                                        List.of(),
                                        null),
                                configured(
                                        "unassigned",
                                        null,
                                        null,
                                        AssignmentMode.UNASSIGNED,
                                        List.of(),
                                        null),
                                node("mine", null, WORKER)));
        Detail visible = tasks.detail(row(tree, "mine").id(), WORKER);
        assertThat(visible.structure())
                .extracting(StructureNode::assigneeName)
                .containsExactly("待领取", "随总负责人（待承接）", "待分配", "个人树测试" + WORKER);
        assertThat(visible.nodes()).extracting(Row::id).containsExactly(row(tree, "mine").id());
        assertThat(
                        tasks.detail(tree.task().id(), OWNER).nodes().stream()
                                .filter(node -> !node.id().equals(row(tree, "mine").id())))
                .allMatch(node -> node.assigneeId() == null);
    }

    @Test
    void waitingReasonFollowsTheFirstUnstartedAssignedAncestorWithoutStartingDescendants() {
        Detail tree =
                create(
                        "root",
                        OWNER,
                        List.of(node("stage", null, OTHER), node("leaf", "stage", WORKER)));
        String root = tree.task().id(),
                stage = row(tree, "stage").id(),
                leaf = row(tree, "leaf").id();
        assertThat(tasks.detail(leaf, WORKER).task().blockedReason())
                .isEqualTo("等待上级任务「" + marker + "root」（负责人：个人树测试" + OWNER + "）开始");
        transition(root, Action.START, OWNER);
        Row waiting = tasks.detail(leaf, WORKER).task();
        assertThat(waiting.blockedReason())
                .contains(marker + "stage", "个人树测试" + OTHER)
                .doesNotContain(marker + "root");
        assertThat(waiting.status()).isEqualTo("PENDING");
        assertThat(waiting.canStart()).isFalse();
        transition(stage, Action.START, OTHER);
        Row ready = tasks.detail(leaf, WORKER).task();
        assertThat(ready.status()).isEqualTo("PENDING");
        assertThat(ready.canStart()).isTrue();
        assertThat(ready.blockedReason()).isNull();
        assertThat(ready.ancestorContext())
                .extracting(AncestorContext::status)
                .containsOnly("RUNNING");
        transition(leaf, Action.START, WORKER);
        assertThat(tasks.detail(leaf, WORKER).task().status()).isEqualTo("RUNNING");
    }

    @Test
    void summaryExceptionsKeepInheritedDependenciesAndExposeOnlyWaitingSummary() {
        Detail tree =
                create(
                        configured("root", null, null, AssignmentMode.UNASSIGNED, List.of(), null),
                        List.of(
                                node("predecessor", null, OTHER),
                                configured(
                                        "stage",
                                        null,
                                        null,
                                        AssignmentMode.OPEN,
                                        List.of("predecessor"),
                                        null),
                                node("leaf", "stage", WORKER),
                                node("secret-sibling", null, OTHER)));
        String predecessor = row(tree, "predecessor").id(), leaf = row(tree, "leaf").id();
        AdminUserApi users = servicesContext.getBean(AdminUserApi.class);
        Mockito.clearInvocations(users);
        Detail waiting = tasks.detail(leaf, WORKER);
        Mockito.verify(users, Mockito.times(1)).getUserList(Mockito.anyCollection());
        // OTHER 只负责不可见前置任务；等待说明必须取批量结果，不能额外查此执行人。
        Mockito.verify(users, Mockito.never()).getUser(OTHER);
        assertThat(waiting.task().blockedReason())
                .contains("前置任务", marker + "stage", marker + "predecessor", "个人树测试" + OTHER)
                .doesNotContain(marker + "secret-sibling");
        assertThat(waiting.nodes()).extracting(Row::id).containsExactly(leaf);
        assertThat(waiting.task().ancestorContext())
                .extracting(AncestorContext::assigneeName)
                .containsExactly("未分配负责人", "待领取");
        assertThatThrownBy(() -> tasks.detail(predecessor, WORKER)).hasMessageContaining("权限");
        transition(predecessor, Action.START, OTHER);
        transition(predecessor, Action.COMPLETE, OTHER);
        assertThat(tasks.detail(leaf, WORKER).task().canStart()).isTrue();
        transition(leaf, Action.START, WORKER);
        assertThat(tasks.detail(tree.task().id(), OWNER).task().status()).isEqualTo("PENDING");
        assertThat(tasks.detail(row(tree, "stage").id(), OWNER).task().status())
                .isEqualTo("PENDING");
    }

    @Test
    void futureAncestorDatesAreReferenceOnlyAndRemainUnchangedWhenChildStartsEarly() {
        LocalDateTime start = LocalDateTime.now().plusDays(2).withSecond(0).withNano(0);
        Detail tree =
                create(
                        configured("root", null, null, AssignmentMode.UNASSIGNED, List.of(), null),
                        List.of(
                                configured(
                                        "timed-stage",
                                        null,
                                        null,
                                        AssignmentMode.UNASSIGNED,
                                        List.of(),
                                        new Schedule(TimeMode.FIXED, start, 0, 0)),
                                node("leaf", "timed-stage", WORKER)));
        Row ready = tasks.detail(row(tree, "leaf").id(), WORKER).task();
        assertThat(ready.canStart()).isTrue();
        assertThat(ready.blockedReason()).isNull();
        transition(ready.id(), Action.START, WORKER);
        assertThat(tasks.detail(ready.id(), WORKER).task().actualStart()).isBefore(start);
        Row stage = tasks.detail(row(tree, "timed-stage").id(), OWNER).task();
        assertThat(stage.expectedStart()).isEqualTo(start);
        assertThat(stage.actualStart()).isNull();
    }

    @Test
    void earlyStartPreservesParentDependencyPausePermissionAndPlanBoundaries() {
        LocalDateTime expected = LocalDateTime.now().plusWeeks(1).withNano(0);
        Schedule future = new Schedule(TimeMode.FIXED, expected, 0, 1);
        Detail tree =
                create(
                        configured(
                                "root", null, WORKER, AssignmentMode.ASSIGNED, List.of(), future),
                        List.of(
                                configured(
                                        "before",
                                        null,
                                        WORKER,
                                        AssignmentMode.ASSIGNED,
                                        List.of(),
                                        future),
                                configured(
                                        "after",
                                        null,
                                        WORKER,
                                        AssignmentMode.ASSIGNED,
                                        List.of("before"),
                                        future)));
        String root = tree.task().id(),
                before = row(tree, "before").id(),
                after = row(tree, "after").id();
        assertThat(tasks.detail(root, WORKER).task().canStart()).isTrue();
        assertThat(tasks.detail(before, WORKER).task().blockedReason()).contains("上级任务");
        assertThatThrownBy(() -> transition(before, Action.START)).hasMessageContaining("上级任务");
        assertThatThrownBy(() -> transition(root, Action.START, OWNER)).hasMessageContaining("负责人");
        transition(root, Action.START);
        Row started = tasks.detail(root, WORKER).task();
        assertThat(started.actualStart()).isBefore(expected);
        assertThat(started.expectedStart()).isEqualTo(expected);
        assertThat(started.expectedEnd()).isEqualTo(tree.task().expectedEnd());
        assertThat(started.baselineStart()).isEqualTo(tree.task().baselineStart());
        assertThat(started.plans()).isEmpty();
        transition(root, Action.PAUSE);
        assertThat(tasks.detail(before, WORKER).task().canStart()).isFalse();
        assertThatThrownBy(() -> transition(before, Action.START)).hasMessageContaining("暂停");
        transition(root, Action.RESUME);
        assertThat(tasks.detail(after, WORKER).task().blockedReason()).contains("前置");
        assertThatThrownBy(() -> transition(after, Action.START)).hasMessageContaining("前置");
        transition(before, Action.START);
        transition(before, Action.COMPLETE);
        assertThat(tasks.detail(after, WORKER).task().canStart()).isTrue();
        transition(after, Action.START);
        assertThat(tasks.detail(after, WORKER).task().expectedStart()).isEqualTo(expected);
        assertThat(tasks.detail(after, WORKER).task().actualStart()).isBefore(expected);
    }

    @Test
    void personalGroupsSortBeforePaginationByUnfinishedDeadlinePriorityAndStableIdentity() {
        Detail done = create("done", WORKER, List.of());
        transition(done.task().id(), Action.START);
        transition(done.task().id(), Action.COMPLETE);
        Detail unscheduled = create("unscheduled", WORKER, List.of());
        LocalDateTime due = LocalDateTime.now().plusDays(4).withNano(0);
        Detail low = create(deadlineNode("low", WORKER, due, Priority.LOW), List.of());
        Detail high = create(deadlineNode("high", WORKER, due, Priority.HIGH), List.of());
        Detail first =
                create(
                        "child-deadline",
                        WORKER,
                        List.of(
                                deadlineNode(
                                        "first-child", WORKER, due.minusDays(1), Priority.LOW)));
        // 其他人的私有子任务虽属于同一实例，但不能抢占当前用户的排序位置。
        Detail privateDate =
                create(
                        "private-date",
                        WORKER,
                        List.of(deadlineNode("others", OTHER, due.minusDays(3), Priority.HIGH)));
        List<String> sorted = new ArrayList<>();
        for (int page = 1; page <= 6; page++) {
            PageResult<PersonalTreeNode> result =
                    tasks.personalTreePage(
                            checklistQuery("ALL", null, page, 1, LocalDate.now()), WORKER);
            assertThat(result.getTotal()).isEqualTo(6);
            sorted.add(result.getList().getFirst().task().id());
        }
        assertThat(sorted)
                .containsExactly(
                        first.task().id(),
                        high.task().id(),
                        low.task().id(),
                        unscheduled.task().id(),
                        privateDate.task().id(),
                        done.task().id());
    }

    @Test
    void unplannedKeepsOnlyNecessaryGroupContextAfterMembershipRefresh() {
        Detail tree =
                create(
                        "root",
                        OTHER,
                        List.of(node("one", null, WORKER), node("two", null, WORKER)));
        Query unplanned = checklistQuery("ALL", TaskPlanning.Filter.UNPLANNED);
        // 别人的上级计划不替本人安排下级，因此未纳入筛选仍保留必要根上下文。
        addChecklist(tree.task().id(), Period.DAY, OTHER);
        addChecklist(row(tree, "one").id(), Period.WEEK, WORKER);
        List<PersonalTreeNode> page = tasks.personalTreePage(unplanned, WORKER).getList();
        assertThat(page)
                .singleElement()
                .satisfies(
                        group -> {
                            assertThat(group.task().id()).isEqualTo(tree.task().id());
                            assertThat(group.contextOnly()).isTrue();
                        });
        assertThat(children(unplanned, tree.task().id(), WORKER))
                .filteredOn(n -> !n.contextOnly())
                .extracting(n -> n.task().id())
                .containsExactly(row(tree, "two").id());
        addChecklist(row(tree, "two").id(), Period.WEEK, WORKER);
        assertThat(tasks.personalTreePage(unplanned, WORKER).getList()).isEmpty();
        assertThatThrownBy(() -> children(unplanned, tree.task().id(), WORKER))
                .hasMessageContaining("当前筛选");
    }

    @Test
    void completedGroupsUseLatestPersonalHandlingInsteadOfExpectedDate() {
        Detail older = create("older", WORKER, List.of());
        Detail newer = create("newer", WORKER, List.of());
        transition(older.task().id(), Action.START);
        transition(older.task().id(), Action.COMPLETE);
        transition(newer.task().id(), Action.START);
        transition(newer.task().id(), Action.COMPLETE);
        assertThat(
                        tasks.personalTreePage(query("DONE", marker, null, 1, 100, null), WORKER)
                                .getList())
                .extracting(n -> n.task().id())
                .containsExactly(newer.task().id(), older.task().id());
    }

    @Test
    void claimableSummaryDoesNotDiscloseAncestorsUntilAssignmentAndReadableAncestorsCanNavigate() {
        Detail tree =
                create(
                        "root",
                        OWNER,
                        List.of(
                                configured(
                                        "open", null, null, AssignmentMode.OPEN, List.of(), null)));
        Row open = row(tree, "open");
        assertThat(tasks.detail(open.id(), WORKER).task().ancestorContext()).isEmpty();
        Detail claimed = tasks.claim(new Claim(open.id(), open.revision(), key()), WORKER);
        assertThat(claimed.task().ancestorContext())
                .hasSize(1)
                .allMatch(context -> !context.detailVisible());
        assertThat(tasks.detail(open.id(), OWNER).task().ancestorContext())
                .hasSize(1)
                .allMatch(AncestorContext::detailVisible);
        assertThat(tasks.detail(tree.task().id(), OWNER).task().ancestorContext()).isEmpty();
    }

    @Test
    void malformedCrossRootParentCannotLeakAnotherInstancesSummary() {
        Detail first = create("first-root", OWNER, List.of(node("leaf", null, WORKER)));
        Detail foreign = create("foreign-secret", OWNER, List.of());
        String leaf = row(first, "leaf").id();
        jdbc.update(
                "update public.nocode_task_instance set parent_id=? where id=?",
                foreign.task().id(),
                leaf);
        try {
            Detail visible = tasks.detail(leaf, WORKER);
            assertThat(visible.task().ancestorContext()).isEmpty();
            assertThat(visible.task().blockedReason())
                    .contains("上级任务不存在")
                    .doesNotContain(marker + "foreign-secret");
            assertThat(visible.nodes()).extracting(Row::id).containsExactly(leaf);
        } finally {
            jdbc.update(
                    "update public.nocode_task_instance set parent_id=? where id=?",
                    first.task().id(),
                    leaf);
        }
    }

    private Detail create(String name, long assignee, List<NodeInput> nodes) {
        return create(node(name, null, assignee), nodes);
    }

    private Detail create(NodeInput root, List<NodeInput> nodes) {
        Detail result =
                tasks.create(
                        new Create(
                                root, null, null, null, null, null, null, key(), nodes, null, null,
                                null),
                        OWNER);
        roots.add(result.task().rootId());
        return result;
    }

    private NodeInput node(String id, String parent, long assignee) {
        return configured(id, parent, assignee, AssignmentMode.ASSIGNED, List.of(), null);
    }

    private NodeInput deadlineNode(String id, long assignee, LocalDateTime end, Priority priority) {
        return new NodeInput(
                id,
                null,
                marker + id,
                null,
                assignee,
                null,
                priority,
                new Schedule(TimeMode.FIXED, null, 0, 0, end),
                List.of(),
                null,
                null,
                null,
                AssignmentMode.ASSIGNED,
                List.of());
    }

    private NodeInput configured(
            String id,
            String parent,
            Long assignee,
            AssignmentMode mode,
            List<String> predecessors,
            Schedule schedule) {
        return new NodeInput(
                id,
                parent,
                marker + id,
                null,
                assignee,
                null,
                null,
                schedule == null ? new Schedule(TimeMode.UNSCHEDULED, null, 0, 0) : schedule,
                predecessors,
                null,
                null,
                null,
                mode,
                List.of());
    }

    private Row row(Detail detail, String suffix) {
        return detail.nodes().stream()
                .filter(n -> n.title().equals(marker + suffix))
                .findFirst()
                .orElseThrow();
    }

    private List<PersonalTreeNode> children(Query query, String parent, long actor) {
        return tasks.personalTreeChildren(new PersonalTreeChildren(query, parent), actor);
    }

    private Query query(int page, int size) {
        return query("POOL", marker, null, page, size, null);
    }

    private Query checklistQuery(String tab, TaskPlanning.Filter filter) {
        return checklistQuery(tab, filter, 1, 100, LocalDate.now());
    }

    private Query checklistQuery(
            String tab, TaskPlanning.Filter filter, int page, int size, LocalDate date) {
        return new Query(
                "MINE",
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
                false,
                TaskPlanning.Scope.PERSONAL,
                null,
                filter,
                TaskPlanning.Mode.CHECKLIST);
    }

    private void addChecklist(String id, Period period, long actor) {
        TaskPlanning.ChecklistContext context =
                tasks.checklistContext(
                        new TaskPlanning.ContextQuery(List.of(id), TaskPlanning.Target.SELF),
                        actor);
        tasks.checklist(
                new TaskPlanning.ChecklistChange(
                        List.of(id),
                        TaskPlanning.Target.SELF,
                        TaskPlanning.ChecklistAction.ADD,
                        period,
                        period == Period.DAY ? context.today() : context.weekStart(),
                        null,
                        Map.of(id, context.items().getFirst().version()),
                        key()),
                actor);
    }

    private Query query(
            String tab,
            String search,
            String status,
            int page,
            int size,
            TaskPlanning.Scope scheduleScope) {
        return new Query(
                "MINE",
                tab,
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
                scheduleScope,
                null,
                null);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private void transition(String id, Action action) {
        transition(id, action, WORKER);
    }

    private void transition(String id, Action action, long actor) {
        tasks.transition(
                new Transition(id, tasks.detail(id, actor).task().revision(), action, null, key()),
                actor);
    }
}
