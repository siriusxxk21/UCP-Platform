-- 任务入口实时授权与业务变更来源。前置 V024；旧应用授权和历史数据保持原语义。
-- 验证：独立入口授权不能开放原应用 API，应用回退不恢复授权；来源随保存事务回滚。
CREATE TABLE public.nocode_task_entry_access (
    id bigserial PRIMARY KEY,
    application_id bigint NOT NULL REFERENCES public.nocode_application(id) ON DELETE CASCADE,
    entry_id varchar(80) NOT NULL,
    lock_version integer NOT NULL DEFAULT 1,
    enabled boolean NOT NULL DEFAULT false,
    policy_json jsonb NOT NULL DEFAULT '[]'::jsonb,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT nocode_task_entry_access_unique UNIQUE (application_id, entry_id)
);
COMMENT ON TABLE public.nocode_task_entry_access IS '任务入口实时授权和启停，不随应用版本回退';
ALTER TABLE public.nocode_record_history ADD COLUMN source_json jsonb;
COMMENT ON COLUMN public.nocode_record_history.source_json IS '服务端生成的办理来源；历史空值不反推入口';
