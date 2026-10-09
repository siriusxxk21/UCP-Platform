-- 业务申请只存编排状态；整单意图和审批材料复用 nocode_work_submission 不可变存储。
CREATE TABLE public.nocode_handling_request (
    id uuid PRIMARY KEY,
    application_id bigint NOT NULL,
    application_name varchar(200) NOT NULL,
    application_version integer NOT NULL,
    object_id bigint NOT NULL,
    object_name varchar(200) NOT NULL,
    entry_id varchar(128),
    record_id varchar(512),
    operation varchar(16) NOT NULL CHECK (operation IN ('CREATE','UPDATE')),
    name varchar(300) NOT NULL,
    request_key varchar(128) NOT NULL,
    request_digest varchar(64) NOT NULL,
    definition_checksum varchar(64) NOT NULL,
    definition_json jsonb NOT NULL,
    submission_id varchar(36) NOT NULL REFERENCES public.nocode_work_submission(id),
    process_instance_id varchar(128),
    process_definition_id varchar(128) NOT NULL,
    process_definition_key varchar(128) NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN ('PENDING','APPLY_PENDING','APPROVED','REJECTED','CANCELED','APPLY_FAILED')),
    lock_version integer NOT NULL DEFAULT 0,
    error varchar(1000),
    creator varchar(64) NOT NULL,
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL,
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    UNIQUE (creator,application_id,object_id,request_key)
);
CREATE INDEX nocode_handling_request_actor_idx ON public.nocode_handling_request(creator,status,create_time DESC,id) WHERE deleted=0;
CREATE INDEX nocode_handling_request_record_idx ON public.nocode_handling_request(object_id,record_id,status) WHERE deleted=0;
CREATE UNIQUE INDEX nocode_handling_request_instance_idx ON public.nocode_handling_request(process_instance_id) WHERE deleted=0 AND process_instance_id IS NOT NULL;
COMMENT ON TABLE public.nocode_handling_request IS '无代码业务操作申请：审批终态与业务生效分离';
