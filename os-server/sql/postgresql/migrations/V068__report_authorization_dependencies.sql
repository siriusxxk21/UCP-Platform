-- 为已有授权补登记条件字段；不修改授权内容、发布版本或业务记录。
WITH RECURSIVE grants AS (
    SELECT d.id AS dataset_id,d.name,g.object_id::text AS object_id,
           d.id::text || ':ceiling:' || g.object_id::text AS source_key,
           d.name || ' [ceiling:' || g.object_id::text || ']' AS source_name,g.grant_json AS permission
    FROM public.nocode_report_object_grant g
    JOIN public.nocode_report_dataset d ON d.id=g.dataset_id AND d.deleted=0
    WHERE g.deleted=0 AND g.grant_json IS NOT NULL AND g.grant_json <> 'null'::jsonb
    UNION ALL
    SELECT d.id,d.name,permission->>'objectId',d.id::text || ':policy',d.name || ' [policy]',permission
    FROM public.nocode_report_dataset_policy p
    JOIN public.nocode_report_dataset d ON d.id=p.dataset_id AND d.deleted=0
    CROSS JOIN LATERAL jsonb_array_elements(p.members_json) member
    CROSS JOIN LATERAL jsonb_array_elements(member->'objects') permission
    WHERE p.deleted=0
), scopes AS (
    SELECT dataset_id,object_id,source_key,source_name,entry.value AS scope
    FROM grants CROSS JOIN LATERAL jsonb_each(COALESCE(permission->'actionScopes','{}'::jsonb)) entry
    UNION ALL
    SELECT s.dataset_id,s.object_id,s.source_key,s.source_name,child
    FROM scopes s CROSS JOIN LATERAL jsonb_array_elements(COALESCE(s.scope->'groups','[]'::jsonb)) child
), fields AS (
    SELECT object_id,source_key,source_name,condition->>'fieldId' AS field_id
    FROM scopes CROSS JOIN LATERAL jsonb_array_elements(COALESCE(scope->'conditions','[]'::jsonb)) condition
)
INSERT INTO public.nocode_resource_dependency(source_kind,source_key,source_name,target_object_id,field_ids_json,creator,updater)
SELECT 'DATASET',source_key,source_name,object_id::bigint,jsonb_agg(DISTINCT field_id ORDER BY field_id),'migration:V054','migration:V054'
FROM fields WHERE field_id IS NOT NULL GROUP BY object_id,source_key,source_name
ON CONFLICT(source_kind,source_key,target_object_id) DO UPDATE
SET source_name=excluded.source_name,field_ids_json=excluded.field_ids_json,deleted=0,
    updater=excluded.updater,update_time=CURRENT_TIMESTAMP;
