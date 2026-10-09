-- 记录关联独立于办理主记录；计划记录保留安排者和来源，旧数据按原创建者回填。
CREATE TABLE public.nocode_task_record_link (
 id varchar(64) PRIMARY KEY, task_id varchar(64) NOT NULL REFERENCES public.nocode_task_instance(id),
 application_id varchar(64) NOT NULL, object_id varchar(64) NOT NULL, record_id varchar(200) NOT NULL,
 label varchar(200), creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0,
 UNIQUE(task_id,application_id,object_id,record_id)
);
CREATE INDEX nocode_task_record_link_context_idx ON public.nocode_task_record_link(application_id,object_id,record_id) WHERE deleted=0;
ALTER TABLE public.nocode_task_plan ADD COLUMN arranged_by_id bigint;
ALTER TABLE public.nocode_task_plan ADD COLUMN source varchar(20) NOT NULL DEFAULT 'SELF';
ALTER TABLE public.nocode_task_plan ADD COLUMN arranged_at timestamp;
UPDATE public.nocode_task_plan SET arranged_by_id=creator::bigint,arranged_at=create_time;
ALTER TABLE public.nocode_task_plan ALTER COLUMN arranged_by_id SET NOT NULL;
ALTER TABLE public.nocode_task_plan ALTER COLUMN arranged_at SET NOT NULL;
ALTER TABLE public.nocode_task_plan ADD CONSTRAINT nocode_task_plan_source_ck CHECK(source IN ('SELF','MANAGER'));

-- 只修改本任务域模板，既有消息中的任务定位仍兼容，新增消息同时定位具体评论。
UPDATE public.sys_msg_template SET template_url='/nocode-app/task-center?taskId=' || chr(36) || '{taskId}&commentId=' || chr(36) || '{commentId}',
 updater='task-center-migration',update_time=now()
WHERE code='nocode-task-comment' AND deleted=0;
