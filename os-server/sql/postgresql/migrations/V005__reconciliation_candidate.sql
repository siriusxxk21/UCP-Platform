-- A reconciliation draft may adopt one explicitly reviewed physical snapshot.
-- Any subsequent design save invalidates this grant; publishing clears it.
ALTER TABLE public.nocode_object
  ADD COLUMN reconciliation_hash varchar(64),
  ADD COLUMN reconciliation_version_id bigint REFERENCES public.nocode_object_version(id);
COMMENT ON COLUMN public.nocode_object.reconciliation_hash IS '用户已核对的物理结构摘要，普通保存立即失效';
COMMENT ON COLUMN public.nocode_object.reconciliation_version_id IS '获准同步该物理结构的草稿版本';
