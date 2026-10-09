package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.module.msg.api.IMsgSendService;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.api.TaskPlanning;
import com.richuang.os.nocode.controller.admin.task.TaskCenterController;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskCenterService;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.*;
import org.springframework.security.access.prepost.PreAuthorize;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

/** 员工删除使用当前开发库和正式事务，只清理由随机根任务证明归属的本类夹具。 */
class TaskEmployeeDeleteIntegrationTest {
    private static final long BOSS = 10001L, WORKER = 21001L, OTHER = 21002L;
    private final String marker = "employee_delete_" + UUID.randomUUID() + "_";
    private final Set<String> roots = new LinkedHashSet<>();
    private TaskCenterService tasks;

    @BeforeAll
    static void open() throws Exception {
        try {
            connect();
        } catch (FlywayValidateException pendingMigrations) {
            Flyway.configure()
                    .configuration(databaseTool.flyway().getConfiguration())
                    .ignoreMigrationPatterns("*:pending", "*:future")
                    .load()
                    .validate();
        }
    }

    @AfterAll
    static void closeContext() {
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
                            user.setStatus(0);
                            user.setNickname("删除验证" + user.getId());
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
            for (String table :
                    List.of(
                            "nocode_task_plan",
                            "nocode_task_comment",
                            "nocode_task_record_link",
                            "nocode_task_entry_record",
                            "nocode_task_entry_binding")) {
                jdbc.update(
                        "delete from public."
                                + table
                                + " where task_id in(select id from public.nocode_task_instance"
                                + " where root_id=?)",
                        root);
            }
            jdbc.update("delete from public.nocode_task_event where root_id=?", root);
            jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
        }
    }

    @Test
    void ownSplitDeletesSoftlyArchivesPlansPreservesAuditAndRetries() throws Exception {
        Detail root = root();
        Row child = split(root.task().id());
        assertThat(child.canDelete()).isTrue();
        assertThat(child.deleteBlockedReason()).isNull();
        tasks.comment(new AddComment(child.id(), null, "拆分备注保留", List.of(), key()), WORKER);
        TaskPlanning.ChecklistContext context =
                tasks.checklistContext(
                        new TaskPlanning.ContextQuery(
                                List.of(child.id()), TaskPlanning.Target.SELF),
                        WORKER);
        tasks.checklist(
                new TaskPlanning.ChecklistChange(
                        List.of(child.id()),
                        TaskPlanning.Target.SELF,
                        TaskPlanning.ChecklistAction.ADD,
                        Period.DAY,
                        LocalDate.now(),
                        List.of(),
                        Map.of(child.id(), context.items().getFirst().version()),
                        key()),
                WORKER);
        Row current = tasks.detail(child.id(), WORKER).task();
        DeleteSubtask command = new DeleteSubtask(child.id(), current.revision(), key());
        tasks.deleteSubtask(command, WORKER);
        tasks.deleteSubtask(command, WORKER);

        assertThat(
                        jdbc.queryForObject(
                                "select deleted from public.nocode_task_instance where id=?",
                                Integer.class,
                                child.id()))
                .isEqualTo(1);
        assertThat(tasks.detail(root.task().id(), WORKER).task().childCount()).isZero();
        assertThat(tasks.detail(root.task().id(), WORKER).nodes())
                .extracting(Row::id)
                .doesNotContain(child.id());
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_plan where task_id=? and"
                                        + " deleted=0",
                                Integer.class,
                                child.id()))
                .isZero();
        assertThat(
                        jdbc.queryForList(
                                "select distinct history_reason from public.nocode_task_plan where"
                                        + " task_id=?",
                                String.class,
                                child.id()))
                .containsExactly("TASK_DELETED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_comment where task_id=?",
                                Integer.class,
                                child.id()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_event where root_id=? and"
                                        + " event_type='SUBTASK_DELETED'",
                                Integer.class,
                                root.task().id()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select task_id from public.nocode_task_event where request_key=?",
                                String.class,
                                command.requestKey()))
                .isEqualTo(root.task().id());
        assertThatThrownBy(
                        () ->
                                tasks.deleteSubtask(
                                        new DeleteSubtask(
                                                root.task().id(), 0, command.requestKey()),
                                        WORKER))
                .hasMessageContaining("同一请求标识");
        assertThat(
                        TaskCenterController.class
                                .getMethod("deleteSubtask", JsonNode.class)
                                .getAnnotation(PreAuthorize.class)
                                .value())
                .isEqualTo("@nocodeAccess.taskQuery()");
    }

    @Test
    void originalAndTemplateIssuedNodesAreNotPersonalSplits() {
        Detail root =
                tasks.create(
                        new Create(
                                node("root", "自己发起", WORKER),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key(),
                                List.of(node("original", "原始节点", WORKER))),
                        WORKER);
        roots.add(root.task().id());
        Row original =
                root.nodes().stream()
                        .filter(row -> row.parentId() != null)
                        .findFirst()
                        .orElseThrow();
        assertDenied(original, WORKER, "原始安排");
        jdbc.update(
                "update public.nocode_task_instance set template_node_id='original-template-node'"
                        + " where id=?",
                original.id());
        assertDenied(original, WORKER, "模板下发");
        assertDenied(root.task(), WORKER, "总任务");
    }

    @Test
    void oldUnprovenNodesFailClosedInsteadOfGuessingFromCreator() {
        Row child = split(root().task().id());
        jdbc.update(
                "update public.nocode_task_event set material_json=null where task_id=? and"
                        + " event_type='CREATED'",
                child.id());
        assertDenied(child, WORKER, "旧任务来源无法确认");
    }

    @Test
    void delegatorRetainsDeletionButRecipientAndCreatorWithoutCoordinationDoNotGainIt() {
        Row child = split(root().task().id());
        assertDenied(child, BOSS, "原拆分人");
        assign(child.id(), OTHER, WORKER);
        assertDenied(child, OTHER, "原拆分人");
        Row delegated = tasks.detail(child.id(), WORKER).task();
        assertThat(delegated.canDelegate()).isTrue();
        assertThat(delegated.canDelete()).isTrue();
        clearInvocations(servicesContext.getBean(IMsgSendService.class));
        DeleteSubtask command = new DeleteSubtask(child.id(), delegated.revision(), key());
        tasks.deleteSubtask(command, WORKER);
        tasks.deleteSubtask(command, WORKER);
        verify(servicesContext.getBean(IMsgSendService.class), times(1))
                .send(
                        argThat(
                                message ->
                                        message.getTitle().startsWith("子任务已删除：")
                                                && message.getTargets().stream()
                                                        .anyMatch(
                                                                target ->
                                                                        target.getTargetId()
                                                                                .equals(
                                                                                        Long
                                                                                                .toString(
                                                                                                        OTHER)))));
    }

    @Test
    void originalSplitterCoordinatesOnlyWhileStillOwningDirectParent() {
        Detail root = root();
        Row parent = split(root.task().id());
        Row child = split(parent.id());
        assign(root.task().id(), OTHER, BOSS);
        assign(child.id(), OTHER, WORKER);
        Row delegated = tasks.detail(child.id(), WORKER).task();
        assertThat(delegated.canDelegate()).isTrue();
        assertThat(delegated.canDelete()).isTrue();
        assign(child.id(), WORKER, WORKER);
        assign(child.id(), OTHER, WORKER);
        assign(parent.id(), OTHER, BOSS);
        Row revoked = tasks.detail(child.id(), WORKER).task();
        assertThat(revoked.canDelegate()).isFalse();
        assertThat(revoked.canDelete()).isFalse();
        assertThatThrownBy(() -> assign(child.id(), WORKER, WORKER)).hasMessageContaining("原拆分人");
        assertDenied(revoked, WORKER, "原拆分人");
    }

    @Test
    void rootCoordinatorCanDeleteOthersPersonalSplitWithoutGainingContentAccess() {
        Detail root = root();
        Row child = split(root.task().id());
        assign(root.task().id(), OTHER, BOSS);
        Detail otherView = tasks.detail(root.task().id(), OTHER);
        assertThat(otherView.nodes()).extracting(Row::id).doesNotContain(child.id());
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
                        100,
                        null,
                        null,
                        null,
                        false,
                        null,
                        null,
                        null);
        PersonalTreeNode summary =
                tasks
                        .personalTreeChildren(
                                new PersonalTreeChildren(query, root.task().id()), OTHER)
                        .stream()
                        .filter(node -> node.task().id().equals(child.id()))
                        .findFirst()
                        .orElseThrow();
        assertThat(summary.contextOnly()).isTrue();
        assertThat(summary.detailVisible()).isFalse();
        assertThat(summary.task().canDelegate()).isTrue();
        assertThat(summary.task().canDelete()).isTrue();
        assertThat(summary.task().canExecute()).isFalse();
        assertThat(summary.task().description()).isNull();
        assertThat(summary.task().entries()).isEmpty();
        int revision = tasks.detail(child.id(), WORKER).task().revision();
        tasks.deleteSubtask(new DeleteSubtask(child.id(), revision, key()), OTHER);
        assertThat(
                        jdbc.queryForObject(
                                "select deleted from public.nocode_task_instance where id=?",
                                Integer.class,
                                child.id()))
                .isEqualTo(1);
    }

    @Test
    void explicitTransferPreservesExecutionHistoryAndArchivesOutgoingPlans() {
        Detail root = root();
        Row child = split(root.task().id());
        tasks.plan(new SavePlan(List.of(child.id()), Period.DAY, LocalDate.now(), true), WORKER);
        start(root.task().id());
        start(child.id());
        tasks.comment(new AddComment(child.id(), null, "交接前办理记录", List.of(), key()), WORKER);
        Row before = tasks.detail(child.id(), WORKER).task();
        assertThat(before.canTransfer()).isTrue();
        assertThat(before.canDelegate()).isFalse();
        assertThatThrownBy(() -> assign(child.id(), OTHER, WORKER)).hasMessageContaining("仅未开始");
        Assign command = transfer(before, OTHER, "已完成准备，接手后继续执行");
        tasks.assign(command, WORKER);
        tasks.assign(command, WORKER);
        Row after = tasks.detail(child.id(), OTHER).task();
        assertThat(after.assigneeId()).isEqualTo(OTHER);
        assertThat(after.status()).isEqualTo(before.status());
        assertThat(after.actualStart()).isEqualTo(before.actualStart());
        assertThat(after.expectedStart()).isEqualTo(before.expectedStart());
        assertThat(after.expectedEnd()).isEqualTo(before.expectedEnd());
        assertThat(after.canTransfer()).isFalse();
        assertThat(tasks.detail(child.id(), WORKER).task().canExecute()).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_comment where task_id=?",
                                Integer.class,
                                child.id()))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_plan where task_id=? and"
                                        + " deleted=0",
                                Integer.class,
                                child.id()))
                .isZero();
        assertThat(tasks.detail(child.id(), WORKER).events())
                .filteredOn(
                        event -> event.type().equals("ASSIGNED") && event.note().contains("转交在办任务"))
                .hasSize(1);
        assertThatThrownBy(() -> tasks.assign(transfer(before, WORKER, "旧页面"), WORKER))
                .hasMessageContaining("其他人修改");
    }

    @Test
    void transferRequiresNamedDifferentRecipientAndHandoverNote() {
        Detail root = root();
        Row child = split(root.task().id());
        assertThatThrownBy(() -> tasks.assign(transfer(child, OTHER, "尚未开始"), WORKER))
                .hasMessageContaining("在办子任务");
        start(root.task().id());
        start(child.id());
        Row running = tasks.detail(child.id(), WORKER).task();
        assertThatThrownBy(() -> tasks.assign(transfer(running, OTHER, ""), WORKER))
                .hasMessageContaining("交接说明");
        assertThatThrownBy(() -> tasks.assign(transfer(running, WORKER, "没有换人"), WORKER))
                .hasMessageContaining("不同的负责人");
        assertThatThrownBy(
                        () ->
                                tasks.assign(
                                        new Assign(
                                                running.id(),
                                                running.revision(),
                                                AssignmentMode.OPEN,
                                                null,
                                                List.of(),
                                                key(),
                                                "改为领取",
                                                null,
                                                true),
                                        WORKER))
                .hasMessageContaining("指定新负责人");
        assertThatThrownBy(() -> tasks.assign(transfer(running, OTHER, "越权交接"), OTHER))
                .hasMessageContaining("原拆分人");
    }

    @Test
    void selfPausedTransferStaysPausedButParentPauseAndFinishedStatesBlockIt() {
        Detail root = root();
        Row child = split(root.task().id());
        start(root.task().id());
        start(child.id());
        Row running = tasks.detail(child.id(), WORKER).task();
        tasks.transition(
                new Transition(child.id(), running.revision(), Action.PAUSE, "等待材料", key()),
                WORKER);
        Row paused = tasks.detail(child.id(), WORKER).task();
        assertThat(paused.canTransfer()).isTrue();
        tasks.assign(transfer(paused, OTHER, "由同事接续等待材料"), WORKER);
        Row received = tasks.detail(child.id(), OTHER).task();
        assertThat(received.status()).isEqualTo("PAUSED");
        assertThat(received.canResume()).isTrue();
        assertThat(received.pauseReason()).isEqualTo(paused.pauseReason());
        Row currentRoot = tasks.detail(root.task().id(), WORKER).task();
        tasks.transition(
                new Transition(
                        currentRoot.id(), currentRoot.revision(), Action.PAUSE, "整体暂停", key()),
                WORKER);
        Row blocked = tasks.detail(child.id(), WORKER).task();
        assertThat(blocked.canTransfer()).isFalse();
        assertThatThrownBy(() -> tasks.assign(transfer(blocked, WORKER, "上级暂停"), WORKER))
                .hasMessageContaining("实例不能");
        jdbc.update(
                "update public.nocode_task_instance set status='RUNNING' where id=?",
                root.task().id());
        for (State state : List.of(State.PENDING_ACCEPTANCE, State.COMPLETED, State.CANCELLED)) {
            jdbc.update(
                    "update public.nocode_task_instance set status=? where id=?",
                    state.name(),
                    child.id());
            Row ended = tasks.detail(child.id(), WORKER).task();
            assertThat(ended.canTransfer()).isFalse();
            assertThatThrownBy(() -> tasks.assign(transfer(ended, WORKER, "不可更换"), WORKER))
                    .hasMessageContaining("在办子任务");
        }
    }

    @Test
    void failedDeletionNotificationRollsBackTaskAndReceipt() {
        Row child = split(root().task().id());
        assign(child.id(), OTHER, WORKER);
        Row delegated = tasks.detail(child.id(), WORKER).task();
        when(servicesContext.getBean(IMsgSendService.class).send(any())).thenReturn(null);
        String request = key();
        assertThatThrownBy(
                        () ->
                                tasks.deleteSubtask(
                                        new DeleteSubtask(
                                                child.id(), delegated.revision(), request),
                                        WORKER))
                .hasMessageContaining("删除提醒");
        assertThat(
                        jdbc.queryForObject(
                                "select deleted from public.nocode_task_instance where id=?",
                                Integer.class,
                                child.id()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_event where request_key=?",
                                Integer.class,
                                request))
                .isZero();
    }

    @Test
    void transferringParentDoesNotReassignDescendantsOrResetTheirPlans() {
        Detail root = root();
        Row parent = split(root.task().id());
        Row child = split(parent.id());
        tasks.plan(new SavePlan(List.of(child.id()), Period.DAY, LocalDate.now(), true), WORKER);
        start(root.task().id());
        start(parent.id());
        Row before = tasks.detail(parent.id(), WORKER).task();
        tasks.assign(transfer(before, OTHER, "转交本节点，原子项分工保持"), WORKER);
        Row unchanged = tasks.detail(child.id(), WORKER).task();
        assertThat(unchanged.assigneeId()).isEqualTo(WORKER);
        assertThat(unchanged.status()).isEqualTo("PENDING");
        assertThat(unchanged.plans()).isNotEmpty();
        assertThat(tasks.detail(parent.id(), OTHER).task().canDelegate()).isFalse();
        assertThatThrownBy(() -> assign(child.id(), OTHER, OTHER)).hasMessageContaining("原拆分人");
    }

    @Test
    void transferAndOutgoingOwnerCompletionCannotBothCommit() throws Exception {
        Detail root = root();
        Row child = split(root.task().id());
        start(root.task().id());
        start(child.id());
        Row running = tasks.detail(child.id(), WORKER).task();
        CountDownLatch ready = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> transfer =
                    executor.submit(
                            () -> {
                                ready.await();
                                try {
                                    tasks.assign(transfer(running, OTHER, "并发交接"), WORKER);
                                    return true;
                                } catch (
                                        com.richuang.os.framework.common.exception.ServiceException
                                                denied) {
                                    return false;
                                }
                            });
            Future<Boolean> complete =
                    executor.submit(
                            () -> {
                                ready.await();
                                try {
                                    tasks.transition(
                                            new Transition(
                                                    child.id(),
                                                    running.revision(),
                                                    Action.COMPLETE,
                                                    null,
                                                    key()),
                                            WORKER);
                                    return true;
                                } catch (
                                        com.richuang.os.framework.common.exception.ServiceException
                                                denied) {
                                    return false;
                                }
                            });
            ready.countDown();
            assertThat(
                            List.of(
                                    transfer.get(20, TimeUnit.SECONDS),
                                    complete.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    private void assign(String id, long assignee, long actor) {
        int revision =
                jdbc.queryForObject(
                        "select lock_version from public.nocode_task_instance where id=?",
                        Integer.class,
                        id);
        tasks.assign(
                new Assign(
                        id, revision, AssignmentMode.ASSIGNED, assignee, List.of(), key(), "分工调整"),
                actor);
    }

    private Assign transfer(Row row, long assignee, String note) {
        return new Assign(
                row.id(),
                row.revision(),
                AssignmentMode.ASSIGNED,
                assignee,
                List.of(),
                key(),
                note,
                null,
                true);
    }

    private void start(String id) {
        Row row = tasks.detail(id, WORKER).task();
        tasks.transition(new Transition(id, row.revision(), Action.START, null, key()), WORKER);
    }

    @Test
    void staleRevisionDoesNotDeleteAndCanBeRetriedWithFreshView() {
        Row child = split(root().task().id());
        jdbc.update(
                "update public.nocode_task_instance set lock_version=lock_version+1 where id=?",
                child.id());
        assertThatThrownBy(
                        () ->
                                tasks.deleteSubtask(
                                        new DeleteSubtask(child.id(), child.revision(), key()),
                                        WORKER))
                .hasMessageContaining("任务已被其他人修改");
        assertThat(
                        jdbc.queryForObject(
                                "select deleted from public.nocode_task_instance where id=?",
                                Integer.class,
                                child.id()))
                .isZero();
        Row refreshed = tasks.detail(child.id(), WORKER).task();
        tasks.deleteSubtask(new DeleteSubtask(child.id(), refreshed.revision(), key()), WORKER);
    }

    @Test
    void allExecutedStatesAndHistoricalStartPreventDeletion() {
        Row child = split(root().task().id());
        for (State state :
                List.of(
                        State.RUNNING,
                        State.PAUSED,
                        State.PENDING_ACCEPTANCE,
                        State.COMPLETED,
                        State.CANCELLED)) {
            jdbc.update(
                    "update public.nocode_task_instance set status=? where id=?",
                    state.name(),
                    child.id());
            assertDenied(child, WORKER, "已开始或已结束");
        }
        jdbc.update(
                "update public.nocode_task_instance set status='PENDING',actual_start=now() where"
                        + " id=?",
                child.id());
        assertDenied(child, WORKER, "已开始或已结束");
    }

    @Test
    void lockedAncestorsPreventDeletionButPendingUnderRunningParentCanDelete() {
        Detail root = root();
        Row child = split(root.task().id());
        for (State state :
                List.of(State.PAUSED, State.PENDING_ACCEPTANCE, State.COMPLETED, State.CANCELLED)) {
            jdbc.update(
                    "update public.nocode_task_instance set status=? where id=?",
                    state.name(),
                    root.task().id());
            assertDenied(child, WORKER, state == State.PAUSED ? "暂停" : "上级任务");
        }
        jdbc.update(
                "update public.nocode_task_instance set status='RUNNING',actual_start=now() where"
                        + " id=?",
                root.task().id());
        tasks.deleteSubtask(new DeleteSubtask(child.id(), child.revision(), key()), WORKER);
        assertThat(tasks.detail(root.task().id(), WORKER).task().status()).isEqualTo("RUNNING");
    }

    @Test
    void parentIsNeverCascadeDeleted() {
        Detail root = root();
        Row parent = split(root.task().id());
        Row child = split(parent.id());
        assertDenied(parent, WORKER, "还有下级任务");
        tasks.deleteSubtask(new DeleteSubtask(child.id(), child.revision(), key()), WORKER);
        assertThat(tasks.detail(parent.id(), WORKER).task().canDelete()).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select deleted from public.nocode_task_instance where id=?",
                                Integer.class,
                                parent.id()))
                .isZero();
    }

    @Test
    void successorAndDataSourceReferencesMustBeHandledByManager() {
        Detail root = root();
        Row child = split(root.task().id());
        Row other = split(root.task().id());
        jdbc.update(
                "update public.nocode_task_instance set"
                    + " config_json=jsonb_set(config_json::jsonb,'{predecessorIds}',jsonb_build_array(?::text))::text"
                    + " where id=?",
                child.id(),
                other.id());
        assertDenied(child, WORKER, "后续任务依赖");
        jdbc.update(
                "update public.nocode_task_instance set"
                        + " config_json=jsonb_set(config_json::jsonb,'{predecessorIds}','[]')::text"
                        + " where id=?",
                other.id());
        jdbc.update(
                "update public.nocode_task_instance set"
                    + " config_json=jsonb_set(config_json::jsonb,'{sharing}',jsonb_build_object('mode','SHARED','sourceNodeId',?::text,'writableFieldIds','[]'::jsonb))::text"
                    + " where id=?",
                child.id(),
                other.id());
        assertDenied(child, WORKER, "数据来源");
    }

    @Test
    void removedOrSupersededHandlingFactsStillProtectStandardWorkHistory() {
        Row child = split(root().task().id());
        jdbc.update(
                "insert into"
                    + " public.nocode_task_entry_record(id,task_id,entry_key,dataset_id,business_json,operation,snapshot_json,request_key,request_hash,creator,updater,deleted,superseded_by)"
                    + " values(?,?,'work',?,'{}','CREATED','{}',?,'fixture',?,?,1,'later-fact')",
                key(),
                child.id(),
                child.id() + ":work",
                key(),
                Long.toString(WORKER),
                Long.toString(WORKER));
        assertDenied(child, WORKER, "业务记录、关联或工时");
    }

    @Test
    void unlinkedBusinessRecordsStillProtectHistoricalRelations() {
        Row child = split(root().task().id());
        jdbc.update(
                "insert into"
                    + " public.nocode_task_record_link(id,task_id,application_id,object_id,record_id,creator,updater,deleted)"
                    + " values(?,?,'fixture-app','fixture-object','record',?,?,1)",
                key(),
                child.id(),
                Long.toString(WORKER),
                Long.toString(WORKER));
        assertDenied(child, WORKER, "业务记录、关联或工时");
    }

    @Test
    void persistedEntrySourceBlocksDeletionEvenIfNodeConfigWasNotCopied() {
        Detail root = root();
        Row child = split(root.task().id());
        Row other = split(root.task().id());
        jdbc.update(
                "insert into"
                    + " public.nocode_task_entry_binding(id,task_id,entry_key,dataset_id,config_json,business_json,creator,updater)"
                    + " values(?,?,'source',?,jsonb_build_object('sourceNodeId',?::text)::text,'{}',?,?)",
                key(),
                other.id(),
                child.id() + ":source",
                child.id(),
                Long.toString(BOSS),
                Long.toString(BOSS));
        assertThatThrownBy(
                        () ->
                                tasks.deleteSubtask(
                                        new DeleteSubtask(child.id(), child.revision(), key()),
                                        WORKER))
                .hasMessageContaining("数据来源");
        assertThat(
                        jdbc.queryForObject(
                                "select deleted from public.nocode_task_instance where id=?",
                                Integer.class,
                                child.id()))
                .isZero();
    }

    @Test
    void sourceWorkflowGuardProtectsCapabilityAndWrite() {
        Row child = split(root().task().id());
        String guardName = "employeeDeleteWorkflowGuard";
        servicesContext
                .getBeanFactory()
                .registerSingleton(
                        guardName,
                        (com.richuang.os.nocode.api.workflow.WorkflowTaskGuard)
                                rootId -> {
                                    if (rootId.equals(child.rootId()))
                                        throw com.richuang.os.nocode.api.NocodeErrorCodes.invalid(
                                                "来源流程已暂停");
                                });
        try {
            assertDenied(child, WORKER, "来源流程已暂停");
        } finally {
            servicesContext.getDefaultListableBeanFactory().destroySingleton(guardName);
        }
        assertThat(tasks.detail(child.id(), WORKER).task().canDelete()).isTrue();
    }

    @Test
    void executionAndDeletionCannotBothCommit() throws Exception {
        Detail root = root();
        tasks.transition(
                new Transition(root.task().id(), root.task().revision(), Action.START, null, key()),
                WORKER);
        Row child = split(root.task().id());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> deletion =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    tasks.deleteSubtask(
                                            new DeleteSubtask(child.id(), child.revision(), key()),
                                            WORKER);
                                    return true;
                                } catch (
                                        com.richuang.os.framework.common.exception.ServiceException
                                                denied) {
                                    return false;
                                }
                            });
            Future<Boolean> execution =
                    executor.submit(
                            () -> {
                                start.await();
                                try {
                                    tasks.transition(
                                            new Transition(
                                                    child.id(),
                                                    child.revision(),
                                                    Action.START,
                                                    null,
                                                    key()),
                                            WORKER);
                                    return true;
                                } catch (
                                        com.richuang.os.framework.common.exception.ServiceException
                                                denied) {
                                    return false;
                                }
                            });
            start.countDown();
            assertThat(
                            List.of(
                                    deletion.get(20, TimeUnit.SECONDS),
                                    execution.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            Map<String, Object> persisted =
                    jdbc.queryForMap(
                            "select status,deleted,actual_start from public.nocode_task_instance"
                                    + " where id=?",
                            child.id());
            if (((Number) persisted.get("deleted")).intValue() == 1) {
                assertThat(persisted.get("status")).isEqualTo("PENDING");
                assertThat(persisted.get("actual_start")).isNull();
            } else {
                assertThat(persisted.get("status")).isEqualTo("RUNNING");
                assertThat(persisted.get("actual_start")).isNotNull();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    private void assertDenied(Row row, long actor, String reason) {
        Row latest = tasks.detail(row.id(), actor).task();
        assertThat(latest.canDelete()).isFalse();
        assertThat(latest.deleteBlockedReason()).contains(reason);
        Integer oldRevision =
                jdbc.queryForObject(
                        "select lock_version from public.nocode_task_instance where id=?",
                        Integer.class,
                        row.id());
        assertThatThrownBy(
                        () ->
                                tasks.deleteSubtask(
                                        new DeleteSubtask(row.id(), latest.revision(), key()),
                                        actor))
                .hasMessageContaining(reason);
        assertThat(
                        jdbc.queryForObject(
                                "select deleted from public.nocode_task_instance where id=?",
                                Integer.class,
                                row.id()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select lock_version from public.nocode_task_instance where id=?",
                                Integer.class,
                                row.id()))
                .isEqualTo(oldRevision);
    }

    private Detail root() {
        Detail root =
                tasks.create(
                        new Create(
                                node("root", "总任务", WORKER),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        BOSS);
        roots.add(root.task().id());
        return root;
    }

    private Row split(String parent) {
        return tasks.split(
                        new Create(
                                node("new", "本人细项", WORKER),
                                parent,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        WORKER)
                .task();
    }

    private NodeInput node(String id, String title, long assignee) {
        return new NodeInput(
                id,
                null,
                marker + title,
                null,
                assignee,
                Urgency.NORMAL,
                Priority.MEDIUM,
                new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                List.of(),
                null,
                null,
                null,
                AssignmentMode.ASSIGNED,
                List.of());
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
