package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.common.dto.DynamicConditionDTO;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationAuthorization.*;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.richuang.os.nocode.runtime.service.application.ApplicationRuntimeService;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskCenterService;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskFormRuntimeService;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskWorkEntryService;

import org.junit.jupiter.api.*;
import org.mockito.Mockito;

import java.time.LocalDate;
import java.util.*;

/** 开发库真实记录与任务联合筛选；校验发布资源、授权与SQL分页，夹具按专属身份清理。 */
class TaskBusinessFilterIntegrationTest {
    private static final long OWNER = 10001L;
    private static final long WORKER = 21001L;
    private NocodeIntegrationSupport fixture;
    private TaskCenterService tasks;
    private ApplicationAuthorizationService access;
    private String app;
    private String object;
    private String name;
    private String amount;
    private String secret;
    private String reference;
    private String relatedObject;
    private String relatedName;
    private final Set<String> roots = new LinkedHashSet<>();
    private final Set<String> templates = new LinkedHashSet<>();
    private final Set<String> extraApplications = new LinkedHashSet<>();

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void closeEnvironment() {
        close();
    }

    @BeforeEach
    void setup() {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        tasks = servicesContext.getBean(TaskCenterService.class);
        access = servicesContext.getBean(ApplicationAuthorizationService.class);
        AdminUserApi users = servicesContext.getBean(AdminUserApi.class);
        Mockito.when(users.getUser(Mockito.anyLong()))
                .thenAnswer(
                        call -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(call.getArgument(0));
                            user.setNickname("筛选测试成员" + user.getId());
                            user.setStatus(0);
                            return user;
                        });
        DataObjectApi.PublishedObject target = publish(fixture.create("task_filter_target"));
        relatedObject = target.objectId();
        relatedName = field(target.definition(), "name");
        SaveObjectDraft base = fixture.createRequest("task_filter");
        List<FieldDefinition> fields = new ArrayList<>(base.fields());
        fields.add(fixture.field("amount", "amount", "INTEGER", 1));
        fields.add(fixture.field("secret", "secret", "TEXT", 2));
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        base.objectCode(),
                                        base.objectName(),
                                        null,
                                        base.tableName(),
                                        base.titleFieldKey(),
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                Map.of(),
                                List.of(
                                        new DataCenter.Relation(
                                                null,
                                                "customer",
                                                "关联客户",
                                                "REFERENCE",
                                                relatedObject,
                                                null,
                                                null,
                                                false,
                                                "RESTRICT")),
                                List.of(),
                                List.of()),
                        OWNER);
        DataObjectApi.PublishedObject published = publish(design.draft());
        DataCenter.Definition definition = published.definition();
        object = definition.objectId();
        name = field(definition, "name");
        amount = field(definition, "amount");
        secret = field(definition, "secret");
        reference = definition.relations().getFirst().fieldId();
        List<ApplicationUi.Node> formNodes =
                List.of(name, amount, secret, reference).stream()
                        .map(
                                id ->
                                        new ApplicationUi.Node(
                                                "field_" + id,
                                                "FIELD",
                                                id,
                                                null,
                                                null,
                                                null,
                                                List.of()))
                        .toList();
        ApplicationUi.Node taskNode =
                new ApplicationUi.Node("tasks", "TASKS", null, "form", null, null, List.of());
        ApplicationService applications = servicesContext.getBean(ApplicationService.class);
        ApplicationCenter.Detail created =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "tasks",
                                "任务字段筛选测试",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        object,
                                                        published.versionNo(),
                                                        published.checksum()),
                                                new ApplicationCenter.ObjectReference(
                                                        target.objectId(),
                                                        target.versionNo(),
                                                        target.checksum())),
                                        List.of(
                                                resource(
                                                        "form",
                                                        "FORM",
                                                        new ApplicationUi.Form(
                                                                object, formNodes, List.of())),
                                                resource(
                                                        "application_tasks",
                                                        "PAGE",
                                                        new ApplicationUi.Page(
                                                                List.of(taskNode(null, null)))),
                                                resource(
                                                        "table",
                                                        "PAGE",
                                                        new ApplicationUi.Page(List.of(taskNode))),
                                                resource(
                                                        "context",
                                                        "PAGE",
                                                        new ApplicationUi.Page(
                                                                List.of(taskNode), object, 2)),
                                                resource(
                                                        "project_form",
                                                        "FORM",
                                                        new ApplicationUi.Form(
                                                                relatedObject,
                                                                List.of(
                                                                        new ApplicationUi.Node(
                                                                                "project_name",
                                                                                "FIELD",
                                                                                relatedName,
                                                                                null,
                                                                                null,
                                                                                null,
                                                                                List.of())),
                                                                List.of())),
                                                resource(
                                                        "project",
                                                        "PAGE",
                                                        new ApplicationUi.Page(
                                                                List.of(
                                                                        taskNode(
                                                                                "project_form",
                                                                                new ApplicationUi
                                                                                        .TaskView(
                                                                                        "form",
                                                                                        List.of(
                                                                                                "title",
                                                                                                "business:"
                                                                                                        + amount),
                                                                                        condition(
                                                                                                amount,
                                                                                                "gte",
                                                                                                10),
                                                                                        null,
                                                                                        List.of(),
                                                                                        new ApplicationUi
                                                                                                .TaskSort(
                                                                                                "business:"
                                                                                                        + amount,
                                                                                                true)))),
                                                                relatedObject,
                                                                2))))),
                        OWNER);
        app = created.application().id();
        grantApplicationObjects(app);
        applications.publish(
                new ApplicationCenter.Revision(app, created.application().revision(), "字段筛选"),
                OWNER);
        authorize("ALL");
    }

    @Test
    void applicationOnlyTasksRemainSeparateFromIndependentTasksAndInheritAcrossGraphChanges() {
        ApplicationService apps = servicesContext.getBean(ApplicationService.class);
        ApplicationCenter.Detail current = apps.get(app);
        ApplicationCenter.Detail second =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "other",
                                "其他任务应用",
                                null,
                                null,
                                current.draft()),
                        OWNER);
        String other = second.application().id();
        extraApplications.add(other);
        grantApplicationObjects(other);
        apps.publish(
                new ApplicationCenter.Revision(other, second.application().revision(), "应用隔离"),
                OWNER);
        Detail standalone = createApplicationTask(null, null, null, null, OWNER);
        Detail scoped = createApplicationTask(app, null, null, null, OWNER);
        Detail elsewhere = createApplicationTask(other, null, null, null, OWNER);
        NodeInput crossBusiness =
                new NodeInput(
                        null,
                        null,
                        "跨应用业务内容",
                        null,
                        OWNER,
                        null,
                        null,
                        null,
                        List.of(),
                        new Binding(other, "form", null),
                        null);
        Detail explicit =
                tasks.create(
                        new Create(
                                crossBusiness,
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
                        OWNER);
        roots.add(explicit.task().rootId());
        NodeInput feedback =
                new NodeInput(
                        null,
                        null,
                        "仅有过程反馈",
                        null,
                        OWNER,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        List.of(
                                new TaskWorkEntries.Config(
                                        "feedback",
                                        "施工反馈",
                                        new Binding(app, "form", null),
                                        TaskWorkEntries.DataMode.INDEPENDENT,
                                        null,
                                        null,
                                        List.of(),
                                        List.of(),
                                        false,
                                        false)));
        Detail feedbackOnly =
                tasks.create(
                        new Create(
                                feedback,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                UUID.randomUUID().toString()),
                        OWNER);
        roots.add(feedbackOnly.task().rootId());
        assertThat(feedbackOnly.task().applicationId()).isNull();
        Detail independentlyBoundChild =
                tasks.create(
                        new Create(
                                crossBusiness,
                                standalone.task().id(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                UUID.randomUUID().toString()),
                        OWNER);
        assertThat(independentlyBoundChild.task().applicationId()).isNull();
        assertThat(standalone.task().applicationId()).isNull();
        assertThat(standalone.task().binding()).isNull();
        assertThat(standalone.task().project()).isNull();
        assertThat(scoped.task().applicationId()).isEqualTo(app);
        assertThat(scoped.task().business()).isNull();
        Detail child = createApplicationTask(null, scoped.task().id(), null, null, OWNER);
        assertThat(child.task().applicationId()).isEqualTo(app);
        Detail before = tasks.detail(scoped.task().id(), OWNER);
        List<NodeInput> inputs =
                before.nodes().stream()
                        .map(
                                row ->
                                        new NodeInput(
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
        String addedId = UUID.randomUUID().toString();
        inputs.add(simpleNode(addedId, scoped.task().id(), OWNER));
        Detail adjusted =
                tasks.adjust(
                        new Adjust(scoped.task().id(), before.task().revision(), inputs, "增加无业务节点"),
                        OWNER);
        assertThat(adjusted.nodes())
                .allMatch(row -> app.equals(row.applicationId()) && row.binding() == null);
        String template = template("应用任务模板");
        Detail templated = createApplicationTask(app, null, template, null, OWNER);
        assertThat(templated.nodes())
                .hasSize(2)
                .allMatch(row -> app.equals(row.applicationId()) && row.binding() == null);
        PageResult<Row> page =
                tasks.pageTasks(
                        new PageQuery(app, "application_tasks", "tasks", null, query(1, 100)),
                        OWNER);
        assertThat(page.getTotal()).isEqualTo(6);
        assertThat(page.getList())
                .extracting(Row::id)
                .contains(
                        scoped.task().id(),
                        child.task().id(),
                        addedId,
                        templated.task().id(),
                        explicit.task().id())
                .doesNotContain(
                        standalone.task().id(),
                        elsewhere.task().id(),
                        feedbackOnly.task().id(),
                        independentlyBoundChild.task().id());
        assertThat(
                        tasks.pageTasks(
                                        new PageQuery(
                                                other,
                                                "application_tasks",
                                                "tasks",
                                                null,
                                                query(1, 100)),
                                        OWNER)
                                .getList())
                .extracting(Row::id)
                .containsExactly(elsewhere.task().id());
        // 应用成员仍须参与任务；应用访问权不会扩大任务可见范围。
        assertThat(
                        tasks.pageTasks(
                                        new PageQuery(
                                                app,
                                                "application_tasks",
                                                "tasks",
                                                null,
                                                query(1, 100)),
                                        WORKER)
                                .getTotal())
                .isZero();
        assertThatThrownBy(
                        () ->
                                tasks.linkCandidates(
                                        new PageQuery(
                                                app,
                                                "application_tasks",
                                                "tasks",
                                                null,
                                                query(1, 100)),
                                        OWNER))
                .hasMessageContaining("当前业务记录");
        assertThatThrownBy(
                        () ->
                                tasks.pageTasks(
                                        new PageQuery(
                                                app,
                                                "application_tasks",
                                                "forged",
                                                null,
                                                query(1, 100)),
                                        OWNER))
                .hasMessageContaining("区块不存在");
        assertThatThrownBy(
                        () -> createApplicationTask(other, scoped.task().id(), null, null, OWNER))
                .hasMessageContaining("上级任务一致");
        assertThatThrownBy(() -> createApplicationTask(other, null, null, null, WORKER))
                .hasMessageContaining("运行权限");
        Detail workerRoot = createApplicationTask(app, null, null, null, WORKER);
        Row workerRow = workerRoot.task();
        NodeInput workerInput =
                new NodeInput(
                        workerRow.id(),
                        null,
                        workerRow.title(),
                        workerRow.description(),
                        workerRow.assigneeId(),
                        workerRow.urgency(),
                        workerRow.priority(),
                        workerRow.schedule(),
                        workerRow.predecessorIds(),
                        workerRow.binding(),
                        workerRow.sharing(),
                        workerRow.entries());
        access.save(new Save(app, access.get(app).revision(), List.of()), OWNER);
        assertThatThrownBy(
                        () ->
                                tasks.adjust(
                                        new Adjust(
                                                workerRoot.task().id(),
                                                workerRoot.task().revision(),
                                                List.of(
                                                        workerInput,
                                                        simpleNode(
                                                                UUID.randomUUID().toString(),
                                                                workerRoot.task().id(),
                                                                WORKER)),
                                                "撤权后新增节点"),
                                        WORKER))
                .hasMessageContaining("运行权限");
        assertThatThrownBy(
                        () ->
                                tasks.pageTasks(
                                        new PageQuery(
                                                app,
                                                "application_tasks",
                                                "tasks",
                                                null,
                                                query(1, 100)),
                                        WORKER))
                .hasMessageContaining("运行权限");
    }

    @Test
    void recordOnlyTasksAndLegacyBindingsKeepTheirApplicationAndRecordScopes() {
        Detail bound = create("历史业务绑定", 20, OWNER, WORKER);
        String recordId = bound.task().business().recordId();
        RecordRef record = new RecordRef(app, object, recordId, "施工业务");
        Detail root = createApplicationTask(null, null, null, record, OWNER);
        Detail child = createApplicationTask(null, root.task().id(), null, null, OWNER);
        assertThat(root.task().applicationId()).isEqualTo(app);
        assertThat(root.task().binding()).isNull();
        assertThat(child.task().project()).isEqualTo(record);
        PageResult<Row> context =
                tasks.pageTasks(
                        new PageQuery(app, "context", "tasks", recordId, query(1, 100)), OWNER);
        assertThat(context.getList())
                .extracting(Row::id)
                .contains(bound.task().id(), root.task().id(), child.task().id());
        jdbc.update(
                "UPDATE public.nocode_task_instance SET application_id=NULL WHERE root_id=?",
                bound.task().id());
        assertThat(tasks.detail(bound.task().id(), OWNER).task().applicationId()).isEqualTo(app);
        assertThat(
                        tasks.pageTasks(
                                        new PageQuery(
                                                app,
                                                "application_tasks",
                                                "tasks",
                                                null,
                                                query(1, 100)),
                                        OWNER)
                                .getList())
                .extracting(Row::id)
                .contains(bound.task().id(), root.task().id(), child.task().id());
        // 普通表单视图同时限定所属应用和业务对象，只返回具有主业务绑定的任务。
        assertThat(page(null, 1, 100, OWNER).getList())
                .extracting(Row::id)
                .contains(bound.task().id())
                .doesNotContain(root.task().id(), child.task().id());
    }

    @Test
    void formBoundApplicationPagesStayIsolatedWhileRecordPagesKeepExplicitCrossApplicationLinks() {
        Detail local = create("本应用业务任务", 20, OWNER, OWNER);
        ApplicationService apps = servicesContext.getBean(ApplicationService.class);
        ApplicationCenter.Detail copied =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "same_object_other_app",
                                "同对象的另一应用",
                                null,
                                null,
                                apps.get(app).draft()),
                        OWNER);
        String otherApp = copied.application().id();
        extraApplications.add(otherApp);
        grantApplicationObjects(otherApp);
        apps.publish(
                new ApplicationCenter.Revision(
                        otherApp, copied.application().revision(), "验证应用任务隔离"),
                OWNER);
        NodeInput otherNode =
                new NodeInput(
                        null,
                        null,
                        "另一应用业务任务",
                        null,
                        OWNER,
                        Urgency.NORMAL,
                        Priority.MEDIUM,
                        new Schedule(TimeMode.T0, null, 0, 1),
                        List.of(),
                        new Binding(otherApp, "form", null),
                        new Sharing(DataMode.INDEPENDENT, null, List.of()));
        ApplicationRecords.Save material =
                new ApplicationRecords.Save(
                        otherApp,
                        object,
                        null,
                        null,
                        Map.of(name, "另一应用的同对象数据", amount, 30),
                        Map.of(),
                        Map.of(),
                        null,
                        "form",
                        UUID.randomUUID().toString(),
                        null);
        Detail foreign =
                tasks.create(
                        new Create(
                                otherNode,
                                null,
                                null,
                                null,
                                null,
                                material,
                                null,
                                UUID.randomUUID().toString()),
                        OWNER);
        roots.add(foreign.task().rootId());
        assertThat(local.task().applicationId()).isEqualTo(app);
        assertThat(foreign.task().applicationId()).isEqualTo(otherApp);
        assertThat(local.task().business().object().objectId())
                .isEqualTo(foreign.task().business().object().objectId());

        PageResult<Row> localPage = page(null, 1, 100, OWNER);
        assertThat(localPage.getTotal()).isEqualTo(1);
        assertThat(localPage.getList()).extracting(Row::id).containsExactly(local.task().id());
        PageResult<Row> otherPage =
                tasks.pageTasks(
                        new PageQuery(otherApp, "table", "tasks", null, query(1, 100)), OWNER);
        assertThat(otherPage.getTotal()).isEqualTo(1);
        assertThat(otherPage.getList()).extracting(Row::id).containsExactly(foreign.task().id());
        assertThat(page(condition(amount, "gte", 10), 1, 100, OWNER).getList())
                .extracting(Row::id)
                .containsExactly(local.task().id());

        String localRecord = local.task().business().recordId();
        PageQuery recordPage = new PageQuery(app, "context", "tasks", localRecord, query(1, 100));
        assertThat(tasks.pageTasks(recordPage, OWNER).getList())
                .extracting(Row::id)
                .containsExactly(local.task().id());
        assertThat(tasks.linkCandidates(recordPage, OWNER).getList())
                .extracting(Row::id)
                .contains(foreign.task().id());
        link(foreign, "context", localRecord, true, UUID.randomUUID().toString());
        assertThat(tasks.pageTasks(recordPage, OWNER).getList())
                .extracting(Row::id)
                .containsExactlyInAnyOrder(local.task().id(), foreign.task().id());
        // 显式关联只影响该记录页面，不修改任务所属应用，也不扩大普通应用任务列表。
        assertThat(tasks.detail(foreign.task().id(), OWNER).task().applicationId())
                .isEqualTo(otherApp);
        assertThat(page(null, 1, 100, OWNER).getList())
                .extracting(Row::id)
                .containsExactly(local.task().id());
    }

    private NodeInput simpleNode(String id, String parent, long assignee) {
        return new NodeInput(
                id, parent, "应用任务", null, assignee, null, null, null, List.of(), null, null);
    }

    @Test
    void recordPageWithoutFormUsesPublishedObjectAndKeepsIndependentLinksAndBusinessFilters() {
        publishRecordTaskPages();
        Detail first = create("当前记录甲", 20, OWNER, WORKER);
        Detail second = create("当前记录乙", 5, OWNER, WORKER);
        String firstRecord = first.task().business().recordId();
        String secondRecord = second.task().business().recordId();
        Detail independent = createApplicationTask(null, null, null, null, OWNER);
        PageQuery page = new PageQuery(app, "record_tasks", "tasks", firstRecord, query(1, 100));
        PageQuery other = new PageQuery(app, "record_tasks", "tasks", secondRecord, query(1, 100));
        assertThat(tasks.pageTasks(page, OWNER).getList())
                .extracting(Row::id)
                .containsExactly(first.task().id());
        assertThat(tasks.linkCandidates(page, OWNER).getList())
                .extracting(Row::id)
                .contains(independent.task().id(), second.task().id())
                .doesNotContain(first.task().id());
        link(independent, "record_tasks", firstRecord, true, UUID.randomUUID().toString());
        // 记录关联可指向独立任务，不能额外交集应用归属而丢失已关联任务。
        assertThat(tasks.detail(independent.task().id(), OWNER).task().applicationId()).isNull();
        assertThat(tasks.pageTasks(page, OWNER).getList())
                .extracting(Row::id)
                .containsExactlyInAnyOrder(first.task().id(), independent.task().id());
        assertThat(tasks.pageTasks(other, OWNER).getList())
                .extracting(Row::id)
                .containsExactly(second.task().id());
        assertThat(tasks.linkCandidates(page, OWNER).getList())
                .extracting(Row::id)
                .doesNotContain(independent.task().id());
        assertThat(
                        tasks.pageTasks(
                                        new PageQuery(
                                                app,
                                                "context",
                                                "tasks",
                                                firstRecord,
                                                query(1, 100)),
                                        OWNER)
                                .getList())
                .extracting(Row::id)
                .containsExactlyInAnyOrder(first.task().id(), independent.task().id());
        link(independent, "record_tasks", firstRecord, false, UUID.randomUUID().toString());
        assertThat(tasks.pageTasks(page, OWNER).getTotal()).isEqualTo(1);
        assertThat(tasks.linkCandidates(page, OWNER).getList())
                .extracting(Row::id)
                .contains(independent.task().id());
        assertThatThrownBy(
                        () ->
                                tasks.pageTasks(
                                        new PageQuery(
                                                app,
                                                "record_tasks",
                                                "tasks",
                                                firstRecord,
                                                query(1, 100),
                                                condition(amount, "gte", 10)),
                                        OWNER))
                .hasMessageContaining("配置业务表单");
        String project = relatedRecord("无表单项目上下文");
        link(first, "project_tasks", project, true, UUID.randomUUID().toString());
        assertThatThrownBy(
                        () ->
                                link(
                                        second,
                                        "project_tasks",
                                        project,
                                        true,
                                        UUID.randomUUID().toString()))
                .hasMessageContaining("当前发布任务视图");
        PageQuery filtered = new PageQuery(app, "project_tasks", "tasks", project, query(1, 100));
        assertThat(tasks.pageTasks(filtered, OWNER).getList())
                .extracting(Row::id)
                .containsExactly(first.task().id());
        assertThat(
                        tasks.pageTasks(
                                        new PageQuery(
                                                app,
                                                "project_tasks",
                                                "tasks",
                                                project,
                                                query(1, 100),
                                                condition(amount, "lt", 10)),
                                        OWNER)
                                .getTotal())
                .isZero();
    }

    @Test
    void recordPageWithoutFormStillRequiresReadablePublishedRecordForListsAndLinks() {
        publishRecordTaskPages();
        Detail record = create("权限记录", 20, OWNER, WORKER);
        String recordId = record.task().business().recordId();
        PageQuery page = new PageQuery(app, "record_tasks", "tasks", recordId, query(1, 100));
        // 只读字段已裁剪为 name/amount，未授权 secret 不影响当前记录任务范围。
        assertThat(tasks.pageTasks(page, WORKER).getList())
                .extracting(Row::id)
                .contains(record.task().id());
        assertThatThrownBy(
                        () ->
                                tasks.pageTasks(
                                        new PageQuery(
                                                app, "record_tasks", "tasks", null, query(1, 100)),
                                        OWNER))
                .hasMessageContaining("当前业务记录");
        assertThatThrownBy(
                        () ->
                                tasks.linkCandidates(
                                        new PageQuery(
                                                app, "record_tasks", "tasks", null, query(1, 100)),
                                        OWNER))
                .hasMessageContaining("当前业务记录");
        assertThatThrownBy(
                        () ->
                                tasks.pageTasks(
                                        new PageQuery(
                                                app,
                                                "record_tasks",
                                                "tasks",
                                                UUID.randomUUID().toString(),
                                                query(1, 100)),
                                        OWNER))
                .hasMessageContaining("记录不存在或不可访问");
        authorize("OWN");
        assertThatThrownBy(() -> tasks.pageTasks(page, WORKER)).hasMessageContaining("READ权限");
        assertThatThrownBy(() -> tasks.linkCandidates(page, WORKER)).hasMessageContaining("READ权限");
        assertThatThrownBy(
                        () ->
                                tasks.linkRecord(
                                        new LinkTask(
                                                new RecordContext(
                                                        app, "record_tasks", "tasks", recordId),
                                                record.task().id(),
                                                record.task().revision(),
                                                true,
                                                UUID.randomUUID().toString()),
                                        WORKER))
                .hasMessageContaining("READ权限");
        access.save(
                new Save(
                        app,
                        access.get(app).revision(),
                        List.of(
                                new ApplicationAuthorization.Member(
                                        "USER",
                                        Long.toString(WORKER),
                                        List.of(
                                                new ObjectGrant(
                                                        relatedObject,
                                                        Set.of("READ"),
                                                        "ALL",
                                                        Set.of(relatedName),
                                                        Set.of(),
                                                        Set.of(),
                                                        Set.of()))))),
                OWNER);
        assertThatThrownBy(() -> tasks.pageTasks(page, WORKER)).hasMessageContaining("查看权限");
        assertThatThrownBy(() -> tasks.linkCandidates(page, WORKER)).hasMessageContaining("查看权限");
        access.save(new Save(app, access.get(app).revision(), List.of()), OWNER);
        assertThatThrownBy(() -> tasks.pageTasks(page, WORKER)).hasMessageContaining("运行权限");
    }

    private void publishRecordTaskPages() {
        ApplicationService apps = servicesContext.getBean(ApplicationService.class);
        ApplicationCenter.Detail current = apps.get(app);
        List<ApplicationCenter.Resource> resources = new ArrayList<>(current.draft().resources());
        resources.add(
                resource(
                        "record_tasks",
                        "PAGE",
                        new ApplicationUi.Page(List.of(taskNode(null, null)), object, 2)));
        resources.add(
                resource(
                        "project_tasks",
                        "PAGE",
                        new ApplicationUi.Page(
                                List.of(
                                        taskNode(
                                                null,
                                                new ApplicationUi.TaskView(
                                                        "form",
                                                        List.of("title", "business:" + amount),
                                                        condition(amount, "gte", 10),
                                                        null,
                                                        List.of(),
                                                        new ApplicationUi.TaskSort(
                                                                "business:" + amount, true)))),
                                relatedObject,
                                2)));
        ApplicationCenter.Detail draft =
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
                        OWNER);
        apps.publish(
                new ApplicationCenter.Revision(
                        app, draft.application().revision(), "记录任务直接沿用页面上下文"),
                OWNER);
    }

    private Detail createApplicationTask(
            String application, String parent, String template, RecordRef record, long actor) {
        Detail detail =
                tasks.create(
                        new Create(
                                simpleNode(null, null, actor),
                                parent,
                                template,
                                null,
                                record,
                                null,
                                null,
                                UUID.randomUUID().toString(),
                                null,
                                null,
                                application),
                        actor);
        roots.add(detail.task().rootId());
        return detail;
    }

    @Test
    void businessReceiptSurvivesRereadAndRejectsForeignTasksAndCurrentRevocation() {
        TaskFormRuntimeService forms = servicesContext.getBean(TaskFormRuntimeService.class);
        Detail detail = create("保存回执", 10, OWNER, WORKER);
        Row task =
                tasks.transition(
                                new Transition(
                                        detail.task().id(),
                                        detail.task().revision(),
                                        Action.START,
                                        null,
                                        UUID.randomUUID().toString()),
                                WORKER)
                        .task();
        FormContext current = tasks.form(task.id(), WORKER);
        String key = UUID.randomUUID().toString();
        SaveBusiness command =
                new SaveBusiness(
                        task.id(),
                        task.revision(),
                        new ApplicationRecords.Save(
                                app,
                                object,
                                current.record().record().id(),
                                current.record().record().revision(),
                                Map.of(name, "已提交的回执材料"),
                                Map.of(),
                                Map.of(),
                                null,
                                "form",
                                key,
                                null));
        tasks.saveBusiness(command, WORKER);
        for (int attempt = 0; attempt < 2; attempt++) {
            BusinessHandling.Result receipt =
                    forms.receipt(new TaskForms.Receipt(task.id(), key), WORKER);
            assertThat(receipt.outcome()).isEqualTo("EFFECTIVE");
            assertThat(receipt.result().record().values())
                    .containsEntry(name, "已提交的回执材料")
                    .doesNotContainKey(secret);
        }
        assertThat(tasks.saveBusiness(command, WORKER).record().record().values())
                .containsEntry(name, "已提交的回执材料");
        Detail other = create("其他任务", 10, WORKER, WORKER);
        assertThatThrownBy(
                        () -> forms.receipt(new TaskForms.Receipt(other.task().id(), key), WORKER))
                .hasMessageContaining("不属于");
        assertThat(
                        forms.receipt(
                                new TaskForms.Receipt(task.id(), UUID.randomUUID().toString()),
                                WORKER))
                .isNull();
        assertThat(forms.receipt(new TaskForms.Receipt(task.id(), key), OWNER)).isNull();
        assertThatThrownBy(() -> forms.createReceipt(new TaskForms.CreateReceipt(key), WORKER))
                .hasMessageContaining("不能用于恢复新发起");
        access.save(new Save(app, access.get(app).revision(), List.of()), OWNER);
        assertThatThrownBy(() -> forms.receipt(new TaskForms.Receipt(task.id(), key), WORKER))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void initialBusinessReceiptRecoversTaskWithoutIdAndKeepsEqualCreateAndSaveKeyIdempotent() {
        TaskFormRuntimeService forms = servicesContext.getBean(TaskFormRuntimeService.class);
        String key = UUID.randomUUID().toString();
        ApplicationRecords.Save input =
                new ApplicationRecords.Save(
                        app,
                        object,
                        null,
                        null,
                        Map.of(name, "初次发起回执", amount, 12),
                        Map.of(),
                        Map.of(),
                        null,
                        "form",
                        key,
                        null);
        NodeInput node =
                new NodeInput(
                        null,
                        null,
                        "初次发起回执",
                        null,
                        WORKER,
                        null,
                        null,
                        null,
                        List.of(),
                        new Binding(app, "form", null),
                        null);
        Create command = new Create(node, null, null, null, null, input, null, key);
        Detail detail = tasks.create(command, WORKER);
        roots.add(detail.task().rootId());
        assertThat(tasks.create(command, WORKER).task().id()).isEqualTo(detail.task().id());
        TaskForms.CreatedReceipt recovered =
                forms.createReceipt(new TaskForms.CreateReceipt(key), WORKER);
        assertThat(recovered.taskId()).isEqualTo(detail.task().id());
        assertThat(recovered.handling().outcome()).isEqualTo("EFFECTIVE");
        assertThat(recovered.handling().result().record().id())
                .isEqualTo(detail.task().business().recordId());
        assertThatThrownBy(
                        () ->
                                tasks.create(
                                        new Create(
                                                node,
                                                null,
                                                null,
                                                null,
                                                null,
                                                input,
                                                null,
                                                UUID.randomUUID().toString()),
                                        WORKER))
                .hasMessageContaining("已属于已有任务");
        assertThatThrownBy(() -> forms.createReceipt(new TaskForms.CreateReceipt(key), OWNER))
                .hasMessageContaining("无法确认");
        assertThatThrownBy(
                        () ->
                                forms.createReceipt(
                                        new TaskForms.CreateReceipt(UUID.randomUUID().toString()),
                                        WORKER))
                .hasMessageContaining("不要重复新建");
        assertThat(tasks.entryOptions(WORKER))
                .extracting(EntryOption::value)
                .contains(app + ":FORM:form");
    }

    @Test
    void feedbackRecordContextFindsRealContributorsWithoutAssociatingViewOnlySharedNodes() {
        TaskWorkEntryService entries = servicesContext.getBean(TaskWorkEntryService.class);
        String marker = "反馈反查-" + UUID.randomUUID();
        TaskWorkEntries.Config config =
                new TaskWorkEntries.Config(
                        "feedback",
                        "业务反馈",
                        new Binding(app, "form", null),
                        TaskWorkEntries.DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false);
        Detail root =
                tasks.create(
                        new Create(
                                new NodeInput(
                                        null,
                                        null,
                                        marker,
                                        null,
                                        OWNER,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        null,
                                        List.of(config)),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                UUID.randomUUID().toString()),
                        OWNER);
        roots.add(root.task().id());
        Detail child =
                tasks.create(
                        new Create(
                                new NodeInput(
                                        null,
                                        null,
                                        marker + "分工",
                                        null,
                                        OWNER,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        null),
                                root.task().id(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                UUID.randomUUID().toString()),
                        OWNER);
        Row rootRow = tasks.detail(root.task().id(), OWNER).task();
        tasks.transition(
                new Transition(
                        rootRow.id(),
                        rootRow.revision(),
                        Action.START,
                        null,
                        UUID.randomUUID().toString()),
                OWNER);
        TaskWorkEntries.Saved saved =
                entries.save(
                        new TaskWorkEntries.Save(
                                rootRow.id(),
                                "feedback",
                                null,
                                new ApplicationRecords.Save(
                                        app,
                                        object,
                                        null,
                                        null,
                                        Map.of(name, "来自任务的反馈", amount, 12),
                                        Map.of(),
                                        Map.of(),
                                        null,
                                        "form",
                                        UUID.randomUUID().toString(),
                                        null)),
                        OWNER);
        String recordId = saved.handling().result().record().id();
        PageQuery recordPage = new PageQuery(app, "context", "tasks", recordId, query(1, 100));
        assertThat(tasks.pageTasks(recordPage, OWNER).getList())
                .extracting(Row::id)
                .containsExactly(rootRow.id());
        assertThat(
                        entries.page(
                                        new TaskWorkEntries.Query(
                                                child.task().id(),
                                                "feedback",
                                                false,
                                                false,
                                                1,
                                                20,
                                                null),
                                        OWNER)
                                .getList())
                .hasSize(1);
        assertThat(tasks.pageTasks(recordPage, OWNER).getList())
                .extracting(Row::id)
                .containsExactly(rootRow.id());
        assertThat(tasks.pageTasks(recordPage, WORKER).getList()).isEmpty();
        tasks.transition(
                new Transition(
                        child.task().id(),
                        child.task().revision(),
                        Action.START,
                        null,
                        UUID.randomUUID().toString()),
                OWNER);
        entries.link(
                new TaskWorkEntries.Link(
                        child.task().id(), "feedback", recordId, UUID.randomUUID().toString()),
                OWNER);
        assertThat(tasks.pageTasks(recordPage, OWNER).getList())
                .extracting(Row::id)
                .containsExactlyInAnyOrder(rootRow.id(), child.task().id());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_task_record_link WHERE task_id"
                                        + " IN (?,?)",
                                Integer.class,
                                rootRow.id(),
                                child.task().id()))
                .isZero();
    }

    @Test
    void multipleFeedbackEntriesAndInheritedChildrenParticipateInServerSideEntryFiltering() {
        String marker = "入口筛选-" + UUID.randomUUID();
        TaskWorkEntries.Config feedback =
                new TaskWorkEntries.Config(
                        "feedback",
                        "业务反馈",
                        new Binding(app, "form", null),
                        TaskWorkEntries.DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        false,
                        false);
        NodeInput rootInput =
                new NodeInput(
                        null,
                        null,
                        marker + "根",
                        null,
                        WORKER,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        List.of(feedback));
        Detail root =
                tasks.create(
                        new Create(
                                rootInput,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                UUID.randomUUID().toString()),
                        OWNER);
        roots.add(root.task().id());
        Detail child =
                tasks.create(
                        new Create(
                                new NodeInput(
                                        null,
                                        null,
                                        marker + "子",
                                        null,
                                        WORKER,
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        null),
                                root.task().id(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                UUID.randomUUID().toString()),
                        OWNER);
        String selector = app + ":FORM:form";
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
                        selector,
                        null,
                        null,
                        1,
                        1);
        PageResult<Row> first = tasks.page(query, WORKER);
        PageResult<Row> second =
                tasks.page(
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
                                selector,
                                null,
                                null,
                                2,
                                1),
                        WORKER);
        assertThat(first.getTotal()).isEqualTo(2);
        assertThat(second.getTotal()).isEqualTo(2);
        Set<String> ids = new HashSet<>();
        first.getList().forEach(row -> ids.add(row.id()));
        second.getList().forEach(row -> ids.add(row.id()));
        assertThat(ids).containsExactlyInAnyOrder(root.task().id(), child.task().id());
        assertThat(
                        tasks.page(
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
                                                "nonexistent:FORM:form",
                                                null,
                                                null,
                                                1,
                                                20),
                                        WORKER)
                                .getTotal())
                .isZero();
        assertThat(tasks.entryOptions(WORKER)).extracting(EntryOption::value).contains(selector);
        assertThat(tasks.entryOptions(30001L))
                .extracting(EntryOption::value)
                .doesNotContain(selector);
        access.save(new Save(app, access.get(app).revision(), List.of()), OWNER);
        assertThat(tasks.entryOptions(WORKER))
                .extracting(EntryOption::value)
                .doesNotContain(selector);
    }

    @Test
    void nestedBusinessConditionsFilterCountAndEveryPageOnDatabase() {
        Detail first = create("匹配甲", 20, OWNER, WORKER);
        Detail second = create("匹配乙", 30, OWNER, WORKER);
        create("匹配金额不足", 5, OWNER, WORKER);
        create("别类业务", 40, OWNER, WORKER);
        DynamicConditionDTO conditions =
                conditions(
                        Map.of(
                                "logic",
                                "AND",
                                "items",
                                List.of(
                                        item(name, "like", "匹配"),
                                        Map.of(
                                                "type",
                                                "group",
                                                "groupLogic",
                                                "OR",
                                                "groupItems",
                                                List.of(
                                                        item(amount, "eq", 20),
                                                        item(amount, "gte", 30))))));
        PageResult<Row> pageOne = page(conditions, 1, 1, OWNER);
        PageResult<Row> pageTwo = page(conditions, 2, 1, OWNER);
        assertThat(pageOne.getTotal()).isEqualTo(2);
        assertThat(pageTwo.getTotal()).isEqualTo(2);
        assertThat(pageOne.getList()).hasSize(1);
        assertThat(pageTwo.getList()).hasSize(1);
        assertThat(List.of(pageOne.getList().getFirst().id(), pageTwo.getList().getFirst().id()))
                .containsExactlyInAnyOrder(first.task().id(), second.task().id());
        assertThat(page(conditions, 3, 1, OWNER).getList()).isEmpty();
    }

    @Test
    void noMatchDoesNotFallBackAndFilterValuesStayBoundParameters() {
        create("正常业务", 10, OWNER, WORKER);
        PageResult<Row> result = page(condition(name, "eq", "' OR 1=1 --"), 1, 10, OWNER);
        assertThat(result.getTotal()).isZero();
        assertThat(result.getList()).isEmpty();
        assertThat(page(null, 1, 10, OWNER).getTotal()).isEqualTo(1);
    }

    @Test
    void unknownAndUnreadableFieldsCannotBeUsedAsInferenceFilters() {
        create("可读名称", 10, OWNER, WORKER);
        assertThatThrownBy(() -> page(condition("name) OR TRUE --", "eq", "x"), 1, 10, OWNER))
                .hasMessageContaining("无权查询");
        assertThatThrownBy(() -> page(condition(secret, "eq", "私密内容"), 1, 10, WORKER))
                .hasMessageContaining("无权查询");
        assertThat(page(condition(name, "eq", "可读名称"), 1, 10, WORKER).getTotal()).isEqualTo(1);
    }

    @Test
    void businessOwnScopeRestrictsMatchesDespiteTaskParticipation() {
        Detail owners = create("同一类别", 10, OWNER, WORKER);
        Detail own = create("同一类别", 20, WORKER, WORKER);
        authorize("OWN");
        // 两条任务均分配给员工，但业务记录只允许员工读取自己创建的这一条。
        assertThat(page(null, 1, 10, WORKER).getTotal()).isEqualTo(2);
        PageResult<Row> matches = page(condition(name, "eq", "同一类别"), 1, 10, WORKER);
        assertThat(matches.getTotal()).isEqualTo(1);
        assertThat(matches.getList()).extracting(Row::id).containsExactly(own.task().id());
        assertThat(matches.getList()).extracting(Row::id).doesNotContain(owners.task().id());
    }

    @Test
    void applicationRevocationAndForgedNodesAreRejectedImmediately() {
        create("待撤权", 10, OWNER, WORKER);
        assertThat(page(condition(name, "eq", "待撤权"), 1, 10, WORKER).getTotal()).isEqualTo(1);
        access.save(new Save(app, access.get(app).revision(), List.of()), OWNER);
        assertThatThrownBy(() -> page(condition(name, "eq", "待撤权"), 1, 10, WORKER))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                tasks.pageTasks(
                                        new PageQuery(
                                                app,
                                                "table",
                                                "fake",
                                                null,
                                                query(1, 10),
                                                condition(name, "eq", "待撤权")),
                                        OWNER))
                .hasMessageContaining("任务区块不存在");
    }

    @Test
    void currentRecordContextDoesNotAcceptBusinessTableFiltersOrMissingRecord() {
        Detail task = create("当前记录", 10, OWNER, WORKER);
        assertThatThrownBy(
                        () ->
                                tasks.pageTasks(
                                        new PageQuery(app, "context", "tasks", null, query(1, 10)),
                                        OWNER))
                .hasMessageContaining("当前业务记录");
        assertThatThrownBy(
                        () ->
                                tasks.pageTasks(
                                        new PageQuery(
                                                app,
                                                "context",
                                                "tasks",
                                                task.task().business().recordId(),
                                                query(1, 10),
                                                condition(name, "eq", "当前记录")),
                                        OWNER))
                .hasMessageContaining("配置业务表单");
        assertThat(
                        tasks.pageTasks(
                                        new PageQuery(
                                                app,
                                                "context",
                                                "tasks",
                                                task.task().business().recordId(),
                                                query(1, 10)),
                                        OWNER)
                                .getTotal())
                .isEqualTo(1);
    }

    @Test
    void singleReferenceSelectsTasksByRelatedRecordIdWithoutMatchingOtherCustomers() {
        String selected = relatedRecord("客户甲");
        String other = relatedRecord("客户乙");
        String unused = relatedRecord("无任务客户");
        Detail first = create("施工登记甲", 10, OWNER, WORKER, Map.of(reference, selected));
        Detail second = create("施工登记乙", 20, OWNER, WORKER, Map.of(reference, other));
        PageResult<Row> matching = page(condition(reference, "eq", selected), 1, 10, OWNER);
        assertThat(matching.getTotal()).isEqualTo(1);
        assertThat(matching.getList()).extracting(Row::id).containsExactly(first.task().id());
        assertThat(page(condition(reference, "eq", other), 1, 10, OWNER).getList())
                .extracting(Row::id)
                .containsExactly(second.task().id());
        PageResult<Row> noMatch = page(condition(reference, "eq", unused), 1, 10, OWNER);
        assertThat(noMatch.getTotal()).isZero();
        assertThat(noMatch.getList()).isEmpty();
    }

    @Test
    void projectContextAndPublishedBusinessFiltersSharePagingAndCannotBeClearedByClient() {
        String projectA = relatedRecord("项目A");
        String projectB = relatedRecord("项目B");
        Detail low = create("小额工程", 5, OWNER, WORKER);
        Detail first = create("工程甲", 10, OWNER, WORKER);
        Detail second = create("工程乙", 30, OWNER, WORKER);
        Detail other = create("其他项目工程", 50, OWNER, WORKER);
        assertThatThrownBy(() -> link(low, "project", projectA, true, UUID.randomUUID().toString()))
                .hasMessageContaining("当前发布任务视图");
        link(first, "project", projectA, true, UUID.randomUUID().toString());
        link(second, "project", projectA, true, UUID.randomUUID().toString());
        link(other, "project", projectB, true, UUID.randomUUID().toString());
        PageResult<Row> page =
                tasks.pageTasks(
                        new PageQuery(app, "project", "tasks", projectA, query(1, 1)), OWNER);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getList()).extracting(Row::id).containsExactly(second.task().id());
        assertThat(
                        tasks.pageTasks(
                                        new PageQuery(
                                                app, "project", "tasks", projectA, query(2, 1)),
                                        OWNER)
                                .getList())
                .extracting(Row::id)
                .containsExactly(first.task().id());
        assertThat(
                        tasks.pageTasks(
                                        new PageQuery(
                                                app,
                                                "project",
                                                "tasks",
                                                projectA,
                                                query(1, 10),
                                                condition(amount, "lt", 10)),
                                        OWNER)
                                .getTotal())
                .isZero();
        assertThat(
                        tasks.pageTasks(
                                        new PageQuery(
                                                app, "project", "tasks", projectB, query(1, 10)),
                                        OWNER)
                                .getList())
                .extracting(Row::id)
                .containsExactly(other.task().id());
    }

    @Test
    void sharedNodesWithoutOwnMaterialAreFoundByBusinessObjectRecordEntryAndConditions() {
        verifySharedNodeScopes(true);
    }

    @Test
    void independentSharedNodesRemainInRecordAndTaskScopesWithoutEnteringApplicationScope() {
        verifySharedNodeScopes(false);
    }

    private void verifySharedNodeScopes(boolean applicationOwned) {
        String title = fixture.prefix + "shared";
        NodeInput source =
                new NodeInput(
                        "source",
                        null,
                        title + " source",
                        null,
                        OWNER,
                        Urgency.NORMAL,
                        Priority.MEDIUM,
                        null,
                        List.of(),
                        new Binding(app, "form", null),
                        null);
        NodeInput shared =
                new NodeInput(
                        "shared",
                        null,
                        title + " shared",
                        null,
                        OWNER,
                        Urgency.NORMAL,
                        Priority.MEDIUM,
                        null,
                        List.of("source"),
                        null,
                        new Sharing(DataMode.SHARED, "source", List.of()));
        NodeInput nested =
                new NodeInput(
                        "nested",
                        null,
                        title + " nested",
                        null,
                        OWNER,
                        Urgency.NORMAL,
                        Priority.MEDIUM,
                        null,
                        List.of("shared"),
                        null,
                        new Sharing(DataMode.SHARED, "shared", List.of()));
        Detail root =
                tasks.create(
                        new Create(
                                new NodeInput(
                                        null, null, title, null, OWNER, null, null, null, List.of(),
                                        null, null),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                UUID.randomUUID().toString(),
                                List.of(source, shared, nested),
                                null,
                                applicationOwned ? app : null),
                        OWNER);
        roots.add(root.task().id());
        assertThat(root.nodes())
                .allSatisfy(
                        node ->
                                assertThat(node.applicationId())
                                        .isEqualTo(applicationOwned ? app : null));
        tasks.transition(
                new Transition(
                        root.task().id(),
                        root.task().revision(),
                        Action.START,
                        null,
                        UUID.randomUUID().toString()),
                OWNER);
        Row sourceRow =
                root.nodes().stream()
                        .filter(row -> row.title().endsWith(" source"))
                        .findFirst()
                        .orElseThrow();
        Row started =
                tasks.transition(
                                new Transition(
                                        sourceRow.id(),
                                        sourceRow.revision(),
                                        Action.START,
                                        null,
                                        UUID.randomUUID().toString()),
                                OWNER)
                        .task();
        FormContext saved =
                tasks.saveBusiness(
                        new SaveBusiness(
                                started.id(),
                                started.revision(),
                                new ApplicationRecords.Save(
                                        app,
                                        object,
                                        null,
                                        null,
                                        Map.of(name, "共享内容", amount, 20),
                                        Map.of(),
                                        Map.of(),
                                        null,
                                        "form",
                                        UUID.randomUUID().toString(),
                                        null)),
                        OWNER);
        // 子节点办理了应用数据不改变整组归属；有明确应用归属的组仍需找到共享链上的全部节点。
        assertThat(page(condition(name, "eq", "共享内容"), 1, 2, OWNER).getTotal())
                .isEqualTo(applicationOwned ? 3 : 0);
        assertThat(page(condition(name, "eq", "共享内容"), 2, 2, OWNER).getList())
                .hasSize(applicationOwned ? 1 : 0);
        assertThat(
                        tasks.pageTasks(
                                        new PageQuery(
                                                app,
                                                "context",
                                                "tasks",
                                                saved.binding().recordId(),
                                                query(1, 10)),
                                        OWNER)
                                .getTotal())
                .isEqualTo(3);
        Query entry =
                new Query(
                        "VISIBLE",
                        "ALL",
                        LocalDate.now(),
                        title,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "form",
                        null,
                        null,
                        1,
                        10);
        assertThat(tasks.page(entry, OWNER).getTotal()).isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_task_instance WHERE root_id=?"
                                        + " AND business_json IS NULL",
                                Integer.class,
                                root.task().id()))
                .isEqualTo(3);
    }

    @Test
    void explicitRecordLinksAreIdempotentReversibleAndKeepTaskMaterialsAndIdentity() {
        Detail target = create("既有任务", 20, OWNER, WORKER);
        Detail record = create("待关联记录", 30, OWNER, WORKER);
        String recordId = record.task().business().recordId();
        PageQuery page = new PageQuery(app, "context", "tasks", recordId, query(1, 100));
        assertThat(tasks.linkCandidates(page, OWNER).getList())
                .extracting(Row::id)
                .contains(target.task().id())
                .doesNotContain(record.task().id());
        LinkTask command =
                new LinkTask(
                        new RecordContext(app, "context", "tasks", recordId),
                        target.task().id(),
                        target.task().revision(),
                        true,
                        UUID.randomUUID().toString());
        Detail linked = tasks.linkRecord(command, OWNER);
        Detail retried = tasks.linkRecord(command, OWNER);
        assertThat(retried.task().revision()).isEqualTo(linked.task().revision());
        assertThat(linked.task().business()).isEqualTo(target.task().business());
        assertThat(linked.links()).hasSize(1);
        assertThat(
                        tasks.pageTasks(page, OWNER).getList().stream()
                                .filter(row -> row.id().equals(target.task().id()))
                                .findFirst()
                                .orElseThrow()
                                .canUnlink())
                .isTrue();
        assertThat(tasks.linkCandidates(page, OWNER).getList())
                .extracting(Row::id)
                .doesNotContain(target.task().id());
        assertThatThrownBy(
                        () ->
                                tasks.linkRecord(
                                        new LinkTask(
                                                command.context(),
                                                target.task().id(),
                                                linked.task().revision(),
                                                false,
                                                UUID.randomUUID().toString()),
                                        21002L))
                .isInstanceOf(RuntimeException.class);
        Detail unlinked =
                tasks.linkRecord(
                        new LinkTask(
                                command.context(),
                                target.task().id(),
                                linked.task().revision(),
                                false,
                                UUID.randomUUID().toString()),
                        OWNER);
        assertThat(unlinked.links()).isEmpty();
        assertThat(unlinked.task().business()).isEqualTo(target.task().business());
        assertThat(tasks.pageTasks(page, OWNER).getList())
                .extracting(Row::id)
                .doesNotContain(target.task().id());
        assertThatThrownBy(
                        () ->
                                tasks.linkRecord(
                                        new LinkTask(
                                                command.context(),
                                                record.task().id(),
                                                record.task().revision(),
                                                false,
                                                UUID.randomUUID().toString()),
                                        OWNER))
                .hasMessageContaining("自动关联");
    }

    @Test
    void taskViewDraftDoesNotChangePublishedFiltersAndTemplateScopeIsMandatory() {
        String selectedTemplate = template("施工模板");
        String otherTemplate = template("其他模板");
        Detail selected = fromTemplate(selectedTemplate, "目标类型", 20);
        fromTemplate(otherTemplate, "其他类型", 30);
        ApplicationService apps = servicesContext.getBean(ApplicationService.class);
        ApplicationCenter.Detail current = apps.get(app);
        ApplicationUi.TaskView view =
                new ApplicationUi.TaskView(
                        "form",
                        List.of("title", "business:" + name),
                        condition(amount, "gte", 10),
                        new ApplicationUi.TaskFilter(
                                List.of("PENDING"), List.of("NORMAL"), List.of("MEDIUM"), "DAILY"),
                        List.of(selectedTemplate),
                        new ApplicationUi.TaskSort("createdAt", true));
        List<ApplicationCenter.Resource> resources =
                current.draft().resources().stream()
                        .map(
                                resource ->
                                        resource.id().equals("table")
                                                ? resource(
                                                        "table",
                                                        "PAGE",
                                                        new ApplicationUi.Page(
                                                                List.of(taskNode("form", view))))
                                                : resource)
                        .toList();
        ApplicationCenter.Detail draft =
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
                        OWNER);
        assertThat(page(null, 1, 10, OWNER).getTotal()).isEqualTo(2);
        apps.publish(
                new ApplicationCenter.Revision(app, draft.application().revision(), "发布施工范围"),
                OWNER);
        // 运行门户会裁剪页面两次；配置必须完整传给前端，不能只在数据库查询中生效。
        ApplicationCenter.Published runtime =
                servicesContext.getBean(ApplicationRuntimeService.class).application(app, OWNER);
        ApplicationCenter.Resource projected =
                runtime.definition().resources().stream()
                        .filter(resource -> resource.id().equals("table"))
                        .findFirst()
                        .orElseThrow();
        ApplicationUi.TaskView projectedView =
                mapper.convertValue(projected.config(), ApplicationUi.Page.class)
                        .nodes()
                        .getFirst()
                        .taskView();
        com.fasterxml.jackson.databind.JsonNode projectedJson = mapper.valueToTree(projectedView);
        assertThat(projectedJson).isEqualTo(mapper.valueToTree(view));
        assertThat(page(null, 1, 10, OWNER).getList())
                .extracting(Row::id)
                .containsExactly(selected.task().id());
        assertThat(page(condition(amount, "lt", 10), 1, 10, OWNER).getTotal()).isZero();
        Query conflict =
                new Query(
                        "VISIBLE",
                        "ALL",
                        LocalDate.now(),
                        null,
                        null,
                        null,
                        "COMPLETED",
                        null,
                        null,
                        null,
                        null,
                        null,
                        1,
                        10);
        assertThat(
                        tasks.pageTasks(new PageQuery(app, "table", "tasks", null, conflict), OWNER)
                                .getTotal())
                .isZero();
    }

    private String template(String name) {
        Template template =
                tasks.saveTemplate(
                        new SaveTemplate(
                                null,
                                null,
                                name,
                                null,
                                List.of(
                                        new NodeInput(
                                                "node", null, "工序", null, OWNER, null, null, null,
                                                List.of(), null, null))),
                        OWNER);
        templates.add(template.id());
        tasks.publish(new PublishTemplate(template.id(), template.revision()), OWNER);
        return template.id();
    }

    private Detail fromTemplate(String template, String title, int value) {
        NodeInput root =
                new NodeInput(
                        null,
                        null,
                        title,
                        null,
                        OWNER,
                        Urgency.NORMAL,
                        Priority.MEDIUM,
                        null,
                        List.of(),
                        new Binding(app, "form", null),
                        null);
        Detail result =
                tasks.create(
                        new Create(
                                root,
                                null,
                                template,
                                null,
                                null,
                                new ApplicationRecords.Save(
                                        app,
                                        object,
                                        null,
                                        null,
                                        Map.of(name, title, amount, value),
                                        Map.of(),
                                        Map.of(),
                                        null,
                                        "form",
                                        UUID.randomUUID().toString(),
                                        null),
                                null,
                                UUID.randomUUID().toString()),
                        OWNER);
        roots.add(result.task().id());
        return result;
    }

    private Detail link(Detail task, String page, String recordId, boolean include, String key) {
        return tasks.linkRecord(
                new LinkTask(
                        new RecordContext(app, page, "tasks", recordId),
                        task.task().id(),
                        tasks.detail(task.task().id(), OWNER).task().revision(),
                        include,
                        key),
                OWNER);
    }

    private ApplicationUi.Node taskNode(String form, ApplicationUi.TaskView view) {
        return new ApplicationUi.Node(
                "tasks", "TASKS", null, form, null, null, List.of(), null, null, null, null, null,
                null, view);
    }

    private Detail create(String title, int value, long actor, long assignee) {
        return create(title, value, actor, assignee, Map.of());
    }

    private Detail create(
            String title, int value, long actor, long assignee, Map<String, Object> extra) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(name, title);
        values.put(amount, value);
        if (actor == OWNER) values.put(secret, "私密内容");
        values.putAll(extra);
        NodeInput node =
                new NodeInput(
                        null,
                        null,
                        "业务任务",
                        null,
                        assignee,
                        Urgency.NORMAL,
                        Priority.MEDIUM,
                        new Schedule(TimeMode.T0, null, 0, 1),
                        List.of(),
                        new Binding(app, "form", null),
                        new Sharing(DataMode.INDEPENDENT, null, List.of()));
        ApplicationRecords.Save save =
                new ApplicationRecords.Save(
                        app,
                        object,
                        null,
                        null,
                        values,
                        Map.of(),
                        Map.of(),
                        null,
                        "form",
                        UUID.randomUUID().toString(),
                        null);
        Detail detail =
                tasks.create(
                        new Create(
                                node,
                                null,
                                null,
                                null,
                                null,
                                save,
                                null,
                                UUID.randomUUID().toString()),
                        actor);
        roots.add(detail.task().rootId());
        return detail;
    }

    private String relatedRecord(String title) {
        return servicesContext
                .getBean(RecordService.class)
                .save(
                        new ApplicationRecords.Save(
                                app,
                                relatedObject,
                                null,
                                null,
                                Map.of(relatedName, title),
                                Map.of(),
                                Map.of(),
                                null,
                                null,
                                UUID.randomUUID().toString(),
                                null),
                        OWNER)
                .record()
                .id();
    }

    private DataObjectApi.PublishedObject publish(ObjectDraft draft) {
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(draft.id(), draft.lockVersion(), null), OWNER);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "任务字段筛选"), OWNER)
                                .state())
                .isEqualTo("SUCCEEDED");
        return servicesContext.getBean(DataObjectApi.class).getVersion(draft.id(), null);
    }

    private void authorize(String scope) {
        ObjectGrant grant =
                new ObjectGrant(
                        object,
                        Set.of("READ", "CREATE", "UPDATE"),
                        scope,
                        Set.of(name, amount),
                        Set.of(name, amount),
                        Set.of(),
                        Set.of());
        access.save(
                new Save(
                        app,
                        access.get(app).revision(),
                        List.of(
                                new ApplicationAuthorization.Member(
                                        "USER", Long.toString(WORKER), List.of(grant)))),
                OWNER);
    }

    private PageResult<Row> page(DynamicConditionDTO filter, int page, int size, long actor) {
        return tasks.pageTasks(
                new PageQuery(app, "table", "tasks", null, query(page, size), filter), actor);
    }

    private Query query(int page, int size) {
        return new Query(
                "VISIBLE",
                "ALL",
                LocalDate.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                page,
                size);
    }

    private DynamicConditionDTO condition(String field, String operator, Object value) {
        return conditions(Map.of("logic", "AND", "items", List.of(item(field, operator, value))));
    }

    private Map<String, Object> item(String field, String operator, Object value) {
        return Map.of("type", "condition", "field", field, "operator", operator, "value", value);
    }

    private DynamicConditionDTO conditions(Map<String, Object> source) {
        return mapper.convertValue(source, DynamicConditionDTO.class);
    }

    private String field(DataCenter.Definition definition, String code) {
        return definition.fields().stream()
                .filter(field -> field.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private ApplicationCenter.Resource resource(String id, String kind, Object configuration) {
        return new ApplicationCenter.Resource(
                id, kind, "task_filter_" + id, id, mapper.convertValue(configuration, Map.class));
    }

    @AfterEach
    void cleanFixture() {
        for (String root : roots) {
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_record WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_binding WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update(
                    "DELETE FROM public.nocode_task_record_link WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update("DELETE FROM public.nocode_task_event WHERE root_id=?", root);
            jdbc.update("DELETE FROM public.nocode_task_instance WHERE root_id=?", root);
        }
        roots.clear();
        for (String template : templates) {
            jdbc.update(
                    "DELETE FROM public.nocode_task_template_version WHERE template_id=?",
                    template);
            jdbc.update("DELETE FROM public.nocode_task_template WHERE id=?", template);
        }
        templates.clear();
        if (app != null) extraApplications.add(app);
        for (String application : extraApplications) {
            Long id = Long.valueOf(application);
            jdbc.update("DELETE FROM public.nocode_handling_request WHERE application_id=?", id);
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
        extraApplications.clear();
        if (fixture != null) fixture.clean();
    }
}
