-- 一次性开发库冲突修复，经用户于 2026-10-06 明确授权；不是通用迁移、不可自动执行。
-- 前提：停止已核实的应用进程；全库备份并校验 SHA-256；记录全部 public 表内容摘要。
-- 仅接受 localhost:5432/devops、V001~V065 加唯一旧任务 V066 成功记录、报表尚未安装。
-- 原 SQL 原样存放 archive-20261005；旧执行行原样归档到独立审计 schema，不伪造报表历史。
-- 在同一事务执行本文件；先回滚演练，再显式提交。随后标准 Flyway 执行 V066~V075。
-- 若升级失败保持服务停止，检查实际成功版本后向前修复；禁止重放旧任务 V066 或全表 repair。
SET LOCAL lock_timeout = '10s';
DO $$
DECLARE
    original_row jsonb;
    affected integer;
BEGIN
    IF current_database() <> 'devops'
       OR inet_server_addr() NOT IN ('127.0.0.1'::inet, '::1'::inet)
       OR inet_server_port() <> 5432 THEN
        RAISE EXCEPTION 'This maintenance is limited to the reviewed local devops database';
    END IF;
    LOCK TABLE public.nocode_schema_history IN ACCESS EXCLUSIVE MODE;
    LOCK TABLE public.nocode_task_template IN SHARE MODE;
    IF (SELECT count(*) FROM public.nocode_schema_history) <> 67
       OR EXISTS (SELECT 1 FROM public.nocode_schema_history WHERE NOT success OR version IS NULL)
       OR (SELECT count(DISTINCT version::integer) FROM public.nocode_schema_history
           WHERE version::integer BETWEEN 1 AND 65) <> 65
       OR NOT EXISTS (SELECT 1 FROM public.nocode_schema_history
                      WHERE version='0' AND type='BASELINE' AND success)
       OR (SELECT count(*) FROM public.nocode_schema_history WHERE version::integer >= 66) <> 1 THEN
        RAISE EXCEPTION 'Migration history no longer matches the reviewed pre-upgrade state';
    END IF;
    SELECT to_jsonb(h) INTO STRICT original_row FROM public.nocode_schema_history h
    WHERE installed_rank=67 AND version='066' AND type='SQL' AND success
      AND description='task template primary version'
      AND script='V066__task_template_primary_version.sql' AND checksum=958506139;
    IF EXISTS (SELECT 1 FROM pg_tables WHERE schemaname='public' AND tablename LIKE 'nocode_report_%')
       OR EXISTS (SELECT 1 FROM public.system_menu WHERE deleted=0
                  AND (path LIKE '/nocode/report-center%' OR permission LIKE 'nocode:report:%')) THEN
        RAISE EXCEPTION 'Report structures or menus already exist; do not replay report migrations';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_attribute
                   WHERE attrelid='public.nocode_task_template'::regclass
                     AND attname='primary_version' AND NOT attisdropped
                     AND atttypid='integer'::regtype AND NOT attnotnull AND NOT atthasdef)
       OR NOT EXISTS (SELECT 1 FROM pg_constraint
                      WHERE conrelid='public.nocode_task_template'::regclass
                        AND conname='nocode_task_template_primary_version_ck'
                        AND convalidated AND contype='c'
                        AND pg_get_constraintdef(oid) = 'CHECK (((primary_version IS NULL) OR ((published_version IS NOT NULL) AND ((primary_version >= 1) AND (primary_version <= published_version)))))') THEN
        RAISE EXCEPTION 'Legacy task migration structure differs from the reviewed script';
    END IF;
    CREATE SCHEMA migration_audit_20261006;
    REVOKE ALL ON SCHEMA migration_audit_20261006 FROM PUBLIC;
    CREATE TABLE migration_audit_20261006.task_v066 (
        original_history jsonb NOT NULL,
        original_sql_sha256 text NOT NULL,
        backup_file text NOT NULL,
        backup_sha256 text NOT NULL,
        archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
        archived_by text NOT NULL DEFAULT current_user,
        reason text NOT NULL
    );
    INSERT INTO migration_audit_20261006.task_v066
        (original_history, original_sql_sha256, backup_file, backup_sha256, reason)
    VALUES (original_row,
        'c8136cfe9b10dbd3a345e553e2f34909833788364444854b5da0349ea47f15aa',
        'sql/full/devops-before-dev-sync-20261006-0105.sql',
        'cb3c7a053819171d901bbe5cf970dc0179abad83c49e95f83f22ac9bad65a88f',
        '用户授权开发库冲突修复：旧任务 V066 原行归档，统一序列 V066~V074 由 Flyway 实际执行，V075 验证并保留已选主版本');
    DELETE FROM public.nocode_schema_history
    WHERE installed_rank=67 AND version='066' AND script='V066__task_template_primary_version.sql'
      AND checksum=958506139 AND success;
    GET DIAGNOSTICS affected = ROW_COUNT;
    IF affected <> 1 THEN
        RAISE EXCEPTION 'Expected to archive exactly one legacy history row';
    END IF;
END $$;
SELECT original_history, original_sql_sha256, backup_sha256
FROM migration_audit_20261006.task_v066;
