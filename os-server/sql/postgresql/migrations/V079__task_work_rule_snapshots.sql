-- 固化办理时的计量标准；存量事实以升级时实例仍有效的规则留底。
-- 不改业务记录、审批材料、数量或既有时长，只封存以前动态引用的规则。
ALTER TABLE public.nocode_task_entry_record ADD COLUMN work_rule_json text;
COMMENT ON COLUMN public.nocode_task_entry_record.work_rule_json IS '办理提交时工时规则；存量由 V079 按升级时实例标准封存';
UPDATE public.nocode_task_entry_record f
SET work_rule_json=coalesce(b.config_json::jsonb->'workRule','null'::jsonb)::text
FROM public.nocode_task_entry_binding b
WHERE b.task_id=f.task_id AND b.entry_key=f.entry_key AND f.work_rule_json IS NULL;
UPDATE public.nocode_task_entry_record SET work_rule_json='null' WHERE work_rule_json IS NULL;
