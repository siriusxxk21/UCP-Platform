-- DEC-20260911-02：表格变更历史。前置 V017；仅新增平台表，不重写存量业务数据。
-- 校验：Flyway verify、RecordHistoryIntegrationTest。初始快照由同一业务事务按表加锁建立。
CREATE TABLE public.nocode_record_history_head (
    object_id bigint PRIMARY KEY REFERENCES public.nocode_object(id) ON DELETE CASCADE,
    covered_from timestamptz NOT NULL DEFAULT clock_timestamp(),
    creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1))
);
CREATE TABLE public.nocode_record_history (
    id bigserial PRIMARY KEY,
    object_id bigint NOT NULL REFERENCES public.nocode_record_history_head(object_id) ON DELETE CASCADE,
    record_id text NOT NULL,
    application_id bigint,
    operation varchar(16) NOT NULL CHECK (operation IN ('BASELINE','CREATE','UPDATE','DELETE')),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    before_json jsonb,
    after_json jsonb,
    definition_json jsonb NOT NULL,
    creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1))
);
CREATE INDEX nocode_record_history_time_idx ON public.nocode_record_history(object_id, occurred_at, id);
CREATE INDEX nocode_record_history_record_idx ON public.nocode_record_history(object_id, record_id, occurred_at DESC, id DESC);
COMMENT ON TABLE public.nocode_record_history IS '成功业务写入的前后值；与业务事务共同提交，BASELINE 不计入变更次数';
