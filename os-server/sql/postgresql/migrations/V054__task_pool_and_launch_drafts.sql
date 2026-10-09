-- 人员安排与执行状态分离；历史节点仍以缺省 assignmentMode 保留原 ASSIGNED 语义。
ALTER TABLE public.nocode_task_instance ALTER COLUMN assignee_id DROP NOT NULL;
ALTER TABLE public.nocode_task_instance ADD COLUMN planned_start timestamp;
COMMENT ON COLUMN public.nocode_task_instance.planned_start IS '显式计划起点；旧任务 T0 继续使用原创建起点';

CREATE INDEX nocode_task_claimable_idx ON public.nocode_task_instance (create_time, id)
    WHERE deleted = 0 AND status = 'PENDING' AND assignee_id IS NULL
        AND config_json::jsonb->>'assignmentMode' = 'OPEN';

-- 实例编排草稿与模板草稿、业务表单草稿分开，始终只允许创建人访问。
CREATE TABLE public.nocode_task_launch_draft (
    id varchar(64) PRIMARY KEY,
    lock_version integer NOT NULL DEFAULT 1,
    content_json text NOT NULL,
    published_task_id varchar(64),
    publish_key varchar(120),
    creator varchar(64) NOT NULL,
    create_time timestamp NOT NULL DEFAULT clock_timestamp(),
    updater varchar(64) NOT NULL,
    update_time timestamp NOT NULL DEFAULT clock_timestamp(),
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT ck_nocode_task_launch_draft_revision CHECK (lock_version > 0),
    CONSTRAINT ck_nocode_task_launch_draft_publish CHECK (
        (published_task_id IS NULL AND publish_key IS NULL)
        OR (published_task_id IS NOT NULL AND publish_key IS NOT NULL))
);
COMMENT ON TABLE public.nocode_task_launch_draft IS '本人任务编排草稿及原子加入任务池回执';
CREATE INDEX nocode_task_launch_draft_owner_idx ON public.nocode_task_launch_draft
    (creator, update_time DESC, id DESC) WHERE deleted = 0 AND published_task_id IS NULL;

-- 复用消息中心已有站内信投递，不授予新的任务或业务权限。
INSERT INTO public.sys_msg_template
    (id,code,name,priority,subscribe_able,template_title,template_content,template_url,notice_config,creator,updater)
SELECT -9054001,'nocode-task-assignment','任务人员安排提醒',0,0,
    '任务人员安排提醒','任务的人员安排已更新',
    '/nocode-app/task-center?taskId=' || chr(36) || '{taskId}','[]','task-center-migration','task-center-migration'
WHERE NOT EXISTS(SELECT 1 FROM public.sys_msg_template WHERE code='nocode-task-assignment' AND deleted=0);
