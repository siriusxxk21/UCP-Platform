-- 统一无代码领域命名；使用 ALTER 保留数据、外键依赖和序列当前位置。
-- V001/V002 已经执行，保持原文件和校验和不变，由本次增量迁移升级。
SET LOCAL lock_timeout = '10s';

ALTER TABLE public.lc_object RENAME TO nocode_object;
ALTER TABLE public.lc_object_version RENAME TO nocode_object_version;
ALTER TABLE public.lc_object_table RENAME TO nocode_object_table;
ALTER TABLE public.lc_field RENAME TO nocode_field;
ALTER TABLE public.aud_operation_log RENAME TO nocode_operation_log;
ALTER SEQUENCE public.lc_stable_id_seq RENAME TO nocode_stable_id_seq;

DO $$
DECLARE
    item record;
BEGIN
    -- 表改名不会自动改变约束名；重命名约束时 PostgreSQL 会同步其支撑索引。
    FOR item IN
        SELECT c.conrelid::regclass AS relation, c.conname,
               regexp_replace(c.conname, '^(lc_|aud_)', 'nocode_') AS new_name
        FROM pg_constraint c
        JOIN pg_class t ON t.oid = c.conrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'public'
          AND t.relname IN ('nocode_object', 'nocode_object_version', 'nocode_object_table',
                            'nocode_field', 'nocode_operation_log')
          AND c.conname ~ '^(lc_|aud_)'
    LOOP
        EXECUTE format('ALTER TABLE %s RENAME CONSTRAINT %I TO %I',
                       item.relation, item.conname, item.new_name);
    END LOOP;

    FOR item IN
        SELECT i.relname, regexp_replace(i.relname, '^(lc_|aud_)', 'nocode_') AS new_name
        FROM pg_index x
        JOIN pg_class t ON t.oid = x.indrelid
        JOIN pg_class i ON i.oid = x.indexrelid
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'public'
          AND t.relname IN ('nocode_object', 'nocode_object_version', 'nocode_object_table',
                            'nocode_field', 'nocode_operation_log')
          AND i.relname ~ '^(lc_|aud_)'
    LOOP
        EXECUTE format('ALTER INDEX public.%I RENAME TO %I', item.relname, item.new_name);
    END LOOP;
END $$;

ALTER SEQUENCE public.lc_object_id_seq RENAME TO nocode_object_id_seq;
ALTER SEQUENCE public.lc_object_version_id_seq RENAME TO nocode_object_version_id_seq;
ALTER SEQUENCE public.lc_object_table_id_seq RENAME TO nocode_object_table_id_seq;
ALTER SEQUENCE public.lc_field_id_seq RENAME TO nocode_field_id_seq;
ALTER SEQUENCE public.aud_operation_log_id_seq RENAME TO nocode_operation_log_id_seq;

-- B1 只有未发布的 GENERATED 单主表草稿。仅转换这类候选名称，不修改被纳管的底座表。
-- 后续阶段若存在其他状态，必须单独制定迁移，不能把已发布业务表当作候选名称改写。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.nocode_object_table t
        JOIN public.nocode_object_version v ON v.id = t.object_version_id
        JOIN public.nocode_object o ON o.id = v.object_id
        WHERE t.table_name !~ '^nocode_data_[a-z][a-z0-9_]*$'
          AND (o.source_type <> 'GENERATED' OR o.status <> 'DRAFT'
               OR o.current_published_version_no IS NOT NULL OR v.state <> 'DRAFT'
               OR t.table_role <> 'MAIN' OR to_regclass('public.' || t.table_name) IS NOT NULL)
    ) THEN
        RAISE EXCEPTION 'Non-draft or existing business tables require a dedicated migration';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.nocode_object_table
        WHERE table_name !~ '^nocode_data_[a-z][a-z0-9_]*$'
          AND ('nocode_data_' || regexp_replace(table_name, '^b_', '')
                 !~ '^nocode_data_[a-z][a-z0-9_]*$'
               OR length('nocode_data_' || regexp_replace(table_name, '^b_', '')) > 63)
    ) THEN
        RAISE EXCEPTION 'Candidate table name cannot fit the nocode_data_ namespace';
    END IF;

    IF EXISTS (
        SELECT 1 FROM public.nocode_object_table t
        WHERE t.table_name !~ '^nocode_data_[a-z][a-z0-9_]*$'
          AND (to_regclass('public.nocode_data_' || regexp_replace(t.table_name, '^b_', '')) IS NOT NULL
               OR EXISTS (
                   SELECT 1 FROM public.nocode_object_table other
                   WHERE other.id <> t.id
                     AND CASE WHEN other.table_name ~ '^nocode_data_'
                              THEN other.table_name
                              ELSE 'nocode_data_' || regexp_replace(other.table_name, '^b_', '') END
                         = 'nocode_data_' || regexp_replace(t.table_name, '^b_', '')
               ))
    ) THEN
        RAISE EXCEPTION 'Candidate table name collides in the nocode_data_ namespace';
    END IF;
END $$;

WITH renamed AS (
    UPDATE public.nocode_object_table
    SET table_name = 'nocode_data_' || regexp_replace(table_name, '^b_', '')
    WHERE table_name !~ '^nocode_data_[a-z][a-z0-9_]*$'
    RETURNING object_version_id, table_name
), version_changed AS (
    UPDATE public.nocode_object_version v
    SET schema_json = jsonb_set(v.schema_json, '{tableName}', to_jsonb(r.table_name)),
        schema_checksum = encode(sha256(convert_to(
            jsonb_set(v.schema_json, '{tableName}', to_jsonb(r.table_name))::text, 'UTF8')), 'hex'),
        lock_version = v.lock_version + 1
    FROM renamed r WHERE v.id = r.object_version_id
    RETURNING v.object_id
)
UPDATE public.nocode_object o
SET lock_version = o.lock_version + 1, updated_at = now()
WHERE o.id IN (SELECT object_id FROM version_changed);

-- 约束覆盖未来直接写入，服务层同时给出中文提示。底座原有表不受这些领域约束影响。
ALTER TABLE public.nocode_object_table
    ADD CONSTRAINT nocode_object_table_generated_name CHECK (table_name ~ '^nocode_data_[a-z][a-z0-9_]*$');

COMMENT ON TABLE public.nocode_operation_log IS
    '无代码对象修订的领域留痕，与元数据同事务；通用操作日志继续复用底座';
