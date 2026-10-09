-- DEC-20260911-02 补充：在工作台下提供独立“表格更新”菜单。
-- 前置版本：V018；仅调整底座菜单与对应菜单可见范围，不修改业务数据权限。
-- 校验：菜单父级为 /dashboard 顶级工作台，组件为 nocode/record-history/index，
--       现有工作台角色具有该入口；数据查询继续复验应用、记录及字段权限。
DO $$
DECLARE
    workbench_id bigint;
    history_id bigint;
BEGIN
    LOCK TABLE public.system_menu IN SHARE ROW EXCLUSIVE MODE;
    SELECT id INTO STRICT workbench_id FROM public.system_menu
    WHERE deleted=0 AND parent_id=0 AND path='/dashboard';

    SELECT id INTO history_id FROM public.system_menu
    WHERE deleted=0 AND path='/nocode-app/record-history';
    IF history_id IS NULL THEN
        history_id := nextval('public.system_menu_seq');
        INSERT INTO public.system_menu
        (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
         visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
        VALUES (history_id,'表格更新','',2,10,workbench_id,'/nocode-app/record-history',
                'HistoryOutlined','nocode/record-history/index','NocodeRecordHistory',0,
                true,false,true,'history-menu-migration','history-menu-migration',now(),now(),0);
    ELSE
        UPDATE public.system_menu SET name='表格更新',permission='',type=2,sort=10,
            parent_id=workbench_id,icon='HistoryOutlined',component='nocode/record-history/index',
            component_name='NocodeRecordHistory',status=0,visible=true,keep_alive=false,
            updater='history-menu-migration',update_time=now()
        WHERE id=history_id;
    END IF;

    INSERT INTO public.system_role_menu
    (id,role_id,menu_id,creator,updater,create_time,update_time,deleted,tenant_id)
    SELECT nextval('public.system_role_menu_seq'),r.role_id,history_id,
           'history-menu-migration','history-menu-migration',now(),now(),0,r.tenant_id
    FROM (
        SELECT DISTINCT rm.role_id,rm.tenant_id FROM public.system_role_menu rm
        JOIN public.system_menu m ON m.id=rm.menu_id AND m.deleted=0
        WHERE rm.deleted=0 AND m.path='/dashboard'
    ) r
    WHERE NOT EXISTS (
        SELECT 1 FROM public.system_role_menu existing
        WHERE existing.deleted=0 AND existing.role_id=r.role_id
          AND existing.tenant_id=r.tenant_id AND existing.menu_id=history_id
    );
END $$;
