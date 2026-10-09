package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.module.msg.api.IMsgSendService;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.*;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

/** 当前开发库验证自动收尾与个人上下文：仅清理本类唯一实例及业务夹具。 */
class TaskRollupPersonalIntegrationTest {
    private static final long CREATOR = 10001L, WORKER = 23001L, OTHER = 23002L, REVIEWER = 23003L;
    private final String marker = "rollup_personal_" + UUID.randomUUID() + "_";
    private final Set<String> roots = new LinkedHashSet<>();
    private TaskCenterService tasks;
    private WorkDraftIntegrationTest businessFixture;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void closeServices() {
        close();
    }

    @BeforeEach
    void setup() {
        tasks = servicesContext.getBean(TaskCenterService.class);
        when(servicesContext.getBean(IMsgSendService.class).send(any())).thenReturn(999L);
        when(servicesContext.getBean(AdminUserApi.class).getUser(anyLong()))
                .thenAnswer(
                        call -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(call.getArgument(0));
                            user.setNickname("汇总测试人员");
                            user.setStatus(0);
                            return user;
                        });
    }

    @AfterEach
    void cleanup() {
        for (String root : roots) {
            for (String table :
                    List.of(
                            "nocode_task_entry_record",
                            "nocode_task_entry_binding",
                            "nocode_task_plan",
                            "nocode_task_comment"))
                jdbc.update(
                        "delete from public."
                                + table
                                + " where task_id in (select id from public.nocode_task_instance"
                                + " where root_id=?)",
                        root);
            jdbc.update("delete from public.nocode_task_event where root_id=?", root);
            jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
        }
        if (businessFixture != null) businessFixture.cleanup();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private NodeInput node(String id, String parent, long owner, Long acceptor) {
        return new NodeInput(
                id,
                parent,
                marker + id,
                null,
                owner,
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

    private Detail create(long owner, Long acceptor, List<NodeInput> children) {
        Detail result =
                tasks.create(
                        new Create(
                                node("root", null, owner, acceptor),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key(),
                                children),
                        CREATOR);
        roots.add(result.task().id());
        return result;
    }

    private String id(Detail tree, String name) {
        return tree.nodes().stream()
                .filter(n -> n.title().equals(marker + name))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private Detail action(String id, Action action, long actor) {
        return tasks.transition(
                new Transition(
                        id,
                        tasks.detail(id, actor).task().revision(),
                        action,
                        action == Action.REJECT ? "整改后重新交付" : null,
                        key()),
                actor);
    }

    private void finish(String id, long actor) {
        action(id, Action.START, actor);
        action(id, Action.COMPLETE, actor);
    }

    private Query query(String tab, PersonalScope scope) {
        return new Query(
                "MINE", tab, null, marker, null, null, null, null, null, null, null, null, 1, 100,
                null, null, null, null, null, null, null, null, scope);
    }

    @Test
    void completesEveryParentBottomUpAndReplaysOnlyOneHistoryEvent() {
        Detail tree =
                create(
                        WORKER,
                        null,
                        List.of(
                                node("branch", null, WORKER, null),
                                node("leaf", "branch", WORKER, null)));
        action(tree.task().id(), Action.START, WORKER);
        action(id(tree, "branch"), Action.START, WORKER);
        action(id(tree, "leaf"), Action.START, WORKER);
        Row leaf = tasks.detail(id(tree, "leaf"), WORKER).task();
        Transition command =
                new Transition(leaf.id(), leaf.revision(), Action.COMPLETE, null, key());
        tasks.transition(command, WORKER);
        tasks.transition(command, WORKER);
        Detail done = tasks.detail(tree.task().id(), WORKER);
        assertThat(done.nodes()).allMatch(n -> n.status().equals("COMPLETED"));
        assertThat(done.events()).filteredOn(e -> e.type().equals("COMPLETED")).hasSize(3);
        assertThat(done.nodes()).allMatch(n -> n.actualEnd() != null);
    }

    @Test
    void leafRequiresExplicitCompletionAndReadsNeverMutateHistoricalParents() {
        Detail leaf = create(WORKER, null, List.of());
        action(leaf.task().id(), Action.START, WORKER);
        assertThat(tasks.detail(leaf.task().id(), WORKER).task().status()).isEqualTo("RUNNING");
        Detail tree = create(WORKER, null, List.of(node("historic", null, WORKER, null)));
        action(tree.task().id(), Action.START, WORKER);
        jdbc.update(
                "update public.nocode_task_instance set status='COMPLETED' where id=?",
                id(tree, "historic"));
        tasks.personalTreePage(query("TODO", PersonalScope.ACTION), WORKER);
        assertThat(tasks.detail(tree.task().id(), WORKER).task().status()).isEqualTo("RUNNING");
    }

    @Test
    void submitsOnlyRootAcceptanceAndRejectedRootCannotAutomaticallyResubmit() {
        Detail tree = create(WORKER, REVIEWER, List.of(node("leaf", null, OTHER, null)));
        action(tree.task().id(), Action.START, WORKER);
        finish(id(tree, "leaf"), OTHER);
        assertThat(tasks.detail(tree.task().id(), WORKER).task().status())
                .isEqualTo("PENDING_ACCEPTANCE");
        assertThat(
                        tasks.personalTreePage(query("TODO", PersonalScope.ACTION), REVIEWER)
                                .getList()
                                .getFirst()
                                .myPendingCount())
                .isEqualTo(1);
        action(tree.task().id(), Action.REJECT, REVIEWER);
        assertThat(tasks.detail(tree.task().id(), WORKER).task().completionReason()).contains("整改");
        // 新拆分任务完成也不能绕过退回后的显式重新提交。
        Detail extra =
                tasks.create(
                        new Create(
                                node("repair", null, WORKER, null),
                                tree.task().id(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        CREATOR);
        finish(extra.task().id(), WORKER);
        assertThat(tasks.detail(tree.task().id(), WORKER).task().status()).isEqualTo("RUNNING");
        action(tree.task().id(), Action.COMPLETE, WORKER);
        action(tree.task().id(), Action.APPROVE, REVIEWER);
        assertThat(tasks.detail(tree.task().id(), WORKER).task().status()).isEqualTo("COMPLETED");
        assertThat(tasks.detail(tree.task().id(), WORKER).events())
                .filteredOn(e -> e.type().equals("SUBMITTED_FOR_ACCEPTANCE"))
                .hasSize(2);
    }

    @Test
    void cancellationNeedsExplicitAcknowledgmentAndDeliveryNote() {
        Detail tree =
                create(
                        WORKER,
                        null,
                        List.of(
                                node("cancelled", null, WORKER, null),
                                node("done", null, WORKER, null)));
        action(tree.task().id(), Action.START, WORKER);
        action(id(tree, "cancelled"), Action.CANCEL, WORKER);
        finish(id(tree, "done"), WORKER);
        assertThat(tasks.detail(tree.task().id(), WORKER).task().status()).isEqualTo("RUNNING");
        assertThat(tasks.readiness(tree.task().id(), WORKER).checks())
                .filteredOn(c -> c.code() == TaskGuidance.CheckCode.CHILDREN_CANCELLED)
                .hasSize(1);
        assertThatThrownBy(() -> action(tree.task().id(), Action.COMPLETE, WORKER))
                .hasMessageContaining("确认");
        Row current = tasks.detail(tree.task().id(), WORKER).task();
        assertThatThrownBy(
                        () ->
                                tasks.transition(
                                        new Transition(
                                                current.id(),
                                                current.revision(),
                                                Action.COMPLETE,
                                                null,
                                                key(),
                                                true),
                                        WORKER))
                .hasMessageContaining("交付说明");
        tasks.transition(
                new Transition(
                        current.id(),
                        current.revision(),
                        Action.COMPLETE,
                        "取消项不影响剩余交付",
                        key(),
                        true),
                WORKER);
        assertThat(tasks.detail(current.id(), WORKER).task().status()).isEqualTo("COMPLETED");
    }

    @Test
    void completedContextStaysButEmployeeLeavesTodoWhenOwnWorkIsDone() {
        Detail tree =
                create(
                        OTHER,
                        null,
                        List.of(
                                node("a", null, WORKER, null),
                                node("b", null, WORKER, null),
                                node("private", null, OTHER, null)));
        action(tree.task().id(), Action.START, OTHER);
        finish(id(tree, "a"), WORKER);
        Query pending = query("TODO", PersonalScope.ACTION);
        List<PersonalTreeNode> groups = tasks.personalTreePage(pending, WORKER).getList();
        assertThat(groups).hasSize(1);
        assertThat(groups.getFirst().task().id()).isEqualTo(tree.task().id());
        assertThat(groups.getFirst().detailVisible()).isFalse();
        assertThat(groups.getFirst().contextOnly()).isTrue();
        assertThat(groups.getFirst().task().canExecute()).isFalse();
        assertThat(groups.getFirst().task().binding()).isNull();
        List<PersonalTreeNode> children =
                tasks.personalTreeChildren(
                        new PersonalTreeChildren(pending, tree.task().id()), WORKER);
        assertThat(children)
                .extracting(n -> n.task().id())
                .containsExactly(id(tree, "a"), id(tree, "b"), id(tree, "private"));
        assertThat(children.getLast().detailVisible()).isFalse();
        assertThat(children.getLast().contextOnly()).isTrue();
        assertThat(children.getFirst().task().status()).isEqualTo("COMPLETED");
        assertThat(children.getFirst().contextOnly()).isTrue();
        assertThatThrownBy(() -> tasks.detail(tree.task().id(), WORKER)).hasMessageContaining("权限");
        finish(id(tree, "b"), WORKER);
        assertThat(tasks.personalTreePage(pending, WORKER).getTotal()).isZero();
        PersonalTreeNode history =
                tasks.personalTreePage(query("DONE", null), WORKER).getList().getFirst();
        assertThat(history.task().groupStatus()).isEqualTo("RUNNING");
        assertThat(history.myCompletedCount()).isEqualTo(2);
        assertThat(tasks.personalTreePage(query("TODO", PersonalScope.FOLLOW_UP), OTHER).getTotal())
                .isZero();
    }

    @Test
    void rootOwnerCanFollowOtherWorkWithoutMakingItExecutionTodo() {
        Detail tree =
                create(
                        WORKER,
                        null,
                        List.of(node("a", null, WORKER, null), node("b", null, OTHER, null)));
        action(tree.task().id(), Action.START, WORKER);
        finish(id(tree, "a"), WORKER);
        assertThat(tasks.personalTreePage(query("TODO", PersonalScope.ACTION), WORKER).getTotal())
                .isZero();
        assertThat(
                        tasks.personalTreePage(query("TODO", PersonalScope.FOLLOW_UP), WORKER)
                                .getTotal())
                .isEqualTo(1);
        finish(id(tree, "b"), OTHER);
        assertThat(
                        tasks.personalTreePage(query("TODO", PersonalScope.FOLLOW_UP), WORKER)
                                .getTotal())
                .isZero();
        assertThat(tasks.detail(tree.task().id(), WORKER).task().status()).isEqualTo("COMPLETED");
    }

    @Test
    void progressCountsCompleteDirectChildrenWithoutSkippingOtherPeoplesBranches() {
        Detail tree =
                create(
                        OTHER,
                        null,
                        List.of(
                                node("hidden-parent", null, OTHER, null),
                                node("visible-done", "hidden-parent", WORKER, null),
                                node("visible-todo", "hidden-parent", WORKER, null),
                                node("hidden-sibling", "hidden-parent", OTHER, null)));
        action(tree.task().id(), Action.START, OTHER);
        action(id(tree, "hidden-parent"), Action.START, OTHER);
        finish(id(tree, "visible-done"), WORKER);
        Query pending = query("TODO", PersonalScope.ACTION);
        PersonalTreeNode group = tasks.personalTreePage(pending, WORKER).getList().getFirst();
        assertThat(group.detailVisible()).isFalse();
        assertThat(group.matchingChildCount()).isEqualTo(1);
        assertThat(group.completedChildCount()).isZero();
        assertThat(
                        tasks.personalTreeChildren(
                                new PersonalTreeChildren(pending, tree.task().id()), WORKER))
                .extracting(n -> n.task().id())
                .containsExactly(id(tree, "hidden-parent"));
        PersonalTreeNode branch =
                tasks.personalTreeChildren(
                                new PersonalTreeChildren(pending, tree.task().id()), WORKER)
                        .getFirst();
        assertThat(branch.matchingChildCount()).isEqualTo(3);
        assertThat(branch.completedChildCount()).isEqualTo(1);
        assertThat(
                        tasks.personalTreeChildren(
                                new PersonalTreeChildren(pending, branch.task().id()), WORKER))
                .extracting(n -> n.task().id())
                .containsExactlyInAnyOrder(
                        id(tree, "visible-done"),
                        id(tree, "visible-todo"),
                        id(tree, "hidden-sibling"));
    }

    @Test
    void nonRootCoordinatorStillHasFollowUpEntryForColleagueGrandchild() {
        Detail tree =
                create(
                        OTHER,
                        null,
                        List.of(
                                node("branch", null, WORKER, null),
                                node("colleague", "branch", OTHER, null)));
        action(tree.task().id(), Action.START, OTHER);
        action(id(tree, "branch"), Action.START, WORKER);
        assertThat(tasks.personalTreePage(query("TODO", PersonalScope.ACTION), WORKER).getTotal())
                .isZero();
        List<PersonalTreeNode> follow =
                tasks.personalTreePage(query("TODO", PersonalScope.FOLLOW_UP), WORKER).getList();
        assertThat(follow).hasSize(1);
        assertThat(follow.getFirst().task().id()).isEqualTo(tree.task().id());
        assertThat(follow.getFirst().contextOnly()).isTrue();
    }

    @Test
    void finalChildrenCompletingConcurrentlyRollupParentExactlyOnce() throws Exception {
        Detail tree =
                create(
                        WORKER,
                        null,
                        List.of(node("a", null, WORKER, null), node("b", null, OTHER, null)));
        action(tree.task().id(), Action.START, WORKER);
        action(id(tree, "a"), Action.START, WORKER);
        action(id(tree, "b"), Action.START, OTHER);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<?> one =
                    executor.submit(
                            () -> {
                                try {
                                    go.await();
                                    action(id(tree, "a"), Action.COMPLETE, WORKER);
                                } catch (InterruptedException e) {
                                    throw new IllegalStateException(e);
                                }
                            });
            Future<?> two =
                    executor.submit(
                            () -> {
                                try {
                                    go.await();
                                    action(id(tree, "b"), Action.COMPLETE, OTHER);
                                } catch (InterruptedException e) {
                                    throw new IllegalStateException(e);
                                }
                            });
            go.countDown();
            one.get(30, TimeUnit.SECONDS);
            two.get(30, TimeUnit.SECONDS);
            assertThat(tasks.detail(tree.task().id(), WORKER).task().status())
                    .isEqualTo("COMPLETED");
            assertThat(tasks.detail(tree.task().id(), WORKER).events())
                    .filteredOn(
                            e ->
                                    e.taskId().equals(tree.task().id())
                                            && e.type().equals("COMPLETED"))
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void requiredBusinessMaterialStopsOnlyParentAndPreservesChildCompletion() {
        businessFixture = new WorkDraftIntegrationTest();
        businessFixture.setup();
        Binding binding =
                new Binding(
                        businessFixture.resource.applicationId(),
                        businessFixture.resource.resourceId(),
                        null);
        NodeInput middle =
                new NodeInput(
                        "middle",
                        null,
                        marker + "middle",
                        null,
                        CREATOR,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        binding,
                        null,
                        null,
                        AssignmentMode.ASSIGNED,
                        List.of());
        Detail tree =
                tasks.create(
                        new Create(
                                node("root", null, WORKER, null),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key(),
                                List.of(middle, node("child", "middle", WORKER, null))),
                        CREATOR);
        roots.add(tree.task().id());
        action(tree.task().id(), Action.START, WORKER);
        action(id(tree, "middle"), Action.START, CREATOR);
        finish(id(tree, "child"), WORKER);
        assertThat(tasks.detail(id(tree, "child"), WORKER).task().status()).isEqualTo("COMPLETED");
        Row parent = tasks.detail(id(tree, "middle"), CREATOR).task();
        assertThat(parent.status()).isEqualTo("RUNNING");
        assertThat(parent.completionReason()).contains("表单");
        assertThat(tasks.readiness(parent.id(), CREATOR).canComplete()).isFalse();
        assertThat(tasks.detail(tree.task().id(), WORKER).task().status()).isEqualTo("RUNNING");
    }
}
