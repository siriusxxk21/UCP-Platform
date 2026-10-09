package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationCenter.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;

import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 当前开发环境的应用聚合回归；每例整笔回滚，只操作当前随机前缀对象夹具。 */
class ApplicationIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ApplicationService applications;
    private DataObjectApi objectApi;

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
        objectApi = servicesContext.getBean(DataObjectApi.class);
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    private void rollback(Runnable action) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            try {
                                action.run();
                            } finally {
                                tx.setRollbackOnly();
                            }
                        });
    }

    private ObjectReference reference() {
        return reference(fixture.createRequest("appobject"));
    }

    private ObjectReference reference(SaveObjectDraft request) {
        return reference(request, List.of());
    }

    private ObjectReference reference(
            SaveObjectDraft request, List<DataCenter.Relation> relations) {
        return reference(request, relations, List.of());
    }

    private ObjectReference reference(
            SaveObjectDraft request,
            List<DataCenter.Relation> relations,
            List<DataCenter.Detail> details) {
        return reference(request, relations, details, null);
    }

    private ObjectReference reference(
            SaveObjectDraft request,
            List<DataCenter.Relation> relations,
            List<DataCenter.Detail> details,
            Map<String, DataCenter.FieldOptions> options) {
        var d =
                designs.save(
                        new DataCenter.SaveDesign(
                                request,
                                DataCenter.Settings.defaults(),
                                options,
                                relations,
                                List.of(),
                                details),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(d.draft().id(), d.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "应用引用测试"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        var version = objectApi.getVersion(d.draft().id(), null);
        return new ObjectReference(version.objectId(), version.versionNo(), version.checksum());
    }

    @Test
    void internalDetailCanvasPublishesRunsAndPreservesOmittedDetailsWithGenericFillRules() {
        rollback(
                () -> {
                    var unitOptions =
                            new DataCenter.FieldOptions(
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    "ACTIVE",
                                    List.of(new DataCenter.Option("EACH", "个", false)),
                                    null,
                                    null,
                                    null,
                                    null,
                                    false,
                                    false);
                    var sourceRequest = fixture.createRequest("generic_source");
                    var source =
                            reference(
                                    new SaveObjectDraft(
                                            null,
                                            null,
                                            sourceRequest.objectCode(),
                                            sourceRequest.objectName(),
                                            null,
                                            sourceRequest.tableName(),
                                            sourceRequest.titleFieldKey(),
                                            List.of(
                                                    sourceRequest.fields().getFirst(),
                                                    fixture.field("unit", "unit", "SELECT", 1)),
                                            List.of()),
                                    List.of(),
                                    List.of(),
                                    Map.of("unit", unitOptions));
                    var sourceDef =
                            objectApi
                                    .getVersion(source.objectId(), source.versionNo())
                                    .definition();
                    String sourceName =
                            sourceDef.fields().stream()
                                    .filter(f -> f.code().equals("name"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    String sourceUnit =
                            sourceDef.fields().stream()
                                    .filter(f -> f.code().equals("unit"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    var request = fixture.createRequest("generic_target");
                    var detail =
                            new DataCenter.Detail(
                                    null,
                                    "items",
                                    "通用明细",
                                    "biz_" + fixture.prefix + "_generic_rows",
                                    "ACTIVE",
                                    List.of(fixture.field("row_unit", "row_unit", "SELECT", 0)),
                                    Map.of("row_unit", unitOptions),
                                    List.of());
                    var target =
                            reference(
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
                                                    fixture.field("unit", "unit", "SELECT", 1)),
                                            List.of()),
                                    List.of(
                                            new DataCenter.Relation(
                                                    null,
                                                    "source",
                                                    "来源",
                                                    "REFERENCE",
                                                    source.objectId(),
                                                    null,
                                                    null,
                                                    false,
                                                    "RESTRICT"),
                                            new DataCenter.Relation(
                                                    null,
                                                    "row_source",
                                                    "本行来源",
                                                    "REFERENCE",
                                                    source.objectId(),
                                                    null,
                                                    null,
                                                    false,
                                                    "RESTRICT",
                                                    "detail:items")),
                                    List.of(detail),
                                    Map.of("unit", unitOptions));
                    var targetDef =
                            objectApi
                                    .getVersion(target.objectId(), target.versionNo())
                                    .definition();
                    var rowDef = targetDef.details().getFirst();
                    String name =
                            targetDef.fields().stream()
                                    .filter(f -> f.code().equals("name"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    String unit =
                            targetDef.fields().stream()
                                    .filter(f -> f.code().equals("unit"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    String rowUnit =
                            rowDef.fields().stream()
                                    .filter(f -> f.code().equals("row_unit"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    String sourceField =
                            targetDef.relations().stream()
                                    .filter(r -> r.sourceDetailId() == null)
                                    .findFirst()
                                    .orElseThrow()
                                    .fieldId();
                    String rowSource =
                            targetDef.relations().stream()
                                    .filter(r -> r.sourceDetailId() != null)
                                    .findFirst()
                                    .orElseThrow()
                                    .fieldId();
                    java.util.function.BiFunction<String, FormFills.Binding, ApplicationUi.Node>
                            node =
                                    (id, fill) ->
                                            new ApplicationUi.Node(
                                                    "node_" + id,
                                                    "FIELD",
                                                    id,
                                                    null,
                                                    null,
                                                    null,
                                                    List.of(),
                                                    null,
                                                    new ApplicationUi.FieldPresentation(
                                                            null, null, null, false, null, null,
                                                            fill));
                    var canvasDetail =
                            new ApplicationUi.Node(
                                    "items_block",
                                    "INTERNAL_DETAIL",
                                    null,
                                    null,
                                    "资料明细",
                                    24,
                                    List.of(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    new ApplicationUi.InternalDetailBinding(rowDef.id(), "GRID"));
                    var form =
                            new ApplicationUi.Form(
                                    target.objectId(),
                                    List.of(
                                            node.apply(name, null),
                                            new ApplicationUi.Node(
                                                    "detail_card",
                                                    "CARD",
                                                    null,
                                                    null,
                                                    "明细区",
                                                    24,
                                                    List.of(canvasDetail)),
                                            node.apply(sourceField, null),
                                            node.apply(
                                                    unit,
                                                    new FormFills.Binding(
                                                            sourceField,
                                                            sourceUnit,
                                                            "SOURCE_CHANGE",
                                                            true))),
                                    List.of(rowDef.id()),
                                    null,
                                    Map.of(
                                            rowDef.id(),
                                            List.of(
                                                    node.apply(rowSource, null),
                                                    node.apply(
                                                            rowUnit,
                                                            new FormFills.Binding(
                                                                    rowSource,
                                                                    sourceUnit,
                                                                    "SOURCE_CHANGE",
                                                                    true)))));
                    var app =
                            create(
                                    new Definition(
                                            List.of(source, target),
                                            List.of(
                                                    new Resource(
                                                            "generic",
                                                            "FORM",
                                                            "generic",
                                                            "通用表单",
                                                            mapper.convertValue(
                                                                    form,
                                                                    new com.fasterxml.jackson.core
                                                                                    .type
                                                                                    .TypeReference<
                                                                            Map<
                                                                                    String,
                                                                                    Object>>() {})),
                                                    new Resource(
                                                            "main_only",
                                                            "FORM",
                                                            "main_only",
                                                            "不展示明细的主表表单",
                                                            mapper.convertValue(
                                                                    new ApplicationUi.Form(
                                                                            target.objectId(),
                                                                            List.of(
                                                                                    node.apply(
                                                                                            name,
                                                                                            null)),
                                                                            List.of()),
                                                                    new com.fasterxml.jackson.core
                                                                                    .type
                                                                                    .TypeReference<
                                                                            Map<
                                                                                    String,
                                                                                    Object>>() {})))));
                    String appId = app.application().id();
                    applications.publish(
                            new Revision(appId, app.application().revision(), "通用规则验证"), 10001);
                    var runtimeApp =
                            servicesContext
                                    .getBean(
                                            com.richuang.os.nocode.runtime.service.application
                                                    .ApplicationRuntimeService.class)
                                    .application(appId, 10001);
                    var renderedForm =
                            mapper.convertValue(
                                    runtimeApp.definition().resources().stream()
                                            .filter(r -> r.id().equals("generic"))
                                            .findFirst()
                                            .orElseThrow()
                                            .config(),
                                    ApplicationUi.Form.class);
                    assertThat(renderedForm.nodes().get(1).children().getFirst().detail())
                            .isEqualTo(canvasDetail.detail());
                    var mainOnlyForm =
                            mapper.convertValue(
                                    runtimeApp.definition().resources().stream()
                                            .filter(r -> r.id().equals("main_only"))
                                            .findFirst()
                                            .orElseThrow()
                                            .config(),
                                    ApplicationUi.Form.class);
                    assertThat(mainOnlyForm.detailIds()).isEmpty();
                    assertThat(mainOnlyForm.detailNodes()).isEmpty();
                    var records =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.runtime.service.record.RecordService
                                            .class);
                    var selected =
                            records.save(
                                    new ApplicationRecords.Save(
                                            appId,
                                            source.objectId(),
                                            null,
                                            null,
                                            Map.of(sourceName, "资料", sourceUnit, "EACH"),
                                            Map.of(),
                                            Map.of(),
                                            null,
                                            null,
                                            UUID.randomUUID().toString(),
                                            null),
                                    10001);
                    assertThat(
                                    records.formFill(
                                            new FormFills.Query(
                                                    appId,
                                                    target.objectId(),
                                                    "generic",
                                                    sourceField,
                                                    selected.record().id()),
                                            10001))
                            .containsExactly(entry(unit, "EACH"));
                    assertThat(
                                    records.formFill(
                                            new FormFills.Query(
                                                    appId,
                                                    target.objectId(),
                                                    "generic",
                                                    rowSource,
                                                    selected.record().id(),
                                                    rowDef.id(),
                                                    null),
                                            10001))
                            .containsExactly(entry(rowUnit, "EACH"));
                    var duplicateNodes = new ArrayList<>(form.nodes());
                    duplicateNodes.add(canvasDetail);
                    var invalidLayout =
                            new ApplicationUi.Form(
                                    form.objectId(),
                                    duplicateNodes,
                                    form.detailIds(),
                                    form.options(),
                                    form.detailNodes());
                    assertThatThrownBy(
                                    () ->
                                            records.previewFormFill(
                                                    new FormFills.PreviewQuery(
                                                            new FormFills.Query(
                                                                    appId,
                                                                    target.objectId(),
                                                                    "generic",
                                                                    rowSource,
                                                                    selected.record().id(),
                                                                    rowDef.id(),
                                                                    null),
                                                            List.of(source, target),
                                                            invalidLayout),
                                                    10001))
                            .hasMessageContaining("只能放置一次");
                    assertThatThrownBy(
                                    () ->
                                            records.previewSelection(
                                                    new SelectionFields.PreviewQuery(
                                                            new SelectionFields.Query(
                                                                    appId,
                                                                    target.objectId(),
                                                                    rowDef.id(),
                                                                    rowSource,
                                                                    null,
                                                                    1,
                                                                    10,
                                                                    List.of(),
                                                                    null,
                                                                    "generic",
                                                                    Map.of()),
                                                            List.of(source, target),
                                                            invalidLayout,
                                                            false),
                                                    10001))
                            .hasMessageContaining("只能放置一次");
                    var stored =
                            records.save(
                                    new ApplicationRecords.Save(
                                            appId,
                                            target.objectId(),
                                            null,
                                            null,
                                            Map.of(
                                                    name,
                                                    "通用记录",
                                                    sourceField,
                                                    selected.record().id(),
                                                    unit,
                                                    "EACH"),
                                            Map.of(
                                                    rowDef.id(),
                                                    List.of(
                                                            new ApplicationRecords.Row(
                                                                    null,
                                                                    null,
                                                                    Map.of(
                                                                            rowSource,
                                                                            selected.record().id(),
                                                                            rowUnit,
                                                                            "EACH"),
                                                                    null,
                                                                    Map.of(),
                                                                    "first"),
                                                            new ApplicationRecords.Row(
                                                                    null,
                                                                    null,
                                                                    Map.of(
                                                                            rowSource,
                                                                            selected.record().id(),
                                                                            rowUnit,
                                                                            "EACH"),
                                                                    null,
                                                                    Map.of(),
                                                                    "second"))),
                                            Map.of(),
                                            null,
                                            "generic",
                                            UUID.randomUUID().toString(),
                                            null),
                                    10001);
                    var first = stored.details().get(rowDef.id()).getFirst();
                    var second = stored.details().get(rowDef.id()).getLast();
                    var clearingMain = new HashMap<String, Object>();
                    clearingMain.put(sourceField, null);
                    var clearingRow = new HashMap<String, Object>();
                    clearingRow.put(rowSource, null);
                    // 未提交快照字段也必须按配置清空；同单另一行保持原快照。
                    var cleared =
                            records.save(
                                    new ApplicationRecords.Save(
                                            appId,
                                            target.objectId(),
                                            stored.record().id(),
                                            stored.record().revision(),
                                            clearingMain,
                                            Map.of(
                                                    rowDef.id(),
                                                    List.of(
                                                            new ApplicationRecords.Row(
                                                                    first.id(),
                                                                    first.revision(),
                                                                    clearingRow,
                                                                    null,
                                                                    Map.of(),
                                                                    "first"),
                                                            second)),
                                            Map.of(),
                                            null,
                                            "generic",
                                            UUID.randomUUID().toString(),
                                            null),
                                    10001);
                    assertThat(cleared.record().values()).containsEntry(unit, null);
                    assertThat(cleared.details().get(rowDef.id()).getFirst().values())
                            .containsEntry(rowUnit, null);
                    assertThat(cleared.details().get(rowDef.id()).getLast().values())
                            .containsEntry(rowUnit, "EACH");
                    assertThat(
                                    records.get(
                                                    appId,
                                                    target.objectId(),
                                                    cleared.record().id(),
                                                    10001)
                                            .record()
                                            .values())
                            .containsEntry(unit, null);
                    var beforeOmittedUpdate =
                            records.get(appId, target.objectId(), cleared.record().id(), 10001);
                    assertThat(beforeOmittedUpdate.details().get(rowDef.id())).hasSize(2);
                    var withoutDetails =
                            records.save(
                                    new ApplicationRecords.Save(
                                            appId,
                                            target.objectId(),
                                            cleared.record().id(),
                                            cleared.record().revision(),
                                            Map.of(name, "只修改主表"),
                                            Map.of(),
                                            Map.of(),
                                            null,
                                            "main_only",
                                            UUID.randomUUID().toString(),
                                            null),
                                    10001);
                    var afterOmittedUpdate =
                            records.get(
                                    appId, target.objectId(), withoutDetails.record().id(), 10001);
                    assertThat(afterOmittedUpdate.record().values()).containsEntry(name, "只修改主表");
                    assertThat(afterOmittedUpdate.details())
                            .isEqualTo(beforeOmittedUpdate.details());
                    var nullDetails =
                            records.save(
                                    new ApplicationRecords.Save(
                                            appId,
                                            target.objectId(),
                                            withoutDetails.record().id(),
                                            withoutDetails.record().revision(),
                                            Map.of(name, "仍不提交明细"),
                                            null,
                                            Map.of(),
                                            null,
                                            "main_only",
                                            UUID.randomUUID().toString(),
                                            null),
                                    10001);
                    var afterNullDetails =
                            records.get(appId, target.objectId(), nullDetails.record().id(), 10001);
                    assertThat(afterNullDetails.record().values()).containsEntry(name, "仍不提交明细");
                    assertThat(afterNullDetails.details()).isEqualTo(beforeOmittedUpdate.details());
                });
    }

    private Detail create(Definition definition) {
        var app =
                LegacyTaskEntryFixtures.seed(
                        applications,
                        new Save(
                                null,
                                null,
                                fixture.prefix + "app",
                                "应用回归",
                                "事务回滚夹具",
                                "AppstoreOutlined",
                                definition),
                        10001);
        grantApplicationObjects(app.application().id());
        return app;
    }

    /** 读取对象当前共享上限修订号；引用即授权会先写默认上限，避免用例硬编码修订号。 */
    private int grantRevision(
            com.richuang.os.nocode.application.service.sharing.ObjectSharingService sharing,
            String objectId,
            String app) {
        return sharing.forApplication(app).stream()
                .filter(g -> g.objectId().equals(objectId))
                .mapToInt(ObjectSharing.Grant::revision)
                .findFirst()
                .orElse(0);
    }

    @Test
    void publishedDynamicFormRulesAreEnforcedByRealSaveAndRollbackFailures() {
        rollback(
                () -> {
                    var request = fixture.createRequest("dynamicform");
                    var ref =
                            reference(
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
                                                    fixture.field("note", "note", "TEXT", 1)),
                                            List.of()));
                    var d = objectApi.getVersion(ref.objectId(), ref.versionNo()).definition();
                    String name =
                            d.fields().stream()
                                    .filter(f -> f.code().equals("name"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    String note =
                            d.fields().stream()
                                    .filter(f -> f.code().equals("note"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    var nameExpression =
                            new DocumentPolicy.Expression("FIELD", name, null, null, List.of());
                    var required =
                            new DocumentPolicy.Expression(
                                    "EQ",
                                    null,
                                    null,
                                    null,
                                    List.of(
                                            nameExpression,
                                            new DocumentPolicy.Expression(
                                                    "VALUE", null, null, "采购", List.of())));
                    var locked =
                            new DocumentPolicy.Expression(
                                    "EQ",
                                    null,
                                    null,
                                    null,
                                    List.of(
                                            nameExpression,
                                            new DocumentPolicy.Expression(
                                                    "VALUE", null, null, "锁定", List.of())));
                    var form =
                            new ApplicationUi.Form(
                                    ref.objectId(),
                                    List.of(
                                            new ApplicationUi.Node(
                                                    "name_node",
                                                    "FIELD",
                                                    name,
                                                    null,
                                                    null,
                                                    null,
                                                    List.of()),
                                            new ApplicationUi.Node(
                                                    "note_node",
                                                    "FIELD",
                                                    note,
                                                    null,
                                                    null,
                                                    null,
                                                    List.of(),
                                                    null,
                                                    new ApplicationUi.FieldPresentation(
                                                            "说明",
                                                            null,
                                                            null,
                                                            false,
                                                            null,
                                                            new ApplicationUi.FieldBehavior(
                                                                    null, required, locked,
                                                                    false)))),
                                    List.of());
                    var resource =
                            new Resource(
                                    "form",
                                    "FORM",
                                    "dynamic_form",
                                    "动态表单",
                                    mapper.convertValue(
                                            form,
                                            new com.fasterxml.jackson.core.type.TypeReference<
                                                    Map<String, Object>>() {}));
                    var app = create(new Definition(List.of(ref), List.of(resource)));
                    String appId = app.application().id();
                    applications.publish(
                            new Revision(appId, app.application().revision(), "动态表单沙箱验收"), 10001);
                    var records =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.runtime.service.record.RecordService
                                            .class);
                    assertThatThrownBy(
                                    () ->
                                            records.save(
                                                    new ApplicationRecords.Save(
                                                            appId,
                                                            ref.objectId(),
                                                            null,
                                                            null,
                                                            Map.of(name, "采购"),
                                                            Map.of(),
                                                            Map.of(),
                                                            null,
                                                            "form",
                                                            UUID.randomUUID().toString(),
                                                            null),
                                                    10001))
                            .hasMessageContaining("条件必填");
                    var saved =
                            records.save(
                                    new ApplicationRecords.Save(
                                            appId,
                                            ref.objectId(),
                                            null,
                                            null,
                                            Map.of(name, "采购", note, "正常说明"),
                                            Map.of(),
                                            Map.of(),
                                            null,
                                            "form",
                                            UUID.randomUUID().toString(),
                                            null),
                                    10001);
                    assertThat(saved.record().values()).containsEntry(note, "正常说明");
                    assertThatThrownBy(
                                    () ->
                                            records.save(
                                                    new ApplicationRecords.Save(
                                                            appId,
                                                            ref.objectId(),
                                                            saved.record().id(),
                                                            saved.record().revision(),
                                                            Map.of(name, "锁定", note, "越过只读"),
                                                            Map.of(),
                                                            Map.of(),
                                                            null,
                                                            "form",
                                                            UUID.randomUUID().toString(),
                                                            null),
                                                    10001))
                            .hasMessageContaining("只读");
                    assertThat(
                                    records.get(appId, ref.objectId(), saved.record().id(), 10001)
                                            .record()
                                            .values())
                            .containsEntry(name, "采购")
                            .containsEntry(note, "正常说明");
                });
    }

    @Test
    void detailCandidatesFollowCurrentParentAndFillSnapshotsWithinPublishedRowBinding() {
        rollback(
                () -> {
                    var productRequest = fixture.createRequest("detailproduct");
                    var product =
                            reference(
                                    new SaveObjectDraft(
                                            null,
                                            null,
                                            productRequest.objectCode(),
                                            productRequest.objectName(),
                                            null,
                                            productRequest.tableName(),
                                            productRequest.titleFieldKey(),
                                            List.of(
                                                    productRequest.fields().getFirst(),
                                                    fixture.field(
                                                            "category", "category", "TEXT", 1)),
                                            List.of()));
                    var productDefinition =
                            objectApi
                                    .getVersion(product.objectId(), product.versionNo())
                                    .definition();
                    String productName =
                            productDefinition.fields().stream()
                                    .filter(f -> f.code().equals("name"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    String category =
                            productDefinition.fields().stream()
                                    .filter(f -> f.code().equals("category"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    var detail =
                            new DataCenter.Detail(
                                    null,
                                    "items",
                                    "明细",
                                    "biz_" + fixture.prefix + "_detail_fill",
                                    "ACTIVE",
                                    List.of(fixture.field("snapshot", "snapshot", "TEXT", 0)),
                                    Map.of(),
                                    List.of());
                    var order =
                            reference(
                                    fixture.createRequest("detailorder"),
                                    List.of(
                                            new DataCenter.Relation(
                                                    null,
                                                    "product",
                                                    "产品",
                                                    "REFERENCE",
                                                    product.objectId(),
                                                    null,
                                                    null,
                                                    false,
                                                    "RESTRICT",
                                                    "detail:items")),
                                    List.of(detail));
                    var d = objectApi.getVersion(order.objectId(), order.versionNo()).definition();
                    var line = d.details().getFirst();
                    String name = d.fields().getFirst().id(),
                            relation = d.relations().getFirst().fieldId();
                    String snapshot =
                            line.fields().stream()
                                    .filter(f -> f.code().equals("snapshot"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    var rowNodes =
                            line.fields().stream()
                                    .map(
                                            f ->
                                                    new ApplicationUi.Node(
                                                            "row_" + f.id(),
                                                            "FIELD",
                                                            f.id(),
                                                            null,
                                                            null,
                                                            null,
                                                            List.of(),
                                                            null,
                                                            new ApplicationUi.FieldPresentation(
                                                                    f.name(),
                                                                    null,
                                                                    null,
                                                                    false,
                                                                    f.id().equals(relation)
                                                                            ? new SelectionFields
                                                                                    .Presentation(
                                                                                    "AUTO",
                                                                                    List.of(),
                                                                                    false, name,
                                                                                    category, null)
                                                                            : null,
                                                                    null,
                                                                    f.id().equals(snapshot)
                                                                            ? new FormFills.Binding(
                                                                                    relation,
                                                                                    productName,
                                                                                    "SOURCE_CHANGE")
                                                                            : null)))
                                    .toList();
                    var form =
                            new ApplicationUi.Form(
                                    order.objectId(),
                                    List.of(
                                            new ApplicationUi.Node(
                                                    "name", "FIELD", name, null, null, null,
                                                    List.of())),
                                    List.of(line.id()),
                                    null,
                                    Map.of(line.id(), rowNodes));
                    var app =
                            create(
                                    new Definition(
                                            List.of(product, order),
                                            List.of(
                                                    new Resource(
                                                            "form",
                                                            "FORM",
                                                            "detail_form",
                                                            "明细配置",
                                                            mapper.convertValue(
                                                                    form,
                                                                    new com.fasterxml.jackson.core
                                                                                    .type
                                                                                    .TypeReference<
                                                                            Map<
                                                                                    String,
                                                                                    Object>>() {})))));
                    String appId = app.application().id();
                    applications.publish(
                            new Revision(appId, app.application().revision(), "主从候选与快照"), 10001);
                    var records =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.runtime.service.record.RecordService
                                            .class);
                    var selected =
                            records.save(
                                    new ApplicationRecords.Save(
                                            appId,
                                            product.objectId(),
                                            null,
                                            null,
                                            Map.of(productName, "产品 A", category, "A"),
                                            Map.of(),
                                            Map.of(),
                                            null,
                                            null,
                                            UUID.randomUUID().toString(),
                                            null),
                                    10001);
                    assertThat(
                                    records.formFill(
                                            new FormFills.Query(
                                                    appId,
                                                    order.objectId(),
                                                    "form",
                                                    relation,
                                                    selected.record().id(),
                                                    line.id(),
                                                    null),
                                            10001))
                            .containsExactly(entry(snapshot, "产品 A"));
                    var candidates =
                            records.selection(
                                    new SelectionFields.Query(
                                            appId,
                                            order.objectId(),
                                            line.id(),
                                            relation,
                                            null,
                                            1,
                                            10,
                                            List.of(),
                                            null,
                                            "form",
                                            Map.of(name, "A")),
                                    10001);
                    assertThat(candidates.options())
                            .extracting(SelectionFields.Option::value)
                            .containsExactly(selected.record().id());
                    assertThat(
                                    records.selection(
                                                    new SelectionFields.Query(
                                                            appId,
                                                            order.objectId(),
                                                            line.id(),
                                                            relation,
                                                            null,
                                                            1,
                                                            10,
                                                            List.of(),
                                                            null,
                                                            "form",
                                                            Map.of(name, "B")),
                                                    10001)
                                            .options())
                            .isEmpty();
                    var stored =
                            records.save(
                                    new ApplicationRecords.Save(
                                            appId,
                                            order.objectId(),
                                            null,
                                            null,
                                            Map.of(name, "A"),
                                            Map.of(
                                                    line.id(),
                                                    List.of(
                                                            new ApplicationRecords.Row(
                                                                    null,
                                                                    null,
                                                                    Map.of(
                                                                            relation,
                                                                            selected.record().id(),
                                                                            snapshot,
                                                                            "产品 A"),
                                                                    null,
                                                                    Map.of(),
                                                                    "item-a"))),
                                            Map.of(),
                                            null,
                                            "form",
                                            UUID.randomUUID().toString(),
                                            null),
                                    10001);
                    assertThatThrownBy(
                                    () ->
                                            records.save(
                                                    new ApplicationRecords.Save(
                                                            appId,
                                                            order.objectId(),
                                                            stored.record().id(),
                                                            stored.record().revision(),
                                                            Map.of(name, "B"),
                                                            stored.details(),
                                                            Map.of(),
                                                            null,
                                                            "form",
                                                            UUID.randomUUID().toString(),
                                                            null),
                                                    10001))
                            .hasMessageContaining("联动条件");
                    assertThat(
                                    records.get(
                                                    appId,
                                                    order.objectId(),
                                                    stored.record().id(),
                                                    10001)
                                            .record()
                                            .values())
                            .containsEntry(name, "A");
                });
    }

    @Test
    void relatedFillUsesPublishedBindingAndTaskEntryIntersectionWithoutGrantingApplicationAccess() {
        rollback(
                () -> {
                    var supplier = reference(fixture.createRequest("fillsupplier"));
                    var supplierDefinition =
                            objectApi
                                    .getVersion(supplier.objectId(), supplier.versionNo())
                                    .definition();
                    String supplierName = supplierDefinition.fields().getFirst().id();
                    var request = fixture.createRequest("fillorder");
                    var order =
                            reference(
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
                                                    fixture.field("note", "note", "TEXT", 1)),
                                            List.of()),
                                    List.of(
                                            new DataCenter.Relation(
                                                    null,
                                                    "supplier",
                                                    "供应商",
                                                    "REFERENCE",
                                                    supplier.objectId(),
                                                    null,
                                                    null,
                                                    false,
                                                    "RESTRICT")));
                    var d = objectApi.getVersion(order.objectId(), order.versionNo()).definition();
                    String note =
                            d.fields().stream()
                                    .filter(f -> f.code().equals("note"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    String source = d.relations().getFirst().fieldId();
                    var nodes =
                            d.fields().stream()
                                    .map(
                                            f ->
                                                    new ApplicationUi.Node(
                                                            "node_" + f.id(),
                                                            "FIELD",
                                                            f.id(),
                                                            null,
                                                            null,
                                                            null,
                                                            List.of(),
                                                            null,
                                                            f.id().equals(note)
                                                                    ? new ApplicationUi
                                                                            .FieldPresentation(
                                                                            "供应商名称快照",
                                                                            null,
                                                                            null,
                                                                            false,
                                                                            null,
                                                                            null,
                                                                            new FormFills.Binding(
                                                                                    source,
                                                                                    supplierName,
                                                                                    "SOURCE_CHANGE"))
                                                                    : null))
                                    .toList();
                    var form = new ApplicationUi.Form(order.objectId(), nodes, List.of());
                    var formResource =
                            new Resource(
                                    "fillform",
                                    "FORM",
                                    "fill_form",
                                    "关联填充表单",
                                    mapper.convertValue(
                                            form,
                                            new com.fasterxml.jackson.core.type.TypeReference<
                                                    Map<String, Object>>() {}));
                    var rootFields =
                            d.fields().stream()
                                    .map(FieldDefinition::id)
                                    .collect(java.util.stream.Collectors.toSet());
                    var rootGrant =
                            new ApplicationAuthorization.ObjectGrant(
                                    order.objectId(),
                                    Set.of("READ", "CREATE"),
                                    "OWN",
                                    rootFields,
                                    rootFields,
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of());
                    var sourceGrant =
                            new ApplicationAuthorization.ObjectGrant(
                                    supplier.objectId(),
                                    Set.of("READ"),
                                    "ALL",
                                    Set.of(supplierName),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of());
                    var entryConfig =
                            new TaskEntries.Config(
                                    order.objectId(),
                                    null,
                                    "fillform",
                                    "FORM",
                                    "填充测试",
                                    null,
                                    null,
                                    0,
                                    List.of(rootGrant, sourceGrant));
                    var entryResource =
                            new Resource(
                                    "entry",
                                    "TASK_ENTRY",
                                    "fill_entry",
                                    "关联办理",
                                    mapper.convertValue(
                                            entryConfig,
                                            new com.fasterxml.jackson.core.type.TypeReference<
                                                    Map<String, Object>>() {}));
                    var app =
                            create(
                                    new Definition(
                                            List.of(supplier, order),
                                            List.of(formResource, entryResource)));
                    String appId = app.application().id();
                    applications.publish(
                            new Revision(appId, app.application().revision(), "关联填充沙箱验收"), 10001);
                    var records =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.runtime.service.record.RecordService
                                            .class);
                    var saved =
                            records.save(
                                    new ApplicationRecords.Save(
                                            appId,
                                            supplier.objectId(),
                                            null,
                                            null,
                                            Map.of(supplierName, "沙箱供应商"),
                                            Map.of(),
                                            Map.of(),
                                            null,
                                            null,
                                            UUID.randomUUID().toString(),
                                            null),
                                    10001);
                    var query =
                            new FormFills.Query(
                                    appId,
                                    order.objectId(),
                                    "fillform",
                                    source,
                                    saved.record().id());
                    assertThat(records.formFill(query, 10001))
                            .containsExactly(entry(note, "沙箱供应商"));
                    assertThat(
                                    records.formFill(
                                            new FormFills.Query(
                                                    appId,
                                                    order.objectId(),
                                                    "fillform",
                                                    source,
                                                    null),
                                            10001))
                            .isEmpty();
                    assertThatThrownBy(
                                    () ->
                                            records.formFill(
                                                    new FormFills.Query(
                                                            appId,
                                                            order.objectId(),
                                                            "fillform",
                                                            note,
                                                            saved.record().id()),
                                                    10001))
                            .hasMessageContaining("未配置");
                    var entries =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.runtime.service.task
                                            .TaskEntryRuntimeService.class);
                    var policies =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.application.service.task
                                            .TaskEntryPolicyService.class);
                    long employee = 21004;
                    var policy = policies.get(appId, "entry");
                    var updated =
                            policies.save(
                                    new TaskEntries.SavePolicy(
                                            appId,
                                            "entry",
                                            policy.revision(),
                                            true,
                                            List.of(
                                                    new ApplicationAuthorization.Member(
                                                            "USER",
                                                            Long.toString(employee),
                                                            List.of(rootGrant, sourceGrant)))),
                                    10001);
                    var locator =
                            new TaskEntries.Locator(
                                    appId, "entry", applications.published(appId).versionNo());
                    assertThatThrownBy(() -> records.formFill(query, employee))
                            .isInstanceOf(RuntimeException.class);
                    assertThat(entries.formFill(new TaskEntries.FormFill(locator, query), employee))
                            .containsExactly(entry(note, "沙箱供应商"));
                    policies.save(
                            new TaskEntries.SavePolicy(
                                    appId,
                                    "entry",
                                    updated.revision(),
                                    true,
                                    List.of(
                                            new ApplicationAuthorization.Member(
                                                    "USER",
                                                    Long.toString(employee),
                                                    List.of(rootGrant)))),
                            10001);
                    assertThatThrownBy(
                                    () ->
                                            entries.formFill(
                                                    new TaskEntries.FormFill(locator, query),
                                                    employee))
                            .isInstanceOf(RuntimeException.class);
                });
    }

    /** 设计预览按请求中的草稿对象版本与未发布表单计算带入，不读取已发布应用快照。 */
    @Test
    void draftFormFillPreviewUsesUnpublishedFormAndRejectsNonDesigner() {
        rollback(
                () -> {
                    var supplier = reference(fixture.createRequest("previewfill"));
                    var supplierDefinition =
                            objectApi
                                    .getVersion(supplier.objectId(), supplier.versionNo())
                                    .definition();
                    String supplierName = supplierDefinition.fields().getFirst().id();
                    var request = fixture.createRequest("previeworder");
                    var order =
                            reference(
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
                                                    fixture.field("note", "note", "TEXT", 1)),
                                            List.of()),
                                    List.of(
                                            new DataCenter.Relation(
                                                    null,
                                                    "supplier",
                                                    "供应商",
                                                    "REFERENCE",
                                                    supplier.objectId(),
                                                    null,
                                                    null,
                                                    false,
                                                    "RESTRICT")));
                    var d = objectApi.getVersion(order.objectId(), order.versionNo()).definition();
                    String note =
                            d.fields().stream()
                                    .filter(f -> f.code().equals("note"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    String source = d.relations().getFirst().fieldId();
                    var rootFields =
                            d.fields().stream()
                                    .map(FieldDefinition::id)
                                    .collect(java.util.stream.Collectors.toSet());
                    var rootGrant =
                            new ApplicationAuthorization.ObjectGrant(
                                    order.objectId(),
                                    Set.of("READ", "CREATE"),
                                    "OWN",
                                    rootFields,
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of());
                    var sourceGrant =
                            new ApplicationAuthorization.ObjectGrant(
                                    supplier.objectId(),
                                    Set.of("READ", "CREATE"),
                                    "ALL",
                                    Set.of(supplierName),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of());
                    var plainNodes =
                            d.fields().stream()
                                    .map(
                                            f ->
                                                    new ApplicationUi.Node(
                                                            "node_" + f.id(),
                                                            "FIELD",
                                                            f.id(),
                                                            null,
                                                            null,
                                                            null,
                                                            List.of(),
                                                            null,
                                                            null))
                                    .toList();
                    var formResource =
                            new Resource(
                                    "preview_form",
                                    "FORM",
                                    "fill_preview_form",
                                    "预览填充表单",
                                    mapper.convertValue(
                                            new ApplicationUi.Form(
                                                    order.objectId(), plainNodes, List.of()),
                                            new com.fasterxml.jackson.core.type.TypeReference<
                                                    Map<String, Object>>() {}));
                    var app =
                            create(new Definition(List.of(supplier, order), List.of(formResource)));
                    String appId = app.application().id();
                    applications.publish(
                            new Revision(appId, app.application().revision(), "关联带入预览验收"), 10001);
                    var records =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.runtime.service.record.RecordService
                                            .class);
                    var saved =
                            records.save(
                                    new ApplicationRecords.Save(
                                            appId,
                                            supplier.objectId(),
                                            null,
                                            null,
                                            Map.of(supplierName, "预览供应商"),
                                            Map.of(),
                                            Map.of(),
                                            null,
                                            null,
                                            UUID.randomUUID().toString(),
                                            null),
                                    10001);
                    // 已发布表单没有带入配置：正式路径维持原语义，仍是预览草稿的对照面。
                    assertThatThrownBy(
                                    () ->
                                            records.formFill(
                                                    new FormFills.Query(
                                                            appId,
                                                            order.objectId(),
                                                            "preview_form",
                                                            source,
                                                            saved.record().id()),
                                                    10001))
                            .hasMessageContaining("未配置");
                    var draftForm =
                            new ApplicationUi.Form(
                                    order.objectId(),
                                    fillNodes(d, note, source, supplierName),
                                    List.of());
                    var preview =
                            new FormFills.PreviewQuery(
                                    new FormFills.Query(
                                            appId,
                                            order.objectId(),
                                            null,
                                            source,
                                            saved.record().id()),
                                    List.of(supplier, order),
                                    draftForm);
                    assertThat(records.previewFormFill(preview, 10001))
                            .containsExactly(entry(note, "预览供应商"));
                    assertThatThrownBy(
                                    () ->
                                            records.previewFormFill(
                                                    new FormFills.PreviewQuery(
                                                            preview.query(),
                                                            List.of(supplier, order),
                                                            new ApplicationUi.Form(
                                                                    order.objectId(),
                                                                    fillNodes(
                                                                            d, note, source, note),
                                                                    List.of())),
                                                    10001))
                            .hasMessageContaining("来源字段不存在");
                    assertThatThrownBy(() -> records.previewFormFill(preview, 21004))
                            .isInstanceOf(
                                    org.springframework.security.access.AccessDeniedException
                                            .class);
                    assertThat(records.previewFormFill(preview, 10001))
                            .containsExactly(entry(note, "预览供应商"));
                });
    }

    /** 目标字段带带入配置、其余字段保持原样的完整节点集。 */
    private static List<ApplicationUi.Node> fillNodes(
            DataCenter.Definition definition, String target, String source, String valueField) {
        return definition.fields().stream()
                .map(
                        f ->
                                new ApplicationUi.Node(
                                        "node_" + f.id(),
                                        "FIELD",
                                        f.id(),
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        f.id().equals(target)
                                                ? new ApplicationUi.FieldPresentation(
                                                        "供应商名称快照",
                                                        null,
                                                        null,
                                                        false,
                                                        null,
                                                        null,
                                                        new FormFills.Binding(
                                                                source,
                                                                valueField,
                                                                "SOURCE_CHANGE"))
                                                : null))
                .toList();
    }

    /** 单值引用目标：来源记录自身的引用字段可以直接带入本表单的同对象引用字段。 */
    @Test
    void referenceTargetFillCarriesRelatedReferenceValue() {
        rollback(
                () -> {
                    var account = reference(fixture.createRequest("fillaccount"));
                    var accountDefinition =
                            objectApi
                                    .getVersion(account.objectId(), account.versionNo())
                                    .definition();
                    String accountName = accountDefinition.fields().getFirst().id();
                    var statementRequest = fixture.createRequest("fillstatement");
                    var statement =
                            reference(
                                    new SaveObjectDraft(
                                            null,
                                            null,
                                            statementRequest.objectCode(),
                                            statementRequest.objectName(),
                                            null,
                                            statementRequest.tableName(),
                                            statementRequest.titleFieldKey(),
                                            List.of(
                                                    statementRequest.fields().getFirst(),
                                                    fixture.field("note", "note", "TEXT", 1)),
                                            List.of()),
                                    List.of(
                                            new DataCenter.Relation(
                                                    null,
                                                    "account",
                                                    "银行账号",
                                                    "REFERENCE",
                                                    account.objectId(),
                                                    null,
                                                    null,
                                                    false,
                                                    "RESTRICT")));
                    var statementDefinition =
                            objectApi
                                    .getVersion(statement.objectId(), statement.versionNo())
                                    .definition();
                    String statementTitle = statementDefinition.fields().getFirst().id();
                    String statementAccount = statementDefinition.relations().getFirst().fieldId();
                    var orderRequest = fixture.createRequest("fillorder");
                    var order =
                            reference(
                                    new SaveObjectDraft(
                                            null,
                                            null,
                                            orderRequest.objectCode(),
                                            orderRequest.objectName(),
                                            null,
                                            orderRequest.tableName(),
                                            orderRequest.titleFieldKey(),
                                            List.of(
                                                    orderRequest.fields().getFirst(),
                                                    fixture.field("note", "note", "TEXT", 1)),
                                            List.of()),
                                    List.of(
                                            new DataCenter.Relation(
                                                    null,
                                                    "bank",
                                                    "银行流水",
                                                    "REFERENCE",
                                                    statement.objectId(),
                                                    null,
                                                    null,
                                                    false,
                                                    "RESTRICT"),
                                            new DataCenter.Relation(
                                                    null,
                                                    "pay_account",
                                                    "银行账户",
                                                    "REFERENCE",
                                                    account.objectId(),
                                                    null,
                                                    null,
                                                    false,
                                                    "RESTRICT")));
                    var d = objectApi.getVersion(order.objectId(), order.versionNo()).definition();
                    String note =
                            d.fields().stream()
                                    .filter(f -> f.code().equals("note"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    String source = relationField(d, "bank");
                    String payAccount = relationField(d, "pay_account");
                    var nodes =
                            d.fields().stream()
                                    .map(
                                            f ->
                                                    new ApplicationUi.Node(
                                                            "node_" + f.id(),
                                                            "FIELD",
                                                            f.id(),
                                                            null,
                                                            null,
                                                            null,
                                                            List.of(),
                                                            null,
                                                            f.id().equals(payAccount)
                                                                    ? new ApplicationUi
                                                                            .FieldPresentation(
                                                                            "银行账户",
                                                                            null,
                                                                            null,
                                                                            false,
                                                                            null,
                                                                            null,
                                                                            new FormFills.Binding(
                                                                                    source,
                                                                                    statementAccount,
                                                                                    "SOURCE_CHANGE"))
                                                                    : null))
                                    .toList();
                    var form = new ApplicationUi.Form(order.objectId(), nodes, List.of());
                    var formResource =
                            new Resource(
                                    "fillform",
                                    "FORM",
                                    "fill_form",
                                    "引用带入表单",
                                    mapper.convertValue(
                                            form,
                                            new com.fasterxml.jackson.core.type.TypeReference<
                                                    Map<String, Object>>() {}));
                    var rootFields =
                            d.fields().stream()
                                    .map(FieldDefinition::id)
                                    .collect(java.util.stream.Collectors.toSet());
                    var rootGrant =
                            new ApplicationAuthorization.ObjectGrant(
                                    order.objectId(),
                                    Set.of("READ", "CREATE"),
                                    "OWN",
                                    rootFields,
                                    rootFields,
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of());
                    var statementGrant =
                            new ApplicationAuthorization.ObjectGrant(
                                    statement.objectId(),
                                    Set.of("READ"),
                                    "ALL",
                                    Set.of(statementTitle, statementAccount),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of());
                    var accountGrant =
                            new ApplicationAuthorization.ObjectGrant(
                                    account.objectId(),
                                    Set.of("READ"),
                                    "ALL",
                                    Set.of(accountName),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of(),
                                    Set.of());
                    var entryConfig =
                            new TaskEntries.Config(
                                    order.objectId(),
                                    null,
                                    "fillform",
                                    "FORM",
                                    "引用带入办理",
                                    null,
                                    null,
                                    0,
                                    List.of(rootGrant, statementGrant, accountGrant));
                    var entryResource =
                            new Resource(
                                    "entry",
                                    "TASK_ENTRY",
                                    "fill_entry",
                                    "引用带入任务入口",
                                    mapper.convertValue(
                                            entryConfig,
                                            new com.fasterxml.jackson.core.type.TypeReference<
                                                    Map<String, Object>>() {}));
                    var app =
                            create(
                                    new Definition(
                                            List.of(account, statement, order),
                                            List.of(formResource, entryResource)));
                    String appId = app.application().id();
                    applications.publish(
                            new Revision(appId, app.application().revision(), "引用带入验收"), 10001);
                    var records =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.runtime.service.record.RecordService
                                            .class);
                    var savedAccount =
                            records.save(
                                    new ApplicationRecords.Save(
                                            appId,
                                            account.objectId(),
                                            null,
                                            null,
                                            Map.of(accountName, "账户甲"),
                                            Map.of(),
                                            Map.of(),
                                            null,
                                            null,
                                            UUID.randomUUID().toString(),
                                            null),
                                    10001);
                    var savedStatement =
                            records.save(
                                    new ApplicationRecords.Save(
                                            appId,
                                            statement.objectId(),
                                            null,
                                            null,
                                            Map.of(
                                                    statementTitle,
                                                    "流水甲",
                                                    statementAccount,
                                                    savedAccount.record().id()),
                                            Map.of(),
                                            Map.of(),
                                            null,
                                            null,
                                            UUID.randomUUID().toString(),
                                            null),
                                    10001);
                    assertThat(
                                    records.formFill(
                                            new FormFills.Query(
                                                    appId,
                                                    order.objectId(),
                                                    "fillform",
                                                    source,
                                                    savedStatement.record().id()),
                                            10001))
                            .containsExactly(entry(payAccount, savedAccount.record().id()));
                    assertThat(
                                    records.formFill(
                                            new FormFills.Query(
                                                    appId,
                                                    order.objectId(),
                                                    "fillform",
                                                    source,
                                                    null),
                                            10001))
                            .isEmpty();
                });
    }

    private static String relationField(DataCenter.Definition definition, String code) {
        return definition.relations().stream()
                .filter(r -> r.code().equals(code))
                .findFirst()
                .orElseThrow()
                .fieldId();
    }

    @Test
    void publishExplainsRevokedSharingAndAcceptsRepairAfterReferenceAutoGrant() {
        rollback(
                () -> {
                    var ref = reference();
                    var app =
                            applications.save(
                                    new Save(
                                            null,
                                            null,
                                            fixture.prefix + "app",
                                            "资产管理验收",
                                            null,
                                            null,
                                            new Definition(List.of(ref), List.of())),
                                    10001);
                    var sharing =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.application.service.sharing
                                            .ObjectSharingService.class);
                    // 引用即授权：保存后默认上限已写入，发布无需数据管理员单独同意。
                    var ceiling =
                            NocodeIntegrationSupport.resolvedPermission(
                                    ref.objectId(), app.application().id());
                    assertThat(ceiling).isNotNull();
                    assertThat(ceiling.actions())
                            .containsExactlyInAnyOrder(
                                    "READ", "CREATE", "UPDATE", "DELETE", "IMPORT", "EXPORT");
                    var request = new Revision(app.application().id(), 0, "发布提示验收");
                    // 撤权后发布须给出对象名、应用名与重新授权指引，且不再提示“尚未授权”。
                    sharing.save(
                            new ObjectSharing.Save(
                                    ref.objectId(), app.application().id(), 1, null, "撤销提示验收"),
                            10001);
                    assertThatThrownBy(() -> applications.publish(request, 10001))
                            .hasMessageContaining("数据对象“验证对象”授予应用“资产管理验收”的共享授权已被撤销")
                            .hasMessageContaining("重新授权")
                            .hasMessageNotContaining("尚未授权");
                    assertThat(applications.releases(app.application().id(), 1, 100).getList())
                            .isEmpty();

                    grantApplicationObjects(app.application().id());
                    assertThat(
                                    applications
                                            .publish(request, 10001)
                                            .application()
                                            .publishedVersion())
                            .isEqualTo(1);
                });
    }

    @Test
    void syncingReferenceResetsDefaultCeilingWhileUnchangedSaveKeepsNarrowing() {
        rollback(
                () -> {
                    var sharing =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.application.service.sharing
                                            .ObjectSharingService.class);
                    var request = fixture.createRequest("syncref");
                    var ref =
                            reference(
                                    new SaveObjectDraft(
                                            null,
                                            null,
                                            request.objectCode(),
                                            "同步授权对象",
                                            null,
                                            request.tableName(),
                                            request.titleFieldKey(),
                                            List.of(request.fields().getFirst()),
                                            List.of()));
                    String name =
                            objectApi
                                    .getVersion(ref.objectId(), ref.versionNo())
                                    .definition()
                                    .fields()
                                    .stream()
                                    .filter(f -> f.code().equals("name"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    // 新引用即写入默认上限：六类操作、全部记录范围、主字段可读写。
                    var created =
                            applications.save(
                                    new Save(
                                            null,
                                            null,
                                            fixture.prefix + "app",
                                            "同步授权验收",
                                            null,
                                            null,
                                            new Definition(List.of(ref), List.of())),
                                    10001);
                    String appId = created.application().id();
                    assertThat(
                                    NocodeIntegrationSupport.resolvedPermission(
                                                    ref.objectId(), appId)
                                            .actions())
                            .containsExactlyInAnyOrder(
                                    "READ", "CREATE", "UPDATE", "DELETE", "IMPORT", "EXPORT");

                    // 数据管理员收紧为只读。
                    sharing.save(
                            new ObjectSharing.Save(
                                    ref.objectId(),
                                    appId,
                                    grantRevision(sharing, ref.objectId(), appId),
                                    new ApplicationAuthorization.ObjectGrant(
                                            ref.objectId(),
                                            Set.of("READ"),
                                            "ALL",
                                            Set.of(name),
                                            Set.of(),
                                            Set.of(),
                                            Set.of()),
                                    "收紧为只读"),
                            10001);
                    assertThat(
                                    NocodeIntegrationSupport.resolvedPermission(
                                                    ref.objectId(), appId)
                                            .actions())
                            .containsExactly("READ");

                    // 未改动引用的保存（仅改应用名）不重写上限，收紧结果保留。
                    var head = applications.get(appId);
                    applications.save(
                            new Save(
                                    appId,
                                    head.application().revision(),
                                    head.application().code(),
                                    "同步授权验收-改名",
                                    null,
                                    null,
                                    head.draft()),
                            10001);
                    assertThat(
                                    NocodeIntegrationSupport.resolvedPermission(
                                                    ref.objectId(), appId)
                                            .actions())
                            .containsExactly("READ");

                    // 对象发布 v2：先开可编辑草稿，再新增一个备注字段。
                    var design =
                            designs.editPublished(
                                    new DataCenter.Revision(
                                            ref.objectId(),
                                            designs.get(ref.objectId()).draft().lockVersion(),
                                            "同步授权 v2"),
                                    10001);
                    var current = design.draft();
                    var withNote =
                            fixture.edit(
                                    current,
                                    java.util.stream.Stream.concat(
                                                    current.fields().stream(),
                                                    java.util.stream.Stream.of(
                                                            fixture.field(
                                                                    "note", "note", "TEXT", 1)))
                                            .toList(),
                                    List.of(),
                                    current.titleFieldId());
                    var changed =
                            designs.save(
                                    new DataCenter.SaveDesign(
                                            withNote,
                                            design.settings(),
                                            design.fieldOptions(),
                                            design.relations(),
                                            design.indexes(),
                                            design.details(),
                                            design.mainBinding()),
                                    10001);
                    var plan =
                            publisher.plan(
                                    new DataCenter.Revision(
                                            changed.draft().id(),
                                            changed.draft().lockVersion(),
                                            null),
                                    10001);
                    assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
                    assertThat(
                                    publisher
                                            .execute(
                                                    new DataCenter.ExecutePlan(
                                                            plan.id(), "同步授权 v2"),
                                                    10001)
                                            .state())
                            .isEqualTo("SUCCEEDED");
                    var v2 = objectApi.getVersion(ref.objectId(), null);
                    var synced = new ObjectReference(v2.objectId(), v2.versionNo(), v2.checksum());
                    assertThat(synced.versionNo()).isGreaterThan(ref.versionNo());

                    // 同步到 v2（2026-10 起反转）：上限只在首次引用时写一次。数据管理员收紧过的只读上限在应用同步对象版本后原样保留，
                    // 不会被重置成默认全量；新字段也不会自动进到这份清单里（上限是「全部」时才自动包含新字段）。
                    var head2 = applications.get(appId);
                    applications.save(
                            new Save(
                                    appId,
                                    head2.application().revision(),
                                    head2.application().code(),
                                    "同步授权验收-改名",
                                    null,
                                    null,
                                    new Definition(List.of(synced), head2.draft().resources())),
                            10001);
                    var ceiling =
                            NocodeIntegrationSupport.resolvedPermission(synced.objectId(), appId);
                    assertThat(ceiling.actions()).containsExactly("READ");
                    String note =
                            v2.definition().fields().stream()
                                    .filter(f -> f.code().equals("note"))
                                    .findFirst()
                                    .orElseThrow()
                                    .id();
                    assertThat(ceiling.readFields()).containsExactly(name).doesNotContain(note);
                    assertThat(ceiling.writeFields()).isEmpty();
                });
    }

    @Test
    void publishListsOnlyMissingNumberRulePermissionsAndAcceptsTheirRepair() {
        rollback(
                () -> {
                    var request = fixture.createRequest("numberobject");
                    var ref =
                            reference(
                                    new SaveObjectDraft(
                                            null,
                                            null,
                                            request.objectCode(),
                                            "资产分类",
                                            null,
                                            request.tableName(),
                                            "number",
                                            List.of(
                                                    new FieldDefinition(
                                                            "number",
                                                            null,
                                                            "asset_number",
                                                            "分类编号",
                                                            "TEXT",
                                                            80,
                                                            null,
                                                            null,
                                                            true,
                                                            true,
                                                            0)),
                                            List.of()));
                    String field =
                            objectApi
                                    .getVersion(ref.objectId(), 1)
                                    .definition()
                                    .fields()
                                    .getFirst()
                                    .id();
                    var rule =
                            new ApplicationCenter.Resource(
                                    "number",
                                    "NUMBER_RULE",
                                    "number",
                                    "分类自动编号",
                                    Map.of(
                                            "objectId",
                                            ref.objectId(),
                                            "fieldId",
                                            field,
                                            "prefix",
                                            "FL-",
                                            "period",
                                            "NONE",
                                            "width",
                                            4));
                    var app = create(new Definition(List.of(ref), List.of(rule)));
                    var sharing =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.application.service.sharing
                                            .ObjectSharingService.class);
                    var publish = new Revision(app.application().id(), 0, "编号权限提示验收");
                    sharing.save(
                            new ObjectSharing.Save(
                                    ref.objectId(),
                                    app.application().id(),
                                    grantRevision(sharing, ref.objectId(), app.application().id()),
                                    new ApplicationAuthorization.ObjectGrant(
                                            ref.objectId(),
                                            Set.of("READ"),
                                            "ALL",
                                            Set.of(field),
                                            Set.of(),
                                            Set.of(),
                                            Set.of()),
                                    "仅查看"),
                            10001);
                    assertThatThrownBy(() -> applications.publish(publish, 10001))
                            .hasMessageContaining("自动编号“分类自动编号”")
                            .hasMessageContaining("数据对象“资产分类”")
                            .hasMessageContaining("“新增”操作权限")
                            .hasMessageContaining("“分类编号”字段的填写和修改权限")
                            .hasMessageContaining("配置数据权限");
                    sharing.save(
                            new ObjectSharing.Save(
                                    ref.objectId(),
                                    app.application().id(),
                                    grantRevision(sharing, ref.objectId(), app.application().id()),
                                    new ApplicationAuthorization.ObjectGrant(
                                            ref.objectId(),
                                            Set.of("READ", "CREATE"),
                                            "ALL",
                                            Set.of(field),
                                            Set.of(),
                                            Set.of(),
                                            Set.of()),
                                    "允许新增"),
                            10001);
                    assertThatThrownBy(() -> applications.publish(publish, 10001))
                            .hasMessageContaining("“分类编号”字段的填写和修改权限")
                            .hasMessageNotContaining("缺少“新增”");
                    sharing.save(
                            new ObjectSharing.Save(
                                    ref.objectId(),
                                    app.application().id(),
                                    grantRevision(sharing, ref.objectId(), app.application().id()),
                                    new ApplicationAuthorization.ObjectGrant(
                                            ref.objectId(),
                                            Set.of("READ", "CREATE"),
                                            "ALL",
                                            Set.of(field),
                                            Set.of(field),
                                            Set.of(),
                                            Set.of()),
                                    "补齐字段权限"),
                            10001);
                    assertThat(
                                    applications
                                            .publish(publish, 10001)
                                            .application()
                                            .publishedVersion())
                            .isEqualTo(1);
                });
    }

    @Test
    void resourceGraphRoundTripsAndRejectsExecutableOrDanglingConfiguration() {
        rollback(
                () -> {
                    var reference = reference();
                    var field =
                            objectApi
                                    .getVersion(reference.objectId(), reference.versionNo())
                                    .definition()
                                    .fields()
                                    .getFirst()
                                    .id();
                    var node =
                            Map.<String, Object>of(
                                    "id",
                                    "field-node",
                                    "type",
                                    "FIELD",
                                    "fieldId",
                                    field,
                                    "children",
                                    List.of());
                    var form =
                            new ApplicationCenter.Resource(
                                    "form1",
                                    "FORM",
                                    "order_form",
                                    "录入表单",
                                    Map.of(
                                            "objectId",
                                            reference.objectId(),
                                            "nodes",
                                            List.of(node),
                                            "detailIds",
                                            List.of()));
                    var view =
                            new ApplicationCenter.Resource(
                                    "view1",
                                    "VIEW",
                                    "order_list",
                                    "订单列表",
                                    Map.of(
                                            "objectId",
                                            reference.objectId(),
                                            "fieldIds",
                                            List.of(field),
                                            "equal",
                                            Map.of(),
                                            "descending",
                                            true,
                                            "pageSize",
                                            20,
                                            "formId",
                                            "form1"));
                    var page =
                            new ApplicationCenter.Resource(
                                    "page1",
                                    "PAGE",
                                    "order_page",
                                    "工作台",
                                    Map.of(
                                            "nodes",
                                            List.of(
                                                    Map.of(
                                                            "id",
                                                            "view-node",
                                                            "type",
                                                            "VIEW",
                                                            "resourceId",
                                                            "view1",
                                                            "children",
                                                            List.of()))));
                    var menu =
                            new ApplicationCenter.Resource(
                                    "menu1",
                                    "MENU",
                                    "order_menu",
                                    "订单管理",
                                    Map.of("targetId", "page1"));
                    var app =
                            create(
                                    new Definition(
                                            List.of(reference), List.of(form, view, page, menu)));
                    applications.publish(new Revision(app.application().id(), 0, "资源图发布"), 10001);
                    assertThat(
                                    applications
                                            .published(app.application().id())
                                            .definition()
                                            .resources())
                            .hasSize(4);
                    assertThatThrownBy(
                                    () ->
                                            applications.normalize(
                                                    new Definition(
                                                            List.of(reference),
                                                            List.of(view, page, menu))))
                            .hasMessageContaining("资源不存在");
                    var unsafe =
                            new ApplicationCenter.Resource(
                                    "page2",
                                    "PAGE",
                                    "unsafe_page",
                                    "不安全配置",
                                    Map.of("nodes", List.of(), "script", "alert(1)"));
                    assertThatThrownBy(
                                    () ->
                                            applications.normalize(
                                                    new Definition(
                                                            List.of(reference), List.of(unsafe))))
                            .hasMessageContaining("格式无效");
                    var duplicate =
                            new ApplicationCenter.Resource(
                                    "form2",
                                    "FORM",
                                    "duplicate_form",
                                    "重复字段",
                                    Map.of(
                                            "objectId",
                                            reference.objectId(),
                                            "nodes",
                                            List.of(
                                                    node,
                                                    Map.of(
                                                            "id",
                                                            "different-node",
                                                            "type",
                                                            "FIELD",
                                                            "fieldId",
                                                            field,
                                                            "children",
                                                            List.of()))));
                    assertThatThrownBy(
                                    () ->
                                            applications.normalize(
                                                    new Definition(
                                                            List.of(reference),
                                                            List.of(duplicate))))
                            .hasMessageContaining("业务表单“重复字段”的主表")
                            .hasMessageContaining("different-node")
                            .hasMessageContaining(field)
                            .hasMessageContaining("重复配置");
                });
    }

    @Test
    void applicationDraftAndPublishedSnapshotAreIndependent() {
        rollback(
                () -> {
                    var reference = reference();
                    var created = create(new Definition(List.of(reference), List.of()));
                    assertThat(created.application().revision()).isZero();
                    assertThat(created.application().publishedVersion()).isNull();
                    assertThatThrownBy(() -> applications.published(created.application().id()))
                            .hasMessageContaining("未发布");
                    var released =
                            applications.publish(
                                    new Revision(created.application().id(), 0, "首个发布"), 10001);
                    var before = applications.published(created.application().id());
                    assertThat(before.versionNo()).isEqualTo(1);
                    applications.save(
                            new Save(
                                    created.application().id(),
                                    released.application().revision(),
                                    created.application().code(),
                                    "修改后的草稿",
                                    null,
                                    null,
                                    Definition.empty()),
                            10001);
                    var after = applications.published(created.application().id());
                    assertThat(after.checksum()).isEqualTo(before.checksum());
                    assertThat(after.application().name()).isEqualTo(created.application().name());
                    assertThat(after.definition().objects()).containsExactly(reference);
                    assertThat(applications.get(created.application().id()).draft().objects())
                            .isEmpty();
                    var dependencies = designs.get(reference.objectId()).dependencies();
                    assertThat(dependencies).anyMatch(d -> d.sourceKey().endsWith(":published"));
                    assertThat(dependencies).noneMatch(d -> d.sourceKey().endsWith(":draft"));
                });
    }

    @Test
    void staleRevisionAndCodeMutationAreRejected() {
        rollback(
                () -> {
                    var app = create(Definition.empty());
                    var edited =
                            applications.save(
                                    new Save(
                                            app.application().id(),
                                            0,
                                            app.application().code(),
                                            "新名称",
                                            null,
                                            null,
                                            app.draft()),
                                    10001);
                    assertThatThrownBy(
                                    () ->
                                            applications.save(
                                                    new Save(
                                                            app.application().id(),
                                                            0,
                                                            app.application().code(),
                                                            "过期修改",
                                                            null,
                                                            null,
                                                            app.draft()),
                                                    10001))
                            .hasMessageContaining("刷新");
                    assertThatThrownBy(
                                    () ->
                                            applications.save(
                                                    new Save(
                                                            app.application().id(),
                                                            1,
                                                            fixture.prefix + "other",
                                                            "改编码",
                                                            null,
                                                            null,
                                                            app.draft()),
                                                    10001))
                            .hasMessageContaining("不可修改");
                    assertThat(edited.application().revision()).isEqualTo(1);
                    assertThat(applications.get(app.application().id()).application().name())
                            .isEqualTo("新名称");
                });
    }

    @Test
    void fixedResourceScopePreservesHistoryAndRestoresCurrentVersionAfterFailure() {
        rollback(
                () -> {
                    var object = reference();
                    var field =
                            objectApi
                                    .getVersion(object.objectId(), object.versionNo())
                                    .definition()
                                    .fields()
                                    .getFirst()
                                    .id();
                    var form =
                            new Resource(
                                    "fixed-form",
                                    "FORM",
                                    "fixed_form",
                                    "历史表单",
                                    Map.of(
                                            "objectId",
                                            object.objectId(),
                                            "nodes",
                                            List.of(
                                                    Map.of(
                                                            "id",
                                                            "fixed-field",
                                                            "type",
                                                            "FIELD",
                                                            "fieldId",
                                                            field,
                                                            "children",
                                                            List.of())),
                                            "detailIds",
                                            List.of()));
                    var created = create(new Definition(List.of(object), List.of(form)));
                    var id = created.application().id();
                    var first = applications.publish(new Revision(id, 0, "V1"), 10001);
                    var release = applications.published(id);
                    var fixed =
                            new com.richuang.os.nocode.api.work.PublishedResourceRef(
                                    id, 1, release.checksum(), form.id(), form.kind());
                    var service =
                            servicesContext.getBean(
                                    com.richuang.os.nocode.application.service.published
                                            .ApplicationPublishedService.class);
                    var edited =
                            applications.save(
                                    new Save(
                                            id,
                                            first.application().revision(),
                                            created.application().code(),
                                            "新版应用",
                                            null,
                                            null,
                                            new Definition(List.of(object), List.of())),
                                    10001);
                    applications.publish(
                            new Revision(id, edited.application().revision(), "V2"), 10001);

                    assertThat(applications.published(id).versionNo()).isEqualTo(2);
                    assertThat(service.resolve(fixed).name()).isEqualTo("历史表单");
                    assertThat(
                                    service.withVersion(
                                            fixed, () -> applications.published(id).versionNo()))
                            .isEqualTo(1);
                    assertThatThrownBy(
                                    () ->
                                            service.withVersion(
                                                    fixed,
                                                    () -> {
                                                        assertThat(
                                                                        applications
                                                                                .published(id)
                                                                                .versionNo())
                                                                .isEqualTo(1);
                                                        throw new IllegalStateException("业务失败");
                                                    }))
                            .hasMessage("业务失败");
                    assertThat(applications.published(id).versionNo()).isEqualTo(2);
                    assertThatThrownBy(
                                    () ->
                                            service.resolve(
                                                    new com.richuang.os.nocode.api.work
                                                            .PublishedResourceRef(
                                                            id,
                                                            1,
                                                            "incorrect",
                                                            form.id(),
                                                            form.kind())))
                            .hasMessageContaining("校验失败");
                    assertThatThrownBy(
                                    () ->
                                            service.resolve(
                                                    new com.richuang.os.nocode.api.work
                                                            .PublishedResourceRef(
                                                            id,
                                                            99,
                                                            release.checksum(),
                                                            form.id(),
                                                            form.kind())))
                            .hasMessageContaining("版本不存在");
                    assertThatThrownBy(
                                    () ->
                                            service.resolve(
                                                    new com.richuang.os.nocode.api.work
                                                            .PublishedResourceRef(
                                                            id,
                                                            2,
                                                            applications.published(id).checksum(),
                                                            form.id(),
                                                            form.kind())))
                            .hasMessageContaining("不存在所引用");
                    applications.status(
                            new Revision(id, applications.get(id).application().revision(), "停用验证"),
                            "DISABLED",
                            10001);
                    assertThatThrownBy(() -> service.resolve(fixed)).hasMessageContaining("停用");
                });
    }

    @Test
    void checksumMismatchAndDraftObjectCannotEnterApplication() {
        rollback(
                () -> {
                    var reference = reference();
                    assertThatThrownBy(
                                    () ->
                                            create(
                                                    new Definition(
                                                            List.of(
                                                                    new ObjectReference(
                                                                            reference.objectId(),
                                                                            reference.versionNo(),
                                                                            "invalid")),
                                                            List.of())))
                            .hasMessageContaining("校验和");
                    var unpublished =
                            service.create(
                                    fixture.createRequest("draft"), 10001, UUID.randomUUID());
                    assertThatThrownBy(() -> objectApi.getVersion(unpublished.id(), 1))
                            .hasMessageContaining("尚未发布");
                });
    }

    @Test
    void disabledApplicationCannotRunAndHistoryIsImmutable() {
        rollback(
                () -> {
                    var app = create(new Definition(List.of(reference()), List.of()));
                    var appId = app.application().id();
                    applications.publish(new Revision(appId, 0, "第一版"), 10001);
                    var firstChecksum =
                            applications.releases(appId, 1, 100).getList().getFirst().checksum();
                    applications.publish(new Revision(appId, 1, "第二版"), 10001);
                    var secondReleases = applications.releases(appId, 1, 100).getList();
                    assertThat(secondReleases).hasSize(2);
                    assertThat(secondReleases.getLast().checksum()).isEqualTo(firstChecksum);
                    var disabled =
                            applications.status(
                                    new Revision(app.application().id(), 2, "暂停业务运行"),
                                    "DISABLED",
                                    10001);
                    assertThat(disabled.application().revision()).isEqualTo(3);
                    assertThatThrownBy(() -> applications.published(app.application().id()))
                            .hasMessageContaining("停用");
                });
    }
}
