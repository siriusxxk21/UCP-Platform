-- DEC-20260912-01：填写草稿保留内部明细完整输入；旧草稿缺省为空组。
ALTER TABLE public.nocode_work_draft ADD COLUMN details_json jsonb NOT NULL DEFAULT '{}'::jsonb;
COMMENT ON COLUMN public.nocode_work_draft.details_json IS '主单据的完整明细输入集合，含稳定临时行键、原记录 ID 及修订';
