-- V043 后追加任务产品类型。类型不依据子任务数量或模板来源推断，已有明确执行依赖按流程兼容。
-- 验证：任务/模板/不可变版本 kind 非空且限两种值，同一实例各节点一致；不改原任务或记录身份。
ALTER TABLE public.nocode_task_instance ADD COLUMN kind varchar(20) NOT NULL DEFAULT 'ORDINARY';
-- 原模板节点标识供多入口固定发布引用使用，不通过实例 UUID 或表单类型猜测入口来源。
ALTER TABLE public.nocode_task_instance ADD COLUMN template_node_id text;
ALTER TABLE public.nocode_task_template ADD COLUMN kind varchar(20) NOT NULL DEFAULT 'ORDINARY';
ALTER TABLE public.nocode_task_template_version ADD COLUMN kind varchar(20) NOT NULL DEFAULT 'ORDINARY';

UPDATE public.nocode_task_instance t SET kind='PROCESS'
WHERE EXISTS (SELECT 1 FROM public.nocode_task_instance n WHERE n.root_id=t.root_id
    AND (jsonb_array_length(CASE WHEN jsonb_typeof(n.config_json::jsonb->'predecessorIds')='array'
        THEN n.config_json::jsonb->'predecessorIds' ELSE '[]'::jsonb END)>0
        OR n.config_json::jsonb->'schedule'->>'mode'='PREDECESSOR'));
UPDATE public.nocode_task_template t SET kind='PROCESS'
WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(t.nodes_json::jsonb) n
    WHERE jsonb_array_length(CASE WHEN jsonb_typeof(n->'predecessorIds')='array'
        THEN n->'predecessorIds' ELSE '[]'::jsonb END)>0 OR n->'schedule'->>'mode'='PREDECESSOR');
UPDATE public.nocode_task_template_version t SET kind='PROCESS'
WHERE EXISTS (SELECT 1 FROM jsonb_array_elements(t.nodes_json::jsonb) n
    WHERE jsonb_array_length(CASE WHEN jsonb_typeof(n->'predecessorIds')='array'
        THEN n->'predecessorIds' ELSE '[]'::jsonb END)>0 OR n->'schedule'->>'mode'='PREDECESSOR');

ALTER TABLE public.nocode_task_instance ADD CONSTRAINT nocode_task_instance_kind_ck CHECK(kind IN ('ORDINARY','PROCESS'));
ALTER TABLE public.nocode_task_template ADD CONSTRAINT nocode_task_template_kind_ck CHECK(kind IN ('ORDINARY','PROCESS'));
ALTER TABLE public.nocode_task_template_version ADD CONSTRAINT nocode_task_template_version_kind_ck CHECK(kind IN ('ORDINARY','PROCESS'));
CREATE INDEX nocode_task_instance_kind_idx ON public.nocode_task_instance(kind,assignee_id,status,expected_end) WHERE deleted=0;
