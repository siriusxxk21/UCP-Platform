package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.module.infra.dal.dataobject.file.FileDO;
import com.lingan.ucp.module.infra.service.file.FileService;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.application.service.task.TaskEntryPolicyService;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.application.ApplicationRuntimeService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.task.*;

import org.junit.jupiter.api.*;

import java.util.*;

/** 使用当前开发库和唯一测试前缀的真实 Mapper；入口权限与普通应用权限分别验证。 */
class TaskEntryIntegrationTest {
    private String taskRoot;
    private NocodeIntegrationSupport fixture;
    private ApplicationService apps;
    private RecordService records;
    private TaskEntryRuntimeService entries;
    private TaskEntryPolicyService policies;
    private DataCenter.Definition definition;
    private String app;
    private String name;
    private String secret;
    private String attachment;
    private String detailId;
    private String quantity;
    private final long employee = 21001L;
    private final long outsider = 21002L;

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        org.mockito.Mockito.reset(servicesContext.getBean(FileService.class));
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        apps = servicesContext.getBean(ApplicationService.class);
        records = servicesContext.getBean(RecordService.class);
        entries = servicesContext.getBean(TaskEntryRuntimeService.class);
        policies = servicesContext.getBean(TaskEntryPolicyService.class);
        var request = fixture.createRequest("entry");
        var fields = new ArrayList<>(request.fields());
        fields.add(fixture.field("secret", "secret", "TEXT", 1));
        fields.add(fixture.field("attachment", "attachment", "ATTACHMENT", 2));
        var design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        request.objectName(),
                                        null,
                                        request.tableName(),
                                        request.titleFieldKey(),
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                null,
                                List.of(),
                                List.of(),
                                List.of(
                                        new DataCenter.Detail(
                                                null,
                                                "items",
                                                "采购明细",
                                                "biz_" + fixture.prefix + "items",
                                                "ACTIVE",
                                                List.of(
                                                        new FieldDefinition(
                                                                "quantity",
                                                                null,
                                                                "quantity",
                                                                "数量",
                                                                "INTEGER",
                                                                null,
                                                                null,
                                                                null,
                                                                true,
                                                                false,
                                                                0)),
                                                Map.of(),
                                                List.of()))),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "任务入口测试"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        var version =
                servicesContext.getBean(DataObjectApi.class).getVersion(design.draft().id(), null);
        definition = version.definition();
        attachment =
                definition.fields().stream()
                        .filter(f -> f.code().equals("attachment"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        detailId = definition.details().getFirst().id();
        quantity = definition.details().getFirst().fields().getFirst().id();
        name =
                definition.fields().stream()
                        .filter(f -> f.code().equals("name"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        secret =
                definition.fields().stream()
                        .filter(f -> f.code().equals("secret"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        var form =
                resource(
                        "form",
                        "FORM",
                        new ApplicationUi.Form(
                                definition.objectId(),
                                List.of(
                                        new ApplicationUi.Node(
                                                "attachment_node",
                                                "FIELD",
                                                attachment,
                                                null,
                                                null,
                                                null,
                                                List.of()),
                                        new ApplicationUi.Node(
                                                "name_node",
                                                "FIELD",
                                                name,
                                                null,
                                                null,
                                                null,
                                                List.of()),
                                        new ApplicationUi.Node(
                                                "secret_node",
                                                "FIELD",
                                                secret,
                                                null,
                                                null,
                                                null,
                                                List.of())),
                                List.of(detailId)));
        var view =
                resource(
                        "view",
                        "VIEW",
                        new ApplicationUi.View(
                                definition.objectId(),
                                List.of(name, secret),
                                Map.of(),
                                null,
                                false,
                                10,
                                "form"));
        var resources =
                List.of(
                        form,
                        view,
                        entryResource("edit", "LIST", Set.of("READ", "CREATE", "UPDATE", "DELETE")),
                        entryResource("read", "LIST", Set.of("READ")),
                        entryResource("create", "FORM", Set.of("READ", "CREATE")));
        var created =
                LegacyTaskEntryFixtures.seed(
                        apps,
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "app",
                                "任务入口验证",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        version.objectId(),
                                                        version.versionNo(),
                                                        version.checksum())),
                                        resources)),
                        10001);
        app = created.application().id();
        grantApplicationObjects(app);
        apps.publish(
                new ApplicationCenter.Revision(app, created.application().revision(), "任务入口验证"),
                10001);
        authorize("edit", grant(Set.of("READ", "CREATE", "UPDATE", "DELETE")), true);
        authorize("read", grant(Set.of("READ")), true);
        authorize("create", grant(Set.of("READ", "CREATE")), true);
    }

    private ApplicationCenter.Resource resource(String id, String kind, Object config) {
        return new ApplicationCenter.Resource(
                id,
                kind,
                "entry_test_" + id,
                id,
                mapper.convertValue(
                        config,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {}));
    }

    private ObjectGrant grant(Set<String> actions) {
        return new ObjectGrant(
                definition.objectId(),
                actions,
                "OWN",
                Set.of(name, attachment),
                actions.size() == 1 ? Set.of() : Set.of(name, attachment),
                Set.of(detailId),
                actions.size() == 1 ? Set.of() : Set.of(detailId),
                Set.of(),
                Set.of());
    }

    private ApplicationCenter.Resource entryResource(String id, String mode, Set<String> actions) {
        return resource(
                id,
                "TASK_ENTRY",
                new TaskEntries.Config(
                        definition.objectId(),
                        mode.equals("LIST") ? "view" : null,
                        "form",
                        mode,
                        "测试办理",
                        "仅测试使用",
                        null,
                        1,
                        List.of(grant(actions))));
    }

    private void authorize(String id, ObjectGrant grant, boolean enabled) {
        var current = policies.get(app, id);
        policies.save(
                new TaskEntries.SavePolicy(
                        app,
                        id,
                        current.revision(),
                        enabled,
                        List.of(new Member("USER", Long.toString(employee), List.of(grant)))),
                10001);
    }

    private TaskEntries.Locator locator(String id) {
        return new TaskEntries.Locator(app, id, apps.published(app).versionNo());
    }

    private TaskEntries.Save save(
            String entry, String id, String revision, Map<String, Object> values, String key) {
        return new TaskEntries.Save(
                locator(entry),
                new ApplicationRecords.Save(
                        app,
                        definition.objectId(),
                        id,
                        revision,
                        values,
                        Map.of(),
                        Map.of(),
                        null,
                        "form",
                        key,
                        null));
    }

    private ApplicationRecords.Aggregate create(String entry, String value) {
        return entries.save(
                save(entry, null, null, Map.of(name, value), UUID.randomUUID().toString()),
                employee);
    }

    private TaskEntries.Query query(String entry) {
        return new TaskEntries.Query(
                locator(entry),
                new ApplicationRecords.Query(
                        app, definition.objectId(), 1, 10, null, Map.of(), null, false));
    }

    @Test
    void entryOnlyEmployeeCanMaintainOwnRowsWithoutObtainingApplicationAccess() {
        var row = create("edit", "本人登记");
        assertThat(entries.page(query("edit"), employee).getTotal()).isEqualTo(1);
        assertThat(
                        entries.get(
                                        new TaskEntries.Get(locator("edit"), row.record().id()),
                                        employee)
                                .record()
                                .values())
                .containsEntry(name, "本人登记");
        assertThatThrownBy(() -> records.model(app, definition.objectId(), employee))
                .hasMessageContaining("运行权限");
        assertThat(servicesContext.getBean(ApplicationRuntimeService.class).mine(employee))
                .noneMatch(a -> a.id().equals(app));
        assertThatThrownBy(() -> entries.context(locator("edit"), outsider))
                .hasMessageContaining("权限");
        var updated =
                entries.save(
                        save(
                                "edit",
                                row.record().id(),
                                row.record().revision(),
                                Map.of(name, "已经修改"),
                                UUID.randomUUID().toString()),
                        employee);
        entries.delete(
                new TaskEntries.Delete(
                        locator("edit"), updated.record().id(), updated.record().revision()),
                employee);
        assertThat(entries.page(query("edit"), employee).getTotal()).isZero();
        assertThat(servicesContext.getBean(TaskEntryRuntimeScope.class).current()).isNull();
    }

    @Test
    void readOnlyEntryAndHiddenFieldsCannotBorrowRightsFromWritableEntry() {
        var row = create("edit", "用于只读验证");
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        save(
                                                "read",
                                                row.record().id(),
                                                row.record().revision(),
                                                Map.of(name, "不能修改"),
                                                UUID.randomUUID().toString()),
                                        employee))
                .hasMessageContaining("权限");
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        save(
                                                "edit",
                                                row.record().id(),
                                                row.record().revision(),
                                                Map.of(secret, "不能注入"),
                                                UUID.randomUUID().toString()),
                                        employee))
                .hasMessageContaining("字段");
        assertThat(entries.context(locator("edit"), employee).model().object().fields())
                .noneMatch(f -> secret.equals(f.id()));
        assertThat(servicesContext.getBean(TaskEntryRuntimeScope.class).current()).isNull();
    }

    @Test
    void ownRangeAndObjectIdentityAreCheckedForDirectRequests() {
        var other =
                records.save(
                        new ApplicationRecords.Save(
                                app,
                                definition.objectId(),
                                null,
                                null,
                                Map.of(name, "管理员记录"),
                                null),
                        10001);
        assertThat(entries.page(query("edit"), employee).getTotal()).isZero();
        assertThatThrownBy(
                        () ->
                                entries.get(
                                        new TaskEntries.Get(locator("edit"), other.record().id()),
                                        employee))
                .isInstanceOf(RuntimeException.class);
        var forged =
                new TaskEntries.Query(
                        locator("edit"),
                        new ApplicationRecords.Query(
                                app, "9999999", 1, 10, null, Map.of(), null, false));
        assertThatThrownBy(() -> entries.page(forged, employee)).hasMessageContaining("切换");
    }

    @Test
    void directFormReadsOnlyOwnSubmissionWithoutOpeningListOrUpdate() {
        var saved = create("create", "快速填写");
        assertThatThrownBy(() -> entries.page(query("create"), employee))
                .hasMessageContaining("未开放");
        var current =
                entries.get(new TaskEntries.Get(locator("create"), saved.record().id()), employee);
        assertThat(current.record().values()).containsEntry(name, "快速填写");
        assertThat(current.record().permissions().actions()).containsExactly("READ");
        assertThat(current.record().permissions().writeFields()).isEmpty();
        var otherEntry = create("edit", "另一个入口");
        assertThatThrownBy(
                        () ->
                                entries.get(
                                        new TaskEntries.Get(
                                                locator("create"), otherEntry.record().id()),
                                        employee))
                .hasMessageContaining("本人通过此入口");
        assertThatThrownBy(
                        () ->
                                entries.get(
                                        new TaskEntries.Get(locator("create"), saved.record().id()),
                                        10001))
                .hasMessageContaining("本人通过此入口");
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        save(
                                                "create",
                                                saved.record().id(),
                                                saved.record().revision(),
                                                Map.of(name, "越权修改"),
                                                UUID.randomUUID().toString()),
                                        employee))
                .hasMessageContaining("未开放");
    }

    @Test
    void retriesAndUnchangedSavesDoNotDuplicateRowsOrBusinessEvents() {
        var key = UUID.randomUUID().toString();
        var command = save("edit", null, null, Map.of(name, "重试保护"), key);
        var first = entries.save(command, employee);
        assertThat(entries.save(command, employee).record().id()).isEqualTo(first.record().id());
        assertThat(
                        entries.receipt(new TaskEntries.Receipt(locator("edit"), key), employee)
                                .recordId())
                .isEqualTo(first.record().id());
        assertThat(
                        entries.receipt(new TaskEntries.Receipt(locator("create"), key), employee)
                                .recordId())
                .isNull();
        entries.save(
                save(
                        "edit",
                        first.record().id(),
                        first.record().revision(),
                        Map.of(name, "重试保护"),
                        UUID.randomUUID().toString()),
                employee);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_record_history WHERE"
                                        + " object_id=? AND operation <> 'BASELINE'",
                                Integer.class,
                                Long.valueOf(definition.objectId())))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT source_json->>'entryId' FROM public.nocode_record_history"
                                        + " WHERE object_id=? AND operation='CREATE'",
                                String.class,
                                Long.valueOf(definition.objectId())))
                .isEqualTo("edit");
    }

    @Test
    void revocationAndNewPublicationInvalidateExistingLocator() {
        var opened = locator("edit");
        authorize("edit", grant(Set.of("READ", "CREATE", "UPDATE", "DELETE")), false);
        assertThatThrownBy(() -> entries.context(opened, employee)).hasMessageContaining("停用");
        authorize("edit", grant(Set.of("READ", "CREATE", "UPDATE", "DELETE")), true);
        var detail = apps.get(app);
        apps.publish(
                new ApplicationCenter.Revision(app, detail.application().revision(), "验证旧窗口失效"),
                10001);
        assertThatThrownBy(() -> entries.context(opened, employee)).hasMessageContaining("配置已更新");
    }

    @Test
    void objectSharingCeilingStillAppliesToOwnerAndEntryMember() {
        var sharing = servicesContext.getBean(ObjectSharingService.class);
        var current = sharing.forApplication(app).getFirst();
        sharing.save(
                new ObjectSharing.Save(
                        definition.objectId(), app, current.revision(), null, "入口测试撤回共享"),
                10001);
        assertThatThrownBy(() -> entries.context(locator("edit"), employee))
                .hasMessageContaining("权限");
        assertThatThrownBy(() -> entries.context(locator("edit"), 10001))
                .hasMessageContaining("权限");
    }

    @AfterEach
    void cleanup() {
        if (taskRoot != null) {
            jdbc.update("DELETE FROM public.nocode_task_event WHERE root_id=?", taskRoot);
            jdbc.update("DELETE FROM public.nocode_task_instance WHERE root_id=?", taskRoot);
            taskRoot = null;
        }
        if (app != null) {
            long id = Long.parseLong(app);
            jdbc.update("DELETE FROM public.nocode_handling_request WHERE application_id=?", id);
            jdbc.update(
                    "DELETE FROM public.nocode_work_submission WHERE draft_id IN (SELECT id FROM"
                            + " public.nocode_work_draft WHERE resource_json->>'applicationId'=?)",
                    app);
            jdbc.update(
                    "DELETE FROM public.nocode_work_draft WHERE resource_json->>'applicationId'=?",
                    app);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant_log WHERE application_id=?",
                    id);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant WHERE application_id=?",
                    id);
            jdbc.update("DELETE FROM public.nocode_application_access WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_version WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        }
        if (fixture != null) fixture.clean();
    }

    @Test
    void entryOnlyTasksKeepInferredApplicationWithoutGrantingFullApplicationAccess() {
        com.lingan.ucp.module.system.api.user.AdminUserApi users =
                servicesContext.getBean(com.lingan.ucp.module.system.api.user.AdminUserApi.class);
        org.mockito.Mockito.when(users.getUser(org.mockito.Mockito.anyLong()))
                .thenAnswer(
                        call -> {
                            com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO user =
                                    new com.lingan.ucp.module.system.api.user.dto
                                            .AdminUserRespDTO();
                            user.setId(call.getArgument(0));
                            user.setNickname("任务入口权限验证成员");
                            user.setStatus(0);
                            return user;
                        });
        ApplicationCenter.Detail current = apps.get(app);
        List<ApplicationCenter.Resource> resources = new ArrayList<>(current.draft().resources());
        resources.add(
                new ApplicationCenter.Resource(
                        "task-page",
                        "PAGE",
                        "task_page",
                        "应用任务",
                        mapper.convertValue(
                                new ApplicationUi.Page(
                                        List.of(
                                                new ApplicationUi.Node(
                                                        "tasks", "TASKS", null, null, null, null,
                                                        List.of()))),
                                Map.class)));
        ApplicationCenter.Detail updated =
                apps.save(
                        new ApplicationCenter.Save(
                                app,
                                current.application().revision(),
                                current.application().code(),
                                current.application().name(),
                                current.application().description(),
                                current.application().icon(),
                                new ApplicationCenter.Definition(
                                        current.draft().objects(), resources)),
                        10001);
        apps.publish(
                new ApplicationCenter.Revision(app, updated.application().revision(), "入口归属验证"),
                10001);
        com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService tasks =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService.class);
        TaskCenter.NodeInput node =
                new TaskCenter.NodeInput(
                        null,
                        null,
                        "仅入口授权任务",
                        null,
                        employee,
                        null,
                        null,
                        null,
                        List.of(),
                        new TaskCenter.Binding(app, "form", "edit"),
                        null);
        assertThatThrownBy(
                        () ->
                                servicesContext
                                        .getBean(ApplicationRuntimePolicy.class)
                                        .requireEntry(app, employee))
                .hasMessageContaining("运行权限");
        assertThatThrownBy(
                        () ->
                                tasks.create(
                                        new TaskCenter.Create(
                                                node,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                UUID.randomUUID().toString(),
                                                null,
                                                null,
                                                app),
                                        employee))
                .hasMessageContaining("运行权限");
        TaskCenter.Detail root =
                tasks.create(
                        new TaskCenter.Create(
                                node,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                UUID.randomUUID().toString()),
                        employee);
        taskRoot = root.task().id();
        assertThat(root.task().applicationId()).isEqualTo(app);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT application_id FROM public.nocode_task_instance WHERE id=?",
                                String.class,
                                taskRoot))
                .isNull();
        TaskCenter.NodeInput child =
                new TaskCenter.NodeInput(
                        null, null, "入口任务拆分", null, 10001L, null, null, null, List.of(), null,
                        null);
        TaskCenter.Detail split =
                tasks.create(
                        new TaskCenter.Create(
                                child,
                                taskRoot,
                                null,
                                null,
                                null,
                                null,
                                null,
                                UUID.randomUUID().toString(),
                                null,
                                null,
                                app),
                        employee);
        assertThat(split.task().applicationId()).isEqualTo(app);
        TaskCenter.Detail before = tasks.detail(taskRoot, employee);
        List<TaskCenter.NodeInput> nodes =
                before.nodes().stream()
                        .map(
                                row ->
                                        new TaskCenter.NodeInput(
                                                row.id(),
                                                row.parentId(),
                                                row.title(),
                                                row.description(),
                                                row.assigneeId(),
                                                row.urgency(),
                                                row.priority(),
                                                row.schedule(),
                                                row.predecessorIds(),
                                                row.binding(),
                                                row.sharing(),
                                                row.entries()))
                        .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        nodes.add(
                new TaskCenter.NodeInput(
                        UUID.randomUUID().toString(),
                        taskRoot,
                        "入口任务编排新增",
                        null,
                        employee,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null));
        TaskCenter.Detail adjusted =
                tasks.adjust(
                        new TaskCenter.Adjust(taskRoot, before.task().revision(), nodes, "入口授权兼容"),
                        employee);
        assertThat(adjusted.nodes()).hasSize(3).allMatch(row -> app.equals(row.applicationId()));
        assertThat(
                        tasks.pageTasks(
                                        new TaskCenter.PageQuery(
                                                app, "task-page", "tasks", null, null),
                                        10001)
                                .getList())
                .extracting(TaskCenter.Row::id)
                .containsAll(adjusted.nodes().stream().map(TaskCenter.Row::id).toList());
        assertThatThrownBy(
                        () ->
                                tasks.pageTasks(
                                        new TaskCenter.PageQuery(
                                                app, "task-page", "tasks", null, null),
                                        employee))
                .hasMessageContaining("运行权限");
    }

    @Test
    void fixedEntryTaskKeepsItsVersionAfterPublishButNotRevokedPermissions() {
        com.lingan.ucp.module.system.api.user.AdminUserApi users =
                servicesContext.getBean(com.lingan.ucp.module.system.api.user.AdminUserApi.class);
        org.mockito.Mockito.when(users.getUser(employee))
                .thenAnswer(
                        invocation -> {
                            com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO user =
                                    new com.lingan.ucp.module.system.api.user.dto
                                            .AdminUserRespDTO();
                            user.setId(employee);
                            user.setNickname("入口办理测试人员");
                            user.setStatus(0);
                            return user;
                        });
        com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService tasks =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService.class);
        TaskCenter.NodeInput node =
                new TaskCenter.NodeInput(
                        null,
                        null,
                        "固定入口版本",
                        null,
                        employee,
                        null,
                        null,
                        null,
                        List.of(),
                        new TaskCenter.Binding(app, "form", "edit"),
                        null);
        TaskCenter.Detail created =
                tasks.create(
                        new TaskCenter.Create(
                                node,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                UUID.randomUUID().toString()),
                        employee);
        taskRoot = created.task().id();
        TaskEntries.Locator old = locator("edit");
        ApplicationCenter.Detail draft = apps.get(app);
        apps.publish(
                new ApplicationCenter.Revision(app, draft.application().revision(), "任务存续跨发布"),
                10001);
        assertThatThrownBy(() -> entries.context(old, employee)).hasMessageContaining("配置已更新");
        assertThat(tasks.form(taskRoot, employee).binding().resource().applicationVersion())
                .isEqualTo(old.version());
        TaskCenter.Detail running =
                tasks.transition(
                        new TaskCenter.Transition(
                                taskRoot,
                                created.task().revision(),
                                TaskCenter.Action.START,
                                null,
                                UUID.randomUUID().toString()),
                        employee);
        ApplicationRecords.Save input =
                save("edit", null, null, Map.of(name, "旧版任务继续保存"), UUID.randomUUID().toString())
                        .record();
        TaskCenter.FormContext saved =
                tasks.saveBusiness(
                        new TaskCenter.SaveBusiness(taskRoot, running.task().revision(), input),
                        employee);
        assertThat(saved.record().record().values()).containsEntry(name, "旧版任务继续保存");
        assertThat(saved.binding().resource().applicationVersion()).isEqualTo(old.version());
        com.lingan.ucp.nocode.runtime.service.taskcenter.TaskFormRuntimeService forms =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.runtime.service.taskcenter.TaskFormRuntimeService
                                .class);
        assertThatThrownBy(
                        () ->
                                forms.fill(
                                        new TaskForms.Fill(
                                                taskRoot,
                                                new FormFills.Query(
                                                        app,
                                                        definition.objectId(),
                                                        "other-form",
                                                        name,
                                                        null,
                                                        null,
                                                        saved.binding().recordId())),
                                        employee))
                .hasMessageContaining("当前任务固定表单");
        FieldRules.EvaluateQuery ruleQuery =
                new FieldRules.EvaluateQuery(
                        app,
                        definition.objectId(),
                        "form",
                        saved.binding().recordId(),
                        false,
                        Map.of(name, "任务固定版本求值"),
                        List.of(),
                        List.of(),
                        List.of());
        assertThat(forms.fieldRules(new TaskForms.FieldRules(taskRoot, ruleQuery), employee))
                .isNotNull();
        assertThatThrownBy(
                        () ->
                                forms.fieldRules(
                                        new TaskForms.FieldRules(taskRoot, ruleQuery), outsider))
                .hasMessageContaining("权限");
        assertThatThrownBy(() -> tasks.form(taskRoot, outsider)).hasMessageContaining("权限");
        authorize("edit", grant(Set.of("READ", "CREATE", "UPDATE", "DELETE")), false);
        assertThatThrownBy(() -> tasks.form(taskRoot, employee)).hasMessageContaining("停用");
    }

    @Test
    void draftsStayEntryScopedAndOnlySuccessfulSaveCreatesActivity() {
        var input = save("create", null, null, Map.of(name, "尚未提交"), UUID.randomUUID().toString());
        var draft = entries.saveDraft(input, employee);
        assertThat(
                        entries.activity(
                                        new TaskEntries.ActivityQuery(locator("create"), null),
                                        employee)
                                .items())
                .isEmpty();
        assertThat(entries.draft(locator("create"), employee).id()).isEqualTo(draft.id());
        assertThat(entries.draft(locator("edit"), employee)).isNull();
        assertThatThrownBy(
                        () ->
                                servicesContext
                                        .getBean(
                                                com.lingan.ucp.nocode.runtime.service.work
                                                        .WorkFormService.class)
                                        .getDraft(
                                                draft.id(),
                                                employee,
                                                com.lingan.ucp.nocode.api.work.WorkSourceRef
                                                        .PERSONAL))
                .isInstanceOf(RuntimeException.class);
        var command =
                new TaskEntries.Save(
                        input.entry(),
                        input.record(),
                        new TaskEntries.DraftRef(draft.id(), draft.revision()));
        var saved = entries.save(command, employee);
        assertThat(entries.save(command, employee).record().id()).isEqualTo(saved.record().id());
        assertThat(entries.draft(locator("create"), employee)).isNull();
        assertThat(
                        entries.activity(
                                        new TaskEntries.ActivityQuery(locator("create"), null),
                                        employee)
                                .items())
                .hasSize(1);
        assertThat(
                        entries.activity(
                                        new TaskEntries.ActivityQuery(locator("edit"), null),
                                        employee)
                                .items())
                .isEmpty();
        assertThat(
                        entries.activity(
                                        new TaskEntries.ActivityQuery(locator("create"), null),
                                        10001)
                                .items())
                .isEmpty();
    }

    @Test
    void portalDraftsArePersonalPagedAndKeepBlockedVersionsVisible() {
        var a =
                entries.saveDraft(
                        save(
                                "create",
                                null,
                                null,
                                Map.of(name, "表单草稿"),
                                UUID.randomUUID().toString()),
                        employee);
        var b =
                entries.saveDraft(
                        save(
                                "edit",
                                null,
                                null,
                                Map.of(name, "列表草稿"),
                                UUID.randomUUID().toString()),
                        employee);
        var page = entries.drafts(new TaskEntries.DraftQuery(null, 1), employee);
        assertThat(page.items()).hasSize(1);
        assertThat(page.items().getFirst().available()).isTrue();
        assertThat(page.before()).isNotNull();
        var next = entries.drafts(new TaskEntries.DraftQuery(page.before(), 1), employee);
        assertThat(next.items()).hasSize(1);
        assertThat(next.items().getFirst().id()).isNotEqualTo(page.items().getFirst().id());
        assertThat(next.before()).isNull();
        assertThat(entries.drafts(new TaskEntries.DraftQuery(null, 20), outsider).items())
                .isEmpty();
        authorize("create", grant(Set.of("READ", "CREATE")), false);
        var disabled =
                entries.drafts(new TaskEntries.DraftQuery(null, 20), employee).items().stream()
                        .filter(i -> i.id().equals(a.id()))
                        .findFirst()
                        .orElseThrow();
        assertThat(disabled.available()).isFalse();
        assertThat(disabled.blockedReason()).contains("停用");
        authorize("create", grant(Set.of("READ", "CREATE")), true);
        var appDetail = apps.get(app);
        apps.publish(
                new ApplicationCenter.Revision(app, appDetail.application().revision(), "草稿版本隔离"),
                10001);
        assertThat(entries.drafts(new TaskEntries.DraftQuery(null, 20), employee).items())
                .allSatisfy(
                        item -> {
                            assertThat(item.available()).isFalse();
                            assertThat(item.blockedReason()).contains("配置已更新");
                        });
        assertThatThrownBy(() -> entries.drafts(new TaskEntries.DraftQuery(null, 10000), employee))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void approvalSealsWholeDocumentAndAppliesDetailsFilesAndEntryHistoryOnce() {
        var bpm =
                servicesContext.getBean(
                        com.lingan.ucp.module.bpm.api.task.BpmProcessInstanceApi.class);
        var process = new com.lingan.ucp.module.bpm.api.definition.dto.BpmProcessDefinitionDTO();
        process.setId("document:1");
        process.setKey("document");
        process.setFormType(20);
        process.setFormCustomViewPath("/nocode-app/process-record");
        org.mockito.Mockito.when(
                        servicesContext
                                .getBean(
                                        com.lingan.ucp.module.bpm.api.definition
                                                .BpmProcessDefinitionApi.class)
                                .getProcessDefinition("document:1"))
                .thenReturn(process);
        org.mockito.Mockito.doAnswer(i -> UUID.randomUUID().toString())
                .when(bpm)
                .createProcessInstance(
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
        var head = designs.get(definition.objectId());
        var edited =
                designs.editPublished(
                        new DataCenter.Revision(
                                head.draft().id(), head.draft().lockVersion(), "整单审批"),
                        10001);
        var d = edited.draft();
        var settings = edited.settings();
        var rule = new BusinessHandling.Rule("APPROVAL", "document:1", null, Map.of());
        var updated =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        d.id(),
                                        d.lockVersion(),
                                        d.objectCode(),
                                        d.objectName(),
                                        d.description(),
                                        d.tableName(),
                                        d.titleFieldId(),
                                        d.fields(),
                                        List.of()),
                                new DataCenter.Settings(
                                        settings.icon(),
                                        settings.ownerId(),
                                        settings.organizationId(),
                                        settings.titleTemplate(),
                                        new DocumentPolicy(
                                                List.of(),
                                                null,
                                                new BusinessHandling.Policy(rule, rule))),
                                edited.fieldOptions(),
                                edited.relations(),
                                edited.indexes(),
                                edited.details()),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(d.id(), updated.draft().lockVersion(), null),
                        10001);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "整单审批专项"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        var file = new FileDO();
        file.setId(918274L);
        org.mockito.Mockito.when(
                        servicesContext.getBean(FileService.class).getFiles(List.of(918274L)))
                .thenReturn(List.of(file));
        var input =
                document(
                        "create",
                        null,
                        Map.of(name, "审批整单", attachment, List.of("918274")),
                        List.of(
                                new ApplicationRecords.Row(
                                        null,
                                        null,
                                        Map.of(quantity, "7"),
                                        null,
                                        Map.of(),
                                        "first-line"),
                                new ApplicationRecords.Row(
                                        null,
                                        null,
                                        Map.of(quantity, "3"),
                                        null,
                                        Map.of(),
                                        "second-line")));
        var pending = entries.submit(input, employee).request();
        assertThat(entries.page(query("edit"), employee).getTotal()).isZero();
        var handling =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.runtime.service.handling.BusinessHandlingService
                                .class);
        var material = handling.detail(pending.id(), null, employee).material();
        assertThat(material.values()).containsEntry(attachment, List.of("918274"));
        assertThat(material.details().get(detailId)).hasSize(2);
        var event = new com.lingan.ucp.module.bpm.api.event.BpmProcessInstanceStatusEvent(this);
        event.setId(pending.processInstanceId());
        event.setBusinessKey("nocode-handling:" + pending.id());
        event.setProcessDefinitionKey("document");
        event.setStatus(com.lingan.ucp.module.bpm.api.event.BpmProcessInstanceStatus.APPROVED);
        servicesContext.publishEvent(event);
        servicesContext.publishEvent(event);
        var applied = handling.detail(pending.id(), null, employee).request();
        assertThat(applied.status()).as(applied.error()).isEqualTo("APPROVED");
        var actual =
                entries.get(new TaskEntries.Get(locator("create"), applied.recordId()), employee);
        assertThat(actual.details().get(detailId))
                .extracting(r -> r.values().get(quantity))
                .containsExactly("7", "3");
        assertThat(actual.record().values()).containsEntry(attachment, List.of("918274"));
        assertThat(
                        entries.activity(
                                        new TaskEntries.ActivityQuery(locator("create"), null),
                                        employee)
                                .items())
                .hasSize(1);
    }

    @Test
    void draftConflictsRevocationAndHiddenFieldsAreEnforced() {
        var input = save("create", null, null, Map.of(name, "草稿验证"), UUID.randomUUID().toString());
        var draft = entries.saveDraft(input, employee);
        assertThatThrownBy(() -> entries.saveDraft(input, employee)).hasMessageContaining("先恢复");
        var hidden =
                save(
                        "create",
                        null,
                        null,
                        Map.of(secret, "不能暂存隐藏字段"),
                        UUID.randomUUID().toString());
        assertThatThrownBy(
                        () ->
                                entries.saveDraft(
                                        new TaskEntries.Save(
                                                hidden.entry(),
                                                hidden.record(),
                                                new TaskEntries.DraftRef(
                                                        draft.id(), draft.revision())),
                                        employee))
                .hasMessageContaining("字段");
        assertThatThrownBy(
                        () ->
                                entries.saveDraft(
                                        new TaskEntries.Save(
                                                input.entry(),
                                                input.record(),
                                                new TaskEntries.DraftRef(
                                                        draft.id(), draft.revision() + 1)),
                                        employee))
                .isInstanceOf(RuntimeException.class);
        authorize("create", grant(Set.of("READ", "CREATE")), false);
        assertThatThrownBy(() -> entries.draft(locator("create"), employee))
                .hasMessageContaining("停用");
        assertThatThrownBy(
                        () ->
                                entries.activity(
                                        new TaskEntries.ActivityQuery(locator("create"), null),
                                        employee))
                .hasMessageContaining("停用");
    }

    private TaskEntries.Save document(
            String entry,
            ApplicationRecords.Aggregate original,
            Map<String, Object> values,
            List<ApplicationRecords.Row> rows) {
        return new TaskEntries.Save(
                locator(entry),
                new ApplicationRecords.Save(
                        app,
                        definition.objectId(),
                        original == null ? null : original.record().id(),
                        original == null ? null : original.record().revision(),
                        values,
                        Map.of(detailId, rows),
                        Map.of(),
                        null,
                        "form",
                        UUID.randomUUID().toString(),
                        null));
    }

    @Test
    void detailFormConditionsRoundTripAndRejectTheWholeDocumentBeforeAnyWrite() {
        var current = apps.get(app);
        var resources = new ArrayList<>(current.draft().resources());
        var root = mapper.convertValue(resources.getFirst().config(), ApplicationUi.Form.class);
        var condition =
                new DocumentPolicy.Expression(
                        "GT",
                        null,
                        null,
                        null,
                        List.of(
                                new DocumentPolicy.Expression(
                                        "FIELD", quantity, null, null, List.of()),
                                new DocumentPolicy.Expression("VALUE", null, null, 10, List.of())));
        var node =
                new ApplicationUi.Node(
                        "quantity_node",
                        "FIELD",
                        quantity,
                        null,
                        null,
                        24,
                        List.of(),
                        null,
                        new ApplicationUi.FieldPresentation(
                                "数量",
                                null,
                                null,
                                false,
                                null,
                                new ApplicationUi.FieldBehavior(null, null, condition, false)));
        resources.set(
                0,
                resource(
                        "form",
                        "FORM",
                        new ApplicationUi.Form(
                                root.objectId(),
                                root.nodes(),
                                root.detailIds(),
                                root.options(),
                                Map.of(detailId, List.of(node)))));
        var savedApp =
                apps.save(
                        new ApplicationCenter.Save(
                                app,
                                current.application().revision(),
                                current.application().code(),
                                current.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        current.draft().objects(), resources)),
                        10001);
        apps.publish(
                new ApplicationCenter.Revision(app, savedApp.application().revision(), "明细逐行条件验证"),
                10001);
        var publishedForm =
                entries.context(locator("edit"), employee).resources().stream()
                        .filter(r -> r.id().equals("form"))
                        .findFirst()
                        .orElseThrow();
        assertThat(
                        mapper.convertValue(publishedForm.config(), ApplicationUi.Form.class)
                                .detailNodes())
                .containsKey(detailId);
        var first =
                new ApplicationRecords.Row(
                        null, null, Map.of(quantity, "2"), null, Map.of(), "row-a");
        var second =
                new ApplicationRecords.Row(
                        null, null, Map.of(quantity, "11"), null, Map.of(), "row-b");
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        document(
                                                "edit",
                                                null,
                                                Map.of(name, "不应保存"),
                                                List.of(first, second)),
                                        employee))
                .hasMessageContaining("只读");
        assertThat(entries.page(query("edit"), employee).getTotal()).isZero();
        var stored =
                entries.save(document("edit", null, Map.of(name, "已保存"), List.of(first)), employee);
        var row = stored.details().get(detailId).getFirst();
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        document(
                                                "edit",
                                                stored,
                                                Map.of(name, "也不应修改"),
                                                List.of(
                                                        new ApplicationRecords.Row(
                                                                row.id(),
                                                                row.revision(),
                                                                Map.of(quantity, "11"),
                                                                null,
                                                                Map.of(),
                                                                "row-a"))),
                                        employee))
                .hasMessageContaining("只读");
        var unchanged =
                entries.get(new TaskEntries.Get(locator("edit"), stored.record().id()), employee);
        assertThat(unchanged.record().values()).containsEntry(name, "已保存");
        assertThat(unchanged.details().get(detailId).getFirst().values())
                .containsEntry(quantity, "2");
    }

    @Test
    void documentDraftRestoresFilesAndDetailsAndCreatesOneWorkRecordOnlyAfterCommit() {
        var fileService = servicesContext.getBean(FileService.class);
        var file = new FileDO();
        file.setId(918273L);
        org.mockito.Mockito.when(fileService.getFiles(List.of(918273L))).thenReturn(List.of(file));
        var input =
                document(
                        "edit",
                        null,
                        Map.of(name, "采购历史登记", attachment, List.of("918273")),
                        List.of(
                                new ApplicationRecords.Row(
                                        null, null, Map.of(), null, Map.of(), "draft-line-1")));
        var draft = entries.saveDraft(input, employee);
        var restored = entries.draft(locator("edit"), employee);
        assertThat(restored.values()).containsEntry(attachment, List.of("918273"));
        assertThat(restored.details().get(detailId).getFirst().clientRowKey())
                .isEqualTo("draft-line-1");
        var ref = new TaskEntries.DraftRef(draft.id(), draft.revision());
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        new TaskEntries.Save(input.entry(), input.record(), ref),
                                        employee))
                .hasMessageContaining("必填");
        assertThat(
                        entries.activity(
                                        new TaskEntries.ActivityQuery(locator("edit"), null),
                                        employee)
                                .items())
                .isEmpty();
        assertThat(entries.page(query("edit"), employee).getTotal()).isZero();
        assertThat(entries.draft(locator("edit"), employee).id()).isEqualTo(draft.id());
        var complete =
                document(
                        "edit",
                        null,
                        input.record().values(),
                        List.of(
                                new ApplicationRecords.Row(
                                        null,
                                        null,
                                        Map.of(quantity, "3"),
                                        null,
                                        Map.of(),
                                        "draft-line-1")));
        var command = new TaskEntries.Save(complete.entry(), complete.record(), ref);
        var saved = entries.save(command, employee);
        assertThat(entries.save(command, employee).record().id()).isEqualTo(saved.record().id());
        assertThat(saved.details().get(detailId).getFirst().values()).containsEntry(quantity, "3");
        assertThat(entries.draft(locator("edit"), employee)).isNull();
        var work =
                entries.activity(new TaskEntries.ActivityQuery(locator("edit"), null), employee)
                        .items();
        assertThat(work).hasSize(1);
        assertThat(work.getFirst().recordId()).isEqualTo(saved.record().id());
        assertThat(work.getFirst().recordName()).isEqualTo("采购历史登记");
        assertThat(work.getFirst().operation()).isEqualTo("CREATE");
    }

    @Test
    void draftRejectsMissingFilesAndRevokedDetailsWithoutCreatingWork() {
        var missing =
                document(
                        "edit",
                        null,
                        Map.of(name, "无效附件", attachment, List.of("918274")),
                        List.of());
        assertThatThrownBy(() -> entries.saveDraft(missing, employee))
                .hasMessageContaining("附件不存在");
        var input =
                document(
                        "edit",
                        null,
                        Map.of(name, "明细权限"),
                        List.of(
                                new ApplicationRecords.Row(
                                        null,
                                        null,
                                        Map.of(quantity, "2"),
                                        null,
                                        Map.of(),
                                        "line-permission")));
        entries.saveDraft(input, employee);
        var g = grant(Set.of("READ", "CREATE", "UPDATE", "DELETE"));
        authorize(
                "edit",
                new ObjectGrant(
                        g.objectId(),
                        g.actions(),
                        g.scope(),
                        g.readFields(),
                        g.writeFields(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of()),
                true);
        assertThatThrownBy(() -> entries.draft(locator("edit"), employee))
                .hasMessageContaining("明细读取权限");
        assertThatThrownBy(() -> entries.save(input, employee))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        entries.activity(
                                        new TaskEntries.ActivityQuery(locator("edit"), null),
                                        employee)
                                .items())
                .isEmpty();
    }

    @Test
    void detailOnlyEditsReachBossHistoryAndCurrentDetailPermissionFiltersThem() throws Exception {
        var row =
                entries.save(
                        document(
                                "edit",
                                null,
                                Map.of(name, "采购明细跟踪"),
                                List.of(
                                        new ApplicationRecords.Row(
                                                null, null, Map.of(quantity, "2")))),
                        employee);
        var start = java.time.Instant.now().toString();
        var line = row.details().get(detailId).getFirst();
        var changed =
                entries.save(
                        document(
                                "edit",
                                row,
                                Map.of(name, "采购明细跟踪"),
                                List.of(
                                        new ApplicationRecords.Row(
                                                line.id(),
                                                line.revision(),
                                                Map.of(quantity, "5")))),
                        employee);
        // 同值保存和旧版本失败都不能让老板多看到一次工作。
        entries.save(
                document("edit", changed, Map.of(name, "采购明细跟踪"), changed.details().get(detailId)),
                employee);
        assertThatThrownBy(
                        () ->
                                entries.save(
                                        document("edit", row, Map.of(name, "冲突"), List.of()),
                                        employee))
                .isInstanceOf(RuntimeException.class);
        var histories =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.runtime.service.history.RecordHistoryService.class);
        var query = new RecordHistory.Query(start, null, app, null);
        var summary = histories.query(query, 10001);
        assertThat(summary.tables().getFirst().counts().update()).isEqualTo(1);
        var result =
                histories.detail(
                        new RecordHistory.DetailQuery(
                                new RecordHistory.Query(summary.start(), summary.end(), app, null),
                                summary.visibility(),
                                definition.objectId(),
                                row.record().id()),
                        10001);
        assertThat(result.row().changes()).hasSize(1);
        var detail = result.row().changes().getFirst().details().getFirst();
        assertThat(detail.before().get(line.id())).containsEntry(quantity, "2");
        assertThat(detail.after().get(line.id())).containsEntry(quantity, "5");
        assertThat(result.row().restored()).isFalse();
        var authorization = servicesContext.getBean(ApplicationAuthorizationService.class);
        var detailOnly =
                new ObjectGrant(
                        definition.objectId(),
                        Set.of("READ"),
                        "ALL",
                        Set.of(),
                        Set.of(),
                        Set.of(detailId),
                        Set.of(),
                        Set.of(),
                        Set.of());
        authorization.save(
                new ApplicationAuthorization.Save(
                        app,
                        authorization.get(app).revision(),
                        List.of(new Member("USER", Long.toString(outsider), List.of(detailOnly)))),
                10001);
        var visible = histories.query(query, outsider);
        assertThat(visible.tables().getFirst().counts().update()).isEqualTo(1);
        var detailOnlyResult =
                histories.detail(
                        new RecordHistory.DetailQuery(
                                new RecordHistory.Query(visible.start(), visible.end(), app, null),
                                visible.visibility(),
                                definition.objectId(),
                                row.record().id()),
                        outsider);
        assertThat(detailOnlyResult.row().changes().getFirst().after()).isEmpty();
        assertThat(detailOnlyResult.row().changes().getFirst().details()).hasSize(1);
        assertThat(
                        entries.activity(
                                        new TaskEntries.ActivityQuery(locator("edit"), null),
                                        employee)
                                .items())
                .hasSize(2);
        var event =
                mapper.readTree(
                        jdbc.queryForObject(
                                "SELECT"
                                    + " jsonb_build_object('before',before_json,'after',after_json)::text"
                                    + " FROM public.nocode_record_history WHERE object_id=? AND"
                                    + " operation='UPDATE' ORDER BY id DESC LIMIT 1",
                                String.class,
                                Long.valueOf(definition.objectId())));
        var projection =
                servicesContext.getBean(
                        com.lingan.ucp.nocode.runtime.service.history.HistoryDetailProjection
                                .class);
        var g = grant(Set.of("READ"));
        var hidden =
                new ObjectGrant(
                        g.objectId(),
                        g.actions(),
                        g.scope(),
                        g.readFields(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of(),
                        Set.of());
        assertThat(
                        projection.changes(
                                event,
                                List.of(
                                        new ApplicationRuntimePolicy.Access(
                                                employee, definition, List.of(hidden)))))
                .isEmpty();
        ((com.fasterxml.jackson.databind.node.ObjectNode) event.path("before")).remove("details");
        var unknown =
                projection.changes(
                        event,
                        List.of(
                                new ApplicationRuntimePolicy.Access(
                                        employee, definition, List.of(g))));
        assertThat(unknown.getFirst().beforeKnown()).isFalse();
    }

    @Test
    void completedWorkPagesKeepAllEventsWithoutDuplicatingOrExposingOtherEmployees() {
        for (int i = 0; i < 23; i++) create("edit", "采购登记 " + i);
        var first =
                entries.activity(new TaskEntries.ActivityQuery(locator("edit"), null), employee);
        assertThat(first.items()).hasSize(20);
        var second =
                entries.activity(
                        new TaskEntries.ActivityQuery(locator("edit"), first.next()), employee);
        assertThat(second.items()).hasSize(3);
        var ids =
                java.util.stream.Stream.concat(first.items().stream(), second.items().stream())
                        .map(TaskEntries.Activity::id)
                        .toList();
        assertThat(new HashSet<>(ids)).hasSize(23);
        assertThat(
                        entries.activity(
                                        new TaskEntries.ActivityQuery(locator("edit"), null), 10001)
                                .items())
                .isEmpty();
    }
}
