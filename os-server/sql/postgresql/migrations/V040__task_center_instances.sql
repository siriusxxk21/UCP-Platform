-- 正式工作任务：不修改原 TASK_ENTRY、Flowable 或公共业务对象记录。
CREATE TABLE public.nocode_task_instance (
 id varchar(64) PRIMARY KEY, root_id varchar(64) NOT NULL, parent_id varchar(64),
 title varchar(200) NOT NULL, assignee_id bigint NOT NULL, status varchar(20) NOT NULL,
 lock_version integer NOT NULL DEFAULT 0, config_json text NOT NULL, business_json text,
 project_json text, template_id varchar(64), template_version integer,
 t0 timestamp NOT NULL, baseline_start timestamp, baseline_end timestamp,
 expected_start timestamp, expected_end timestamp, actual_start timestamp, actual_end timestamp,
 request_key varchar(120), request_hash varchar(64),
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0,
 CONSTRAINT nocode_task_state_ck CHECK(status IN ('PENDING','RUNNING','COMPLETED','CANCELLED'))
);
CREATE INDEX nocode_task_root_idx ON public.nocode_task_instance(root_id) WHERE deleted=0;
CREATE INDEX nocode_task_assignee_idx ON public.nocode_task_instance(assignee_id,status,expected_end) WHERE deleted=0;
CREATE UNIQUE INDEX nocode_task_request_uk ON public.nocode_task_instance(creator,request_key) WHERE request_key IS NOT NULL;
CREATE TABLE public.nocode_task_template (
 id varchar(64) PRIMARY KEY, name varchar(200) NOT NULL, description text,
 lock_version integer NOT NULL DEFAULT 0, published_version integer, nodes_json text NOT NULL,
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0
);
CREATE TABLE public.nocode_task_template_version (
 id varchar(64) PRIMARY KEY, template_id varchar(64) NOT NULL, version_no integer NOT NULL,
 name varchar(200) NOT NULL, description text, nodes_json text NOT NULL, bindings_json text NOT NULL,
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0,
 UNIQUE(template_id,version_no)
);
CREATE TABLE public.nocode_task_plan (
 id varchar(64) PRIMARY KEY, task_id varchar(64) NOT NULL REFERENCES public.nocode_task_instance(id),
 user_id bigint NOT NULL, period varchar(10) NOT NULL, plan_date date NOT NULL,
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0,
 UNIQUE(task_id,user_id,period,plan_date), CHECK(period IN ('DAY','WEEK','MONTH'))
);
CREATE INDEX nocode_task_plan_user_idx ON public.nocode_task_plan(user_id,period,plan_date) WHERE deleted=0;
CREATE TABLE public.nocode_task_comment (
 id varchar(64) PRIMARY KEY, task_id varchar(64) NOT NULL REFERENCES public.nocode_task_instance(id),
 parent_id varchar(64), content text NOT NULL, mentioned_json text NOT NULL,
 request_key varchar(120) NOT NULL, request_hash varchar(64) NOT NULL,
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0,
 UNIQUE(creator,request_key)
);
CREATE INDEX nocode_task_comment_task_idx ON public.nocode_task_comment(task_id,create_time) WHERE deleted=0;
CREATE TABLE public.nocode_task_event (
 id varchar(64) PRIMARY KEY, task_id varchar(64) NOT NULL, root_id varchar(64) NOT NULL,
 event_type varchar(30) NOT NULL, note text, material_json text,
 request_key varchar(120), request_hash varchar(64),
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0
);
CREATE INDEX nocode_task_event_root_idx ON public.nocode_task_event(root_id,create_time) WHERE deleted=0;
CREATE INDEX nocode_task_event_actor_idx ON public.nocode_task_event(creator,create_time DESC) WHERE deleted=0;
CREATE UNIQUE INDEX nocode_task_event_request_uk ON public.nocode_task_event(creator,request_key) WHERE request_key IS NOT NULL;

-- 使用 /message/list 对应的真实消息中心；正文由调用方按纯文本转义。
INSERT INTO public.sys_msg_template
 (id,code,name,priority,subscribe_able,template_title,template_content,template_url,notice_config,creator,updater)
SELECT -9040001,'nocode-task-comment','任务评论提醒',0,0,
 '任务协作提醒','任务有新的评论','/nocode-app/task-center?taskId=' || chr(36) || '{taskId}','[]','task-center-migration','task-center-migration'
WHERE NOT EXISTS(SELECT 1 FROM public.sys_msg_template WHERE code='nocode-task-comment' AND deleted=0);

-- 保留旧深链接；将旧入口页面作为“我的任务”，其办理入口仍可在页面中访问。
DO $$
DECLARE dashboard bigint; folder bigint; mine bigint; task_menu bigint; entry record;
BEGIN
 LOCK TABLE public.system_menu IN SHARE ROW EXCLUSIVE MODE;
 SELECT id INTO STRICT dashboard FROM public.system_menu WHERE path='/dashboard' AND parent_id=0 AND deleted=0;
 SELECT id INTO STRICT mine FROM public.system_menu WHERE path='/nocode-app/task-center' AND deleted=0;
 folder:=nextval('public.system_menu_seq');
 INSERT INTO public.system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
 VALUES(folder,'任务中心','',1,11,dashboard,'/task-center','CheckSquareOutlined',NULL,'NocodeTaskFolder',0,true,false,true,'task-center-migration','task-center-migration',0);
 UPDATE public.system_menu SET parent_id=folder,name='我的任务',sort=1,permission='nocode:task:query',updater='task-center-migration',update_time=now() WHERE id=mine;
 INSERT INTO public.system_role_menu(id,role_id,menu_id,creator,updater,create_time,update_time,deleted,tenant_id)
 SELECT nextval('public.system_role_menu_seq'),role_id,folder,'task-center-migration','task-center-migration',now(),now(),0,tenant_id
 FROM public.system_role_menu WHERE menu_id=mine AND deleted=0;
 FOR entry IN SELECT * FROM (VALUES
 ('任务管理','/nocode-app/task-center/manage','NocodeTaskManage','nocode:task:query',2),
 ('发起任务','/nocode-app/task-center/launch','NocodeTaskLaunch','nocode:task:create',3),
 ('任务模板','/nocode-app/task-center/templates','NocodeTaskTemplates','nocode:task:template',4)
 ) AS items(name,path,component_name,permission,sort) LOOP
  task_menu:=nextval('public.system_menu_seq');
  INSERT INTO public.system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
  VALUES(task_menu,entry.name,entry.permission,2,entry.sort,folder,entry.path,'UnorderedListOutlined','nocode/task-center/index',entry.component_name,0,true,false,true,'task-center-migration','task-center-migration',0);
  INSERT INTO public.system_role_menu(id,role_id,menu_id,creator,updater,create_time,update_time,deleted,tenant_id)
  SELECT nextval('public.system_role_menu_seq'),role_id,task_menu,'task-center-migration','task-center-migration',now(),now(),0,tenant_id
  FROM public.system_role_menu WHERE public.system_role_menu.menu_id=mine AND deleted=0;
 END LOOP;
END $$;
