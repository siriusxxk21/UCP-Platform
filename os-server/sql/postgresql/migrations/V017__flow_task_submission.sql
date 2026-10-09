-- 流程任务固定绑定及事务事件。前置 V016；不更改既有模型、实例或业务数据。
-- 校验：两张表的约束、正式来源提交/失败回滚/重复提交/普通入口保护集成测试。
CREATE TABLE public.nocode_flow_task_binding (
    task_id varchar(128) PRIMARY KEY,
    task_json jsonb NOT NULL,
    submission_id varchar(36) REFERENCES public.nocode_work_submission(id),
    submitter varchar(64),
    request_digest varchar(64),
    creator varchar(64) NOT NULL,
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL,
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT nocode_flow_task_binding_json_ck CHECK (jsonb_typeof(task_json)='object'),
    CONSTRAINT nocode_flow_task_binding_submission_uk UNIQUE (submission_id),
    CONSTRAINT nocode_flow_task_binding_result_ck CHECK (
        (submission_id IS NULL AND submitter IS NULL AND request_digest IS NULL)
        OR (submission_id IS NOT NULL AND submitter IS NOT NULL AND request_digest IS NOT NULL)),
    CONSTRAINT nocode_flow_task_binding_deleted_ck CHECK (deleted IN (0,1))
);
CREATE INDEX nocode_flow_task_binding_instance_idx
    ON public.nocode_flow_task_binding ((task_json->>'processInstanceId')) WHERE deleted=0;

CREATE TABLE public.nocode_work_event (
    id varchar(36) PRIMARY KEY,
    event_type varchar(32) NOT NULL,
    source_type varchar(32) NOT NULL,
    source_id varchar(128) NOT NULL,
    submission_id varchar(36) NOT NULL REFERENCES public.nocode_work_submission(id),
    creator varchar(64) NOT NULL,
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL,
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT nocode_work_event_submission_uk UNIQUE (event_type,submission_id),
    CONSTRAINT nocode_work_event_deleted_ck CHECK (deleted IN (0,1))
);
CREATE INDEX nocode_work_event_source_idx
    ON public.nocode_work_event (source_type,source_id,create_time,id) WHERE deleted=0;
CREATE INDEX nocode_work_submission_record_idx
    ON public.nocode_work_submission ((material_json->>'objectId'),(material_json->>'recordId')) WHERE deleted=0;

COMMENT ON TABLE public.nocode_flow_task_binding IS '真实引擎任务固定资源与提交关联，不拥有另一套任务状态';
COMMENT ON TABLE public.nocode_work_event IS '与业务提交同事务的事件事实；当前仅登记，后续接入可靠投递';
