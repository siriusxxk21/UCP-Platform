package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.module.drive.api.bizfile.DriveBizFileApi;
import com.richuang.os.module.drive.api.bizfile.dto.*;
import com.richuang.os.module.infra.dal.dataobject.file.FileDO;
import com.richuang.os.module.infra.service.file.FileService;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.module.system.api.user.dto.AdminUserRespDTO;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.TaskCenter.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.runtime.service.bizfile.BizFileBrowseService;
import com.richuang.os.nocode.runtime.service.bizfile.BizFileUploadService;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.taskcenter.*;

import org.junit.jupiter.api.*;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** 真实开发库验证任务临时授权上传、保存绑定、附件列表与内容；仅外部文件存储使用既有边界替身。 */
class TaskBusinessFileIntegrationTest {
    private static final long MANAGER = 10001L;
    private static final long EMPLOYEE = 20002L;
    private static final long SPACE = 991001L;
    private static final long DIRECTORY = 991002L;
    private NocodeIntegrationSupport fixture;
    private TaskCenterService tasks;
    private TaskWorkEntryService entries;
    private TaskBusinessFileService files;
    private ApplicationService applications;
    private RecordService records;
    private DriveBizFileApi drive;
    private String app;
    private String object;
    private String title;
    private String attachment;
    private final List<String> roots = new ArrayList<>();
    private final Map<Long, byte[]> contents = new HashMap<>();
    private final AtomicLong sequence = new AtomicLong(799001L);

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void closeContext() {
        close();
    }

    @BeforeEach
    void setup() {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        tasks = servicesContext.getBean(TaskCenterService.class);
        entries = servicesContext.getBean(TaskWorkEntryService.class);
        files = servicesContext.getBean(TaskBusinessFileService.class);
        applications = servicesContext.getBean(ApplicationService.class);
        records = servicesContext.getBean(RecordService.class);
        drive = servicesContext.getBean(DriveBizFileApi.class);
        when(servicesContext
                        .getBean(PermissionCommonApi.class)
                        .hasAnyPermissions(MANAGER, "nocode:object:manage"))
                .thenReturn(true);
        when(servicesContext.getBean(AdminUserApi.class).getUser(anyLong()))
                .thenAnswer(
                        call -> {
                            AdminUserRespDTO user = new AdminUserRespDTO();
                            user.setId(call.getArgument(0));
                            user.setNickname("任务附件测试人员");
                            user.setStatus(0);
                            return user;
                        });
        publish();
        storageBoundary();
    }

    @AfterEach
    void cleanup() {
        for (String root : roots) {
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_record WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update(
                    "DELETE FROM public.nocode_task_entry_binding WHERE task_id IN (SELECT id FROM"
                            + " public.nocode_task_instance WHERE root_id=?)",
                    root);
            jdbc.update("DELETE FROM public.nocode_task_event WHERE root_id=?", root);
            jdbc.update("DELETE FROM public.nocode_task_instance WHERE root_id=?", root);
        }
        if (object != null) {
            jdbc.update("DELETE FROM public.nocode_biz_file_retention WHERE object_id=?", object);
            jdbc.update(
                    "DELETE FROM public.nocode_biz_attachment_binding WHERE object_id=?", object);
            jdbc.update(
                    "DELETE FROM public.nocode_biz_directory_binding WHERE object_id=?", object);
            jdbc.update("DELETE FROM public.nocode_biz_upload_session WHERE object_id=?", object);
        }
        if (app != null) {
            long appId = Long.parseLong(app);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant_log WHERE application_id=?",
                    appId);
            jdbc.update(
                    "DELETE FROM public.nocode_object_application_grant WHERE application_id=?",
                    appId);
            jdbc.update(
                    "DELETE FROM public.nocode_application_access WHERE application_id=?", appId);
            jdbc.update(
                    "DELETE FROM public.nocode_application_version WHERE application_id=?", appId);
            jdbc.update("DELETE FROM public.nocode_application WHERE id=?", appId);
        }
        fixture.clean();
    }

    @Test
    void nonApplicationMemberCanUploadSaveListAndReadOnlyInsideAssignedTask() throws Exception {
        String task = startTask(EMPLOYEE);
        String other = startTask(EMPLOYEE);
        TaskWorkEntries.Form target = target(task, "work", null);
        BusinessFiles.Uploaded uploaded = upload(target, EMPLOYEE);
        long fileId = uploaded.fileId();
        assertThat(files.renew(new TaskBusinessFiles.Renew(target, "editor-session"), EMPLOYEE))
                .isTrue();
        BusinessFiles.TemporaryContent temporary =
                files.temporaryContent(temporary(target, fileId), EMPLOYEE);
        try (InputStream stream = files.temporaryStream(temporary, 0)) {
            assertThat(stream.readAllBytes()).isEqualTo(bytes());
        }
        assertThatThrownBy(() -> save(other, "work", null, null, fileId, EMPLOYEE))
                .hasMessageContaining("不属于当前任务办理项");
        assertThatThrownBy(() -> save(task, "other", null, null, fileId, EMPLOYEE))
                .hasMessageContaining("不属于当前任务办理项");
        assertThatThrownBy(
                        () ->
                                files.temporaryContent(
                                        temporary(target(other, "work", null), fileId), EMPLOYEE))
                .hasMessageContaining("临时文件不存在");
        assertThat(sessionState(fileId)).isEqualTo("TEMPORARY");

        save(task, "work", null, null, fileId, EMPLOYEE);
        TaskWorkEntries.Item record =
                entries.page(
                                new TaskWorkEntries.Query(task, "work", false, false, 1, 20, ""),
                                EMPLOYEE)
                        .getList()
                        .getFirst();
        assertThat(sessionState(fileId)).isEqualTo("BOUND");
        TaskWorkEntries.Form savedTarget = target(task, "work", record.record().id());
        BusinessFiles.FileQuery query = fileQuery(record.record().id());
        List<BusinessFiles.File> listed =
                files.files(new TaskBusinessFiles.Files(savedTarget, query), EMPLOYEE).getList();
        assertThat(listed).extracting(BusinessFiles.File::fileId).containsExactly(fileId);
        BusinessFiles.Content content =
                files.content(
                        new TaskBusinessFiles.Content(
                                savedTarget,
                                new BusinessFiles.ContentQuery(
                                        app,
                                        object,
                                        record.record().id(),
                                        null,
                                        null,
                                        attachment,
                                        listed.getFirst().entryId()),
                                true),
                        EMPLOYEE);
        try (InputStream stream = files.contentStream(content, 0)) {
            assertThat(stream.readAllBytes()).isEqualTo(bytes());
        }
        assertThatThrownBy(
                        () ->
                                servicesContext
                                        .getBean(BizFileBrowseService.class)
                                        .files(query, EMPLOYEE))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () ->
                                servicesContext
                                        .getBean(BizFileUploadService.class)
                                        .upload(
                                                uploadQuery(null),
                                                EMPLOYEE,
                                                new ByteArrayInputStream(bytes())))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () -> files.files(new TaskBusinessFiles.Files(savedTarget, query), 30003L))
                .hasMessageContaining("仅当前任务");
        assertThatThrownBy(
                        () ->
                                files.files(
                                        new TaskBusinessFiles.Files(
                                                target(other, "work", record.record().id()), query),
                                        EMPLOYEE))
                .isInstanceOf(RuntimeException.class);
        tasks.transition(
                new Transition(
                        task,
                        tasks.detail(task, EMPLOYEE).task().revision(),
                        Action.COMPLETE,
                        null,
                        key()),
                EMPLOYEE);
        assertThatThrownBy(
                        () ->
                                files.upload(
                                        new TaskBusinessFiles.Upload(
                                                savedTarget, uploadQuery(record.record().id())),
                                        EMPLOYEE,
                                        new ByteArrayInputStream(bytes())))
                .hasMessageContaining("进行中任务");
        assertThatThrownBy(
                        () ->
                                files.renew(
                                        new TaskBusinessFiles.Renew(savedTarget, "editor-session"),
                                        EMPLOYEE))
                .hasMessageContaining("进行中任务");
    }

    @Test
    void taskUploadCannotEscapeToOrdinaryApplicationEvenForApplicationManager() {
        String task = startTask(MANAGER);
        TaskWorkEntries.Form target = target(task, "work", null);
        long fileId = upload(target, MANAGER).fileId();
        assertThatThrownBy(() -> records.save(input(null, null, fileId), MANAGER))
                .hasMessageContaining("不属于当前任务办理项");
        String encoded =
                jdbc.queryForObject(
                        "SELECT session_key FROM public.nocode_biz_upload_session WHERE file_id=?",
                        String.class,
                        fileId);
        assertThat(encoded).startsWith("task_").hasSize(64);
        assertThatThrownBy(
                        () ->
                                servicesContext
                                        .getBean(BizFileUploadService.class)
                                        .temporaryContent(
                                                new BusinessFiles.TemporaryQuery(
                                                        object, attachment, encoded, fileId),
                                                MANAGER))
                .hasMessageContaining("不属于当前任务办理项");
        assertThatThrownBy(
                        () ->
                                servicesContext
                                        .getBean(BizFileUploadService.class)
                                        .renew(encoded, MANAGER))
                .hasMessageContaining("必须从当前任务");
        save(task, "work", null, null, fileId, MANAGER);
        assertThat(sessionState(fileId)).isEqualTo("BOUND");
    }

    private void publish() {
        SaveObjectDraft request =
                new SaveObjectDraft(
                        null,
                        null,
                        fixture.prefix + "task_file",
                        "任务附件验证",
                        null,
                        "biz_" + fixture.prefix + "task_file",
                        "title",
                        List.of(
                                new FieldDefinition(
                                        "title", null, "name", "名称", "TEXT", null, null, null,
                                        false, false, 0),
                                new FieldDefinition(
                                        "file",
                                        null,
                                        "file",
                                        "凭证",
                                        "ATTACHMENT",
                                        null,
                                        null,
                                        null,
                                        false,
                                        false,
                                        1)),
                        List.of());
        DataCenter.BusinessFilePolicy policy =
                new DataCenter.BusinessFilePolicy(
                        "任务附件验证空间", List.of("任务"), List.of(), List.of("title"), List.of("file"));
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                request,
                                new DataCenter.Settings(null, null, null, null, null, policy),
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        MANAGER);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        MANAGER);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "任务附件验证"), MANAGER)
                                .state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi.PublishedObject version =
                servicesContext.getBean(DataObjectApi.class).getVersion(design.draft().id(), null);
        object = version.objectId();
        title =
                version.definition().fields().stream()
                        .filter(f -> "name".equals(f.code()))
                        .findFirst()
                        .orElseThrow()
                        .id();
        attachment =
                version.definition().fields().stream()
                        .filter(f -> "file".equals(f.code()))
                        .findFirst()
                        .orElseThrow()
                        .id();
        ApplicationCenter.Resource form =
                new ApplicationCenter.Resource(
                        "task-files-form",
                        "FORM",
                        "task_files_form",
                        "办理凭证",
                        Map.of(
                                "objectId",
                                object,
                                "detailIds",
                                List.of(),
                                "nodes",
                                List.of(
                                        Map.of(
                                                "id",
                                                "name-node",
                                                "type",
                                                "FIELD",
                                                "fieldId",
                                                title,
                                                "children",
                                                List.of()),
                                        Map.of(
                                                "id",
                                                "file-node",
                                                "type",
                                                "FIELD",
                                                "fieldId",
                                                attachment,
                                                "children",
                                                List.of()))));
        ApplicationCenter.Detail saved =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "task_file_app",
                                "任务附件验证",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        object,
                                                        version.versionNo(),
                                                        version.checksum())),
                                        List.of(form))),
                        MANAGER);
        app = saved.application().id();
        grantApplicationObjects(app);
        applications.publish(new ApplicationCenter.Revision(app, 0, "任务附件验证"), MANAGER);
    }

    private void storageBoundary() {
        when(drive.ensureBusinessSpace(anyString(), anyLong())).thenReturn(SPACE);
        when(drive.ensureBusinessSpace(any(), anyString(), anyLong())).thenReturn(SPACE);
        when(drive.ensureDirectory(anyLong(), any(), anyLong())).thenReturn(DIRECTORY);
        when(drive.createManagedDirectory(anyLong(), anyLong(), anyString(), anyLong()))
                .thenReturn(DIRECTORY);
        doAnswer(
                        call -> {
                            long file = sequence.incrementAndGet();
                            contents.put(file, ((InputStream) call.getArgument(3)).readAllBytes());
                            return file;
                        })
                .when(drive)
                .uploadProtectedContent(anyString(), any(), anyLong(), any());
        doAnswer(
                        call -> {
                            Long id = call.getArgument(0);
                            return new DriveBizTemporaryContent(
                                    id,
                                    "proof.txt",
                                    contents.get(id).length,
                                    "text/plain",
                                    (long) contents.get(id).length);
                        })
                .when(drive)
                .temporaryContentInfo(anyLong());
        doAnswer(call -> new ByteArrayInputStream(contents.get((Long) call.getArgument(0))))
                .when(drive)
                .openTemporaryContent(anyLong(), anyLong());
        doAnswer(
                        call -> {
                            Long file = call.getArgument(2);
                            return DriveBizEntryDTO.builder()
                                    .id(file + 100000L)
                                    .parentId(DIRECTORY)
                                    .spaceId(SPACE)
                                    .name("proof.txt")
                                    .folder(false)
                                    .fileId(file)
                                    .size((long) contents.get(file).length)
                                    .mimeType("text/plain")
                                    .build();
                        })
                .when(drive)
                .bindFile(anyLong(), anyLong(), anyLong(), any(), anyLong());
        doAnswer(
                        call -> {
                            Long entry = call.getArgument(0);
                            long file = entry - 100000L;
                            return new DriveBizFileContent(
                                    entry,
                                    SPACE,
                                    file,
                                    "proof.txt",
                                    contents.get(file).length,
                                    "text/plain",
                                    (long) contents.get(file).length);
                        })
                .when(drive)
                .contentInfo(anyLong());
        doAnswer(
                        call ->
                                new ByteArrayInputStream(
                                        contents.get((Long) call.getArgument(0) - 100000L)))
                .when(drive)
                .openContent(anyLong(), anyLong());
        doAnswer(
                        call -> {
                            List<Long> ids = call.getArgument(0);
                            return ids.stream()
                                    .filter(contents::containsKey)
                                    .map(
                                            id -> {
                                                FileDO file = new FileDO();
                                                file.setId(id);
                                                return file;
                                            })
                                    .toList();
                        })
                .when(servicesContext.getBean(FileService.class))
                .getFiles(anyList());
    }

    private String startTask(long actor) {
        TaskWorkEntries.Config work = config("work");
        TaskWorkEntries.Config other = config("other");
        NodeInput node =
                new NodeInput(
                        null,
                        null,
                        "附件授权回归",
                        null,
                        actor,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        List.of(work, other),
                        AssignmentMode.ASSIGNED,
                        List.of(),
                        new DataPolicy(1, DataAccessMode.GROUP, DataAccessMode.GROUP));
        Detail root =
                tasks.create(new Create(node, null, null, null, null, null, null, key()), MANAGER);
        String id = root.task().id();
        roots.add(id);
        tasks.transition(
                new Transition(
                        id, tasks.detail(id, actor).task().revision(), Action.START, null, key()),
                actor);
        return id;
    }

    private TaskWorkEntries.Config config(String key) {
        return new TaskWorkEntries.Config(
                key,
                key,
                new Binding(app, "task-files-form", null),
                TaskWorkEntries.DataMode.ROOT_SHARED,
                null,
                null,
                null,
                null,
                false,
                false);
    }

    private TaskWorkEntries.Form target(String task, String entry, String record) {
        return new TaskWorkEntries.Form(task, entry, record, null);
    }

    private BusinessFiles.Uploaded upload(TaskWorkEntries.Form target, long actor) {
        return files.upload(
                new TaskBusinessFiles.Upload(target, uploadQuery(target.recordId())),
                actor,
                new ByteArrayInputStream(bytes()));
    }

    private BusinessFiles.UploadQuery uploadQuery(String record) {
        return new BusinessFiles.UploadQuery(
                app,
                object,
                record,
                null,
                attachment,
                "editor-session",
                key(),
                "proof.txt",
                "text/plain",
                bytes().length);
    }

    private TaskBusinessFiles.Temporary temporary(TaskWorkEntries.Form target, long file) {
        return new TaskBusinessFiles.Temporary(
                target,
                new TaskBusinessFiles.TemporaryQuery(
                        object, attachment, "editor-session", file, null),
                true);
    }

    private BusinessFiles.FileQuery fileQuery(String record) {
        return new BusinessFiles.FileQuery(
                app, object, null, List.of(), record, null, null, attachment, null, 1, 20);
    }

    private ApplicationRecords.Save input(String record, String revision, long file) {
        return new ApplicationRecords.Save(
                app,
                object,
                record,
                revision,
                Map.of(title, "办理凭证", attachment, List.of(Long.toString(file))),
                Map.of(),
                Map.of(),
                null,
                "task-files-form",
                key(),
                null);
    }

    private void save(
            String task, String entry, String record, String revision, long file, long actor) {
        entries.save(
                new TaskWorkEntries.Save(task, entry, null, input(record, revision, file)), actor);
    }

    private String sessionState(long file) {
        return jdbc.queryForObject(
                "SELECT state FROM public.nocode_biz_upload_session WHERE file_id=?",
                String.class,
                file);
    }

    private byte[] bytes() {
        return "任务办理凭证".getBytes(StandardCharsets.UTF_8);
    }

    private String key() {
        return UUID.randomUUID().toString();
    }
}
