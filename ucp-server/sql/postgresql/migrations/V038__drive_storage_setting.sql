-- 网盘独立存储源选择。未指定时沿用既有文件主配置，保留所有存量文件的 config_id。
CREATE TABLE public.drive_storage_setting (
    id bigint PRIMARY KEY CHECK (id = 1),
    config_id bigint,
    creator varchar(64) DEFAULT '', create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '', update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1))
);
INSERT INTO public.drive_storage_setting (id, config_id, creator, updater)
VALUES (1, NULL, 'drive-storage-migration', 'drive-storage-migration');
COMMENT ON TABLE public.drive_storage_setting IS '网盘后续写入使用的文件配置；空值沿用平台主配置';
COMMENT ON COLUMN public.drive_storage_setting.config_id IS '引用 infra_file_config.id；旧文件仍使用各自 infra_file.config_id';

-- 权限只补定义，不自动扩大存量角色授权。
INSERT INTO public.system_menu
(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
 visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
SELECT nextval('public.system_menu_seq'),v.name,v.permission,3,v.sort,parent.id,'',NULL,NULL,NULL,0,
       false,false,false,'drive-storage-migration','drive-storage-migration',now(),now(),0
FROM (VALUES
    ('查看网盘存储配置','drive:storage:query',8),
    ('切换网盘存储源','drive:storage:update',9)
) AS v(name,permission,sort)
JOIN public.system_menu parent ON parent.deleted=0 AND parent.type=2 AND parent.path='/drive/space'
WHERE NOT EXISTS (SELECT 1 FROM public.system_menu existing
                  WHERE existing.deleted=0 AND existing.permission=v.permission);
