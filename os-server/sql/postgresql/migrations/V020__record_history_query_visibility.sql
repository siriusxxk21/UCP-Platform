-- 用途：历史查询拆分后固定跨请求的提交可见性，并为范围内变更扫描增加部分索引。
-- 前置：V019。保留既有历史及定义原文，不改写已执行迁移。
-- 影响：给现有历史回填本次迁移的事务 ID；新写入由数据库自动记录顶层事务 ID。
-- 校验：written_txid 无空值；旧写入接口仍可使用；跨请求排除查询时尚未提交的写入。
ALTER TABLE public.nocode_record_history
    ADD COLUMN written_txid xid8 NOT NULL DEFAULT pg_current_xact_id();
COMMENT ON COLUMN public.nocode_record_history.written_txid IS '写入事务 ID，配合 pg_current_snapshot 固定查询可见性';

CREATE INDEX nocode_record_history_changes_time_idx
    ON public.nocode_record_history (object_id, occurred_at, id)
    WHERE deleted = 0 AND operation <> 'BASELINE';
