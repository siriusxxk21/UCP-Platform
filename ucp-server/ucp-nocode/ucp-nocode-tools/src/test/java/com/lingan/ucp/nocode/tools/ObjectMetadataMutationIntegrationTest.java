package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;

import org.junit.jupiter.api.*;

import java.util.*;

/** 在当前开发库验证成员部署身份与显式关系转换；只清理本测试拥有的对象和物理表。 */
class ObjectMetadataMutationIntegrationTest {
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
        fixture.clean();
    }

    private Design create(String suffix) {
        return designs.save(
                new SaveDesign(
                        fixture.createRequest(suffix),
                        Settings.defaults(),
                        Map.of(),
                        List.of(),
                        List.of(),
                        List.of()),
                10001);
    }

    private Design publish(Design design) {
        PublishPlan plan =
                publisher.plan(
                        new Revision(design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(publisher.execute(new ExecutePlan(plan.id(), "元数据转换夹具"), 10001).state())
                .isEqualTo("SUCCEEDED");
        return designs.get(design.draft().id());
    }

    private Design edit(Design design) {
        Design current = designs.get(design.draft().id());
        return designs.editPublished(
                new Revision(current.draft().id(), current.draft().lockVersion(), null), 10001);
    }

    private Design save(
            Design design,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> options,
            List<Relation> relations,
            List<Index> indexes,
            List<Detail> details) {
        ObjectDraft draft = design.draft();
        return designs.save(
                new SaveDesign(
                        new SaveObjectDraft(
                                draft.id(),
                                draft.lockVersion(),
                                draft.objectCode(),
                                draft.objectName(),
                                draft.description(),
                                draft.tableName(),
                                draft.titleFieldId(),
                                fields,
                                List.of(),
                                design.settings().titleTemplate(),
                                draft.category()),
                        design.settings(),
                        options,
                        relations,
                        indexes,
                        details,
                        design.mainBinding()),
                10001);
    }

    private Design members(
            Design design, List<Relation> relations, List<Index> indexes, List<Detail> details) {
        return save(
                design,
                design.draft().fields(),
                design.fieldOptions(),
                relations,
                indexes,
                details);
    }

    private Relation reference(String id, String code, String target, String field) {
        return new Relation(id, code, code, "REFERENCE", target, field, null, false, "RESTRICT");
    }

    @Test
    void savedUnpublishedRelationAndIndexCanChangeBeforeFirstDeployment() {
        Design firstTarget = publish(create("target_a"));
        Design secondTarget = publish(create("target_b"));
        Design initial = create("source");
        Design saved =
                members(
                        initial,
                        List.of(reference(null, "customer", firstTarget.draft().id(), null)),
                        List.of(
                                new Index(
                                        null,
                                        "name_index",
                                        "名称索引",
                                        false,
                                        List.of(initial.draft().titleFieldId()))),
                        List.of());
        Relation old = saved.relations().getFirst();
        Index oldIndex = saved.indexes().getFirst();
        Design changed =
                members(
                        saved,
                        List.of(
                                reference(
                                        old.id(),
                                        "supplier",
                                        secondTarget.draft().id(),
                                        old.fieldId())),
                        List.of(
                                new Index(
                                        oldIndex.id(),
                                        "renamed_index",
                                        "改名索引",
                                        false,
                                        oldIndex.fieldIds())),
                        List.of());
        assertThat(changed.relations().getFirst().id()).isEqualTo(old.id());
        assertThat(changed.relations().getFirst().targetObjectId())
                .isEqualTo(secondTarget.draft().id());
        assertThat(changed.draft().fields())
                .anyMatch(f -> f.id().equals(old.fieldId()) && f.code().equals("supplier_id"));
        assertThat(changed.indexes().getFirst().code()).isEqualTo("renamed_index");
        Design deployed = edit(publish(changed));
        Relation active = deployed.relations().getFirst();
        assertThatThrownBy(
                        () ->
                                members(
                                        deployed,
                                        List.of(
                                                reference(
                                                        active.id(),
                                                        "different",
                                                        firstTarget.draft().id(),
                                                        active.fieldId())),
                                        deployed.indexes(),
                                        deployed.details()))
                .hasMessageContaining("已发布或已部署关系");
        Index activeIndex = deployed.indexes().getFirst();
        assertThatThrownBy(
                        () ->
                                members(
                                        deployed,
                                        deployed.relations(),
                                        List.of(
                                                new Index(
                                                        activeIndex.id(),
                                                        "another_index",
                                                        "索引",
                                                        false,
                                                        activeIndex.fieldIds())),
                                        deployed.details()))
                .hasMessageContaining("已发布或已部署索引");
    }

    @Test
    void newMembersOnPublishedObjectStayEditableUntilTheirOwnDeployment() {
        Design target = publish(create("target"));
        Design draft = edit(publish(create("source")));
        Detail detail =
                new Detail(
                        null,
                        "items",
                        "新增明细",
                        "biz_" + fixture.prefix + "_items",
                        "ACTIVE",
                        List.of(fixture.field("line", "line_name", "TEXT", 0)),
                        Map.of(),
                        List.of());
        Design saved =
                members(
                        draft,
                        List.of(reference(null, "target", target.draft().id(), null)),
                        List.of(
                                new Index(
                                        null,
                                        "draft_index",
                                        "草稿索引",
                                        false,
                                        List.of(draft.draft().titleFieldId()))),
                        List.of(detail));
        Relation relation = saved.relations().getFirst();
        Index index = saved.indexes().getFirst();
        Detail original = saved.details().getFirst();
        Detail renamed =
                new Detail(
                        original.id(),
                        "lines",
                        original.name(),
                        "biz_" + fixture.prefix + "_lines",
                        original.state(),
                        original.fields(),
                        original.fieldOptions(),
                        original.indexes(),
                        original.binding());
        Design changed =
                members(
                        saved,
                        List.of(
                                reference(
                                        relation.id(),
                                        "renamed_target",
                                        target.draft().id(),
                                        relation.fieldId())),
                        List.of(
                                new Index(
                                        index.id(),
                                        "renamed_index",
                                        index.name(),
                                        false,
                                        index.fieldIds())),
                        List.of(renamed));
        assertThat(changed.details().getFirst().code()).isEqualTo("lines");
        assertThat(changed.indexes().getFirst().code()).isEqualTo("renamed_index");
        assertThat(changed.relations().getFirst().code()).isEqualTo("renamed_target");
        assertThat(databaseMetadata.readTable("public", original.tableName())).isEmpty();
        publish(changed);
        assertThat(databaseMetadata.readTable("public", renamed.tableName())).isPresent();
    }

    @Test
    void changingUnpublishedRelationKindRetiresOnlyItsGeneratedField() {
        Design target = publish(create("target"));
        Design source = create("source");
        Design saved =
                members(
                        source,
                        List.of(reference(null, "target", target.draft().id(), null)),
                        List.of(),
                        List.of());
        Relation old = saved.relations().getFirst();
        Relation multiple =
                new Relation(
                        old.id(),
                        old.code(),
                        old.name(),
                        "MANY_TO_MANY",
                        old.targetObjectId(),
                        null,
                        null,
                        false,
                        "RESTRICT");
        Design changed = members(saved, List.of(multiple), List.of(), List.of());
        assertThat(changed.relations().getFirst().fieldId()).isNull();
        assertThat(changed.draft().fields()).noneMatch(f -> f.id().equals(old.fieldId()));
        Design single =
                members(
                        changed,
                        List.of(reference(old.id(), "single_again", target.draft().id(), null)),
                        List.of(),
                        List.of());
        assertThat(single.relations().getFirst().fieldId()).isNotEqualTo(old.fieldId());
        assertThat(single.draft().fields()).hasSize(2);
        Relation mainRelation = single.relations().getFirst();
        Detail detail =
                new Detail(
                        null,
                        "items",
                        "关系来源明细",
                        "biz_" + fixture.prefix + "_items",
                        "ACTIVE",
                        List.of(fixture.field("item", "item_name", "TEXT", 0)),
                        Map.of(),
                        List.of());
        Relation moved =
                new Relation(
                        mainRelation.id(),
                        mainRelation.code(),
                        mainRelation.name(),
                        "REFERENCE",
                        mainRelation.targetObjectId(),
                        mainRelation.fieldId(),
                        null,
                        false,
                        "RESTRICT",
                        "detail:items");
        Design movedDesign = members(single, List.of(moved), List.of(), List.of(detail));
        Relation savedMove = movedDesign.relations().getFirst();
        assertThat(savedMove.fieldId()).isNotEqualTo(mainRelation.fieldId());
        assertThat(movedDesign.draft().fields()).hasSize(1);
        assertThat(movedDesign.details().getFirst().fields())
                .anyMatch(f -> f.id().equals(savedMove.fieldId()));
    }

    @Test
    void physicalGeneratedColumnProtectsIdentityEvenWithoutPublishedRelation() {
        Design target = publish(create("target"));
        Design source = edit(publish(create("source")));
        Design saved =
                members(
                        source,
                        List.of(reference(null, "target", target.draft().id(), null)),
                        List.of(
                                new Index(
                                        null,
                                        "physical_index",
                                        "实际索引",
                                        false,
                                        List.of(source.draft().titleFieldId()))),
                        List.of());
        Relation relation = saved.relations().getFirst();
        jdbc.execute(
                "ALTER TABLE public.\""
                        + saved.draft().tableName()
                        + "\" ADD COLUMN target_id bigint");
        assertThatThrownBy(
                        () ->
                                members(
                                        saved,
                                        List.of(
                                                reference(
                                                        relation.id(),
                                                        "renamed",
                                                        target.draft().id(),
                                                        relation.fieldId())),
                                        List.of(),
                                        List.of()))
                .hasMessageContaining("已发布或已部署关系");
        Index index = saved.indexes().getFirst();
        jdbc.execute(
                "CREATE INDEX \"nocode_i_"
                        + index.id()
                        + "\" ON public.\""
                        + saved.draft().tableName()
                        + "\" (name)");
        assertThatThrownBy(
                        () ->
                                members(
                                        saved,
                                        saved.relations(),
                                        List.of(
                                                new Index(
                                                        index.id(),
                                                        "renamed_index",
                                                        index.name(),
                                                        false,
                                                        index.fieldIds())),
                                        saved.details()))
                .hasMessageContaining("已发布或已部署索引");
    }

    @Test
    void explicitReferenceConversionKeepsStableFieldAndHistoryUntilPublish() {
        Design target = publish(create("target"));
        Design source = create("source");
        FieldDefinition choice = fixture.field("choice", "choice", "SELECT", 1);
        List<FieldDefinition> fields = new ArrayList<>(source.draft().fields());
        fields.add(choice);
        Map<String, FieldOptions> options = new HashMap<>(source.fieldOptions());
        options.put(
                choice.key(),
                new FieldOptions(
                        null,
                        "INTERNAL",
                        null,
                        "保留说明",
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(new Option("legacy", "历史选项", false)),
                        null,
                        null,
                        "NONE",
                        null,
                        false,
                        false));
        Design published = publish(save(source, fields, options, List.of(), List.of(), List.of()));
        jdbc.update(
                "INSERT INTO public.\""
                        + published.draft().tableName()
                        + "\" (name,choice) VALUES (?,?)",
                "旧记录",
                "legacy");
        Design draft = edit(published);
        FieldDefinition original =
                draft.draft().fields().stream()
                        .filter(f -> f.code().equals("choice"))
                        .findFirst()
                        .orElseThrow();
        assertThatThrownBy(
                        () ->
                                members(
                                        draft,
                                        List.of(
                                                reference(
                                                        null,
                                                        "mapped",
                                                        target.draft().id(),
                                                        original.id())),
                                        List.of(),
                                        List.of()))
                .hasMessageContaining("引用列类型不支持");
        FieldDefinition converted =
                new FieldDefinition(
                        original.key(),
                        original.id(),
                        original.code(),
                        original.name(),
                        "REFERENCE",
                        null,
                        null,
                        null,
                        false,
                        false,
                        original.sort());
        List<FieldDefinition> convertedFields =
                draft.draft().fields().stream()
                        .map(f -> f.id().equals(original.id()) ? converted : f)
                        .toList();
        Map<String, FieldOptions> convertedOptions = new HashMap<>(draft.fieldOptions());
        convertedOptions.put(
                original.id(),
                new FieldOptions(
                        original.code(),
                        "INTERNAL",
                        null,
                        "保留说明",
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
                        false));
        Design saved =
                save(
                        draft,
                        convertedFields,
                        convertedOptions,
                        List.of(reference(null, "chosen", target.draft().id(), original.id())),
                        List.of(),
                        List.of());
        FieldOptions normalized = saved.fieldOptions().get(original.id());
        assertThat(normalized.columnName()).isEqualTo("choice");
        assertThat(normalized.nativeType()).isEqualTo("bigint");
        assertThat(normalized.generated()).isFalse();
        assertThat(normalized.classification()).isEqualTo("INTERNAL");
        assertThat(normalized.description()).isEqualTo("保留说明");
        assertThat(saved.relations().getFirst().fieldId()).isEqualTo(original.id());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT choice FROM public.\""
                                        + published.draft().tableName()
                                        + "\"",
                                String.class))
                .isEqualTo("legacy");
        Design detached = members(saved, List.of(), List.of(), List.of());
        assertThat(detached.draft().fields()).anyMatch(f -> f.id().equals(original.id()));
    }
}
