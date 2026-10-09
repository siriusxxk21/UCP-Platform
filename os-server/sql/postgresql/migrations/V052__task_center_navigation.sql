-- 任务中心收敛为“我的任务、任务管理、任务模板”；发起任务改为页面内操作。
-- 前置 V051；沿用 V040 菜单身份、V041 独立的 manage-all 权限及 V042 顶层目录，不改业务数据。
-- 旧发起菜单保留 ID、父级、权限码、启停状态与全部角色关联，旧 URL 由前端兼容。
-- 仅为原已有效拥有任务中心目录和 query 的角色补任务管理导航，不增加任何权限码。
-- 缺失祖先、禁用菜单或补父级会恢复额外孤儿子权限的异常角色保持原状，仍可使用旧链接。
-- 验证：TaskMenuMigrationTest 在事务临时表执行本文件，核对权限树、精确授权与重复执行。
DO $$
DECLARE
    folder_id bigint;
    launch_id bigint;
    manage_id bigint;
BEGIN
    LOCK TABLE public.system_menu, public.system_role_menu IN SHARE ROW EXCLUSIVE MODE;

    SELECT id INTO STRICT folder_id
    FROM public.system_menu
    WHERE path='/task-center' AND parent_id=0
      AND component_name='NocodeTaskFolder' AND type=1 AND deleted=0;

    SELECT id INTO STRICT launch_id
    FROM public.system_menu
    WHERE path='/nocode-app/task-center/launch' AND parent_id=folder_id
      AND component_name='NocodeTaskLaunch' AND permission='nocode:task:create'
      AND type IN (2,3) AND deleted=0;

    SELECT id INTO STRICT manage_id
    FROM public.system_menu
    WHERE path='/nocode-app/task-center/manage' AND parent_id=folder_id
      AND component_name='NocodeTaskManage' AND permission='nocode:task:query'
      AND type=2 AND deleted=0;

    -- 按钮仍是目录的直接子项，避免旧角色未获 manage 菜单时被底座连带过滤 create。
    UPDATE public.system_menu
    SET type=3, visible=false, updater='task-navigation-migration', update_time=now()
    WHERE id=launch_id AND (type IS DISTINCT FROM 3 OR visible IS DISTINCT FROM false);

    -- 与底座 filterDisableMenus 一致：只沿同租户、同角色已授权且启用的完整祖先链取权限。
    WITH RECURSIVE effective_menus(role_id, tenant_id, menu_id, permission) AS (
        SELECT grants.role_id, grants.tenant_id, menu.id, menu.permission
        FROM public.system_role_menu grants
        JOIN public.system_menu menu ON menu.id=grants.menu_id
        WHERE grants.deleted=0 AND menu.deleted=0 AND menu.status=0 AND menu.parent_id=0
        UNION
        SELECT grants.role_id, grants.tenant_id, menu.id, menu.permission
        FROM effective_menus parent
        JOIN public.system_menu menu ON menu.parent_id=parent.menu_id
        JOIN public.system_role_menu grants ON grants.menu_id=menu.id
          AND grants.role_id=parent.role_id AND grants.tenant_id=parent.tenant_id
        WHERE grants.deleted=0 AND menu.deleted=0 AND menu.status=0
    ), candidates AS (
        SELECT DISTINCT folder.role_id, folder.tenant_id
        FROM effective_menus folder
        JOIN public.system_menu manage ON manage.id=manage_id AND manage.status=0 AND manage.visible=true
        WHERE folder.menu_id=folder_id
          AND EXISTS (
              SELECT 1 FROM effective_menus query_menu
              WHERE query_menu.role_id=folder.role_id AND query_menu.tenant_id=folder.tenant_id
                AND query_menu.permission='nocode:task:query'
          )
          AND NOT EXISTS (
              SELECT 1 FROM public.system_role_menu existing
              WHERE existing.role_id=folder.role_id AND existing.tenant_id=folder.tenant_id
                AND existing.menu_id=manage_id AND existing.deleted=0
          )
    ), reachable_menus(role_id, tenant_id, menu_id, permission) AS (
        SELECT candidate.role_id, candidate.tenant_id, manage.id, manage.permission
        FROM candidates candidate
        JOIN public.system_menu manage ON manage.id=manage_id
        UNION
        SELECT grants.role_id, grants.tenant_id, menu.id, menu.permission
        FROM reachable_menus parent
        JOIN public.system_menu menu ON menu.parent_id=parent.menu_id
        JOIN public.system_role_menu grants ON grants.menu_id=menu.id
          AND grants.role_id=parent.role_id AND grants.tenant_id=parent.tenant_id
        WHERE grants.deleted=0 AND menu.deleted=0 AND menu.status=0
    )
    INSERT INTO public.system_role_menu
        (id,role_id,menu_id,creator,updater,create_time,update_time,deleted,tenant_id)
    SELECT nextval('public.system_role_menu_seq'),candidate.role_id,manage_id,
           'task-navigation-migration','task-navigation-migration',now(),now(),0,candidate.tenant_id
    FROM candidates candidate
    WHERE NOT EXISTS (
        -- 补导航不能让原先缺父节点的 manage-all 等额外子权限重新进入登录权限集合。
        SELECT 1 FROM reachable_menus reachable
        WHERE reachable.role_id=candidate.role_id AND reachable.tenant_id=candidate.tenant_id
          AND coalesce(reachable.permission,'')<>''
          AND NOT EXISTS (
              SELECT 1 FROM effective_menus original
              WHERE original.role_id=reachable.role_id AND original.tenant_id=reachable.tenant_id
                AND original.permission=reachable.permission
          )
    );
END $$;
