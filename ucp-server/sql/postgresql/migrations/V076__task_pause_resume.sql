-- 任务暂停保存于当前节点；子任务通过祖先门控暂停，不改写其执行及完成事实。
-- 不改变已执行迁移，仅扩展状态约束；暂停/恢复事件复用现有任务事件表。
ALTER TABLE public.nocode_task_instance DROP CONSTRAINT nocode_task_state_ck;
ALTER TABLE public.nocode_task_instance ADD CONSTRAINT nocode_task_state_ck
    CHECK (status IN ('PENDING','RUNNING','PAUSED','PENDING_ACCEPTANCE','COMPLETED','CANCELLED'));
