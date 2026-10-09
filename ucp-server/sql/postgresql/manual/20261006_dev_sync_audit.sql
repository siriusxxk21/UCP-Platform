-- 本轮 dev 同步前后核对；仅创建事务临时表，调用方必须回滚。
-- 输出表级行数和内容摘要，不输出业务记录、密码或连接凭据。
SELECT current_database(), current_setting('server_version');
SELECT row_to_json(h) FROM public.nocode_schema_history h ORDER BY installed_rank;
SELECT a.attname, format_type(a.atttypid,a.atttypmod), a.attnotnull, a.atthasdef
FROM pg_attribute a WHERE a.attrelid='public.nocode_task_template'::regclass
AND a.attname='primary_version' AND NOT a.attisdropped;
SELECT conname, pg_get_constraintdef(oid), convalidated
FROM pg_constraint WHERE conrelid='public.nocode_task_template'::regclass
AND conname='nocode_task_template_primary_version_ck';
CREATE TEMP TABLE dev_sync_fingerprints (table_name text, row_count bigint, content_md5 text) ON COMMIT DROP;
DO $$
DECLARE
    item record;
BEGIN
    FOR item IN SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename LOOP
        EXECUTE format('INSERT INTO dev_sync_fingerprints SELECT %L, count(*), md5(COALESCE(string_agg(row_hash, '''' ORDER BY row_hash), '''')) FROM (SELECT md5(row_to_json(t)::text) row_hash FROM public.%I t) hashes', item.tablename, item.tablename);
    END LOOP;
END $$;
SELECT 'FINGERPRINT', table_name, row_count, content_md5 FROM dev_sync_fingerprints ORDER BY table_name;
SELECT 'PRIMARY_VERSION', id, published_version, primary_version FROM public.nocode_task_template ORDER BY id;
