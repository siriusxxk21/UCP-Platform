package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/** 当前开发库验证真实领取、修订和混合树权限；只删除本类创建的实例。 */
class TaskPoolIntegrationTest {
    private static final long OWNER = 10001L, FIRST = 21001L, SECOND = 21002L;
    private TaskCenterService tasks;
    private final Set<String> roots = new LinkedHashSet<>();
    private final String marker = "pool_" + UUID.randomUUID();

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
        AdminUserApi users = servicesContext.getBean(AdminUserApi.class);
        Mockito.when(users.getUser(Mockito.anyLong()))
                .thenAnswer(
                        i -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(i.getArgument(0));
                            user.setNickname("池测试" + user.getId());
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
    void unassignedAndOpenRemainPendingAndLegacyKeepsActor() {
        Detail unassigned = create(node("u", AssignmentMode.UNASSIGNED, null), List.of());
        Detail open = create(node("o", AssignmentMode.OPEN, null), List.of());
        Detail legacy = create(node("l", null, null), List.of());
        assertThat(unassigned.task().assigneeId()).isNull();
        assertThat(unassigned.task().expectedStart()).isNull();
        assertThat(unassigned.task().canPlan()).isFalse();
        assertThat(open.task().assignmentMode()).isEqualTo(AssignmentMode.OPEN);
        assertThat(open.task().status()).isEqualTo("PENDING");
        assertThat(legacy.task().assigneeId()).isEqualTo(OWNER);
        assertThat(legacy.task().assignmentMode()).isEqualTo(AssignmentMode.ASSIGNED);
        assertThat(legacy.task().legacyProtocol()).isTrue();
        assertThat(open.task().legacyProtocol()).isFalse();
        assertThatThrownBy(() -> tasks.claim(new Claim(unassigned.task().id(), 0, key()), FIRST))
                .hasMessageContaining("未开放");
        assertThat(tasks.page(query("CLAIMABLE", null), FIRST).getList())
                .extracting(Row::id)
                .containsExactly(open.task().id());
        assertThat(tasks.page(query("ALL", AssignmentMode.UNASSIGNED), OWNER).getTotal())
                .isEqualTo(1);
    }

    @Test
    void onlyOneConcurrentClaimWinsAndTheWinnerCanReplay() throws Exception {
        Detail open = create(node("race", AssignmentMode.OPEN, null), List.of());
        String firstKey = key(), secondKey = key();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first =
                    executor.submit(() -> attempt(start, open.task().id(), FIRST, firstKey));
            Future<Boolean> second =
                    executor.submit(() -> attempt(start, open.task().id(), SECOND, secondKey));
            start.countDown();
            boolean wonFirst = first.get(15, TimeUnit.SECONDS),
                    wonSecond = second.get(15, TimeUnit.SECONDS);
            assertThat(wonFirst ^ wonSecond).isTrue();
            long winner = wonFirst ? FIRST : SECOND;
            String request = wonFirst ? firstKey : secondKey;
            Detail replay = tasks.claim(new Claim(open.task().id(), 0, request), winner);
            assertThat(replay.task().assigneeId()).isEqualTo(winner);
            assertThat(replay.task().status()).isEqualTo("PENDING");
            assertThat(replay.task().revision()).isEqualTo(1);
            assertThat(replay.events()).filteredOn(e -> e.type().equals("CLAIMED")).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void restrictedCandidatesAndDisabledAccountsCannotClaim() {
        NodeInput node = node("restricted", AssignmentMode.OPEN, null);
        node =
                new NodeInput(
                        node.id(),
                        null,
                        node.title(),
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
                        List.of(FIRST));
        Detail open = create(node, List.of());
        assertThat(tasks.page(query("CLAIMABLE", null), SECOND).getTotal()).isZero();
        assertThatThrownBy(() -> tasks.detail(open.task().id(), SECOND)).hasMessageContaining("权限");
        assertThatThrownBy(() -> tasks.claim(new Claim(open.task().id(), 0, key()), SECOND))
                .hasMessageContaining("范围");
        AdminUserRespDTO disabled = new AdminUserRespDTO();
        disabled.setId(FIRST);
        disabled.setStatus(1);
        Mockito.when(servicesContext.getBean(AdminUserApi.class).getUser(FIRST))
                .thenReturn(disabled);
        assertThatThrownBy(() -> tasks.detail(open.task().id(), FIRST)).hasMessageContaining("停用");
        assertThatThrownBy(() -> tasks.claim(new Claim(open.task().id(), 0, key()), FIRST))
                .hasMessageContaining("停用");
    }

    @Test
    void openChildDoesNotExposePrivateTreeBeforeOrAfterClaim() {
        Detail root =
                create(
                        node("root", AssignmentMode.ASSIGNED, OWNER),
                        List.of(
                                node("open", AssignmentMode.OPEN, null),
                                node("secret", AssignmentMode.ASSIGNED, OWNER)));
        Row open =
                root.nodes().stream()
                        .filter(n -> n.assignmentMode() == AssignmentMode.OPEN)
                        .findFirst()
                        .orElseThrow();
        Detail summary = tasks.detail(open.id(), FIRST);
        assertThat(summary.nodes()).hasSize(1);
        assertThat(summary.structure()).hasSize(3).noneMatch(StructureNode::detailVisible);
        assertThat(summary.task().description()).isNull();
        assertThat(summary.task().entries()).isEmpty();
        assertThat(summary.preview().description()).isEqualTo("任务执行说明");
        assertThat(summary.preview().effectiveWorkMinutes()).isNull();
        assertThat(summary.preview().acceptorId()).isNull();
        assertThat(summary.task().parentId()).isNull();
        assertThat(summary.comments()).isEmpty();
        assertThat(summary.events()).isEmpty();
        Detail claimed = tasks.claim(new Claim(open.id(), open.revision(), key()), FIRST);
        assertThat(claimed.preview()).isNull();
        assertThat(claimed.nodes()).extracting(Row::id).containsExactly(open.id());
        assertThat(tasks.page(query("ALL", null), FIRST).getList())
                .extracting(Row::id)
                .containsExactly(open.id());
        assertThatThrownBy(() -> tasks.detail(root.task().id(), FIRST)).hasMessageContaining("权限");
        assertThat(tasks.detail(root.task().id(), OWNER).nodes())
                .filteredOn(n -> !n.id().equals(open.id()))
                .allMatch(n -> n.assigneeId().equals(OWNER));
    }

    @Test
    void claimDetailShowsWorkRequirementsWithoutGrantingExecutionOrChangingTask() {
        LocalDateTime start = LocalDate.now().plusDays(1).atStartOfDay();
        Detail created =
                create(
                        new NodeInput(
                                "preview",
                                null,
                                marker + "preview",
                                "<p>检查装修材料并提交结果</p>",
                                null,
                                Urgency.NORMAL,
                                Priority.HIGH,
                                new Schedule(TimeMode.FIXED, start, 0, 2),
                                List.of(),
                                null,
                                null,
                                List.of(),
                                AssignmentMode.OPEN,
                                List.of(FIRST),
                                null,
                                OWNER,
                                120),
                        List.of());
        Detail before = tasks.detail(created.task().id(), OWNER);
        Detail preview = tasks.detail(created.task().id(), FIRST);
        assertThat(preview.preview().description()).isEqualTo(created.task().description());
        assertThat(preview.preview().schedule()).isEqualTo(created.task().schedule());
        assertThat(preview.preview().expectedStart()).isEqualTo(created.task().expectedStart());
        assertThat(preview.preview().expectedEnd()).isEqualTo(created.task().expectedEnd());
        assertThat(preview.preview().createdAt()).isNotNull();
        assertThat(preview.preview().priority()).isEqualTo(Priority.HIGH);
        assertThat(preview.preview().effectiveWorkMinutes()).isEqualTo(120);
        assertThat(preview.preview().acceptorId()).isEqualTo(OWNER);
        assertThat(preview.preview().acceptorName()).isEqualTo("池测试" + OWNER);
        assertThat(preview.task().schedule()).isNull();
        assertThat(preview.task().description()).isNull();
        assertThat(preview.task().binding()).isNull();
        assertThat(preview.task().entries()).isEmpty();
        assertThat(preview.task().canExecute()).isFalse();
        assertThat(preview.task().canStart()).isFalse();
        assertThat(preview.task().canEdit()).isFalse();
        assertThat(preview.task().canAccept()).isFalse();
        assertThat(preview.comments()).isEmpty();
        assertThat(preview.events()).isEmpty();
        assertThat(preview.links()).isEmpty();
        assertThat(preview.structure())
                .extracting(StructureNode::id)
                .containsExactlyElementsOf(created.nodes().stream().map(Row::id).toList());
        assertThat(preview.structure()).noneMatch(StructureNode::detailVisible);
        Detail after = tasks.detail(created.task().id(), OWNER);
        assertThat(after.task().revision()).isEqualTo(before.task().revision());
        assertThat(after.task().assigneeId()).isNull();
        assertThat(after.task().actualStart()).isNull();
        assertThat(after.events()).hasSameSizeAs(before.events());
        tasks.claim(new Claim(created.task().id(), created.task().revision(), key()), FIRST);
        assertThat(tasks.detail(created.task().id(), FIRST).preview()).isNull();
        assertThat(tasks.detail(created.task().id(), FIRST).structure())
                .extracting(StructureNode::id)
                .containsExactly(created.task().id());
        assertThatThrownBy(() -> tasks.detail(created.task().id(), SECOND))
                .hasMessageContaining("权限");
    }

    @Test
    void mixedLegacyTreeRetainsOldParticipantsButNotNewClaimants() {
        Detail root =
                create(
                        node("legacy-root", null, OWNER),
                        List.of(
                                node("legacy-worker", null, SECOND),
                                node("open", AssignmentMode.OPEN, null)));
        Row open =
                root.nodes().stream()
                        .filter(n -> n.assignmentMode() == AssignmentMode.OPEN)
                        .findFirst()
                        .orElseThrow();
        assertThat(tasks.detail(root.task().id(), SECOND).nodes()).hasSize(3);
        tasks.claim(new Claim(open.id(), open.revision(), key()), FIRST);
        assertThat(tasks.detail(root.task().id(), SECOND).nodes()).hasSize(3);
        assertThat(tasks.detail(open.id(), FIRST).nodes())
                .extracting(Row::id)
                .containsExactly(open.id());
        assertThat(tasks.page(query("ALL", null), FIRST).getTotal()).isEqualTo(1);
        assertThat(tasks.page(query("ALL", null), SECOND).getTotal()).isEqualTo(3);
    }

    @Test
    void managerAssignmentClearsOldPlansAndUnassignedParentDoesNotBlockStart() {
        Detail root =
                create(
                        node("group", AssignmentMode.UNASSIGNED, null),
                        List.of(node("child", AssignmentMode.ASSIGNED, FIRST)));
        Row child =
                root.nodes().stream().filter(n -> n.parentId() != null).findFirst().orElseThrow();
        assertThat(tasks.detail(child.id(), FIRST).task().canStart()).isTrue();
        tasks.plan(
                new SavePlan(List.of(child.id()), Period.DAY, LocalDate.now(), true, "SELF"),
                FIRST);
        Detail reassigned =
                tasks.assign(
                        new Assign(
                                child.id(),
                                child.revision(),
                                AssignmentMode.ASSIGNED,
                                SECOND,
                                List.of(),
                                key(),
                                "测试改派"),
                        OWNER);
        assertThat(reassigned.task().plans()).isEmpty();
        Detail started =
                tasks.transition(
                        new Transition(
                                child.id(),
                                reassigned.task().revision(),
                                Action.START,
                                null,
                                key()),
                        SECOND);
        assertThat(started.task().status()).isEqualTo("RUNNING");
        assertThatThrownBy(
                        () ->
                                tasks.assign(
                                        new Assign(
                                                child.id(),
                                                started.task().revision(),
                                                AssignmentMode.ASSIGNED,
                                                FIRST,
                                                List.of(),
                                                key(),
                                                null),
                                        OWNER))
                .hasMessageContaining("未开始");
        assertThat(tasks.detail(root.task().id(), OWNER).task().status()).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @EnumSource(
            value = AssignmentMode.class,
            names = {"UNASSIGNED", "OPEN"})
    void summaryDependenciesGateChildrenUntilThePredecessorReallyCompletes(AssignmentMode mode) {
        Detail root =
                create(
                        node("whole", AssignmentMode.UNASSIGNED, null),
                        List.of(
                                node("a", AssignmentMode.ASSIGNED, OWNER),
                                node("a1", "a", AssignmentMode.ASSIGNED, FIRST, List.of(), null),
                                node("b", null, mode, null, List.of("a"), null),
                                node("b1", "b", AssignmentMode.ASSIGNED, FIRST, List.of(), null),
                                node(
                                        "c",
                                        null,
                                        AssignmentMode.ASSIGNED,
                                        SECOND,
                                        List.of("b"),
                                        null)));
        Row a = row(root, "a"),
                a1 = row(root, "a1"),
                b = row(root, "b"),
                b1 = row(root, "b1"),
                c = row(root, "c");
        assertThat(tasks.detail(b1.id(), FIRST).task().canStart()).isFalse();
        assertThatThrownBy(() -> transition(b1.id(), Action.START, FIRST))
                .hasMessageContaining("前置");
        transition(a.id(), Action.START, OWNER);
        assertThatThrownBy(() -> transition(a.id(), Action.COMPLETE, OWNER))
                .hasMessageContaining("子任务");
        transition(a1.id(), Action.START, FIRST);
        transition(a1.id(), Action.COMPLETE, FIRST);
        // 已有负责人且无需验收的父任务随下级自动汇总，不能再要求负责人重复完成。
        assertThat(tasks.detail(a.id(), OWNER).task().status()).isEqualTo("COMPLETED");
        assertThat(tasks.detail(b1.id(), FIRST).task().canStart()).isTrue();
        transition(b1.id(), Action.START, FIRST);
        transition(b1.id(), Action.COMPLETE, FIRST);
        // 子任务完成不伪造汇总任务完成；后续仍等待 B 的真实完成动作。
        assertThat(tasks.detail(b.id(), OWNER).task().status()).isEqualTo("PENDING");
        assertThatThrownBy(() -> transition(c.id(), Action.START, SECOND))
                .hasMessageContaining("前置");
        Row currentB = tasks.detail(b.id(), OWNER).task();
        tasks.assign(
                new Assign(
                        b.id(),
                        currentB.revision(),
                        AssignmentMode.ASSIGNED,
                        OWNER,
                        List.of(),
                        key(),
                        "测试汇总结束"),
                OWNER);
        transition(b.id(), Action.START, OWNER);
        transition(b.id(), Action.COMPLETE, OWNER);
        assertThat(transition(c.id(), Action.START, SECOND).task().status()).isEqualTo("RUNNING");
        assertThat(tasks.detail(root.task().id(), OWNER).task().status()).isEqualTo("PENDING");
    }

    @Test
    void nestedSummariesCannotBypassAnUnstartedAssignedAncestor() {
        Detail root =
                create(
                        node("whole", AssignmentMode.ASSIGNED, OWNER),
                        List.of(
                                node("stage", AssignmentMode.UNASSIGNED, null),
                                node("group", "stage", AssignmentMode.OPEN, null, List.of(), null),
                                node(
                                        "leaf",
                                        "group",
                                        AssignmentMode.ASSIGNED,
                                        FIRST,
                                        List.of(),
                                        null)));
        Row leaf = row(root, "leaf");
        assertThat(tasks.detail(leaf.id(), FIRST).task().canStart()).isFalse();
        assertThatThrownBy(() -> transition(leaf.id(), Action.START, FIRST))
                .hasMessageContaining("上级任务");
        transition(root.task().id(), Action.START, OWNER);
        assertThat(transition(leaf.id(), Action.START, FIRST).task().status()).isEqualTo("RUNNING");
        assertThat(tasks.detail(row(root, "stage").id(), OWNER).task().status())
                .isEqualTo("PENDING");
        assertThat(tasks.detail(row(root, "group").id(), OWNER).task().status())
                .isEqualTo("PENDING");
    }

    @ParameterizedTest
    @EnumSource(
            value = AssignmentMode.class,
            names = {"UNASSIGNED", "OPEN"})
    void nestedSummariesTreatTheAncestorExpectedStartAsReference(AssignmentMode mode) {
        Detail root =
                create(
                        node("whole", AssignmentMode.UNASSIGNED, null),
                        List.of(
                                node(
                                        "stage",
                                        null,
                                        mode,
                                        null,
                                        List.of(),
                                        new Schedule(
                                                TimeMode.FIXED,
                                                LocalDateTime.now().plusDays(2),
                                                0,
                                                0)),
                                node(
                                        "group",
                                        "stage",
                                        AssignmentMode.UNASSIGNED,
                                        null,
                                        List.of(),
                                        null),
                                node(
                                        "leaf",
                                        "group",
                                        AssignmentMode.ASSIGNED,
                                        FIRST,
                                        List.of(),
                                        null)));
        Row leaf = row(root, "leaf");
        assertThat(tasks.detail(leaf.id(), FIRST).task().canStart()).isTrue();
        assertThat(transition(leaf.id(), Action.START, FIRST).task().status()).isEqualTo("RUNNING");
        Row stage = tasks.detail(row(root, "stage").id(), OWNER).task();
        assertThat(stage.expectedStart()).isAfter(LocalDateTime.now());
        assertThat(stage.actualStart()).isNull();
    }

    @ParameterizedTest
    @EnumSource(
            value = AssignmentMode.class,
            names = {"UNASSIGNED", "OPEN"})
    void cancellingAPredecessorDoesNotReleaseSummaryDescendants(AssignmentMode mode) {
        Detail root =
                create(
                        node("whole", AssignmentMode.UNASSIGNED, null),
                        List.of(
                                node("a", AssignmentMode.ASSIGNED, OWNER),
                                node("b", null, mode, null, List.of("a"), null),
                                node(
                                        "group",
                                        "b",
                                        AssignmentMode.UNASSIGNED,
                                        null,
                                        List.of(),
                                        null),
                                node(
                                        "leaf",
                                        "group",
                                        AssignmentMode.ASSIGNED,
                                        FIRST,
                                        List.of(),
                                        null)));
        transition(row(root, "a").id(), Action.CANCEL, OWNER);
        Row leaf = row(root, "leaf");
        assertThat(tasks.detail(leaf.id(), FIRST).task().canStart()).isFalse();
        assertThatThrownBy(() -> transition(leaf.id(), Action.START, FIRST))
                .hasMessageContaining("前置");
    }

    @Test
    void commentsCannotMentionPrivateSiblingAssignees() {
        Detail root =
                create(
                        node("root", AssignmentMode.ASSIGNED, OWNER),
                        List.of(
                                node("open", AssignmentMode.OPEN, null),
                                node("secret", AssignmentMode.ASSIGNED, SECOND)));
        Row open =
                root.nodes().stream()
                        .filter(n -> n.assignmentMode() == AssignmentMode.OPEN)
                        .findFirst()
                        .orElseThrow();
        tasks.claim(new Claim(open.id(), open.revision(), key()), FIRST);
        assertThatThrownBy(
                        () ->
                                tasks.comment(
                                        new AddComment(
                                                open.id(), null, "私有节点评论", List.of(SECOND), key()),
                                        FIRST))
                .hasMessageContaining("有权查看当前任务");
        assertThat(tasks.detail(open.id(), FIRST).comments()).isEmpty();
        assertThat(
                        tasks.comment(
                                        new AddComment(
                                                open.id(), null, "通知发起人", List.of(OWNER), key()),
                                        FIRST)
                                .content())
                .isEqualTo("通知发起人");
    }

    @Test
    void largeCandidateIdsMatchNumericAndStringJsonWithoutPrecisionLoss() {
        long candidate = 2105958645419823106L;
        NodeInput open =
                new NodeInput(
                        "large-candidate",
                        null,
                        marker + "large",
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
                        List.of(candidate));
        Detail created = create(open, List.of());
        assertThat(tasks.page(query("CLAIMABLE", null), candidate).getList())
                .extracting(Row::id)
                .containsExactly(created.task().id());
        // 正式底座把 Long 序列化为字符串；已有配置也可能保存数字，两种数据库编码均须精确匹配。
        jdbc.update(
                "update public.nocode_task_instance set"
                    + " config_json=jsonb_set(config_json::jsonb,'{candidateUserIds}',jsonb_build_array(cast(?"
                    + " as varchar)))::text where id=?",
                Long.toString(candidate),
                created.task().id());
        assertThat(tasks.page(query("CLAIMABLE", null), candidate).getTotal()).isEqualTo(1);
        assertThat(tasks.page(query("CLAIMABLE", null), candidate + 1).getTotal()).isZero();
        Detail claimed =
                tasks.claim(
                        new Claim(created.task().id(), created.task().revision(), key()),
                        candidate);
        assertThat(claimed.task().assigneeId()).isEqualTo(candidate);
    }

    @Test
    void assigningLegacyFixedTaskPreservesItsDeadlineDuringLaterRecalculation() {
        LocalDateTime deadline = LocalDateTime.now().minusDays(1).withNano(0);
        NodeInput legacy =
                new NodeInput(
                        "legacy-fixed",
                        null,
                        marker + "fixed",
                        null,
                        OWNER,
                        null,
                        null,
                        new Schedule(TimeMode.FIXED, deadline, 0, 0),
                        List.of(),
                        null,
                        null);
        Detail created = create(legacy, List.of());
        Detail assigned =
                tasks.assign(
                        new Assign(
                                created.task().id(),
                                created.task().revision(),
                                AssignmentMode.ASSIGNED,
                                FIRST,
                                List.of(),
                                key(),
                                null),
                        OWNER);
        assertThat(assigned.task().schedule().fixedEnd()).isEqualTo(deadline);
        tasks.create(
                new Create(
                        node("new-child", AssignmentMode.UNASSIGNED, null),
                        created.task().id(),
                        null,
                        null,
                        null,
                        null,
                        null,
                        key()),
                OWNER);
        assertThat(tasks.detail(created.task().id(), OWNER).task().expectedEnd())
                .isEqualTo(deadline);
    }

    @Test
    void managementRootPaginationCountsWholeTasksWithoutLosingPersonalChildren() {
        Set<String> expected = new LinkedHashSet<>();
        for (int index = 0; index < 3; index++) {
            Detail created =
                    create(
                            node("whole-" + index, AssignmentMode.UNASSIGNED, null),
                            List.of(
                                    node("a-" + index, AssignmentMode.ASSIGNED, FIRST),
                                    node("b-" + index, AssignmentMode.ASSIGNED, FIRST),
                                    node("c-" + index, AssignmentMode.ASSIGNED, FIRST)));
            expected.add(created.task().id());
        }
        Set<String> returned = new LinkedHashSet<>();
        for (int page = 1; page <= 3; page++) {
            com.lingan.ucp.framework.common.pojo.PageResult<Row> result =
                    tasks.page(rootQuery(null, marker, page, 1), OWNER);
            assertThat(result.getTotal()).isEqualTo(3);
            assertThat(result.getList()).hasSize(1);
            Row root = result.getList().getFirst();
            assertThat(root.id()).isEqualTo(root.rootId());
            assertThat(root.parentId()).isNull();
            assertThat(root.childCount()).isEqualTo(3);
            assertThat(tasks.detail(root.id(), OWNER).nodes()).hasSize(4);
            returned.add(root.id());
        }
        assertThat(returned).containsExactlyInAnyOrderElementsOf(expected);
        // 原查询不带聚合开关仍按节点计数，个人列表仍保留本人负责的全部子任务。
        assertThat(tasks.page(query("ALL", null), OWNER).getTotal()).isEqualTo(12);
        Query mine =
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
                        100);
        assertThat(tasks.page(mine, FIRST).getTotal()).isEqualTo(9);
        assertThat(tasks.page(mine, FIRST).getList()).allMatch(row -> row.parentId() != null);
    }

    @Test
    void childSearchFindsItsManagedRootButDoesNotGrantPrivateRootAccess() {
        Detail created =
                create(
                        node("whole", AssignmentMode.UNASSIGNED, null),
                        List.of(
                                node("needle-child", AssignmentMode.ASSIGNED, FIRST),
                                node("private-child", AssignmentMode.ASSIGNED, SECOND)));
        Query rootSearch = rootQuery(null, marker + "needle-child", 1, 20);
        assertThat(tasks.page(rootSearch, OWNER).getList())
                .extracting(Row::id)
                .containsExactly(created.task().id());
        assertThat(tasks.page(rootSearch, FIRST).getTotal()).isZero();
        assertThat(tasks.page(rootSearch, SECOND).getTotal()).isZero();
        Row child = row(created, "needle-child");
        assertThat(tasks.page(rootQuery(null, child.id(), 1, 20), OWNER).getTotal()).isEqualTo(1);
        assertThat(tasks.detail(child.id(), FIRST).task().groupStatus()).isNull();
        assertThatThrownBy(() -> tasks.detail(created.task().id(), FIRST))
                .hasMessageContaining("权限");
        Detail assignedRoot =
                create(
                        node("assigned-root", AssignmentMode.ASSIGNED, FIRST),
                        List.of(node("private-descendant", AssignmentMode.ASSIGNED, SECOND)));
        // 本人负责总任务可看到整组状态；该摘要仍不扩大私有下级的搜索与详情权限。
        assertThat(tasks.detail(assignedRoot.task().id(), FIRST).task().groupStatus())
                .isEqualTo("PENDING");
        assertThat(
                        tasks.page(rootQuery(null, marker + "private-descendant", 1, 20), FIRST)
                                .getTotal())
                .isZero();
    }

    @Test
    void wholeTaskRunsAfterAnyDescendantStartsButCompletesOnlyWithTheRoot() {
        Detail created =
                create(
                        node("whole", AssignmentMode.UNASSIGNED, null),
                        List.of(node("child", AssignmentMode.ASSIGNED, FIRST)));
        Row child = row(created, "child");
        assertThat(tasks.page(rootQuery("PENDING", marker, 1, 20), OWNER).getTotal()).isEqualTo(1);
        transition(child.id(), Action.START, FIRST);
        Row root = tasks.page(rootQuery("RUNNING", marker, 1, 20), OWNER).getList().getFirst();
        assertThat(root.status()).isEqualTo("PENDING");
        assertThat(root.groupStatus()).isEqualTo("RUNNING");
        assertThat(tasks.page(rootQuery("PENDING", marker, 1, 20), OWNER).getTotal()).isZero();
        assertThat(tasks.detail(root.id(), OWNER).task().groupStatus()).isEqualTo("RUNNING");
        transition(child.id(), Action.COMPLETE, FIRST);
        assertThat(tasks.page(rootQuery("RUNNING", marker, 1, 20), OWNER).getTotal()).isEqualTo(1);
        assertThat(tasks.page(rootQuery("COMPLETED", marker, 1, 20), OWNER).getTotal()).isZero();
        Row currentRoot = tasks.detail(root.id(), OWNER).task();
        tasks.assign(
                new Assign(
                        root.id(),
                        currentRoot.revision(),
                        AssignmentMode.ASSIGNED,
                        OWNER,
                        List.of(),
                        key(),
                        null),
                OWNER);
        transition(root.id(), Action.START, OWNER);
        transition(root.id(), Action.COMPLETE, OWNER);
        assertThat(tasks.page(rootQuery("COMPLETED", marker, 1, 20), OWNER).getList())
                .singleElement()
                .extracting(Row::groupStatus)
                .isEqualTo("COMPLETED");
        assertThat(tasks.page(rootQuery("RUNNING", marker, 1, 20), OWNER).getTotal()).isZero();
    }

    @Test
    void cancellingWithoutStartingDoesNotInventWholeTaskProgress() {
        Detail created =
                create(
                        node("whole", AssignmentMode.UNASSIGNED, null),
                        List.of(node("child", AssignmentMode.ASSIGNED, FIRST)));
        transition(row(created, "child").id(), Action.CANCEL, FIRST);
        assertThat(tasks.page(rootQuery("PENDING", marker, 1, 20), OWNER).getTotal()).isEqualTo(1);
        assertThat(tasks.page(rootQuery("RUNNING", marker, 1, 20), OWNER).getTotal()).isZero();
        transition(created.task().id(), Action.CANCEL, OWNER);
        assertThat(tasks.page(rootQuery("CANCELLED", marker, 1, 20), OWNER).getList())
                .singleElement()
                .extracting(Row::groupStatus)
                .isEqualTo("CANCELLED");
        assertThat(tasks.page(rootQuery("PENDING", marker, 1, 20), OWNER).getTotal()).isZero();
    }

    @Test
    void rootAggregationCannotBeUsedForPersonalOrPublicClaimableQueries() {
        for (String scope : List.of("MINE", "VISIBLE")) {
            Query query =
                    new Query(
                            scope,
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
                            20,
                            null,
                            null,
                            null,
                            true);
            assertThatThrownBy(() -> tasks.page(query, FIRST)).hasMessageContaining("整体任务分页");
        }
        Query claimable =
                new Query(
                        "MANAGE",
                        "CLAIMABLE",
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
                        20,
                        null,
                        null,
                        null,
                        true);
        assertThatThrownBy(() -> tasks.page(claimable, FIRST)).hasMessageContaining("整体任务分页");
    }

    private Query rootQuery(String status, String search, int page, int size) {
        return new Query(
                "MANAGE",
                "ALL",
                LocalDate.now(),
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
                true);
    }

    private boolean attempt(CountDownLatch start, String id, long actor, String key)
            throws InterruptedException {
        start.await();
        try {
            tasks.claim(new Claim(id, 0, key), actor);
            return true;
        } catch (com.lingan.ucp.framework.common.exception.ServiceException expected) {
            return false;
        }
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

    private NodeInput node(String id, AssignmentMode mode, Long assignee) {
        return node(id, null, mode, assignee, List.of(), null);
    }

    private NodeInput node(
            String id,
            String parentId,
            AssignmentMode mode,
            Long assignee,
            List<String> predecessors,
            Schedule schedule) {
        return new NodeInput(
                id,
                parentId,
                marker + id,
                "任务执行说明",
                assignee,
                null,
                null,
                schedule != null
                        ? schedule
                        : mode == null
                                ? new Schedule(TimeMode.T0, null, 0, 0)
                                : new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                predecessors,
                null,
                null,
                null,
                mode,
                mode == null ? null : List.of());
    }

    private Row row(Detail detail, String id) {
        return detail.nodes().stream()
                .filter(node -> node.title().equals(marker + id))
                .findFirst()
                .orElseThrow();
    }

    private Detail transition(String id, Action action, long actor) {
        Row current = tasks.detail(id, actor).task();
        return tasks.transition(new Transition(id, current.revision(), action, null, key()), actor);
    }

    private Query query(String tab, AssignmentMode mode) {
        return new Query(
                "VISIBLE", tab, null, marker, null, null, null, null, null, null, null, null, 1,
                100, null, null, mode);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
