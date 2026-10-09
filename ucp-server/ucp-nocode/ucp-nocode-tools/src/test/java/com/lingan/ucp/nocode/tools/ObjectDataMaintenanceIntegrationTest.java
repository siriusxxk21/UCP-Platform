package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.runtime.service.maintenance.ObjectDataMaintenanceService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;

import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;

import java.util.*;

/** 当前开发库的真实管理入口回归；只操作随机前缀自有对象，禁止借用应用授权。 */
class ObjectDataMaintenanceIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private ObjectDataMaintenanceService maintenance;
    private PermissionCommonApi permission;
    private ObjectDataMaintenance.Model model;
    private String object;
    private String title;

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
        permission = servicesContext.getBean(PermissionCommonApi.class);
        org.mockito.Mockito.when(permission.hasAnyPermissions(10001L, "nocode:object:query"))
                .thenReturn(true);
        org.mockito.Mockito.when(permission.hasAnyPermissions(10001L, "nocode:object:manage"))
                .thenReturn(true);
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.createRequest("maintenance"),
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
        publisher.execute(new DataCenter.ExecutePlan(plan.id(), "管理维护验证"), 10001L);
        object = design.draft().id();
        model = maintenance.model(object, 10001L);
        title = model.model().object().titleFieldId();
    }

    @AfterEach
    void cleanup() {
        fixture.clean();
    }

    private ObjectDataMaintenance.Save request(
            String id, String revision, String name, String key) {
        return new ObjectDataMaintenance.Save(
                object,
                model.versionNo(),
                model.checksum(),
                id,
                revision,
                Map.of(title, name),
                key);
    }

    private ObjectDataMaintenance.Query query() {
        return new ObjectDataMaintenance.Query(object, 1, 20, null, null, null, false, null, null);
    }

    @Test
    void gridTypesComeFromPhysicalColumnsInsteadOfDraftTypes() {
        DataCenter.Definition definition = model.model().object();
        FieldDefinition field =
                definition.fields().stream()
                        .filter(value -> value.id().equals(title))
                        .findFirst()
                        .orElseThrow();
        assertThat(model.columnTypes())
                .containsEntry("__id", "bigint")
                .containsEntry(title, "character varying(200)");
        DataCenter.Design current = designs.get(object);
        ObjectDraft draft =
                designs.editPublished(
                                new DataCenter.Revision(
                                        object, current.draft().lockVersion(), null),
                                10001L)
                        .draft();
        FieldDefinition target =
                new FieldDefinition(
                        field.key(),
                        field.id(),
                        field.code(),
                        field.name(),
                        field.type(),
                        300,
                        field.precision(),
                        field.scale(),
                        field.required(),
                        field.unique(),
                        field.sort());
        service.update(
                new SaveObjectDraft(
                        object,
                        draft.lockVersion(),
                        draft.objectCode(),
                        draft.objectName(),
                        draft.description(),
                        draft.tableName(),
                        title,
                        List.of(target),
                        List.of()),
                10001L,
                UUID.randomUUID());
        assertThat(maintenance.model(object, 10001L).columnTypes())
                .containsEntry(title, "character varying(200)");
        // 绕过发布修改自有夹具时，原有结构漂移保护仍然生效。
        jdbc.execute(
                "ALTER TABLE public.\""
                        + definition.tableName()
                        + "\" ALTER COLUMN \""
                        + field.code()
                        + "\" TYPE varchar(177)");
        assertThatThrownBy(() -> maintenance.model(object, 10001L))
                .hasMessageContaining("字段物理类型发生变化");
    }

    @Test
    void createEditQueryKeepAuditAndIdempotencyWithoutApplication() {
        Long appsBefore =
                jdbc.queryForObject("SELECT count(*) FROM public.nocode_application", Long.class);
        ObjectDataMaintenance.Save create =
                request(null, null, "维护新增", UUID.randomUUID().toString());
        ApplicationRecords.Aggregate first = maintenance.save(create, 10001L);
        ApplicationRecords.Aggregate retry = maintenance.save(create, 10001L);
        assertThat(retry.record().id()).isEqualTo(first.record().id());
        assertThat(maintenance.page(query(), 10001L).getTotal()).isEqualTo(1);
        ApplicationRecords.Aggregate updated =
                maintenance.save(
                        request(
                                first.record().id(),
                                first.record().revision(),
                                "维护修改",
                                UUID.randomUUID().toString()),
                        10001L);
        assertThat(updated.record().values()).containsEntry(title, "维护修改");
        assertThat(updated.record().revision()).isNotEqualTo(first.record().revision());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_application", Long.class))
                .isEqualTo(appsBefore);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_record_history WHERE"
                                        + " object_id=? AND application_id IS NULL AND"
                                        + " source_json->>'kind'='OBJECT_MAINTENANCE'",
                                Long.class,
                                Long.valueOf(object)))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_document_receipt WHERE"
                                        + " object_id=? AND application_id IS NULL",
                                Long.class,
                                Long.valueOf(object)))
                .isEqualTo(2);
    }

    @Test
    void successfulRequestCanRecoverAfterNewPublicationWithoutRepeatingInsert() {
        ObjectDataMaintenance.Save create =
                request(null, null, "响应丢失后恢复", UUID.randomUUID().toString());
        ApplicationRecords.Row first = maintenance.save(create, 10001L).record();
        DataCenter.Design current = designs.get(object);
        DataCenter.Design draft =
                designs.editPublished(
                        new DataCenter.Revision(object, current.draft().lockVersion(), null),
                        10001L);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(object, draft.draft().lockVersion(), null), 10001L);
        publisher.execute(new DataCenter.ExecutePlan(plan.id(), "收据跨发布恢复验证"), 10001L);
        assertThat(maintenance.model(object, 10001L).versionNo()).isGreaterThan(model.versionNo());
        assertThat(maintenance.save(create, 10001L).record().id()).isEqualTo(first.id());
        assertThatThrownBy(
                        () ->
                                maintenance.save(
                                        request(null, null, "变更原请求内容", create.requestKey()),
                                        10001L))
                .hasMessageContaining("不同的保存内容");
        assertThatThrownBy(
                        () ->
                                maintenance.save(
                                        request(null, null, "过期结构新增", UUID.randomUUID().toString()),
                                        10001L))
                .hasMessageContaining("新版本");
        assertThat(maintenance.page(query(), 10001L).getTotal()).isEqualTo(1);
    }

    @Test
    void managementPermissionDoesNotLeakToNormalRuntime() {
        org.mockito.Mockito.when(permission.hasAnyPermissions(20002L, "nocode:object:query"))
                .thenReturn(true);
        assertThatThrownBy(() -> maintenance.model(object, 20002L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(
                        () ->
                                maintenance.save(
                                        request(null, null, "禁止写入", UUID.randomUUID().toString()),
                                        20002L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(
                        () ->
                                servicesContext
                                        .getBean(RecordService.class)
                                        .model(null, object, 10001L))
                .hasMessageContaining("应用上下文");
        assertThat(maintenance.page(query(), 10001L).getTotal()).isZero();
    }

    @Test
    void staleRecordAndStructureRejectWithoutOverwriting() {
        ApplicationRecords.Row row =
                maintenance
                        .save(request(null, null, "最初", UUID.randomUUID().toString()), 10001L)
                        .record();
        maintenance.save(
                request(row.id(), row.revision(), "别人先修改", UUID.randomUUID().toString()), 10001L);
        assertThatThrownBy(
                        () ->
                                maintenance.save(
                                        request(
                                                row.id(),
                                                row.revision(),
                                                "覆盖尝试",
                                                UUID.randomUUID().toString()),
                                        10001L))
                .isInstanceOf(com.lingan.ucp.framework.common.exception.ServiceException.class);
        assertThatThrownBy(
                        () ->
                                maintenance.save(
                                        new ObjectDataMaintenance.Save(
                                                object,
                                                model.versionNo() + 1,
                                                model.checksum(),
                                                null,
                                                null,
                                                Map.of(title, "错误版本"),
                                                UUID.randomUUID().toString()),
                                        10001L))
                .hasMessageContaining("新版本");
        assertThat(maintenance.get(object, row.id(), 10001L).record().values())
                .containsEntry(title, "别人先修改");
        assertThat(maintenance.page(query(), 10001L).getTotal()).isEqualTo(1);
    }

    @Test
    void failedWriteRollsBackAndClearsMaintenanceContext() {
        ObjectDataMaintenance.Save bad =
                new ObjectDataMaintenance.Save(
                        object,
                        model.versionNo(),
                        model.checksum(),
                        null,
                        null,
                        Map.of("unknown-field", "不允许"),
                        UUID.randomUUID().toString());
        assertThatThrownBy(() -> maintenance.save(bad, 10001L)).hasMessageContaining("字段");
        assertThat(maintenance.page(query(), 10001L).getTotal()).isZero();
        assertThatThrownBy(
                        () ->
                                servicesContext
                                        .getBean(RecordService.class)
                                        .model(null, object, 10001L))
                .hasMessageContaining("应用上下文");
    }

    @Test
    void automaticNumberKeepsPublicGenerationAndReadonlyRulesWithoutApplication() {
        SaveObjectDraft source = fixture.createRequest("maintenance_number");
        List<FieldDefinition> fields = new ArrayList<>(source.fields());
        fields.add(
                new FieldDefinition(
                        "number",
                        null,
                        "auto_code",
                        "维护编号",
                        FieldTypeEnum.AUTO_NUMBER.getCode(),
                        null,
                        null,
                        null,
                        true,
                        true,
                        1));
        DataCenter.Design saved =
                designs.save(
                        new DataCenter.SaveDesign(
                                new SaveObjectDraft(
                                        null,
                                        null,
                                        source.objectCode(),
                                        source.objectName(),
                                        null,
                                        source.tableName(),
                                        source.titleFieldKey(),
                                        fields,
                                        List.of()),
                                DataCenter.Settings.defaults(),
                                Map.of(
                                        "number",
                                        DataCenter.FieldOptions.defaults()
                                                .withAutoNumber(
                                                        new AutoNumberOptions(
                                                                "DC-", "", 4, 7L, "NONE"))),
                                List.of(),
                                List.of(),
                                List.of()),
                        10001L);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                saved.draft().id(), saved.draft().lockVersion(), null),
                        10001L);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        publisher.execute(new DataCenter.ExecutePlan(plan.id(), "管理入口自动编号验证"), 10001L);
        ObjectDataMaintenance.Model numbered = maintenance.model(saved.draft().id(), 10001L);
        String nameField = numbered.model().object().titleFieldId();
        String numberField =
                numbered.model().object().fields().stream()
                        .filter(field -> field.code().equals("auto_code"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        assertThat(numbered.readonlyReasons()).containsEntry(numberField, "由对象自动编号规则生成");
        ObjectDataMaintenance.Save create =
                new ObjectDataMaintenance.Save(
                        saved.draft().id(),
                        numbered.versionNo(),
                        numbered.checksum(),
                        null,
                        null,
                        Map.of(nameField, "维护生成编号"),
                        UUID.randomUUID().toString());
        ApplicationRecords.Row first = maintenance.save(create, 10001L).record();
        assertThat(first.values()).containsEntry(numberField, "DC-0007");
        assertThat(maintenance.save(create, 10001L).record().id()).isEqualTo(first.id());
        ApplicationRecords.Row updated =
                maintenance
                        .save(
                                new ObjectDataMaintenance.Save(
                                        saved.draft().id(),
                                        numbered.versionNo(),
                                        numbered.checksum(),
                                        first.id(),
                                        first.revision(),
                                        Map.of(nameField, "修改不重发编号"),
                                        UUID.randomUUID().toString()),
                                10001L)
                        .record();
        assertThat(updated.values()).containsEntry(numberField, "DC-0007");
        assertThatThrownBy(
                        () ->
                                maintenance.save(
                                        new ObjectDataMaintenance.Save(
                                                saved.draft().id(),
                                                numbered.versionNo(),
                                                numbered.checksum(),
                                                updated.id(),
                                                updated.revision(),
                                                Map.of(numberField, "伪造编号"),
                                                UUID.randomUUID().toString()),
                                        10001L))
                .hasMessageContaining("字段");
        ApplicationRecords.Row second =
                maintenance
                        .save(
                                new ObjectDataMaintenance.Save(
                                        saved.draft().id(),
                                        numbered.versionNo(),
                                        numbered.checksum(),
                                        null,
                                        null,
                                        Map.of(nameField, "第二条"),
                                        UUID.randomUUID().toString()),
                                10001L)
                        .record();
        assertThat(second.values()).containsEntry(numberField, "DC-0008");
        assertThat(maintenance.get(saved.draft().id(), first.id(), 10001L).record().values())
                .containsEntry(numberField, "DC-0007");
    }
}
