-- 无变化保存保留幂等收据，但不计为新增、修改、关联或任务完成材料。
-- 仅扩展既有枚举约束，不新增审计存储、不更改历史贡献。
ALTER TABLE public.nocode_task_entry_record
    DROP CONSTRAINT nocode_task_entry_record_operation_check;
ALTER TABLE public.nocode_task_entry_record
    ADD CONSTRAINT nocode_task_entry_record_operation_check
    CHECK (operation IN ('CREATED', 'UPDATED', 'LINKED', 'DELETED', 'UNCHANGED'));
