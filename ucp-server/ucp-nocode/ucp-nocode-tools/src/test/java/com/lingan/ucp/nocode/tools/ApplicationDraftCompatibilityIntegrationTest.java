package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationCenter.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 旧版本草稿及字段定位的真实保存回归；每例仅创建随机前缀夹具并整笔回滚。 */
class ApplicationDraftCompatibilityIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ApplicationService applications;
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
        objects = servicesContext.getBean(DataObjectApi.class);
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    private void rollback(Runnable action) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            try {
                                action.run();
                            } finally {
                                status.setRollbackOnly();
                            }
                        });
    }

    @Test
    void unchangedOldDraftSurvivesRemovedMainAndDetailFieldsButCannotPublish() {
        rollback(
                () -> {
                    var reference = createObject();
                    var original =
                            objects.getVersion(reference.objectId(), reference.versionNo())
                                    .definition();
                    var detail = original.details().getFirst();
                    String removedMain = original.fields().getLast().id();
                    String removedDetail = detail.fields().getLast().id();
                    var created = save(null, definition(reference, form(original)));
                    var frozenDraft = mapper.valueToTree(created.draft());
                    String sourceKey = "application:" + created.application().id() + ":draft";

                    // 仅移除当前回滚夹具的登记，重现历史应用引用已落后于对象结构的现存状态。
                    objects.removeDependencies("APP", sourceKey, 10001);
                    var editable =
                            designs.editPublished(
                                    new DataCenter.Revision(
                                            reference.objectId(),
                                            designs.get(reference.objectId()).draft().lockVersion(),
                                            null),
                                    10001);
                    var changedDetail =
                            new DataCenter.Detail(
                                    detail.id(),
                                    detail.code(),
                                    detail.name(),
                                    detail.tableName(),
                                    detail.state(),
                                    List.of(detail.fields().getFirst()),
                                    Map.of(),
                                    List.of(),
                                    detail.binding());
                    var changed =
                            designs.save(
                                    new DataCenter.SaveDesign(
                                            fixture.edit(
                                                    editable.draft(),
                                                    List.of(),
                                                    List.of(removedMain),
                                                    editable.draft().titleFieldId()),
                                            editable.settings(),
                                            null,
                                            editable.relations(),
                                            editable.indexes(),
                                            List.of(changedDetail),
                                            editable.mainBinding()),
                                    10001);
                    publishObject(changed);
                    var current = objects.getVersion(reference.objectId(), null);
                    assertThat(current.versionNo()).isGreaterThan(reference.versionNo());
                    assertThat(current.definition().fields())
                            .extracting(FieldDefinition::id)
                            .doesNotContain(removedMain);
                    assertThat(current.definition().details().getFirst().fields())
                            .extracting(FieldDefinition::id)
                            .doesNotContain(removedDetail);

                    var saved = save(created, created.draft());
                    assertThat(mapper.<JsonNode>valueToTree(saved.draft())).isEqualTo(frozenDraft);
                    assertThat(saved.draft().objects()).containsExactly(reference);
                    var reread = applications.get(saved.application().id());
                    assertThat(mapper.<JsonNode>valueToTree(reread.draft())).isEqualTo(frozenDraft);
                    var dependency =
                            designs.get(reference.objectId()).dependencies().stream()
                                    .filter(d -> sourceKey.equals(d.sourceKey()))
                                    .findFirst()
                                    .orElseThrow();
                    assertThat(dependency.fieldIds())
                            .containsExactlyInAnyOrder(
                                    original.fields().getFirst().id(),
                                    detail.fields().getFirst().id());
                    assertThat(dependency.fieldIds()).doesNotContain(removedMain, removedDetail);

                    // 草稿的容错不能旁路全局依赖登记或应用正式发布的版本兼容性边界。
                    assertInvalid(
                            () ->
                                    objects.registerDependency(
                                            new DataCenter.Dependency(
                                                    "APP",
                                                    sourceKey,
                                                    "回滚夹具",
                                                    reference.objectId(),
                                                    List.of(removedMain)),
                                            10001),
                            "依赖字段不属于对象的已发布版本");
                    assertInvalid(
                            () ->
                                    applications.publish(
                                            new Revision(
                                                    saved.application().id(),
                                                    saved.application().revision(),
                                                    "旧版本发布应拒绝"),
                                            10001),
                            // 2026-10 起：开着自动跟随时人工发布先把落后的对象引用提到最新版再校验，所以拒绝的理由变成
                            // 「按新版本校验未通过」并点名是哪个表单的哪个字段，不再是笼统的「固定版本已不兼容当前结构」。
                            "已有新版本 V2，本应用开着自动跟随，按新版本校验未通过：",
                            "在当前对象固定版本中不存在或已停用");
                    assertThat(
                                    applications
                                            .get(saved.application().id())
                                            .application()
                                            .publishedVersion())
                            .isNull();

                    // 显式同步新版本后，仍引用旧字段的表单必须给出精确位置，不能静默删除节点。
                    var synced =
                            new ObjectReference(
                                    current.objectId(), current.versionNo(), current.checksum());
                    assertInvalid(
                            () ->
                                    save(
                                            saved,
                                            new Definition(
                                                    List.of(synced), saved.draft().resources())),
                            "业务表单“字段定位表单”的主表",
                            removedMain,
                            "不存在");
                    assertThat(
                                    mapper.<JsonNode>valueToTree(
                                            applications.get(saved.application().id()).draft()))
                            .isEqualTo(frozenDraft);
                });
    }

    @Test
    void mainAndDetailFieldErrorsIdentifyResourceScopeNodeAndField() {
        rollback(
                () -> {
                    var reference = createObject();
                    var object = objects.getPublished(reference.objectId());
                    var detail = object.details().getFirst();
                    String mainField = object.fields().getFirst().id();
                    String rowField = detail.fields().getFirst().id();
                    var created = save(null, definition(reference, form(object)));
                    for (boolean row : List.of(false, true)) {
                        String location = row ? "内部明细“条目明细”" : "主表";
                        // 使用另一作用域真实存在的字段，验证主字段和明细列没有共用字段集合。
                        String foreignField = row ? mainField : rowField;
                        var foreign =
                                configuredForm(
                                        object, row, List.of(node("foreign_node", foreignField)));
                        assertInvalid(
                                () -> save(created, definition(reference, foreign)),
                                "业务表单“字段定位表单”",
                                location,
                                "foreign_node",
                                foreignField,
                                "不存在");
                        String fieldId = row ? rowField : mainField;
                        var duplicate =
                                configuredForm(
                                        object,
                                        row,
                                        List.of(
                                                node("first_node", fieldId),
                                                card(node("duplicate_node", fieldId))));
                        assertInvalid(
                                () -> save(created, definition(reference, duplicate)),
                                "业务表单“字段定位表单”",
                                location,
                                "duplicate_node",
                                fieldId,
                                "重复配置");
                    }
                    assertThat(applications.get(created.application().id()).draft())
                            .isEqualTo(created.draft());
                });
    }

    @Test
    void pageRejectsDirectFieldWithItsOwnResourceAndNodeLocation() {
        rollback(
                () -> {
                    var reference = createObject();
                    String fieldId =
                            objects.getPublished(reference.objectId()).fields().getFirst().id();
                    var page =
                            new Resource(
                                    "page",
                                    "PAGE",
                                    "page",
                                    "字段定位页面",
                                    config(
                                            new ApplicationUi.Page(
                                                    List.of(card(node("page_field", fieldId))))));
                    assertInvalid(
                            () -> save(null, new Definition(List.of(reference), List.of(page))),
                            "业务页面“字段定位页面”",
                            "page_field",
                            fieldId,
                            "不允许直接放入业务页面");
                });
    }

    @Test
    void removedLinkSourcesIdentifyMainOrDetailTargetAndSourceFields() {
        rollback(
                () -> {
                    var reference = createObject("DEPARTMENT");
                    var object = objects.getPublished(reference.objectId());
                    var created = save(null, definition(reference, form(object)));
                    for (boolean row : List.of(false, true)) {
                        var target =
                                row
                                        ? object.details().getFirst().fields().getLast()
                                        : object.fields().getLast();
                        String missingSource = "999999999";
                        var linked =
                                new ApplicationUi.Node(
                                        "linked_target",
                                        "FIELD",
                                        target.id(),
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        new ApplicationUi.FieldPresentation(
                                                null,
                                                null,
                                                null,
                                                null,
                                                new SelectionFields.Presentation(
                                                        "AUTO",
                                                        List.of(),
                                                        false,
                                                        missingSource,
                                                        null,
                                                        null),
                                                null));
                        var form = configuredForm(object, row, List.of(linked));
                        assertInvalid(
                                () -> save(created, definition(reference, form)),
                                "业务表单“字段定位表单”",
                                row ? "内部明细“条目明细”" : "主表",
                                target.name(),
                                target.id(),
                                missingSource,
                                "联动来源必须为表单中的其他字段");
                    }
                    assertThat(applications.get(created.application().id()).draft())
                            .isEqualTo(created.draft());
                });
    }

    private ObjectReference createObject() {
        return createObject("TEXT");
    }

    private ObjectReference createObject(String extraType) {
        var request = fixture.createRequest("draft_compatibility");
        var detail =
                new DataCenter.Detail(
                        null,
                        "items",
                        "条目明细",
                        request.tableName() + "_items",
                        "ACTIVE",
                        List.of(
                                fixture.field("item", "item", "TEXT", 0),
                                fixture.field("row_memo", "row_memo", extraType, 1)),
                        Map.of(),
                        List.of());
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
                                        List.of(
                                                request.fields().getFirst(),
                                                fixture.field("memo", "memo", extraType, 1)),
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                Map.of(),
                                List.of(),
                                List.of(),
                                List.of(detail)),
                        10001);
        publishObject(design);
        var version = objects.getVersion(design.draft().id(), null);
        return new ObjectReference(version.objectId(), version.versionNo(), version.checksum());
    }

    private void publishObject(DataCenter.Design design) {
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "草稿兼容回滚夹具"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
    }

    private Detail save(Detail previous, Definition definition) {
        return applications.save(
                new Save(
                        previous == null ? null : previous.application().id(),
                        previous == null ? null : previous.application().revision(),
                        fixture.prefix + "app",
                        "草稿兼容回滚夹具",
                        null,
                        null,
                        definition),
                10001);
    }

    private Definition definition(ObjectReference reference, ApplicationUi.Form form) {
        return new Definition(
                List.of(reference),
                List.of(new Resource("form", "FORM", "form", "字段定位表单", config(form))));
    }

    private Map<String, Object> config(Object value) {
        return mapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
    }

    private ApplicationUi.Form form(DataCenter.Definition object) {
        var detail = object.details().getFirst();
        return new ApplicationUi.Form(
                object.objectId(),
                object.fields().stream().map(f -> node("main_" + f.id(), f.id())).toList(),
                List.of(detail.id()),
                null,
                Map.of(
                        detail.id(),
                        detail.fields().stream().map(f -> node("row_" + f.id(), f.id())).toList()));
    }

    private ApplicationUi.Form configuredForm(
            DataCenter.Definition object, boolean row, List<ApplicationUi.Node> nodes) {
        var form = form(object);
        return new ApplicationUi.Form(
                form.objectId(),
                row ? form.nodes() : nodes,
                form.detailIds(),
                null,
                row ? Map.of(form.detailIds().getFirst(), nodes) : form.detailNodes());
    }

    private ApplicationUi.Node node(String id, String fieldId) {
        return new ApplicationUi.Node(id, "FIELD", fieldId, null, null, null, List.of());
    }

    private ApplicationUi.Node card(ApplicationUi.Node node) {
        return new ApplicationUi.Node("card", "CARD", null, null, "嵌套容器", null, List.of(node));
    }

    private void assertInvalid(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable operation,
            String... messageParts) {
        assertThatThrownBy(operation)
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error -> {
                            assertThat(error.getCode()).isEqualTo(NocodeErrorCodes.INVALID);
                            assertThat(error.getMessage()).contains(messageParts);
                        });
    }
}
