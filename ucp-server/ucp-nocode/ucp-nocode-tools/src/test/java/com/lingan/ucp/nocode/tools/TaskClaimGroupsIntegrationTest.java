package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskClaims;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.util.*;
import java.util.concurrent.*;

/** 当前开发库验证真实分组、连续默认分工及受限转交；仅清理本类登记的随机实例。 */
class TaskClaimGroupsIntegrationTest {
    private static final long OWNER = 10001L, FIRST = 21001L, SECOND = 21002L;
    private TaskCenterService tasks;
    private final Set<String> roots = new LinkedHashSet<>(), templates = new LinkedHashSet<>();
    private final String marker = "claim_group_" + UUID.randomUUID() + "_";

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
        IMsgSendService messages = servicesContext.getBean(IMsgSendService.class);
        Mockito.reset(messages);
        Mockito.when(messages.send(Mockito.any())).thenReturn(999L);
        Mockito.when(servicesContext.getBean(AdminUserApi.class).getUser(Mockito.anyLong()))
                .thenAnswer(
                        i -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(i.getArgument(0));
                            user.setNickname("领取测试" + user.getId());
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
                    "delete from public.nocode_task_comment where task_id in(select id from"
                            + " public.nocode_task_instance where root_id=?)",
                    root);
            jdbc.update(
                    "delete from public.nocode_task_plan where task_id in(select id from"
                            + " public.nocode_task_instance where root_id=?)",
                    root);
            jdbc.update("delete from public.nocode_task_event where root_id=?", root);
            jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
            assertThat(
                            jdbc.queryForObject(
                                    "select count(*) from public.nocode_task_instance where"
                                            + " root_id=?",
                                    Integer.class,
                                    root))
                    .isZero();
        }
        for (String id : templates) {
            jdbc.update("delete from public.nocode_task_template_version where template_id=?", id);
            jdbc.update(
                    "delete from public.nocode_task_template where id=? and name like ?",
                    id,
                    marker + "%");
        }
    }

    @Test
    void rootOwnerCanClaimRemainingOpenChildrenWithoutReclaimingRoot() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.ASSIGNED, FIRST),
                        List.of(
                                node("a", null, AssignmentMode.OPEN, null),
                                node("b", null, AssignmentMode.OPEN, null),
                                node("other", null, AssignmentMode.ASSIGNED, SECOND),
                                configured(
                                        node("restricted", null, AssignmentMode.OPEN, null),
                                        List.of(SECOND),
                                        null),
                                node("unassigned", null, AssignmentMode.UNASSIGNED, null)));
        TaskClaims.Group group =
                tasks.claimableGroups(query(marker, 1, 20), FIRST).getList().getFirst();
        assertThat(group.ownership()).isEqualTo(TaskClaims.Ownership.MINE);
        assertThat(group.ownerName()).isEqualTo("领取测试" + FIRST);
        assertThat(group.claimableChildCount()).isEqualTo(2);
        assertThat(group.remainingClaimCount()).isEqualTo(2);
        assertThat(group.wholeClaimCount()).isZero();
        TaskClaims.Preview preview =
                tasks.claimPreview(new TaskClaims.Root(original.task().id(), true), FIRST);
        assertThat(preview.remainingOnly()).isTrue();
        assertThat(preview.items())
                .extracting(TaskClaims.Item::id)
                .containsExactlyInAnyOrder(row(original, "a").id(), row(original, "b").id());
        TaskClaims.ClaimGroup command =
                new TaskClaims.ClaimGroup(
                        original.task().id(), preview.instanceRevision(), key(), true);
        Detail claimed = tasks.claimGroup(command, FIRST);
        Detail full = tasks.detail(original.task().id(), OWNER);
        assertThat(full.task().assigneeId()).isEqualTo(FIRST);
        assertThat(full.task().status()).isEqualTo("PENDING");
        assertThat(full.task().actualStart()).isNull();
        assertThat(full.task().plans()).isEmpty();
        assertThat(row(full, "a").assigneeId()).isEqualTo(FIRST);
        assertThat(row(full, "b").assigneeId()).isEqualTo(FIRST);
        assertThat(row(full, "other").assigneeId()).isEqualTo(SECOND);
        assertThat(row(full, "restricted").assigneeId()).isNull();
        assertThat(row(full, "unassigned").assigneeId()).isNull();
        assertThat(tasks.claimGroup(command, FIRST).task().revision())
                .isEqualTo(claimed.task().revision());
        assertThat(tasks.claimableGroups(query(marker, 1, 20), FIRST).getTotal()).isZero();
        assertThatThrownBy(
                        () ->
                                tasks.claimPreview(
                                        new TaskClaims.Root(original.task().id(), true), FIRST))
                .hasMessageContaining("没有可领取");
    }

    @Test
    void visibleColleagueOwnerIsShownButCannotBeReclaimed() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.ASSIGNED, FIRST),
                        List.of(node("a", null, AssignmentMode.OPEN, null)));
        TaskClaims.Group group =
                tasks.claimableGroups(query(marker, 1, 20), OWNER).getList().getFirst();
        assertThat(group.ownership()).isEqualTo(TaskClaims.Ownership.ASSIGNED);
        assertThat(group.ownerName()).isEqualTo("领取测试" + FIRST);
        assertThat(group.claimableChildCount()).isEqualTo(1);
        assertThat(group.remainingClaimCount()).isZero();
        assertThatThrownBy(
                        () ->
                                tasks.claimPreview(
                                        new TaskClaims.Root(original.task().id(), true), OWNER))
                .hasMessageContaining("不可整项领取");
    }

    @Test
    void unavailableRootStillShowsEligibleChildrenWithoutOfferingRemainingClaim() {
        create(
                node("root", null, AssignmentMode.UNASSIGNED, null),
                List.of(node("a", null, AssignmentMode.OPEN, null)));
        TaskClaims.Group group =
                tasks.claimableGroups(query(marker, 1, 20), OWNER).getList().getFirst();
        assertThat(group.ownership()).isEqualTo(TaskClaims.Ownership.UNAVAILABLE);
        assertThat(group.ownerName()).isEqualTo("待分配");
        assertThat(group.claimableChildCount()).isEqualTo(1);
        assertThat(group.remainingClaimCount()).isZero();
    }

    @Test
    void runningRootOwnerCanClaimRemainingWithoutChangingRootExecution() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.ASSIGNED, FIRST),
                        List.of(node("a", null, AssignmentMode.OPEN, null)));
        tasks.plan(
                new SavePlan(
                        List.of(original.task().id()), Period.DAY, java.time.LocalDate.now(), true),
                FIRST);
        Detail running = transition(original.task().id(), Action.START, FIRST);
        assertThat(running.task().plans()).hasSize(1);
        TaskClaims.Preview preview =
                tasks.claimPreview(new TaskClaims.Root(original.task().id(), true), FIRST);
        Detail claimed =
                tasks.claimGroup(
                        new TaskClaims.ClaimGroup(
                                original.task().id(), preview.instanceRevision(), key(), true),
                        FIRST);
        assertThat(claimed.task().status()).isEqualTo("RUNNING");
        assertThat(claimed.task().actualStart()).isEqualTo(running.task().actualStart());
        assertThat(claimed.task().expectedStart()).isEqualTo(running.task().expectedStart());
        assertThat(claimed.task().expectedEnd()).isEqualTo(running.task().expectedEnd());
        assertThat(claimed.task().plans()).isEqualTo(running.task().plans());
        assertThat(row(claimed, "a").status()).isEqualTo("PENDING");
        assertThat(row(claimed, "a").actualStart()).isNull();
    }

    @Test
    void unstartedRootWithRunningChildUsesSameGroupStatusAsPersonalList() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.OPEN, null),
                        List.of(
                                node("running", null, AssignmentMode.ASSIGNED, FIRST),
                                node("open", null, AssignmentMode.OPEN, null)));
        transition(row(original, "running").id(), Action.START, FIRST);

        Row rootBefore = tasks.detail(original.task().id(), OWNER).task();
        assertThat(rootBefore.status()).isEqualTo(State.PENDING.name());
        assertThat(rootBefore.actualStart()).isNull();
        assertThat(rootBefore.groupStatus()).isEqualTo(State.RUNNING.name());

        TaskClaims.Group group =
                tasks.claimableGroups(query(marker, 1, 20), FIRST).getList().getFirst();
        assertThat(group.status()).isEqualTo(rootBefore.groupStatus());
        assertThat(group.claimableCount()).isEqualTo(2);
        assertThat(group.claimableChildCount()).isEqualTo(1);
        assertThat(group.childCount()).isEqualTo(2);
        assertThat(tasks.claimableChildren(new TaskClaims.Root(original.task().id()), FIRST))
                .anySatisfy(
                        child -> {
                            assertThat(child.id()).isEqualTo(row(original, "running").id());
                            assertThat(child.status()).isEqualTo(State.RUNNING.name());
                            assertThat(child.canClaim()).isFalse();
                        });

        Row rootAfter = tasks.detail(original.task().id(), OWNER).task();
        assertThat(rootAfter.status()).isEqualTo(rootBefore.status());
        assertThat(rootAfter.actualStart()).isNull();
        assertThat(rootAfter.revision()).isEqualTo(rootBefore.revision());
    }

    @Test
    void remainingClaimRejectsStalePreviewAndPreservesColleagueClaim() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.ASSIGNED, FIRST),
                        List.of(
                                node("a", null, AssignmentMode.OPEN, null),
                                node("b", null, AssignmentMode.OPEN, null)));
        TaskClaims.Preview preview =
                tasks.claimPreview(new TaskClaims.Root(original.task().id(), true), FIRST);
        Row a = row(original, "a");
        tasks.claim(new Claim(a.id(), a.revision(), key()), SECOND);
        assertThatThrownBy(
                        () ->
                                tasks.claimGroup(
                                        new TaskClaims.ClaimGroup(
                                                original.task().id(),
                                                preview.instanceRevision(),
                                                key(),
                                                true),
                                        FIRST))
                .hasMessageContaining("修改");
        assertThat(row(tasks.detail(original.task().id(), OWNER), "b").assigneeId()).isNull();
        TaskClaims.Preview fresh =
                tasks.claimPreview(new TaskClaims.Root(original.task().id(), true), FIRST);
        assertThat(fresh.items())
                .extracting(TaskClaims.Item::id)
                .containsExactly(row(original, "b").id());
        tasks.claimGroup(
                new TaskClaims.ClaimGroup(
                        original.task().id(), fresh.instanceRevision(), key(), true),
                FIRST);
        assertThat(row(tasks.detail(original.task().id(), OWNER), "a").assigneeId())
                .isEqualTo(SECOND);
    }

    @Test
    void wholeClaimIncludesLegacyOpenChildrenAndCanDelegateThemWithoutStarting() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.OPEN, null),
                        List.of(
                                node("a", null, AssignmentMode.OPEN, null),
                                node("b", null, AssignmentMode.OPEN, null)));
        TaskClaims.Preview preview =
                tasks.claimPreview(new TaskClaims.Root(original.task().id(), true), FIRST);
        assertThat(preview.items())
                .extracting(TaskClaims.Item::title)
                .containsExactlyInAnyOrder(marker + "root", marker + "a", marker + "b");
        assertThat(preview.remainingOnly()).isFalse();
        TaskClaims.Group group =
                tasks.claimableGroups(query(marker, 1, 20), FIRST).getList().getFirst();
        assertThat(group.ownership()).isEqualTo(TaskClaims.Ownership.UNCLAIMED);
        assertThat(group.ownerName()).isEqualTo("待领取");
        assertThat(group.claimableChildCount()).isEqualTo(2);
        assertThat(group.remainingClaimCount()).isZero();
        assertThat(
                        tasks.claimableGroups(query(marker, 1, 20), FIRST)
                                .getList()
                                .getFirst()
                                .wholeClaimCount())
                .isEqualTo(3);
        TaskClaims.ClaimGroup command =
                new TaskClaims.ClaimGroup(
                        original.task().id(), preview.instanceRevision(), key(), true);
        Detail claimed = tasks.claimGroup(command, FIRST);
        assertThat(claimed.nodes())
                .hasSize(3)
                .allMatch(
                        n ->
                                Objects.equals(n.assigneeId(), FIRST)
                                        && n.status().equals("PENDING")
                                        && n.actualStart() == null
                                        && n.plans().isEmpty());
        assertThat(row(claimed, "a").assignmentMode()).isEqualTo(AssignmentMode.ASSIGNED);
        assertThat(tasks.claimGroup(command, FIRST).task().revision())
                .isEqualTo(claimed.task().revision());
        assertThatThrownBy(
                        () ->
                                tasks.claimGroup(
                                        new TaskClaims.ClaimGroup(
                                                command.rootId(),
                                                command.expectedInstanceRevision(),
                                                command.requestKey(),
                                                false),
                                        FIRST))
                .hasMessageContaining("同一请求标识");
        Row a = row(claimed, "a");
        assertThat(a.canDelegate()).isTrue();
        tasks.assign(
                new Assign(
                        a.id(),
                        a.revision(),
                        AssignmentMode.ASSIGNED,
                        SECOND,
                        List.of(),
                        key(),
                        null),
                FIRST);
        Detail full = tasks.detail(original.task().id(), OWNER);
        assertThat(row(full, "a").assigneeId()).isEqualTo(SECOND);
        assertThat(row(full, "b").assigneeId()).isEqualTo(FIRST);
    }

    @Test
    void wholeClaimSkipsAssignedRestrictedUnassignedAndFinishedChildren() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.OPEN, null),
                        List.of(
                                node("open", null, AssignmentMode.OPEN, null),
                                node("follow", null, AssignmentMode.FOLLOW_ROOT, null),
                                configured(
                                        node("restricted", null, AssignmentMode.OPEN, null),
                                        List.of(SECOND),
                                        null),
                                node("other", null, AssignmentMode.ASSIGNED, SECOND),
                                node("unassigned", null, AssignmentMode.UNASSIGNED, null),
                                node("cancelled", null, AssignmentMode.OPEN, null)));
        transition(row(original, "cancelled").id(), Action.CANCEL, OWNER);
        TaskClaims.Preview preview =
                tasks.claimPreview(new TaskClaims.Root(original.task().id(), true), FIRST);
        assertThat(preview.items())
                .extracting(TaskClaims.Item::title)
                .containsExactlyInAnyOrder(marker + "root", marker + "open", marker + "follow");
        TaskClaims.Group group =
                tasks.claimableGroups(query(marker, 1, 20), FIRST).getList().getFirst();
        assertThat(group.canClaimGroup()).isTrue();
        assertThat(group.wholeClaimCount()).isEqualTo(3);
        tasks.claimGroup(
                new TaskClaims.ClaimGroup(
                        original.task().id(), preview.instanceRevision(), key(), true),
                FIRST);
        Detail full = tasks.detail(original.task().id(), OWNER);
        assertThat(row(full, "other").assigneeId()).isEqualTo(SECOND);
        for (String name : List.of("restricted", "unassigned", "cancelled"))
            assertThat(row(full, name).assigneeId()).isNull();
        assertThat(row(full, "follow").assigneeId()).isEqualTo(FIRST);
    }

    @Test
    void wholeClaimRejectsStalePreviewAfterSingleOpenChildWasClaimed() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.OPEN, null),
                        List.of(
                                node("a", null, AssignmentMode.OPEN, null),
                                node("b", null, AssignmentMode.OPEN, null)));
        TaskClaims.Preview preview =
                tasks.claimPreview(new TaskClaims.Root(original.task().id(), true), FIRST);
        Row a = row(original, "a");
        tasks.claim(new Claim(a.id(), a.revision(), key()), SECOND);
        assertThatThrownBy(
                        () ->
                                tasks.claimGroup(
                                        new TaskClaims.ClaimGroup(
                                                original.task().id(),
                                                preview.instanceRevision(),
                                                key(),
                                                true),
                                        FIRST))
                .hasMessageContaining("修改");
        Detail unchanged = tasks.detail(original.task().id(), OWNER);
        assertThat(unchanged.task().assigneeId()).isNull();
        assertThat(row(unchanged, "b").assigneeId()).isNull();
        TaskClaims.Preview fresh =
                tasks.claimPreview(new TaskClaims.Root(original.task().id(), true), FIRST);
        assertThat(fresh.items())
                .extracting(TaskClaims.Item::title)
                .containsExactlyInAnyOrder(marker + "root", marker + "b");
        tasks.claimGroup(
                new TaskClaims.ClaimGroup(
                        original.task().id(), fresh.instanceRevision(), key(), true),
                FIRST);
        assertThat(row(tasks.detail(original.task().id(), OWNER), "a").assigneeId())
                .isEqualTo(SECOND);
    }

    @Test
    void wholeClaimRollsBackAllOpenChildrenWhenNotificationFails() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.OPEN, null),
                        List.of(
                                node("a", null, AssignmentMode.OPEN, null),
                                node("b", null, AssignmentMode.OPEN, null)));
        TaskClaims.Preview preview =
                tasks.claimPreview(new TaskClaims.Root(original.task().id(), true), FIRST);
        TaskClaims.ClaimGroup command =
                new TaskClaims.ClaimGroup(
                        original.task().id(), preview.instanceRevision(), key(), true);
        Mockito.when(servicesContext.getBean(IMsgSendService.class).send(Mockito.any()))
                .thenReturn(null);
        assertThatThrownBy(() -> tasks.claimGroup(command, FIRST)).hasMessageContaining("提醒");
        Detail unchanged = tasks.detail(original.task().id(), OWNER);
        assertThat(unchanged.nodes()).allMatch(n -> n.assigneeId() == null);
        assertThat(unchanged.events()).noneMatch(e -> e.type().equals("CLAIMED"));
        Mockito.when(servicesContext.getBean(IMsgSendService.class).send(Mockito.any()))
                .thenReturn(999L);
        assertThat(tasks.claimGroup(command, FIRST).nodes())
                .hasSize(3)
                .allMatch(n -> Objects.equals(n.assigneeId(), FIRST));
    }

    @Test
    void wholeClaimRequiresEligibleRootEvenWhenChildrenAreOpen() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.ASSIGNED, SECOND),
                        List.of(node("a", null, AssignmentMode.OPEN, null)));
        assertThat(
                        tasks.claimableGroups(query(marker, 1, 20), FIRST)
                                .getList()
                                .getFirst()
                                .wholeClaimCount())
                .isZero();
        assertThatThrownBy(
                        () ->
                                tasks.claimPreview(
                                        new TaskClaims.Root(original.task().id(), true), FIRST))
                .hasMessageContaining("不可整项领取");
        assertThatThrownBy(
                        () ->
                                tasks.claimGroup(
                                        new TaskClaims.ClaimGroup(
                                                original.task().id(),
                                                original.task().revision(),
                                                key(),
                                                true),
                                        FIRST))
                .hasMessageContaining("范围");
    }

    @Test
    void paginationCountsRealRootsAndSearchFindsEligibleDescendants() {
        Detail a =
                create(
                        node("a", null, AssignmentMode.OPEN, null),
                        List.of(
                                node("needle-one", null, AssignmentMode.FOLLOW_ROOT, null),
                                node("needle-two", null, AssignmentMode.FOLLOW_ROOT, null)));
        Detail b =
                create(
                        node("b", null, AssignmentMode.OPEN, null),
                        List.of(node("needle-three", null, AssignmentMode.FOLLOW_ROOT, null)));
        PageResult<TaskClaims.Group> first =
                tasks.claimableGroups(query(marker + "needle", 1, 1), FIRST);
        PageResult<TaskClaims.Group> second =
                tasks.claimableGroups(query(marker + "needle", 2, 1), FIRST);
        assertThat(first.getTotal()).isEqualTo(2);
        assertThat(second.getTotal()).isEqualTo(2);
        assertThat(
                        List.of(
                                first.getList().getFirst().rootId(),
                                second.getList().getFirst().rootId()))
                .containsExactlyInAnyOrder(a.task().id(), b.task().id());
        assertThat(tasks.claimableGroups(query(marker, 1, 20), FIRST).getList())
                .allMatch(g -> g.canClaimGroup() && !g.rootVisible());
    }

    @Test
    void groupClaimsOnlyContinuousDefaultsAndLeavesIndependentBranchesUntouched() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.OPEN, null),
                        List.of(
                                node("a", null, AssignmentMode.FOLLOW_ROOT, null),
                                        node("b", "a", AssignmentMode.FOLLOW_ROOT, null),
                                node("open", null, AssignmentMode.OPEN, null),
                                        node(
                                                "open-child",
                                                "open",
                                                AssignmentMode.FOLLOW_ROOT,
                                                null),
                                node("unassigned", null, AssignmentMode.UNASSIGNED, null),
                                        node(
                                                "unassigned-child",
                                                "unassigned",
                                                AssignmentMode.FOLLOW_ROOT,
                                                null),
                                node("other", null, AssignmentMode.ASSIGNED, SECOND),
                                        node(
                                                "other-child",
                                                "other",
                                                AssignmentMode.FOLLOW_ROOT,
                                                null)));
        TaskClaims.Preview preview = preview(original, FIRST);
        assertThat(preview.items())
                .extracting(TaskClaims.Item::title)
                .containsExactlyInAnyOrder(marker + "root", marker + "a", marker + "b");
        TaskClaims.ClaimGroup command =
                new TaskClaims.ClaimGroup(original.task().id(), preview.instanceRevision(), key());
        Detail claimed = tasks.claimGroup(command, FIRST);
        assertThat(claimed.nodes())
                .extracting(Row::title)
                .containsExactlyInAnyOrder(marker + "root", marker + "a", marker + "b");
        assertThat(claimed.nodes())
                .allMatch(
                        n ->
                                n.assigneeId().equals(FIRST)
                                        && n.status().equals("PENDING")
                                        && n.actualStart() == null
                                        && n.plans().isEmpty());
        assertThat(row(claimed, "a").assignmentMode()).isEqualTo(AssignmentMode.FOLLOW_ROOT);
        Detail full = tasks.detail(original.task().id(), OWNER);
        assertThat(row(full, "open-child").assigneeId()).isNull();
        assertThat(row(full, "other").assigneeId()).isEqualTo(SECOND);
        assertThat(row(full, "other-child").assigneeId()).isNull();
        assertThat(row(full, "unassigned-child").assigneeId()).isNull();
        assertThat(tasks.claimGroup(command, FIRST).task().revision())
                .isEqualTo(claimed.task().revision());
        assertThat(full.events()).filteredOn(e -> e.type().equals("CLAIMED")).hasSize(1);
    }

    @Test
    void completeClaimableStructureDoesNotGrantOtherBranchesDetailsOrClaimRights() {
        Detail original =
                create(
                        node("private-root", null, AssignmentMode.ASSIGNED, OWNER),
                        List.of(
                                node("open", null, AssignmentMode.OPEN, null),
                                node("secret", null, AssignmentMode.ASSIGNED, SECOND)));
        TaskClaims.Group group =
                tasks.claimableGroups(query(marker, 1, 20), FIRST).getList().getFirst();
        assertThat(group.rootId()).isEqualTo(original.task().id());
        assertThat(group.title()).isEqualTo(marker + "private-root");
        assertThat(group.rootVisible()).isFalse();
        assertThat(group.canClaimGroup()).isFalse();
        assertThat(group.claimableCount()).isEqualTo(1);
        assertThat(group.ownership()).isEqualTo(TaskClaims.Ownership.RESTRICTED);
        assertThat(group.ownerName()).isEqualTo("领取测试" + OWNER);
        assertThat(group.childCount()).isEqualTo(2);
        assertThat(group.completedChildCount()).isZero();
        assertThat(group.anchorTaskId()).isEqualTo(row(original, "open").id());
        assertThat(group.claimableChildCount()).isEqualTo(1);
        assertThat(group.remainingClaimCount()).isZero();
        List<TaskClaims.Item> children =
                tasks.claimableChildren(new TaskClaims.Root(original.task().id()), FIRST);
        assertThat(children)
                .extracting(TaskClaims.Item::title)
                .containsExactly(marker + "open", marker + "secret");
        assertThat(children).allMatch(n -> n.parentId().equals(original.task().id()));
        assertThat(children).allMatch(n -> n.anchorTaskId().equals(group.anchorTaskId()));
        assertThat(children).noneMatch(TaskClaims.Item::detailVisible);
        assertThat(children.getFirst().canClaim()).isTrue();
        assertThat(children.getLast().canClaim()).isFalse();
        assertThat(children.getLast().assigneeName()).isEqualTo("领取测试" + SECOND);
        Detail summary = tasks.detail(row(original, "open").id(), FIRST);
        assertThat(summary.task().rootId()).isEqualTo(original.task().id());
        assertThat(summary.task().description()).isNull();
        assertThat(summary.task().binding()).isNull();
        assertThat(summary.structure()).hasSize(3);
        assertThat(summary.nodes()).hasSize(1);
        assertThatThrownBy(() -> tasks.detail(original.task().id(), FIRST))
                .hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                tasks.claim(
                                        new Claim(
                                                row(original, "secret").id(),
                                                row(original, "secret").revision(),
                                                key()),
                                        FIRST))
                .hasMessageContaining("领取");
        assertThatThrownBy(() -> preview(original, FIRST)).hasMessageContaining("不可整项领取");
        assertThatThrownBy(() -> tasks.detail(row(original, "secret").id(), FIRST))
                .hasMessageContaining("权限");
    }

    @Test
    void createWithAssignedRootResolvesDefaultsButNotExplicitBranches() {
        Detail created =
                create(
                        node("root", null, AssignmentMode.ASSIGNED, FIRST),
                        List.of(
                                node("a", null, AssignmentMode.FOLLOW_ROOT, null),
                                node("b", "a", AssignmentMode.FOLLOW_ROOT, null),
                                node("other", null, AssignmentMode.ASSIGNED, SECOND)));
        assertThat(row(created, "a").assigneeId()).isEqualTo(FIRST);
        assertThat(row(created, "b").assigneeId()).isEqualTo(FIRST);
        assertThat(row(created, "other").assigneeId()).isEqualTo(SECOND);
    }

    @Test
    void originalManagerFirstAssignmentAlsoTakesContinuousDefaults() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.UNASSIGNED, null),
                        List.of(node("a", null, AssignmentMode.FOLLOW_ROOT, null)));
        Detail assigned =
                tasks.assign(
                        new Assign(
                                original.task().id(),
                                original.task().revision(),
                                AssignmentMode.ASSIGNED,
                                FIRST,
                                List.of(),
                                key(),
                                null),
                        OWNER);
        assertThat(row(assigned, "a").assigneeId()).isEqualTo(FIRST);
        assertThat(row(assigned, "a").assignmentMode()).isEqualTo(AssignmentMode.FOLLOW_ROOT);
    }

    @Test
    void legacyRootClaimUsesTheSameDefaultInheritance() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.OPEN, null),
                        List.of(node("a", null, AssignmentMode.FOLLOW_ROOT, null)));
        Detail claimed =
                tasks.claim(
                        new Claim(original.task().id(), original.task().revision(), key()), FIRST);
        assertThat(row(claimed, "a").assigneeId()).isEqualTo(FIRST);
    }

    @Test
    void singleChildClaimInvalidatesPreviewAndBecomesAnIndependentBarrier() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.OPEN, null),
                        List.of(
                                node("a", null, AssignmentMode.FOLLOW_ROOT, null),
                                node("b", "a", AssignmentMode.FOLLOW_ROOT, null),
                                node("c", null, AssignmentMode.FOLLOW_ROOT, null)));
        TaskClaims.Preview before = preview(original, FIRST);
        tasks.claim(
                new Claim(row(original, "a").id(), row(original, "a").revision(), key()), SECOND);
        assertThatThrownBy(
                        () ->
                                tasks.claimGroup(
                                        new TaskClaims.ClaimGroup(
                                                original.task().id(),
                                                before.instanceRevision(),
                                                key()),
                                        FIRST))
                .hasMessageContaining("修改");
        TaskClaims.Preview current = preview(original, FIRST);
        assertThat(current.items())
                .extracting(TaskClaims.Item::title)
                .containsExactlyInAnyOrder(marker + "root", marker + "c");
        tasks.claimGroup(
                new TaskClaims.ClaimGroup(original.task().id(), current.instanceRevision(), key()),
                FIRST);
        Detail full = tasks.detail(original.task().id(), OWNER);
        assertThat(row(full, "a").assignmentMode()).isEqualTo(AssignmentMode.ASSIGNED);
        assertThat(row(full, "a").assigneeId()).isEqualTo(SECOND);
        assertThat(row(full, "b").assigneeId()).isNull();
        assertThatThrownBy(
                        () ->
                                tasks.claim(
                                        new Claim(
                                                row(full, "b").id(),
                                                row(full, "b").revision(),
                                                key()),
                                        FIRST))
                .hasMessageContaining("范围");
        Detail arranged =
                tasks.assign(
                        new Assign(
                                row(full, "b").id(),
                                row(full, "b").revision(),
                                AssignmentMode.ASSIGNED,
                                SECOND,
                                List.of(),
                                key(),
                                null),
                        OWNER);
        assertThat(arranged.task().assigneeId()).isEqualTo(SECOND);
    }

    @Test
    void candidatesAndAcceptorQualificationsApplyToDefaultChildrenAndSqlGroups() {
        NodeInput root =
                configured(node("root", null, AssignmentMode.OPEN, null), List.of(FIRST), SECOND);
        Detail original = create(root, List.of(node("a", null, AssignmentMode.FOLLOW_ROOT, null)));
        assertThat(tasks.claimableGroups(query(marker, 1, 20), SECOND).getTotal()).isZero();
        assertThat(tasks.claimableGroups(query(marker, 1, 20), FIRST).getTotal()).isEqualTo(1);
        assertThatThrownBy(() -> tasks.claim(new Claim(row(original, "a").id(), 0, key()), SECOND))
                .hasMessageContaining("范围");
        assertThat(preview(original, FIRST).items()).hasSize(2);
        AdminUserRespDTO disabled = new AdminUserRespDTO();
        disabled.setId(FIRST);
        disabled.setStatus(1);
        Mockito.when(servicesContext.getBean(AdminUserApi.class).getUser(FIRST))
                .thenReturn(disabled);
        assertThatThrownBy(() -> preview(original, FIRST)).hasMessageContaining("停用");
        assertThatThrownBy(() -> tasks.claimableGroups(query(marker, 1, 20), FIRST))
                .hasMessageContaining("停用");
    }

    @Test
    void rootAcceptorCannotClaimEvenWhenIncludedInCandidates() {
        Detail original =
                create(
                        configured(
                                node("root", null, AssignmentMode.OPEN, null),
                                List.of(FIRST, SECOND),
                                FIRST),
                        List.of(node("a", null, AssignmentMode.FOLLOW_ROOT, null)));
        assertThat(tasks.claimableGroups(query(marker, 1, 20), FIRST).getTotal()).isZero();
        assertThatThrownBy(() -> preview(original, FIRST)).hasMessageContaining("不可整项领取");
        assertThat(preview(original, SECOND).items()).hasSize(2);
    }

    @Test
    void concurrentGroupClaimsCannotSplitOwnershipAndSameKeyReplays() throws Exception {
        Detail original =
                create(
                        node("root", null, AssignmentMode.OPEN, null),
                        List.of(
                                node("a", null, AssignmentMode.FOLLOW_ROOT, null),
                                node("b", "a", AssignmentMode.FOLLOW_ROOT, null)));
        int revision = preview(original, FIRST).instanceRevision();
        String one = key(), two = key();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> a =
                    executor.submit(
                            () -> attempt(start, original.task().id(), revision, FIRST, one));
            Future<Boolean> b =
                    executor.submit(
                            () -> attempt(start, original.task().id(), revision, SECOND, two));
            start.countDown();
            boolean first = a.get(20, TimeUnit.SECONDS), second = b.get(20, TimeUnit.SECONDS);
            assertThat(first ^ second).isTrue();
            long winner = first ? FIRST : SECOND;
            Detail full = tasks.detail(original.task().id(), OWNER);
            assertThat(full.nodes()).allMatch(n -> Objects.equals(n.assigneeId(), winner));
            assertThat(
                            tasks.claimGroup(
                                            new TaskClaims.ClaimGroup(
                                                    original.task().id(),
                                                    revision,
                                                    first ? one : two),
                                            winner)
                                    .task()
                                    .assigneeId())
                    .isEqualTo(winner);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void delegatedAssignmentReturnsVisibleRootButDoesNotGrantChildDataRights() {
        Detail created =
                create(
                        node("root", null, AssignmentMode.ASSIGNED, FIRST),
                        List.of(
                                node("a", null, AssignmentMode.FOLLOW_ROOT, null),
                                node("other", null, AssignmentMode.ASSIGNED, SECOND)));
        Row child = tasks.detail(row(created, "a").id(), FIRST).task();
        assertThat(child.canDelegate()).isTrue();
        assertThat(child.canAssign()).isFalse();
        Assign command =
                new Assign(
                        child.id(),
                        child.revision(),
                        AssignmentMode.ASSIGNED,
                        SECOND,
                        List.of(),
                        key(),
                        null);
        assertThat(tasks.assign(command, FIRST).task().id()).isEqualTo(created.task().id());
        assertThat(tasks.assign(command, FIRST).task().id()).isEqualTo(created.task().id());
        assertThatThrownBy(() -> tasks.detail(child.id(), FIRST)).hasMessageContaining("权限");
        assertThat(tasks.detail(child.id(), SECOND).task().assigneeId()).isEqualTo(SECOND);
        // 总负责人可继续调整分工，但不因此获得对方子任务的业务资料。
        assertThat(
                        tasks.assign(
                                        new Assign(
                                                row(created, "other").id(),
                                                0,
                                                AssignmentMode.ASSIGNED,
                                                FIRST,
                                                List.of(),
                                                key(),
                                                "收回本人"),
                                        FIRST)
                                .task()
                                .assigneeId())
                .isEqualTo(FIRST);
    }

    @Test
    void delegationCanOpenOnlyOwnedPendingChildrenAndCannotAlterOtherRules() {
        Detail created =
                create(
                        node("root", null, AssignmentMode.ASSIGNED, FIRST),
                        List.of(
                                node("a", null, AssignmentMode.FOLLOW_ROOT, null),
                                node("b", null, AssignmentMode.FOLLOW_ROOT, null)));
        Row a = row(created, "a");
        assertThatThrownBy(
                        () ->
                                tasks.assign(
                                        new Assign(
                                                a.id(),
                                                a.revision(),
                                                AssignmentMode.UNASSIGNED,
                                                null,
                                                List.of(),
                                                key(),
                                                null),
                                        FIRST))
                .hasMessageContaining("仅可");
        assertThatThrownBy(
                        () ->
                                tasks.assign(
                                        new Assign(
                                                a.id(),
                                                a.revision(),
                                                AssignmentMode.FOLLOW_ROOT,
                                                null,
                                                List.of(),
                                                key(),
                                                null),
                                        FIRST))
                .hasMessageContaining("仅可");
        assertThatThrownBy(
                        () ->
                                tasks.assign(
                                        new Assign(
                                                created.task().id(),
                                                created.task().revision(),
                                                AssignmentMode.OPEN,
                                                null,
                                                List.of(),
                                                key(),
                                                null),
                                        FIRST))
                .hasMessageContaining("原拆分人");
        assertThat(
                        tasks.assign(
                                        new Assign(
                                                a.id(),
                                                a.revision(),
                                                AssignmentMode.OPEN,
                                                null,
                                                List.of(SECOND),
                                                key(),
                                                null),
                                        FIRST)
                                .task()
                                .id())
                .isEqualTo(created.task().id());
        assertThatThrownBy(() -> tasks.detail(a.id(), FIRST)).hasMessageContaining("权限");
        transition(created.task().id(), Action.START, FIRST);
        transition(row(created, "b").id(), Action.START, FIRST);
        Row running = tasks.detail(row(created, "b").id(), FIRST).task();
        assertThat(running.canDelegate()).isFalse();
        assertThatThrownBy(
                        () ->
                                tasks.assign(
                                        new Assign(
                                                running.id(),
                                                running.revision(),
                                                AssignmentMode.ASSIGNED,
                                                SECOND,
                                                List.of(),
                                                key(),
                                                null),
                                        FIRST))
                .hasMessageContaining("未开始");
    }

    @Test
    void independentBranchDefaultCannotBeClaimedButOriginalManagerCanArrangeIt() {
        Detail created =
                create(
                        node("root", null, AssignmentMode.OPEN, null),
                        List.of(
                                node("other", null, AssignmentMode.ASSIGNED, SECOND),
                                node("hidden-default", "other", AssignmentMode.FOLLOW_ROOT, null)));
        assertThat(tasks.claimableChildren(new TaskClaims.Root(created.task().id()), FIRST))
                .extracting(TaskClaims.Item::title)
                .containsExactly(marker + "other", marker + "hidden-default");
        assertThat(tasks.claimableChildren(new TaskClaims.Root(created.task().id()), FIRST))
                .noneMatch(TaskClaims.Item::canClaim);
        Row hidden = row(created, "hidden-default");
        assertThatThrownBy(
                        () -> tasks.claim(new Claim(hidden.id(), hidden.revision(), key()), FIRST))
                .hasMessageContaining("范围");
        assertThat(
                        tasks.assign(
                                        new Assign(
                                                hidden.id(),
                                                hidden.revision(),
                                                AssignmentMode.ASSIGNED,
                                                SECOND,
                                                List.of(),
                                                key(),
                                                null),
                                        OWNER)
                                .task()
                                .assigneeId())
                .isEqualTo(SECOND);
    }

    @Test
    void rootAndTemplateCannotUseFollowRootAndFreshFollowerCannotForgeOwner() {
        assertThatThrownBy(
                        () ->
                                create(
                                        node("root", null, AssignmentMode.FOLLOW_ROOT, null),
                                        List.of()))
                .hasMessageContaining("随总任务");
        assertThatThrownBy(
                        () ->
                                create(
                                        node("root", null, AssignmentMode.OPEN, null),
                                        List.of(
                                                node(
                                                        "forged",
                                                        null,
                                                        AssignmentMode.FOLLOW_ROOT,
                                                        FIRST))))
                .hasMessageContaining("随总任务");
        assertThatThrownBy(
                        () ->
                                tasks.saveTemplate(
                                        new SaveTemplate(
                                                null,
                                                null,
                                                marker + "invalid",
                                                null,
                                                List.of(),
                                                null,
                                                node(
                                                        "root",
                                                        null,
                                                        AssignmentMode.FOLLOW_ROOT,
                                                        null)),
                                        OWNER))
                .hasMessageContaining("随总任务");
    }

    @Test
    void newlySubmittedBlockedDefaultRequiresExplicitArrangementButExistingBranchRemainsValid()
            throws Exception {
        assertThatThrownBy(
                        () ->
                                create(
                                        node("root", null, AssignmentMode.ASSIGNED, FIRST),
                                        List.of(
                                                node(
                                                        "other",
                                                        null,
                                                        AssignmentMode.ASSIGNED,
                                                        SECOND),
                                                node(
                                                        "default",
                                                        "other",
                                                        AssignmentMode.FOLLOW_ROOT,
                                                        null))))
                .hasMessageContaining("独立人员安排");
        Detail created =
                create(
                        node("root", null, AssignmentMode.OPEN, null),
                        List.of(
                                node("other", null, AssignmentMode.ASSIGNED, SECOND),
                                node("default", "other", AssignmentMode.FOLLOW_ROOT, null)));
        tasks.claimGroup(
                new TaskClaims.ClaimGroup(created.task().id(), created.task().revision(), key()),
                FIRST);
        Row current = tasks.detail(created.task().id(), OWNER).task();
        assertThat(
                        tasks.adjust(
                                        new Adjust(
                                                current.id(),
                                                current.revision(),
                                                configs(current.id()),
                                                "保留历史独立分支"),
                                        OWNER)
                                .nodes())
                .anyMatch(n -> n.title().equals(marker + "default") && n.assigneeId() == null);
        Row other = row(tasks.detail(current.id(), OWNER), "other");
        assertThatThrownBy(
                        () ->
                                tasks.create(
                                        new Create(
                                                node("new", null, AssignmentMode.FOLLOW_ROOT, null),
                                                other.id(),
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                key(),
                                                List.of(),
                                                null,
                                                null,
                                                null),
                                        OWNER))
                .hasMessageContaining("独立人员安排");
    }

    @Test
    void templateDefaultRemainsUnresolvedUntilEachLaunchChoosesTheRootOwner() {
        NodeInput root = node("root", null, AssignmentMode.OPEN, null);
        Template template =
                tasks.saveTemplate(
                        new SaveTemplate(
                                null,
                                null,
                                marker + "template",
                                null,
                                List.of(node("a", null, AssignmentMode.FOLLOW_ROOT, null)),
                                null,
                                root),
                        OWNER);
        templates.add(template.id());
        TemplateVersion version =
                tasks.publish(new PublishTemplate(template.id(), template.revision()), OWNER);
        assertThat(version.nodes().getFirst().assigneeId()).isNull();
        assertThat(version.nodes().getFirst().assignmentMode())
                .isEqualTo(AssignmentMode.FOLLOW_ROOT);
        Detail created =
                tasks.create(
                        new Create(
                                node("root", null, AssignmentMode.ASSIGNED, FIRST),
                                null,
                                template.id(),
                                version.version(),
                                null,
                                null,
                                null,
                                key(),
                                null,
                                null,
                                null,
                                null),
                        OWNER);
        roots.add(created.task().id());
        assertThat(row(created, "a").assigneeId()).isEqualTo(FIRST);
        assertThat(
                        tasks.version(template.id(), version.version(), OWNER)
                                .nodes()
                                .getFirst()
                                .assigneeId())
                .isNull();
    }

    @Test
    void wholeClaimWorksForPublishedOpenTemplateWithoutChangingItsVersion() {
        NodeInput root = node("root", null, AssignmentMode.OPEN, null);
        Template template =
                tasks.saveTemplate(
                        new SaveTemplate(
                                null,
                                null,
                                marker + "open-template",
                                null,
                                List.of(
                                        node("a", null, AssignmentMode.OPEN, null),
                                        node("nested", "a", AssignmentMode.OPEN, null),
                                        node("b", null, AssignmentMode.OPEN, null)),
                                null,
                                root),
                        OWNER);
        templates.add(template.id());
        TemplateVersion version =
                tasks.publish(new PublishTemplate(template.id(), template.revision()), OWNER);
        Detail created =
                tasks.create(
                        new Create(
                                root,
                                null,
                                template.id(),
                                version.version(),
                                null,
                                null,
                                null,
                                key(),
                                null,
                                null,
                                null,
                                null),
                        OWNER);
        roots.add(created.task().id());
        TaskClaims.Preview preview =
                tasks.claimPreview(new TaskClaims.Root(created.task().id(), true), FIRST);
        assertThat(preview.items()).hasSize(4);
        assertThat(
                        tasks.claimGroup(
                                        new TaskClaims.ClaimGroup(
                                                created.task().id(),
                                                preview.instanceRevision(),
                                                key(),
                                                true),
                                        FIRST)
                                .nodes())
                .hasSize(4)
                .allMatch(n -> Objects.equals(n.assigneeId(), FIRST));
        assertThat(tasks.version(template.id(), version.version(), OWNER)).isEqualTo(version);
    }

    @Test
    void runningResolvedFollowerKeepsItsProtocolWhenAdjustingAnotherPendingNode() throws Exception {
        Detail created =
                create(
                        node("root", null, AssignmentMode.ASSIGNED, FIRST),
                        List.of(
                                node("a", null, AssignmentMode.FOLLOW_ROOT, null),
                                node("b", null, AssignmentMode.OPEN, null)));
        transition(created.task().id(), Action.START, FIRST);
        transition(row(created, "a").id(), Action.START, FIRST);
        Detail full = tasks.detail(created.task().id(), OWNER);
        List<NodeInput> configs = configs(created.task().id());
        Detail adjusted =
                tasks.adjust(
                        new Adjust(created.task().id(), full.task().revision(), configs, "仅验证配置等价"),
                        OWNER);
        assertThat(row(adjusted, "a").assignmentMode()).isEqualTo(AssignmentMode.FOLLOW_ROOT);
        assertThat(row(adjusted, "a").assigneeId()).isEqualTo(FIRST);
        assertThat(row(adjusted, "a").status()).isEqualTo("RUNNING");
    }

    @Test
    void childAssignmentBumpsRootAndRejectsStaleWholePreview() {
        Detail created =
                create(
                        node("root", null, AssignmentMode.OPEN, null),
                        List.of(node("a", null, AssignmentMode.FOLLOW_ROOT, null)));
        TaskClaims.Preview before = preview(created, FIRST);
        Row a = row(created, "a");
        tasks.assign(
                new Assign(
                        a.id(),
                        a.revision(),
                        AssignmentMode.ASSIGNED,
                        SECOND,
                        List.of(),
                        key(),
                        null),
                OWNER);
        assertThatThrownBy(
                        () ->
                                tasks.claimGroup(
                                        new TaskClaims.ClaimGroup(
                                                created.task().id(),
                                                before.instanceRevision(),
                                                key()),
                                        FIRST))
                .hasMessageContaining("修改");
        assertThat(tasks.detail(created.task().id(), OWNER).task().assigneeId()).isNull();
    }

    @Test
    void terminalRootCannotExposeOrClaimRemainingOpenChildren() {
        Detail created =
                create(
                        node("root", null, AssignmentMode.OPEN, null),
                        List.of(node("a", null, AssignmentMode.FOLLOW_ROOT, null)));
        transition(row(created, "a").id(), Action.CANCEL, OWNER);
        transition(created.task().id(), Action.CANCEL, OWNER);
        assertThat(tasks.claimableGroups(query(marker, 1, 20), FIRST).getTotal()).isZero();
        assertThatThrownBy(() -> tasks.claim(new Claim(row(created, "a").id(), 0, key()), FIRST))
                .isInstanceOf(com.lingan.ucp.framework.common.exception.ServiceException.class);
    }

    @Test
    void terminalOwnedRootCannotUseRemainingClaim() {
        Detail original =
                create(
                        node("root", null, AssignmentMode.ASSIGNED, FIRST),
                        List.of(node("a", null, AssignmentMode.OPEN, null)));
        transition(row(original, "a").id(), Action.CANCEL, OWNER);
        Detail cancelled = transition(original.task().id(), Action.CANCEL, OWNER);
        assertThat(tasks.claimableGroups(query(marker, 1, 20), FIRST).getTotal()).isZero();
        assertThatThrownBy(
                        () ->
                                tasks.claimPreview(
                                        new TaskClaims.Root(original.task().id(), true), FIRST))
                .hasMessageContaining("不可整项领取");
        assertThatThrownBy(
                        () ->
                                tasks.claimGroup(
                                        new TaskClaims.ClaimGroup(
                                                original.task().id(),
                                                cancelled.task().revision(),
                                                key(),
                                                true),
                                        FIRST))
                .hasMessageContaining("仅未开始");
    }

    @Test
    void assignedFilterIncludesResolvedFollowersWithoutRelabelingUnresolvedDefaults() {
        Detail assigned =
                create(
                        node("assigned", null, AssignmentMode.ASSIGNED, FIRST),
                        List.of(node("resolved", null, AssignmentMode.FOLLOW_ROOT, null)));
        Detail open =
                create(
                        node("open", null, AssignmentMode.OPEN, null),
                        List.of(node("unresolved", null, AssignmentMode.FOLLOW_ROOT, null)));
        Query query =
                new Query(
                        "VISIBLE",
                        "ALL",
                        null,
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
                        AssignmentMode.ASSIGNED);
        assertThat(tasks.page(query, OWNER).getList())
                .extracting(Row::id)
                .containsExactlyInAnyOrder(assigned.task().id(), row(assigned, "resolved").id());
        assertThat(tasks.claimableChildren(new TaskClaims.Root(open.task().id()), FIRST))
                .extracting(TaskClaims.Item::id)
                .contains(row(open, "unresolved").id());
    }

    private boolean attempt(CountDownLatch start, String id, int revision, long actor, String key)
            throws InterruptedException {
        start.await();
        try {
            tasks.claimGroup(new TaskClaims.ClaimGroup(id, revision, key), actor);
            return true;
        } catch (com.lingan.ucp.framework.common.exception.ServiceException expected) {
            return false;
        }
    }

    private List<NodeInput> configs(String root) throws Exception {
        ObjectMapper mapper = servicesContext.getBean(ObjectMapper.class);
        List<NodeInput> result = new ArrayList<>();
        for (String value :
                jdbc.queryForList(
                        "select config_json from public.nocode_task_instance where root_id=? order"
                                + " by case when parent_id is null then 0 else 1 end,id",
                        String.class,
                        root)) result.add(mapper.readValue(value, NodeInput.class));
        return result;
    }

    private TaskClaims.Preview preview(Detail detail, long actor) {
        return tasks.claimPreview(new TaskClaims.Root(detail.task().id()), actor);
    }

    private TaskClaims.Query query(String search, int page, int size) {
        return new TaskClaims.Query(search, null, null, page, size);
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

    private Row row(Detail detail, String id) {
        return detail.nodes().stream()
                .filter(n -> n.title().equals(marker + id))
                .findFirst()
                .orElseThrow();
    }

    private Detail transition(String id, Action action, long actor) {
        Row current = tasks.detail(id, actor).task();
        return tasks.transition(
                new Transition(id, current.revision(), action, "分组测试", key()), actor);
    }

    private NodeInput node(String id, String parent, AssignmentMode mode, Long assignee) {
        return new NodeInput(
                id,
                parent,
                marker + id,
                "私有任务说明",
                assignee,
                null,
                null,
                new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                List.of(),
                null,
                null,
                null,
                mode,
                List.of());
    }

    private NodeInput configured(NodeInput n, List<Long> candidates, Long acceptor) {
        return new NodeInput(
                n.id(),
                n.parentId(),
                n.title(),
                n.description(),
                n.assigneeId(),
                n.urgency(),
                n.priority(),
                n.schedule(),
                n.predecessorIds(),
                n.binding(),
                n.sharing(),
                n.entries(),
                n.assignmentMode(),
                candidates,
                n.dataPolicy(),
                acceptor);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
