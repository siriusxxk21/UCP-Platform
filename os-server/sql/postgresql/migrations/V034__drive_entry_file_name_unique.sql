-- 网盘文件同名的并发保护。前置 V033；V032 已用部分唯一索引约束目录同名，文件侧只有“先查后插”，
-- 同一目录并发上传同名文件会各写一份，节点路径因此不再唯一，删除与重命名会落到错误节点。
-- 这里按与目录相同的口径补文件侧唯一索引，冲突由服务侧换名重试消化。
-- 校验：同一目录内并发上传同名文件后，落库名称互不相同且各自可独立改名、删除。
CREATE UNIQUE INDEX drive_entry_file_name_uk
    ON public.drive_entry(space_id,parent_id,name)
    WHERE deleted=0 AND type='FILE' AND trash_state='NORMAL';
