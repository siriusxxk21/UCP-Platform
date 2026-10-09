-- 流程任务节点的持久交接记录；引擎与任务仍分别使用各自既有状态机。
CREATE TABLE public.nocode_workflow_task_node (
    id varchar(64) PRIMARY KEY,
    tenant_id bigint NOT NULL DEFAULT 0,
    execution_id varchar(64) NOT NULL,
    process_instance_id varchar(64) NOT NULL,
    process_definition_id varchar(128) NOT NULL,
    node_id varchar(200) NOT NULL,
    node_name varchar(255) NOT NULL,
    initiator_id bigint NOT NULL,
    publisher_id bigint NOT NULL,
    configuration_json text NOT NULL,
    people_json text NOT NULL,
    task_id varchar(64),
    state varchar(32) NOT NULL DEFAULT 'CREATING',
    last_error varchar(1000),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted boolean NOT NULL DEFAULT false,
    CONSTRAINT nocode_workflow_task_node_execution_uk UNIQUE (execution_id, process_instance_id, node_id),
    CONSTRAINT nocode_workflow_task_node_task_uk UNIQUE (task_id),
    CONSTRAINT nocode_workflow_task_node_state_ck CHECK (state IN ('CREATING','WAITING','COMPLETED','INVALIDATED'))
);
CREATE INDEX nocode_workflow_task_node_retry_idx ON public.nocode_workflow_task_node(next_attempt_time)
    WHERE deleted = false AND state IN ('CREATING','WAITING');
CREATE INDEX nocode_workflow_task_node_process_idx ON public.nocode_workflow_task_node(tenant_id,process_instance_id)
    WHERE deleted = false;
COMMENT ON TABLE public.nocode_workflow_task_node IS '工作流任务节点激活与完成交接；失败重试不重复创建或推进';
