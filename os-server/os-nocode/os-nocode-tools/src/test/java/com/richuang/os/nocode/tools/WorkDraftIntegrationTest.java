package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.work.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.richuang.os.nocode.application.service.sharing.ObjectSharingService;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.work.WorkDraftQueryService;
import com.richuang.os.nocode.runtime.service.work.WorkFormService;

import org.junit.jupiter.api.*;

import java.util.*;
import java.util.concurrent.*;

/** 真实 PostgreSQL / Mapper / 事务验证；外部组织、文件和 BPM API 使用共享夹具替身。 */
class WorkDraftIntegrationTest {
    NocodeIntegrationSupport fixture;
    WorkFormService work;
    private WorkDraftQueryService workspace;
    RecordService records;
    ApplicationService applications;
    DataCenter.Definition object;
    PublishedResourceRef resource;
    String nameField;

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
        org.mockito.Mockito.when(
                        servicesContext
                                .getBean(
                                        com.richuang.os.framework.common.biz.system.permission
                                                .PermissionCommonApi.class)
                                .hasAnyPermissions(10001L, ObjectSharingService.MANAGE_PERMISSION))
                .thenReturn(true);
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        work = servicesContext.getBean(WorkFormService.class);
        workspace = servicesContext.getBean(WorkDraftQueryService.class);
        records = servicesContext.getBean(RecordService.class);
        applications = servicesContext.getBean(ApplicationService.class);
        var request = fixture.createRequest("work");
        var title = request.fields().getFirst();
        request =
                new SaveObjectDraft(
                        null,
                        null,
                        request.objectCode(),
                        request.objectName(),
                        request.description(),
                        request.tableName(),
                        request.titleFieldKey(),
                        List.of(
                                new FieldDefinition(
                                        title.key(),
                                        null,
                                        title.code(),
                                        title.name(),
                                        title.type(),
                                        title.length(),
                                        title.precision(),
                                        title.scale(),
                                        true,
                                        false,
                                        title.sort())),
                        List.of());
        var design =
                designs.save(
                        new DataCenter.SaveDesign(
                                request,
                                DataCenter.Settings.defaults(),
                                null,
                                List.of(),
                                List.of(),
                                List.of()),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "工作草稿回归"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        var version =
                servicesContext.getBean(DataObjectApi.class).getVersion(design.draft().id(), null);
        object = version.definition();
        nameField =
                object.fields().stream()
                        .filter(f -> "name".equals(f.code()))
                        .findFirst()
                        .orElseThrow()
                        .id();
        var form =
                new ApplicationCenter.Resource(
                        "work-form",
                        "FORM",
                        "work_form",
                        "工作表单",
                        Map.of(
                                "objectId",
                                object.objectId(),
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
                                                nameField,
                                                "children",
                                                List.of()))));
        var app =
                applications.save(
                        new ApplicationCenter.Save(
                                null,
                                null,
                                fixture.prefix + "app",
                                "工作草稿回归",
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        List.of(
                                                new ApplicationCenter.ObjectReference(
                                                        version.objectId(),
                                                        version.versionNo(),
                                                        version.checksum())),
                                        List.of(form))),
                        10001);
        grantApplicationObjects(app.application().id());
        applications.publish(
                new ApplicationCenter.Revision(app.application().id(), 0, "工作草稿回归"), 10001);
        var published = applications.published(app.application().id());
        resource =
                new PublishedResourceRef(
                        app.application().id(),
                        published.versionNo(),
                        published.checksum(),
                        form.id(),
                        form.kind());
    }

    @AfterEach
    void cleanup() {
        writeFailure.clear();
        var ids =
                jdbc.queryForList(
                        "SELECT id FROM public.nocode_application WHERE app_code LIKE ?",
                        Long.class,
                        fixture.prefix + "%");
        for (Long id : ids) {
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

    private WorkDrafts.Draft draft(Map<String, Object> values, long actor) {
        return work.saveDraft(
                new WorkDrafts.Save(null, null, resource, object.objectId(), null, null, values),
                actor);
    }

    private WorkDrafts.Submit command(WorkDrafts.Draft draft) {
        return new WorkDrafts.Submit(draft.id(), draft.revision(), UUID.randomUUID().toString());
    }

    long recordCount() {
        return records.page(
                        new ApplicationRecords.Query(
                                resource.applicationId(),
                                object.objectId(),
                                1,
                                20,
                                null,
                                null,
                                null,
                                false),
                        10001)
                .getTotal();
    }

    void grantMember(Set<String> actions, Set<String> fields) {
        var auth = servicesContext.getBean(ApplicationAuthorizationService.class);
        auth.save(
                new ApplicationAuthorization.Save(
                        resource.applicationId(),
                        auth.get(resource.applicationId()).revision(),
                        List.of(
                                new ApplicationAuthorization.Member(
                                        "USER",
                                        "20002",
                                        List.of(
                                                new ApplicationAuthorization.ObjectGrant(
                                                        object.objectId(),
                                                        actions,
                                                        "ALL",
                                                        fields,
                                                        fields,
                                                        Set.of(),
                                                        Set.of()))))),
                10001);
    }

    @Test
    void incompleteDraftNeverCreatesBusinessDataAndSubmitStillRequiresMandatoryFields() {
        var draft = draft(Map.of(), 10001);
        assertThat(draft.state()).isEqualTo("DRAFT");
        assertThat(recordCount()).isZero();
        assertThatThrownBy(() -> work.submit(command(draft), 10001)).hasMessageContaining("必填");
        assertThat(recordCount()).isZero();
        assertThat(work.getDraft(draft.id(), 10001).state()).isEqualTo("DRAFT");
    }

    @Test
    void submissionIsIdempotentAndItsValuesSurviveLaterRecordChanges() {
        var draft = draft(Map.of(nameField, "提交时名称"), 10001);
        var command = command(draft);
        var first = work.submit(command, 10001);
        assertThat(work.submit(command, 10001).id()).isEqualTo(first.id());
        assertThat(recordCount()).isEqualTo(1);
        assertThat(work.getDraft(draft.id(), 10001).state()).isEqualTo("SUBMITTED");
        records.save(
                new ApplicationRecords.Save(
                        resource.applicationId(),
                        object.objectId(),
                        first.recordId(),
                        first.recordRevision(),
                        Map.of(nameField, "业务后续名称"),
                        null),
                10001);
        assertThat(work.getSubmission(first.id(), 10001).values())
                .containsEntry(nameField, "提交时名称");
        assertThatThrownBy(
                        () ->
                                work.submit(
                                        new WorkDrafts.Submit(
                                                draft.id(),
                                                draft.revision() + 1,
                                                command.idempotencyKey()),
                                        10001))
                .hasMessageContaining("幂等标识");
        assertThatThrownBy(
                        () ->
                                work.saveDraft(
                                        new WorkDrafts.Save(
                                                draft.id(),
                                                draft.revision() + 1,
                                                resource,
                                                object.objectId(),
                                                null,
                                                null,
                                                Map.of(nameField, "覆盖")),
                                        10001))
                .hasMessageContaining("已被修改或提交");
    }

    @Test
    void failureAfterMaterialInsertRollsBackBusinessMaterialAndDraftTransition() {
        var draft = draft(Map.of(nameField, "原子提交"), 10001);
        var command = command(draft);
        writeFailure.failAfter("INSERT INTO public.nocode_work_submission");
        try {
            assertThatThrownBy(() -> work.submit(command, 10001))
                    .hasRootCauseMessage("B1 intentional failure after MyBatis database write");
        } finally {
            writeFailure.clear();
        }
        assertThat(recordCount()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_work_submission WHERE"
                                        + " draft_id=?",
                                Integer.class,
                                draft.id()))
                .isZero();
        assertThat(work.getDraft(draft.id(), 10001).revision()).isEqualTo(draft.revision());
        assertThat(work.getDraft(draft.id(), 10001).state()).isEqualTo("DRAFT");
        assertThat(work.submit(command, 10001).values()).containsEntry(nameField, "原子提交");
        assertThat(recordCount()).isEqualTo(1);
    }

    @Test
    void concurrentRetriesProduceOneRecordAndOneMaterial() throws Exception {
        var draft = draft(Map.of(nameField, "并发提交"), 10001);
        var command = command(draft);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<WorkDrafts.Submission> submit =
                    () -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS))
                            throw new IllegalStateException("并发起跑超时");
                        return work.submit(command, 10001);
                    };
            var first = executor.submit(submit);
            var second = executor.submit(submit);
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                start.countDown();
            }
            assertThat(first.get(20, TimeUnit.SECONDS).id())
                    .isEqualTo(second.get(20, TimeUnit.SECONDS).id());
        }
        assertThat(recordCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM public.nocode_work_submission WHERE"
                                        + " draft_id=?",
                                Integer.class,
                                draft.id()))
                .isEqualTo(1);
    }

    @Test
    void latestApplicationPublishDoesNotRewriteDraftResourceVersion() {
        var draft = draft(Map.of(nameField, "沿用原表单"), 10001);
        var current = applications.get(resource.applicationId());
        var saved =
                applications.save(
                        new ApplicationCenter.Save(
                                resource.applicationId(),
                                current.application().revision(),
                                current.application().code(),
                                current.application().name(),
                                null,
                                null,
                                new ApplicationCenter.Definition(
                                        current.draft().objects(), List.of())),
                        10001);
        applications.publish(
                new ApplicationCenter.Revision(
                        resource.applicationId(), saved.application().revision(), "移除当前表单"),
                10001);
        var restored = workspace.context(draft.id(), 10001);
        assertThat(restored.form().nodes()).hasSize(1);
        assertThat(restored.draft().resource()).isEqualTo(resource);
        assertThat(restored.writable()).isTrue();
        assertThat(work.submit(command(draft), 10001).resource()).isEqualTo(resource);
        assertThat(workspace.context(draft.id(), 10001).writable()).isFalse();
        assertThat(applications.published(resource.applicationId()).versionNo()).isEqualTo(2);
        assertThatThrownBy(() -> draft(Map.of(nameField, "伪造旧表单入口"), 10001))
                .hasMessageContaining("应用已发布新版本");
    }

    @Test
    void currentAuthorizationAndDraftOwnershipAreRechecked() {
        grantMember(Set.of("READ", "CREATE", "UPDATE"), Set.of(nameField));
        var draft = draft(Map.of(nameField, "成员草稿"), 20002);
        assertThatThrownBy(() -> work.getDraft(draft.id(), 10001)).hasMessageContaining("无权访问");
        grantMember(Set.of("READ"), Set.of(nameField));
        var readOnly = workspace.context(draft.id(), 20002);
        assertThat(readOnly.writable()).isFalse();
        assertThat(readOnly.model().permissions().writeFields()).isEmpty();
        assertThat(readOnly.blockedReason()).isNotBlank();
        assertThatThrownBy(() -> work.submit(command(draft), 20002)).hasMessageContaining("权限");
        assertThat(recordCount()).isZero();
        // 未提交输入不能因撤回读取权限而被静默裁剪，恢复授权后应取回原内容。
        grantMember(Set.of("READ"), Set.of());
        assertThatThrownBy(() -> workspace.context(draft.id(), 20002))
                .hasMessageContaining("读取权限已变化");
        grantMember(Set.of("READ", "CREATE"), Set.of(nameField));
        assertThat(work.getDraft(draft.id(), 20002).values()).containsEntry(nameField, "成员草稿");
        var submission = work.submit(command(draft), 20002);
        grantMember(Set.of("READ"), Set.of());
        assertThat(work.getSubmission(submission.id(), 20002).values())
                .doesNotContainKey(nameField);
        // 已封存材料允许按当前权限只读展示，读取不改写存储的原始输入。
        var completed = workspace.context(draft.id(), 20002);
        assertThat(completed.writable()).isFalse();
        assertThat(completed.draft().values()).doesNotContainKey(nameField);
        assertThat(completed.submission().values()).doesNotContainKey(nameField);
        assertThat(
                        servicesContext
                                .getBean(
                                        com.richuang.os.nocode.work.service.draft.WorkDraftService
                                                .class)
                                .get(draft.id(), 20002)
                                .values())
                .containsEntry(nameField, "成员草稿");
        var auth = servicesContext.getBean(ApplicationAuthorizationService.class);
        auth.save(
                new ApplicationAuthorization.Save(
                        resource.applicationId(),
                        auth.get(resource.applicationId()).revision(),
                        List.of()),
                10001);
        assertThatThrownBy(() -> work.getSubmission(submission.id(), 20002))
                .hasMessageContaining("权限");
        assertThatThrownBy(() -> work.getDraft(draft.id(), 20002)).hasMessageContaining("权限");
    }

    @Test
    void staleEditDraftCannotOverwriteChangedBusinessRecord() {
        var record =
                records.save(
                                new ApplicationRecords.Save(
                                        resource.applicationId(),
                                        object.objectId(),
                                        null,
                                        null,
                                        Map.of(nameField, "原记录"),
                                        null),
                                10001)
                        .record();
        var draft =
                work.saveDraft(
                        new WorkDrafts.Save(
                                null,
                                null,
                                resource,
                                object.objectId(),
                                record.id(),
                                record.revision(),
                                Map.of(nameField, "草稿修改")),
                        10001);
        var newer =
                records.save(
                                new ApplicationRecords.Save(
                                        resource.applicationId(),
                                        object.objectId(),
                                        record.id(),
                                        record.revision(),
                                        Map.of(nameField, "其他人已修改"),
                                        null),
                                10001)
                        .record();
        assertThatThrownBy(() -> work.submit(command(draft), 10001)).hasMessageContaining("记录已");
        assertThat(
                        records.get(resource.applicationId(), object.objectId(), record.id(), 10001)
                                .record()
                                .revision())
                .isEqualTo(newer.revision());
        assertThat(work.getDraft(draft.id(), 10001).state()).isEqualTo("DRAFT");
        assertThat(workspace.context(draft.id(), 10001).recordChanged()).isTrue();
        var rebased =
                work.saveDraft(
                        new WorkDrafts.Save(
                                draft.id(),
                                draft.revision(),
                                resource,
                                object.objectId(),
                                record.id(),
                                newer.revision(),
                                Map.of(nameField, "比较后保留草稿")),
                        10001);
        assertThat(workspace.context(draft.id(), 10001).recordChanged()).isFalse();
        assertThat(work.submit(command(rebased), 10001).values())
                .containsEntry(nameField, "比较后保留草稿");
    }

    @Test
    void personalWorkspacePaginatesWithoutLeakingOtherOwnersOrUnauthorizedFields() {
        grantMember(Set.of("READ", "CREATE"), Set.of(nameField));
        var own = draft(Map.of(nameField, "本人"), 10001);
        var member = draft(Map.of(nameField, "成员"), 20002);
        var own2 = draft(Map.of(nameField, "本人第二条"), 10001);
        jdbc.update(
                "UPDATE public.nocode_work_draft SET create_time=TIMESTAMP '2026-09-09"
                        + " 10:00:00.123456' WHERE id=?",
                own.id());
        jdbc.update(
                "UPDATE public.nocode_work_draft SET create_time=TIMESTAMP '2026-09-09"
                        + " 10:00:00.123457' WHERE id=?",
                own2.id());
        var first =
                workspace.page(
                        new WorkDraftViews.Query(resource.applicationId(), "DRAFT", null, 1),
                        10001);
        assertThat(first.items()).hasSize(1);
        assertThat(first.before()).isNotNull();
        assertThat(first.before().createdAt()).endsWith(".123457");
        var second =
                workspace.page(
                        new WorkDraftViews.Query(
                                resource.applicationId(), "DRAFT", first.before(), 1),
                        10001);
        assertThat(second.items()).hasSize(1);
        assertThat(second.before()).isNull();
        assertThat(List.of(first.items().getFirst().id(), second.items().getFirst().id()))
                .containsExactlyInAnyOrder(own.id(), own2.id())
                .doesNotContain(member.id());
        work.submit(command(own), 10001);
        var submitted =
                workspace.page(
                        new WorkDraftViews.Query(resource.applicationId(), "SUBMITTED", null, 20),
                        10001);
        assertThat(submitted.items()).extracting(WorkDraftViews.Item::id).containsExactly(own.id());
        var material = workspace.context(own.id(), 10001);
        assertThat(material.currentRecord()).isNull();
        assertThat(material.submission().values()).containsEntry(nameField, "本人");
        assertThatThrownBy(() -> workspace.context(member.id(), 10001))
                .hasMessageContaining("无权访问");
        assertThatThrownBy(
                        () ->
                                workspace.selection(
                                        new WorkDraftViews.Selection(
                                                own2.id(),
                                                new SelectionFields.Query(
                                                        resource.applicationId(),
                                                        object.objectId(),
                                                        null,
                                                        "forged-field",
                                                        null,
                                                        1,
                                                        20,
                                                        List.of(),
                                                        null)),
                                        10001))
                .hasMessageContaining("不属于");
    }

    @Test
    void draftRevisionOutsideIntegerCacheRangeStillUpdatesAndRejectsStaleWriters() {
        var draft = draft(Map.of(nameField, "较长办理草稿"), 10001);
        jdbc.update("UPDATE public.nocode_work_draft SET lock_version=128 WHERE id=?", draft.id());
        var command =
                new WorkDrafts.Save(
                        draft.id(),
                        128,
                        resource,
                        object.objectId(),
                        null,
                        null,
                        Map.of(nameField, "第129次保存"));
        assertThat(work.saveDraft(command, 10001).revision()).isEqualTo(129);
        assertThatThrownBy(() -> work.saveDraft(command, 10001)).hasMessageContaining("已被修改或提交");
    }
}
