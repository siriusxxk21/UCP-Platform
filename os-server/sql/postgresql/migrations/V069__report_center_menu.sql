-- 报表中心与数据中心、应用中心并列；仅注册已实现的数据集页面和权限定义。
-- 不给任何既有普通角色自动授权，资源权限与数据权限仍由各自策略控制。
DO $$
DECLARE
    entry_id bigint;
    dataset_id bigint;
BEGIN
    LOCK TABLE public.system_menu IN SHARE ROW EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM public.system_menu WHERE deleted=0
               AND (path='/nocode/report-center' OR permission LIKE 'nocode:report:%')) THEN
        RAISE EXCEPTION 'Report-center menu or permissions already exist; review before migration';
    END IF;
    entry_id := nextval('public.system_menu_seq');
    dataset_id := nextval('public.system_menu_seq');
    INSERT INTO public.system_menu
    (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
     visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
    VALUES
    (entry_id,'报表中心','',1,28,0,'/nocode/report-center','BarChartOutlined',NULL,NULL,0,
     true,true,true,'report-menu-migration','report-menu-migration',now(),now(),0),
    (dataset_id,'数据集','nocode:report:query',2,1,entry_id,'/nocode/report-center/datasets',
     'DatabaseOutlined','nocode/report-center/datasets','NocodeReportDatasets',0,
     true,false,true,'report-menu-migration','report-menu-migration',now(),now(),0);
    INSERT INTO public.system_menu
    (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
     visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
    SELECT nextval('public.system_menu_seq'),v.name,v.permission,3,v.sort,dataset_id,'',NULL,NULL,NULL,0,
           false,false,false,'report-menu-migration','report-menu-migration',now(),now(),0
    FROM (VALUES
        ('创建数据集','nocode:report:create',1),
        ('编辑数据集','nocode:report:update',2),
        ('发布数据集','nocode:report:publish',3),
        ('管理数据集','nocode:report:manage',4),
        ('数据集成员授权','nocode:report:authorize',5)
    ) AS v(name,permission,sort);
END $$;
