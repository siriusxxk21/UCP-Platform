package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.api.TaskWorkEntries.*;
import com.lingan.ucp.nocode.api.TaskWorkEntries.DataMode;
import com.lingan.ucp.nocode.api.TaskWorkEntries.Query;
import com.lingan.ucp.nocode.runtime.service.taskcenter.*;

import org.junit.jupiter.api.*;

import java.util.*;

/** 当前开发数据库的自有任务夹具；验证真实写入历史、无变化、删除和跨任务权限。 */
class TaskHandlingHistoryIntegrationTest {
    private static final long ACTOR = 10001L;
    private WorkDraftIntegrationTest business;
    private TaskCenterService tasks;
    private TaskWorkEntryService entries;
    private TaskHandlingHistoryService history;
    private final List<String> roots = new ArrayList<>();

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void stop() {
        close();
    }

    @BeforeEach
    void setup() {
        business = new WorkDraftIntegrationTest();
        business.setup();
        tasks = servicesContext.getBean(TaskCenterService.class);
        entries = servicesContext.getBean(TaskWorkEntryService.class);
        history = servicesContext.getBean(TaskHandlingHistoryService.class);
        when(servicesContext.getBean(AdminUserApi.class).getUser(anyLong()))
                .thenAnswer(
                        invocation -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(invocation.getArgument(0));
                            user.setNickname("办理历史测试成员");
                            user.setStatus(0);
                            return user;
                        });
    }

    @AfterEach
    void cleanup() {
        for (String root : roots) {
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_record WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_binding WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update("DELETE FROM public.nocode_task_event WHERE root_id=?", root);
            jdbc.update("DELETE FROM public.nocode_task_instance WHERE root_id=?", root);
        }
        business.cleanup();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    private Binding binding() {
        return new Binding(business.resource.applicationId(), business.resource.resourceId(), null);
    }

    private String root() {
        return root(DataAccessMode.GROUP);
    }

    private String root(DataAccessMode mode) {
        Config feedback =
                new Config(
                        "feedback",
                        "过程反馈",
                        binding(),
                        DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false);
        NodeInput node =
                new NodeInput(
                        null,
                        null,
                        "采购办理历史",
                        null,
                        ACTOR,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        binding(),
                        null,
                        List.of(feedback),
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        new DataPolicy(1, mode, DataAccessMode.GROUP));
        String id =
                tasks.create(new Create(node, null, null, null, null, null, null, key()), ACTOR)
                        .task()
                        .id();
        roots.add(id);
        start(id);
        return id;
    }

    private String child(String parent) {
        NodeInput node =
                new NodeInput(
                        null,
                        null,
                        "采购子任务",
                        null,
                        ACTOR,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        null,
                        null,
                        null,
                        AssignmentMode.ASSIGNED,
                        List.of());
        String id =
                tasks.create(new Create(node, parent, null, null, null, null, null, key()), ACTOR)
                        .task()
                        .id();
        start(id);
        return id;
    }

    private void start(String id) {
        tasks.transition(
                new Transition(
                        id, tasks.detail(id, ACTOR).task().revision(), Action.START, null, key()),
                ACTOR);
    }

    private ApplicationRecords.Save input(String id, String revision, String value) {
        return new ApplicationRecords.Save(
                business.resource.applicationId(),
                business.object.objectId(),
                id,
                revision,
                Map.of(business.nameField, value),
                Map.of(),
                Map.of(),
                null,
                business.resource.resourceId(),
                key(),
                null);
    }

    private Saved save(String task, String entry, String id, String revision, String value) {
        return entries.save(new Save(task, entry, null, input(id, revision, value)), ACTOR);
    }

    private List<HandlingRow> page(String task, boolean current) {
        return history.page(new HistoryQuery(task, null, current, null, null, null, 1, 100), ACTOR)
                .getList();
    }

    private HandlingDetail detail(String task, String id) {
        return history.detail(new HistoryRef(task, null, id), ACTOR);
    }

    @Test
    void recordsActualBeforeDespiteIntermediateApplicationWriteAndSurvivesDelete() {
        String root = root();
        String child = child(root);
        Saved created = save(root, TaskDataPolicies.BUSINESS, null, null, "数量3");
        ApplicationRecords.Row first = created.handling().result().record();
        ApplicationRecords.Row external =
                business.records.save(input(first.id(), first.revision(), "数量7"), ACTOR).record();
        Saved updated =
                save(child, TaskDataPolicies.BUSINESS, external.id(), external.revision(), "数量9");
        HandlingDetail change = detail(root, updated.contributionId());
        assertThat(change.row().operation()).isEqualTo(Operation.UPDATED);
        assertThat(change.row().taskId()).isEqualTo(child);
        assertThat(change.before()).containsEntry(business.nameField, "数量7");
        assertThat(change.after()).containsEntry(business.nameField, "数量9");
        assertThat(change.beforeKnown()).isTrue();
        assertThat(change.afterKnown()).isTrue();
        ApplicationRecords.Row current = updated.handling().result().record();
        entries.delete(
                new Delete(
                        child, TaskDataPolicies.BUSINESS, current.id(), current.revision(), key()),
                ACTOR);
        assertThat(
                        entries.page(
                                        new Query(
                                                root,
                                                TaskDataPolicies.BUSINESS,
                                                false,
                                                false,
                                                1,
                                                100,
                                                null),
                                        ACTOR)
                                .getList())
                .isEmpty();
        assertThat(page(root, false)).hasSize(3);
        HandlingRow deleted =
                page(root, false).stream()
                        .filter(row -> row.operation() == Operation.DELETED)
                        .findFirst()
                        .orElseThrow();
        assertThat(detail(root, deleted.id()).before()).containsEntry(business.nameField, "数量9");
        assertThat(detail(root, deleted.id()).after()).isNull();
        assertThat(detail(root, created.contributionId()).after())
                .containsEntry(business.nameField, "数量3");
        assertThat(page(root, true)).hasSize(1);
        assertThat(page(child, true)).hasSize(2);
        String other = root();
        assertThatThrownBy(() -> detail(other, updated.contributionId()))
                .hasMessageContaining("无权");
    }

    @Test
    void unchangedAndFailedSavesDoNotCreateSuccessfulChangesOrSources() {
        String root = root();
        ApplicationRecords.Row first =
                save(root, TaskDataPolicies.BUSINESS, null, null, "保持原值")
                        .handling()
                        .result()
                        .record();
        Saved unchanged =
                save(root, TaskDataPolicies.BUSINESS, first.id(), first.revision(), "保持原值");
        assertThat(page(root, false)).hasSize(1);
        assertThatThrownBy(() -> detail(root, unchanged.contributionId()))
                .hasMessageContaining("无权");
        assertThatThrownBy(() -> save(root, TaskDataPolicies.BUSINESS, first.id(), "stale", "失败内容"))
                .isInstanceOf(RuntimeException.class);
        assertThat(page(root, false)).hasSize(1);
        assertThat(
                        entries.page(
                                        new Query(
                                                root,
                                                TaskDataPolicies.BUSINESS,
                                                false,
                                                false,
                                                1,
                                                100,
                                                null),
                                        ACTOR)
                                .getList()
                                .getFirst()
                                .sources())
                .hasSize(1);
    }

    @Test
    void linkedSnapshotAndRepeatedFeedbackRemainSeparateFactsAndSupportPaging() {
        String root = root(DataAccessMode.ALL);
        ApplicationRecords.Row external =
                business.records.save(input(null, null, "已有采购"), ACTOR).record();
        Saved linked =
                entries.link(
                        new Link(root, TaskDataPolicies.BUSINESS, external.id(), key()), ACTOR);
        save(root, "feedback", null, null, "首次反馈");
        save(root, "feedback", null, null, "第二次反馈");
        HandlingDetail detail = detail(root, linked.contributionId());
        assertThat(detail.row().operation()).isEqualTo(Operation.LINKED);
        assertThat(detail.beforeKnown()).isFalse();
        assertThat(detail.afterKnown()).isTrue();
        assertThat(detail.after()).containsEntry(business.nameField, "已有采购");
        assertThat(page(root, false)).hasSize(3);
        assertThat(
                        history.page(
                                        new HistoryQuery(
                                                root, "feedback", false, null, null, null, 1, 1),
                                        ACTOR)
                                .getTotal())
                .isEqualTo(2);
        assertThat(
                        history.page(
                                        new HistoryQuery(
                                                root,
                                                null,
                                                false,
                                                null,
                                                Operation.LINKED,
                                                null,
                                                1,
                                                10),
                                        ACTOR)
                                .getList())
                .hasSize(1);
        assertThat(
                        history.page(
                                        new HistoryQuery(
                                                root,
                                                null,
                                                false,
                                                external.id(),
                                                null,
                                                null,
                                                1,
                                                10),
                                        ACTOR)
                                .getList())
                .hasSize(1);
        assertThat(
                        history.page(
                                        new HistoryQuery(
                                                root, null, false, null, null, "第二次", 1, 10),
                                        ACTOR)
                                .getList())
                .hasSize(1);
    }

    @Test
    void untaggedLegacyReceiptDoesNotClaimAnEarlierUpdateAsItsOwnBefore() {
        String root = root();
        ApplicationRecords.Row first =
                save(root, TaskDataPolicies.BUSINESS, null, null, "旧值")
                        .handling()
                        .result()
                        .record();
        Saved saved = save(root, TaskDataPolicies.BUSINESS, first.id(), first.revision(), "封存后值");
        jdbc.update(
                "UPDATE public.nocode_record_history SET"
                        + " source_json=source_json-'taskContributionId' WHERE"
                        + " source_json->>'taskContributionId'=?",
                saved.contributionId());
        HandlingDetail detail = detail(root, saved.contributionId());
        assertThat(detail.row().historyKnown()).isFalse();
        assertThat(detail.beforeKnown()).isFalse();
        assertThat(detail.before()).isNull();
        assertThat(detail.after()).containsEntry(business.nameField, "封存后值");
    }

    @Test
    void revokedFieldVisibilityCannotLeakThroughHistoryLabelsSearchOrDetail() {
        String root = root();
        Saved created = save(root, TaskDataPolicies.BUSINESS, null, null, "仅限授权查看的采购名称");
        // 精确修改本测试自己的入口夹具，模拟旧任务字段读取上限收窄；不改任何真实应用授权。
        jdbc.update(
                "UPDATE public.nocode_task_entry_binding SET config_json=(config_json::jsonb ||"
                    + " '{\"readableFieldIds\":[]}'::jsonb)::text WHERE task_id=? AND entry_key=?",
                root,
                TaskDataPolicies.BUSINESS);
        assertThat(
                        history.page(
                                        new HistoryQuery(
                                                root, null, false, null, null, "采购名称", 1, 10),
                                        ACTOR)
                                .getTotal())
                .isZero();
        assertThat(page(root, false)).isEmpty();
        assertThatThrownBy(() -> detail(root, created.contributionId())).hasMessageContaining("无权");
        assertThatThrownBy(
                        () ->
                                history.page(
                                        new HistoryQuery(
                                                root, null, false, null, null, null, 1, 10),
                                        98765L))
                .isInstanceOf(RuntimeException.class);
    }
}
