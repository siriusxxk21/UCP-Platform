-- 前置V077：注册任务中心能效统计导航，不新增管理权限、不变更任务或办理数据。
-- 仅为已有有效query与create/manage-all的角色补入口；迁移测试在临时表执行核对授权。
DO $$
DECLARE folder_id bigint; efficiency_id bigint;
BEGIN
    LOCK TABLE public.system_menu, public.system_role_menu IN SHARE ROW EXCLUSIVE MODE;
    SELECT id INTO STRICT folder_id FROM public.system_menu
    WHERE path='/task-center' AND parent_id=0 AND component_name='NocodeTaskFolder' AND type=1 AND deleted=0;

    SELECT id INTO efficiency_id FROM public.system_menu
    WHERE path='/nocode-app/task-center/efficiency' AND deleted=0;
    IF efficiency_id IS NULL THEN
        efficiency_id:=nextval('public.system_menu_seq');
        INSERT INTO public.system_menu
            (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
             visible,keep_alive,always_show,creator,updater,deleted)
        VALUES(efficiency_id,'能效统计','nocode:task:query',2,5,folder_id,
            '/nocode-app/task-center/efficiency','BarChartOutlined','nocode/task-center/TaskEfficiency',
            'NocodeTaskEfficiency',0,true,false,true,'task-efficiency-migration','task-efficiency-migration',0);
    END IF;

    WITH RECURSIVE effective_menus(role_id,tenant_id,menu_id,permission) AS (
        SELECT g.role_id,g.tenant_id,m.id,m.permission
        FROM public.system_role_menu g JOIN public.system_menu m ON m.id=g.menu_id
        WHERE g.deleted=0 AND m.deleted=0 AND m.status=0 AND m.parent_id=0
        UNION
        SELECT g.role_id,g.tenant_id,m.id,m.permission
        FROM effective_menus p JOIN public.system_menu m ON m.parent_id=p.menu_id
        JOIN public.system_role_menu g ON g.menu_id=m.id AND g.role_id=p.role_id AND g.tenant_id=p.tenant_id
        WHERE g.deleted=0 AND m.deleted=0 AND m.status=0
    )
    INSERT INTO public.system_role_menu
        (id,role_id,menu_id,creator,updater,create_time,update_time,deleted,tenant_id)
    SELECT nextval('public.system_role_menu_seq'),p.role_id,efficiency_id,
        'task-efficiency-migration','task-efficiency-migration',now(),now(),0,p.tenant_id
    FROM effective_menus p WHERE p.menu_id=folder_id
        AND EXISTS(SELECT 1 FROM effective_menus q WHERE q.role_id=p.role_id AND q.tenant_id=p.tenant_id AND q.permission='nocode:task:query')
        AND EXISTS(SELECT 1 FROM effective_menus manage WHERE manage.role_id=p.role_id AND manage.tenant_id=p.tenant_id AND manage.permission IN ('nocode:task:create','nocode:task:manage-all'))
        AND NOT EXISTS(SELECT 1 FROM public.system_role_menu old WHERE old.role_id=p.role_id AND old.tenant_id=p.tenant_id AND old.menu_id=efficiency_id AND old.deleted=0);
END $$;
