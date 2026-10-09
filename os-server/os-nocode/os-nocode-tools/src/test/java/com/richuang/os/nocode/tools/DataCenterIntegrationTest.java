package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;
import com.richuang.os.framework.mybatis.core.metadata.BaseDOColumns;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.DataClassificationEnum;
import com.richuang.os.nocode.enums.DisplayResolverEnum;
import com.richuang.os.nocode.enums.FieldTypeEnum;
import com.richuang.os.nocode.enums.MemberStateEnum;
import com.richuang.os.nocode.metadata.dal.dataobject.NocodeObjectDO;
import com.richuang.os.nocode.metadata.dal.dataobject.NocodeObjectTableDO;
import com.richuang.os.nocode.metadata.dal.dataobject.NocodeObjectVersionDO;
import com.richuang.os.nocode.metadata.service.object.DataObjectApiImpl;
import com.richuang.os.nocode.metadata.service.table.DataTableService;
import com.richuang.os.nocode.schema.service.reconcile.ObjectReconcileService;
import com.richuang.os.nocode.web.ObjectImportService;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;

import java.util.*;

/** 在当前开发库验证发布事务、公共字段、版本保护和纳管边界；仅清理本测试拥有的夹具。 */
class DataCenterIntegrationTest {
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

    private Design create(String suffix, List<Detail> details, List<Relation> relations) {
        return designs.save(
                new SaveDesign(
                        fixture.createRequest(suffix),
                        Settings.defaults(),
                        null,
                        relations,
                        List.of(),
                        details),
                10001);
    }

    private PublishPlan plan(Design d) {
        return publisher.plan(new Revision(d.draft().id(), d.draft().lockVersion(), null), 10001);
    }

    private void publish(Design d) {
        var p = plan(d);
        assertThat(p.checks()).noneMatch(Check::blocking);
        assertThat(publisher.execute(new ExecutePlan(p.id(), "自动化交付验证"), 10001).state())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    void internalDetailReferencePublishesClonesAndKeepsOldJsonCompatible() throws Exception {
        var target = create("target", List.of(), List.of());
        publish(target);
        var detail =
                new Detail(
                        null,
                        "entries",
                        "分录",
                        "biz_" + fixture.prefix + "_entries",
                        "ACTIVE",
                        List.of(fixture.field("summary", "summary", "TEXT", 0)),
                        Map.of(),
                        List.of());
        var source =
                create(
                        "voucher",
                        List.of(detail),
                        List.of(
                                new Relation(
                                        null,
                                        "account",
                                        "科目",
                                        "REFERENCE",
                                        target.draft().id(),
                                        null,
                                        null,
                                        true,
                                        "RESTRICT",
                                        "detail:entries")));
        var relation = source.relations().getFirst();
        var entries = source.details().getFirst();
        assertThat(relation.sourceDetailId()).isEqualTo(entries.id());
        assertThat(entries.fields()).anyMatch(f -> f.id().equals(relation.fieldId()));
        assertThat(source.draft().fields()).noneMatch(f -> f.id().equals(relation.fieldId()));
        publish(source);
        assertThat(
                        databaseMetadata
                                .readTable("public", entries.tableName())
                                .orElseThrow()
                                .constraints())
                .anyMatch(c -> c.name().equals("nocode_fk_r_" + relation.id()));
        var edited =
                designs.editPublished(
                        new Revision(
                                source.draft().id(),
                                designs.get(source.draft().id()).draft().lockVersion(),
                                null),
                        10001);
        assertThat(edited.relations().getFirst().sourceDetailId()).isEqualTo(entries.id());
        var copied =
                designs.copy(
                        new Copy(
                                source.draft().id(),
                                fixture.prefix + "copy",
                                "凭证副本",
                                "biz_" + fixture.prefix + "copy"),
                        10001);
        assertThat(copied.relations().getFirst().sourceDetailId())
                .isEqualTo(copied.details().getFirst().id())
                .isNotEqualTo(entries.id());
        publish(copied);
        var legacy =
                new Relation("1", "legacy", "旧引用", "REFERENCE", "2", "3", null, false, "RESTRICT");
        var serialized = mapper.writeValueAsString(legacy);
        assertThat(serialized).doesNotContain("sourceDetailId");
        assertThat(mapper.readValue(serialized, Relation.class).sourceDetailId()).isNull();
    }

    @Test
    void internalDetailReferenceRejectsInvalidOwnershipAndUnsupportedRelationKinds() {
        var target = create("target", List.of(), List.of());
        publish(target);
        var detail =
                new Detail(
                        null,
                        "entries",
                        "分录",
                        "biz_" + fixture.prefix + "_entries",
                        "ACTIVE",
                        List.of(fixture.field("summary", "summary", "TEXT", 0)),
                        Map.of(),
                        List.of());
        assertThatThrownBy(
                        () ->
                                create(
                                        "bad",
                                        List.of(detail),
                                        List.of(
                                                new Relation(
                                                        null,
                                                        "account",
                                                        "科目",
                                                        "REFERENCE",
                                                        target.draft().id(),
                                                        null,
                                                        null,
                                                        true,
                                                        "RESTRICT",
                                                        "999999999"))))
                .hasMessageContaining("来源明细");
        assertThatThrownBy(
                        () ->
                                create(
                                        "bad",
                                        List.of(detail),
                                        List.of(
                                                new Relation(
                                                        null,
                                                        "account",
                                                        "科目",
                                                        "MANY_TO_MANY",
                                                        target.draft().id(),
                                                        null,
                                                        null,
                                                        false,
                                                        "RESTRICT",
                                                        "detail:entries"))))
                .hasMessageContaining("仅支持单值");
    }

    @com.baomidou.mybatisplus.annotation.TableName("platform_owned_fixture")
    static class BaseMappedFixture extends BaseDO {
        @com.baomidou.mybatisplus.annotation.TableId private Long id;
    }

    @Test
    void systemProtectionUsesRegisteredBaseMappingsBeyondNamePrefixes() {
        var assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new com.baomidou.mybatisplus.core.MybatisConfiguration(),
                        "data-center-protection-test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                assistant, BaseMappedFixture.class);
        assertThat(tables.systemTable("public", "platform_owned_fixture")).isTrue();
        assertThat(tables.systemTable("public", "ai_agent")).isTrue();
        assertThat(tables.systemTable("public", "agent")).isTrue();
        assertThat(tables.systemTable("public", "knowledge_document")).isTrue();
        assertThat(tables.systemTable("public", "biz_external_business")).isFalse();
    }

    @Test
    void unknownDomainCodesAreRejectedAndImportStillUsesStableCodes() {
        var input = fixture.createRequest("");
        var invalidOptions =
                new FieldOptions(
                        null, "PUBLIC", null, null, null, null, null, "ACTIVE", List.of(), null,
                        null, "NONE", null, false, false);
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        new SaveDesign(
                                                input,
                                                Settings.defaults(),
                                                Map.of(
                                                        input.fields().getFirst().key(),
                                                        invalidOptions),
                                                List.of(),
                                                List.of(),
                                                List.of()),
                                        10001))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("分类");
        assertThatThrownBy(
                        () -> com.richuang.os.nocode.enums.VersionStateEnum.fromCode("published"))
                .isInstanceOf(ServiceException.class);
        assertThat(
                        com.richuang.os.nocode.enums.FieldTypeEnum.fromCode("TEXT")
                                .supportsStructureImport())
                .isTrue();
        assertThat(
                        com.richuang.os.nocode.enums.FieldTypeEnum.fromCode("FORMULA")
                                .supportsStructureImport())
                .isFalse();
    }

    @Test
    void retainedInternalDetailCanBeEnabledAgainWithoutLosingRows() {
        var detail =
                new Detail(
                        null,
                        "items",
                        "内部明细",
                        fixture.createRequest("").tableName() + "_items",
                        "ACTIVE",
                        List.of(fixture.field("item-name", "item_name", "TEXT", 0)),
                        Map.of(),
                        List.of());
        var d = create("", List.of(detail), List.of());
        publish(d);
        var published = designs.get(d.draft().id());
        var item = published.details().getFirst();
        jdbc.update("INSERT INTO public." + d.draft().tableName() + " (name) VALUES ('existing')");
        jdbc.update(
                "INSERT INTO public."
                        + item.tableName()
                        + " (parent_id,item_name) SELECT id,'kept' FROM public."
                        + d.draft().tableName());
        for (String state : List.of("INACTIVE", "ACTIVE")) {
            var head = designs.get(d.draft().id());
            var draft =
                    designs.editPublished(
                            new Revision(head.draft().id(), head.draft().lockVersion(), "测试明细启停"),
                            10001);
            var old = draft.details().getFirst();
            var changed =
                    new Detail(
                            old.id(),
                            old.code(),
                            old.name(),
                            old.tableName(),
                            state,
                            old.fields(),
                            old.fieldOptions(),
                            old.indexes());
            var saved =
                    designs.save(
                            new SaveDesign(
                                    fixture.edit(
                                            draft.draft(),
                                            List.of(),
                                            List.of(),
                                            draft.draft().titleFieldId()),
                                    draft.settings(),
                                    draft.fieldOptions(),
                                    draft.relations(),
                                    draft.indexes(),
                                    List.of(changed)),
                            10001);
            publish(saved);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT item_name FROM public." + item.tableName(), String.class))
                .isEqualTo("kept");
    }

    @Test
    void metadataUsesBaseDoAndPreservesStandardAuditFields() {
        assertThat(BaseDO.class)
                .isAssignableFrom(NocodeObjectDO.class)
                .isAssignableFrom(NocodeObjectVersionDO.class)
                .isAssignableFrom(NocodeObjectTableDO.class);
        var d = create("", List.of(), List.of());
        long id = Long.parseLong(d.draft().id());
        var entity = draftMapper.selectById(id);
        assertThat(entity.getCreator()).isEqualTo("10001");
        assertThat(entity.getCreateTime()).isNotNull();
        assertThat(entity.getUpdateTime()).isNotNull();
        assertThat(entity.getDeleted()).isFalse();
        for (String table :
                List.of(
                        "nocode_object",
                        "nocode_object_version",
                        "nocode_object_table",
                        "nocode_field",
                        "nocode_relation",
                        "nocode_index_definition",
                        "nocode_publish_plan",
                        "nocode_deployment",
                        "nocode_resource_dependency",
                        "nocode_operation_log"))
            assertThat(
                            BaseDOColumns.differences(
                                    databaseMetadata.readTable("public", table).orElseThrow()))
                    .as(table)
                    .isEmpty();
        var edit = fixture.edit(d.draft(), List.of(), List.of(), d.draft().titleFieldId());
        designs.save(new SaveDesign(edit, null, null, null, null, null), 10002);
        assertThat(draftMapper.selectById(id).getUpdater()).isEqualTo("10002");
        assertThat(draftMapper.selectById(id).getCreator()).isEqualTo("10001");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT updater FROM public.nocode_field WHERE"
                                    + " object_version_id=(SELECT id FROM"
                                    + " public.nocode_object_version WHERE object_id=?) LIMIT 1",
                                String.class,
                                id))
                .isEqualTo("10002");
    }

    @Test
    void mainDetailAndManyToManyHaveCommonFieldsAndPublishAtomically() {
        var target = create("target", List.of(), List.of());
        publish(target);
        var detail =
                new Detail(
                        null,
                        "items",
                        "明细",
                        "biz_" + fixture.prefix + "_items",
                        "ACTIVE",
                        List.of(fixture.field("item-name", "item_name", "TEXT", 0)),
                        null,
                        List.of());
        var relation =
                new Relation(
                        null,
                        "partners",
                        "合作对象",
                        "MANY_TO_MANY",
                        target.draft().id(),
                        null,
                        null,
                        false,
                        "RESTRICT");
        var d = create("main", List.of(detail), List.of(relation));
        assertThat(
                        centerMapper.allHeads().stream()
                                .filter(h -> h.getObjectCode().startsWith(fixture.prefix)))
                .hasSize(2);
        var p = plan(d);
        assertThat(p.state()).isEqualTo("PENDING");
        assertThat(databaseMetadata.relationExists("public", d.draft().tableName())).isFalse();
        var done = publisher.execute(new ExecutePlan(p.id(), "验证完整对象发布"), 10001);
        assertThat(done.state()).isEqualTo("SUCCEEDED");
        assertThat(publisher.execute(new ExecutePlan(p.id(), "重复点击"), 10001).state())
                .isEqualTo("SUCCEEDED");
        for (String table :
                List.of(
                        d.draft().tableName(),
                        detail.tableName(),
                        DataTableService.relationTable(
                                d.draft().id(), d.relations().getFirst().id()))) {
            assertThat(table).startsWith("biz_");
            var actual = databaseMetadata.readTable("public", table).orElseThrow();
            assertThat(BaseDOColumns.differences(actual)).isEmpty();
            assertThat(actual.columns())
                    .anyMatch(c -> c.name().equals("id") && c.primaryKeyPosition() == 1);
        }
        assertThat(tables.drift(d.draft().id())).isEmpty();
        var detailTable = databaseMetadata.readTable("public", detail.tableName()).orElseThrow();
        assertThat(detailTable.constraints())
                .anyMatch(c -> c.definition().contains("FOREIGN KEY (parent_id)"));
    }

    @Test
    void newDetailCannotUseLegacyPrefix() {
        var detail =
                new Detail(
                        null,
                        "items",
                        "明细",
                        "nocode_data_" + fixture.prefix + "_items",
                        "ACTIVE",
                        List.of(fixture.field("item-name", "item_name", "TEXT", 0)),
                        null,
                        List.of());
        assertThatThrownBy(() -> create("main", List.of(detail), List.of()))
                .hasMessageContaining("biz_");
    }

    @Test
    void failedDdlRollsBackMainAndDetailsButKeepsFailureHistory() {
        var d =
                create(
                        "",
                        List.of(
                                new Detail(
                                        null,
                                        "items",
                                        "明细",
                                        "biz_" + fixture.prefix + "_items",
                                        "ACTIVE",
                                        List.of(fixture.field("item-name", "item_name", "TEXT", 0)),
                                        null,
                                        List.of())),
                        List.of());
        var p = plan(d);
        writeFailure.failAfter("CREATE TABLE");
        try {
            assertThatThrownBy(() -> publisher.execute(new ExecutePlan(p.id(), "故障注入"), 10001))
                    .isInstanceOf(ServiceException.class);
        } finally {
            writeFailure.clear();
        }
        assertThat(databaseMetadata.relationExists("public", d.draft().tableName())).isFalse();
        assertThat(databaseMetadata.relationExists("public", d.details().getFirst().tableName()))
                .isFalse();
        assertThat(designs.get(d.draft().id()).publishedVersion()).isNull();
        assertThat(publisher.get(p.id()).state()).isEqualTo("FAILED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_deployment WHERE object_id=?",
                                Long.class,
                                Long.parseLong(d.draft().id())))
                .isZero();
    }

    @Test
    void publishedVersionsFreezeAndStalePlansCannotApply() {
        var d = create("", List.of(), List.of());
        publish(d);
        String frozen = designs.version(d.draft().id(), 1);
        var current = designs.get(d.draft().id());
        var draft =
                designs.editPublished(
                        new Revision(current.draft().id(), current.draft().lockVersion(), null),
                        10002);
        var p = plan(draft);
        var changed =
                designs.save(
                        new SaveDesign(
                                fixture.edit(
                                        draft.draft(),
                                        List.of(fixture.field("extra", "extra", "TEXT", 2)),
                                        List.of(),
                                        draft.draft().titleFieldId()),
                                null,
                                null,
                                null,
                                null,
                                null),
                        10002);
        assertThatThrownBy(() -> publisher.execute(new ExecutePlan(p.id(), "旧计划"), 10001))
                .hasMessageContaining("草稿已变化");
        assertThat(designs.version(d.draft().id(), 1)).isEqualTo(frozen);
        publish(changed);
        assertThat(designs.get(d.draft().id()).publishedVersion()).isEqualTo(2);
        assertThat(
                        databaseMetadata
                                .readTable("public", d.draft().tableName())
                                .orElseThrow()
                                .columns())
                .anyMatch(c -> c.name().equals("extra"));
    }

    @Test
    void emptyPublishedColumnAllowsReplacementAndExistingValuesRequireConfirmation() {
        var request = fixture.createRequest("empty_type");
        var fields = new ArrayList<>(request.fields());
        fields.add(
                new FieldDefinition(
                        "amount", null, "amount", "金额文本", "TEXT", 100, null, null, false, false,
                        1));
        var initial =
                designs.save(
                        new SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        request.objectName(),
                                        request.description(),
                                        request.tableName(),
                                        request.titleFieldKey(),
                                        fields,
                                        List.of()),
                                Settings.defaults(),
                                Map.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        publish(initial);
        var changed = changeAmountToInteger(designs.get(initial.draft().id()));
        var plan = plan(changed);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(plan.changes()).anyMatch(c -> c.kind().equals("ALTER_COLUMN_TYPE"));
        assertThat(publisher.execute(new ExecutePlan(plan.id(), "空表类型调整"), 10001).state())
                .isEqualTo("SUCCEEDED");
        assertThat(
                        databaseMetadata
                                .readTable("public", initial.draft().tableName())
                                .orElseThrow()
                                .columns())
                .anyMatch(c -> c.name().equals("amount") && c.nativeType().equals("bigint"));

        jdbc.update(
                "INSERT INTO public.\""
                        + initial.draft().tableName()
                        + "\" (name,amount) VALUES (?,?)",
                "已有记录",
                1);
        Design pending = changeAmountToText(designs.get(initial.draft().id()));
        PublishPlan confirmation = plan(pending);
        assertThat(confirmation.checks()).noneMatch(Check::blocking);
        assertThat(confirmation.conversions())
                .singleElement()
                .satisfies(change -> assertThat(change.affectedRows()).isEqualTo(1));
        assertThatThrownBy(
                        () ->
                                publisher.execute(
                                        new ExecutePlan(confirmation.id(), "缺少清空确认"), 10001))
                .hasMessageContaining("逐列确认");
    }

    private Design changeAmountToInteger(Design published) {
        return changeAmountType(published, "INTEGER", null);
    }

    private Design changeAmountToText(Design published) {
        return changeAmountType(published, "TEXT", 100);
    }

    private Design changeAmountType(Design published, String type, Integer length) {
        var edit =
                designs.editPublished(
                        new Revision(published.draft().id(), published.draft().lockVersion(), null),
                        10001);
        var fields =
                edit.draft().fields().stream()
                        .map(
                                field ->
                                        field.code().equals("amount")
                                                ? new FieldDefinition(
                                                        field.key(),
                                                        field.id(),
                                                        field.code(),
                                                        field.name(),
                                                        type,
                                                        length,
                                                        null,
                                                        null,
                                                        field.required(),
                                                        field.unique(),
                                                        field.sort())
                                                : field)
                        .toList();
        return designs.save(
                new SaveDesign(
                        fixture.edit(edit.draft(), fields, List.of(), edit.draft().titleFieldId()),
                        null,
                        null,
                        null,
                        null,
                        null),
                10001);
    }

    @Test
    void driftAndMissingBaseFieldsAreExplicitAndProtected() {
        var d = create("", List.of(), List.of());
        publish(d);
        jdbc.execute(
                "ALTER TABLE public.\""
                        + d.draft().tableName()
                        + "\" ADD COLUMN outside_change text");
        assertThat(tables.drift(d.draft().id())).isNotEmpty();
        var latest = designs.get(d.draft().id());
        var draft =
                designs.editPublished(
                        new Revision(latest.draft().id(), latest.draft().lockVersion(), null),
                        10001);
        assertThat(plan(draft).checks())
                .anyMatch(c -> c.blocking() && c.code().equals("STRUCTURE_DRIFT"));
        String existing = "biz_" + fixture.prefix + "_existing";
        try {
            jdbc.execute(
                    "CREATE TABLE public.\""
                            + existing
                            + "\" (id bigint PRIMARY KEY,name varchar(100))");
            var preflight = tables.preflight("public", existing);
            assertThat(preflight.allowed()).isTrue();
            assertThat(preflight.readOnly()).isTrue();
            assertThat(preflight.checks()).anyMatch(c -> c.code().equals("BASE_FIELDS"));
            var adopted =
                    tables.adopt(
                            new Adoption(
                                    "public",
                                    existing,
                                    fixture.prefix + "adopted",
                                    "纳管验证",
                                    "name",
                                    preflight.fingerprint()),
                            10001);
            publish(adopted);
            assertThat(databaseMetadata.readTable("public", existing).orElseThrow().columns())
                    .hasSize(2);
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS public.\"" + existing + "\"");
        }
    }

    @Test
    void commonFieldNamesAreReservedAndLogicalDeletionIsHonored() {
        for (String code : BaseDOColumns.NAMES) {
            var request = fixture.createRequest(code);
            var fields = new ArrayList<>(request.fields());
            fields.add(fixture.field("extra", code, "TEXT", 1));
            assertThatThrownBy(
                            () ->
                                    service.create(
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
                                            10001,
                                            UUID.randomUUID()))
                    .isInstanceOf(ServiceException.class);
        }
        var d = create("", List.of(), List.of());
        publish(d);
        var current = designs.get(d.draft().id());
        jdbc.update(
                "INSERT INTO public.\""
                        + current.draft().tableName()
                        + "\" (name,deleted) VALUES (?,?),(?,?)",
                "可见",
                0,
                "隐藏",
                1);
        assertThat(tables.preview("public", current.draft().tableName(), 1, 20, 10001).rows())
                .hasSize(1);
        jdbc.update("DELETE FROM public.\"" + current.draft().tableName() + "\"");
        designs.lifecycle(
                new Revision(current.draft().id(), current.draft().lockVersion(), "验证删除"),
                "delete",
                10001);
        assertThat(draftMapper.selectById(Long.parseLong(current.draft().id()))).isNull();
        assertThatThrownBy(() -> designs.get(current.draft().id()))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void physicalAdditionsCanBeReviewedAndPublishedWithoutChangingData() {
        var d = create("", List.of(), List.of());
        publish(d);
        String before = designs.version(d.draft().id(), 1);
        jdbc.execute(
                "ALTER TABLE public.\""
                        + d.draft().tableName()
                        + "\" ADD COLUMN external_note varchar(100)");
        jdbc.update(
                "INSERT INTO public.\""
                        + d.draft().tableName()
                        + "\" (name,external_note) VALUES (?,?)",
                "保留记录",
                "外部新增值");
        var reconcile = servicesContext.getBean(ObjectReconcileService.class);
        var preview = reconcile.preview(d.draft().id());
        assertThat(preview.checks()).noneMatch(Check::blocking);
        assertThat(preview.changes()).anyMatch(s -> s.kind().equals("IMPORT_COLUMN"));
        var mapped =
                reconcile.apply(
                        new com.richuang.os.nocode.api.ObjectReconciliation.Apply(
                                preview.id(), preview.revision(), preview.fingerprint(), "核对外部新增列"),
                        10001);
        assertThat(mapped.draft().fields()).anyMatch(f -> f.code().equals("external_note"));
        publish(mapped);
        assertThat(tables.drift(d.draft().id())).isEmpty();
        assertThat(designs.version(d.draft().id(), 1)).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT external_note FROM public.\""
                                        + d.draft().tableName()
                                        + "\"",
                                String.class))
                .isEqualTo("外部新增值");
    }

    @Test
    void detailCompositeIndexAndCopyPreserveIndependentFieldIdentities() {
        var detail =
                new Detail(
                        null,
                        "items",
                        "明细",
                        "biz_" + fixture.prefix + "_items",
                        "ACTIVE",
                        List.of(
                                fixture.field("item-name", "item_name", "TEXT", 0),
                                fixture.field("item-value", "item_value", "INTEGER", 1)),
                        Map.of(),
                        List.of());
        var d =
                designs.save(
                        new SaveDesign(
                                fixture.createRequest("main"),
                                Settings.defaults(),
                                Map.of(),
                                List.of(),
                                List.of(
                                        new Index(
                                                null,
                                                "item_unique",
                                                "明细组合唯一",
                                                true,
                                                List.of("item-name", "item-value"))),
                                List.of(detail)),
                        10001);
        publish(d);
        assertThat(databaseMetadata.readTable("public", detail.tableName()).orElseThrow().indexes())
                .anyMatch(i -> i.unique() && i.name().startsWith("nocode_i_"));
        var copied =
                designs.copy(
                        new Copy(
                                d.draft().id(),
                                fixture.prefix + "copy",
                                "副本",
                                "biz_" + fixture.prefix + "copy"),
                        10001);
        assertThat(copied.details()).hasSize(1);
        assertThat(copied.indexes()).hasSize(1);
        assertThat(copied.details().getFirst().fields().getFirst().id())
                .isNotEqualTo(d.details().getFirst().fields().getFirst().id());
        publish(copied);
    }

    @Test
    void referenceAndRegisteredDependenciesPreventInvalidLifecycleChanges() {
        var target = create("target", List.of(), List.of());
        publish(target);
        var related =
                create(
                        "ref",
                        List.of(),
                        List.of(
                                new Relation(
                                        null,
                                        "customer",
                                        "客户",
                                        "REFERENCE",
                                        target.draft().id(),
                                        null,
                                        null,
                                        false,
                                        "RESTRICT")));
        publish(related);
        var latest = designs.get(target.draft().id());
        assertThatThrownBy(
                        () ->
                                designs.lifecycle(
                                        new Revision(
                                                latest.draft().id(),
                                                latest.draft().lockVersion(),
                                                "测试依赖"),
                                        "disable",
                                        10001))
                .hasMessageContaining("引用");
        var api = servicesContext.getBean(DataObjectApiImpl.class);
        api.registerDependency(
                new Dependency(
                        "PAGE",
                        fixture.prefix + "page",
                        "测试页面",
                        target.draft().id(),
                        List.of(target.draft().titleFieldId())),
                10001);
        assertThat(designs.get(target.draft().id()).dependencies())
                .anyMatch(d -> d.sourceKind().equals("PAGE"));
        api.removeDependencies("PAGE", fixture.prefix + "page", 10002);
        assertThat(designs.get(target.draft().id()).dependencies())
                .noneMatch(d -> d.sourceKind().equals("PAGE"));
    }

    @Test
    void importReportsRowsAndPreservesCsvQuoting() {
        var importer = servicesContext.getBean(ObjectImportService.class);
        String csv =
                String.join(",", ObjectImportService.HEADERS)
                        + "\r\nname,\"名称,描述\",TEXT,100,,,是,否\r\namount,金额,DECIMAL,,18,2,否,否";
        var preview =
                importer.preview(
                        new MockMultipartFile(
                                "file",
                                "template.csv",
                                "text/csv",
                                csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(preview.errors()).isEmpty();
        assertThat(preview.columns().getFirst().name()).isEqualTo("名称,描述");
        var d =
                importer.create(
                        new ImportDesign(
                                fixture.prefix + "import",
                                "导入验证",
                                "biz_" + fixture.prefix + "import",
                                "name",
                                preview.columns()),
                        10001);
        assertThat(d.draft().fields()).hasSize(2);
        String invalid =
                String.join(",", ObjectImportService.HEADERS) + "\ncreator,创建人,TEXT,100,,,否,否";
        var rejected =
                importer.preview(
                        new MockMultipartFile(
                                "file",
                                "bad.csv",
                                "text/csv",
                                invalid.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(rejected.errors()).hasSize(1);
        assertThat(rejected.errors().getFirst().row()).isEqualTo(2);
    }

    @Test
    void formulaAndSummaryValidateDependenciesAndMaterializeOnlyFormula() {
        var main = new ArrayList<>(fixture.createRequest("").fields());
        main.add(fixture.field("quantity", "quantity", "INTEGER", 1));
        main.add(fixture.field("double_qty", "double_qty", "FORMULA", 2));
        main.add(fixture.field("items_count", "items_count", "SUMMARY", 3));
        var opts = new HashMap<String, FieldOptions>();
        opts.put(
                "double_qty",
                new FieldOptions(
                        null,
                        "NORMAL",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(),
                        "quantity * 2",
                        "INTEGER",
                        "NONE",
                        null,
                        false,
                        false));
        opts.put(
                "items_count",
                new FieldOptions(
                        null,
                        "NORMAL",
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(),
                        "count(items)",
                        "INTEGER",
                        "NONE",
                        null,
                        false,
                        false));
        var request = fixture.createRequest("computed");
        var d =
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
                                        main,
                                        List.of()),
                                Settings.defaults(),
                                opts,
                                List.of(),
                                List.of(),
                                List.of(
                                        new Detail(
                                                null,
                                                "items",
                                                "明细",
                                                "biz_" + fixture.prefix + "_items",
                                                "ACTIVE",
                                                List.of(
                                                        fixture.field(
                                                                "item", "item_name", "TEXT", 0)),
                                                Map.of(),
                                                List.of()))),
                        10001);
        publish(d);
        jdbc.update(
                "INSERT INTO public.\"" + d.draft().tableName() + "\" (name,quantity) VALUES (?,?)",
                "计算",
                3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT double_qty FROM public.\"" + d.draft().tableName() + "\"",
                                Long.class))
                .isEqualTo(6L);
        assertThat(
                        databaseMetadata
                                .readTable("public", d.draft().tableName())
                                .orElseThrow()
                                .columns())
                .noneMatch(c -> c.name().equals("items_count"));
        assertThatThrownBy(
                        () ->
                                com.richuang.os.nocode.metadata.service.formula.FieldExpressions
                                        .parse("pg_sleep(9)", Map.of()))
                .isInstanceOf(ServiceException.class);
    }

    /** 金额汇总仍为查询计算字段；保存和发布均不能为它创建存储列。 */
    @Test
    void moneySummaryAcceptsDirectAndFormulaSourcesForAllNumericAggregates() {
        Map<String, FieldOptions> options = new LinkedHashMap<>();
        for (String aggregate : List.of("sum", "avg", "min", "max")) {
            for (String source : List.of("amount", "net")) {
                for (FieldTypeEnum resultType :
                        List.of(FieldTypeEnum.DECIMAL, FieldTypeEnum.MONEY)) {
                    String key =
                            aggregate
                                    + "_"
                                    + source
                                    + "_"
                                    + resultType.getCode().toLowerCase(Locale.ROOT);
                    options.put(
                            key,
                            summaryTestOptions(
                                    aggregate + "(items." + source + ")",
                                    resultType,
                                    MemberStateEnum.ACTIVE));
                }
            }
        }
        options.put(
                "items_count",
                summaryTestOptions("count(items)", FieldTypeEnum.INTEGER, MemberStateEnum.ACTIVE));
        Design saved = designs.save(moneySummaryRequest(options), 10001);
        for (FieldDefinition field : saved.draft().fields()) {
            if (!FieldTypeEnum.SUMMARY.matches(field.type())) continue;
            FieldOptions expected = options.get(field.code());
            assertThat(saved.fieldOptions().get(field.id()).expression())
                    .isEqualTo(expected.expression());
            assertThat(saved.fieldOptions().get(field.id()).resultType())
                    .isEqualTo(expected.resultType());
        }
        publish(saved);
        assertThat(
                        databaseMetadata
                                .readTable("public", saved.draft().tableName())
                                .orElseThrow()
                                .columns())
                .noneMatch(column -> options.containsKey(column.name()));
    }

    @Test
    void moneySummaryRejectsTextResultsNonNumericAndInactiveSources() {
        for (String aggregate : List.of("sum", "avg", "min", "max")) {
            for (FieldTypeEnum resultType : List.of(FieldTypeEnum.TEXT, FieldTypeEnum.INTEGER)) {
                assertThatThrownBy(
                                () ->
                                        designs.save(
                                                moneySummaryRequest(
                                                        Map.of(
                                                                "total",
                                                                summaryTestOptions(
                                                                        aggregate
                                                                                + "(items.amount)",
                                                                        resultType,
                                                                        MemberStateEnum.ACTIVE))),
                                                10001))
                        .isInstanceOf(ServiceException.class)
                        .hasMessageContaining("结果类型为小数或金额");
            }
            for (String source :
                    List.of("text_value", "text_formula", "inactive_amount", "inactive_net")) {
                assertThatThrownBy(
                                () ->
                                        designs.save(
                                                moneySummaryRequest(
                                                        Map.of(
                                                                "total",
                                                                summaryTestOptions(
                                                                        aggregate + "(items."
                                                                                + source + ")",
                                                                        FieldTypeEnum.MONEY,
                                                                        MemberStateEnum.ACTIVE))),
                                                10001))
                        .isInstanceOf(ServiceException.class)
                        .hasMessageContaining("汇总");
            }
        }
    }

    @Test
    void moneySummaryCountRemainsIntegerAndStorageConstraintsStayForbidden() {
        for (FieldTypeEnum resultType :
                List.of(FieldTypeEnum.TEXT, FieldTypeEnum.DECIMAL, FieldTypeEnum.MONEY)) {
            assertThatThrownBy(
                            () ->
                                    designs.save(
                                            moneySummaryRequest(
                                                    Map.of(
                                                            "total",
                                                            summaryTestOptions(
                                                                    "count(items)",
                                                                    resultType,
                                                                    MemberStateEnum.ACTIVE))),
                                            10001))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("count 汇总不指定字段，结果类型为整数");
        }
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        moneySummaryRequest(
                                                Map.of(
                                                        "total",
                                                        summaryTestOptions(
                                                                "count(items.amount)",
                                                                FieldTypeEnum.INTEGER,
                                                                MemberStateEnum.ACTIVE))),
                                        10001))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("count 汇总不指定字段，结果类型为整数");
        FieldOptions summary =
                summaryTestOptions(
                        "sum(items.amount)", FieldTypeEnum.MONEY, MemberStateEnum.ACTIVE);
        assertThatThrownBy(
                        () ->
                                designs.save(
                                        moneySummaryRequest(
                                                Map.of("total", summary.withDefaultValue("0"))),
                                        10001))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("默认值");
        for (boolean required : List.of(true, false)) {
            SaveDesign request = moneySummaryRequest(Map.of("total", summary));
            SaveObjectDraft draft = request.draft();
            List<FieldDefinition> fields = new ArrayList<>(draft.fields());
            FieldDefinition total = fields.getLast();
            fields.set(
                    fields.size() - 1,
                    new FieldDefinition(
                            total.key(),
                            total.id(),
                            total.code(),
                            total.name(),
                            total.type(),
                            total.length(),
                            total.precision(),
                            total.scale(),
                            required,
                            !required,
                            total.sort()));
            SaveObjectDraft constrained =
                    new SaveObjectDraft(
                            null,
                            null,
                            draft.objectCode(),
                            draft.objectName(),
                            null,
                            draft.tableName(),
                            draft.titleFieldKey(),
                            fields,
                            List.of());
            assertThatThrownBy(
                            () ->
                                    designs.save(
                                            new SaveDesign(
                                                    constrained,
                                                    request.settings(),
                                                    request.fieldOptions(),
                                                    request.relations(),
                                                    request.indexes(),
                                                    request.details()),
                                            10001))
                    .isInstanceOf(ServiceException.class)
                    .hasMessageContaining("存储约束");
        }
    }

    /** 复用当前测试的随机前缀，拒绝路径回滚，成功路径由 fixture.clean 仅清理自有对象。 */
    private SaveDesign moneySummaryRequest(Map<String, FieldOptions> summaryOptions) {
        SaveObjectDraft request = fixture.createRequest("money_summary");
        List<FieldDefinition> main = new ArrayList<>(request.fields());
        summaryOptions
                .keySet()
                .forEach(
                        key ->
                                main.add(
                                        fixture.field(
                                                key,
                                                key,
                                                FieldTypeEnum.SUMMARY.getCode(),
                                                main.size())));
        List<FieldDefinition> detailFields = new ArrayList<>();
        detailFields.add(fixture.field("amount", "amount", FieldTypeEnum.MONEY.getCode(), 0));
        detailFields.add(fixture.field("net", "net", FieldTypeEnum.FORMULA.getCode(), 1));
        detailFields.add(
                fixture.field("text_value", "text_value", FieldTypeEnum.TEXT.getCode(), 2));
        detailFields.add(
                fixture.field("text_formula", "text_formula", FieldTypeEnum.FORMULA.getCode(), 3));
        detailFields.add(
                fixture.field(
                        "inactive_amount", "inactive_amount", FieldTypeEnum.MONEY.getCode(), 4));
        detailFields.add(
                fixture.field("inactive_net", "inactive_net", FieldTypeEnum.FORMULA.getCode(), 5));
        Map<String, FieldOptions> detailOptions =
                Map.of(
                        "net",
                                summaryTestOptions(
                                        "amount * 0.9",
                                        FieldTypeEnum.MONEY,
                                        MemberStateEnum.ACTIVE),
                        "text_formula",
                                summaryTestOptions(
                                        "text_value", FieldTypeEnum.TEXT, MemberStateEnum.ACTIVE),
                        "inactive_amount", summaryTestOptions(null, null, MemberStateEnum.INACTIVE),
                        "inactive_net",
                                summaryTestOptions(
                                        "amount", FieldTypeEnum.MONEY, MemberStateEnum.INACTIVE));
        return new SaveDesign(
                new SaveObjectDraft(
                        null,
                        null,
                        request.objectCode(),
                        request.objectName(),
                        null,
                        request.tableName(),
                        request.titleFieldKey(),
                        main,
                        List.of()),
                Settings.defaults(),
                summaryOptions,
                List.of(),
                List.of(),
                List.of(
                        new Detail(
                                null,
                                "items",
                                "金额明细",
                                "biz_" + fixture.prefix + "_money_items",
                                MemberStateEnum.ACTIVE.getCode(),
                                detailFields,
                                detailOptions,
                                List.of())));
    }

    private FieldOptions summaryTestOptions(
            String expression, FieldTypeEnum resultType, MemberStateEnum state) {
        return new FieldOptions(
                null,
                DataClassificationEnum.NORMAL.getCode(),
                null,
                null,
                null,
                null,
                null,
                state.getCode(),
                List.of(),
                expression,
                resultType == null ? null : resultType.getCode(),
                DisplayResolverEnum.NONE.getCode(),
                null,
                false,
                false);
    }

    @Test
    void unpublishedSavedDetailCanRenameItsCandidateIdentity() {
        var target = create("detail_rename_target", List.of(), List.of());
        publish(target);
        var request = fixture.createRequest("detail_rename");
        var summary = fixture.field("items-count", "items_count", "SUMMARY", 1);
        var main = new ArrayList<>(request.fields());
        main.add(summary);
        var initial =
                designs.save(
                        new SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        request.objectCode(),
                                        request.objectName(),
                                        request.description(),
                                        request.tableName(),
                                        request.titleFieldKey(),
                                        main,
                                        List.of(),
                                        null,
                                        request.category()),
                                Settings.defaults(),
                                Map.of(
                                        summary.key(),
                                        new FieldOptions(
                                                null,
                                                "NORMAL",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "ACTIVE",
                                                List.of(),
                                                "count(items)",
                                                "INTEGER",
                                                "NONE",
                                                null,
                                                false,
                                                false)),
                                List.of(
                                        new Relation(
                                                null,
                                                "detail_target",
                                                "明细目标",
                                                "REFERENCE",
                                                target.draft().id(),
                                                null,
                                                null,
                                                false,
                                                "RESTRICT",
                                                "detail:items")),
                                List.of(
                                        new Index(
                                                null,
                                                "detail_name",
                                                "明细名称索引",
                                                false,
                                                List.of("item"),
                                                false)),
                                List.of(
                                        new Detail(
                                                null,
                                                "items",
                                                "明细",
                                                "biz_" + fixture.prefix + "_items",
                                                "ACTIVE",
                                                List.of(
                                                        fixture.field(
                                                                "item", "item_name", "TEXT", 0)),
                                                Map.of(),
                                                List.of()))),
                        10001);
        var oldDetail = initial.details().getFirst();
        var renamed =
                designs.save(
                        new SaveDesign(
                                new SaveObjectDraft(
                                        initial.draft().id(),
                                        initial.draft().lockVersion(),
                                        initial.draft().objectCode(),
                                        initial.draft().objectName(),
                                        initial.draft().description(),
                                        initial.draft().tableName(),
                                        initial.draft().titleFieldId(),
                                        initial.draft().fields(),
                                        List.of(),
                                        initial.settings().titleTemplate(),
                                        initial.draft().category()),
                                initial.settings(),
                                initial.fieldOptions(),
                                initial.relations(),
                                initial.indexes(),
                                List.of(
                                        new Detail(
                                                oldDetail.id(),
                                                "lines",
                                                "凭证明细",
                                                "biz_" + fixture.prefix + "_lines",
                                                oldDetail.state(),
                                                oldDetail.fields(),
                                                oldDetail.fieldOptions(),
                                                oldDetail.indexes(),
                                                oldDetail.binding())),
                                initial.mainBinding()),
                        10001);

        var current = renamed.details().getFirst();
        assertThat(current.id()).isEqualTo(oldDetail.id());
        assertThat(current.code()).isEqualTo("lines");
        assertThat(current.tableName()).isEqualTo("biz_" + fixture.prefix + "_lines");
        assertThat(renamed.relations().getFirst().sourceDetailId()).isEqualTo(oldDetail.id());
        assertThat(renamed.indexes().getFirst().fieldIds())
                .isEqualTo(initial.indexes().getFirst().fieldIds());
        var currentSummary =
                renamed.draft().fields().stream()
                        .filter(field -> field.code().equals("items_count"))
                        .findFirst()
                        .orElseThrow();
        assertThat(renamed.fieldOptions().get(currentSummary.id()).expression())
                .isEqualTo("count(lines)");
        assertThat(databaseMetadata.readTable("public", oldDetail.tableName())).isEmpty();
        assertThat(databaseMetadata.readTable("public", current.tableName())).isEmpty();

        publish(renamed);
        assertThat(databaseMetadata.readTable("public", oldDetail.tableName())).isEmpty();
        assertThat(databaseMetadata.readTable("public", current.tableName())).isPresent();
    }

    @Test
    void deactivatingARequiredColumnKeepsHistoryAndAllowsFutureRows() {
        var request = fixture.createRequest("required");
        var extra =
                new FieldDefinition(
                        "extra", null, "extra", "历史必填字段", "TEXT", 100, null, null, true, true, 1);
        var fields = new ArrayList<>(request.fields());
        fields.add(extra);
        var d =
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
                                Map.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        publish(d);
        jdbc.update(
                "INSERT INTO public.\"" + d.draft().tableName() + "\" (name,extra) VALUES (?,?)",
                "旧记录",
                "历史值");
        var current = designs.get(d.draft().id());
        var edit =
                designs.editPublished(
                        new Revision(current.draft().id(), current.draft().lockVersion(), null),
                        10001);
        String extraId =
                edit.draft().fields().stream()
                        .filter(f -> f.code().equals("extra"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        var saved =
                designs.save(
                        new SaveDesign(
                                fixture.edit(
                                        edit.draft(),
                                        List.of(),
                                        List.of(extraId),
                                        edit.draft().titleFieldId()),
                                null,
                                null,
                                null,
                                null,
                                null),
                        10001);
        publish(saved);
        jdbc.update(
                "INSERT INTO public.\"" + d.draft().tableName() + "\" (name) VALUES (?)", "新记录");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT extra FROM public.\""
                                        + d.draft().tableName()
                                        + "\" WHERE name='旧记录'",
                                String.class))
                .isEqualTo("历史值");
        assertThat(
                        databaseMetadata
                                .readTable("public", d.draft().tableName())
                                .orElseThrow()
                                .columns())
                .anyMatch(c -> c.name().equals("extra") && c.nullable());
    }
}
