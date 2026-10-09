package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.schema.service.convert.FieldSwitchPreviewService;

import org.junit.jupiter.api.*;

import java.util.*;

/** 验证草稿反复切换显式对象引用时，物理列类型始终以已发布版本为基线。 */
class ObjectFieldDraftConversionIntegrationTest {
    private NocodeIntegrationSupport fixture;

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
    }

    @AfterEach
    void cleanup() {
        writeFailure.clear();
        fixture.clean();
    }

    private Design published(String suffix) {
        return published(suffix, false);
    }

    private Design published(String suffix, boolean sensitive) {
        SaveObjectDraft request = fixture.createRequest(suffix);
        List<FieldDefinition> fields = new ArrayList<>(request.fields());
        fields.add(fixture.field("value", "value", "TEXT", 1));
        Map<String, FieldOptions> options =
                sensitive
                        ? Map.of(
                                "value",
                                new FieldOptions(
                                        null,
                                        "SENSITIVE",
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        "ACTIVE",
                                        List.of(),
                                        null,
                                        null,
                                        "NONE",
                                        null,
                                        false,
                                        false))
                        : Map.of();
        Design draft =
                designs.save(
                        new SaveDesign(
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
                                Settings.defaults(),
                                options,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        PublishPlan plan =
                publisher.plan(
                        new Revision(draft.draft().id(), draft.draft().lockVersion(), null), 10001);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        publisher.execute(new ExecutePlan(plan.id(), "草稿切换夹具首发"), 10001);
        return designs.get(draft.draft().id());
    }

    private FieldDefinition value(Design design) {
        return design.draft().fields().stream()
                .filter(field -> field.code().equals("value"))
                .findFirst()
                .orElseThrow();
    }

    private Design saveType(
            Design design,
            String type,
            Integer precision,
            Integer scale,
            List<Relation> relations) {
        FieldDefinition value = value(design);
        List<FieldDefinition> fields =
                design.draft().fields().stream()
                        .map(
                                field ->
                                        field.id().equals(value.id())
                                                ? new FieldDefinition(
                                                        field.key(),
                                                        field.id(),
                                                        field.code(),
                                                        field.name(),
                                                        type,
                                                        "TEXT".equals(type) ? 200 : null,
                                                        precision,
                                                        scale,
                                                        false,
                                                        false,
                                                        field.sort())
                                                : field)
                        .toList();
        return designs.save(
                new SaveDesign(
                        fixture.edit(
                                design.draft(), fields, List.of(), design.draft().titleFieldId()),
                        design.settings(),
                        design.fieldOptions(),
                        relations,
                        design.indexes(),
                        design.details()),
                10001);
    }

    @Test
    void publishedTextCanPassThroughDraftReferenceToDecimalOrBackToText() {
        Design target = published("reference_target");
        for (String finalType : List.of("DECIMAL", "TEXT")) {
            Design source = published("source_" + finalType.toLowerCase());
            String fieldId = value(source).id();
            Design editable =
                    designs.editPublished(
                            new Revision(source.draft().id(), source.draft().lockVersion(), null),
                            10001);
            Relation relation =
                    new Relation(
                            null,
                            "draft_reference",
                            "草稿引用",
                            "REFERENCE",
                            target.draft().id(),
                            fieldId,
                            null,
                            false,
                            "RESTRICT");
            Design reference = saveType(editable, "REFERENCE", null, null, List.of(relation));
            assertThat(value(reference).type()).isEqualTo("REFERENCE");
            assertThat(reference.fieldOptions().get(fieldId).nativeType()).isEqualTo("bigint");
            assertThat(reference.relations()).hasSize(1);

            Design changed =
                    saveType(
                            reference,
                            finalType,
                            "DECIMAL".equals(finalType) ? 18 : null,
                            "DECIMAL".equals(finalType) ? 2 : null,
                            List.of());
            assertThat(value(changed).type()).isEqualTo(finalType);
            assertThat(changed.fieldOptions().get(fieldId).nativeType()).isNull();
            assertThat(changed.relations()).isEmpty();
            assertThat(designs.published(source.draft().id()).fields())
                    .anyMatch(field -> field.id().equals(fieldId) && field.type().equals("TEXT"));
        }
    }

    @Test
    void ordinaryFieldStillCannotForgeItsNativeType() {
        Design source = published("native_type_guard");
        Design editable =
                designs.editPublished(
                        new Revision(source.draft().id(), source.draft().lockVersion(), null),
                        10001);
        String fieldId = value(editable).id();
        FieldOptions old = editable.fieldOptions().get(fieldId);
        Map<String, FieldOptions> options = new HashMap<>(editable.fieldOptions());
        options.put(
                fieldId,
                new FieldOptions(
                        old.columnName(),
                        old.classification(),
                        old.defaultValue(),
                        old.description(),
                        old.pattern(),
                        old.minimum(),
                        old.maximum(),
                        old.state(),
                        old.options(),
                        old.expression(),
                        old.resultType(),
                        old.resolver(),
                        "bigint",
                        old.primaryKey(),
                        old.generated()));
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        new SaveDesign(
                                                fixture.edit(
                                                        editable.draft(),
                                                        editable.draft().fields(),
                                                        List.of(),
                                                        editable.draft().titleFieldId()),
                                                editable.settings(),
                                                options,
                                                editable.relations(),
                                                editable.indexes(),
                                                editable.details()),
                                        10001))
                .hasMessageContaining("数据库能力不能手工修改");
    }

    @Test
    void publishedExplicitReferenceCanChangeItsTargetInDraft() {
        Design firstTarget = published("first_target");
        Design secondTarget = published("second_target");
        Design source = published("retarget_source");
        String fieldId = value(source).id();
        Design editable =
                designs.editPublished(
                        new Revision(source.draft().id(), source.draft().lockVersion(), null),
                        10001);
        Relation relation =
                new Relation(
                        null,
                        "selected_record",
                        "显式引用",
                        "REFERENCE",
                        firstTarget.draft().id(),
                        fieldId,
                        null,
                        false,
                        "RESTRICT");
        Design reference = saveType(editable, "REFERENCE", null, null, List.of(relation));
        PublishPlan plan =
                publisher.plan(
                        new Revision(reference.draft().id(), reference.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        publisher.execute(new ExecutePlan(plan.id(), "建立可换目标引用夹具"), 10001);
        Design publishedReference = designs.get(source.draft().id());
        Relation old = publishedReference.relations().getFirst();

        Design retargetDraft =
                designs.editPublished(
                        new Revision(
                                publishedReference.draft().id(),
                                publishedReference.draft().lockVersion(),
                                null),
                        10001);
        Relation next =
                new Relation(
                        old.id(),
                        old.code(),
                        old.name(),
                        old.kind(),
                        secondTarget.draft().id(),
                        old.fieldId(),
                        old.targetFieldId(),
                        old.required(),
                        old.onDelete(),
                        old.sourceDetailId());
        Design saved = saveType(retargetDraft, "REFERENCE", null, null, List.of(next));
        assertThat(saved.relations())
                .singleElement()
                .extracting(Relation::targetObjectId)
                .isEqualTo(secondTarget.draft().id());
        assertThat(value(saved).id()).isEqualTo(fieldId);
        assertThat(designs.published(source.draft().id()).relations())
                .singleElement()
                .extracting(Relation::targetObjectId)
                .isEqualTo(firstTarget.draft().id());
    }

    @Test
    void preDraftRowsIncludeDeletedRecordsAndMaskSensitiveValues() {
        Design ordinary = published("rows_ordinary");
        jdbc.update(
                "INSERT INTO public.\""
                        + ordinary.draft().tableName()
                        + "\" (name,value,deleted) VALUES ('记录一','旧值一',0),('记录二','旧值二',1)");
        FieldSwitchPreviewService preview =
                servicesContext.getBean(FieldSwitchPreviewService.class);
        FieldConversions.Page first =
                preview.rows(ordinary.draft().id(), null, value(ordinary).id(), 1, 1);
        assertThat(first.total()).isEqualTo(2);
        assertThat(first.rows())
                .singleElement()
                .extracting(FieldConversions.Row::oldValue)
                .isEqualTo("旧值一");
        FieldConversions.Page second =
                preview.rows(ordinary.draft().id(), null, value(ordinary).id(), 2, 1);
        assertThat(second.rows())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.oldValue()).isEqualTo("旧值二");
                            assertThat(row.deleted()).isTrue();
                        });

        Design sensitive = published("rows_sensitive", true);
        jdbc.update(
                "INSERT INTO public.\""
                        + sensitive.draft().tableName()
                        + "\" (name,value) VALUES ('公开标题','秘密值')");
        FieldConversions.Page masked =
                preview.rows(sensitive.draft().id(), null, value(sensitive).id(), 1, 20);
        assertThat(masked.total()).isEqualTo(1);
        assertThat(masked.rows())
                .singleElement()
                .satisfies(
                        row -> {
                            assertThat(row.title()).isEqualTo("公开标题");
                            assertThat(row.oldValue()).isEqualTo("••••");
                        });
    }
}
