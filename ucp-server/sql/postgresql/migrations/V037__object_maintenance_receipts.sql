-- 数据对象维护没有应用上下文，沿用原幂等收据并用空 application_id 区分入口。
ALTER TABLE public.nocode_document_receipt ALTER COLUMN application_id DROP NOT NULL;
CREATE UNIQUE INDEX nocode_document_receipt_maintenance_request
    ON public.nocode_document_receipt (creator, object_id, operation, request_key)
    WHERE application_id IS NULL;
