package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.module.bpm.api.definition.BpmProcessDefinitionApi;
import com.lingan.ucp.module.bpm.api.definition.dto.BpmBusinessBindingDTO;
import com.lingan.ucp.module.system.enums.permission.RoleCodeEnum;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationCenter.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 当前开发库应用回收站回归；只操作独立前缀夹具，正常用例整体回滚。 */
class ApplicationRecycleIntegrationTest extends NocodeIntegrationSupport {
    @Test
    void unfinishedHandlingRequestsProtectTheirApplicationIncludingRetryableFailures() {
        rollback(
                () -> {
                    var app = create("handling", 10001, Definition.empty());
                    var draftId = UUID.randomUUID().toString();
                    var submissionId = UUID.randomUUID().toString();
                    var handlingId = UUID.randomUUID();
                    jdbc.update(
                            "INSERT INTO"
                                + " public.nocode_work_draft(id,source_type,source_id,resource_json,object_id,values_json,creator,updater)"
                                + " VALUES(?,?,?,'{}'::jsonb,'1','{}'::jsonb,'10001','10001')",
                            draftId,
                            "APPLICATION",
                            prefix + "source");
                    jdbc.update(
                            "INSERT INTO"
                                + " public.nocode_work_submission(id,draft_id,idempotency_key,request_digest,material_json,creator,updater)"
                                + " VALUES(?,?,?,?,'{}'::jsonb,'10001','10001')",
                            submissionId,
                            draftId,
                            prefix + "submission",
                            "fixture");
                    jdbc.update(
                            "INSERT INTO"
                                + " public.nocode_handling_request(id,application_id,application_name,application_version,object_id,object_name,operation,name,request_key,request_digest,definition_checksum,definition_json,submission_id,process_definition_id,process_definition_key,status,creator,updater)"
                                + " VALUES(?,?,?,1,1,'测试对象','CREATE','删除保护测试',?,?,?,'{}'::jsonb,?,?,?,'PENDING','10001','10001')",
                            handlingId,
                            Long.valueOf(app.application().id()),
                            app.application().name(),
                            prefix + "request",
                            "fixture",
                            "fixture",
                            submissionId,
                            prefix + "definition",
                            prefix + "key");
                    for (var state : List.of("PENDING", "APPLY_PENDING", "APPLY_FAILED")) {
                        jdbc.update(
                                "UPDATE public.nocode_handling_request SET status=? WHERE id=?",
                                state,
                                handlingId);
                        assertThat(
                                        applications()
                                                .deletePreview(app.application().id(), 10001)
                                                .blockers())
                                .anyMatch(message -> message.contains("业务办理申请"));
                        assertThatThrownBy(() -> applications().delete(revision(app), 10001))
                                .hasMessageContaining("业务办理申请");
                    }
                    jdbc.update(
                            "UPDATE public.nocode_handling_request SET status='CANCELED' WHERE"
                                    + " id=?",
                            handlingId);
                    assertThat(applications().delete(revision(app), 10001)).isTrue();
                });
    }

    @Test
    void runningRecordProcessBlocksDeleteUntilCompleted() {
        rollback(
                () -> {
                    var ref = reference();
                    var app = create("running", 10001, new Definition(List.of(ref), List.of()));
                    jdbc.update(
                            "INSERT INTO"
                                + " public.nocode_record_process(application_id,application_version,object_id,object_version,record_id,action_id,name,business_key,process_definition_id,process_definition_key,status,creator,updater)"
                                + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            Long.valueOf(app.application().id()),
                            1,
                            Long.valueOf(ref.objectId()),
                            ref.versionNo(),
                            "1",
                            "test-action",
                            "测试流程",
                            prefix + "process",
                            prefix + "definition",
                            prefix + "key",
                            "RUNNING",
                            "10001",
                            "10001");
                    assertThat(
                                    applications()
                                            .deletePreview(app.application().id(), 10001)
                                            .blockers())
                            .anyMatch(message -> message.contains("运行中的流程"));
                    assertThatThrownBy(() -> applications().delete(revision(app), 10001))
                            .hasMessageContaining("运行中的流程");
                    jdbc.update(
                            "UPDATE public.nocode_record_process SET status='CANCELED' WHERE"
                                    + " business_key=?",
                            prefix + "process");
                    assertThat(applications().delete(revision(app), 10001)).isTrue();
                });
    }

    @Test
    void deleteWaitsForInFlightApplicationReadAndRejectsSubsequentReads() throws Exception {
        var app = create("locking", 10001, Definition.empty());
        var id = app.application().id();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var read =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .executeWithoutResult(
                                                    tx -> {
                                                        applications().get(id);
                                                        entered.countDown();
                                                        try {
                                                            if (!release.await(
                                                                    10,
                                                                    java.util.concurrent.TimeUnit
                                                                            .SECONDS))
                                                                throw new IllegalStateException(
                                                                        "未释放测试读锁");
                                                        } catch (InterruptedException ex) {
                                                            Thread.currentThread().interrupt();
                                                            throw new IllegalStateException(ex);
                                                        }
                                                    }));
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var deletion = pool.submit(() -> applications().delete(revision(app), 10001));
            try {
                assertThatThrownBy(
                                () -> deletion.get(250, java.util.concurrent.TimeUnit.MILLISECONDS))
                        .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            read.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(deletion.get(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> applications().get(id)).hasMessageContaining("不存在");
        } finally {
            release.countDown();
            jdbc.update(
                    "DELETE FROM public.nocode_application WHERE id=? AND app_code=?",
                    Long.valueOf(id),
                    prefix + "locking");
        }
    }

    private ApplicationService applications() {
        return servicesContext.getBean(ApplicationService.class);
    }

    private void rollback(Runnable action) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            try {
                                action.run();
                            } finally {
                                tx.setRollbackOnly();
                            }
                        });
    }

    private Detail create(String suffix, long actor, Definition definition) {
        return applications()
                .save(
                        new Save(
                                null,
                                null,
                                prefix + suffix,
                                "回收站验证" + suffix,
                                "本次测试夹具",
                                null,
                                definition,
                                "测试分类"),
                        actor);
    }

    private Revision revision(Detail app) {
        return new Revision(app.application().id(), app.application().revision(), "回收站专项验证");
    }

    private Detail edit(Detail app) {
        var row = app.application();
        return applications()
                .save(
                        new Save(
                                row.id(),
                                row.revision(),
                                row.code(),
                                row.name() + "已编辑",
                                row.description(),
                                row.icon(),
                                app.draft(),
                                row.category()),
                        10001);
    }

    private ObjectReference reference() {
        var design =
                designs.save(
                        new DataCenter.SaveDesign(
                                createRequest("recycleobj"), null, null, null, null, null),
                        10001);
        var plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        10001);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "回收站对象夹具"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        var published =
                servicesContext.getBean(DataObjectApi.class).getVersion(design.draft().id(), null);
        return new ObjectReference(
                published.objectId(), published.versionNo(), published.checksum());
    }

    @Test
    void deletingHidesApplicationAndPreservesSharedObjectDataAndHistory() {
        rollback(
                () -> {
                    var reference = reference();
                    var app =
                            create(
                                    "preserve",
                                    10001,
                                    new Definition(List.of(reference), List.of()));
                    grantApplicationObjects(app.application().id());
                    app = applications().publish(revision(app), 10001);
                    var id = app.application().id();
                    var object =
                            servicesContext
                                    .getBean(DataObjectApi.class)
                                    .getVersion(reference.objectId(), null);
                    var table = object.definition().tableName();
                    assertThat(table).startsWith("biz_" + prefix);
                    jdbc.update(
                            "INSERT INTO public.\""
                                    + table
                                    + "\"(name,creator,updater) VALUES(?,?,?)",
                            "应保留的业务数据",
                            "10001",
                            "10001");
                    String history =
                            jdbc.queryForObject(
                                    "SELECT definition_json::text FROM"
                                            + " public.nocode_application_version WHERE"
                                            + " application_id=?",
                                    String.class,
                                    Long.valueOf(id));
                    assertThat(applications().deletePreview(id, 10001).objectCount()).isEqualTo(1);
                    applications().delete(revision(app), 10001);
                    assertThat(applications().page(1, 10, prefix, 10001L).getList()).isEmpty();
                    assertThat(applications().runnableIds()).doesNotContain(id);
                    assertThatThrownBy(() -> applications().get(id)).hasMessageContaining("不存在");
                    assertThatThrownBy(() -> applications().published(id, 1))
                            .hasMessageContaining("不存在");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.\""
                                                    + table
                                                    + "\" WHERE name=?",
                                            Integer.class,
                                            "应保留的业务数据"))
                            .isEqualTo(1);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT definition_json::text FROM"
                                                    + " public.nocode_application_version WHERE"
                                                    + " application_id=?",
                                            String.class,
                                            Long.valueOf(id)))
                            .isEqualTo(history);
                    var recycled =
                            applications().recyclePage(1, 10, prefix, 10001).getList().getFirst();
                    assertThat(recycled.deletedBy()).isEqualTo("10001");
                    assertThat(recycled.deletedAt()).isNotNull();
                    assertThat(recycled.category()).isEqualTo("测试分类");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.nocode_resource_dependency"
                                                    + " WHERE source_key LIKE ? AND deleted=0",
                                            Integer.class,
                                            "application:" + id + ":%"))
                            .isPositive();
                });
    }

    @Test
    void restoreRequiresManualSaveAndExplicitPublishAndEnableWithFreshRevision() {
        rollback(
                () -> {
                    var app =
                            create(
                                    "restore",
                                    10001,
                                    new Definition(List.of(reference()), List.of()));
                    grantApplicationObjects(app.application().id());
                    app = applications().publish(revision(app), 10001);
                    var id = app.application().id();
                    var original = app;
                    var originalReleases = applications().releases(id, 1, 100).getList();
                    applications().delete(revision(app), 10001);
                    var recycled =
                            applications().recyclePage(1, 10, prefix, 10001).getList().getFirst();
                    var restore = new Revision(id, recycled.revision(), "恢复应用");
                    var restored = applications().restoreDeleted(restore, 10001);
                    assertThat(
                                    jdbc.queryForMap(
                                            "SELECT restored_by,restored_reason,deleted_by FROM"
                                                    + " public.nocode_application WHERE id=?",
                                            Long.valueOf(id)))
                            .containsEntry("restored_by", "10001")
                            .containsEntry("restored_reason", "恢复应用")
                            .containsEntry("deleted_by", "10001");
                    assertThat(restored.application().status()).isEqualTo("DISABLED");
                    assertThat(restored.application().recoveryPending()).isTrue();
                    assertThat(restored.application().recoveryNeedsEdit()).isTrue();
                    assertThat(restored.draft()).isEqualTo(original.draft());
                    assertThat(applications().releases(id, 1, 100).getList())
                            .isEqualTo(originalReleases);
                    assertThatThrownBy(() -> applications().published(id))
                            .hasMessageContaining("停用");
                    assertThatThrownBy(
                                    () ->
                                            applications()
                                                    .status(revision(restored), "ACTIVE", 10001))
                            .hasMessageContaining("发布启用");
                    assertThatThrownBy(() -> applications().publish(revision(restored), 10001))
                            .hasMessageContaining("发布启用");
                    assertThatThrownBy(
                                    () ->
                                            applications()
                                                    .restore(
                                                            new Restore(
                                                                    id,
                                                                    restored.application()
                                                                            .revision(),
                                                                    1,
                                                                    "历史绕过"),
                                                            10001))
                            .hasMessageContaining("发布启用");
                    assertThatThrownBy(
                                    () ->
                                            applications()
                                                    .publishAndEnable(revision(restored), 10001))
                            .hasMessageContaining("人工编辑");
                    var unchanged =
                            applications()
                                    .save(
                                            new Save(
                                                    id,
                                                    restored.application().revision(),
                                                    restored.application().code(),
                                                    restored.application().name(),
                                                    restored.application().description(),
                                                    restored.application().icon(),
                                                    restored.draft(),
                                                    restored.application().category()),
                                            10001);
                    assertThat(unchanged.application().recoveryNeedsEdit()).isTrue();
                    var edited = edit(unchanged);
                    assertThat(edited.application().recoveryPending()).isTrue();
                    assertThat(edited.application().recoveryNeedsEdit()).isFalse();
                    assertThatThrownBy(
                                    () ->
                                            applications()
                                                    .publishAndEnable(revision(restored), 10001))
                            .hasMessageContaining("其他操作");
                    var enabled = applications().publishAndEnable(revision(edited), 10001);
                    assertThat(enabled.application().status()).isEqualTo("ACTIVE");
                    assertThat(enabled.application().recoveryPending()).isFalse();
                    assertThat(enabled.application().publishedVersion()).isEqualTo(2);
                    assertThat(applications().published(id).application().name()).endsWith("已编辑");
                });
    }

    @Test
    void recyclePermissionsSearchPaginationAndRevisionAreEnforced() {
        rollback(
                () -> {
                    var first = create("own", 10001, Definition.empty());
                    var foreign = create("foreign", 10002, Definition.empty());
                    assertThatThrownBy(
                                    () ->
                                            applications()
                                                    .deletePreview(first.application().id(), 10002))
                            .hasMessageContaining("自己创建");
                    assertThatThrownBy(() -> applications().delete(revision(first), 10002))
                            .hasMessageContaining("自己创建");
                    var updated = edit(first);
                    assertThatThrownBy(() -> applications().delete(revision(first), 10001))
                            .hasMessageContaining("其他操作");
                    applications().delete(revision(updated), 10001);
                    applications().delete(revision(foreign), 10002);
                    assertThat(applications().recyclePage(1, 1, prefix, 10001).getTotal())
                            .isEqualTo(1);
                    assertThat(applications().recyclePage(1, 1, prefix + "%", 10001).getTotal())
                            .isZero();
                    var deleted =
                            applications().recyclePage(1, 1, prefix, 10001).getList().getFirst();
                    var command = new Revision(deleted.id(), deleted.revision(), "恢复");
                    assertThatThrownBy(() -> applications().restoreDeleted(command, 10002))
                            .hasMessageContaining("自己创建");
                    assertThatThrownBy(() -> applications().restoreDeleted(revision(first), 10001))
                            .hasMessageContaining("其他操作");
                    var permissions = servicesContext.getBean(PermissionCommonApi.class);
                    when(permissions.hasAnyRoles(10003L, RoleCodeEnum.SUPER_ADMIN.getCode()))
                            .thenReturn(true);
                    assertThat(applications().recyclePage(1, 1, prefix, 10003).getTotal())
                            .isEqualTo(2);
                    assertThat(applications().recyclePage(2, 1, prefix, 10003).getList())
                            .hasSize(1);
                    assertThat(applications().restoreDeleted(command, 10003).application().status())
                            .isEqualTo("DISABLED");
                });
    }

    @Test
    void effectiveProcessDefinitionBindingsAreRecheckedWhenDeleting() {
        rollback(
                () -> {
                    var app = create("binding", 10001, Definition.empty());
                    var api = servicesContext.getBean(BpmProcessDefinitionApi.class);
                    assertThat(
                                    applications()
                                            .deletePreview(app.application().id(), 10001)
                                            .blockers())
                            .isEmpty();
                    when(api.getEffectiveBusinessBindings("nocode"))
                            .thenReturn(
                                    List.of(
                                            new BpmBusinessBindingDTO(
                                                    "fixture-process",
                                                    "采购流程",
                                                    2,
                                                    "submit",
                                                    "填写采购单",
                                                    "{\"resource\":{\"applicationId\":\""
                                                            + app.application().id()
                                                            + "\"}}")));
                    try {
                        assertThat(
                                        applications()
                                                .deletePreview(app.application().id(), 10001)
                                                .blockers())
                                .singleElement()
                                .asString()
                                .contains("采购流程", "填写采购单");
                        assertThatThrownBy(() -> applications().delete(revision(app), 10001))
                                .hasMessageContaining("采购流程");
                        assertThat(applications().get(app.application().id())).isNotNull();
                    } finally {
                        when(api.getEffectiveBusinessBindings("nocode")).thenReturn(List.of());
                    }
                });
    }

    @Test
    void failedRecoveryPublishRollsBackActivationAndVersion() {
        // 此用例让服务独立提交/回滚，验证失败时数据库不保留事务内临时启用状态。
        var app = create("failedpublish", 10001, Definition.empty());
        var id = app.application().id();
        try {
            applications().delete(revision(app), 10001);
            var recycled = applications().recyclePage(1, 10, prefix, 10001).getList().getFirst();
            var restored =
                    applications()
                            .restoreDeleted(new Revision(id, recycled.revision(), "恢复"), 10001);
            var edited = edit(restored);
            assertThatThrownBy(() -> applications().publishAndEnable(revision(edited), 10001))
                    .hasMessageContaining("引用一个");
            var failed = applications().get(id);
            assertThat(failed.application().status()).isEqualTo("DISABLED");
            assertThat(failed.application().recoveryPending()).isTrue();
            assertThat(failed.application().revision()).isEqualTo(edited.application().revision());
            assertThat(applications().releases(id, 1, 100).getList()).isEmpty();
        } finally {
            jdbc.update(
                    "DELETE FROM public.nocode_application WHERE id=? AND app_code=?",
                    Long.valueOf(id),
                    prefix + "failedpublish");
        }
    }
}
