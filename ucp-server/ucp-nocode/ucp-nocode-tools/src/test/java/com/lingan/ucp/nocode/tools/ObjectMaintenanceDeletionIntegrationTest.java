package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.Row;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.api.ObjectDataMaintenance.Delete;
import com.lingan.ucp.nocode.api.ObjectDataMaintenance.DeletePreview;
import com.lingan.ucp.nocode.enums.ObjectDataImpactActionEnum;
import com.lingan.ucp.nocode.metadata.service.table.DataTableService;
import com.lingan.ucp.nocode.runtime.service.maintenance.ObjectDataMaintenanceService;

import org.junit.jupiter.api.*;

import java.util.*;

/** 当前开发库验证删除检查、确认失效和实际关系策略；仅清理随机前缀自有对象。 */
class ObjectMaintenanceDeletionIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ObjectDataMaintenanceService maintenance;
    private DataObjectApi objects;
    private int serial;

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
        maintenance = servicesContext.getBean(ObjectDataMaintenanceService.class);
        objects = servicesContext.getBean(DataObjectApi.class);
        PermissionCommonApi permissions = servicesContext.getBean(PermissionCommonApi.class);
        org.mockito.Mockito.when(permissions.hasAnyPermissions(10001L, "nocode:object:query"))
                .thenReturn(true);
        org.mockito.Mockito.when(permissions.hasAnyPermissions(10001L, "nocode:object:manage"))
                .thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    @Test
    void restrictionNamesExactSourceAndPreviewDoesNotWrite() {
        Definition target = object();
        Definition source = related(target, "REFERENCE", "RESTRICT", false);
        Row parent = create(target, "删除目标", Map.of());
        Row linked =
                create(
                        source,
                        "仍在使用的采购单",
                        Map.of(source.relations().getFirst().fieldId(), parent.id()));
        Long histories =
                jdbc.queryForObject(
                        "SELECT count(*) FROM public.nocode_record_history WHERE object_id IN"
                                + " (?,?)",
                        Long.class,
                        Long.valueOf(target.objectId()),
                        Long.valueOf(source.objectId()));
        DeletePreview preview = maintenance.previewDelete(command(target, parent, null), 10001);
        assertThat(preview.allowed()).isFalse();
        assertThat(preview.impacts())
                .anySatisfy(
                        impact -> {
                            assertThat(impact.action())
                                    .isEqualTo(ObjectDataImpactActionEnum.BLOCK.getCode());
                            assertThat(impact.objectId()).isEqualTo(source.objectId());
                            assertThat(impact.recordId()).isEqualTo(linked.id());
                            assertThat(impact.recordTitle()).isEqualTo("仍在使用的采购单");
                            assertThat(impact.relationName()).isEqualTo("演示引用");
                        });
        assertThat(maintenance.get(target.objectId(), parent.id(), 10001).record().revision())
                .isEqualTo(parent.revision());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_record_history WHERE object_id"
                                        + " IN (?,?)",
                                Long.class,
                                Long.valueOf(target.objectId()),
                                Long.valueOf(source.objectId())))
                .isEqualTo(histories);
        assertThatThrownBy(
                        () ->
                                maintenance.delete(
                                        command(target, parent, preview.impactToken()), 10001))
                .hasMessageContaining("采购单");
    }

    @Test
    void setNullKeepsRecordAndRejectsChangedAffectedRevision() {
        Definition target = object();
        Definition source = related(target, "REFERENCE", "SET_NULL", false);
        Row parent = create(target, "主记录", Map.of());
        String reference = source.relations().getFirst().fieldId();
        Row linked = create(source, "引用记录", Map.of(reference, parent.id()));
        DeletePreview before = maintenance.previewDelete(command(target, parent, null), 10001);
        assertThat(before.allowed()).isTrue();
        assertThat(before.impacts())
                .anyMatch(
                        impact ->
                                impact.recordId().equals(linked.id())
                                        && impact.objectId().equals(source.objectId())
                                        && ObjectDataImpactActionEnum.CLEAR_REFERENCE.matches(
                                                impact.action()));
        update(source, linked, Map.of(source.titleFieldId(), "引用记录已修改"));
        DeletePreview after = maintenance.previewDelete(command(target, parent, null), 10001);
        assertThat(after.impactToken()).isNotEqualTo(before.impactToken());
        assertThatThrownBy(
                        () ->
                                maintenance.delete(
                                        command(target, parent, before.impactToken()), 10001))
                .hasMessageContaining("删除影响已变化");
        assertThat(maintenance.get(source.objectId(), linked.id(), 10001).record().values())
                .containsEntry(reference, parent.id());
        maintenance.delete(command(target, parent, after.impactToken()), 10001);
        assertThat(maintenance.get(source.objectId(), linked.id(), 10001).record().values())
                .containsEntry(reference, null)
                .containsEntry(source.titleFieldId(), "引用记录已修改");
        assertThatThrownBy(() -> maintenance.get(target.objectId(), parent.id(), 10001))
                .hasMessageContaining("不存在");
    }

    @Test
    void multipleSetNullReferencesValidateTheCombinedDocumentCandidate() {
        Definition target = object();
        Design design =
                designs.save(
                        new SaveDesign(
                                fixture.createRequest("two_refs" + serial++),
                                Settings.defaults(),
                                Map.of(),
                                List.of(
                                        new Relation(
                                                null,
                                                "left_ref",
                                                "第一供应商",
                                                "REFERENCE",
                                                target.objectId(),
                                                null,
                                                null,
                                                false,
                                                "SET_NULL"),
                                        new Relation(
                                                null,
                                                "right_ref",
                                                "第二供应商",
                                                "REFERENCE",
                                                target.objectId(),
                                                null,
                                                null,
                                                false,
                                                "SET_NULL")),
                                List.of(),
                                List.of()),
                        10001);
        String left = design.relations().get(0).fieldId();
        String right = design.relations().get(1).fieldId();
        DocumentPolicy.Expression assertion =
                new DocumentPolicy.Expression(
                        "OR", null, null, null, List.of(present(left), present(right)));
        DocumentPolicy document =
                new DocumentPolicy(
                        List.of(
                                new DocumentPolicy.Rule(
                                        "one_reference",
                                        "至少一个供应商",
                                        "DOCUMENT",
                                        null,
                                        null,
                                        assertion,
                                        null,
                                        "至少保留一个供应商引用")),
                        null);
        ObjectDraft draft = design.draft();
        Definition source =
                publish(
                        designs.save(
                                new SaveDesign(
                                        new SaveObjectDraft(
                                                draft.id(),
                                                draft.lockVersion(),
                                                draft.objectCode(),
                                                draft.objectName(),
                                                draft.description(),
                                                draft.tableName(),
                                                draft.titleFieldId(),
                                                draft.fields(),
                                                List.of()),
                                        new Settings(null, null, null, null, document),
                                        design.fieldOptions(),
                                        design.relations(),
                                        design.indexes(),
                                        design.details(),
                                        design.mainBinding()),
                                10001));
        Row parent = create(target, "共同目标", Map.of());
        Row row = create(source, "必须保留一个引用", Map.of(left, parent.id(), right, parent.id()));
        DeletePreview preview = maintenance.previewDelete(command(target, parent, null), 10001);
        assertThat(preview.allowed()).isFalse();
        assertThat(preview.impacts())
                .anyMatch(
                        impact ->
                                ObjectDataImpactActionEnum.BLOCK.matches(impact.action())
                                        && impact.message().contains("至少保留一个供应商引用"));
        assertThat(maintenance.get(source.objectId(), row.id(), 10001).record().values())
                .containsEntry(left, parent.id())
                .containsEntry(right, parent.id());
    }

    private static DocumentPolicy.Expression present(String field) {
        return new DocumentPolicy.Expression(
                "NE",
                null,
                null,
                null,
                List.of(
                        new DocumentPolicy.Expression("FIELD", field, null, null, List.of()),
                        new DocumentPolicy.Expression("VALUE", null, null, null, List.of())));
    }

    @Test
    void cascadeShowsChildAndInternalRowsThenDeletesTheSameRows() {
        Definition target = object();
        Definition child = related(target, "MASTER_DETAIL", "CASCADE", true);
        Row parent = create(target, "父记录", Map.of());
        Row linked =
                create(child, "从记录", Map.of(child.relations().getFirst().fieldId(), parent.id()));
        Detail detail = child.details().getFirst();
        String detailId =
                jdbc.queryForObject(
                        "INSERT INTO public.\""
                                + detail.tableName()
                                + "\" (id, parent_id, note) VALUES (91001,?,?) RETURNING id::text",
                        String.class,
                        Long.valueOf(linked.id()),
                        "内部明细");
        DeletePreview preview = maintenance.previewDelete(command(target, parent, null), 10001);
        assertThat(preview.allowed()).isTrue();
        assertThat(
                        preview.impacts().stream()
                                .filter(
                                        impact ->
                                                ObjectDataImpactActionEnum.DELETE.matches(
                                                        impact.action())))
                .hasSize(3);
        assertThat(preview.impacts())
                .anyMatch(
                        impact ->
                                "行项目".equals(impact.relationName())
                                        && linked.id().equals(impact.recordId())
                                        && impact.recordTitle().contains("明细 #" + detailId));
        assertThat(activeRows(detail.tableName())).isEqualTo(1);
        maintenance.delete(command(target, parent, preview.impactToken()), 10001);
        assertThat(activeRows(target.tableName())).isZero();
        assertThat(activeRows(child.tableName())).isZero();
        assertThat(activeRows(detail.tableName())).isZero();
    }

    @Test
    void manyToManyDetachesOnlyLinkAndNewSourceInvalidatesConfirmation() {
        Definition target = object();
        Definition source = related(target, "MANY_TO_MANY", "SET_NULL", false);
        Row parent = create(target, "关联目标", Map.of());
        Row first = create(source, "关联来源一", Map.of());
        attach(source, first, parent);
        DeletePreview before = maintenance.previewDelete(command(target, parent, null), 10001);
        assertThat(before.allowed()).isTrue();
        Row second = create(source, "关联来源二", Map.of());
        attach(source, second, parent);
        DeletePreview after = maintenance.previewDelete(command(target, parent, null), 10001);
        assertThat(after.impactToken()).isNotEqualTo(before.impactToken());
        assertThat(
                        after.impacts().stream()
                                .filter(
                                        impact ->
                                                ObjectDataImpactActionEnum.CLEAR_REFERENCE.matches(
                                                        impact.action())))
                .hasSize(2);
        assertThatThrownBy(
                        () ->
                                maintenance.delete(
                                        command(target, parent, before.impactToken()), 10001))
                .hasMessageContaining("删除影响已变化");
        maintenance.delete(command(target, parent, after.impactToken()), 10001);
        assertThat(activeRows(source.tableName())).isEqualTo(2);
        assertThat(activeRows(relationTable(source))).isZero();
    }

    @Test
    void incomingInternalDetailPointsToTheActualDetailWithoutDeletingIt() {
        Definition target = object();
        SaveObjectDraft base = fixture.createRequest("detail_source" + serial++);
        Detail detail =
                new Detail(
                        null,
                        "lines",
                        "领用明细",
                        "biz_" + fixture.prefix + "lines" + serial++,
                        "ACTIVE",
                        List.of(fixture.field("note", "note", "TEXT", 0)),
                        Map.of(),
                        List.of());
        Definition source =
                publish(
                        designs.save(
                                new SaveDesign(
                                        base,
                                        Settings.defaults(),
                                        Map.of(),
                                        List.of(
                                                new Relation(
                                                        null,
                                                        "related",
                                                        "明细供应商",
                                                        "REFERENCE",
                                                        target.objectId(),
                                                        null,
                                                        null,
                                                        false,
                                                        "RESTRICT",
                                                        "detail:lines")),
                                        List.of(),
                                        List.of(detail)),
                                10001));
        Row parent = create(target, "供应商", Map.of());
        Row main = create(source, "领用单", Map.of());
        Detail stored = source.details().getFirst();
        String referenceId = source.relations().getFirst().fieldId();
        FieldDefinition reference =
                stored.fields().stream()
                        .filter(field -> field.id().equals(referenceId))
                        .findFirst()
                        .orElseThrow();
        String column =
                stored.fieldOptions()
                        .getOrDefault(referenceId, FieldOptions.defaults())
                        .columnName();
        if (column == null) column = reference.code();
        String id =
                jdbc.queryForObject(
                        "INSERT INTO public.\""
                                + stored.tableName()
                                + "\" (id, parent_id, note, \""
                                + column
                                + "\") VALUES (92001,?,?,?) RETURNING id::text",
                        String.class,
                        Long.valueOf(main.id()),
                        "仍然引用",
                        Long.valueOf(parent.id()));
        DeletePreview preview = maintenance.previewDelete(command(target, parent, null), 10001);
        assertThat(preview.allowed()).isFalse();
        assertThat(preview.impacts())
                .anyMatch(
                        impact ->
                                ObjectDataImpactActionEnum.BLOCK.matches(impact.action())
                                        && main.id().equals(impact.recordId())
                                        && impact.recordTitle().contains("明细 #" + id)
                                        && impact.relationName().contains("领用明细")
                                        && impact.message().contains("主单据"));
        assertThat(activeRows(stored.tableName())).isEqualTo(1);
    }

    @Test
    void protectedLifecycleReturnsSpecificBlockedImpact() {
        SaveObjectDraft base = fixture.createRequest("state" + serial++);
        List<FieldDefinition> fields = new ArrayList<>(base.fields());
        fields.add(fixture.field("state", "state", "SELECT", 1));
        FieldOptions stateOptions =
                new FieldOptions(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "ACTIVE",
                        List.of(new Option("locked", "已锁定", false)),
                        null,
                        null,
                        "NONE",
                        null,
                        false,
                        false);
        DocumentPolicy document =
                new DocumentPolicy(
                        List.of(),
                        new DocumentPolicy.Lifecycle(
                                "state",
                                "locked",
                                List.of(
                                        new DocumentPolicy.State(
                                                "locked", "已锁定", List.of(), List.of(), false)),
                                List.of()));
        Definition target =
                publish(
                        designs.save(
                                new SaveDesign(
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
                                        new Settings(null, null, null, null, document),
                                        Map.of("state", stateOptions),
                                        List.of(),
                                        List.of(),
                                        List.of()),
                                10001));
        Row row = create(target, "锁定单据", Map.of());
        DeletePreview preview = maintenance.previewDelete(command(target, row, null), 10001);
        assertThat(preview.allowed()).isFalse();
        assertThat(preview.impacts())
                .anyMatch(
                        impact ->
                                ObjectDataImpactActionEnum.BLOCK.matches(impact.action())
                                        && impact.recordTitle().equals("锁定单据")
                                        && impact.message().contains("当前状态"));
        assertThat(activeRows(target.tableName())).isEqualTo(1);
    }

    @Test
    void tooManyIncomingRowsBlockWithoutPartialDeletion() {
        Definition target = object();
        Definition source = related(target, "MASTER_DETAIL", "CASCADE", false);
        Row row = create(target, "超过单次删除容量", Map.of());
        String field = source.relations().getFirst().fieldId();
        FieldDefinition reference =
                source.fields().stream()
                        .filter(value -> value.id().equals(field))
                        .findFirst()
                        .orElseThrow();
        String column =
                source.fieldOptions().getOrDefault(field, FieldOptions.defaults()).columnName();
        if (column == null) column = reference.code();
        jdbc.update(
                "INSERT INTO public.\""
                        + source.tableName()
                        + "\" (name, \""
                        + column
                        + "\") SELECT '从记录' || n, ? FROM generate_series(1,201) n",
                Long.valueOf(row.id()));
        DeletePreview preview = maintenance.previewDelete(command(target, row, null), 10001);
        assertThat(preview.allowed()).isFalse();
        assertThat(preview.impacts())
                .anyMatch(
                        impact ->
                                impact.message().contains("超过 200")
                                        && impact.message().contains("201")
                                        && impact.recordId() == null);
        assertThat(activeRows(source.tableName())).isEqualTo(201);
    }

    private Definition object() {
        return publish(
                designs.save(
                        new SaveDesign(
                                fixture.createRequest("target" + serial++),
                                Settings.defaults(),
                                Map.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001));
    }

    private Definition related(Definition target, String kind, String onDelete, boolean details) {
        List<Detail> groups =
                details
                        ? List.of(
                                new Detail(
                                        null,
                                        "lines",
                                        "行项目",
                                        "biz_" + fixture.prefix + "lines" + serial++,
                                        "ACTIVE",
                                        List.of(fixture.field("note", "note", "TEXT", 0)),
                                        Map.of(),
                                        List.of()))
                        : List.of();
        return publish(
                designs.save(
                        new SaveDesign(
                                fixture.createRequest("source" + serial++),
                                Settings.defaults(),
                                Map.of(),
                                List.of(
                                        new Relation(
                                                null,
                                                "related",
                                                "演示引用",
                                                kind,
                                                target.objectId(),
                                                null,
                                                null,
                                                false,
                                                onDelete)),
                                List.of(),
                                groups),
                        10001));
    }

    private Definition publish(Design design) {
        PublishPlan plan =
                publisher.plan(
                        new Revision(design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        assertThat(publisher.execute(new ExecutePlan(plan.id(), "删除影响验证"), 10001).state())
                .isEqualTo("SUCCEEDED");
        return objects.getPublished(design.draft().id());
    }

    private Row create(Definition d, String title, Map<String, Object> extra) {
        Map<String, Object> input = new LinkedHashMap<>(extra);
        input.put(d.titleFieldId(), title);
        return save(d, null, input);
    }

    private Row update(Definition d, Row row, Map<String, Object> input) {
        return save(d, row, input);
    }

    private Row save(Definition d, Row row, Map<String, Object> input) {
        DataObjectApi.PublishedObject version = objects.getVersion(d.objectId(), null);
        return maintenance
                .save(
                        new ObjectDataMaintenance.Save(
                                d.objectId(),
                                version.versionNo(),
                                version.checksum(),
                                row == null ? null : row.id(),
                                row == null ? null : row.revision(),
                                input,
                                UUID.randomUUID().toString()),
                        10001)
                .record();
    }

    private Delete command(Definition d, Row row, String token) {
        DataObjectApi.PublishedObject version = objects.getVersion(d.objectId(), null);
        return new Delete(
                d.objectId(),
                version.versionNo(),
                version.checksum(),
                row.id(),
                row.revision(),
                token);
    }

    private long activeRows(String table) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.\"" + table + "\" WHERE deleted=0", Long.class);
    }

    private String relationTable(Definition source) {
        return DataTableService.relationTable(
                databaseMetadata,
                source.schemaName(),
                source.objectId(),
                source.relations().getFirst().id());
    }

    private void attach(Definition source, Row row, Row target) {
        jdbc.update(
                "INSERT INTO public.\""
                        + relationTable(source)
                        + "\" (source_id, target_id) VALUES (?,?)",
                Long.valueOf(row.id()),
                Long.valueOf(target.id()));
    }
}
