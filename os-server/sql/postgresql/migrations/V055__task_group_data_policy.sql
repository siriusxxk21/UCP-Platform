-- 总任务数据授权快照与模板总任务配置；不改写旧任务，不给旧任务追加授权。
ALTER TABLE public.nocode_task_instance ADD COLUMN authorization_json text;
ALTER TABLE public.nocode_task_template ADD COLUMN root_json text;
ALTER TABLE public.nocode_task_template ADD COLUMN authorization_json text;
ALTER TABLE public.nocode_task_template_version ADD COLUMN root_json text;
ALTER TABLE public.nocode_task_template_version ADD COLUMN authorization_json text;
COMMENT ON COLUMN public.nocode_task_instance.authorization_json IS '服务端批准的总任务数据权限及授权来源快照';
COMMENT ON COLUMN public.nocode_task_template_version.authorization_json IS '模板发布者批准的不可扩权授权快照';
