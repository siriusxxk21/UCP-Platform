-- 表级绑定保存在 nocode_object_table.config_json.binding，旧快照缺失时按旧规则解释。
ALTER TABLE public.nocode_index_definition ADD COLUMN parent_scoped boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN public.nocode_index_definition.parent_scoped IS '是否在内部明细父记录范围内建立索引';
