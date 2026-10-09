package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.FieldRuleFixture.*;
import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.richuang.os.nocode.runtime.service.task.TaskGroupRuntime;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskCenterService;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskWorkEntryService;

import org.junit.jupiter.api.*;

import java.util.*;

/** 真实开发库中验证无应用成员的任务办理人能选项目并保存日志，只清理本类创建的夹具。 */
class TaskFeedbackReferenceIntegrationTest {
    private static final long OWNER = 10001L;
    private static final long WORKER = 20002L;
    private FieldRuleFixture fixture;
    private TaskCenterService tasks;
    private TaskWorkEntryService entries;
    private DataCenter.Definition project;
    private DataCenter.Definition feedback;
    private String app;
    private String task;
    private String projectField;
    private String eligible;
    private String outsideView;
    private String outsideRule;
    private final List<String> roots = new ArrayList<>();

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
        fixture = new FieldRuleFixture();
        tasks = servicesContext.getBean(TaskCenterService.class);
        entries = servicesContext.getBean(TaskWorkEntryService.class);
        when(servicesContext.getBean(AdminUserApi.class).getUser(anyLong()))
                .thenAnswer(
                        call -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(call.getArgument(0));
                            user.setNickname("任务引用回归成员");
                            user.setStatus(0);
                            return user;
                        });
        project =
                fixture.object(
                        "project",
                        List.of(
                                field("area", "地区", "TEXT"),
                                field("state", "项目状态", "TEXT"),
                                field("secret", "保密预算", "TEXT")),
                        Map.of(),
                        List.of(),
                        List.of());
        feedback =
                fixture.object(
                        "feedback",
                        List.of(),
                        Map.of(),
                        List.of(reference("project", project)),
                        List.of());
        projectField = relationField(feedback, "project");
        feedback =
                fixture.republish(
                        feedback,
                        Map.of(
                                projectField,
                                feedback.fieldOptions()
                                        .getOrDefault(
                                                projectField, DataCenter.FieldOptions.defaults())
                                        .withRules(
                                                new FieldRules(
                                                        new FieldRules.Reference(
                                                                null,
                                                                List.of(
                                                                        constant(
                                                                                id(
                                                                                        project,
                                                                                        "state"),
                                                                                "eq",
                                                                                "施工中"))),
                                                        null,
                                                        null,
                                                        null,
                                                        null,
                                                        null))));
        ApplicationUi.FieldPresentation picker =
                new ApplicationUi.FieldPresentation(
                        null,
                        null,
                        null,
                        false,
                        new SelectionFields.Presentation(
                                "SELECT", List.of(), false, null, null, null, "project-view"));
        ApplicationUi.Node referenceNode =
                new ApplicationUi.Node(
                        "field-project",
                        "FIELD",
                        projectField,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        picker);
        app =
                fixture.app(
                        List.of(
                                resource(
                                        "feedback-form",
                                        "FORM",
                                        new ApplicationUi.Form(
                                                feedback.objectId(),
                                                List.of(node(id(feedback, "name")), referenceNode),
                                                List.of())),
                                resource(
                                        "project-form",
                                        "FORM",
                                        new ApplicationUi.Form(
                                                project.objectId(),
                                                project.fields().stream()
                                                        .map(field -> node(field.id()))
                                                        .toList(),
                                                List.of())),
                                resource(
                                        "project-view",
                                        "VIEW",
                                        new ApplicationUi.View(
                                                project.objectId(),
                                                List.of(id(project, "name")),
                                                Map.of(id(project, "area"), "东区"),
                                                null,
                                                false,
                                                20,
                                                "project-form"))),
                        feedback,
                        project);
        eligible = seed("幸福小区", "东区", "施工中");
        outsideView = seed("西区项目", "西区", "施工中");
        outsideRule = seed("竣工项目", "东区", "已竣工");
        task = createTask(false);
    }

    private String createTask(boolean explicitProject) {
        TaskWorkEntries.Config config =
                new TaskWorkEntries.Config(
                        "feedback",
                        "施工日志",
                        new Binding(app, "feedback-form", null),
                        TaskWorkEntries.DataMode.ROOT_SHARED,
                        null,
                        null,
                        null,
                        null,
                        true,
                        false);
        List<TaskWorkEntries.Config> configs = new ArrayList<>(List.of(config));
        if (explicitProject)
            configs.add(
                    new TaskWorkEntries.Config(
                            "project",
                            "任务项目",
                            new Binding(app, "project-form", null),
                            TaskWorkEntries.DataMode.ROOT_SHARED,
                            null,
                            null,
                            null,
                            null,
                            false,
                            false));
        NodeInput root =
                new NodeInput(
                        null,
                        null,
                        fixture.prefix() + "施工日志任务",
                        null,
                        WORKER,
                        null,
                        null,
                        new Schedule(TimeMode.UNSCHEDULED, null, 0, 0),
                        List.of(),
                        null,
                        null,
                        configs,
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP));
        String created =
                tasks.create(new Create(root, null, null, null, null, null, null, key()), OWNER)
                        .task()
                        .id();
        roots.add(created);
        tasks.transition(
                new Transition(
                        created,
                        tasks.detail(created, WORKER).task().revision(),
                        Action.START,
                        null,
                        key()),
                WORKER);
        return created;
    }

    @AfterEach
    void cleanup() {
        for (String root : roots) {
            jdbc.update("DELETE FROM public.nocode_task_entry_record WHERE task_id=?", root);
            jdbc.update("DELETE FROM public.nocode_task_entry_binding WHERE task_id=?", root);
            jdbc.update("DELETE FROM public.nocode_task_event WHERE root_id=?", root);
            jdbc.update("DELETE FROM public.nocode_task_instance WHERE root_id=?", root);
        }
        if (fixture != null) fixture.cleanup();
    }

    @Test
    void nonMemberCanChooseAndSubmitButOnlyTaskFeedbackBecomesReadable() {
        assertThat(
                        servicesContext
                                .getBean(ApplicationAuthorizationService.class)
                                .grants(app, null, WORKER))
                .isEmpty();
        SelectionFields.Result choices = selection(null, List.of());
        assertThat(choices.options())
                .extracting(SelectionFields.Option::value)
                .containsExactly(eligible);
        assertThat(choices.options())
                .extracting(SelectionFields.Option::label)
                .containsExactly("幸福小区");
        ApplicationRecords.Row saved = save(eligible).handling().result().record();
        assertThat(saved.values()).containsEntry(projectField, eligible);
        assertThat(
                        entries.page(
                                        new TaskWorkEntries.Query(
                                                task, "feedback", false, false, 1, 20, ""),
                                        WORKER)
                                .getList())
                .hasSize(1);
        assertThat(selection(null, List.of(eligible)).selected())
                .anyMatch(
                        value ->
                                value.value().equals(eligible)
                                        && !value.unavailable()
                                        && !value.disabled());
        assertThatThrownBy(() -> fixture.runtime.get(app, project.objectId(), eligible, WORKER))
                .hasMessageContaining("权限");
        TaskGroupRuntime group = servicesContext.getBean(TaskGroupRuntime.class);
        assertThat(
                        group.execute(
                                task,
                                "feedback",
                                WORKER,
                                false,
                                () ->
                                        fixture.runtime
                                                .page(
                                                        new ApplicationRecords.Query(
                                                                app,
                                                                project.objectId(),
                                                                1,
                                                                20,
                                                                "",
                                                                Map.of(),
                                                                null,
                                                                false),
                                                        WORKER)
                                                .getList()))
                .isEmpty();
        assertThatThrownBy(
                        () ->
                                group.execute(
                                        task,
                                        "feedback",
                                        WORKER,
                                        false,
                                        () ->
                                                fixture.runtime.get(
                                                        app, project.objectId(), eligible, WORKER)))
                .hasMessageContaining("本组任务");
    }

    @Test
    void fixedViewAndObjectReferenceRulesStillRejectForgedSelectedIds() {
        assertThat(selection(null, List.of(outsideView, outsideRule)).selected())
                .allMatch(SelectionFields.Option::disabled);
        assertThatThrownBy(() -> save(outsideView)).hasMessageContaining("视图范围");
        assertThatThrownBy(() -> save(outsideRule)).hasMessageContaining("对象引用筛选");
        assertThat(
                        entries.page(
                                        new TaskWorkEntries.Query(
                                                task, "feedback", false, false, 1, 20, ""),
                                        WORKER)
                                .getList())
                .isEmpty();
    }

    @Test
    void explicitlyConfiguredProjectGroupCannotSelectRecordsOutsideItsTaskData() {
        task = createTask(true);
        assertThat(selection(null, List.of()).options()).isEmpty();
        assertThatThrownBy(() -> save(eligible)).hasMessageContaining("范围");
    }

    @Test
    void nameOnlyReferenceCannotSearchConfidentialTargetFields() {
        assertThat(selection("仅内部可见的预算线索", List.of()).options()).isEmpty();
        assertThat(selection("幸福", List.of()).options())
                .extracting(SelectionFields.Option::value)
                .containsExactly(eligible);
    }

    private SelectionFields.Result selection(String search, List<String> selected) {
        return entries.selection(
                new TaskWorkEntries.Selection(
                        new TaskWorkEntries.Form(task, "feedback", null, null),
                        new SelectionFields.Query(
                                app,
                                feedback.objectId(),
                                null,
                                projectField,
                                search,
                                1,
                                20,
                                selected,
                                null,
                                "feedback-form",
                                Map.of(),
                                true,
                                null)),
                WORKER);
    }

    private TaskWorkEntries.Saved save(String selected) {
        return entries.save(
                new TaskWorkEntries.Save(
                        task,
                        "feedback",
                        null,
                        new ApplicationRecords.Save(
                                app,
                                feedback.objectId(),
                                null,
                                null,
                                Map.of(id(feedback, "name"), "当日施工日志", projectField, selected),
                                Map.of(),
                                Map.of(),
                                null,
                                "feedback-form",
                                key(),
                                null)),
                WORKER);
    }

    private String seed(String name, String area, String state) {
        return fixture.save(
                        app,
                        project,
                        Map.of(
                                id(project, "name"),
                                name,
                                id(project, "area"),
                                area,
                                id(project, "state"),
                                state,
                                id(project, "secret"),
                                "仅内部可见的预算线索"))
                .id();
    }

    private ApplicationCenter.Resource resource(String id, String kind, Object config) {
        return new ApplicationCenter.Resource(
                id,
                kind,
                "task_ref_" + id.replace('-', '_'),
                id,
                mapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
    }

    private ApplicationUi.Node node(String field) {
        return new ApplicationUi.Node(
                "field-" + field, "FIELD", field, null, null, null, List.of());
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
