package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationCenter.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.resource.ApplicationAutomationCatalog;
import com.lingan.ucp.nocode.application.service.resource.ApplicationResourceContext;
import com.lingan.ucp.nocode.runtime.service.application.ApplicationRuntimeService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 默认表单沿真实草稿、不可变发布和记录保存验证；仅随机前缀夹具，整笔事务回滚。 */
class ApplicationDefaultFormIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ApplicationService applications;
    private ApplicationResourceContext resources;
    private DataObjectApi objects;

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
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        applications = servicesContext.getBean(ApplicationService.class);
        resources = servicesContext.getBean(ApplicationResourceContext.class);
        objects = servicesContext.getBean(DataObjectApi.class);
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    @Test
    void draftDefaultOnlyTakesEffectAfterPublishingAndResolvedIdUsesRealSaveRules() {
        rollback(
                () -> {
                    ObjectReference object = object("primary");
                    Resource view = view("records", object, null);
                    Detail app = save(null, new Definition(List.of(object), List.of(view)));
                    grantApplicationObjects(app.application().id());
                    publish(app);
                    String appId = app.application().id();
                    assertThat(resolve(applications.published(appId), "records")).isNull();
                    String legacyChecksum = applications.published(appId).checksum();

                    Resource primary = form("default_form", object, true, false);
                    app =
                            save(
                                    applications.get(appId),
                                    new Definition(List.of(object), List.of(view, primary)));
                    assertThat(resolve(applications.published(appId), "records")).isNull();
                    assertThat(applications.published(appId).checksum()).isEqualTo(legacyChecksum);
                    publish(app);
                    Published visible =
                            servicesContext
                                    .getBean(ApplicationRuntimeService.class)
                                    .application(appId, 10001);
                    Resource resolved = resolve(visible, "records");
                    assertThat(resolved.id()).isEqualTo("default_form");
                    assertThat(
                                    resources
                                            .decode(
                                                    visible.definition()
                                                            .resources()
                                                            .getFirst()
                                                            .config(),
                                                    ApplicationUi.View.class)
                                            .formId())
                            .isNull();
                    String field =
                            objects.getVersion(object.objectId(), object.versionNo())
                                    .definition()
                                    .fields()
                                    .getFirst()
                                    .id();
                    ApplicationRecords.Aggregate record =
                            servicesContext
                                    .getBean(RecordService.class)
                                    .save(
                                            new ApplicationRecords.Save(
                                                    appId,
                                                    object.objectId(),
                                                    null,
                                                    null,
                                                    Map.of(field, "默认表单实际保存"),
                                                    Map.of(),
                                                    Map.of(),
                                                    null,
                                                    resolved.id(),
                                                    UUID.randomUUID().toString(),
                                                    null),
                                            10001);
                    assertThat(record.record().values()).containsEntry(field, "默认表单实际保存");
                    assertThat(applications.published(appId, 1).checksum())
                            .isEqualTo(legacyChecksum);
                    assertThat(resolve(applications.published(appId, 1), "records")).isNull();

                    Resource locked = form("default_form", object, true, true);
                    app =
                            save(
                                    applications.get(appId),
                                    new Definition(List.of(object), List.of(view, locked)));
                    publish(app);
                    assertThatThrownBy(
                                    () ->
                                            servicesContext
                                                    .getBean(RecordService.class)
                                                    .save(
                                                            new ApplicationRecords.Save(
                                                                    appId,
                                                                    object.objectId(),
                                                                    null,
                                                                    null,
                                                                    Map.of(field, "不可越过只读"),
                                                                    Map.of(),
                                                                    Map.of(),
                                                                    null,
                                                                    "default_form",
                                                                    UUID.randomUUID().toString(),
                                                                    null),
                                                            10001))
                            .hasMessageContaining("只读");
                });
    }

    @Test
    void replacementMovesInheritedViewsButKeepsExplicitBindingsAndProtectsDeletion() {
        rollback(
                () -> {
                    ObjectReference object = object("replacement");
                    Resource first = form("first", object, true, false);
                    Resource second = form("second", object, false, false);
                    Resource inherited = view("inherited", object, null);
                    Resource explicit = view("explicit", object, "first");
                    Detail app =
                            save(
                                    null,
                                    new Definition(
                                            List.of(object),
                                            List.of(first, second, inherited, explicit)));
                    Detail original = app;
                    assertThatThrownBy(
                                    () ->
                                            save(
                                                    original,
                                                    new Definition(
                                                            List.of(object),
                                                            List.of(second, inherited))))
                            .hasMessageContaining("请先更换或取消默认表单");
                    assertThatThrownBy(
                                    () ->
                                            save(
                                                    original,
                                                    new Definition(
                                                            List.of(object),
                                                            List.of(
                                                                    form(
                                                                            "second", object, true,
                                                                            false),
                                                                    inherited,
                                                                    explicit))))
                            .hasMessageContaining("不存在");
                    app =
                            save(
                                    app,
                                    new Definition(
                                            List.of(object),
                                            List.of(
                                                    form("first", object, false, false),
                                                    form("second", object, true, false),
                                                    inherited,
                                                    explicit)));
                    grantApplicationObjects(app.application().id());
                    publish(app);
                    Published published = applications.published(app.application().id());
                    assertThat(resolve(published, "inherited").id()).isEqualTo("second");
                    assertThat(resolve(published, "explicit").id()).isEqualTo("first");
                    Detail current = applications.get(app.application().id());
                    assertThatThrownBy(() -> save(original, current.draft()))
                            .hasMessageContaining("已被其他操作修改");
                    assertThat(applications.get(app.application().id()).draft())
                            .isEqualTo(current.draft());
                });
    }

    @Test
    void duplicateDefaultsAndForeignExplicitFormsAreRejectedOnSaveAndStrictValidation() {
        rollback(
                () -> {
                    ObjectReference object = object("unique");
                    ObjectReference foreign = object("foreign");
                    Definition duplicate =
                            new Definition(
                                    List.of(object),
                                    List.of(
                                            form("first", object, true, false),
                                            form("second", object, true, false)));
                    assertThatThrownBy(() -> save(null, duplicate))
                            .hasMessageContaining("只能设置一个默认表单");
                    assertThatThrownBy(() -> applications.normalize(duplicate))
                            .hasMessageContaining("只能设置一个默认表单");
                    Definition different =
                            new Definition(
                                    List.of(object, foreign),
                                    List.of(
                                            form("first", object, true, false),
                                            form("second", foreign, true, false)));
                    assertThat(save(null, different).draft().resources()).hasSize(2);
                    Definition crossObject =
                            new Definition(
                                    different.objects(),
                                    List.of(
                                            form("first", object, true, false),
                                            form("second", foreign, true, false),
                                            view("records", object, "second")));
                    assertThatThrownBy(() -> applications.normalize(crossObject))
                            .hasMessageContaining("同一对象");
                    assertThatThrownBy(() -> save(null, crossObject)).hasMessageContaining("同一对象");
                });
    }

    @Test
    void pageCreateActionAcceptsInheritedDefaultAndStillRejectsNoForm() {
        rollback(
                () -> {
                    ObjectReference object = object("page");
                    Resource view = view("records", object, null);
                    Resource page =
                            resource(
                                    "page",
                                    "PAGE",
                                    new ApplicationUi.Page(
                                            List.of(
                                                    new ApplicationUi.Node(
                                                            "list", "VIEW", null, "records", null,
                                                            24, List.of()),
                                                    mapper.convertValue(
                                                            Map.of(
                                                                    "id",
                                                                    "create",
                                                                    "type",
                                                                    "BUTTON",
                                                                    "text",
                                                                    "新增",
                                                                    "action",
                                                                    Map.of(
                                                                            "kind",
                                                                            "CREATE",
                                                                            "targetNodeId",
                                                                            "list")),
                                                            ApplicationUi.Node.class))));
                    assertThatThrownBy(
                                    () ->
                                            save(
                                                    null,
                                                    new Definition(
                                                            List.of(object), List.of(view, page))))
                            .hasMessageContaining("需要配置业务表单或对象默认表单");
                    Detail app =
                            save(
                                    null,
                                    new Definition(
                                            List.of(object),
                                            List.of(
                                                    view,
                                                    page,
                                                    form("default_form", object, true, false))));
                    grantApplicationObjects(app.application().id());
                    assertThatCode(() -> publish(app)).doesNotThrowAnyException();
                });
    }

    @Test
    void defaultFormsKeepObjectRequiredFieldValidation() {
        rollback(
                () -> {
                    ObjectReference object = object("required", true);
                    DataCenter.Definition definition =
                            objects.getVersion(object.objectId(), object.versionNo()).definition();
                    String optional =
                            definition.fields().stream()
                                    .filter(field -> field.code().equals("note"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    Resource incomplete =
                            resource(
                                    "default_form",
                                    "FORM",
                                    new ApplicationUi.Form(
                                            object.objectId(),
                                            List.of(
                                                    new ApplicationUi.Node(
                                                            "note", "FIELD", optional, null, null,
                                                            12, List.of())),
                                            List.of(),
                                            new ApplicationUi.FormOptions(
                                                    "vertical", "保存", false, false, true)));
                    assertThatThrownBy(
                                    () ->
                                            save(
                                                    null,
                                                    new Definition(
                                                            List.of(object), List.of(incomplete))))
                            .hasMessageContaining("缺少必填字段");
                });
    }

    private ObjectReference object(String suffix) {
        return object(suffix, false);
    }

    private ObjectReference object(String suffix, boolean required) {
        SaveObjectDraft request = fixture.createRequest(suffix);
        if (required) {
            FieldDefinition title = request.fields().getFirst();
            request =
                    new SaveObjectDraft(
                            null,
                            null,
                            request.objectCode(),
                            request.objectName(),
                            null,
                            request.tableName(),
                            request.titleFieldKey(),
                            List.of(
                                    new FieldDefinition(
                                            title.key(),
                                            null,
                                            title.code(),
                                            title.name(),
                                            title.type(),
                                            null,
                                            null,
                                            null,
                                            true,
                                            false,
                                            0),
                                    fixture.field("note", "note", "TEXT", 1)),
                            List.of());
        }
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                request,
                                DataCenter.Settings.defaults(),
                                Map.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "默认表单测试"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi.PublishedObject published = objects.getVersion(design.draft().id(), null);
        return new ObjectReference(
                published.objectId(), published.versionNo(), published.checksum());
    }

    private Resource form(String id, ObjectReference object, boolean primary, boolean readOnly) {
        List<ApplicationUi.Node> nodes =
                objects
                        .getVersion(object.objectId(), object.versionNo())
                        .definition()
                        .fields()
                        .stream()
                        .map(
                                field ->
                                        new ApplicationUi.Node(
                                                "node_" + field.id(),
                                                "FIELD",
                                                field.id(),
                                                null,
                                                null,
                                                12,
                                                List.of()))
                        .toList();
        return resource(
                id,
                "FORM",
                new ApplicationUi.Form(
                        object.objectId(),
                        nodes,
                        List.of(),
                        new ApplicationUi.FormOptions("vertical", "保存", readOnly, false, primary)));
    }

    private Resource view(String id, ObjectReference object, String formId) {
        String field =
                objects.getVersion(object.objectId(), object.versionNo())
                        .definition()
                        .fields()
                        .getFirst()
                        .id();
        return resource(
                id,
                "VIEW",
                new ApplicationUi.View(
                        object.objectId(), List.of(field), Map.of(), null, false, 20, formId));
    }

    private Resource resource(String id, String kind, Object config) {
        return new Resource(
                id,
                kind,
                id,
                id,
                mapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
    }

    private Detail save(Detail current, Definition definition) {
        return applications.save(
                new Save(
                        current == null ? null : current.application().id(),
                        current == null ? null : current.application().revision(),
                        fixture.prefix + "app",
                        "默认表单验证",
                        "事务回滚夹具",
                        "AppstoreOutlined",
                        definition),
                10001);
    }

    private void publish(Detail app) {
        applications.publish(
                new Revision(app.application().id(), app.application().revision(), "默认表单验证"),
                10001);
    }

    private Resource resolve(Published published, String viewId) {
        Resource view =
                published.definition().resources().stream()
                        .filter(resource -> resource.id().equals(viewId))
                        .findFirst()
                        .orElseThrow();
        return resources.viewForm(
                published.definition().resources(),
                resources.decode(view.config(), ApplicationUi.View.class));
    }

    private void rollback(Runnable action) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            try {
                                // 回滚夹具跨对象创建和应用保存持锁，沿生产锁序先锁目录，避免与并行体验写入倒序。
                                servicesContext
                                        .getBean(ApplicationAutomationCatalog.class)
                                        .lock(true);
                                action.run();
                            } finally {
                                status.setRollbackOnly();
                            }
                        });
    }
}
