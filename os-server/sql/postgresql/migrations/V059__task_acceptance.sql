-- 验收为总任务的可选收尾环节；旧配置缺少 acceptorId 时保持直接完成。
ALTER TABLE public.nocode_task_instance DROP CONSTRAINT nocode_task_state_ck;
ALTER TABLE public.nocode_task_instance ADD CONSTRAINT nocode_task_state_ck
    CHECK (status IN ('PENDING','RUNNING','PENDING_ACCEPTANCE','COMPLETED','CANCELLED'));
CREATE INDEX nocode_task_acceptor_idx
    ON public.nocode_task_instance ((config_json::jsonb->>'acceptorId'), status)
    WHERE deleted=0 AND parent_id IS NULL;
