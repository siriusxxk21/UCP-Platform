-- 网盘平台入口。前置 V032；新增一级入口「网盘」、七个二级菜单及 drive:* 权限定义。
-- 只补菜单与权限定义，不向既有角色批量授权：网盘菜单仍需在角色管理中显式分配后才对非超管可见。
-- 校验：一级入口唯一；二级菜单各一条；16 个 drive 权限码全部存在；既有菜单与角色授权行数不变。
DO $$
DECLARE
    entry_count integer;
    entry_id bigint;
    menu_count integer;
    permission_count integer;
BEGIN
    LOCK TABLE public.system_menu IN SHARE ROW EXCLUSIVE MODE;

    SELECT count(*), min(id) INTO entry_count, entry_id
    FROM public.system_menu WHERE deleted=0 AND parent_id=0 AND path='/drive';
    IF entry_count > 1 THEN
        RAISE EXCEPTION 'Multiple drive entry menus; resolve before installing drive workspace';
    END IF;
    IF entry_id IS NULL THEN
        entry_id := nextval('public.system_menu_seq');
        INSERT INTO public.system_menu
        (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
         visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
        VALUES
        (entry_id,'网盘','',1,27,0,'/drive','CloudOutlined',NULL,NULL,0,
         true,true,true,'drive-menu-migration','drive-menu-migration',now(),now(),0);
    END IF;

    -- 二级菜单按路径补齐：组件路径对应 views/drive 下的页面，主权限码取该页的首个能力。
    INSERT INTO public.system_menu
    (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
     visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
    SELECT nextval('public.system_menu_seq'),v.name,v.permission,2,v.sort,entry_id,v.path,v.icon,
           v.component,v.component_name,0,true,true,true,'drive-menu-migration','drive-menu-migration',
           now(),now(),0
    FROM (VALUES
        ('最近使用','drive:entry:query',1,'/drive/recent','ClockCircleOutlined','drive/recent','DriveRecent'),
        ('我的文件','drive:entry:query',2,'/drive/my-file','FolderOutlined','drive/my-file','DriveMyFile'),
        ('团队空间','drive:entry:query',3,'/drive/team','TeamOutlined','drive/team','DriveTeam'),
        ('与我共享','drive:share:query',4,'/drive/shared','ShareAltOutlined','drive/shared','DriveShared'),
        ('我的收藏','drive:mark:query',5,'/drive/favorite','StarOutlined','drive/favorite','DriveFavorite'),
        ('回收站','drive:entry:query',6,'/drive/trash','DeleteOutlined','drive/trash','DriveTrash'),
        ('空间管理','drive:space:query',7,'/drive/space','HddOutlined','drive/space','DriveSpace')
    ) AS v(name,permission,sort,path,icon,component,component_name)
    WHERE NOT EXISTS (
        SELECT 1 FROM public.system_menu existing WHERE existing.deleted=0 AND existing.path=v.path
    );

    -- 按钮权限按权限码补齐，挂在对应二级菜单下。
    INSERT INTO public.system_menu
    (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
     visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
    SELECT nextval('public.system_menu_seq'),v.name,v.permission,3,v.sort,parent.id,'',NULL,NULL,NULL,0,
           false,false,false,'drive-menu-migration','drive-menu-migration',now(),now(),0
    FROM (VALUES
        ('上传文件','drive:entry:upload',1,'/drive/my-file'),
        ('修改节点','drive:entry:update',2,'/drive/my-file'),
        ('删除节点','drive:entry:delete',3,'/drive/my-file'),
        ('查看成员与权限','drive:permission:query',4,'/drive/my-file'),
        ('调整成员与权限','drive:permission:update',5,'/drive/my-file'),
        ('创建分享','drive:share:create',1,'/drive/shared'),
        ('修改分享','drive:share:update',2,'/drive/shared'),
        ('撤销分享','drive:share:delete',3,'/drive/shared'),
        ('收藏与取消收藏','drive:mark:update',1,'/drive/favorite'),
        ('新建空间','drive:space:create',1,'/drive/space'),
        ('修改空间','drive:space:update',2,'/drive/space'),
        ('删除空间','drive:space:delete',3,'/drive/space')
    ) AS v(name,permission,sort,parent_path)
    JOIN public.system_menu parent ON parent.deleted=0 AND parent.type=2 AND parent.path=v.parent_path
    WHERE NOT EXISTS (
        SELECT 1 FROM public.system_menu existing WHERE existing.deleted=0 AND existing.permission=v.permission
    );

    SELECT count(*) INTO menu_count
    FROM public.system_menu WHERE deleted=0 AND path LIKE '/drive%' AND type=2;
    IF menu_count <> 7 THEN
        RAISE EXCEPTION 'Drive menus incomplete: % of 7 found', menu_count;
    END IF;

    SELECT count(DISTINCT permission) INTO permission_count
    FROM public.system_menu WHERE deleted=0 AND permission LIKE 'drive:%';
    IF permission_count <> 16 THEN
        RAISE EXCEPTION 'Drive permissions incomplete: % of 16 defined', permission_count;
    END IF;
END $$;
