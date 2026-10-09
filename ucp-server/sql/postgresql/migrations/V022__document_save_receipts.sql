-- DEC-20260912-01：整单保存成功收据与操作关联。前置 V021，不改写用户业务数据。
-- 收据与业务写入同事务；永久保留请求键及结果，无自动到期重建语义。
CREATE TABLE public.nocode_document_receipt (
    id bigserial PRIMARY KEY,
    application_id bigint NOT NULL,
    object_id bigint NOT NULL REFERENCES public.nocode_object(id) ON DELETE CASCADE,
    operation varchar(16) NOT NULL CHECK (operation IN ('SAVE')),
    request_key varchar(128) NOT NULL,
    request_digest varchar(64) NOT NULL,
    operation_id uuid NOT NULL UNIQUE,
    record_id text NOT NULL,
    record_revision text NOT NULL,
    policy_version varchar(64) NOT NULL,
    result_json jsonb NOT NULL,
    creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE (creator, application_id, object_id, operation, request_key)
);
CREATE INDEX nocode_document_receipt_record_idx ON public.nocode_document_receipt(object_id, record_id);
ALTER TABLE public.nocode_record_history ADD COLUMN operation_id uuid;
ALTER TABLE public.nocode_record_history ADD COLUMN policy_version varchar(64);
CREATE INDEX nocode_record_history_operation_idx ON public.nocode_record_history(operation_id) WHERE operation_id IS NOT NULL;
COMMENT ON TABLE public.nocode_document_receipt IS '整单成功收据：请求键永久去重，读取时重新核验业务权限；失败事务不留成功收据';
COMMENT ON COLUMN public.nocode_record_history.operation_id IS '一次整单写入标识，可关联成功收据；旧事件为空';
COMMENT ON COLUMN public.nocode_record_history.policy_version IS '本次完整性策略内容摘要，策略正文保留在 definition_json';
