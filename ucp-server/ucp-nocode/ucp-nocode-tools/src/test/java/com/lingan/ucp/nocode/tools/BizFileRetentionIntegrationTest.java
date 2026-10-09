package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.module.drive.api.bizfile.DriveBizFileApi;
import com.lingan.ucp.module.drive.api.bizfile.dto.DriveBizEntryDTO;
import com.lingan.ucp.module.infra.dal.dataobject.file.FileDO;
import com.lingan.ucp.module.infra.service.file.FileService;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.work.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.enums.ApplicationActionEnum;
import com.lingan.ucp.nocode.enums.WorkSourceEnum;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileRetentionService;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileUploadService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.work.WorkFormService;

import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 在当前开发库验证业务文件保留引用：记录历史按一次变更的操作编号登记附件/图片字段的前后并集， 数量等普通字段不得误判；任务入口草稿按草稿编号整体替换登记并顺延本人仍有效的上传会话；
 * 清理复核只认有效持有者，草稿离开 DRAFT 后不再保留内容。保留引用表不在通用清理范围，本测试按对象前缀自行清理。
 */
class BizFileRetentionIntegrationTest {
    private static final long SPACE_ID = 910001L;
    private static final long RECORD_ENTRY_ID = 920001L;
    private static final long FILE_ENTRY_ID = 930011L;
    private static final long ACTOR = 10001L;

    private NocodeIntegrationSupport fixture;
    private BizFileUploadService upload;
    private BizFileRetentionService retention;
    private WorkFormService workForms;
    private RecordService runtime;
    private DataObjectApi objects;
    private ApplicationService apps;
    private ObjectSharingService sharing;
    private DriveBizFileApi drive;
    private final AtomicLong fileSeq = new AtomicLong(760000L);

    private String objectId;
    private String detailId;
    private String titleId;
    private String filesId;
    private String docId;
    private String labelId;
    private String qtyId;

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
        upload = servicesContext.getBean(BizFileUploadService.class);
        retention = servicesContext.getBean(BizFileRetentionService.class);
        workForms = servicesContext.getBean(WorkFormService.class);
        runtime = servicesContext.getBean(RecordService.class);
        objects = servicesContext.getBean(DataObjectApi.class);
        apps = servicesContext.getBean(ApplicationService.class);
        sharing = servicesContext.getBean(ObjectSharingService.class);
        drive = servicesContext.getBean(DriveBizFileApi.class);
        org.mockito.Mockito.clearInvocations(drive);
        org.mockito.Mockito.reset(servicesContext.getBean(FileService.class));
    }

    @AfterEach
    void cleanup() {
        List<Long> objectIds =
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_object WHERE object_code LIKE ?",
                        Long.class,
                        fixture.prefix + "%");
        for (Long id : objectIds) {
            jdbc.update(
                    "DELETE FROM public.nocode_biz_file_retention WHERE object_id=?",
                    id.toString());
            jdbc.update(
                    "DELETE FROM public.nocode_biz_attachment_binding WHERE object_id=?",
                    id.toString());
            jdbc.update(
                    "DELETE FROM public.nocode_biz_directory_binding WHERE object_id=?",
                    id.toString());
            jdbc.update(
                    "DELETE FROM public.nocode_biz_upload_session WHERE object_id=?",
                    id.toString());
        }
        List<Long> appIds =
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        fixture.prefix + "%");
        for (Long id : appIds) {
            jdbc.update(
                    "DELETE FROM public.nocode_work_submission WHERE draft_id IN (SELECT id FROM"
                            + " public.nocode_work_draft WHERE resource_json->>'applicationId'=?)",
                    id.toString());
            jdbc.update(
                    "DELETE FROM public.nocode_work_draft WHERE resource_json->>'applicationId'=?",
                    id.toString());
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant_log WHERE application_id=?",
                    id);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant WHERE application_id=?",
                    id);
            jdbc.update("DELETE FROM public.nocode_application_access WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application_version WHERE application_id=?", id);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", id);
        }
        fixture.clean();
    }

    @Test
    void recordHistoryRegistersAttachmentUnionOfBothSnapshots() {
        publishFixture();
        String app = publishApplication("full", "保留引用验证", allActions());
        stubProtectedUpload();
        stubDirectoryAndBinding();
        long mainFile =
                upload.upload(
                                query(app, null, null, filesId, "sess-main"),
                                ACTOR,
                                content("合同.pdf"))
                        .fileId();
        long detailFile =
                upload.upload(
                                query(app, null, detailId, docId, "sess-detail"),
                                ACTOR,
                                content("盖章页.pdf"))
                        .fileId();
        stubFileService();

        // 主表与明细同时保存：两个受保护文件都登记到本次变更的操作编号
        ApplicationRecords.Aggregate created =
                runtime.save(
                        new ApplicationRecords.Save(
                                app,
                                objectId,
                                null,
                                null,
                                Map.of(titleId, "采购合同A", filesId, List.of(Long.toString(mainFile))),
                                Map.of(
                                        detailId,
                                        List.of(
                                                new ApplicationRecords.Row(
                                                        null,
                                                        null,
                                                        Map.of(
                                                                labelId,
                                                                "第一行",
                                                                docId,
                                                                List.of(Long.toString(detailFile)),
                                                                qtyId,
                                                                740123),
                                                        null,
                                                        Map.of(),
                                                        "row-1"))),
                                Map.of(),
                                null,
                                null,
                                UUID.randomUUID().toString(),
                                null),
                        ACTOR);
        String recordId = created.record().id();
        String createOperation =
                jdbc.queryForObject(
                        "SELECT operation_id::text FROM public.nocode_record_history WHERE"
                                + " object_id=? AND record_id=? AND operation='CREATE'",
                        String.class,
                        Long.valueOf(objectId),
                        recordId);
        assertThat(retentionRow(mainFile))
                .containsEntry("holder_type", "RECORD_HISTORY")
                .containsEntry("holder_id", createOperation)
                .containsEntry("record_id", recordId);
        assertThat(retentionRow(detailFile)).containsEntry("holder_id", createOperation);
        // 明细中的数量字段是普通数值，不得被误判为文件编号
        assertThat(retentionCount(740123L)).isZero();
        // 清理复核：两个文件都被历史修订引用
        assertThat(retention.retainedFileIds(List.of(mainFile, detailFile, 999999L)))
                .containsExactlyInAnyOrder(mainFile, detailFile);

        // 移除主表文件：变更前快照仍引用该文件，登记必须取前后并集
        ApplicationRecords.Aggregate updated =
                runtime.save(
                        new ApplicationRecords.Save(
                                app,
                                objectId,
                                recordId,
                                created.record().revision(),
                                Map.of(filesId, List.of()),
                                Map.of(),
                                Map.of(),
                                null,
                                null,
                                UUID.randomUUID().toString(),
                                null),
                        ACTOR);
        String updateOperation =
                jdbc.queryForObject(
                        "SELECT operation_id::text FROM public.nocode_record_history WHERE"
                                + " object_id=? AND record_id=? AND operation='UPDATE'",
                        String.class,
                        Long.valueOf(objectId),
                        updated.record().id());
        assertThat(
                        jdbc.queryForList(
                                "SELECT holder_id FROM public.nocode_biz_file_retention WHERE"
                                        + " file_id=? ORDER BY id",
                                String.class,
                                mainFile))
                .containsExactly(createOperation, updateOperation);
    }

    @Test
    void entryDraftReplacesRetentionAndRenewsOnlyLiveUploadSessions() {
        publishFixture();
        String app = publishApplicationWithForm("draft", "草稿保留验证");
        stubProtectedUpload();
        long kept =
                upload.upload(query(app, null, null, filesId, "sess-kept"), ACTOR, content("a.pdf"))
                        .fileId();
        long dropped =
                upload.upload(query(app, null, null, filesId, "sess-drop"), ACTOR, content("b.pdf"))
                        .fileId();
        stubFileService();
        // 会话先压缩到 5 分钟，续留效果才可观测；上传方为草稿作者本人
        shortenSessions(kept, dropped);

        // 首次暂存：两个文件都登记到草稿编号，本人仍有效的会话顺延
        WorkDrafts.Draft first =
                saveDraft(
                        app,
                        null,
                        null,
                        Map.of(
                                titleId,
                                "暂存合同",
                                filesId,
                                List.of(Long.toString(kept), Long.toString(dropped))));
        assertThat(retentionRow(kept))
                .containsEntry("holder_type", "WORK_DRAFT")
                .containsEntry("holder_id", first.id())
                .containsEntry("object_id", objectId);
        assertThat(retentionRow(dropped)).containsEntry("holder_id", first.id());
        assertThat(sessionExpiry(kept)).isAfter(java.time.LocalDateTime.now().plusHours(23));
        assertThat(sessionExpiry(dropped)).isAfter(java.time.LocalDateTime.now().plusHours(23));
        assertThat(retention.retainedFileIds(List.of(kept, dropped, 999999L)))
                .containsExactlyInAnyOrder(kept, dropped);

        // 再次暂存移除一个文件：旧登记物理释放，被移除文件不再阻止清理
        WorkDrafts.Draft second =
                saveDraft(
                        app,
                        first.id(),
                        first.revision(),
                        Map.of(titleId, "暂存合同", filesId, List.of(Long.toString(kept))));
        assertThat(retentionCount(kept)).isEqualTo(1);
        assertThat(retentionCount(dropped)).isZero();
        assertThat(retention.retainedFileIds(List.of(kept, dropped))).containsExactly(kept);
        assertThat(second.revision()).isGreaterThan(first.revision());

        // 已过期的会话不复活；草稿仍存在时保留引用继续阻止清理
        jdbc.update(
                "UPDATE public.nocode_biz_upload_session SET expires_at = now() - interval '1"
                        + " minute' WHERE file_id=?",
                kept);
        WorkDrafts.Draft third =
                saveDraft(
                        app,
                        second.id(),
                        second.revision(),
                        Map.of(titleId, "暂存合同", filesId, List.of(Long.toString(kept))));
        assertThat(sessionExpiry(kept)).isBefore(java.time.LocalDateTime.now());
        assertThat(retention.retainedFileIds(List.of(kept))).containsExactly(kept);
        assertThat(third.revision()).isGreaterThan(second.revision());

        // 草稿离开 DRAFT 后不再保留内容；整体释放只作用于该草稿
        jdbc.update("UPDATE public.nocode_work_draft SET state='SUBMITTED' WHERE id=?", third.id());
        assertThat(retention.retainedFileIds(List.of(kept))).isEmpty();
        retention.release("WORK_DRAFT", third.id());
        assertThat(retentionCount(kept)).isZero();
    }

    /** 发布带业务文件规则的对象：两层分组、记录标签字段、主表与明细各一个附件字段与一个普通数值字段 */
    private void publishFixture() {
        SaveObjectDraft request =
                new SaveObjectDraft(
                        null,
                        null,
                        fixture.prefix + "retention",
                        "业务文件保留验证",
                        null,
                        "biz_" + fixture.prefix + "retention",
                        "new-title",
                        List.of(
                                field("new-title", "name", "合同名称", "TEXT", 0),
                                field("dept", "dept", "归属部门", "TEXT", 1),
                                field("signed", "signed", "签订日期", "DATE", 2),
                                field("files", "files", "合同附件", "ATTACHMENT", 3)),
                        List.of());
        DataCenter.Detail items =
                new DataCenter.Detail(
                        null,
                        "items",
                        "明细区",
                        "biz_" + fixture.prefix + "items",
                        "ACTIVE",
                        List.of(
                                field("label", "label", "行说明", "TEXT", 0),
                                field("doc", "doc", "行附件", "ATTACHMENT", 1),
                                field("qty", "qty", "数量", "INTEGER", 2)),
                        Map.of(),
                        List.of());
        DataCenter.BusinessFilePolicy policy =
                new DataCenter.BusinessFilePolicy(
                        "保留验证空间",
                        List.of("合同"),
                        List.of(
                                new DataCenter.BusinessFileGroup("dept", null),
                                new DataCenter.BusinessFileGroup("signed", "YEAR")),
                        List.of("new-title"),
                        List.of("files", "doc"));
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                request,
                                new DataCenter.Settings(null, null, null, null, null, policy),
                                null,
                                List.of(),
                                List.of(),
                                List.of(items)),
                        ACTOR);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        ACTOR);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "业务文件保留验证"), ACTOR)
                                .state())
                .isEqualTo("SUCCEEDED");

        objectId = design.draft().id();
        DataObjectApi.PublishedObject published = objects.getVersion(objectId, null);
        DataCenter.Definition definition = published.definition();
        detailId = definition.details().getFirst().id();
        DataCenter.BusinessFilePolicy saved = definition.settings().businessFilePolicy();
        Set<String> mainFields =
                definition.fields().stream().map(FieldDefinition::id).collect(Collectors.toSet());
        Set<String> detailFields =
                definition.details().getFirst().fields().stream()
                        .map(FieldDefinition::id)
                        .collect(Collectors.toSet());
        titleId = saved.recordLabelFields().getFirst();
        filesId = saved.fieldIds().stream().filter(mainFields::contains).findFirst().orElseThrow();
        docId = saved.fieldIds().stream().filter(detailFields::contains).findFirst().orElseThrow();
        labelId = fieldIdByCode(definition, detailId, "label");
        qtyId = fieldIdByCode(definition, detailId, "qty");
    }

    /** 发布应用入口；共享授权按全部主表字段与全部明细放宽 */
    private String publishApplication(String suffix, String name, Set<String> actions) {
        String app = createApplication(suffix, name, null);
        var version = objects.getVersion(objectId, null);
        Set<String> allMainFields =
                version.definition().fields().stream()
                        .map(FieldDefinition::id)
                        .collect(Collectors.toSet());
        int revision =
                sharing.forApplication(app).stream()
                        .filter(g -> g.objectId().equals(objectId))
                        .findFirst()
                        .orElseThrow()
                        .revision();
        sharing.save(
                new ObjectSharing.Save(
                        objectId,
                        app,
                        revision,
                        new ApplicationAuthorization.ObjectGrant(
                                objectId,
                                actions,
                                "ALL",
                                allMainFields,
                                allMainFields,
                                Set.of(detailId),
                                Set.of(detailId),
                                Set.of(),
                                Set.of()),
                        "业务文件保留授权夹具"),
                ACTOR);
        apps.publish(new ApplicationCenter.Revision(app, 0, name), ACTOR);
        return app;
    }

    /** 发布带表单资源的应用入口：草稿与正式保存都必须绑定已发布表单 */
    private String publishApplicationWithForm(String suffix, String name) {
        DataCenter.Definition definition = objects.getVersion(objectId, null).definition();
        ApplicationUi.Form form =
                new ApplicationUi.Form(
                        objectId,
                        List.of(
                                new ApplicationUi.Node(
                                        "files_node",
                                        "FIELD",
                                        filesId,
                                        null,
                                        null,
                                        null,
                                        List.of()),
                                new ApplicationUi.Node(
                                        "title_node",
                                        "FIELD",
                                        titleId,
                                        null,
                                        null,
                                        null,
                                        List.of())),
                        List.of(detailId));
        String app = createApplication(suffix, name, form);
        grantApplicationObjects(app);
        apps.publish(new ApplicationCenter.Revision(app, 0, name), ACTOR);
        return app;
    }

    private String createApplication(String suffix, String name, ApplicationUi.Form form) {
        var version = objects.getVersion(objectId, null);
        List<ApplicationCenter.Resource> resources =
                form == null
                        ? List.of()
                        : List.of(
                                new ApplicationCenter.Resource(
                                        "form",
                                        "FORM",
                                        "form",
                                        "form",
                                        mapper.convertValue(
                                                form,
                                                new com.fasterxml.jackson.core.type.TypeReference<
                                                        Map<String, Object>>() {})));
        var saved =
                apps.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + suffix,
                                name,
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        version.objectId(),
                                                        version.versionNo(),
                                                        version.checksum())),
                                        resources)),
                        ACTOR);
        return saved.application().id();
    }

    /** 任务入口草稿：来源由服务端受控入口确定，本用例直接给定入口编号 */
    private WorkDrafts.Draft saveDraft(
            String app, String id, Integer expectedRevision, Map<String, Object> values) {
        var published = apps.published(app);
        return workForms.saveDraft(
                new WorkDrafts.Save(
                        id,
                        expectedRevision,
                        new PublishedResourceRef(
                                app, published.versionNo(), published.checksum(), "form", "FORM"),
                        objectId,
                        null,
                        null,
                        values,
                        Map.of()),
                ACTOR,
                new WorkSourceRef(WorkSourceEnum.TASK_ENTRY, fixture.prefix + "entry"));
    }

    private void stubProtectedUpload() {
        when(drive.uploadProtectedContent(anyString(), any(), anyLong(), any()))
                .thenAnswer(invocation -> fileSeq.incrementAndGet());
    }

    private void stubDirectoryAndBinding() {
        when(drive.ensureBusinessSpace(anyString(), anyLong())).thenReturn(SPACE_ID);
        when(drive.ensureDirectory(anyLong(), any(), anyLong())).thenReturn(RECORD_ENTRY_ID);
        when(drive.bindFile(anyLong(), anyLong(), anyLong(), any(), anyLong()))
                .thenAnswer(
                        invocation ->
                                DriveBizEntryDTO.builder()
                                        .id(FILE_ENTRY_ID)
                                        .parentId(RECORD_ENTRY_ID)
                                        .spaceId(SPACE_ID)
                                        .name("合同.pdf")
                                        .folder(false)
                                        .fileId(invocation.getArgument(2))
                                        .size(1024L)
                                        .mimeType("application/pdf")
                                        .build());
    }

    /** 记录写入阶段会经文件底座校验附件引用；文件底座为边界替身，按请求编号返回存在的文件记录 */
    private void stubFileService() {
        var fileService = servicesContext.getBean(FileService.class);
        when(fileService.getFiles(anyList()))
                .thenAnswer(
                        invocation -> {
                            List<?> requested = invocation.getArgument(0);
                            List<FileDO> files = new ArrayList<>();
                            for (Object fileId : requested) {
                                var file = new FileDO();
                                file.setId(((Number) fileId).longValue());
                                files.add(file);
                            }
                            return files;
                        });
    }

    private BusinessFiles.UploadQuery query(
            String app, String recordId, String detailId, String fieldId, String sessionKey) {
        return new BusinessFiles.UploadQuery(
                app,
                objectId,
                recordId,
                detailId,
                fieldId,
                sessionKey,
                null,
                "合同.pdf",
                "application/pdf",
                1024L);
    }

    private void shortenSessions(long... fileIds) {
        for (long fileId : fileIds) {
            jdbc.update(
                    "UPDATE public.nocode_biz_upload_session SET expires_at = now() + interval"
                            + " '5 minutes' WHERE file_id=?",
                    fileId);
        }
    }

    private java.time.LocalDateTime sessionExpiry(long fileId) {
        return jdbc.queryForObject(
                "SELECT expires_at FROM public.nocode_biz_upload_session WHERE file_id=?",
                java.time.LocalDateTime.class,
                fileId);
    }

    private Map<String, Object> retentionRow(long fileId) {
        return jdbc.queryForMap(
                "SELECT holder_type, holder_id, object_id, record_id FROM"
                        + " public.nocode_biz_file_retention WHERE file_id=?",
                fileId);
    }

    private int retentionCount(long fileId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.nocode_biz_file_retention WHERE file_id=?",
                Integer.class,
                fileId);
    }

    private String fieldIdByCode(DataCenter.Definition definition, String detailId, String code) {
        return definition.details().stream()
                .filter(d -> d.id().equals(detailId))
                .findFirst()
                .orElseThrow()
                .fields()
                .stream()
                .filter(f -> f.code().equals(code))
                .findFirst()
                .orElseThrow()
                .id();
    }

    private static Set<String> allActions() {
        return Arrays.stream(ApplicationActionEnum.values())
                .map(ApplicationActionEnum::getCode)
                .collect(Collectors.toSet());
    }

    private static ByteArrayInputStream content(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private FieldDefinition field(String key, String code, String name, String type, int sort) {
        return new FieldDefinition(
                key, null, code, name, type, null, null, null, false, false, sort);
    }
}
