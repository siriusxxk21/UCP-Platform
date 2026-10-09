-- 关系来源明细随所属对象版本保存；NULL 保持历史主表关系语义。
ALTER TABLE public.nocode_relation ADD COLUMN source_detail_id bigint;
COMMENT ON COLUMN public.nocode_relation.source_detail_id IS '引用来源内部明细稳定 ID；空值表示主表';
ALTER TABLE public.nocode_relation ADD CONSTRAINT nocode_relation_detail_kind
    CHECK (source_detail_id IS NULL OR relation_kind = 'REFERENCE');
CREATE INDEX nocode_relation_detail_source_idx
    ON public.nocode_relation (object_version_id, source_detail_id) WHERE deleted = 0;
