-- PostgreSQL 手动增量：ac79b59b 已部署基线 -> d738bca0 + 2026-09-25 当前工作区。
-- 唯一业务结构变更来源：V037__object_maintenance_receipts.sql。
-- 不包含基线提交自身的 V036（废弃模块删表），不导入任何全量快照。
-- 不删除或覆盖数据对象、应用配置、业务记录、原有收据。
-- 执行：psql -X -v ON_ERROR_STOP=1 -h 主机 -p 5432 -U 用户 -d 数据库 -f 本文件
-- 发布前暂停应用写入并完成全库备份。脚本必须整体执行，不要选中局部语句运行。
-- 按真实结构检查 V037 前提；只有原历史完整时才追加 V037，历史缺项时不补造或跳号登记。
-- V037 原文件 SHA-256：44ea5bc9143e5ddddbae33b936a86f7a4e82930916719b84150020c635111095
-- V037 原文件 Flyway 11.7.2 checksum：-1670108893（已用项目依赖计算）。

BEGIN;
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

SELECT current_database() AS target_database, current_user AS executing_user,
       inet_server_addr() AS server_address, inet_server_port() AS server_port;

DO $release$
DECLARE
    receipt_table oid := to_regclass('public.nocode_document_receipt');
    request_index oid;
    has_history boolean := to_regclass('public.nocode_schema_history') IS NOT NULL;
    history_complete boolean := false;
    already_recorded boolean := false;
    started_at timestamptz := clock_timestamp();
    column_names text[];
    index_is_correct boolean;
    history_count integer;
BEGIN
    IF receipt_table IS NULL
       OR to_regclass('public.nocode_object') IS NULL
       OR to_regclass('public.nocode_application') IS NULL THEN
        RAISE EXCEPTION '缺少既有平台表，目标库不符合本次增量前提；未执行升级。';
    END IF;

    -- 只校验本次 DDL 依赖的列，不读取用户业务内容。
    IF (SELECT count(*) FROM pg_attribute
        WHERE attrelid = receipt_table AND NOT attisdropped AND attnum > 0
          AND ((attname IN ('application_id', 'object_id') AND atttypid = 'bigint'::regtype)
               OR (attname IN ('creator', 'operation', 'request_key')
                   AND atttypid = 'character varying'::regtype))) <> 5 THEN
        RAISE EXCEPTION '收据表列结构与 V037 前提不一致；未执行升级。';
    END IF;

    IF has_history THEN
        -- 串行化本脚本的历史校验和登记，避免重复版本、重复 installed_rank。
        LOCK TABLE public.nocode_schema_history IN EXCLUSIVE MODE;
        IF EXISTS (SELECT 1 FROM public.nocode_schema_history WHERE NOT success)
           OR EXISTS (SELECT 1 FROM public.nocode_schema_history
                      WHERE CASE WHEN version ~ '^[0-9]+$' THEN version::numeric END > 37) THEN
            RAISE EXCEPTION '迁移历史有失败项或数据库已高于 V037；未执行升级。';
        END IF;
        SELECT NOT EXISTS (
            SELECT 1 FROM generate_series(1, 36) AS expected(version_number)
            WHERE NOT EXISTS (
                SELECT 1 FROM public.nocode_schema_history AS actual
                WHERE ltrim(actual.version, '0') = expected.version_number::text
                  AND actual.success AND actual.type = 'SQL'
            )
        ) AND (SELECT count(*) FROM public.nocode_schema_history
               WHERE version ~ '^0*36$') = 1
          AND EXISTS (SELECT 1 FROM public.nocode_schema_history
                      WHERE version ~ '^0*36$' AND success AND type = 'SQL'
                        AND script = 'V036__remove_knowledge_and_agent_modules.sql'
                        AND checksum = -474266132)
        INTO history_complete;
        IF NOT history_complete THEN
            -- 手工升级环境的历史可能只来自旧快照；不能因此重放旧迁移或伪造历史。
            RAISE NOTICE '历史记录不完整或与原基线不同：本次仅按实际结构执行 V037，保留现有历史，不执行缺失的旧迁移、不跳号追加 V037。';
        END IF;
        SELECT count(*) INTO history_count FROM public.nocode_schema_history
        WHERE version ~ '^0*37$';
        IF history_count > 1 THEN
            RAISE EXCEPTION 'V037 历史重复；未执行升级。';
        END IF;
        already_recorded := history_count = 1;
        IF already_recorded AND NOT EXISTS (
            SELECT 1 FROM public.nocode_schema_history
            WHERE version ~ '^0*37$' AND success AND type = 'SQL'
              AND script = 'V037__object_maintenance_receipts.sql' AND checksum = -1670108893
        ) THEN
            RAISE EXCEPTION '已有 V037 历史与原始迁移不一致；未执行升级。';
        END IF;
    ELSE
        RAISE NOTICE '没有 nocode_schema_history；仅按实际结构检查并升级 V037，不创建历史表或补造旧版本。';
    END IF;

    -- 锁内核对和执行。最多等待 10 秒；不要在此期间启动其他发布或迁移。
    LOCK TABLE public.nocode_document_receipt IN ACCESS EXCLUSIVE MODE;
    request_index := to_regclass('public.nocode_document_receipt_maintenance_request');
    IF request_index IS NOT NULL THEN
        SELECT array_agg(attribute.attname::text ORDER BY key.ordinality)
        INTO column_names
        FROM pg_index AS existing
        CROSS JOIN LATERAL unnest(existing.indkey) WITH ORDINALITY AS key(attnum, ordinality)
        JOIN pg_attribute AS attribute
          ON attribute.attrelid = existing.indrelid AND attribute.attnum = key.attnum
        WHERE existing.indexrelid = request_index;
        SELECT existing.indrelid = receipt_table AND existing.indisunique
               AND existing.indisvalid AND existing.indisready
               AND existing.indnkeyatts = 4 AND existing.indnatts = 4
               AND NOT existing.indnullsnotdistinct AND existing.indexprs IS NULL
               AND pg_get_expr(existing.indpred, existing.indrelid) = '(application_id IS NULL)'
        INTO index_is_correct FROM pg_index AS existing WHERE existing.indexrelid = request_index;
        IF index_is_correct IS DISTINCT FROM true
           OR column_names IS DISTINCT FROM ARRAY['creator', 'object_id', 'operation', 'request_key']::text[] THEN
            RAISE EXCEPTION '同名索引已存在但定义不一致；保留原索引，未执行升级。';
        END IF;
    END IF;

    IF already_recorded THEN
        IF request_index IS NULL OR EXISTS (
            SELECT 1 FROM pg_attribute
            WHERE attrelid = receipt_table AND attname = 'application_id' AND attnotnull
        ) THEN
            RAISE EXCEPTION 'V037 历史与真实结构不一致；未自动修复。';
        END IF;
        RAISE NOTICE 'V037 已执行且结构核对通过；无需重复升级。';
        RETURN;
    END IF;

    -- V037：DROP NOT NULL 只移除非空限制，不是删除字段，不改变已有字段值。
    ALTER TABLE public.nocode_document_receipt ALTER COLUMN application_id DROP NOT NULL;
    IF request_index IS NULL THEN
        CREATE UNIQUE INDEX nocode_document_receipt_maintenance_request
            ON public.nocode_document_receipt (creator, object_id, operation, request_key)
            WHERE application_id IS NULL;
    END IF;

    IF has_history AND history_complete THEN
        INSERT INTO public.nocode_schema_history
            (installed_rank, version, description, type, script, checksum,
             installed_by, installed_on, execution_time, success)
        SELECT COALESCE(max(installed_rank), 0) + 1, '037', 'object maintenance receipts',
               'SQL', 'V037__object_maintenance_receipts.sql', -1670108893, current_user,
               localtimestamp, (extract(epoch FROM clock_timestamp() - started_at) * 1000)::integer, true
        FROM public.nocode_schema_history;
    END IF;
    RAISE NOTICE 'V037 升级完成；既有业务数据和收据保持不变。';
END
$release$;

-- 成功应返回 application_id_allows_null=true，以及有效的唯一索引定义。
SELECT NOT attnotnull AS application_id_allows_null
FROM pg_attribute
WHERE attrelid = 'public.nocode_document_receipt'::regclass
  AND attname = 'application_id' AND NOT attisdropped;

SELECT pg_get_indexdef(indexrelid) AS index_definition, indisunique, indisvalid
FROM pg_index
WHERE indexrelid = to_regclass('public.nocode_document_receipt_maintenance_request');

COMMIT;
