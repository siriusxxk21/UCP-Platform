-- 反馈记录详情中的 TASKS 反查真实贡献任务，按任务定位当前有效贡献，避免逐任务全表扫描。
-- 审批尚未生效及已被重提替代的贡献由查询边界排除，索引不改变现有记录或权限。
CREATE INDEX nocode_task_entry_record_task_idx
    ON public.nocode_task_entry_record(task_id)
    WHERE deleted=0 AND superseded_by IS NULL;
