-- 用户组沿用 BaseDO：未指定删除标识的新记录必须是有效记录。
-- 当前底座列为 smallint，使用数据库数值默认值，避免把 Java Boolean 写入数值列。
-- 仅补默认值，不修改既有记录、列类型或逻辑删除规则。
ALTER TABLE public.bpm_user_group ALTER COLUMN deleted SET DEFAULT 0;
