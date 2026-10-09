package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.runtime.service.maintenance.ObjectDataMaintenanceService;

import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;

/** 仅对随机前缀发布夹具执行整列清空；有效及逻辑删除值均真实落库核验。 */
class ObjectColumnMaintenanceIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ObjectDataMaintenanceService maintenance;
    private ObjectDataMaintenance.Model model;
    private String object;
    private String title;
    private String optional;

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
        PermissionCommonApi permission = servicesContext.getBean(PermissionCommonApi.class);
        org.mockito.Mockito.when(permission.hasAnyPermissions(10001L, "nocode:object:query"))
                .thenReturn(true);
        org.mockito.Mockito.when(permission.hasAnyPermissions(10001L, "nocode:object:manage"))
                .thenReturn(true);
        SaveObjectDraft source = fixture.createRequest("clear_column");
        List<FieldDefinition> fields = new ArrayList<>(source.fields());
        fields.add(fixture.field("note", "note", FieldTypeEnum.TEXT.getCode(), 1));
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        source.objectCode(),
                                        source.objectName(),
                                        source.description(),
                                        source.tableName(),
                                        source.titleFieldKey(),
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001L);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001L);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        publisher.execute(new DataCenter.ExecutePlan(plan.id(), "整列清空验证"), 10001L);
        object = design.draft().id();
        model = maintenance.model(object, 10001L);
        title = model.model().object().titleFieldId();
        optional =
                model.model().object().fields().stream()
                        .filter(field -> field.code().equals("note"))
                        .findFirst()
                        .orElseThrow()
                        .id();
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    private ObjectDataMaintenance.ClearColumn clear(String field, String token) {
        return new ObjectDataMaintenance.ClearColumn(
                object, model.versionNo(), model.checksum(), null, field, token);
    }

    private ApplicationRecords.Row row(String name, String note) {
        return maintenance
                .save(
                        new ObjectDataMaintenance.Save(
                                object,
                                model.versionNo(),
                                model.checksum(),
                                null,
                                null,
                                Map.of(title, name, optional, note),
                                UUID.randomUUID().toString()),
                        10001L)
                .record();
    }

    @Test
    void clearsOnlySelectedColumnIncludingLogicallyDeletedRowsAndKeepsOtherValues() {
        ApplicationRecords.Row active = row("有效行", "旧值一");
        ApplicationRecords.Row deleted = row("删除行", "旧值二");
        String table = model.model().object().tableName();
        jdbc.update(
                "UPDATE public.\"" + table + "\" SET deleted=1 WHERE id=?",
                Long.valueOf(deleted.id()));
        ObjectDataMaintenance.ClearColumnPreview preview =
                maintenance.previewClearColumn(clear(optional, null), 10001L);
        assertThat(preview.allowed()).isTrue();
        assertThat(preview.activeRows()).isEqualTo(1);
        assertThat(preview.deletedRows()).isEqualTo(1);
        assertThat(preview.impactToken()).isNotBlank();
        ObjectDataMaintenance.ClearColumnResult result =
                maintenance.clearColumn(clear(optional, preview.impactToken()), 10001L);
        assertThat(result.clearedActiveRows()).isEqualTo(1);
        assertThat(result.clearedDeletedRows()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.\""
                                        + table
                                        + "\" WHERE note IS NOT NULL",
                                Long.class))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT name FROM public.\"" + table + "\" WHERE id=?",
                                String.class,
                                Long.valueOf(active.id())))
                .isEqualTo("有效行");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT name FROM public.\"" + table + "\" WHERE id=?",
                                String.class,
                                Long.valueOf(deleted.id())))
                .isEqualTo("删除行");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT deleted FROM public.\"" + table + "\" WHERE id=?",
                                Integer.class,
                                Long.valueOf(deleted.id())))
                .isEqualTo(1);
        assertThatThrownBy(
                        () ->
                                maintenance.save(
                                        new ObjectDataMaintenance.Save(
                                                object,
                                                model.versionNo(),
                                                model.checksum(),
                                                active.id(),
                                                active.revision(),
                                                Map.of(optional, "旧页面恢复值"),
                                                UUID.randomUUID().toString()),
                                        10001L))
                .hasMessageContaining("记录已被修改");
    }

    @Test
    void titleAndStaleImpactAreBlockedWithoutChangingRows() {
        ApplicationRecords.Row first = row("标题不清", "原值");
        ObjectDataMaintenance.ClearColumnPreview titlePreview =
                maintenance.previewClearColumn(clear(title, null), 10001L);
        assertThat(titlePreview.allowed()).isFalse();
        assertThat(titlePreview.blockers()).anyMatch(value -> value.contains("记录标题"));
        assertThatThrownBy(() -> maintenance.clearColumn(clear(title, "伪造确认"), 10001L))
                .hasMessageContaining("记录标题");
        ObjectDataMaintenance.ClearColumnPreview previous =
                maintenance.previewClearColumn(clear(optional, null), 10001L);
        String table = model.model().object().tableName();
        jdbc.update(
                "UPDATE public.\"" + table + "\" SET note='后来修改' WHERE id=?",
                Long.valueOf(first.id()));
        assertThatThrownBy(
                        () ->
                                maintenance.clearColumn(
                                        clear(optional, previous.impactToken()), 10001L))
                .hasMessageContaining("已变化");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT note FROM public.\"" + table + "\" WHERE id=?",
                                String.class,
                                Long.valueOf(first.id())))
                .isEqualTo("后来修改");
    }

    @Test
    void unauthorizedAndRequiredFieldCannotBeCleared() {
        assertThatThrownBy(() -> maintenance.previewClearColumn(clear(optional, null), 20002L))
                .isInstanceOf(AccessDeniedException.class);
        SaveObjectDraft source = fixture.createRequest("required_clear");
        List<FieldDefinition> fields = new ArrayList<>(source.fields());
        fields.add(
                new FieldDefinition(
                        "required_note",
                        null,
                        "required_note",
                        "必填内容",
                        FieldTypeEnum.TEXT.getCode(),
                        null,
                        null,
                        null,
                        true,
                        false,
                        1));
        Design design =
                designs.save(
                        new SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        source.objectCode(),
                                        source.objectName(),
                                        source.description(),
                                        source.tableName(),
                                        source.titleFieldKey(),
                                        fields,
                                        List.of()),
                                Settings.defaults(),
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001L);
        PublishPlan plan =
                publisher.plan(
                        new Revision(design.draft().id(), design.draft().lockVersion(), null),
                        10001L);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        publisher.execute(new ExecutePlan(plan.id(), "必填列阻断验证"), 10001L);
        ObjectDataMaintenance.Model requiredModel = maintenance.model(design.draft().id(), 10001L);
        String requiredId =
                requiredModel.model().object().fields().stream()
                        .filter(field -> field.code().equals("required_note"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        ObjectDataMaintenance.ClearColumnPreview preview =
                maintenance.previewClearColumn(
                        new ObjectDataMaintenance.ClearColumn(
                                design.draft().id(),
                                requiredModel.versionNo(),
                                requiredModel.checksum(),
                                null,
                                requiredId,
                                null),
                        10001L);
        assertThat(preview.allowed()).isFalse();
        assertThat(preview.blockers()).anyMatch(reason -> reason.contains("必填"));
    }

    @Test
    void nullableObjectReferenceCanBeUnlinkedWithoutDeletingEitherRecord() {
        SaveObjectDraft source = fixture.createRequest("reference_clear");
        Design design =
                designs.save(
                        new SaveDesign(
                                source,
                                Settings.defaults(),
                                Map.of(),
                                List.of(
                                        new Relation(
                                                null,
                                                "related",
                                                "演示引用",
                                                "REFERENCE",
                                                object,
                                                null,
                                                null,
                                                false,
                                                "RESTRICT")),
                                List.of(),
                                List.of()),
                        10001L);
        PublishPlan plan =
                publisher.plan(
                        new Revision(design.draft().id(), design.draft().lockVersion(), null),
                        10001L);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        publisher.execute(new ExecutePlan(plan.id(), "可空引用清空验证"), 10001L);
        ObjectDataMaintenance.Model sourceModel = maintenance.model(design.draft().id(), 10001L);
        String reference = sourceModel.model().object().relations().getFirst().fieldId();
        ApplicationRecords.Row target = row("被引用目标", "仍保留");
        ApplicationRecords.Row linked =
                maintenance
                        .save(
                                new ObjectDataMaintenance.Save(
                                        design.draft().id(),
                                        sourceModel.versionNo(),
                                        sourceModel.checksum(),
                                        null,
                                        null,
                                        Map.of(
                                                sourceModel.model().object().titleFieldId(),
                                                "引用行",
                                                reference,
                                                target.id()),
                                        UUID.randomUUID().toString()),
                                10001L)
                        .record();
        ObjectDataMaintenance.ClearColumn command =
                new ObjectDataMaintenance.ClearColumn(
                        design.draft().id(),
                        sourceModel.versionNo(),
                        sourceModel.checksum(),
                        null,
                        reference,
                        null);
        ObjectDataMaintenance.ClearColumnPreview preview =
                maintenance.previewClearColumn(command, 10001L);
        assertThat(preview.allowed()).isTrue();
        maintenance.clearColumn(
                new ObjectDataMaintenance.ClearColumn(
                        design.draft().id(),
                        sourceModel.versionNo(),
                        sourceModel.checksum(),
                        null,
                        reference,
                        preview.impactToken()),
                10001L);
        assertThat(maintenance.get(design.draft().id(), linked.id(), 10001L).record().values())
                .containsEntry(reference, null);
        assertThat(maintenance.get(object, target.id(), 10001L).record().values())
                .containsEntry(title, "被引用目标");
    }

    @Test
    void persistedOnSaveCalculationBlocksDirectSourceClear() {
        SaveObjectDraft source = fixture.createRequest("calculated_clear");
        List<FieldDefinition> fields = new ArrayList<>(source.fields());
        fields.add(fixture.field("amount", "amount", FieldTypeEnum.DECIMAL.getCode(), 1));
        fields.add(fixture.field("twice", "twice", FieldTypeEnum.FORMULA.getCode(), 2));
        FieldOptions formula =
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
                        "amount * 2",
                        "DECIMAL",
                        "NONE",
                        null,
                        false,
                        false,
                        null,
                        new CalculationOptions(
                                "LOCAL", "ON_SAVE", null, null, null, null, "AND", List.of(), false,
                                List.of(), null));
        Design design =
                designs.save(
                        new SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        source.objectCode(),
                                        source.objectName(),
                                        source.description(),
                                        source.tableName(),
                                        source.titleFieldKey(),
                                        fields,
                                        List.of()),
                                Settings.defaults(),
                                Map.of("twice", formula),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001L);
        PublishPlan plan =
                publisher.plan(
                        new Revision(design.draft().id(), design.draft().lockVersion(), null),
                        10001L);
        assertThat(plan.checks()).noneMatch(Check::blocking);
        publisher.execute(new ExecutePlan(plan.id(), "持久计算清空保护验证"), 10001L);
        ObjectDataMaintenance.Model calculatedModel =
                maintenance.model(design.draft().id(), 10001L);
        String amount =
                calculatedModel.model().object().fields().stream()
                        .filter(field -> field.code().equals("amount"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        maintenance.save(
                new ObjectDataMaintenance.Save(
                        design.draft().id(),
                        calculatedModel.versionNo(),
                        calculatedModel.checksum(),
                        null,
                        null,
                        Map.of(calculatedModel.model().object().titleFieldId(), "计算单", amount, "3"),
                        UUID.randomUUID().toString()),
                10001L);
        ObjectDataMaintenance.ClearColumnPreview preview =
                maintenance.previewClearColumn(
                        new ObjectDataMaintenance.ClearColumn(
                                design.draft().id(),
                                calculatedModel.versionNo(),
                                calculatedModel.checksum(),
                                null,
                                amount,
                                null),
                        10001L);
        assertThat(preview.allowed()).isFalse();
        assertThat(preview.blockers()).anyMatch(reason -> reason.contains("持久计算"));
    }
}
