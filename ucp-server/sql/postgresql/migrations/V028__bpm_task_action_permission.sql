-- 补齐 BPM 审批操作的权限定义。前置 V027；供现有待办及无代码审批联动共用。
-- 仅在缺失时新增按钮权限，不改已有定义，不向普通角色自动授予审批操作。
-- 校验：有效 bpm:task:update 唯一；新建时挂在原待办菜单下；角色授权数量不变。
DO $$
DECLARE
    action_count integer;
    task_menu_count integer;
    task_menu_id bigint;
BEGIN
    LOCK TABLE public.system_menu IN SHARE ROW EXCLUSIVE MODE;
    SELECT count(*) INTO action_count FROM public.system_menu
    WHERE deleted=0 AND permission='bpm:task:update';
    IF action_count > 1 THEN
        RAISE EXCEPTION 'Multiple bpm:task:update permissions; resolve before migrating';
    END IF;
    IF action_count = 0 THEN
        SELECT count(*),min(id) INTO task_menu_count,task_menu_id
        FROM public.system_menu WHERE deleted=0 AND path='/bpm/task/todo' AND type=2;
        IF task_menu_count <> 1 THEN
            RAISE EXCEPTION 'Exactly one existing BPM todo menu is required';
        END IF;
        INSERT INTO public.system_menu
        (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
         visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
        VALUES
        (nextval('public.system_menu_seq'),'审批操作','bpm:task:update',3,2,task_menu_id,
         '',NULL,NULL,NULL,0,false,false,false,'bpm-permission-migration',
         'bpm-permission-migration',now(),now(),0);
    END IF;
END $$;
