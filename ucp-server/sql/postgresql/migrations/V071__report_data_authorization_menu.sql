-- 复用对象共享权限，提供独立于报表制作权限的授权入口；不为现有角色自动授予菜单。
DO $$
DECLARE
    entry_id bigint;
BEGIN
    LOCK TABLE public.system_menu IN SHARE ROW EXCLUSIVE MODE;
    SELECT id INTO STRICT entry_id FROM public.system_menu
        WHERE path='/nocode/report-center' AND deleted=0;
    IF EXISTS (SELECT 1 FROM public.system_menu WHERE deleted=0
               AND path='/nocode/report-center/data-authorization') THEN
        RAISE EXCEPTION 'Report data authorization menu already exists; review before migration';
    END IF;
    INSERT INTO public.system_menu
    (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
     visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
    VALUES
    (nextval('public.system_menu_seq'),'数据授权','nocode:object:share',2,2,entry_id,
     '/nocode/report-center/data-authorization','SafetyCertificateOutlined',
     'nocode/report-center/data-authorization','NocodeReportDataAuthorization',0,
     true,false,true,'report-menu-migration','report-menu-migration',now(),now(),0);
END $$;
