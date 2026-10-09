package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.module.msg.api.IMsgSendService;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskCenterService;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.*;

/** 自动排期的真实服务和持久化回归；仅清理随机标识登记的独立任务夹具。 */
class TaskAutoScheduleIntegrationTest {
    private static final long OWNER = 10001L;
    private static final LocalDateTime START = LocalDateTime.of(2030, 10, 4, 9, 15);
    private final String marker = "auto_schedule_" + UUID.randomUUID() + "_";
    private final Set<String> roots = new LinkedHashSet<>();
    private TaskCenterService tasks;
    private IMsgSendService messages;

    @BeforeAll
    static void open() throws Exception {
        try {
            connect();
        } catch (FlywayValidateException pendingMigrations) {
            // 本功能复用已有排期字段，不执行其他功能的待应用迁移。
            Flyway.configure()
                    .configuration(databaseTool.flyway().getConfiguration())
                    .ignoreMigrationPatterns("*:pending", "*:future")
                    .load()
                    .validate();
        }
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
        Mockito.when(servicesContext.getBean(AdminUserApi.class).getUser(Mockito.anyLong()))
                .thenAnswer(
                        invocation -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(invocation.getArgument(0));
                            user.setNickname("自动排期验证" + user.getId());
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
            jdbc.update("delete from public.nocode_task_event where root_id=?", root);
            jdbc.update("delete from public.nocode_task_instance where root_id=?", root);
        }
    }

    @Test
    void automaticDependenciesUseNaturalDaysAndRollUpNestedParents() {
        NodeInput root = node("root", null, "总任务", auto(10, 30));
        NodeInput preflight = node("preflight", "root", "开工检查", auto(0, 1));
        NodeInput stage = node("stage", "root", "施工阶段", auto(20, 40), "preflight");
        NodeInput first = node("first", "stage", "准备", auto(0, 2));
        NodeInput second = node("second", "stage", "施工", auto(0, 3), "first");
        NodeInput parallel = node("parallel", "stage", "并行校验", auto(1, 1));
        NodeInput last = node("last", "root", "验收", auto(1, 1), "stage", "parallel");
        Detail created =
                create(root, List.of(last, second, parallel, stage, first, preflight), START);

        assertDates(row(created, "开工检查"), START, START.plusDays(1));
        assertDates(row(created, "准备"), START.plusDays(1), START.plusDays(3));
        assertDates(row(created, "施工"), START.plusDays(3), START.plusDays(6));
        assertDates(row(created, "并行校验"), START.plusDays(2), START.plusDays(3));
        assertDates(row(created, "施工阶段"), START.plusDays(1), START.plusDays(6));
        assertDates(row(created, "验收"), START.plusDays(7), START.plusDays(8));
        assertDates(created.task(), START, START.plusDays(8));
        assertThat(created.task().scheduleSummary().source()).isEqualTo(ScheduleSource.AUTO);
        assertThat(created.task().scheduleSummary().partial()).isFalse();
        assertThat(created.nodes())
                .allSatisfy(
                        n -> {
                            assertThat(n.status()).isEqualTo(State.PENDING.name());
                            assertThat(n.actualStart()).isNull();
                            assertThat(n.actualEnd()).isNull();
                        });
        Detail reread = tasks.detail(created.task().id(), OWNER);
        assertThat(dates(reread)).isEqualTo(dates(created));
    }

    @Test
    void launchPreviewReadsOnlySubmittedGraphAndMatchesCreationWithoutWriting() {
        Detail existing = create(node("existing", null, "已有任务", auto(0, 2)), List.of(), START);
        Map<String, Object> before = persisted(existing.task().id());
        Integer beforeCount = fixtureCount();
        NodeInput root = node(existing.task().id(), null, "预览总任务", auto(0, 0));
        NodeInput child = node("draft-child", root.id(), "预览子任务", auto(1, 2));
        Mockito.clearInvocations(messages);

        // 客户端 ID 即使等于真实任务，预览仍只计算此次输入，不读取该实例或套用其名称。
        SchedulePreview preview =
                tasks.schedulePreview(new SchedulePreviewQuery(List.of(root, child), START), OWNER);
        assertThat(preview.nodes())
                .extracting(SchedulePreviewNode::title)
                .containsExactlyInAnyOrder(marker + "预览总任务", marker + "预览子任务");
        assertThat(preview.nodes())
                .allSatisfy(
                        n -> {
                            assertThat(n.expectedStart()).isEqualTo(START.plusDays(1));
                            assertThat(n.expectedEnd()).isEqualTo(START.plusDays(3));
                            assertThat(n.partial()).isFalse();
                        });
        assertThat(preview.warnings()).isEmpty();
        assertThat(fixtureCount()).isEqualTo(beforeCount);
        assertThat(persisted(existing.task().id())).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from public.nocode_task_template where name like"
                                        + " ?",
                                Integer.class,
                                marker + "%"))
                .isZero();
        Mockito.verify(messages, Mockito.never()).send(Mockito.any());

        Detail created = create(root, List.of(child), START);
        for (SchedulePreviewNode expected : preview.nodes()) {
            Row actual =
                    created.nodes().stream()
                            .filter(n -> n.title().equals(expected.title()))
                            .findFirst()
                            .orElseThrow();
            assertDates(actual, expected.expectedStart(), expected.expectedEnd());
        }
    }

    @Test
    void incompleteParentDoesNotAdvanceSuccessorAndFixedConflictsRemainWarnings() {
        NodeInput root = node("root", null, "总任务", auto(0, 0));
        NodeInput stage =
                node("stage", "root", "部分排期阶段", new Schedule(TimeMode.UNSCHEDULED, null, 0, 0));
        NodeInput known = node("known", "stage", "已排期工作", auto(0, 2));
        NodeInput unknown =
                node("unknown", "stage", "未排期工作", new Schedule(TimeMode.UNSCHEDULED, null, 0, 0));
        NodeInput waiting = node("waiting", "root", "等待完整阶段", auto(0, 1), "stage");
        NodeInput fixed =
                node(
                        "fixed",
                        "root",
                        "固定日期冲突",
                        new Schedule(TimeMode.FIXED, START, 0, 0, START.plusDays(1)),
                        "known");
        NodeInput endOnly =
                node(
                        "end-only",
                        "root",
                        "仅截止日期",
                        new Schedule(TimeMode.FIXED, null, 0, 0, START.plusDays(4)));
        NodeInput afterEnd = node("after-end", "root", "截止后继", auto(0, 1), "end-only");
        List<NodeInput> graph =
                List.of(root, stage, known, unknown, waiting, fixed, endOnly, afterEnd);
        SchedulePreview preview =
                tasks.schedulePreview(new SchedulePreviewQuery(graph, START), OWNER);
        assertThat(preview.warnings()).isNotEmpty();
        SchedulePreviewNode fixedPreview = previewNode(preview, "fixed");
        assertThat(fixedPreview.expectedStart()).isEqualTo(START);
        assertThat(fixedPreview.expectedEnd()).isEqualTo(START.plusDays(1));
        assertThat(fixedPreview.warnings()).isNotEmpty();
        assertThat(previewNode(preview, "stage").partial()).isTrue();
        assertThat(previewNode(preview, "stage").expectedEnd()).isEqualTo(START.plusDays(2));
        assertThat(previewNode(preview, "waiting").expectedStart()).isNull();
        assertThat(previewNode(preview, "waiting").expectedEnd()).isNull();
        assertThat(previewNode(preview, "waiting").warnings()).isNotEmpty();
        assertThat(previewNode(preview, "after-end").expectedStart()).isEqualTo(START.plusDays(4));
        assertThat(previewNode(preview, "after-end").expectedEnd()).isEqualTo(START.plusDays(5));

        Detail saved = create(root, graph.subList(1, graph.size()), START);
        assertDates(row(saved, "固定日期冲突"), START, START.plusDays(1));
        assertDates(row(saved, "等待完整阶段"), null, null);
        assertDates(row(saved, "截止后继"), START.plusDays(4), START.plusDays(5));
        assertThat(saved.task().scheduleSummary().partial()).isTrue();
        String stageId = row(saved, "部分排期阶段").id();
        assertThat(
                        jdbc.queryForObject(
                                "select expected_start is null and expected_end is null"
                                        + " from public.nocode_task_instance where id=?",
                                Boolean.class,
                                stageId))
                .isTrue();
    }

    @Test
    void adjustmentPreviewAndSavedDatesMatchWithoutPreviewWrites() {
        Detail original =
                create(
                        node("root", null, "总任务", auto(0, 0)),
                        List.of(
                                node("a", "root", "准备", auto(0, 2)),
                                node("b", "root", "施工", auto(1, 2), "a")),
                        START);
        String root = original.task().id();
        Map<String, Object> before = persisted(root);
        Adjust command =
                new Adjust(
                        root,
                        original.task().instanceRevision(),
                        inputs(root),
                        "调整整体计划起点",
                        START.plusDays(5));
        AdjustmentPreview preview = tasks.preview(command, OWNER);

        assertThat(preview.schedule()).isNotNull();
        assertThat(preview.schedule().nodes()).hasSize(3);
        assertThat(preview.affectedIds())
                .containsAll(original.nodes().stream().map(Row::id).toList());
        assertThat(persisted(root)).isEqualTo(before);
        Detail saved = tasks.adjust(command, OWNER);
        for (SchedulePreviewNode expected : preview.schedule().nodes()) {
            Row actual =
                    saved.nodes().stream()
                            .filter(n -> n.id().equals(expected.id()))
                            .findFirst()
                            .orElseThrow();
            assertDates(actual, expected.expectedStart(), expected.expectedEnd());
        }
        assertDates(row(saved, "准备"), START.plusDays(5), START.plusDays(7));
        assertDates(row(saved, "施工"), START.plusDays(8), START.plusDays(10));
        assertDates(saved.task(), START.plusDays(5), START.plusDays(10));
    }

    @Test
    void splittingUpdatesPendingParentPersistedRollupButKeepsStartedParentDates() {
        for (boolean started : List.of(false, true)) {
            Detail original =
                    create(
                            node("root", null, started ? "已开始总任务" : "未开始总任务", auto(0, 3)),
                            List.of(),
                            START);
            String rootId = original.task().id();
            if (started) transition(rootId, Action.START);

            tasks.split(
                    new Create(
                            node("child", null, "新增工作", auto(1, 5)),
                            rootId,
                            null,
                            null,
                            null,
                            null,
                            null,
                            key()),
                    OWNER);

            Detail saved = tasks.detail(rootId, OWNER);
            LocalDateTime expectedStart = started ? START : START.plusDays(1);
            LocalDateTime expectedEnd = START.plusDays(started ? 3 : 6);
            assertDates(saved.task(), expectedStart, expectedEnd);
            assertDates(row(saved, "新增工作"), START.plusDays(1), START.plusDays(6));
            assertThat(
                            jdbc.queryForObject(
                                            "select expected_start from public.nocode_task_instance"
                                                    + " where id=?",
                                            java.sql.Timestamp.class,
                                            rootId)
                                    .toLocalDateTime())
                    .isEqualTo(expectedStart);
            assertThat(
                            jdbc.queryForObject(
                                            "select expected_end from public.nocode_task_instance"
                                                    + " where id=?",
                                            java.sql.Timestamp.class,
                                            rootId)
                                    .toLocalDateTime())
                    .isEqualTo(expectedEnd);
        }
    }

    @Test
    void completedPredecessorReschedulesOnlyPendingRelativeSuccessors() {
        Detail original =
                create(
                        node("root", null, "总任务", auto(0, 0)),
                        List.of(
                                node("first", "root", "前置工作", auto(0, 2)),
                                node("auto", "root", "自动后继", auto(1, 3), "first"),
                                node(
                                        "relative",
                                        "root",
                                        "历史相对后继",
                                        new Schedule(TimeMode.PREDECESSOR, null, 2, 1),
                                        "first"),
                                node(
                                        "fixed",
                                        "root",
                                        "固定后继",
                                        new Schedule(
                                                TimeMode.FIXED,
                                                START.plusDays(7),
                                                0,
                                                0,
                                                START.plusDays(8)),
                                        "first"),
                                node(
                                        "unknown",
                                        "root",
                                        "暂不排期后继",
                                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                                        "first"),
                                node("running", "root", "已开始并行工作", auto(0, 4))),
                        START);
        transition(original.task().id(), Action.START);
        transition(row(original, "前置工作").id(), Action.START);
        transition(row(original, "已开始并行工作").id(), Action.START);
        Detail before = tasks.detail(original.task().id(), OWNER);
        transition(row(original, "前置工作").id(), Action.COMPLETE);
        Detail after = tasks.detail(original.task().id(), OWNER);
        LocalDateTime completedAt = row(after, "前置工作").actualEnd();

        assertThat(completedAt).isNotNull();
        assertDates(row(after, "自动后继"), completedAt.plusDays(1), completedAt.plusDays(4));
        assertDates(row(after, "历史相对后继"), completedAt.plusDays(2), completedAt.plusDays(3));
        for (String title : List.of("固定后继", "暂不排期后继", "已开始并行工作", "前置工作", "总任务")) {
            Row frozen = row(before, title);
            assertDates(row(after, title), frozen.expectedStart(), frozen.expectedEnd());
        }
        assertThat(row(after, "已开始并行工作").actualStart())
                .isEqualTo(row(before, "已开始并行工作").actualStart());
        assertThat(row(after, "已开始并行工作").status()).isEqualTo(State.RUNNING.name());
        assertThat(row(after, "自动后继").status()).isEqualTo(State.PENDING.name());
    }

    private Detail create(NodeInput root, List<NodeInput> nodes, LocalDateTime start) {
        // 发起协议将总任务单独传入；直属子任务省略 parentId，嵌套层级仍引用客户端节点 ID。
        List<NodeInput> children =
                nodes.stream()
                        .map(
                                node ->
                                        Objects.equals(root.id(), node.parentId())
                                                ? new NodeInput(
                                                        node.id(),
                                                        null,
                                                        node.title(),
                                                        node.description(),
                                                        node.assigneeId(),
                                                        node.urgency(),
                                                        node.priority(),
                                                        node.schedule(),
                                                        node.predecessorIds(),
                                                        node.binding(),
                                                        node.sharing(),
                                                        node.entries(),
                                                        node.assignmentMode(),
                                                        node.candidateUserIds(),
                                                        node.dataPolicy(),
                                                        node.acceptorId(),
                                                        node.effectiveWorkMinutes())
                                                : node)
                        .toList();
        Detail result =
                tasks.create(
                        new Create(
                                root,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key(),
                                children,
                                Kind.ORDINARY,
                                null,
                                start),
                        OWNER);
        roots.add(result.task().id());
        return result;
    }

    private NodeInput node(
            String id, String parent, String title, Schedule schedule, String... predecessors) {
        return new NodeInput(
                id,
                parent,
                marker + title,
                null,
                OWNER,
                Urgency.NORMAL,
                Priority.MEDIUM,
                schedule,
                List.of(predecessors),
                null,
                null,
                null,
                AssignmentMode.ASSIGNED,
                List.of(),
                null);
    }

    private Schedule auto(int offset, int duration) {
        return new Schedule(TimeMode.AUTO, null, offset, duration);
    }

    private SchedulePreviewNode previewNode(SchedulePreview preview, String id) {
        return preview.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow();
    }

    private Integer fixtureCount() {
        return jdbc.queryForObject(
                "select count(*) from public.nocode_task_instance where title like ?",
                Integer.class,
                marker + "%");
    }

    private Row row(Detail detail, String title) {
        return detail.nodes().stream()
                .filter(n -> n.title().equals(marker + title))
                .findFirst()
                .orElseThrow();
    }

    private void assertDates(Row row, LocalDateTime start, LocalDateTime end) {
        assertThat(row.expectedStart()).as(row.title() + "预计开始").isEqualTo(start);
        assertThat(row.expectedEnd()).as(row.title() + "预计完成").isEqualTo(end);
    }

    private Map<String, List<LocalDateTime>> dates(Detail detail) {
        Map<String, List<LocalDateTime>> result = new LinkedHashMap<>();
        detail.nodes()
                .forEach(
                        row ->
                                result.put(
                                        row.id(),
                                        Arrays.asList(row.expectedStart(), row.expectedEnd())));
        return result;
    }

    private List<NodeInput> inputs(String root) {
        return jdbc
                .queryForList(
                        "select config_json from public.nocode_task_instance where root_id=? order"
                                + " by (config_json::jsonb->>'displayOrder')::integer",
                        String.class,
                        root)
                .stream()
                .map(
                        value -> {
                            try {
                                return mapper.readValue(value, NodeInput.class);
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                        })
                .toList();
    }

    private Map<String, Object> persisted(String root) {
        return Map.of(
                "tasks",
                jdbc.queryForList(
                        "select id, expected_start, expected_end, planned_start, config_json,"
                            + " lock_version from public.nocode_task_instance where root_id=? order"
                            + " by id",
                        root),
                "events",
                jdbc.queryForList(
                        "select id, event_type from public.nocode_task_event where root_id=? order"
                                + " by id",
                        root));
    }

    private void transition(String id, Action action) {
        Row current = tasks.detail(id, OWNER).task();
        tasks.transition(new Transition(id, current.revision(), action, "自动排期专项验证", key()), OWNER);
    }

    private String key() {
        return marker + UUID.randomUUID();
    }
}
