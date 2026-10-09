-- 用途：为数据对象与应用增加独立管理分类；前置版本 V029。
-- 影响：仅元数据头新增字段，既有记录归入未分类，不改业务数据和发布快照。
-- 校验：确认两表 category 为 varchar(100)、非空、默认空串，分类筛选与目录接口返回一致。
ALTER TABLE public.nocode_object ADD COLUMN category varchar(100) NOT NULL DEFAULT '';
ALTER TABLE public.nocode_application ADD COLUMN category varchar(100) NOT NULL DEFAULT '';
COMMENT ON COLUMN public.nocode_object.category IS '数据对象管理分类；空字符串为未分类，不进入发布定义';
COMMENT ON COLUMN public.nocode_application.category IS '应用管理分类；空字符串为未分类，不进入发布快照';
CREATE INDEX nocode_object_category_idx ON public.nocode_object(category) WHERE deleted=0;
CREATE INDEX nocode_application_category_idx ON public.nocode_application(category,creator) WHERE deleted=0;
