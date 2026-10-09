-- 正式布局只有 L1/L2 两层，任务中心必须作为独立 L1。
-- 只移动 V040 创建的任务中心目录，保留四子菜单、权限绑定与既有办理 URL。
UPDATE public.system_menu
SET parent_id=0, sort=6, updater='task-center-migration', update_time=now()
WHERE path='/task-center' AND type=1 AND creator='task-center-migration' AND deleted=0;
