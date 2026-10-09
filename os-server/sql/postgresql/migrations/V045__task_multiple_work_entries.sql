-- 执行任务多入口与贡献记录：稳定入口 key 独立分组，业务值继续存公共记录。
CREATE TABLE public.nocode_task_entry_binding (
 id varchar(64) PRIMARY KEY, task_id varchar(64) NOT NULL REFERENCES public.nocode_task_instance(id),
 entry_key varchar(80) NOT NULL, dataset_id varchar(160) NOT NULL, config_json text NOT NULL,
 business_json text NOT NULL, inherited boolean NOT NULL DEFAULT false, submitted_json text,
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0,
 UNIQUE(task_id,entry_key)
);
CREATE INDEX nocode_task_entry_binding_dataset_idx ON public.nocode_task_entry_binding(dataset_id) WHERE deleted=0;
CREATE TABLE public.nocode_task_entry_record (
 id varchar(64) PRIMARY KEY, task_id varchar(64) NOT NULL REFERENCES public.nocode_task_instance(id),
 entry_key varchar(80) NOT NULL, dataset_id varchar(160) NOT NULL, business_json text NOT NULL,
 operation varchar(20) NOT NULL CHECK(operation IN ('CREATED','UPDATED','LINKED')),
 superseded_by varchar(64), record_revision varchar(200), snapshot_json text, request_key varchar(120) NOT NULL, request_hash varchar(64) NOT NULL,
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0,
 UNIQUE(creator,request_key)
);
CREATE INDEX nocode_task_entry_record_dataset_idx ON public.nocode_task_entry_record(dataset_id,create_time) WHERE deleted=0;
CREATE INDEX nocode_task_entry_record_request_idx ON public.nocode_task_entry_record((business_json::jsonb->>'requestId')) WHERE deleted=0;
CREATE TABLE public.nocode_task_entry_template_version (
 id varchar(64) PRIMARY KEY, template_id varchar(64) NOT NULL, version_no integer NOT NULL, bindings_json text NOT NULL,
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(), updater varchar(64) NOT NULL,
 update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0, UNIQUE(template_id,version_no)
);
