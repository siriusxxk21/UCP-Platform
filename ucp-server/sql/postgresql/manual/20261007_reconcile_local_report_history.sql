-- 仅适用于 2026-10-07 本机 devops：V001–V051 + 旧报表 V052–V060 共 61 条含基线。
-- 用户授权同步；调用方必须显式开启事务，先回滚演练，再在核验备份及 SQL 字节后提交。
-- 只调整九条已执行报表的 version/script，完整原行归档；不改校验和、时间、排序和业务数据。
-- 后续通过原 Flyway 配置仅本次启用 outOfOrder/group，实际补执行 V052–V065、V075–V078。
SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';
DO $$
DECLARE item record; original_row jsonb; updated_row jsonb; affected integer;
BEGIN
    IF current_database() <> 'devops' THEN RAISE EXCEPTION 'Unexpected database'; END IF;
    LOCK TABLE public.nocode_schema_history IN ACCESS EXCLUSIVE MODE;
    IF (SELECT count(*) FROM public.nocode_schema_history) <> 61
       OR EXISTS (SELECT 1 FROM public.nocode_schema_history WHERE NOT success OR version IS NULL)
       OR (SELECT count(DISTINCT version::integer) FROM public.nocode_schema_history WHERE version::integer BETWEEN 1 AND 60) <> 60
       OR NOT EXISTS (SELECT 1 FROM public.nocode_schema_history WHERE version='0' AND type='BASELINE' AND success)
       OR to_regnamespace('migration_audit_20261007') IS NOT NULL THEN
        RAISE EXCEPTION 'Migration history differs from the reviewed local state';
    END IF;
    IF to_regclass('public.nocode_report_preference') IS NULL
       OR to_regclass('public.nocode_report_dataset') IS NULL
       OR to_regclass('public.nocode_workflow_task_node') IS NOT NULL
       OR EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid='public.nocode_task_instance'::regclass AND attname='application_id' AND NOT attisdropped) THEN
        RAISE EXCEPTION 'Local schema differs from the reviewed pre-sync state';
    END IF;
    CREATE SCHEMA migration_audit_20261007;
    REVOKE ALL ON SCHEMA migration_audit_20261007 FROM PUBLIC;
    CREATE TABLE migration_audit_20261007.report_history (
        old_version varchar(16) PRIMARY KEY,
        new_version varchar(16) NOT NULL UNIQUE,
        original_history jsonb NOT NULL,
        aligned_history jsonb NOT NULL,
        backup_file text NOT NULL,
        backup_sha256 text NOT NULL,
        archived_at timestamptz NOT NULL DEFAULT clock_timestamp(),
        archived_by text NOT NULL DEFAULT current_user,
        reason text NOT NULL
    );
    FOR item IN SELECT * FROM (VALUES
            ('052', '066', 'V052__report_dataset_lifecycle.sql', 'V066__report_dataset_lifecycle.sql', 1087040939),
            ('053', '067', 'V053__report_dataset_authorization.sql', 'V067__report_dataset_authorization.sql', 1015554861),
            ('054', '068', 'V054__report_authorization_dependencies.sql', 'V068__report_authorization_dependencies.sql', 1448404856),
            ('055', '069', 'V055__report_center_menu.sql', 'V069__report_center_menu.sql', 756361659),
            ('056', '070', 'V056__report_dependency_index.sql', 'V070__report_dependency_index.sql', 1472097016),
            ('057', '071', 'V057__report_data_authorization_menu.sql', 'V071__report_data_authorization_menu.sql', -843940888),
            ('058', '072', 'V058__report_dataset_folders.sql', 'V072__report_dataset_folders.sql', 449313904),
            ('059', '073', 'V059__report_dashboards.sql', 'V073__report_dashboards.sql', -1524098978),
            ('060', '074', 'V060__report_dashboard_lifecycle.sql', 'V074__report_dashboard_lifecycle.sql', -541623985)
    ) AS mapping(old_version,new_version,old_script,new_script,expected_checksum) LOOP
        SELECT to_jsonb(h) INTO STRICT original_row FROM public.nocode_schema_history h
        WHERE version=item.old_version AND script=item.old_script AND checksum=item.expected_checksum
          AND type='SQL' AND success
          AND description=replace(substring(item.old_script FROM 7 FOR length(item.old_script)-10),'_',' ');
        UPDATE public.nocode_schema_history
        SET version=item.new_version, script=item.new_script
        WHERE version=item.old_version AND script=item.old_script AND checksum=item.expected_checksum
          AND installed_rank=(original_row->>'installed_rank')::integer AND success AND type='SQL';
        GET DIAGNOSTICS affected=ROW_COUNT;
        IF affected <> 1 THEN RAISE EXCEPTION 'Expected one matching report migration'; END IF;
        SELECT to_jsonb(h) INTO STRICT updated_row FROM public.nocode_schema_history h WHERE version=item.new_version;
        IF (original_row - 'version' - 'script') IS DISTINCT FROM (updated_row - 'version' - 'script') THEN
            RAISE EXCEPTION 'Unexpected history change';
        END IF;
        INSERT INTO migration_audit_20261007.report_history
            (old_version,new_version,original_history,aligned_history,backup_file,backup_sha256,reason)
        VALUES(item.old_version,item.new_version,original_row,updated_row,
            'sql/full/devops-before-local-sync-20261007.sql',
            '6deb28417b6cb4cc7d1523a277c8a520f5869d81bb0e46f3f4cf5d1f119f468b',
            '用户授权本地库同步：报表旧 V052–V060 对齐 V066–V074，保留原 SQL 内容和执行证据，不重复建表');
    END LOOP;
    IF (SELECT count(*) FROM migration_audit_20261007.report_history) <> 9 THEN
        RAISE EXCEPTION 'Expected nine archived report histories';
    END IF;
END $$;
