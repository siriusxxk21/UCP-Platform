package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.richuang.os.module.bpm.api.event.BpmProcessInstanceStatus;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.runtime.service.taskcenter.*;

import org.junit.jupiter.api.*;

import java.util.*;

/** 多入口真实记录、共享边界、版本与审批重提回归，仅清理本类前缀夹具。 */
class TaskWorkEntryIntegrationTest {
    private WorkDraftIntegrationTest business;
    private BusinessHandlingIntegrationTest approval;
    private TaskCenterService tasks;
    private TaskWorkEntryService entries;
    private final List<String> roots = new ArrayList<>();
    private final List<String> templates = new ArrayList<>();
    private static final long ACTOR = 10001L;

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
                        i -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(i.getArgument(0));
                            user.setNickname("入口测试成员");
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
        for (String template : templates) {
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_template_version WHERE template_id=?",
                    template);
            jdbc.update(
                    "DELETE FROM public.nocode_task_template_version WHERE template_id=?",
                    template);
            jdbc.update("DELETE FROM public.nocode_task_template WHERE id=?", template);
        }
        if (approval != null) approval.cleanup();
        else business.cleanup();
    }

    private Binding binding() {
        return new Binding(business.resource.applicationId(), business.resource.resourceId(), null);
    }

    private TaskWorkEntries.Config config(String key, boolean required, boolean all) {
        return new TaskWorkEntries.Config(
                key,
                key,
                binding(),
                TaskWorkEntries.DataMode.ROOT_SHARED,
                null,
                null,
                null,
                null,
                required,
                all);
    }

    private NodeInput node(String id, List<TaskWorkEntries.Config> configs) {
        return new NodeInput(
                id, null, "多入口任务", null, ACTOR, null, null, null, List.of(), null, null, configs);
    }

    private Detail root(List<TaskWorkEntries.Config> configs) {
        Detail root =
                tasks.create(
                        new Create(node(null, configs), null, null, null, null, null, null, key()),
                        ACTOR);
        roots.add(root.task().id());
        return root;
    }

    private Detail start(String id) {
        return tasks.transition(
                new Transition(
                        id, tasks.detail(id, ACTOR).task().revision(), Action.START, null, key()),
                ACTOR);
    }

    private Detail complete(String id) {
        return tasks.transition(
                new Transition(
                        id,
                        tasks.detail(id, ACTOR).task().revision(),
                        Action.COMPLETE,
                        "完成入口反馈",
                        key()),
                ACTOR);
    }

    private ApplicationRecords.Save input(String id, String revision, String value, String key) {
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
                key,
                null);
    }

    private TaskWorkEntries.Saved save(String task, String entry, String value) {
        return entries.save(
                new TaskWorkEntries.Save(task, entry, null, input(null, null, value, key())),
                ACTOR);
    }

    private List<TaskWorkEntries.Item> page(String task, String entry) {
        return entries.page(new TaskWorkEntries.Query(task, entry, false, false, 1, 100, ""), ACTOR)
                .getList();
    }

    private String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void configuredEntryOrderSurvivesPhysicalRowOrderAndIsInheritedByChildren() {
        String root =
                root(List.of(
                                config("first", false, false),
                                config("second", false, false),
                                config("third", false, false)))
                        .task()
                        .id();
        String child =
                tasks.create(
                                new Create(
                                        node(null, null),
                                        root,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        key()),
                                ACTOR)
                        .task()
                        .id();
        // 仅打乱本用例夹具物理排序，确定性覆盖 create_time/id 顺序恰好与配置相同的偶然情况。
        jdbc.update(
                "UPDATE public.nocode_task_entry_binding SET create_time=now()+CASE entry_key WHEN"
                    + " 'first' THEN interval '3 seconds' WHEN 'second' THEN interval '2 seconds'"
                    + " ELSE interval '1 seconds' END WHERE task_id IN (?,?)",
                root,
                child);
        assertThat(
                        jdbc.queryForList(
                                "SELECT entry_key FROM public.nocode_task_entry_binding WHERE"
                                        + " task_id=? ORDER BY create_time,id",
                                String.class,
                                root))
                .containsExactly("third", "second", "first");
        for (String task : List.of(root, child)) {
            assertThat(entries.entries(task, ACTOR))
                    .extracting(e -> e.config().key())
                    .containsExactly("first", "second", "third");
            assertThat(entries.effectiveConfigs(task))
                    .extracting(TaskWorkEntries.Config::key)
                    .containsExactly("first", "second", "third");
        }
        start(root);
        start(child);
        complete(child);
        assertThat(tasks.detail(root, ACTOR).task().status()).isEqualTo(State.COMPLETED.name());
        assertThat(entries.materials(child, ACTOR))
                .extracting(TaskWorkEntries.Material::entryKey)
                .containsExactly("first", "second", "third");
    }

    @Test
    void multipleEntriesContainMultipleRealRecordsWithoutMixingSameObject() {
        String task =
                root(List.of(config("work", true, false), config("cost", false, false)))
                        .task()
                        .id();
        start(task);
        save(task, "work", "施工一");
        save(task, "work", "施工二");
        save(task, "cost", "费用一");
        assertThat(page(task, "work")).hasSize(2);
        assertThat(page(task, "cost")).hasSize(1);
        assertThat(business.recordCount()).isEqualTo(3);
        TaskWorkEntries.Item record = page(task, "work").getFirst();
        String revision = record.record().revision();
        entries.form(new TaskWorkEntries.Form(task, "work", record.record().id(), null), ACTOR);
        assertThat(page(task, "work").getFirst().sources()).hasSize(1);
        entries.save(
                new TaskWorkEntries.Save(
                        task, "work", null, input(record.record().id(), revision, "施工已纠正", key())),
                ACTOR);
        assertThat(page(task, "work")).hasSize(2);
        assertThat(page(task, "work").getFirst().sources()).hasSize(2);
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        new TaskWorkEntries.Save(
                                                task,
                                                "work",
                                                null,
                                                input(
                                                        record.record().id(),
                                                        revision,
                                                        "覆盖并发",
                                                        key())),
                                        ACTOR))
                .isInstanceOf(RuntimeException.class);
        complete(task);
        assertThat(entries.materials(task, ACTOR)).hasSize(2);
    }

    @Test
    void rootSharingAndExplicitIndependentEntryKeepSeparateDatasets() {
        String root = root(List.of(config("work", false, false))).task().id();
        TaskWorkEntries.Config local =
                new TaskWorkEntries.Config(
                        "private",
                        "独立",
                        binding(),
                        TaskWorkEntries.DataMode.INDEPENDENT,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false);
        Detail child =
                tasks.create(
                        new Create(
                                node(null, List.of()), root, null, null, null, null, null, key()),
                        ACTOR);
        Detail independent =
                tasks.create(
                        new Create(
                                node(null, List.of(local)),
                                root,
                                null,
                                null,
                                null,
                                null,
                                null,
                                key()),
                        ACTOR);
        start(root);
        start(child.task().id());
        start(independent.task().id());
        save(child.task().id(), "work", "子任务贡献");
        assertThat(page(root, "work")).hasSize(1);
        assertThat(page(independent.task().id(), "private")).isEmpty();
        String record = page(root, "work").getFirst().record().id();
        assertThatThrownBy(
                        () ->
                                entries.form(
                                        new TaskWorkEntries.Form(
                                                independent.task().id(), "private", record, null),
                                        ACTOR))
                .hasMessageContaining("范围");
        assertThat(entries.entries(child.task().id(), ACTOR).getFirst().inherited()).isTrue();
    }

    @Test
    void explicitLinkIsIdempotentAndDeniedOutsideTaskScope() {
        String scoped = root(List.of(config("scoped", false, false))).task().id();
        String open = root(List.of(config("open", false, true))).task().id();
        start(scoped);
        start(open);
        String record =
                business.records.save(input(null, null, "既有业务数据", key()), ACTOR).record().id();
        assertThatThrownBy(
                        () ->
                                entries.link(
                                        new TaskWorkEntries.Link(scoped, "scoped", record, key()),
                                        ACTOR))
                .hasMessageContaining("范围");
        TaskWorkEntries.Link link = new TaskWorkEntries.Link(open, "open", record, key());
        String receipt = entries.link(link, ACTOR).contributionId();
        assertThat(entries.link(link, ACTOR).contributionId()).isEqualTo(receipt);
        assertThat(
                        entries.receipt(
                                        new TaskWorkEntries.Receipt(
                                                open, "open", link.requestKey()),
                                        ACTOR)
                                .contributionId())
                .isEqualTo(receipt);
        assertThat(entries.receipt(new TaskWorkEntries.Receipt(open, "open", key()), ACTOR))
                .isNull();
        assertThat(page(open, "open")).hasSize(1);
        assertThat(business.recordCount()).isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                entries.link(
                                        new TaskWorkEntries.Link(open, "open", null, key()), ACTOR))
                .hasMessageContaining("请选择");
    }

    @Test
    void requiredFeedbackBlocksCompletionAndSubmittedVersionIsImmutable() {
        String task = root(List.of(config("required", true, false))).task().id();
        start(task);
        assertThatThrownBy(() -> complete(task)).hasMessageContaining("至少");
        TaskWorkEntries.Saved saved = save(task, "required", "提交版本");
        complete(task);
        ApplicationRecords.Row record = saved.handling().result().record();
        business.records.save(input(record.id(), record.revision(), "业务后续修改", key()), ACTOR);
        assertThat(entries.materials(task, ACTOR).getFirst().records().getFirst().record().values())
                .containsEntry(business.nameField, "提交版本");
    }

    @Test
    void sourceReadOnlyAndFieldLimitsApplyToFormSaveAndReceiptResponses() {
        String root = root(List.of(config("work", false, false))).task().id();
        TaskWorkEntries.Config restricted =
                new TaskWorkEntries.Config(
                        "read",
                        "受限共享",
                        binding(),
                        TaskWorkEntries.DataMode.SOURCE_SHARED,
                        root,
                        "work",
                        List.of(),
                        null,
                        false,
                        false);
        String child =
                tasks.create(
                                new Create(
                                        node(null, List.of(restricted)),
                                        root,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        key()),
                                ACTOR)
                        .task()
                        .id();
        start(root);
        start(child);
        assertThat(entries.entries(child, ACTOR).getFirst().canWrite()).isFalse();
        assertThat(entries.entries(child, ACTOR).getFirst().canLink()).isTrue();
        assertThat(entries.entries(child, ACTOR).getFirst().config().writableFieldIds()).isEmpty();
        String record = save(root, "work", "不可读字段").handling().result().record().id();
        FormContext form =
                entries.form(new TaskWorkEntries.Form(child, "read", record, null), ACTOR);
        assertThat(form.record().record().values()).isEmpty();
        assertThat(form.handling().result().record().values()).isEmpty();
        ApplicationRecords.Save empty =
                new ApplicationRecords.Save(
                        business.resource.applicationId(),
                        business.object.objectId(),
                        null,
                        null,
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        null,
                        business.resource.resourceId(),
                        key(),
                        null);
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        new TaskWorkEntries.Save(child, "read", null, empty),
                                        ACTOR))
                .hasMessageContaining("只读");
        TaskWorkEntries.Link link = new TaskWorkEntries.Link(child, "read", record, key());
        assertThat(entries.link(link, ACTOR).handling().result().record().values()).isEmpty();
        assertThat(entries.link(link, ACTOR).handling().result().record().values()).isEmpty();
    }

    @Test
    void templatePublishedEntryRefDoesNotFollowLaterApplicationPublish() {
        Template template =
                tasks.saveTemplate(
                        new SaveTemplate(
                                null,
                                null,
                                "入口固定版本",
                                null,
                                List.of(
                                        node(
                                                "root",
                                                List.of(
                                                        new TaskWorkEntries.Config(
                                                                "work",
                                                                "work",
                                                                binding(),
                                                                TaskWorkEntries.DataMode
                                                                        .INDEPENDENT,
                                                                null,
                                                                null,
                                                                null,
                                                                null,
                                                                false,
                                                                false))))),
                        ACTOR);
        templates.add(template.id());
        TemplateVersion published =
                tasks.publish(new PublishTemplate(template.id(), template.revision()), ACTOR);
        ApplicationCenter.Detail app = business.applications.get(business.resource.applicationId());
        business.applications.publish(
                new ApplicationCenter.Revision(
                        app.application().id(), app.application().revision(), "再发布"),
                ACTOR);
        Detail task =
                tasks.create(
                        new Create(
                                node(null, List.of()),
                                null,
                                template.id(),
                                published.version(),
                                null,
                                null,
                                null,
                                key()),
                        ACTOR);
        roots.add(task.task().rootId());
        String child =
                task.nodes().stream()
                        .filter(n -> !n.id().equals(task.task().id()))
                        .findFirst()
                        .orElseThrow()
                        .id();
        assertThat(
                        entries.entries(child, ACTOR)
                                .getFirst()
                                .binding()
                                .resource()
                                .applicationVersion())
                .isEqualTo(business.resource.applicationVersion());
        start(task.task().id());
        start(child);
        save(child, "work", "旧版本继续办理");
    }

    @Test
    void rejectedAndCanceledRequestsCanResubmitAndCompleteWithoutOldRequestBlocking() {
        business.cleanup();
        approval = new BusinessHandlingIntegrationTest();
        approval.setup();
        approval.policy("APPROVAL", "APPROVAL");
        business = approval.business;
        String task = root(List.of(config("feedback", true, false))).task().id();
        start(task);
        TaskWorkEntries.Saved first = save(task, "feedback", "原申请");
        assertThat(first.handling().outcome()).isEqualTo("SUBMITTED");
        assertThat(
                        entries.form(
                                        new TaskWorkEntries.Form(
                                                task, "feedback", null, first.contributionId()),
                                        ACTOR)
                                .record()
                                .record()
                                .values())
                .containsEntry(business.nameField, "原申请");
        assertThat(page(task, "feedback").getFirst().record().values())
                .containsEntry(business.nameField, "原申请");
        assertThat(page(task, "feedback").getFirst().status()).isEqualTo("SUBMITTED");
        assertThat(page(task, "feedback").getFirst().sources()).isEmpty();
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        new TaskWorkEntries.Save(
                                                task,
                                                "feedback",
                                                first.contributionId(),
                                                input(null, null, "待审期间重复修改", key())),
                                        ACTOR))
                .hasMessageContaining("尚未生效");
        assertThatThrownBy(() -> complete(task)).hasMessageContaining("未生效");
        approval.event(first.handling().request(), BpmProcessInstanceStatus.REJECTED);
        ApplicationCenter.Detail app = business.applications.get(business.resource.applicationId());
        List<ApplicationCenter.Resource> resources = new ArrayList<>(app.draft().resources());
        ApplicationUi.Page pageConfig =
                new ApplicationUi.Page(
                        List.of(
                                new ApplicationUi.Node(
                                        "feedback-tasks",
                                        "TASKS",
                                        null,
                                        business.resource.resourceId(),
                                        null,
                                        null,
                                        List.of())),
                        business.object.objectId(),
                        2);
        resources.add(
                new ApplicationCenter.Resource(
                        "feedback-page",
                        "PAGE",
                        "feedback_page",
                        "反馈任务反查",
                        mapper.convertValue(
                                pageConfig,
                                new com.fasterxml.jackson.core.type.TypeReference<
                                        Map<String, Object>>() {})));
        ApplicationCenter.Detail updated =
                business.applications.save(
                        new ApplicationCenter.Save(
                                app.application().id(),
                                app.application().revision(),
                                app.application().code(),
                                app.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(app.draft().objects(), resources)),
                        ACTOR);
        business.applications.publish(
                new ApplicationCenter.Revision(
                        app.application().id(), updated.application().revision(), "重提期间发布"),
                ACTOR);
        TaskWorkEntries.Save secondCommand =
                new TaskWorkEntries.Save(
                        task, "feedback", first.contributionId(), input(null, null, "修正申请", key()));
        TaskWorkEntries.Saved second = entries.save(secondCommand, ACTOR);
        assertThat(entries.save(secondCommand, ACTOR).contributionId())
                .isEqualTo(second.contributionId());
        approval.event(second.handling().request(), BpmProcessInstanceStatus.CANCELED);
        TaskWorkEntries.Saved third =
                entries.save(
                        new TaskWorkEntries.Save(
                                task,
                                "feedback",
                                second.contributionId(),
                                input(null, null, "最终版本", key())),
                        ACTOR);
        approval.event(third.handling().request(), BpmProcessInstanceStatus.APPROVED);
        assertThat(page(task, "feedback")).hasSize(1);
        assertThat(page(task, "feedback").getFirst().record().values())
                .containsEntry(business.nameField, "最终版本");
        complete(task);
        assertThat(entries.materials(task, ACTOR).getFirst().records()).hasSize(1);
        String recordId = page(task, "feedback").getFirst().record().id();
        Query query =
                new Query(
                        "MINE",
                        "ALL",
                        java.time.LocalDate.now(),
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
                        100);
        assertThat(
                        tasks.pageTasks(
                                        new PageQuery(
                                                business.resource.applicationId(),
                                                "feedback-page",
                                                "feedback-tasks",
                                                recordId,
                                                query),
                                        ACTOR)
                                .getList())
                .extracting(Row::id)
                .contains(task);
        assertThat(
                        entries.handlingLocation(first.handling().request().id(), ACTOR)
                                .contributionId())
                .isEqualTo(third.contributionId());
        assertThat(entries.handlingLocation(first.handling().request().id(), 20002)).isNull();
        assertThat(
                        approval.service
                                .detail(first.handling().request().id(), null, ACTOR)
                                .material()
                                .values())
                .containsEntry(business.nameField, "原申请");
    }

    @Test
    void explicitIndependentGroupCanBeInheritedByDescendantsWithoutJoiningAncestorGroup() {
        String root = root(List.of(config("work", false, false))).task().id();
        TaskWorkEntries.Config independent =
                new TaskWorkEntries.Config(
                        "work",
                        "独立支系",
                        binding(),
                        TaskWorkEntries.DataMode.INDEPENDENT,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false);
        String child =
                tasks.create(
                                new Create(
                                        node(null, List.of(independent)),
                                        root,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        key()),
                                ACTOR)
                        .task()
                        .id();
        String grandchild =
                tasks.create(
                                new Create(
                                        node(null, List.of()),
                                        child,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        key()),
                                ACTOR)
                        .task()
                        .id();
        start(root);
        start(child);
        start(grandchild);
        save(grandchild, "work", "独立支系后代反馈");
        assertThat(page(child, "work")).hasSize(1);
        assertThat(page(root, "work")).isEmpty();
        assertThat(entries.entries(grandchild, ACTOR).getFirst().datasetId())
                .isEqualTo(entries.entries(child, ACTOR).getFirst().datasetId());
    }

    @Test
    void explicitSharedEntryWithoutMatchingAncestorStartsStableGroupAndChildrenInherit() {
        String root = root(List.of()).task().id();
        String child =
                tasks.create(
                                new Create(
                                        node(null, List.of(config("shared", false, false))),
                                        root,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        key()),
                                ACTOR)
                        .task()
                        .id();
        String grandchild =
                tasks.create(
                                new Create(
                                        node(null, List.of()),
                                        child,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        key()),
                                ACTOR)
                        .task()
                        .id();
        start(root);
        start(child);
        start(grandchild);
        save(grandchild, "shared", "继承模板节点入口");
        assertThat(page(child, "shared")).hasSize(1);
        assertThat(entries.entries(root, ACTOR)).isEmpty();
    }

    @Test
    void allDataSearchCannotProbeFieldsHiddenByEntryConfiguration() {
        TaskWorkEntries.Config limited =
                new TaskWorkEntries.Config(
                        "limited",
                        "字段受限",
                        binding(),
                        TaskWorkEntries.DataMode.ROOT_SHARED,
                        null,
                        null,
                        List.of(),
                        null,
                        false,
                        true);
        String task = root(List.of(limited)).task().id();
        start(task);
        String record =
                business.records.save(input(null, null, "隐藏文本", key()), ACTOR).record().id();
        entries.link(new TaskWorkEntries.Link(task, "limited", record, key()), ACTOR);
        assertThatThrownBy(
                        () ->
                                entries.page(
                                        new TaskWorkEntries.Query(
                                                task, "limited", true, false, 1, 20, "隐藏文本"),
                                        ACTOR))
                .hasMessageContaining("限制了可读字段");
        assertThat(
                        entries.page(
                                        new TaskWorkEntries.Query(
                                                task, "limited", false, false, 1, 20, "隐藏文本"),
                                        ACTOR)
                                .getTotal())
                .isZero();
    }

    @Test
    void publishedListEntryKeepsTemplateVersionEvenAfterCurrentEntryChangesForm() {
        ApplicationCenter.Detail app = business.applications.get(business.resource.applicationId());
        ApplicationCenter.Resource original = app.draft().resources().getFirst();
        ApplicationAuthorization.ObjectGrant grant =
                new ApplicationAuthorization.ObjectGrant(
                        business.object.objectId(),
                        Set.of("READ", "CREATE", "UPDATE"),
                        "ALL",
                        Set.of(business.nameField),
                        Set.of(business.nameField),
                        Set.of(),
                        Set.of());
        ApplicationUi.View view =
                new ApplicationUi.View(
                        business.object.objectId(),
                        List.of(business.nameField),
                        Map.of(),
                        null,
                        false,
                        10,
                        original.id());
        TaskEntries.Config entry =
                new TaskEntries.Config(
                        business.object.objectId(),
                        "entry-view",
                        original.id(),
                        "LIST",
                        "测试",
                        null,
                        null,
                        1,
                        List.of(grant));
        List<ApplicationCenter.Resource> resources = new ArrayList<>(app.draft().resources());
        resources.add(resource("entry-view", "VIEW", view));
        resources.add(resource("entry-edit", "TASK_ENTRY", entry));
        ApplicationCenter.Detail saved =
                LegacyTaskEntryFixtures.seed(
                        business.applications,
                        new ApplicationCenter.Save(
                                app.application().id(),
                                app.application().revision(),
                                app.application().code(),
                                app.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(app.draft().objects(), resources)),
                        ACTOR);
        business.applications.publish(
                new ApplicationCenter.Revision(
                        app.application().id(), saved.application().revision(), "列表入口发布"),
                ACTOR);
        int fixedVersion = business.applications.published(app.application().id()).versionNo();
        com.richuang.os.nocode.application.service.task.TaskEntryPolicyService policies =
                servicesContext.getBean(
                        com.richuang.os.nocode.application.service.task.TaskEntryPolicyService
                                .class);
        TaskEntries.Policy policy = policies.get(app.application().id(), "entry-edit");
        policies.save(
                new TaskEntries.SavePolicy(
                        app.application().id(),
                        "entry-edit",
                        policy.revision(),
                        true,
                        List.of(
                                new ApplicationAuthorization.Member(
                                        "USER", Long.toString(ACTOR), List.of(grant)))),
                ACTOR);
        TaskWorkEntries.Config feedback =
                new TaskWorkEntries.Config(
                        "entry",
                        "入口反馈",
                        new Binding(app.application().id(), original.id(), "entry-edit"),
                        TaskWorkEntries.DataMode.INDEPENDENT,
                        null,
                        null,
                        null,
                        null,
                        false,
                        true);
        Template template =
                tasks.saveTemplate(
                        new SaveTemplate(
                                null,
                                null,
                                "入口固定模板",
                                null,
                                List.of(node("node", List.of(feedback)))),
                        ACTOR);
        templates.add(template.id());
        TemplateVersion version =
                tasks.publish(new PublishTemplate(template.id(), template.revision()), ACTOR);
        ApplicationCenter.Detail latest = business.applications.get(app.application().id());
        List<ApplicationCenter.Resource> next = new ArrayList<>(latest.draft().resources());
        next.add(
                new ApplicationCenter.Resource(
                        "changed-form", "FORM", "changed_form", "新表单", original.config()));
        next.replaceAll(
                r ->
                        r.id().equals("entry-edit")
                                ? resource(
                                        "entry-edit",
                                        "TASK_ENTRY",
                                        new TaskEntries.Config(
                                                business.object.objectId(),
                                                "entry-view",
                                                "changed-form",
                                                "LIST",
                                                "测试",
                                                null,
                                                null,
                                                1,
                                                List.of(grant)))
                                : r);
        ApplicationCenter.Detail modified =
                LegacyTaskEntryFixtures.seed(
                        business.applications,
                        new ApplicationCenter.Save(
                                latest.application().id(),
                                latest.application().revision(),
                                latest.application().code(),
                                latest.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(latest.draft().objects(), next)),
                        ACTOR);
        business.applications.publish(
                new ApplicationCenter.Revision(
                        latest.application().id(), modified.application().revision(), "入口改用新表单"),
                ACTOR);
        Detail created =
                tasks.create(
                        new Create(
                                node(null, List.of()),
                                null,
                                template.id(),
                                version.version(),
                                null,
                                null,
                                null,
                                key()),
                        ACTOR);
        roots.add(created.task().id());
        String task =
                created.nodes().stream()
                        .filter(n -> !n.id().equals(created.task().id()))
                        .findFirst()
                        .orElseThrow()
                        .id();
        assertThat(
                        entries.entries(task, ACTOR)
                                .getFirst()
                                .binding()
                                .resource()
                                .applicationVersion())
                .isEqualTo(fixedVersion);
        start(created.task().id());
        start(task);
        save(task, "entry", "原入口版本继续执行");
        assertThat(
                        entries.page(
                                        new TaskWorkEntries.Query(
                                                task, "entry", true, false, 1, 20, ""),
                                        ACTOR)
                                .getTotal())
                .isEqualTo(1);
        TaskEntries.Policy current = policies.get(app.application().id(), "entry-edit");
        policies.save(
                new TaskEntries.SavePolicy(
                        app.application().id(),
                        "entry-edit",
                        current.revision(),
                        false,
                        current.members()),
                ACTOR);
        assertThatThrownBy(
                        () ->
                                entries.form(
                                        new TaskWorkEntries.Form(task, "entry", null, null), ACTOR))
                .isInstanceOf(RuntimeException.class);
    }

    private ApplicationCenter.Resource resource(String id, String kind, Object value) {
        return new ApplicationCenter.Resource(
                id,
                kind,
                id.replace('-', '_'),
                id,
                mapper.convertValue(
                        value,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {}));
    }

    @Test
    void receiptReturnsOriginalRecordVersionAfterAnotherBusinessUpdate() {
        String task = root(List.of(config("feedback", true, false))).task().id();
        start(task);
        String requestKey = key();
        TaskWorkEntries.Save command =
                new TaskWorkEntries.Save(
                        task, "feedback", null, input(null, null, "本人提交", requestKey));
        TaskWorkEntries.Saved first = entries.save(command, ACTOR);
        ApplicationRecords.Row row = first.handling().result().record();
        business.records.save(input(row.id(), row.revision(), "后续修改", key()), ACTOR);
        assertThat(
                        entries.receipt(
                                        new TaskWorkEntries.Receipt(task, "feedback", requestKey),
                                        ACTOR)
                                .handling()
                                .result()
                                .record()
                                .values())
                .containsEntry(business.nameField, "本人提交");
        assertThat(entries.save(command, ACTOR).handling().result().record().revision())
                .isEqualTo(row.revision());
    }

    @Test
    void pendingApprovalKeepsSubmissionWorkRuleWhenManagerChangesRuntimeRate() {
        business.cleanup();
        approval = new BusinessHandlingIntegrationTest();
        approval.setup();
        approval.policy("APPROVAL", "APPROVAL");
        business = approval.business;
        TaskWorkEntries.Config config =
                new TaskWorkEntries.Config(
                        "feedback",
                        "审批工时",
                        binding(),
                        TaskWorkEntries.DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false,
                        new TaskWorkEntries.WorkRule(
                                TaskWorkEntries.WorkRuleMode.RECORD_ONCE, 15, null, null, null));
        String task = root(List.of(config)).task().id();
        start(task);
        TaskWorkEntries.Saved first = save(task, "feedback", "调时前提交");
        assertThat(first.handling().outcome()).isEqualTo("SUBMITTED");
        assertThat(entries.entries(task, ACTOR).getFirst().workSummary().myMinutes()).isZero();
        TaskWorkTimes.Context context = tasks.workTimeContext(task, ACTOR);
        tasks.adjustWorkTime(
                new TaskWorkTimes.Change(
                        task,
                        context.expectedRevision(),
                        key(),
                        "审批期间调整后续标准",
                        TaskWorkTimes.TotalMode.AUTO,
                        null,
                        List.of(
                                new TaskWorkTimes.EntryChange(
                                        task, "feedback", 45, java.math.BigDecimal.valueOf(2)))),
                ACTOR);
        approval.event(first.handling().request(), BpmProcessInstanceStatus.APPROVED);
        assertThat(entries.entries(task, ACTOR).getFirst().workSummary().myMinutes())
                .isEqualByComparingTo("15");
        TaskWorkEntries.Saved second = save(task, "feedback", "调时后提交");
        approval.event(second.handling().request(), BpmProcessInstanceStatus.APPROVED);
        assertThat(entries.entries(task, ACTOR).getFirst().workSummary().myMinutes())
                .isEqualByComparingTo("60");
        approval.event(first.handling().request(), BpmProcessInstanceStatus.APPROVED);
        assertThat(entries.entries(task, ACTOR).getFirst().workSummary().myMinutes())
                .isEqualByComparingTo("60");
    }

    @Test
    void approvedUpdateCapturesAppliedVersionInsteadOfPreviousOrLaterBusinessValues() {
        business.cleanup();
        approval = new BusinessHandlingIntegrationTest();
        approval.setup();
        approval.policy("DIRECT", "APPROVAL");
        business = approval.business;
        String task = root(List.of(config("feedback", true, true))).task().id();
        start(task);
        TaskWorkEntries.Saved initial = save(task, "feedback", "修改前");
        ApplicationRecords.Row before = initial.handling().result().record();
        TaskWorkEntries.Saved update =
                entries.save(
                        new TaskWorkEntries.Save(
                                task,
                                "feedback",
                                null,
                                input(before.id(), before.revision(), "审批通过版本", key())),
                        ACTOR);
        assertThat(update.handling().outcome()).isEqualTo("SUBMITTED");
        approval.event(update.handling().request(), BpmProcessInstanceStatus.APPROVED);
        ApplicationRecords.Row applied = page(task, "feedback").getFirst().record();
        assertThat(applied.values()).containsEntry(business.nameField, "审批通过版本");
        approval.policy("DIRECT", "DIRECT");
        business.records.save(input(applied.id(), applied.revision(), "批准后其他修改", key()), ACTOR);
        complete(task);
        TaskWorkEntries.Submission snapshot =
                entries.materials(task, ACTOR).getFirst().submissions().stream()
                        .filter(m -> m.contributionId().equals(update.contributionId()))
                        .findFirst()
                        .orElseThrow();
        assertThat(snapshot.record().record().values()).containsEntry(business.nameField, "审批通过版本");
        assertThat(snapshot.record().record().revision()).isEqualTo(applied.revision());
    }

    @Test
    void untrustedSharedSourceIsRejected() {
        TaskWorkEntries.Config invalid =
                new TaskWorkEntries.Config(
                        "wrong",
                        "非法共享",
                        binding(),
                        TaskWorkEntries.DataMode.SOURCE_SHARED,
                        "missing",
                        "work",
                        null,
                        List.of(business.nameField),
                        false,
                        false);
        assertThatThrownBy(() -> root(List.of(invalid))).hasMessageContaining("来源");
    }

    private String groupRoot() {
        return groupRoot(null);
    }

    private String groupRoot(List<TaskWorkEntries.Config> feedback) {
        NodeInput root =
                new NodeInput(
                        null,
                        null,
                        "统一数据授权回归",
                        null,
                        ACTOR,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        binding(),
                        null,
                        feedback,
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP));
        Detail created =
                tasks.create(new Create(root, null, null, null, null, null, null, key()), ACTOR);
        roots.add(created.task().id());
        start(created.task().id());
        return created.task().id();
    }

    private String nonMemberNode(String root) {
        NodeInput child =
                new NodeInput(
                        null,
                        null,
                        "非应用成员完成节点",
                        null,
                        20002L,
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
                tasks.create(new Create(child, root, null, null, null, null, null, key()), ACTOR)
                        .task()
                        .id();
        tasks.transition(
                new Transition(
                        id, tasks.detail(id, 20002).task().revision(), Action.START, null, key()),
                20002);
        return id;
    }

    @Test
    void nonMemberCanCommitCompletionWithDeletedBusinessAndFeedbackContributions() {
        String root = groupRoot(List.of(config("feedback", true, false)));
        String task = nonMemberNode(root);
        Map<String, ApplicationRecords.Row> alive = new HashMap<>();
        for (String entry : List.of(TaskDataPolicies.BUSINESS, "feedback")) {
            ApplicationRecords.Row valid =
                    entries.save(
                                    new TaskWorkEntries.Save(
                                            task,
                                            entry,
                                            null,
                                            input(null, null, "有效材料-" + entry, key())),
                                    20002)
                            .handling()
                            .result()
                            .record();
            ApplicationRecords.Row removed =
                    entries.save(
                                    new TaskWorkEntries.Save(
                                            task,
                                            entry,
                                            null,
                                            input(null, null, "将被删除-" + entry, key())),
                                    20002)
                            .handling()
                            .result()
                            .record();
            alive.put(entry, valid);
            if (TaskDataPolicies.BUSINESS.equals(entry))
                entries.delete(
                        new TaskWorkEntries.Delete(
                                task, entry, removed.id(), removed.revision(), key()),
                        20002);
            else
                business.records.delete(
                        new ApplicationRecords.Delete(
                                business.resource.applicationId(),
                                business.object.objectId(),
                                removed.id(),
                                removed.revision()),
                        ACTOR);
        }
        assertThatThrownBy(
                        () ->
                                business.records.get(
                                        business.resource.applicationId(),
                                        business.object.objectId(),
                                        alive.get("feedback").id(),
                                        20002))
                .hasMessageContaining("权限");
        Detail completed =
                tasks.transition(
                        new Transition(
                                task,
                                tasks.detail(task, 20002).task().revision(),
                                Action.COMPLETE,
                                "保留有效材料",
                                key()),
                        20002);
        assertThat(completed.task().status()).isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM public.nocode_task_instance WHERE id=?",
                                String.class,
                                task))
                .isEqualTo("COMPLETED");
        assertThat(entries.materials(task, 20002))
                .allSatisfy(
                        material ->
                                assertThat(material.records())
                                        .extracting(item -> item.record().id())
                                        .containsExactly(alive.get(material.entryKey()).id()));
        ApplicationRecords.Row laterDeleted = alive.get("feedback");
        business.records.delete(
                new ApplicationRecords.Delete(
                        business.resource.applicationId(),
                        business.object.objectId(),
                        laterDeleted.id(),
                        laterDeleted.revision()),
                ACTOR);
        assertThat(
                        entries.materials(task, 20002).stream()
                                .filter(material -> "feedback".equals(material.entryKey()))
                                .findFirst()
                                .orElseThrow()
                                .records())
                .isEmpty();
    }

    @Test
    void requiredFeedbackDeletedBeforeCompletionReturnsBusinessErrorAndKeepsRunning() {
        String root = groupRoot(List.of(config("feedback", true, false)));
        String task = nonMemberNode(root);
        ApplicationRecords.Row removed =
                entries.save(
                                new TaskWorkEntries.Save(
                                        task,
                                        "feedback",
                                        null,
                                        input(null, null, "唯一反馈删除后不得完成", key())),
                                20002)
                        .handling()
                        .result()
                        .record();
        entries.delete(
                new TaskWorkEntries.Delete(
                        task, "feedback", removed.id(), removed.revision(), key()),
                20002);
        assertThatThrownBy(
                        () ->
                                tasks.transition(
                                        new Transition(
                                                task,
                                                tasks.detail(task, 20002).task().revision(),
                                                Action.COMPLETE,
                                                null,
                                                key()),
                                        20002))
                .isInstanceOf(com.richuang.os.framework.common.exception.ServiceException.class)
                .hasMessageContaining("至少需要一条有效业务记录");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM public.nocode_task_instance WHERE id=?",
                                String.class,
                                task))
                .isEqualTo("RUNNING");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_task_event WHERE task_id=? AND"
                                        + " event_type='COMPLETED'",
                                Integer.class,
                                task))
                .isZero();
    }

    @Test
    void rootAuthorizedDataIsSharedAcrossNodesButDoesNotGrantOrdinaryApplicationAccess() {
        String root = groupRoot();
        ApplicationRecords.Row external =
                business.records.save(input(null, null, "系统其它资料", key()), ACTOR).record();
        TaskWorkEntries.Saved created = save(root, TaskDataPolicies.BUSINESS, "本组新建资料");
        ApplicationRecords.Row row = created.handling().result().record();
        NodeInput child =
                new NodeInput(
                        null,
                        null,
                        "另一执行人的节点",
                        null,
                        20002L,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        null,
                        null,
                        null,
                        AssignmentMode.ASSIGNED,
                        List.of());
        Detail childTask =
                tasks.create(new Create(child, root, null, null, null, null, null, key()), ACTOR);
        String childId = childTask.task().id();
        tasks.transition(
                new Transition(
                        childId,
                        tasks.detail(childId, 20002).task().revision(),
                        Action.START,
                        null,
                        key()),
                20002);
        assertThat(
                        entries.page(
                                        new TaskWorkEntries.Query(
                                                childId,
                                                TaskDataPolicies.BUSINESS,
                                                false,
                                                false,
                                                1,
                                                20,
                                                null),
                                        20002)
                                .getList())
                .extracting(item -> item.record().id())
                .containsExactly(row.id());
        assertThatThrownBy(
                        () ->
                                business.records.get(
                                        business.resource.applicationId(),
                                        business.object.objectId(),
                                        row.id(),
                                        20002))
                .hasMessageContaining("权限");
        entries.save(
                new TaskWorkEntries.Save(
                        childId,
                        TaskDataPolicies.BUSINESS,
                        null,
                        input(row.id(), row.revision(), "兄弟节点可共同办理", key())),
                20002);
        assertThatThrownBy(
                        () ->
                                entries.form(
                                        new TaskWorkEntries.Form(
                                                childId,
                                                TaskDataPolicies.BUSINESS,
                                                external.id(),
                                                null),
                                        20002))
                .hasMessageContaining("范围");
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        new TaskWorkEntries.Save(
                                                childId,
                                                TaskDataPolicies.BUSINESS,
                                                null,
                                                input(
                                                        external.id(),
                                                        external.revision(),
                                                        "不能更改系统其它资料",
                                                        key())),
                                        20002))
                .hasMessageContaining("范围");
    }

    private FieldRules.EvaluateQuery ruleQuery(
            String app, String object, String form, String record) {
        return new FieldRules.EvaluateQuery(
                app,
                object,
                form,
                record,
                record == null,
                Map.of(business.nameField, "任务内输入"),
                List.of(),
                List.of(),
                List.of());
    }

    @Test
    void nonMemberFieldRulesStayInsideApprovedTaskFormAndRecord() {
        String root = groupRoot();
        String task = nonMemberNode(root);
        String app = business.resource.applicationId();
        String object = business.object.objectId();
        String form = business.resource.resourceId();
        FieldRules.EvaluateQuery query = ruleQuery(app, object, form, null);
        TaskWorkEntries.Form target =
                new TaskWorkEntries.Form(task, TaskDataPolicies.BUSINESS, null, null);
        assertThatThrownBy(() -> business.records.evaluateRules(query, 20002))
                .hasMessageContaining("权限");
        assertThat(entries.fieldRules(new TaskWorkEntries.FieldRules(target, query), 20002))
                .isNotNull();
        assertThat(entries.entries(task, 20002))
                .allSatisfy(
                        entry -> {
                            assertThat(entry.unavailableReason()).isNull();
                            assertThat(entry.canWrite()).isTrue();
                        });
        for (FieldRules.EvaluateQuery forged :
                List.of(
                        ruleQuery("wrong-app", object, form, null),
                        ruleQuery(app, "wrong-object", form, null),
                        ruleQuery(app, object, "wrong-form", null),
                        ruleQuery(app, object, form, "123"))) {
            assertThatThrownBy(
                            () ->
                                    entries.fieldRules(
                                            new TaskWorkEntries.FieldRules(target, forged), 20002))
                    .hasMessageContaining("固定表单和记录");
        }
        assertThatThrownBy(
                        () ->
                                entries.fieldRules(
                                        new TaskWorkEntries.FieldRules(
                                                new TaskWorkEntries.Form(
                                                        task, "other-entry", null, null),
                                                query),
                                        20002))
                .hasMessageContaining("业务资源");
        ApplicationRecords.Row external =
                business.records.save(input(null, null, "非本组数据", key()), ACTOR).record();
        assertThatThrownBy(
                        () ->
                                entries.fieldRules(
                                        new TaskWorkEntries.FieldRules(
                                                new TaskWorkEntries.Form(
                                                        task,
                                                        TaskDataPolicies.BUSINESS,
                                                        external.id(),
                                                        null),
                                                ruleQuery(app, object, form, external.id())),
                                        20002))
                .hasMessageContaining("范围");
        // 请求结束后线程作用域已清理，普通应用入口仍不能借用刚才的任务授权。
        assertThatThrownBy(() -> business.records.evaluateRules(query, 20002))
                .hasMessageContaining("权限");
    }

    @Test
    void revokedBusinessAuthorizationReturnsUnavailableCardWithoutData() {
        String root = groupRoot();
        String task = nonMemberNode(root);
        save(root, TaskDataPolicies.BUSINESS, "撤权前业务数据");
        doThrow(new org.springframework.security.access.AccessDeniedException("资格已撤销"))
                .when(servicesContext.getBean(AdminUserApi.class))
                .getUser(ACTOR);
        try {
            assertThat(entries.entries(task, 20002))
                    .singleElement()
                    .satisfies(
                            entry -> {
                                assertThat(entry.config().key())
                                        .isEqualTo(TaskDataPolicies.BUSINESS);
                                assertThat(entry.unavailableReason()).contains("业务授权已失效");
                                assertThat(entry.canWrite()).isFalse();
                                assertThat(entry.canDelete()).isFalse();
                                assertThat(entry.workSummary()).isNull();
                            });
            assertThatThrownBy(
                            () ->
                                    entries.page(
                                            new TaskWorkEntries.Query(
                                                    task,
                                                    TaskDataPolicies.BUSINESS,
                                                    false,
                                                    false,
                                                    1,
                                                    20,
                                                    null),
                                            20002))
                    .hasMessageContaining("资格已撤销");
        } finally {
            AdminUserRespDTO restored = new AdminUserRespDTO();
            restored.setId(ACTOR);
            restored.setStatus(0);
            doReturn(restored).when(servicesContext.getBean(AdminUserApi.class)).getUser(ACTOR);
        }
    }

    @Test
    void delegatedDeleteIsRevisionProtectedAtomicAndAuditedWithRealNode() {
        String task = groupRoot();
        ApplicationRecords.Row row =
                save(task, TaskDataPolicies.BUSINESS, "删除边界").handling().result().record();
        String failureKey = key();
        assertThatThrownBy(
                        () ->
                                entries.delete(
                                        new TaskWorkEntries.Delete(
                                                task,
                                                TaskDataPolicies.BUSINESS,
                                                row.id(),
                                                "stale",
                                                failureKey),
                                        ACTOR))
                .isInstanceOf(com.richuang.os.framework.common.exception.ServiceException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_task_entry_record WHERE"
                                        + " request_key=?",
                                Integer.class,
                                failureKey))
                .isZero();
        assertThat(
                        business.records.get(
                                business.resource.applicationId(),
                                business.object.objectId(),
                                row.id(),
                                ACTOR))
                .isNotNull();
        String request = key();
        TaskWorkEntries.Delete command =
                new TaskWorkEntries.Delete(
                        task, TaskDataPolicies.BUSINESS, row.id(), row.revision(), request);
        assertThat(entries.delete(command, ACTOR)).isTrue();
        assertThat(entries.delete(command, ACTOR)).isTrue();
        assertThat(page(task, TaskDataPolicies.BUSINESS)).isEmpty();
        assertThat(tasks.detail(task, ACTOR).events())
                .anySatisfy(
                        event -> {
                            assertThat(event.taskId()).isEqualTo(task);
                            assertThat(event.actorId()).isEqualTo(ACTOR);
                            assertThat(event.note())
                                    .contains("删除业务数据", TaskDataPolicies.BUSINESS, row.id());
                        });
    }

    @Test
    void delegatedApprovalWriteIsExplicitlyRejectedWithoutRequestOrContribution() {
        business.cleanup();
        approval = new BusinessHandlingIntegrationTest();
        approval.setup();
        approval.policy("APPROVAL", "APPROVAL");
        business = approval.business;
        String task = groupRoot();
        String request = key();
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        new TaskWorkEntries.Save(
                                                task,
                                                TaskDataPolicies.BUSINESS,
                                                null,
                                                input(null, null, "不可留下待审批数据", request)),
                                        ACTOR))
                .hasMessageContaining("暂不支持通过总任务统一授权提交");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_handling_request WHERE"
                                        + " application_id=?",
                                Integer.class,
                                Long.parseLong(business.resource.applicationId())))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_task_entry_record WHERE"
                                        + " request_key=?",
                                Integer.class,
                                request))
                .isZero();
        assertThat(page(task, TaskDataPolicies.BUSINESS)).isEmpty();
        assertThat(
                        business.records
                                .page(
                                        new ApplicationRecords.Query(
                                                business.resource.applicationId(),
                                                business.object.objectId(),
                                                1,
                                                20,
                                                null,
                                                Map.of(),
                                                null,
                                                true),
                                        ACTOR)
                                .getTotal())
                .isZero();
    }
}
