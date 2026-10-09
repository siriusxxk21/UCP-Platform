package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.work.PublishedResourceRef;
import com.lingan.ucp.nocode.application.service.published.ApplicationPublishedService;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileBrowseService;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileUploadService;
import com.lingan.ucp.nocode.runtime.service.bizfile.TaskBusinessFileSessions;
import com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeScope;
import com.lingan.ucp.nocode.runtime.service.task.TaskGroupRuntime;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskBusinessFileServiceImpl;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskWorkEntryService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** 任务附件不能回退普通应用入口；任务、固定表单、记录、字段和临时会话逐层核验。 */
class TaskBusinessFileServiceTest {
    private static final long ACTOR = 20002L;
    private final TaskWorkEntryService entries = mock(TaskWorkEntryService.class);
    private final TaskGroupRuntime runtime = mock(TaskGroupRuntime.class);
    private final ApplicationPublishedService published = mock(ApplicationPublishedService.class);
    private final BizFileBrowseService browse = mock(BizFileBrowseService.class);
    private final BizFileUploadService uploads = mock(BizFileUploadService.class);
    private final TaskBusinessFileServiceImpl service = new TaskBusinessFileServiceImpl();
    private final TaskWorkEntries.Form target =
            new TaskWorkEntries.Form("task", "item", "record", null);
    private final PublishedResourceRef resource =
            new PublishedResourceRef("app", 1, "hash", "form", "FORM");
    private final TaskWorkEntries.Config config =
            new TaskWorkEntries.Config(
                    "item",
                    "办理项",
                    new TaskCenter.Binding("app", "form", null),
                    TaskWorkEntries.DataMode.ROOT_SHARED,
                    null,
                    null,
                    null,
                    null,
                    false,
                    false);
    private TaskCenter.FormContext form;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "entries", entries);
        ReflectionTestUtils.setField(service, "runtime", runtime);
        ReflectionTestUtils.setField(service, "published", published);
        ReflectionTestUtils.setField(service, "browse", browse);
        ReflectionTestUtils.setField(service, "uploads", uploads);
        TaskEntryRuntimeScope scope = mock(TaskEntryRuntimeScope.class);
        when(scope.delegated()).thenReturn(true);
        ReflectionTestUtils.setField(service, "taskScope", scope);
        when(runtime.execute(anyString(), anyString(), anyLong(), anyBoolean(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(4)).get());
        when(published.withVersion(any(PublishedResourceRef.class), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(1)).get());
        ApplicationAuthorization.Capabilities caps =
                new ApplicationAuthorization.Capabilities(
                        Set.of("READ", "CREATE", "UPDATE"),
                        Set.of("file", "hidden"),
                        Set.of("file", "hidden"),
                        Set.of(),
                        Set.of());
        DataCenter.Definition object =
                RuleFixtures.object(
                        "object",
                        "附件对象",
                        List.of(
                                RuleFixtures.field("file", "file", "文件", "ATTACHMENT"),
                                RuleFixtures.field("hidden", "hidden", "不在表单", "ATTACHMENT")),
                        Map.of(),
                        List.of(),
                        List.of());
        ApplicationRecords.Model model =
                new ApplicationRecords.Model(
                        object, true, true, "id", "TEXT", Map.of(), caps, List.of());
        ApplicationRecords.Row row =
                new ApplicationRecords.Row("record", "revision", Map.of(), caps, Map.of());
        ApplicationUi.Node node =
                new ApplicationUi.Node(
                        "file-node", "FIELD", "file", null, null, 24, List.of(), null, null);
        form =
                new TaskCenter.FormContext(
                        new TaskCenter.BusinessRef(
                                resource,
                                new ApplicationCenter.ObjectReference("object", 1, "hash"),
                                "record",
                                null),
                        model,
                        new ApplicationUi.Form("object", List.of(node), List.of()),
                        new ApplicationRecords.Aggregate(row, Map.of(), List.of()),
                        List.of("file"),
                        null);
        when(entries.form(any(), eq(ACTOR))).thenReturn(form);
        when(entries.entries(anyString(), eq(ACTOR)))
                .thenReturn(
                        List.of(
                                new TaskWorkEntries.Entry(
                                        config, form.binding(), "dataset", false, true, 0, false)));
    }

    @Test
    void visibleFieldFilesUseTrustedTaskIdentityAndPinnedVersionWithoutApplicationMembership() {
        BusinessFiles.FileQuery query = files("app", "object", "record", "file");
        PageResult<BusinessFiles.File> result = new PageResult<>(List.of(), 0L);
        when(browse.files(query, ACTOR)).thenReturn(result);
        assertThat(service.files(new TaskBusinessFiles.Files(target, query), ACTOR))
                .isSameAs(result);
        verify(runtime).execute(eq("task"), eq("item"), eq(ACTOR), eq(false), any());
        verify(entries).form(target, ACTOR);
        verify(published).withVersion(eq(resource), any());
        verify(browse).files(query, ACTOR);
    }

    @Test
    void arbitraryApplicationObjectRecordAndRecordFolderCannotBorrowTaskGrant() {
        for (BusinessFiles.FileQuery query :
                List.of(
                        files("another", "object", "record", "file"),
                        files("app", "another", "record", "file"),
                        files("app", "object", "other-record", "file"),
                        files("app", "object", null, "file"),
                        files("app", "object", "record", null))) {
            assertThatThrownBy(
                            () -> service.files(new TaskBusinessFiles.Files(target, query), ACTOR))
                    .isInstanceOf(com.lingan.ucp.framework.common.exception.ServiceException.class);
        }
        verifyNoInteractions(browse);
    }

    @Test
    void objectReadableButNotPresentedAttachmentAndUnapprovedDetailAreDenied() {
        assertThatThrownBy(
                        () ->
                                service.files(
                                        new TaskBusinessFiles.Files(
                                                target, files("app", "object", "record", "hidden")),
                                        ACTOR))
                .hasMessageContaining("未在当前任务表单开放");
        BusinessFiles.ContentQuery query =
                new BusinessFiles.ContentQuery(
                        "app", "object", "record", "secret-detail", "row", "file", 99L);
        assertThatThrownBy(
                        () ->
                                service.content(
                                        new TaskBusinessFiles.Content(target, query, false), ACTOR))
                .isInstanceOf(com.lingan.ucp.framework.common.exception.ServiceException.class);
        verifyNoInteractions(browse);
    }

    @Test
    void revokedTaskIdentityStopsBeforeReadingAnyFile() {
        doThrow(com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid("任务已转交"))
                .when(runtime)
                .execute(anyString(), anyString(), anyLong(), anyBoolean(), any());
        assertThatThrownBy(
                        () ->
                                service.files(
                                        new TaskBusinessFiles.Files(
                                                target, files("app", "object", "record", "file")),
                                        ACTOR))
                .hasMessageContaining("任务已转交");
        verifyNoInteractions(entries, browse, uploads);
    }

    @Test
    void uploadTemporaryPreviewAndRenewUseTheSameTaskIsolatedSession() {
        service.upload(
                new TaskBusinessFiles.Upload(target, upload()),
                ACTOR,
                new ByteArrayInputStream(new byte[] {1}));
        ArgumentCaptor<BusinessFiles.UploadQuery> uploaded =
                ArgumentCaptor.forClass(BusinessFiles.UploadQuery.class);
        verify(uploads).upload(uploaded.capture(), eq(ACTOR), any());
        String scoped = uploaded.getValue().sessionKey();
        assertThat(scoped).hasSize(64).isNotEqualTo("session");
        service.temporaryContent(
                new TaskBusinessFiles.Temporary(
                        target,
                        new TaskBusinessFiles.TemporaryQuery(
                                "object", "file", "session", 77L, null),
                        true),
                ACTOR);
        verify(uploads)
                .temporaryContent(
                        new BusinessFiles.TemporaryQuery("object", "file", scoped, 77L), ACTOR);
        service.renew(new TaskBusinessFiles.Renew(target, "session"), ACTOR);
        verify(uploads).renew(scoped, ACTOR);
        verify(runtime, times(3)).execute(eq("task"), eq("item"), eq(ACTOR), eq(true), any());
        TaskWorkEntries.Form anotherTask =
                new TaskWorkEntries.Form("another-task", "item", "record", null);
        service.renew(new TaskBusinessFiles.Renew(anotherTask, "session"), ACTOR);
        ArgumentCaptor<String> sessions = ArgumentCaptor.forClass(String.class);
        verify(uploads, times(2)).renew(sessions.capture(), eq(ACTOR));
        assertThat(sessions.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void readOnlyOrCompletedTaskCannotUploadPreviewTemporaryOrRenew() {
        when(entries.entries(anyString(), eq(ACTOR)))
                .thenReturn(
                        List.of(
                                new TaskWorkEntries.Entry(
                                        config,
                                        form.binding(),
                                        "dataset",
                                        false,
                                        false,
                                        0,
                                        false)));
        assertThatThrownBy(
                        () ->
                                service.upload(
                                        new TaskBusinessFiles.Upload(target, upload()),
                                        ACTOR,
                                        new ByteArrayInputStream(new byte[] {1})))
                .hasMessageContaining("不可办理");
        assertThatThrownBy(
                        () ->
                                service.temporaryContent(
                                        new TaskBusinessFiles.Temporary(
                                                target,
                                                new TaskBusinessFiles.TemporaryQuery(
                                                        "object", "file", "session", 77L, null),
                                                true),
                                        ACTOR))
                .hasMessageContaining("不可办理");
        assertThatThrownBy(
                        () -> service.renew(new TaskBusinessFiles.Renew(target, "session"), ACTOR))
                .hasMessageContaining("不可办理");
        verifyNoInteractions(uploads);
    }

    @Test
    void recordSpecificFieldDenialIsNotRaisedToModelPermissions() {
        ApplicationAuthorization.Capabilities denied =
                new ApplicationAuthorization.Capabilities(
                        Set.of("READ"), Set.of(), Set.of(), Set.of(), Set.of());
        TaskCenter.FormContext restricted =
                new TaskCenter.FormContext(
                        form.binding(),
                        form.model(),
                        form.form(),
                        new ApplicationRecords.Aggregate(
                                new ApplicationRecords.Row(
                                        "record", "revision", Map.of(), denied, Map.of()),
                                Map.of(),
                                List.of()),
                        List.of(),
                        null);
        when(entries.form(any(), eq(ACTOR))).thenReturn(restricted);
        assertThatThrownBy(
                        () ->
                                service.files(
                                        new TaskBusinessFiles.Files(
                                                target, files("app", "object", "record", "file")),
                                        ACTOR))
                .hasMessageContaining("不允许访问");
        verifyNoInteractions(browse);
    }

    @Test
    void encodedSessionCanOnlyBeUsedByItsWritableTaskEntryAndUploadRecord() {
        TaskEntryRuntimeScope scope = mock(TaskEntryRuntimeScope.class);
        TaskEntryRuntimeScope.TaskData data =
                new TaskEntryRuntimeScope.TaskData("root", "task", "entry", 10001L, Map.of(), true);
        when(scope.current())
                .thenReturn(
                        new TaskEntryRuntimeScope.Invocation(
                                "app", "entry", 1, "任务", "object", ACTOR, Map.of(), data));
        String key = TaskBusinessFileSessions.key("task", "entry", "record", "client");
        assertThatCode(() -> TaskBusinessFileSessions.require(scope, key, "record"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> TaskBusinessFileSessions.require(scope, key, "other-record"))
                .hasMessageContaining("不属于当前任务办理项");
        for (String other :
                List.of(
                        TaskBusinessFileSessions.key("other-task", "entry", "record", "client"),
                        TaskBusinessFileSessions.key("task", "other-entry", "record", "client"),
                        "ordinary-editor")) {
            assertThatThrownBy(() -> TaskBusinessFileSessions.require(scope, other, "record"))
                    .hasMessageContaining("不属于当前任务办理项");
        }
        when(scope.current()).thenReturn(null);
        assertThatThrownBy(() -> TaskBusinessFileSessions.require(scope, key, "record"))
                .hasMessageContaining("不属于当前任务办理项");
        assertThatCode(() -> TaskBusinessFileSessions.require(scope, "ordinary-editor", "record"))
                .doesNotThrowAnyException();
    }

    private BusinessFiles.FileQuery files(String app, String object, String record, String field) {
        return new BusinessFiles.FileQuery(
                app, object, null, List.of(), record, null, null, field, null, 1, 20);
    }

    private BusinessFiles.UploadQuery upload() {
        return new BusinessFiles.UploadQuery(
                "app",
                "object",
                "record",
                null,
                "file",
                "session",
                "once",
                "proof.txt",
                "text/plain",
                1);
    }
}
