-- PostgreSQL 增量：已执行旧 upgrade.sql（V037） -> V038 + V039。
-- 配合原 run-sql.sh：sh run-sql.sh upgrade_20260927_v038_v039.sql
-- 事务由原脚本的 psql --single-transaction 管理；请整体执行，不截取语句。
-- 沿用原脚本的全库备份；发布期间暂停应用写入及其他迁移/发布操作。
-- 仅新增网盘存储配置、两个权限定义、有序公式校准状态表。
-- 不修改旧文件存储归属，不授予角色权限，不改业务记录或自动回填公式。
-- 可重复执行：保留已有存储配置、公式校准状态和进度；结构不匹配则报错回滚。
-- 兼容旧脚本：迁移历史缺失/不完整时仅按结构升级，不创建历史、不补造旧版本。
-- V038 原文件 SHA-256：3c1adcbddc74575daf6252467a670ae76d09e380433b5680256ba25b6e9bfa99
-- V038 Flyway checksum：-1114903555
-- V039 原文件 SHA-256：073fd405102213a5d77aebb12e7863ea9de37c82fc9aa723fc1456270e655e0e
-- V039 Flyway checksum：1765820567

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

SELECT current_database() AS target_database, current_user AS executing_user,
       inet_server_addr() AS server_address, inet_server_port() AS server_port;

DO $release$
DECLARE
    has_history boolean := to_regclass('public.nocode_schema_history') IS NOT NULL;
    history_complete boolean := false;
    recorded_38 boolean := false;
    recorded_39 boolean := false;
    started_at timestamptz := clock_timestamp();
    receipt_table oid := to_regclass('public.nocode_document_receipt');
    request_index oid := to_regclass('public.nocode_document_receipt_maintenance_request');
    checked_table oid;
    index_correct boolean;
    column_names text[];
    parent_menu_id bigint;
    history_count integer;
    expected record;
BEGIN
    -- 同一数据库内串行化本增量，缺少历史表时也避免两个手工升级互相竞争。
    PERFORM pg_advisory_xact_lock(20260927, 3839);
    IF receipt_table IS NULL OR to_regclass('public.nocode_object') IS NULL
       OR to_regclass('public.nocode_application') IS NULL
       OR to_regclass('public.infra_file_config') IS NULL
       OR to_regclass('public.system_menu') IS NULL
       OR to_regclass('public.system_menu_seq') IS NULL THEN
        RAISE EXCEPTION '缺少既有平台表或菜单序列，目标库不符合本次增量前提。';
    END IF;

    -- 不重复执行 V037：实际结构必须已经包含旧 upgrade.sql 的结果。
    LOCK TABLE public.nocode_document_receipt IN ACCESS SHARE MODE;
    SELECT i.indrelid = receipt_table AND i.indisunique AND i.indisvalid AND i.indisready
           AND i.indnkeyatts = 4 AND i.indnatts = 4 AND NOT i.indnullsnotdistinct
           AND i.indexprs IS NULL AND pg_get_expr(i.indpred, i.indrelid) = '(application_id IS NULL)'
    INTO index_correct FROM pg_index i WHERE i.indexrelid = request_index;
    SELECT array_agg(a.attname::text ORDER BY k.ordinality) INTO column_names
    FROM pg_index i
    CROSS JOIN LATERAL unnest(i.indkey) WITH ORDINALITY k(attnum, ordinality)
    JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
    WHERE i.indexrelid = request_index;
    IF index_correct IS DISTINCT FROM true
       OR column_names IS DISTINCT FROM ARRAY['creator','object_id','operation','request_key']::text[]
       OR NOT EXISTS (SELECT 1 FROM pg_attribute WHERE attrelid = receipt_table
                      AND attname = 'application_id' AND NOT attisdropped
                      AND atttypid = 'bigint'::regtype AND NOT attnotnull) THEN
        RAISE EXCEPTION 'V037 实际结构未就绪；请先执行之前的 upgrade.sql，本次仅包含 V038、V039。';
    END IF;

    IF has_history THEN
        LOCK TABLE public.nocode_schema_history IN EXCLUSIVE MODE;
        IF EXISTS (SELECT 1 FROM public.nocode_schema_history WHERE NOT success)
           OR EXISTS (SELECT 1 FROM public.nocode_schema_history
                      WHERE CASE WHEN version ~ '^[0-9]+$' THEN version::numeric END > 39) THEN
            RAISE EXCEPTION '迁移历史有失败项或数据库已高于 V039；停止升级。';
        END IF;
        FOR expected IN SELECT * FROM (VALUES
            (37, 'V037__object_maintenance_receipts.sql', -1670108893),
            (38, 'V038__drive_storage_setting.sql', -1114903555),
            (39, 'V039__ordered_calculation_state.sql', 1765820567)
        ) v(version_number, script_name, checksum_value) LOOP
            SELECT count(*) INTO history_count FROM public.nocode_schema_history
            WHERE ltrim(version, '0') = expected.version_number::text;
            IF history_count > 1 OR (history_count = 1 AND NOT EXISTS (
                SELECT 1 FROM public.nocode_schema_history
                WHERE ltrim(version, '0') = expected.version_number::text AND success AND type = 'SQL'
                  AND script = expected.script_name AND checksum = expected.checksum_value
            )) THEN
                RAISE EXCEPTION 'V% 已有迁移历史重复或与原始迁移不一致。', expected.version_number;
            END IF;
            IF expected.version_number = 38 THEN recorded_38 := history_count = 1; END IF;
            IF expected.version_number = 39 THEN recorded_39 := history_count = 1; END IF;
        END LOOP;
        SELECT NOT EXISTS (
            SELECT 1 FROM generate_series(1, 37) e(version_number)
            WHERE (SELECT count(*) FROM public.nocode_schema_history h
                   WHERE ltrim(h.version, '0') = e.version_number::text) <> 1
               OR NOT EXISTS (SELECT 1 FROM public.nocode_schema_history h
                              WHERE ltrim(h.version, '0') = e.version_number::text
                                AND h.success AND h.type = 'SQL')
        ) AND EXISTS (SELECT 1 FROM public.nocode_schema_history
                      WHERE version ~ '^0*36$' AND script = 'V036__remove_knowledge_and_agent_modules.sql'
                        AND checksum = -474266132)
          AND NOT (recorded_39 AND NOT recorded_38)
        INTO history_complete;
        IF NOT history_complete THEN
            RAISE NOTICE '历史不完整：仅按实际结构升级 V038/V039，保留现有历史，不补造或跳号登记。';
        END IF;
    ELSE
        RAISE NOTICE '没有迁移历史表：仅按实际结构升级 V038/V039，不创建历史表或补造旧版本。';
    END IF;

    -- 已登记过迁移但表被移除属于异常，不自动重建空表掩盖数据缺失。
    IF (recorded_38 AND to_regclass('public.drive_storage_setting') IS NULL)
       OR (recorded_39 AND to_regclass('public.nocode_ordered_calculation_state') IS NULL) THEN
        RAISE EXCEPTION 'V038/V039 历史与实际表结构不一致，停止升级。';
    END IF;

    LOCK TABLE public.system_menu IN SHARE ROW EXCLUSIVE MODE;
    SELECT min(id) INTO parent_menu_id FROM public.system_menu
    WHERE deleted = 0 AND type = 2 AND path = '/drive/space';
    IF (SELECT count(*) FROM public.system_menu
        WHERE deleted = 0 AND type = 2 AND path = '/drive/space') <> 1 THEN
        RAISE EXCEPTION '网盘管理菜单缺失或重复，无法准确挂载新增权限。';
    END IF;
    FOR expected IN SELECT * FROM (VALUES ('drive:storage:query'), ('drive:storage:update')) v(permission_code) LOOP
        IF (SELECT count(*) FROM public.system_menu
            WHERE deleted = 0 AND permission = expected.permission_code) > 1
           OR EXISTS (SELECT 1 FROM public.system_menu WHERE deleted = 0
                      AND permission = expected.permission_code
                      AND (type IS DISTINCT FROM 3 OR parent_id IS DISTINCT FROM parent_menu_id)) THEN
            RAISE EXCEPTION '已有权限 % 重复或挂载位置不一致，保留原配置并停止升级。', expected.permission_code;
        END IF;
        IF recorded_38 AND NOT EXISTS (SELECT 1 FROM public.system_menu
                                      WHERE deleted = 0 AND permission = expected.permission_code) THEN
            RAISE EXCEPTION 'V038 已登记但权限 % 缺失，停止升级。', expected.permission_code;
        END IF;
    END LOOP;

    -- V038 原始 DDL；已有表先保留，下面完整核对列和约束，不能仅 IF NOT EXISTS 静默跳过。
    IF to_regclass('public.drive_storage_setting') IS NULL THEN
        CREATE TABLE public.drive_storage_setting (
            id bigint PRIMARY KEY CHECK (id = 1),
            config_id bigint,
            creator varchar(64) DEFAULT '', create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updater varchar(64) DEFAULT '', update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
            deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1))
        );
        COMMENT ON TABLE public.drive_storage_setting IS '网盘后续写入使用的文件配置；空值沿用平台主配置';
        COMMENT ON COLUMN public.drive_storage_setting.config_id IS '引用 infra_file_config.id；旧文件仍使用各自 infra_file.config_id';
    END IF;

    -- V039 原始 DDL；不写入或重置任何已有校准状态。
    IF to_regclass('public.nocode_ordered_calculation_state') IS NULL THEN
        CREATE TABLE public.nocode_ordered_calculation_state (
            id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
            object_id bigint NOT NULL,
            field_id bigint NOT NULL,
            signature varchar(64) NOT NULL,
            state varchar(24) NOT NULL,
            cursor_json jsonb NOT NULL DEFAULT '{}'::jsonb,
            total_rows bigint NOT NULL DEFAULT 0,
            updated_rows bigint NOT NULL DEFAULT 0,
            completed_groups bigint NOT NULL DEFAULT 0,
            error_message varchar(2000),
            lock_version bigint NOT NULL DEFAULT 0,
            creator varchar(64) NOT NULL DEFAULT '',
            create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updater varchar(64) NOT NULL DEFAULT '',
            update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
            deleted smallint NOT NULL DEFAULT 0,
            CONSTRAINT nocode_ordered_calculation_state_field UNIQUE (object_id, field_id),
            CONSTRAINT nocode_ordered_calculation_state_code CHECK (state IN ('PENDING','BACKFILLING','READY','FAILED'))
        );
    END IF;

    LOCK TABLE public.drive_storage_setting, public.nocode_ordered_calculation_state IN ACCESS EXCLUSIVE MODE;
    FOR expected IN SELECT * FROM (VALUES
        ('drive_storage_setting', 7), ('nocode_ordered_calculation_state', 16)
    ) v(table_name, column_count) LOOP
        checked_table := to_regclass('public.' || expected.table_name);
        IF NOT EXISTS (SELECT 1 FROM pg_class WHERE oid = checked_table AND relkind = 'r' AND NOT relrowsecurity)
           OR (SELECT count(*) FROM pg_attribute WHERE attrelid = checked_table AND attnum > 0 AND NOT attisdropped) <> expected.column_count
           OR (SELECT count(*) FROM pg_constraint WHERE conrelid = checked_table AND contype IN ('p','u','c','f','x')) <> 3 THEN
            RAISE EXCEPTION '表 % 类型、列数或约束数量与原始迁移不一致，停止升级。', expected.table_name;
        END IF;
    END LOOP;

    FOR expected IN SELECT * FROM (VALUES
        ('drive_storage_setting','id','bigint',true,'',NULL),
        ('drive_storage_setting','config_id','bigint',false,'',NULL),
        ('drive_storage_setting','creator','character varying(64)',false,'',$expr$''::character varying$expr$),
        ('drive_storage_setting','create_time','timestamp(6) without time zone',true,'','CURRENT_TIMESTAMP'),
        ('drive_storage_setting','updater','character varying(64)',false,'',$expr$''::character varying$expr$),
        ('drive_storage_setting','update_time','timestamp(6) without time zone',true,'','CURRENT_TIMESTAMP'),
        ('drive_storage_setting','deleted','smallint',true,'','0'),
        ('nocode_ordered_calculation_state','id','bigint',true,'d',NULL),
        ('nocode_ordered_calculation_state','object_id','bigint',true,'',NULL),
        ('nocode_ordered_calculation_state','field_id','bigint',true,'',NULL),
        ('nocode_ordered_calculation_state','signature','character varying(64)',true,'',NULL),
        ('nocode_ordered_calculation_state','state','character varying(24)',true,'',NULL),
        ('nocode_ordered_calculation_state','cursor_json','jsonb',true,'',$expr$'{}'::jsonb$expr$),
        ('nocode_ordered_calculation_state','total_rows','bigint',true,'','0'),
        ('nocode_ordered_calculation_state','updated_rows','bigint',true,'','0'),
        ('nocode_ordered_calculation_state','completed_groups','bigint',true,'','0'),
        ('nocode_ordered_calculation_state','error_message','character varying(2000)',false,'',NULL),
        ('nocode_ordered_calculation_state','lock_version','bigint',true,'','0'),
        ('nocode_ordered_calculation_state','creator','character varying(64)',true,'',$expr$''::character varying$expr$),
        ('nocode_ordered_calculation_state','create_time','timestamp without time zone',true,'','CURRENT_TIMESTAMP'),
        ('nocode_ordered_calculation_state','updater','character varying(64)',true,'',$expr$''::character varying$expr$),
        ('nocode_ordered_calculation_state','update_time','timestamp without time zone',true,'','CURRENT_TIMESTAMP'),
        ('nocode_ordered_calculation_state','deleted','smallint',true,'','0')
    ) v(table_name, column_name, data_type, not_null, identity_mode, default_expression) LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_attribute a
            LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
            WHERE a.attrelid = to_regclass('public.' || expected.table_name)
              AND a.attname = expected.column_name AND NOT a.attisdropped AND a.attnum > 0
              AND format_type(a.atttypid,a.atttypmod) = expected.data_type
              AND a.attnotnull = expected.not_null AND a.attidentity::text = expected.identity_mode
              AND a.attgenerated = ''
              AND pg_get_expr(d.adbin,d.adrelid) IS NOT DISTINCT FROM expected.default_expression
        ) THEN
            RAISE EXCEPTION '列 %.% 与原始迁移定义不一致，停止升级。', expected.table_name, expected.column_name;
        END IF;
    END LOOP;

    FOR expected IN SELECT * FROM (VALUES
        ('drive_storage_setting','drive_storage_setting_pkey','PRIMARY KEY (id)'),
        ('drive_storage_setting','drive_storage_setting_id_check','CHECK ((id = 1))'),
        ('drive_storage_setting','drive_storage_setting_deleted_check','CHECK ((deleted = ANY (ARRAY[0, 1])))'),
        ('nocode_ordered_calculation_state','nocode_ordered_calculation_state_pkey','PRIMARY KEY (id)'),
        ('nocode_ordered_calculation_state','nocode_ordered_calculation_state_field','UNIQUE (object_id, field_id)'),
        ('nocode_ordered_calculation_state','nocode_ordered_calculation_state_code',
         $expr$CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'BACKFILLING'::character varying, 'READY'::character varying, 'FAILED'::character varying])::text[])))$expr$)
    ) v(table_name, constraint_name, definition) LOOP
        IF NOT EXISTS (
            SELECT 1 FROM pg_constraint c LEFT JOIN pg_index i ON i.indexrelid = c.conindid
            WHERE c.conrelid = to_regclass('public.' || expected.table_name)
              AND c.conname = expected.constraint_name AND c.convalidated AND NOT c.condeferrable
              AND pg_get_constraintdef(c.oid) = expected.definition
              AND (c.contype = 'c' OR (i.indisunique AND i.indisvalid AND i.indisready))
        ) THEN
            RAISE EXCEPTION '约束 %.% 与原始迁移定义不一致，停止升级。', expected.table_name, expected.constraint_name;
        END IF;
    END LOOP;

    IF recorded_38 AND NOT EXISTS (SELECT 1 FROM public.drive_storage_setting WHERE id = 1) THEN
        RAISE EXCEPTION 'V038 已登记但存储配置单例缺失，停止升级。';
    END IF;
    INSERT INTO public.drive_storage_setting (id, config_id, creator, updater)
    VALUES (1, NULL, 'drive-storage-migration', 'drive-storage-migration')
    ON CONFLICT (id) DO NOTHING;

    -- 与 V038 一致，只补权限定义；不修改 system_role_menu 或其他角色授权。
    INSERT INTO public.system_menu
    (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
     visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
    SELECT nextval('public.system_menu_seq'),v.name,v.permission,3,v.sort,parent_menu_id,'',NULL,NULL,NULL,0,
           false,false,false,'drive-storage-migration','drive-storage-migration',now(),now(),0
    FROM (VALUES
        ('查看网盘存储配置','drive:storage:query',8),
        ('切换网盘存储源','drive:storage:update',9)
    ) v(name,permission,sort)
    WHERE NOT EXISTS (SELECT 1 FROM public.system_menu existing
                      WHERE existing.deleted = 0 AND existing.permission = v.permission);

    -- 仅为原历史连续完整的环境登记原始迁移校验和；不使用本包装文件的校验和。
    IF has_history AND history_complete THEN
        FOR expected IN SELECT * FROM (VALUES
            ('038','drive storage setting','V038__drive_storage_setting.sql',-1114903555,recorded_38),
            ('039','ordered calculation state','V039__ordered_calculation_state.sql',1765820567,recorded_39)
        ) v(version_number, description_text, script_name, checksum_value, already_recorded) LOOP
            IF NOT expected.already_recorded THEN
                INSERT INTO public.nocode_schema_history
                (installed_rank,version,description,type,script,checksum,installed_by,installed_on,execution_time,success)
                SELECT COALESCE(max(installed_rank),0) + 1, expected.version_number, expected.description_text,
                       'SQL', expected.script_name, expected.checksum_value, current_user, localtimestamp,
                       (extract(epoch FROM clock_timestamp() - started_at) * 1000)::integer, true
                FROM public.nocode_schema_history;
            END IF;
        END LOOP;
    END IF;
    RAISE NOTICE 'V038/V039 结构升级或重复核验完成；已有存储配置、角色授权、业务记录和校准进度均保留。';
END
$release$;

-- 执行摘要写入原 run-sql.sh 日志。
SELECT 'V038' AS version, to_regclass('public.drive_storage_setting') AS table_name,
       (SELECT count(*) FROM public.drive_storage_setting WHERE id = 1) AS singleton_rows,
       (SELECT count(*) FROM public.system_menu WHERE deleted = 0
        AND permission IN ('drive:storage:query','drive:storage:update')) AS permission_rows;
SELECT 'V039' AS version, to_regclass('public.nocode_ordered_calculation_state') AS table_name;
