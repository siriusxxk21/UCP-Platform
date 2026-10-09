-- 允许记录总任务授权下的删除事实，保留 V045 的全部贡献类型；失败的公共记录删除仍整体回滚。
ALTER TABLE public.nocode_task_entry_record
    DROP CONSTRAINT nocode_task_entry_record_operation_check;
ALTER TABLE public.nocode_task_entry_record
    ADD CONSTRAINT nocode_task_entry_record_operation_check
    CHECK (operation IN ('CREATED', 'UPDATED', 'LINKED', 'DELETED'));
