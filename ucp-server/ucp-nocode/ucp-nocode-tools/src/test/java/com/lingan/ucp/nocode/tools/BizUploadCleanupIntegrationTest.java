package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.lingan.ucp.module.drive.api.bizfile.DriveBizFileApi;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizUploadCleanupResult;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizUploadCleanupService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 过期临时上传清理：先标记过期会话为待清理，再逐文件复核有效保留引用与受管节点后删除内容；
 * 有有效引用或受管节点时暂缓且保留待清理状态，保留解除后再次执行进入清理；删除失败登记补偿任务并在后续执行重试关闭。
 * 清理只按上传会话表驱动，已绑定会话与未过期会话不参与；夹具写入与清理执行在同一事务内整体回滚，不向开发库提交数据。
 */
class BizUploadCleanupIntegrationTest {

    private static final long ACTOR = 10001L;
    private static final AtomicLong FILE_SEQ = new AtomicLong(749500L);

    private NocodeIntegrationSupport fixture;
    private BizUploadCleanupService cleanup;
    private DriveBizFileApi drive;

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
        cleanup = servicesContext.getBean(BizUploadCleanupService.class);
        drive = servicesContext.getBean(DriveBizFileApi.class);
        // 网盘边界替身：本类用例只验证无代码侧清理决策，逐用例重置桩与调用记录
        reset(drive);
    }

    @Test
    void cleansExpiredUnretainedAndDefersHeldContentsUntilReleased() {
        String objectId = fixture.prefix + "cleanup";
        long plainFile = FILE_SEQ.incrementAndGet();
        long managedFile = FILE_SEQ.incrementAndGet();
        long retainedFile = FILE_SEQ.incrementAndGet();
        long lapsedDraftFile = FILE_SEQ.incrementAndGet();
        long heldDraftFile = FILE_SEQ.incrementAndGet();
        long freshFile = FILE_SEQ.incrementAndGet();
        long boundFile = FILE_SEQ.incrementAndGet();
        String heldDraftId = fixture.prefix + "live-draft";
        when(drive.hasManagedEntry(managedFile)).thenReturn(true);

        new TransactionTemplate(manager)
                .execute(
                        status -> {
                            insertSession(objectId, plainFile, "TEMPORARY", "-1 minute");
                            insertSession(objectId, managedFile, "TEMPORARY", "-1 minute");
                            insertSession(objectId, retainedFile, "TEMPORARY", "-2 minutes");
                            insertSession(objectId, lapsedDraftFile, "TEMPORARY", "-2 minutes");
                            insertSession(objectId, heldDraftFile, "TEMPORARY", "-2 minutes");
                            insertSession(objectId, freshFile, "TEMPORARY", "30 minutes");
                            insertSession(objectId, boundFile, "BOUND", "-1 minute");
                            // 历史保留有效；有效草稿保留有效；草稿引用已失效（草稿不存在）不应阻止清理
                            insertRetention(
                                    objectId,
                                    retainedFile,
                                    "RECORD_HISTORY",
                                    fixture.prefix + "hist");
                            insertRetention(
                                    objectId,
                                    lapsedDraftFile,
                                    "WORK_DRAFT",
                                    fixture.prefix + "draft");
                            insertRetention(objectId, heldDraftFile, "WORK_DRAFT", heldDraftId);
                            insertDraft(heldDraftId, objectId);

                            BizUploadCleanupResult first = cleanup.cleanupExpired(200);
                            // 无有效引用过期会话完成清理；有效历史、有效草稿与受管节点暂缓，状态停在待清理
                            assertThat(sessionState(plainFile)).isEqualTo("CLEANED");
                            assertThat(sessionState(lapsedDraftFile)).isEqualTo("CLEANED");
                            assertThat(sessionState(retainedFile)).isEqualTo("EXPIRED");
                            assertThat(sessionState(heldDraftFile)).isEqualTo("EXPIRED");
                            assertThat(sessionState(managedFile)).isEqualTo("EXPIRED");
                            // 未过期临时会话与已绑定会话不参与
                            assertThat(sessionState(freshFile)).isEqualTo("TEMPORARY");
                            assertThat(sessionState(boundFile)).isEqualTo("BOUND");
                            verify(drive).deleteTemporaryContent(plainFile);
                            verify(drive).deleteTemporaryContent(lapsedDraftFile);
                            verify(drive, never()).deleteTemporaryContent(retainedFile);
                            verify(drive, never()).deleteTemporaryContent(heldDraftFile);
                            verify(drive, never()).deleteTemporaryContent(managedFile);
                            verify(drive, never()).deleteTemporaryContent(freshFile);
                            verify(drive, never()).deleteTemporaryContent(boundFile);
                            assertThat(first.filesCleaned()).isGreaterThanOrEqualTo(2);
                            assertThat(first.skipped()).isGreaterThanOrEqualTo(3);

                            // 保留引用解除、草稿离开草稿态后再次执行：暂缓内容进入清理
                            jdbc.update(
                                    "DELETE FROM public.nocode_biz_file_retention WHERE file_id=?",
                                    retainedFile);
                            jdbc.update(
                                    "UPDATE public.nocode_work_draft SET state = 'SUBMITTED' WHERE"
                                            + " id = ?",
                                    heldDraftId);
                            cleanup.cleanupExpired(200);
                            assertThat(sessionState(retainedFile)).isEqualTo("CLEANED");
                            assertThat(sessionState(heldDraftFile)).isEqualTo("CLEANED");
                            verify(drive).deleteTemporaryContent(retainedFile);
                            verify(drive).deleteTemporaryContent(heldDraftFile);

                            status.setRollbackOnly();
                            return null;
                        });
    }

    @Test
    void recordsFailureAndRetriesUntilContentDeleted() {
        String objectId = fixture.prefix + "retry";
        long failingFile = FILE_SEQ.incrementAndGet();
        doThrow(new IllegalStateException("对象存储删除失败"))
                .when(drive)
                .deleteTemporaryContent(failingFile);

        new TransactionTemplate(manager)
                .execute(
                        status -> {
                            insertSession(objectId, failingFile, "TEMPORARY", "-1 minute");
                            // 第一次执行：内容删除失败，会话保持待清理并登记补偿任务
                            BizUploadCleanupResult first = cleanup.cleanupExpired(200);
                            assertThat(first.failed()).isGreaterThanOrEqualTo(1);
                            assertThat(sessionState(failingFile)).isEqualTo("EXPIRED");
                            Map<String, Object> failed = taskRow(failingFile);
                            assertThat(failed.get("state")).isEqualTo("FAILED");
                            assertThat(((Number) failed.get("attempts")).intValue()).isEqualTo(1);
                            assertThat((String) failed.get("last_error")).contains("对象存储删除失败");

                            // 第二次执行：补偿成功，会话完成登记且任务关闭
                            doNothing().when(drive).deleteTemporaryContent(failingFile);
                            cleanup.cleanupExpired(200);
                            assertThat(sessionState(failingFile)).isEqualTo("CLEANED");
                            Map<String, Object> closed = taskRow(failingFile);
                            assertThat(closed.get("state")).isEqualTo("DONE");
                            assertThat(((Number) closed.get("attempts")).intValue()).isEqualTo(1);

                            status.setRollbackOnly();
                            return null;
                        });
    }

    private String sessionState(long fileId) {
        return jdbc.queryForObject(
                "SELECT state FROM public.nocode_biz_upload_session WHERE file_id=?",
                String.class,
                fileId);
    }

    private Map<String, Object> taskRow(long fileId) {
        return jdbc.queryForMap(
                "SELECT state, attempts, last_error FROM public.nocode_biz_file_task"
                        + " WHERE task_type=? AND file_id=? ORDER BY id DESC LIMIT 1",
                BizUploadCleanupService.TASK_TYPE_TEMP_CONTENT_DELETE,
                fileId);
    }

    private void insertSession(String objectId, long fileId, String state, String expiresOffset) {
        jdbc.update(
                "INSERT INTO public.nocode_biz_upload_session (session_key, user_id, object_id,"
                        + " field_id, record_id, file_id, file_name, state, expires_at,"
                        + " idempotency_key, creator, updater) VALUES (?, ?, ?, 'files', NULL, ?,"
                        + " ?, ?, now() + CAST(? AS interval), '', ?, ?)",
                fixture.prefix + "-" + fileId,
                ACTOR,
                objectId,
                fileId,
                "清理验证-" + fileId,
                state,
                expiresOffset,
                Long.toString(ACTOR),
                Long.toString(ACTOR));
    }

    private void insertRetention(String objectId, long fileId, String holderType, String holderId) {
        jdbc.update(
                "INSERT INTO public.nocode_biz_file_retention (file_id, holder_type, holder_id,"
                        + " object_id, record_id, creator, updater) VALUES (?, ?, ?, ?, '', ?, ?)",
                fileId,
                holderType,
                holderId,
                objectId,
                Long.toString(ACTOR),
                Long.toString(ACTOR));
    }

    /** 有效草稿持有者：登记保留引用时草稿必须真实存在且处于草稿态 */
    private void insertDraft(String draftId, String objectId) {
        jdbc.update(
                "INSERT INTO public.nocode_work_draft (id, source_type, source_id, state,"
                        + " resource_json, object_id, record_id, values_json, creator, updater)"
                        + " VALUES (?, 'OBJECT', ?, 'DRAFT', '{}'::jsonb, ?, NULL,"
                        + " '{}'::jsonb, ?, ?)",
                draftId,
                objectId,
                objectId,
                Long.toString(ACTOR),
                Long.toString(ACTOR));
    }
}
