package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.module.drive.api.bizfile.DriveBizFileApi;
import com.richuang.os.module.drive.api.bizfile.dto.DriveBizEntryDTO;
import com.richuang.os.module.drive.api.bizfile.dto.DriveBizTemporaryContent;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.sharing.ObjectSharingService;
import com.richuang.os.nocode.enums.ApplicationActionEnum;
import com.richuang.os.nocode.runtime.service.bizfile.BizFileBindingService;
import com.richuang.os.nocode.runtime.service.bizfile.BizFileUploadService;
import com.richuang.os.nocode.runtime.service.record.RecordService;

import org.junit.jupiter.api.*;
import org.springframework.security.access.AccessDeniedException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 在当前开发库验证临时上传与上传会话：应用入口按可新增/可修改能力与字段可写判定，数据维护入口要求对象管理权限，
 * 未接入字段一律拒绝；内容写入受保护底座后登记临时会话，会话归属按用户与对象字段绑定，临时内容仅上传者本人可读；
 * 续期只顺延仍然有效的会话，过期不复活；保存时按最终字段值执行数量上界并校验会话归属后建立网盘绑定。 业务文件绑定表不在通用清理范围，本测试按对象前缀自行清理。
 */
class BizFileUploadIntegrationTest {
    private static final long SPACE_ID = 910001L;
    private static final long RECORD_ENTRY_ID = 920001L;
    private static final long FILE_ENTRY_ID = 930011L;
    private static final long ACTOR = 10001L;

    private NocodeIntegrationSupport fixture;
    private BizFileUploadService upload;
    private BizFileBindingService binding;
    private RecordService runtime;
    private DataObjectApi objects;
    private ApplicationService apps;
    private ObjectSharingService sharing;
    private DriveBizFileApi drive;
    private final AtomicLong fileSeq = new AtomicLong(740000L);

    private String objectId;
    private String detailId;
    private String titleId;
    private String filesId;
    private String docId;

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
        binding = servicesContext.getBean(BizFileBindingService.class);
        runtime = servicesContext.getBean(RecordService.class);
        objects = servicesContext.getBean(DataObjectApi.class);
        apps = servicesContext.getBean(ApplicationService.class);
        sharing = servicesContext.getBean(ObjectSharingService.class);
        drive = servicesContext.getBean(DriveBizFileApi.class);
        // 网盘边界替身由多个用例共享：只清理调用记录，保留其他用例的桩
        org.mockito.Mockito.clearInvocations(drive);
        PermissionCommonApi permission = servicesContext.getBean(PermissionCommonApi.class);
        org.mockito.Mockito.when(permission.hasAnyPermissions(ACTOR, "nocode:object:query"))
                .thenReturn(true);
        org.mockito.Mockito.when(permission.hasAnyPermissions(ACTOR, "nocode:object:manage"))
                .thenReturn(true);
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
    void applicationUploadChecksEntryFieldAndRecordCapabilities() {
        publishFixture();
        String app = publishApplication("full", "上传授权验证", allActions(), null, null, null, null);
        stubProtectedUpload();

        // 新记录：入口可新增、主表附件字段可写即可上传，内容写入受保护底座
        BusinessFiles.Uploaded main =
                upload.upload(
                        query(app, null, null, filesId, "sess-main"), ACTOR, content("合同.pdf"));
        assertThat(main.fileId()).isNotNull();
        assertThat(main.name()).isEqualTo("合同.pdf");
        assertThat(main.size()).isEqualTo(1024L);
        assertThat(main.mimeType()).isEqualTo("application/pdf");
        assertThat(sessionState(main.fileId())).isEqualTo("TEMPORARY");
        verify(drive).uploadProtectedContent(eq("合同.pdf"), eq("application/pdf"), eq(1024L), any());

        // 明细字段：明细区可写即可上传，位置身份随会话登记
        BusinessFiles.Uploaded detail =
                upload.upload(
                        query(app, null, detailId, docId, "sess-detail"),
                        ACTOR,
                        content("盖章页.pdf"));
        assertThat(detail.fileId()).isNotNull();
        assertThat(sessionField(detail.fileId())).isEqualTo(docId);
        // 文件展示名与类型取自上传入参；主表与明细两次上传都经受保护内容底座写入
        verify(drive, times(2))
                .uploadProtectedContent(eq("合同.pdf"), eq("application/pdf"), eq(1024L), any());

        // 未接入字段、明细区位置错误；不在授权明细区内的位置按权限拒绝，不区分是否存在
        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        query(app, null, null, titleId, "sess-main"),
                                        ACTOR,
                                        content("x.pdf")))
                .hasMessageContaining("该字段未接入业务文件，请使用普通附件上传");
        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        query(app, null, detailId, filesId, "sess-main"),
                                        ACTOR,
                                        content("x.pdf")))
                .hasMessageContaining("附件字段不属于所选明细区");
        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        query(app, null, "999999", docId, "sess-main"),
                                        ACTOR,
                                        content("x.pdf")))
                .hasMessageContaining("没有此明细区的填写权限");

        // 已有记录：改走该记录的修改能力；无 UPDATE 的入口拒绝
        String recordId = createRecord(app, "采购合同A");
        BusinessFiles.Uploaded existing =
                upload.upload(
                        query(app, recordId, null, filesId, "sess-record"),
                        ACTOR,
                        content("续签.pdf"));
        assertThat(existing.fileId()).isNotNull();
        String readOnly =
                publishApplication("ro", "只读入口", Set.of("READ", "CREATE"), null, null, null, null);
        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        query(readOnly, recordId, null, filesId, "sess-ro"),
                                        ACTOR,
                                        content("x.pdf")))
                .hasMessageContaining("没有此记录的UPDATE权限");
        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        query(app, "999999999", null, filesId, "sess-x"),
                                        ACTOR,
                                        content("x.pdf")))
                .hasMessageContaining("记录不存在或不可访问");

        // 入口准入：非成员不能上传
        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        query(app, null, null, filesId, "sess-x"),
                                        20002,
                                        content("x.pdf")))
                .hasMessageContaining("没有此应用的运行权限");
    }

    @Test
    void applicationUploadRequiresFieldAndDetailWriteGrants() {
        publishFixture();
        // 可新增且附件字段可读，但可写字段被收窄到标题：主表上传与明细上传都应拒绝
        String narrow =
                publishApplication(
                        "narrow",
                        "字段收窄入口",
                        Set.of("READ", "CREATE"),
                        Set.of(titleId, filesId),
                        Set.of(titleId),
                        Set.of(detailId),
                        Set.of());
        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        query(narrow, null, null, filesId, "sess-1"),
                                        ACTOR,
                                        content("a.pdf")))
                .hasMessageContaining("没有此附件字段的填写权限");
        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        query(narrow, null, detailId, docId, "sess-1"),
                                        ACTOR,
                                        content("a.pdf")))
                .hasMessageContaining("没有此明细区的填写权限");
    }

    @Test
    void maintenanceUploadRequiresObjectManagementAndParticipatingField() {
        publishFixture();
        stubProtectedUpload();

        // 数据维护入口：对象管理权限通过后按发布定义校验字段身份
        BusinessFiles.Uploaded uploaded =
                upload.upload(
                        new BusinessFiles.UploadQuery(
                                null,
                                objectId,
                                null,
                                null,
                                filesId,
                                "mnt-1",
                                null,
                                "归档.pdf",
                                "application/pdf",
                                2048L),
                        ACTOR,
                        content("归档.pdf"));
        assertThat(uploaded.fileId()).isNotNull();

        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        new BusinessFiles.UploadQuery(
                                                null,
                                                objectId,
                                                null,
                                                null,
                                                titleId,
                                                "mnt-2",
                                                null,
                                                "x.pdf",
                                                "application/pdf",
                                                10L),
                                        ACTOR,
                                        content("x")))
                .hasMessageContaining("该字段未接入业务文件，请使用普通附件上传");
        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        new BusinessFiles.UploadQuery(
                                                null,
                                                objectId,
                                                null,
                                                "999999",
                                                docId,
                                                "mnt-2",
                                                null,
                                                "x.pdf",
                                                "application/pdf",
                                                10L),
                                        ACTOR,
                                        content("x")))
                .hasMessageContaining("附件字段所属明细区不存在");

        // 没有对象管理权限的账号不能经数据维护入口上传
        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        new BusinessFiles.UploadQuery(
                                                null,
                                                objectId,
                                                null,
                                                null,
                                                filesId,
                                                "mnt-3",
                                                null,
                                                "x.pdf",
                                                "application/pdf",
                                                10L),
                                        10003,
                                        content("x")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("需要数据对象管理权限才能维护对象数据");
    }

    @Test
    void uploadRejectsOversizedAndMalformedRequests() {
        publishFixture();
        String app = publishApplication("full", "上传入参验证", allActions(), null, null, null, null);

        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        new BusinessFiles.UploadQuery(
                                                app,
                                                objectId,
                                                null,
                                                null,
                                                filesId,
                                                "sess-1",
                                                null,
                                                "big.bin",
                                                "application/octet-stream",
                                                50L * 1024 * 1024 + 1),
                                        ACTOR,
                                        content("x")))
                .hasMessageContaining("单个文件不能超过 50MB");
        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        new BusinessFiles.UploadQuery(
                                                app,
                                                objectId,
                                                null,
                                                null,
                                                filesId,
                                                "sess-1",
                                                null,
                                                "empty.bin",
                                                "application/octet-stream",
                                                0L),
                                        ACTOR,
                                        content("")))
                .hasMessageContaining("文件内容为空");
        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        new BusinessFiles.UploadQuery(
                                                app,
                                                objectId,
                                                null,
                                                null,
                                                filesId,
                                                "sess-1",
                                                null,
                                                "  ",
                                                "application/pdf",
                                                10L),
                                        ACTOR,
                                        content("x")))
                .hasMessageContaining("文件名称不能为空");
        assertThatThrownBy(
                        () ->
                                upload.upload(
                                        new BusinessFiles.UploadQuery(
                                                app,
                                                objectId,
                                                null,
                                                null,
                                                filesId,
                                                "sess key!",
                                                null,
                                                "x.pdf",
                                                "application/pdf",
                                                10L),
                                        ACTOR,
                                        content("x")))
                .hasMessageContaining("上传会话标识无效");
        // 入参不合法时不写内容底座
        verify(drive, never()).uploadProtectedContent(any(), any(), anyLong(), any());
    }

    @Test
    void temporaryContentAndRenewFollowSessionOwnershipAndExpiry() {
        publishFixture();
        String app = publishApplication("full", "上传会话验证", allActions(), null, null, null, null);
        stubProtectedUpload();
        long fileId =
                upload.upload(query(app, null, null, filesId, "sess-A"), ACTOR, content("合同.pdf"))
                        .fileId();
        org.mockito.Mockito.when(drive.temporaryContentInfo(fileId))
                .thenReturn(
                        new DriveBizTemporaryContent(
                                fileId, "合同.pdf", 1024L, "application/pdf", 1024L));

        // 会话本人在有效期内可读临时内容，元信息取自受保护文件记录
        BusinessFiles.TemporaryContent content =
                upload.temporaryContent(
                        new BusinessFiles.TemporaryQuery(objectId, filesId, "sess-A", fileId),
                        ACTOR);
        assertThat(content.fileId()).isEqualTo(fileId);
        assertThat(content.name()).isEqualTo("合同.pdf");
        assertThat(content.size()).isEqualTo(1024L);
        assertThat(content.length()).isEqualTo(1024L);

        // 会话键不符、非本人、位置不符：均按不可读处理，不区分原因
        assertThatThrownBy(
                        () ->
                                upload.temporaryContent(
                                        new BusinessFiles.TemporaryQuery(
                                                objectId, filesId, "sess-B", fileId),
                                        ACTOR))
                .hasMessageContaining("临时文件不存在或上传会话已过期，请重新上传");
        assertThatThrownBy(
                        () ->
                                upload.temporaryContent(
                                        new BusinessFiles.TemporaryQuery(
                                                objectId, filesId, "sess-A", fileId),
                                        10002))
                .hasMessageContaining("临时文件不存在或上传会话已过期，请重新上传");
        assertThatThrownBy(
                        () ->
                                upload.temporaryContent(
                                        new BusinessFiles.TemporaryQuery(
                                                objectId, docId, "sess-A", fileId),
                                        ACTOR))
                .hasMessageContaining("临时文件不存在或上传会话已过期，请重新上传");
        assertThatThrownBy(
                        () ->
                                upload.temporaryContent(
                                        new BusinessFiles.TemporaryQuery(
                                                "999999", filesId, "sess-A", fileId),
                                        ACTOR))
                .hasMessageContaining("临时文件不存在或上传会话已过期，请重新上传");

        // 续期只作用于本人该会话键下的有效会话
        assertThat(upload.renew("sess-A", ACTOR)).isTrue();
        assertThat(upload.renew("sess-A", 10002)).isFalse();
        assertThat(upload.renew("sess-B", ACTOR)).isFalse();
        assertThatThrownBy(() -> upload.renew("bad key", ACTOR)).hasMessageContaining("上传会话标识无效");

        // 过期会话不复活：续期返回 false，临时内容不可读
        jdbc.update(
                "UPDATE public.nocode_biz_upload_session SET expires_at = now() - interval '1"
                        + " minute' WHERE file_id=?",
                fileId);
        assertThat(upload.renew("sess-A", ACTOR)).isFalse();
        assertThatThrownBy(
                        () ->
                                upload.temporaryContent(
                                        new BusinessFiles.TemporaryQuery(
                                                objectId, filesId, "sess-A", fileId),
                                        ACTOR))
                .hasMessageContaining("临时文件不存在或上传会话已过期，请重新上传");
    }

    @Test
    void savingRecordBindsUploadedFileAndMarksSessionBound() {
        publishFixture();
        String app = publishApplication("full", "上传绑定验证", allActions(), null, null, null, null);
        stubProtectedUpload();
        stubDirectoryAndBinding();
        long fileId =
                upload.upload(
                                query(app, null, null, filesId, "sess-save"),
                                ACTOR,
                                content("合同.pdf"))
                        .fileId();
        // 记录写入阶段会经文件底座校验附件引用；文件底座为边界替身，按上传编号返回真实存在的文件记录
        var fileService =
                servicesContext.getBean(
                        com.richuang.os.module.infra.service.file.FileService.class);
        var file = new com.richuang.os.module.infra.dal.dataobject.file.FileDO();
        file.setId(fileId);
        when(fileService.getFiles(List.of(fileId))).thenReturn(List.of(file));

        var saved =
                runtime.save(
                        new ApplicationRecords.Save(
                                app,
                                objectId,
                                null,
                                null,
                                Map.of(titleId, "采购合同A", filesId, List.of(Long.toString(fileId))),
                                null),
                        ACTOR);

        // 保存成功：会话置为已绑定，附件绑定表写入受管节点与内容展示冗余
        assertThat(saved.record().id()).isNotBlank();
        assertThat(sessionState(fileId)).isEqualTo("BOUND");
        Map<String, Object> bound =
                jdbc.queryForMap(
                        "SELECT entry_id, state, file_name, file_size FROM"
                                + " public.nocode_biz_attachment_binding WHERE object_id=? AND"
                                + " file_id=?",
                        objectId,
                        fileId);
        assertThat(bound.get("entry_id")).isEqualTo(FILE_ENTRY_ID);
        assertThat(bound.get("state")).isEqualTo("ACTIVE");
        assertThat(bound.get("file_name")).isEqualTo("合同.pdf");
        assertThat(((Number) bound.get("file_size")).longValue()).isEqualTo(1024L);
        verify(drive).bindFile(eq(SPACE_ID), eq(RECORD_ENTRY_ID), eq(fileId), eq(app), eq(ACTOR));

        // 保存后临时内容不再可读，改经业务内容端点按完整授权链读取
        assertThatThrownBy(
                        () ->
                                upload.temporaryContent(
                                        new BusinessFiles.TemporaryQuery(
                                                objectId, filesId, "sess-save", fileId),
                                        ACTOR))
                .hasMessageContaining("临时文件不存在或上传会话已过期，请重新上传");
    }

    @Test
    void savingMoreThanHundredFilesPerFieldIsRejected() {
        publishFixture();
        String app = publishApplication("full", "数量上界验证", allActions(), null, null, null, null);
        stubDirectoryAndBinding();

        List<String> over = new ArrayList<>();
        for (int index = 1; index <= 101; index++) {
            over.add(Long.toString(700000L + index));
        }
        assertThatThrownBy(
                        () ->
                                binding.bindOnSave(
                                        app,
                                        objectId,
                                        "1",
                                        Map.of(),
                                        Map.of(filesId, over),
                                        List.of(),
                                        ACTOR))
                .hasMessageContaining("单个附件字段最多 100 个文件");

        // 恰好 100 个不触发数量上界：由会话归属校验拒绝未经会话登记的文件
        assertThatThrownBy(
                        () ->
                                binding.bindOnSave(
                                        app,
                                        objectId,
                                        "1b",
                                        Map.of(),
                                        Map.of(filesId, over.subList(0, 100)),
                                        List.of(),
                                        ACTOR))
                .hasMessageContaining("文件未经当前表单上传会话登记或已过期，请重新上传");

        // 真实保存入口在记录写入阶段同样有上界，两处校验对用户表现一致
        assertThatThrownBy(
                        () ->
                                runtime.save(
                                        new ApplicationRecords.Save(
                                                app,
                                                objectId,
                                                null,
                                                null,
                                                Map.of(filesId, over),
                                                null),
                                        ACTOR))
                .hasMessageContaining("多值字段格式无效");
    }

    /** 发布带业务文件规则的对象：两层分组、记录标签字段、主表与明细各一个附件字段 */
    private void publishFixture() {
        SaveObjectDraft request =
                new SaveObjectDraft(
                        null,
                        null,
                        fixture.prefix + "upload",
                        "业务文件上传验证",
                        null,
                        "biz_" + fixture.prefix + "upload",
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
                                field("doc", "doc", "行附件", "ATTACHMENT", 1)),
                        Map.of(),
                        List.of());
        DataCenter.BusinessFilePolicy policy =
                new DataCenter.BusinessFilePolicy(
                        "上传验证空间",
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
                                .execute(new DataCenter.ExecutePlan(plan.id(), "业务文件上传验证"), ACTOR)
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
        assertThat(saved.fieldIds()).hasSize(2);
        filesId = saved.fieldIds().stream().filter(mainFields::contains).findFirst().orElseThrow();
        docId = saved.fieldIds().stream().filter(detailFields::contains).findFirst().orElseThrow();
    }

    /** 发布应用入口并按给定动作与字段/明细授权写共享授权；null 表示按全部主表字段与全部明细放宽 */
    private String publishApplication(
            String suffix,
            String name,
            Set<String> actions,
            Set<String> readFields,
            Set<String> writeFields,
            Set<String> readDetails,
            Set<String> writeDetails) {
        DataObjectApi.PublishedObject version = objects.getVersion(objectId, null);
        Set<String> allMainFields =
                version.definition().fields().stream()
                        .map(FieldDefinition::id)
                        .collect(Collectors.toSet());
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
                                        List.of())),
                        ACTOR);
        String app = saved.application().id();
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
                                readFields == null ? allMainFields : readFields,
                                writeFields == null ? allMainFields : writeFields,
                                readDetails == null ? Set.of(detailId) : readDetails,
                                writeDetails == null ? Set.of(detailId) : writeDetails,
                                Set.of(),
                                Set.of()),
                        "业务文件上传授权夹具"),
                ACTOR);
        apps.publish(new ApplicationCenter.Revision(app, 0, name), ACTOR);
        return app;
    }

    /** 已有记录：经真实写入链路建立，附带建立记录目录（网盘侧为边界替身） */
    private String createRecord(String app, String name) {
        stubDirectoryAndBinding();
        return runtime.save(
                        new ApplicationRecords.Save(
                                app, objectId, null, null, Map.of(titleId, name), null),
                        ACTOR)
                .record()
                .id();
    }

    private void stubProtectedUpload() {
        when(drive.uploadProtectedContent(anyString(), any(), anyLong(), any()))
                .thenAnswer(invocation -> fileSeq.incrementAndGet());
    }

    private void stubDirectoryAndBinding() {
        when(drive.ensureBusinessSpace(anyString(), anyLong())).thenReturn(SPACE_ID);
        when(drive.ensureBusinessSpace(any(), anyString(), anyLong())).thenReturn(SPACE_ID);
        when(drive.ensureDirectory(anyLong(), any(), anyLong())).thenReturn(RECORD_ENTRY_ID);
        when(drive.createManagedDirectory(anyLong(), anyLong(), anyString(), anyLong()))
                .thenReturn(RECORD_ENTRY_ID);
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

    private static Set<String> allActions() {
        return Arrays.stream(ApplicationActionEnum.values())
                .map(ApplicationActionEnum::getCode)
                .collect(Collectors.toSet());
    }

    private String sessionState(Long fileId) {
        return jdbc.queryForObject(
                "SELECT state FROM public.nocode_biz_upload_session WHERE file_id=?",
                String.class,
                fileId);
    }

    private String sessionField(Long fileId) {
        return jdbc.queryForObject(
                "SELECT field_id FROM public.nocode_biz_upload_session WHERE file_id=?",
                String.class,
                fileId);
    }

    private static ByteArrayInputStream content(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private FieldDefinition field(String key, String code, String name, String type, int sort) {
        return new FieldDefinition(
                key, null, code, name, type, null, null, null, false, false, sort);
    }
}
