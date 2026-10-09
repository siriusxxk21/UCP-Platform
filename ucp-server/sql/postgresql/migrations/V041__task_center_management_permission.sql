-- 可显式授权的任务管理能力；不默认授予普通角色，仍不扩大业务对象数据权限。
INSERT INTO public.system_menu
 (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
  visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
SELECT nextval('public.system_menu_seq'),'管理全部任务实例','nocode:task:manage-all',3,1,m.id,
 '',NULL,NULL,NULL,0,true,false,false,'task-center-migration','task-center-migration',now(),now(),0
FROM public.system_menu m WHERE m.path='/nocode-app/task-center/manage' AND m.deleted=0
 AND NOT EXISTS (SELECT 1 FROM public.system_menu existing
                 WHERE existing.permission='nocode:task:manage-all' AND existing.deleted=0);
