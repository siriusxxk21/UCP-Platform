-- 应用删除审计及恢复发布门禁。前置 V030；仅扩展应用元数据，不修改共享对象或业务数据。
-- 校验：回收站权限、乐观锁、删除阻断、恢复后人工保存及原子发布启用专项集成测试。
ALTER TABLE public.nocode_application
    ADD COLUMN deleted_at timestamp(6),
    ADD COLUMN deleted_by varchar(64),
    ADD COLUMN deleted_reason varchar(1000),
    ADD COLUMN restored_at timestamp(6),
    ADD COLUMN restored_by varchar(64),
    ADD COLUMN restored_reason varchar(1000),
    ADD COLUMN recovery_pending boolean NOT NULL DEFAULT false,
    ADD COLUMN recovery_needs_edit boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT nocode_application_recovery_ck CHECK (
        (NOT recovery_pending OR status='DISABLED')
        AND (NOT recovery_needs_edit OR recovery_pending)
    );
CREATE INDEX nocode_application_recycle_idx
    ON public.nocode_application(deleted_at DESC,id DESC) WHERE deleted=1;
COMMENT ON COLUMN public.nocode_application.deleted_at IS '最近一次移入应用回收站的时间，恢复后保留审计';
COMMENT ON COLUMN public.nocode_application.recovery_pending IS '回收站恢复后必须重新发布启用，不允许直接启用旧版本';
COMMENT ON COLUMN public.nocode_application.recovery_needs_edit IS '恢复后尚未人工保存草稿，禁止发布启用';
