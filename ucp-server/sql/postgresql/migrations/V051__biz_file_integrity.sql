-- 网盘业务融合完整性修复。前置 V043。
-- 1. 业务空间不归属个人，owner_id 允许为空；同名活动业务空间唯一。
-- 2. 上传会话增加 BINDING 事务占用状态；幂等键收窄到同一用户/对象/字段/编辑会话的有效临时上传。
-- 3. 幂等登记过期上传清理任务；迁移后在定时任务页执行一次“同步任务”以创建 PowerJob 实例。

ALTER TABLE public.drive_space
    ALTER COLUMN owner_id DROP NOT NULL;

CREATE UNIQUE INDEX drive_space_biz_name_uk
    ON public.drive_space (name)
    WHERE type='BIZ' AND deleted=0;

COMMENT ON COLUMN public.drive_space.owner_id IS
    '归属主体用户编号：个人空间必填，团队空间为责任人，业务空间为空';

ALTER TABLE public.nocode_biz_upload_session
    DROP CONSTRAINT nocode_biz_upload_session_state_ck;
ALTER TABLE public.nocode_biz_upload_session
    ADD CONSTRAINT nocode_biz_upload_session_state_ck
        CHECK (state IN ('TEMPORARY','BINDING','BOUND','EXPIRED','CLEANED'));

DROP INDEX public.nocode_biz_upload_session_idem_uk;
CREATE UNIQUE INDEX nocode_biz_upload_session_idem_uk
    ON public.nocode_biz_upload_session
       (user_id, object_id, field_id, session_key, idempotency_key)
    WHERE deleted=0 AND state IN ('TEMPORARY','BINDING') AND idempotency_key <> '';
COMMENT ON COLUMN public.nocode_biz_upload_session.idempotency_key IS
    '上传幂等键：仅在同一用户/对象/字段/编辑会话的有效临时上传内唯一';

-- 旧库的 infra_job 尚未登记 PowerJob 远程标识；NULL 表示等待管理端“同步任务”建立并回填。
ALTER TABLE public.infra_job
    ADD COLUMN IF NOT EXISTS powerjob_job_id bigint;

INSERT INTO public.infra_job
    (id, powerjob_job_id, name, status, handler_name, handler_param,
     cron_expression, retry_count, retry_interval, monitor_timeout,
     creator, create_time, updater, update_time, deleted)
SELECT nextval('public.infra_job_seq'), NULL, '业务临时文件清理', 1,
       'bizUploadCleanJob', '', '0 */15 * * * ?', 3, 1000, 300000,
       'biz-file-migration', now(), 'biz-file-migration', now(), 0
WHERE NOT EXISTS (
    SELECT 1 FROM public.infra_job
    WHERE handler_name='bizUploadCleanJob' AND deleted=0
);
