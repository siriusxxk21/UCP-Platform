-- 公共工作草稿与不可变提交证据。业务字段仅在正式提交时写入原业务表。
CREATE TABLE public.nocode_work_draft (
    id varchar(36) PRIMARY KEY,
    source_type varchar(32) NOT NULL,
    source_id varchar(128) NOT NULL,
    state varchar(24) NOT NULL DEFAULT 'DRAFT',
    lock_version integer NOT NULL DEFAULT 0,
    resource_json jsonb NOT NULL,
    object_id varchar(128) NOT NULL,
    record_id varchar(512),
    base_record_revision varchar(256),
    values_json jsonb NOT NULL,
    creator varchar(64) NOT NULL,
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL,
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT nocode_work_draft_state_ck CHECK (state IN ('DRAFT','SUBMITTED')),
    CONSTRAINT nocode_work_draft_revision_ck CHECK (lock_version >= 0),
    CONSTRAINT nocode_work_draft_values_ck CHECK (jsonb_typeof(values_json)='object'),
    CONSTRAINT nocode_work_draft_deleted_ck CHECK (deleted IN (0,1))
);
CREATE INDEX nocode_work_draft_owner_idx
    ON public.nocode_work_draft (creator, state, update_time DESC, id) WHERE deleted=0;
CREATE INDEX nocode_work_draft_source_idx
    ON public.nocode_work_draft (source_type, source_id) WHERE deleted=0;

CREATE TABLE public.nocode_work_submission (
    id varchar(36) PRIMARY KEY,
    draft_id varchar(36) NOT NULL REFERENCES public.nocode_work_draft(id),
    idempotency_key varchar(128) NOT NULL,
    request_digest varchar(64) NOT NULL,
    material_json jsonb NOT NULL,
    creator varchar(64) NOT NULL,
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL,
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT nocode_work_submission_draft_uk UNIQUE (draft_id),
    CONSTRAINT nocode_work_submission_command_uk UNIQUE (creator, idempotency_key),
    CONSTRAINT nocode_work_submission_material_ck CHECK (jsonb_typeof(material_json)='object'),
    CONSTRAINT nocode_work_submission_deleted_ck CHECK (deleted IN (0,1))
);
CREATE INDEX nocode_work_submission_owner_idx
    ON public.nocode_work_submission (creator, create_time DESC, id) WHERE deleted=0;

COMMENT ON TABLE public.nocode_work_draft IS '公共工作草稿；来源资格与实时业务授权由服务层验证';
COMMENT ON TABLE public.nocode_work_submission IS '不可变提交材料；业务服务不提供覆写历史内容接口';
