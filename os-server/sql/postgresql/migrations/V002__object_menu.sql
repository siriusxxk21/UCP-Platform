-- Add only the nocode entry and permissions. Existing roles are not bulk-granted.
DO $$
DECLARE
 parent_id_value bigint;
 object_id_value bigint;
 entry_count integer;
BEGIN
 LOCK TABLE public.system_menu IN SHARE ROW EXCLUSIVE MODE;
 SELECT count(*), min(id) INTO entry_count, parent_id_value
 FROM public.system_menu WHERE deleted=0 AND parent_id=0 AND name='数据中心';
 IF entry_count > 1 THEN RAISE EXCEPTION 'Multiple data-center menus; resolve before installing nocode'; END IF;
 IF parent_id_value IS NULL THEN
   IF EXISTS(SELECT 1 FROM public.system_menu WHERE deleted=0 AND path='/nocode') THEN
     RAISE EXCEPTION 'Menu path /nocode already exists';
   END IF;
   parent_id_value := nextval('public.system_menu_seq');
   INSERT INTO public.system_menu(id,name,permission,type,sort,parent_id,path,icon,component,status,visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
   VALUES(parent_id_value,'数据中心','',1,25,0,'/nocode','DatabaseOutlined',NULL,0,true,true,true,'nocode-migration','nocode-migration',now(),now(),0);
 END IF;
 IF EXISTS(SELECT 1 FROM public.system_menu WHERE deleted=0 AND (path='/nocode/object' OR permission LIKE 'nocode:object:%')) THEN
   RAISE EXCEPTION 'Nocode menu or permission already exists';
 END IF;
 object_id_value := nextval('public.system_menu_seq');
 INSERT INTO public.system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
 VALUES(object_id_value,'数据对象','nocode:object:query',2,1,parent_id_value,'/nocode/object','TableOutlined','nocode/object/index','NocodeObject',0,true,true,true,'nocode-migration','nocode-migration',now(),now(),0);
 INSERT INTO public.system_menu(id,name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
 VALUES
 (nextval('public.system_menu_seq'),'创建对象','nocode:object:create',3,1,object_id_value,'',0,true,false,false,'nocode-migration','nocode-migration',now(),now(),0),
 (nextval('public.system_menu_seq'),'修改对象草稿','nocode:object:update',3,2,object_id_value,'',0,true,false,false,'nocode-migration','nocode-migration',now(),now(),0);
END $$;

