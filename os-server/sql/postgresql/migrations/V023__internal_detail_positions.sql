-- DEC-20260912-01：整单内明细排序。前置 V022；不改变已纳管业务表的物理列。
CREATE TABLE public.nocode_detail_position (
    id bigserial PRIMARY KEY,
    object_id bigint NOT NULL REFERENCES public.nocode_object(id) ON DELETE CASCADE,
    detail_id bigint NOT NULL,
    parent_id text NOT NULL,
    record_id text NOT NULL,
    position integer NOT NULL CHECK (position >= 0 AND position < 500),
    creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(object_id, detail_id, parent_id, record_id)
);
CREATE INDEX nocode_detail_position_parent_idx ON public.nocode_detail_position(object_id, detail_id, parent_id, position) WHERE deleted=0;
COMMENT ON TABLE public.nocode_detail_position IS '内部明细在整张单据中的顺序，仅由所属主单据事务维护；旧明细按原查询顺序兼容';
