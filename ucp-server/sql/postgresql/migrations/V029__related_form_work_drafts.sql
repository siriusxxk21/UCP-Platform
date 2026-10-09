-- 前置版本：V028。仅为工作草稿新增独立关联输入列；旧草稿默认空对象，保留已有输入。
-- 校验：information_schema.columns 存在 related_json，旧草稿值为对象；任务暂存/恢复联合表单。
ALTER TABLE public.nocode_work_draft
    ADD COLUMN related_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT ck_nocode_work_draft_related_object CHECK (jsonb_typeof(related_json) = 'object');
COMMENT ON COLUMN public.nocode_work_draft.related_json IS '独立关联表单的暂存输入，按发布关联区域标识分组';
