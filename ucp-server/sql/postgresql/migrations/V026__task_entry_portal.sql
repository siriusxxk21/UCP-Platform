-- 工作台门户只提供入口可见性；办理权限仍由当前入口、成员和对象共享范围共同决定。
CREATE UNIQUE INDEX nocode_work_draft_task_entry_active_uk
    ON public.nocode_work_draft (creator, source_id)
    WHERE deleted=0 AND source_type='TASK_ENTRY' AND state='DRAFT';
CREATE INDEX nocode_record_history_task_source_idx
    ON public.nocode_record_history (creator, application_id, (source_json->>'entryId'), id DESC)
    WHERE deleted=0 AND source_json->>'kind'='TASK_ENTRY';

DO $$
DECLARE
    workbench_id bigint;
    portal_id bigint;
BEGIN
    LOCK TABLE public.system_menu IN SHARE ROW EXCLUSIVE MODE;
    SELECT id INTO STRICT workbench_id FROM public.system_menu
    WHERE deleted=0 AND parent_id=0 AND path='/dashboard';
    SELECT id INTO portal_id FROM public.system_menu
    WHERE deleted=0 AND path='/nocode-app/task-center';
    IF portal_id IS NULL THEN
        portal_id := nextval('public.system_menu_seq');
        INSERT INTO public.system_menu
        (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
         visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
        VALUES (portal_id,'任务中心','',2,11,workbench_id,'/nocode-app/task-center',
                'AppstoreOutlined','nocode/task-center/index','NocodeTaskCenter',0,
                true,false,true,'task-entry-migration','task-entry-migration',now(),now(),0);
    END IF;
    INSERT INTO public.system_role_menu
    (id,role_id,menu_id,creator,updater,create_time,update_time,deleted,tenant_id)
    SELECT nextval('public.system_role_menu_seq'),r.role_id,portal_id,
           'task-entry-migration','task-entry-migration',now(),now(),0,r.tenant_id
    FROM (
        SELECT DISTINCT rm.role_id,rm.tenant_id FROM public.system_role_menu rm
        JOIN public.system_menu m ON m.id=rm.menu_id AND m.deleted=0
        WHERE rm.deleted=0 AND m.path='/dashboard'
    ) r
    WHERE NOT EXISTS (
        SELECT 1 FROM public.system_role_menu existing
        WHERE existing.deleted=0 AND existing.role_id=r.role_id
          AND existing.tenant_id=r.tenant_id AND existing.menu_id=portal_id
    );
END $$;
