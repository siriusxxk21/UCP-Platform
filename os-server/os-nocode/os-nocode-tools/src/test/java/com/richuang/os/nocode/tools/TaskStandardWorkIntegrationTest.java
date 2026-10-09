package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.api.TaskWorkEntries.*;
import com.richuang.os.nocode.runtime.service.taskcenter.*;

import org.junit.jupiter.api.*;

import java.util.*;

/** 当前开发库的任务视图、真实保存、去重和冲减验证，仅清理本类根任务及对象夹具。 */
class TaskStandardWorkIntegrationTest {
    private static final long ACTOR = 10001L;
    private WorkDraftIntegrationTest business;
    private TaskCenterService tasks;
    private TaskWorkEntryService entries;
    private boolean retainAcceptanceFixture;
    private final List<String> roots = new ArrayList<>();
    private final List<String> templates = new ArrayList<>();

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
        business = new WorkDraftIntegrationTest();
        business.setup();
        tasks = servicesContext.getBean(TaskCenterService.class);
        entries = servicesContext.getBean(TaskWorkEntryService.class);
        when(servicesContext.getBean(AdminUserApi.class).getUser(anyLong()))
                .thenAnswer(
                        invocation -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(invocation.getArgument(0));
                            user.setStatus(0);
                            user.setNickname("标准工时验证");
                            return user;
                        });
        ApplicationCenter.Detail app = business.applications.get(business.resource.applicationId());
        List<ApplicationCenter.Resource> resources = new ArrayList<>(app.draft().resources());
        resources.add(
                new ApplicationCenter.Resource(
                        "task-view",
                        "VIEW",
                        "task_view",
                        "房间办理视图",
                        Map.of(
                                "objectId",
                                business.object.objectId(),
                                "fieldIds",
                                List.of(business.nameField),
                                "equal",
                                Map.of(),
                                "formId",
                                business.resource.resourceId(),
                                "pageSize",
                                20,
                                "query",
                                Map.of(
                                        "fixed",
                                        List.of(
                                                Map.of(
                                                        "fieldId",
                                                        business.nameField,
                                                        "operator",
                                                        "neq",
                                                        "value",
                                                        "outside"))))));
        ApplicationCenter.Detail changed =
                business.applications.save(
                        new ApplicationCenter.Save(
                                app.application().id(),
                                app.application().revision(),
                                app.application().code(),
                                app.application().name(),
                                app.application().description(),
                                null,
                                new ApplicationCenter.Definition(app.draft().objects(), resources)),
                        ACTOR);
        business.applications.publish(
                new ApplicationCenter.Revision(
                        changed.application().id(), changed.application().revision(), "标准工时视图验证"),
                ACTOR);
    }

    @AfterEach
    void cleanup() {
        if (retainAcceptanceFixture) return;
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
        for (String template : templates) {
            jdbc.update(
                    "DELETE FROM public.nocode_task_template_version WHERE template_id=?",
                    template);
            jdbc.update("DELETE FROM public.nocode_task_template WHERE id=?", template);
        }
        business.cleanup();
    }

    private Config config(String key, boolean view, WorkRule rule) {
        return new Config(
                key,
                "房间办理",
                new Binding(
                        business.resource.applicationId(),
                        business.resource.resourceId(),
                        null,
                        view ? "task-view" : null),
                TaskWorkEntries.DataMode.ROOT_SHARED,
                null,
                null,
                null,
                null,
                false,
                false,
                rule);
    }

    private WorkRule once() {
        return new WorkRule(WorkRuleMode.RECORD_ONCE, 15, null, null, null);
    }

    private NodeInput launchRoot(int base, Integer adjustment) {
        return new NodeInput(
                "work-adjust-root",
                null,
                "本次工时调整验证",
                null,
                ACTOR,
                null,
                null,
                null,
                List.of(),
                null,
                null,
                List.of(
                        config(
                                "room",
                                true,
                                new WorkRule(
                                        WorkRuleMode.RECORD_ONCE,
                                        base,
                                        null,
                                        null,
                                        null,
                                        adjustment)),
                        config("untouched", true, once())),
                AssignmentMode.ASSIGNED,
                List.of(),
                new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP),
                null);
    }

    private Create launchCommand(String template, int base, Integer adjustment) {
        return new Create(
                launchRoot(base, adjustment),
                null,
                template,
                1,
                null,
                null,
                null,
                UUID.randomUUID().toString(),
                List.of());
    }

    @Test
    void launchAdjustsOnlyChosenEntryAndDoesNotRewriteTemplateOrEarlierInstance() {
        Template template =
                tasks.saveTemplate(
                        new SaveTemplate(
                                null,
                                null,
                                "工时调整专项-" + UUID.randomUUID(),
                                null,
                                List.of(),
                                Kind.ORDINARY,
                                launchRoot(15, null)),
                        ACTOR);
        templates.add(template.id());
        tasks.publish(new PublishTemplate(template.id(), template.revision()), ACTOR);
        for (int adjustment : List.of(5, -5, 0)) {
            Detail created = tasks.create(launchCommand(template.id(), 15, adjustment), ACTOR);
            String id = created.task().id();
            roots.add(id);
            tasks.transition(
                    new Transition(
                            id,
                            created.task().revision(),
                            Action.START,
                            null,
                            UUID.randomUUID().toString()),
                    ACTOR);
            ApplicationRecords.Row first =
                    save(id, null, null, "first", UUID.randomUUID().toString())
                            .handling()
                            .result()
                            .record();
            save(id, first.id(), first.revision(), "updated", UUID.randomUUID().toString());
            assertThat(summary(id).myMinutes())
                    .isEqualByComparingTo(Integer.toString(15 + adjustment));
            assertThat(entries.effectiveConfigs(id).get(1).workRule().effectiveMinutes())
                    .isEqualTo(15);
        }
        assertThat(summary(roots.getFirst()).myMinutes()).isEqualByComparingTo("20");
        WorkRule baseline =
                tasks.version(template.id(), 1, ACTOR).task().entries().getFirst().workRule();
        assertThat(baseline.minutes()).isEqualTo(15);
        assertThat(baseline.adjustmentMinutes()).isNull();
        assertThatThrownBy(() -> tasks.create(launchCommand(template.id(), 16, 0), ACTOR))
                .hasMessageContaining("基准工时");
        assertThatThrownBy(() -> tasks.create(launchCommand(template.id(), 15, -16), ACTOR))
                .hasMessageContaining("调整后的标准工时");
        assertThatThrownBy(
                        () ->
                                tasks.saveTemplate(
                                        new SaveTemplate(
                                                null,
                                                null,
                                                "不能回写调整",
                                                null,
                                                List.of(),
                                                Kind.ORDINARY,
                                                launchRoot(15, 5)),
                                        ACTOR))
                .hasMessageContaining("只用于本次任务发起");
    }

    private Detail create(Config config) {
        return create(true, List.of(config));
    }

    private Detail create(boolean unified, List<Config> configs) {
        NodeInput root =
                new NodeInput(
                        null,
                        null,
                        "标准工时真实验证",
                        null,
                        ACTOR,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        configs,
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        unified
                                ? new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP)
                                : null,
                        null);
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
                                UUID.randomUUID().toString()),
                        ACTOR);
        roots.add(result.task().id());
        tasks.transition(
                new Transition(
                        result.task().id(),
                        result.task().revision(),
                        Action.START,
                        null,
                        UUID.randomUUID().toString()),
                ACTOR);
        return tasks.detail(result.task().id(), ACTOR);
    }

    private Saved save(String task, String id, String revision, String name, String key) {
        return entries.save(
                new Save(
                        task,
                        "room",
                        null,
                        new ApplicationRecords.Save(
                                business.resource.applicationId(),
                                business.object.objectId(),
                                id,
                                revision,
                                Map.of(business.nameField, name),
                                Map.of(),
                                Map.of(),
                                null,
                                business.resource.resourceId(),
                                key,
                                null)),
                ACTOR);
    }

    private WorkSummary summary(String task) {
        return entries.entries(task, ACTOR).getFirst().workSummary();
    }

    private TaskWorkTimes.Change change(String id, int minutes, int quantity) {
        TaskWorkTimes.Context context = tasks.workTimeContext(id, ACTOR);
        return new TaskWorkTimes.Change(
                id,
                context.expectedRevision(),
                UUID.randomUUID().toString(),
                "当前实例工时专项调整",
                TaskWorkTimes.TotalMode.AUTO,
                null,
                List.of(
                        new TaskWorkTimes.EntryChange(
                                id, "room", minutes, java.math.BigDecimal.valueOf(quantity))));
    }

    @Test
    void runningAdjustmentKeepsHistoryUsesNewRateAndPreservesStateAndAuthorization() {
        Detail task = create(config("room", true, once()));
        String id = task.task().id();
        ApplicationRecords.Row first =
                save(id, null, null, "旧标准", UUID.randomUUID().toString())
                        .handling()
                        .result()
                        .record();
        TaskWorkTimes.Change change = change(id, 25, 4);
        TaskWorkTimes.Context adjusted = tasks.adjustWorkTime(change, ACTOR);
        assertThat(adjusted.effectiveWorkMinutes()).isEqualTo(100);
        assertThat(adjusted.workTotalMode()).isEqualTo(TaskWorkTimes.TotalMode.AUTO);
        assertThat(tasks.detail(id, ACTOR).task().status()).isEqualTo(State.RUNNING.name());
        assertThat(summary(id).myMinutes()).isEqualByComparingTo("15");
        assertThat(tasks.adjustWorkTime(change, ACTOR).expectedRevision())
                .isEqualTo(adjusted.expectedRevision());
        save(id, first.id(), first.revision(), "旧记录继续编辑", UUID.randomUUID().toString());
        save(id, null, null, "新标准", UUID.randomUUID().toString());
        assertThat(summary(id).myMinutes()).isEqualByComparingTo("40");
        assertThat(entries.effectiveConfigs(id).getFirst().workRule().minutes()).isEqualTo(15);
        assertThat(entries.effectiveConfigs(id).getFirst().workRule().adjustmentMinutes())
                .isEqualTo(10);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_task_event WHERE root_id=? AND"
                                        + " note LIKE '调整工时%'",
                                Integer.class, id))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                tasks.adjustWorkTime(
                                        new TaskWorkTimes.Change(
                                                id,
                                                change.expectedRevision(),
                                                UUID.randomUUID().toString(),
                                                "过期修订",
                                                change.workTotalMode(),
                                                null,
                                                change.entries()),
                                        ACTOR))
                .hasMessageContaining("其他人修改");
        assertThatThrownBy(
                        () ->
                                tasks.adjustWorkTime(
                                        new TaskWorkTimes.Change(
                                                id,
                                                adjusted.expectedRevision(),
                                                change.requestKey(),
                                                "改过的重试",
                                                change.workTotalMode(),
                                                null,
                                                change.entries()),
                                        ACTOR))
                .hasMessageContaining("同一请求标识");
        // 仅显式人工验收时保留独立夹具；正常回归仍逐例清理。
        if (Boolean.getBoolean("nocode.task.worktime.keepFixture")) {
            String title = "工时调整验收-" + java.time.LocalDate.now() + "-" + id.substring(0, 8);
            jdbc.update(
                    "UPDATE public.nocode_task_instance SET title=?,"
                        + " config_json=jsonb_set(config_json::jsonb,'{title}',to_jsonb(CAST(? AS"
                        + " text)))::text WHERE id=?",
                    title,
                    title,
                    id);
            retainAcceptanceFixture = true;
            System.out.println(
                    "WORKTIME_ACCEPTANCE_FIXTURE root="
                            + id
                            + " title="
                            + title
                            + " app="
                            + business.resource.applicationId()
                            + " object="
                            + business.object.objectId()
                            + " prefix="
                            + business.fixture.prefix
                            + " totalMinutes=100 creditedMinutes=40 currentRate=25"
                            + " plannedQuantity=4");
        }
    }

    @Test
    void workTimeChangesValidateBeforeWritingAndRejectNonManagerAndEndedTask() {
        Detail task = create(config("room", true, once()));
        String id = task.task().id();
        TaskWorkTimes.Change request = change(id, 25, 4);
        jdbc.update("UPDATE public.nocode_task_instance SET assignee_id=? WHERE id=?", 10002L, id);
        assertThat(tasks.workTimeContext(id, 10002L).canAdjust()).isFalse();
        assertThat(tasks.workTimeContext(id, 10002L).entries()).isEmpty();
        assertThatThrownBy(() -> tasks.adjustWorkTime(request, 10002L))
                .hasMessageContaining("只有任务发起人");
        assertThatThrownBy(
                        () ->
                                tasks.adjustWorkTime(
                                        new TaskWorkTimes.Change(
                                                id,
                                                request.expectedRevision(),
                                                request.requestKey(),
                                                "",
                                                request.workTotalMode(),
                                                null,
                                                request.entries()),
                                        ACTOR))
                .hasMessageContaining("调整原因");
        assertThatThrownBy(
                        () ->
                                tasks.adjustWorkTime(
                                        new TaskWorkTimes.Change(
                                                id,
                                                request.expectedRevision(),
                                                request.requestKey(),
                                                "非法预计量",
                                                request.workTotalMode(),
                                                null,
                                                List.of(
                                                        new TaskWorkTimes.EntryChange(
                                                                id,
                                                                "room",
                                                                25,
                                                                new java.math.BigDecimal("-1")))),
                                        ACTOR))
                .hasMessageContaining("预计工作量");
        assertThat(entries.effectiveConfigs(id).getFirst().workRule().effectiveMinutes())
                .isEqualTo(15);
        for (State state : List.of(State.PENDING_ACCEPTANCE, State.COMPLETED, State.CANCELLED)) {
            jdbc.update(
                    "UPDATE public.nocode_task_instance SET status=? WHERE id=?", state.name(), id);
            assertThat(tasks.workTimeContext(id, ACTOR).canAdjust()).isFalse();
            assertThatThrownBy(() -> tasks.adjustWorkTime(request, ACTOR))
                    .hasMessageContaining("不能调整工时");
        }
    }

    @Test
    void pausedTaskCanAdjustBudgetWithoutResumingAndMissingLegacySnapshotIsFrozenFirst() {
        Detail task = create(config("room", true, once()));
        String id = task.task().id();
        Saved saved = save(id, null, null, "旧进程遗留快照", UUID.randomUUID().toString());
        jdbc.update(
                "UPDATE public.nocode_task_entry_record SET work_rule_json=NULL WHERE id=?",
                saved.contributionId());
        task = tasks.detail(id, ACTOR);
        tasks.transition(
                new Transition(
                        id,
                        task.task().revision(),
                        Action.PAUSE,
                        "工时安排",
                        UUID.randomUUID().toString()),
                ACTOR);
        tasks.adjustWorkTime(change(id, 60, 2), ACTOR);
        assertThat(tasks.detail(id, ACTOR).task().status()).isEqualTo(State.PAUSED.name());
        assertThat(summary(id).myMinutes()).isEqualByComparingTo("15");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT work_rule_json::jsonb->>'minutes' FROM"
                                        + " public.nocode_task_entry_record WHERE id=?",
                                String.class,
                                saved.contributionId()))
                .isEqualTo("15");
    }

    @Test
    void createEditSameRecordCountsOnceAndDeleteReversesWithHistory() {
        Detail task = create(config("room", true, once()));
        String id = task.task().id();
        String request = UUID.randomUUID().toString();
        Saved saved = save(id, null, null, "room-a", request);
        Saved replay = save(id, null, null, "room-a", request);
        assertThat(replay.contributionId()).isEqualTo(saved.contributionId());
        ApplicationRecords.Row record = saved.handling().result().record();
        Saved edited =
                save(
                        id,
                        record.id(),
                        record.revision(),
                        "room-a-edited",
                        UUID.randomUUID().toString());
        ApplicationRecords.Row updated = edited.handling().result().record();
        ApplicationRecords.Row unchanged =
                save(
                                id,
                                updated.id(),
                                updated.revision(),
                                "room-a-edited",
                                UUID.randomUUID().toString())
                        .handling()
                        .result()
                        .record();
        assertThat(summary(id).myMinutes()).isEqualByComparingTo("15");
        assertThat(summary(id).myRecordCount()).isEqualTo(1);
        assertThat(
                        entries.delete(
                                new Delete(
                                        id,
                                        "room",
                                        updated.id(),
                                        unchanged.revision(),
                                        UUID.randomUUID().toString()),
                                ACTOR))
                .isTrue();
        assertThat(summary(id).myMinutes()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_task_entry_record WHERE"
                                        + " task_id=? AND operation='DELETED'",
                                Integer.class,
                                id))
                .isEqualTo(1);
    }

    @Test
    void viewRangeIsEnforcedForListingOpeningAndSavingAndFailureDoesNotCount() {
        Detail task = create(config("room", true, once()));
        String id = task.task().id();
        assertThatThrownBy(() -> save(id, null, null, "outside", UUID.randomUUID().toString()))
                .hasMessageContaining("权限");
        assertThat(summary(id).myMinutes()).isZero();
        Saved saved = save(id, null, null, "inside", UUID.randomUUID().toString());
        ApplicationRecords.Row record = saved.handling().result().record();
        assertThatThrownBy(
                        () ->
                                save(
                                        id,
                                        record.id(),
                                        record.revision(),
                                        "outside",
                                        UUID.randomUUID().toString()))
                .hasMessageContaining("权限");
        assertThat(
                        entries.page(
                                        new TaskWorkEntries.Query(
                                                id, "room", false, false, 1, 20, null),
                                        ACTOR)
                                .getList())
                .singleElement()
                .satisfies(
                        item ->
                                assertThat(item.record().values())
                                        .containsEntry(business.nameField, "inside"));
        assertThat(summary(id).myMinutes()).isEqualByComparingTo("15");
    }

    @Test
    void businessViewAndOldFormWorkWithoutCountingConfiguration() {
        Detail view = create(config("room", true, null));
        save(view.task().id(), null, null, "未计时正常办理", UUID.randomUUID().toString());
        assertThat(summary(view.task().id())).isNull();
        finish(view.task().id());
        Detail old = create(config("room", false, null));
        save(old.task().id(), null, null, "legacy", UUID.randomUUID().toString());
        assertThat(summary(old.task().id())).isNull();
        finish(old.task().id());
        Detail legacyZero =
                create(
                        config(
                                "room",
                                true,
                                new WorkRule(WorkRuleMode.QUANTITY, 0, null, null, null)));
        save(legacyZero.task().id(), null, null, "兼容零时长", UUID.randomUUID().toString());
        assertThat(summary(legacyZero.task().id()).myMinutes()).isZero();
        finish(legacyZero.task().id());
    }

    private void finish(String id) {
        Detail current = tasks.detail(id, ACTOR);
        tasks.transition(
                new Transition(
                        id,
                        current.task().revision(),
                        Action.COMPLETE,
                        null,
                        UUID.randomUUID().toString()),
                ACTOR);
        assertThat(tasks.detail(id, ACTOR).task().status()).isEqualTo(State.COMPLETED.name());
    }

    @Test
    void optionalWorkTimeTemplateSavesPublishesLaunchesAndKeepsRequiredBusinessGuard() {
        for (boolean configured : List.of(false, true)) {
            Config original = config("room", true, configured ? once() : null);
            Config required =
                    new Config(
                            original.key(),
                            original.name(),
                            original.binding(),
                            original.dataMode(),
                            null,
                            null,
                            null,
                            null,
                            true,
                            false,
                            original.workRule());
            NodeInput root =
                    new NodeInput(
                            "optional-root",
                            null,
                            "工时可选验证",
                            null,
                            ACTOR,
                            null,
                            null,
                            null,
                            List.of(),
                            null,
                            null,
                            List.of(required),
                            AssignmentMode.ASSIGNED,
                            List.of(),
                            new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP),
                            null,
                            null,
                            TaskWorkTimes.TotalMode.AUTO);
            Template template =
                    tasks.saveTemplate(
                            new SaveTemplate(
                                    null,
                                    null,
                                    "可选工时模板-" + UUID.randomUUID(),
                                    null,
                                    List.of(),
                                    Kind.ORDINARY,
                                    root),
                            ACTOR);
            templates.add(template.id());
            tasks.publish(new PublishTemplate(template.id(), template.revision()), ACTOR);
            NodeInput published = tasks.version(template.id(), 1, ACTOR).task();
            assertThat(published.effectiveWorkMinutes()).isNull();
            Detail created =
                    tasks.create(
                            new Create(
                                    published,
                                    null,
                                    template.id(),
                                    1,
                                    null,
                                    null,
                                    null,
                                    UUID.randomUUID().toString(),
                                    List.of()),
                            ACTOR);
            String id = created.task().id();
            roots.add(id);
            tasks.transition(
                    new Transition(
                            id,
                            created.task().revision(),
                            Action.START,
                            null,
                            UUID.randomUUID().toString()),
                    ACTOR);
            assertThatThrownBy(() -> finish(id)).hasMessageContaining("至少需要一条有效业务记录");
            save(id, null, null, "无预算也能办理", UUID.randomUUID().toString());
            if (configured) assertThat(summary(id).myMinutes()).isEqualByComparingTo("15");
            else assertThat(summary(id)).isNull();
            finish(id);
        }
    }

    @Test
    void runningAdjustmentCanOmitEstimateOrStopCountingWithoutRepricingHistory() {
        Detail task = create(config("room", true, once()));
        String id = task.task().id();
        save(id, null, null, "旧计时", UUID.randomUUID().toString());
        TaskWorkTimes.Context context = tasks.workTimeContext(id, ACTOR);
        TaskWorkTimes.Context adjusted =
                tasks.adjustWorkTime(
                        new TaskWorkTimes.Change(
                                id,
                                context.expectedRevision(),
                                UUID.randomUUID().toString(),
                                "只调单价不预计数量",
                                TaskWorkTimes.TotalMode.AUTO,
                                null,
                                List.of(new TaskWorkTimes.EntryChange(id, "room", 25, null))),
                        ACTOR);
        assertThat(adjusted.effectiveWorkMinutes()).isNull();
        save(id, null, null, "新计时", UUID.randomUUID().toString());
        assertThat(summary(id).myMinutes()).isEqualByComparingTo("40");
        tasks.adjustWorkTime(change(id, 0, 2), ACTOR);
        save(id, null, null, "停计后仍可办理", UUID.randomUUID().toString());
        assertThat(summary(id).myMinutes()).isEqualByComparingTo("40");
        finish(id);
    }

    @Test
    void primaryViewCannotBypassUnifiedAuthorizationThroughLegacyBinding() {
        NodeInput root =
                new NodeInput(
                        null,
                        null,
                        "旧入口绕过检查",
                        null,
                        ACTOR,
                        null,
                        null,
                        null,
                        List.of(),
                        config("room", true, once()).binding(),
                        null,
                        List.of());
        assertThatThrownBy(
                        () ->
                                tasks.create(
                                        new Create(
                                                root,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                UUID.randomUUID().toString()),
                                        ACTOR))
                .hasMessageContaining("统一数据授权");
    }

    @Test
    void conditionCountsOnlyWhenSavedRecordFirstMeetsConfiguredValue() {
        Detail task =
                create(
                        config(
                                "room",
                                true,
                                new WorkRule(
                                        WorkRuleMode.CONDITION,
                                        20,
                                        null,
                                        business.nameField,
                                        "done")));
        String id = task.task().id();
        ApplicationRecords.Row initial =
                save(id, null, null, "pending", UUID.randomUUID().toString())
                        .handling()
                        .result()
                        .record();
        assertThat(summary(id).myMinutes()).isZero();
        ApplicationRecords.Row changed =
                save(id, initial.id(), initial.revision(), "done", UUID.randomUUID().toString())
                        .handling()
                        .result()
                        .record();
        save(id, changed.id(), changed.revision(), "later", UUID.randomUUID().toString());
        assertThat(summary(id).myMinutes()).isEqualByComparingTo("20");
    }

    private Config scoped(String key, boolean legacyAllowAll, DataAccessMode scope) {
        Config base = config(key, false, once());
        return new Config(
                base.key(),
                base.name(),
                base.binding(),
                base.dataMode(),
                null,
                null,
                null,
                null,
                false,
                legacyAllowAll,
                base.workRule(),
                scope);
    }

    private String externalRecord() {
        return business.records
                .save(
                        new ApplicationRecords.Save(
                                business.resource.applicationId(),
                                business.object.objectId(),
                                null,
                                null,
                                Map.of(business.nameField, "外部合法记录"),
                                Map.of(),
                                Map.of(),
                                null,
                                business.resource.resourceId(),
                                UUID.randomUUID().toString(),
                                null),
                        ACTOR)
                .record()
                .id();
    }

    @Test
    void eachGroupEntryHasIndependentScopeAndAllStillDefaultsToTaskRecords() {
        String external = externalRecord();
        Detail task =
                create(
                        true,
                        List.of(
                                scoped("group", true, DataAccessMode.GROUP),
                                scoped("all", false, DataAccessMode.ALL)));
        String id = task.task().id();
        assertThat(entries.entries(id, ACTOR))
                .extracting(Entry::effectivePolicy)
                .containsExactly(DataAccessMode.GROUP, DataAccessMode.ALL);
        assertThatThrownBy(
                        () ->
                                entries.page(
                                        new TaskWorkEntries.Query(
                                                id, "group", true, false, 1, 20, null),
                                        ACTOR))
                .hasMessageContaining("只允许任务关联数据");
        assertThat(
                        entries.page(
                                        new TaskWorkEntries.Query(
                                                id, "all", true, false, 1, 20, null),
                                        ACTOR)
                                .getList())
                .extracting(item -> item.record().id())
                .contains(external);
        assertThat(
                        entries.page(
                                        new TaskWorkEntries.Query(
                                                id, "all", false, false, 1, 20, null),
                                        ACTOR)
                                .getList())
                .isEmpty();
        assertThatThrownBy(() -> entries.form(new Form(id, "group", external, null), ACTOR))
                .isInstanceOf(RuntimeException.class);
        assertThat(entries.form(new Form(id, "all", external, null), ACTOR).record().record().id())
                .isEqualTo(external);
    }

    @Test
    void explicitScopeOverridesAllowAllForOldEntriesWithoutGroupPolicy() {
        String external = externalRecord();
        Detail task =
                create(
                        false,
                        List.of(
                                scoped("all", false, DataAccessMode.ALL),
                                scoped("group", true, DataAccessMode.GROUP)));
        String id = task.task().id();
        assertThat(
                        entries.page(
                                        new TaskWorkEntries.Query(
                                                id, "all", true, false, 1, 20, null),
                                        ACTOR)
                                .getList())
                .extracting(item -> item.record().id())
                .contains(external);
        assertThatThrownBy(
                        () ->
                                entries.page(
                                        new TaskWorkEntries.Query(
                                                id, "group", true, false, 1, 20, null),
                                        ACTOR))
                .hasMessageContaining("只允许任务关联数据");
        assertThat(entries.entries(id, ACTOR))
                .extracting(Entry::effectivePolicy)
                .containsExactly(DataAccessMode.ALL, DataAccessMode.GROUP);
    }
}
