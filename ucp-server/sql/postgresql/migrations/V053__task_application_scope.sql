-- 任务所属应用与主业务表单独立；保留历史任务，通过运行查询兼容其项目与主业务绑定。
ALTER TABLE public.nocode_task_instance ADD COLUMN application_id varchar(64);
COMMENT ON COLUMN public.nocode_task_instance.application_id IS '任务所属应用；独立任务为空，子任务沿根继承';
CREATE INDEX nocode_task_instance_application_idx
    ON public.nocode_task_instance (application_id, expected_end, id) WHERE deleted = 0;
