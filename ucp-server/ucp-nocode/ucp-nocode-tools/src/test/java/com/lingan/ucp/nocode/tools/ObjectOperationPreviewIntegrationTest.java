package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.metadata.service.object.ObjectOperationPreviewService;

import org.junit.jupiter.api.*;

import java.util.*;

/** 当前开发库验证生命周期诊断不写草稿／业务行，执行仍复查；仅操作本测试随机前缀夹具。 */
class ObjectOperationPreviewIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ObjectOperationPreviewService previews;

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
        previews = servicesContext.getBean(ObjectOperationPreviewService.class);
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    @Test
    void deleteCountsAllRowsAndExecutionRetainsItsProtection() {
        Design design = publish(create());
        jdbc.update(
                "INSERT INTO public."
                        + design.draft().tableName()
                        + " (name,notes,deleted) VALUES ('a','secret',0),('b','history',1)");
        ObjectOperationPreview.Result preview = preview(design, "delete", null, null);
        assertThat(preview.allowed()).isFalse();
        assertThat(preview.dataScopes())
                .anySatisfy(
                        scope -> {
                            assertThat(scope.rowCount()).isEqualTo(2L);
                            assertThat(scope.retained()).isTrue();
                        });
        assertThat(preview.impacts())
                .anySatisfy(
                        impact -> {
                            assertThat(impact.code()).isEqualTo("BUSINESS_ROWS");
                            assertThat(impact.message()).contains("2 条", "逻辑删除");
                            assertThat(impact.resolution()).contains("授权业务入口");
                        });
        assertThat(designs.get(design.draft().id()).draft().lockVersion())
                .isEqualTo(design.draft().lockVersion());
        assertThatThrownBy(
                        () ->
                                designs.lifecycle(
                                        new Revision(
                                                design.draft().id(),
                                                design.draft().lockVersion(),
                                                "测试"),
                                        "delete",
                                        10001))
                .hasMessageContaining("不能删除对象");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public." + design.draft().tableName(),
                                Long.class))
                .isEqualTo(2L);
    }

    @Test
    void proposedDraftCanRemoveIndexWithoutWritingSavedMetadata() {
        Design initial = create();
        String field = notes(initial).id();
        Design design =
                designs.save(
                        save(
                                initial,
                                List.of(
                                        new Index(
                                                null,
                                                "notes_index",
                                                "备注索引",
                                                false,
                                                List.of(field))),
                                List.of()),
                        10001);
        ObjectOperationPreview.Result blocked = preview(design, "disable_field", field, null);
        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.impacts())
                .anyMatch(
                        impact ->
                                impact.code().equals("INDEX_FIELD")
                                        && impact.location().contains("备注索引"));
        SaveDesign proposed = save(design, List.of(), List.of());
        assertThat(preview(design, "disable_field", field, proposed).allowed()).isTrue();
        assertThat(designs.get(design.draft().id()).indexes()).hasSize(1);
        assertThat(designs.get(design.draft().id()).draft().fields())
                .anyMatch(item -> field.equals(item.id()));
        assertThat(preview(design, "disable_field", design.draft().titleFieldId(), null).impacts())
                .anyMatch(impact -> impact.code().equals("TITLE_FIELD") && impact.blocking());
    }

    @Test
    void appReferenceWarnsForFieldButBlocksWholeObject() {
        Design design = publish(create());
        design =
                designs.editPublished(
                        new Revision(design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        DataObjectApi api = servicesContext.getBean(DataObjectApi.class);
        String field = notes(design).id();
        api.registerDependency(
                new Dependency(
                        "APP",
                        "application:999999999:published",
                        "夹具引用应用",
                        design.draft().id(),
                        List.of(field)),
                10001);
        ObjectOperationPreview.Result fieldPreview = preview(design, "disable_field", field, null);
        assertThat(fieldPreview.impacts())
                .anyMatch(
                        impact ->
                                "夹具引用应用".equals(impact.sourceName())
                                        && !impact.blocking()
                                        && "/nocode-app/workspace?id=999999999"
                                                .equals(impact.route()));
        assertThat(preview(design, "disable", null, null).impacts())
                .anyMatch(impact -> "夹具引用应用".equals(impact.sourceName()) && impact.blocking());
    }

    @Test
    void restoreKeepsHistoricalIdentityAndReportsMissingPhysicalColumn() {
        Design design = publish(create());
        String field = notes(design).id();
        jdbc.update(
                "INSERT INTO public."
                        + design.draft().tableName()
                        + " (name,notes) VALUES ('a','kept')");
        design =
                designs.editPublished(
                        new Revision(design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        design = designs.save(save(design, List.of(), List.of(field)), 10001);
        ObjectOperationPreview.Result restored = preview(design, "restore_field", field, null);
        assertThat(restored.allowed()).isTrue();
        assertThat(restored.dataScopes())
                .singleElement()
                .satisfies(
                        scope -> {
                            assertThat(scope.rowCount()).isEqualTo(1L);
                            assertThat(scope.nonNullCount()).isEqualTo(1L);
                            assertThat(scope.columnName()).isEqualTo("notes");
                        });
        assertThat(designs.inactiveFields(design.draft().id(), null))
                .anyMatch(item -> field.equals(item.field().id()));
        jdbc.execute("ALTER TABLE public." + design.draft().tableName() + " DROP COLUMN notes");
        ObjectOperationPreview.Result missing = preview(design, "restore_field", field, null);
        assertThat(missing.allowed()).isFalse();
        assertThat(missing.impacts()).anyMatch(impact -> impact.code().equals("MISSING_COLUMN"));
        assertThat(missing.dataScopes())
                .singleElement()
                .satisfies(scope -> assertThat(scope.rowCount()).isNull());
    }

    @Test
    void restoreConstraintConflictsWarnUntilPublishButMissingColumnStillBlocks() {
        Design design = publish(create());
        String fieldId = notes(design).id();
        jdbc.update(
                "INSERT INTO public."
                        + design.draft().tableName()
                        + " (name,notes) VALUES"
                        + " ('empty',NULL),('first','duplicate'),('second','duplicate')");
        design =
                designs.editPublished(
                        new Revision(design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        SaveDesign base = save(design, List.of(), List.of());
        List<FieldDefinition> tightened =
                base.draft().fields().stream()
                        .map(
                                field ->
                                        field.id().equals(fieldId)
                                                ? new FieldDefinition(
                                                        field.key(),
                                                        field.id(),
                                                        field.code(),
                                                        field.name(),
                                                        field.type(),
                                                        field.length(),
                                                        field.precision(),
                                                        field.scale(),
                                                        true,
                                                        true,
                                                        field.sort())
                                                : field)
                        .toList();
        SaveObjectDraft draft = base.draft();
        design =
                designs.save(
                        new SaveDesign(
                                new SaveObjectDraft(
                                        draft.id(),
                                        draft.expectedLockVersion(),
                                        draft.objectCode(),
                                        draft.objectName(),
                                        draft.description(),
                                        draft.tableName(),
                                        draft.titleFieldKey(),
                                        tightened,
                                        List.of()),
                                base.settings(),
                                base.fieldOptions(),
                                base.relations(),
                                base.indexes(),
                                base.details()),
                        10001);
        design = designs.save(save(design, List.of(), List.of(fieldId)), 10001);
        ObjectOperationPreview.Result warning = preview(design, "restore_field", fieldId, null);
        assertThat(warning.allowed()).isTrue();
        assertThat(warning.summary()).contains("可恢复到草稿", "发布前必须解决");
        assertThat(
                        warning.impacts().stream()
                                .filter(impact -> impact.code().equals("FIELD_CONSTRAINT")))
                .hasSize(2)
                .allSatisfy(
                        impact -> {
                            assertThat(impact.blocking()).isFalse();
                            assertThat(impact.message()).contains("发布前待处理");
                            assertThat(impact.resolution()).contains("先恢复到草稿", "字段配置");
                        });
        assertThat(designs.inactiveFields(design.draft().id(), null))
                .anyMatch(item -> item.field().id().equals(fieldId));
        jdbc.execute("ALTER TABLE public." + design.draft().tableName() + " DROP COLUMN notes");
        ObjectOperationPreview.Result missing = preview(design, "restore_field", fieldId, null);
        assertThat(missing.allowed()).isFalse();
        assertThat(missing.impacts())
                .anyMatch(impact -> impact.code().equals("MISSING_COLUMN") && impact.blocking());
    }

    @Test
    void staleRevisionNeverChangesToLatestAutomatically() {
        Design design = create();
        designs.save(save(design, List.of(), List.of()), 10001);
        assertThatThrownBy(() -> preview(design, "disable", null, null))
                .hasMessageContaining("对象已变化");
    }

    private Design create() {
        SaveObjectDraft base = fixture.createRequest("operation");
        List<FieldDefinition> fields = new ArrayList<>(base.fields());
        fields.add(fixture.field("notes", "notes", "TEXTAREA", 1));
        SaveObjectDraft draft =
                new SaveObjectDraft(
                        null,
                        null,
                        base.objectCode(),
                        base.objectName(),
                        null,
                        base.tableName(),
                        base.titleFieldKey(),
                        fields,
                        List.of());
        return designs.save(
                new SaveDesign(
                        draft, Settings.defaults(), Map.of(), List.of(), List.of(), List.of()),
                10001);
    }

    private Design publish(Design design) {
        PublishPlan plan =
                publisher.plan(
                        new Revision(design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(publisher.execute(new ExecutePlan(plan.id(), "生命周期预检夹具"), 10001).state())
                .isEqualTo("SUCCEEDED");
        return designs.get(design.draft().id());
    }

    private ObjectOperationPreview.Result preview(
            Design design, String operation, String field, SaveDesign proposed) {
        return previews.preview(
                new ObjectOperationPreview.Request(
                        design.draft().id(),
                        design.draft().lockVersion(),
                        operation,
                        field,
                        null,
                        proposed));
    }

    private static FieldDefinition notes(Design design) {
        return design.draft().fields().stream()
                .filter(field -> field.code().equals("notes"))
                .findFirst()
                .orElseThrow();
    }

    private static SaveDesign save(Design design, List<Index> indexes, List<String> removed) {
        ObjectDraft draft = design.draft();
        List<FieldDefinition> fields =
                draft.fields().stream().filter(field -> !removed.contains(field.id())).toList();
        Set<String> keys = new HashSet<>();
        fields.forEach(field -> keys.add(field.key()));
        Map<String, FieldOptions> options = new LinkedHashMap<>();
        design.fieldOptions()
                .forEach(
                        (key, value) -> {
                            if (keys.contains(key)) options.put(key, value);
                        });
        return new SaveDesign(
                new SaveObjectDraft(
                        draft.id(),
                        draft.lockVersion(),
                        draft.objectCode(),
                        draft.objectName(),
                        draft.description(),
                        draft.tableName(),
                        draft.titleFieldId(),
                        fields,
                        removed,
                        design.settings().titleTemplate(),
                        draft.category()),
                design.settings(),
                options,
                design.relations(),
                indexes,
                design.details(),
                design.mainBinding());
    }
}
