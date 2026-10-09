-- 阿里云 osserver 覆盖部署前的只读核对，不包含 DROP / DELETE / UPDATE / INSERT。
-- 用途：核对人员组织表与依赖的真实结构，不能用本地快照推定云端状态。
-- 不查询用户姓名、密码、手机号、组织名称等业务内容；estimated_rows 仅为统计估算。
-- FinalShell：psql -X -d osserver -v ON_ERROR_STOP=1 -At -f 本文件 > preflight.json
WITH protected(name) AS (
    VALUES ('system_users'), ('system_dept'), ('system_organization'),
           ('system_user_dept'), ('system_user_post'), ('system_user_role'),
           ('system_post'), ('system_role'), ('system_role_menu'),
           ('system_tenant'), ('system_tenant_package'),
           ('system_social_user'), ('system_social_user_bind')
), relations AS (
    SELECT p.name, c.oid, c.relkind, c.reltuples,
           CASE WHEN c.oid IS NOT NULL THEN pg_get_userbyid(c.relowner) END AS owner
    FROM protected p
    LEFT JOIN pg_namespace n ON n.nspname = 'public'
    LEFT JOIN pg_class c ON c.relnamespace = n.oid AND c.relname = p.name
), identity_metadata AS (
    SELECT r.name, jsonb_build_object(
        'exists', r.oid IS NOT NULL,
        'kind', r.relkind,
        'owner', r.owner,
        'estimated_rows', r.reltuples,
        'columns', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'name', a.attname,
                'type', format_type(a.atttypid, a.atttypmod),
                'not_null', a.attnotnull,
                'identity', a.attidentity,
                'generated', a.attgenerated,
                'default', pg_get_expr(d.adbin, d.adrelid)
            ) ORDER BY a.attnum)
            FROM pg_attribute a
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE a.attrelid = r.oid AND a.attnum > 0 AND NOT a.attisdropped
        ), '[]'::jsonb),
        'constraints', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'name', c.conname, 'type', c.contype,
                'definition', pg_get_constraintdef(c.oid)
            ) ORDER BY c.conname)
            FROM pg_constraint c WHERE c.conrelid = r.oid
        ), '[]'::jsonb),
        'indexes', COALESCE((
            SELECT jsonb_agg(jsonb_build_object(
                'name', c.relname, 'definition', pg_get_indexdef(i.indexrelid)
            ) ORDER BY c.relname)
            FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
            WHERE i.indrelid = r.oid
        ), '[]'::jsonb)
    ) AS metadata
    FROM relations r
)
SELECT jsonb_pretty(jsonb_build_object(
    'checked_at', current_timestamp,
    'database', current_database(),
    'server_version', current_setting('server_version'),
    'server_version_num', current_setting('server_version_num'),
    'connected_role', current_user,
    'database_owner', (SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()),
    'identity_tables', (SELECT jsonb_object_agg(name, metadata ORDER BY name) FROM identity_metadata),
    'related_sequences', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
            'name', s.sequencename, 'owner', s.sequenceowner,
            'type', s.data_type::text, 'start', s.start_value::text,
            'min', s.min_value::text, 'max', s.max_value::text,
            'increment', s.increment_by::text, 'cycle', s.cycle
        ) ORDER BY s.sequencename)
        FROM pg_sequences s
        WHERE s.schemaname = 'public' AND EXISTS (
            SELECT 1 FROM protected p WHERE starts_with(s.sequencename, p.name || '_')
        )
    ), '[]'::jsonb),
    'foreign_keys_touching_identity', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
            'table', c.conrelid::regclass::text,
            'referenced_table', c.confrelid::regclass::text,
            'name', c.conname, 'definition', pg_get_constraintdef(c.oid)
        ) ORDER BY c.conrelid::regclass::text, c.conname)
        FROM pg_constraint c
        WHERE c.contype = 'f' AND (
            c.conrelid IN (SELECT oid FROM relations WHERE oid IS NOT NULL)
            OR c.confrelid IN (SELECT oid FROM relations WHERE oid IS NOT NULL)
        )
    ), '[]'::jsonb),
    'user_tables', COALESCE((
        SELECT jsonb_agg(jsonb_build_object('schema', schemaname, 'table', tablename, 'owner', tableowner)
                         ORDER BY schemaname, tablename)
        FROM pg_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
                         AND schemaname NOT LIKE 'pg_toast%'
    ), '[]'::jsonb),
    'extensions', COALESCE((
        SELECT jsonb_agg(jsonb_build_object('name', extname, 'version', extversion) ORDER BY extname)
        FROM pg_extension
    ), '[]'::jsonb)
));
