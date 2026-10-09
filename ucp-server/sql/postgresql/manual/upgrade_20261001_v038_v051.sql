-- 日创 OS 线上增量：2026-09-26 18:08（V037）至 2026-10-01 当前工程（V051）。
-- 与原 run-sql.sh 放在同目录，执行：sh run-sql.sh upgrade_20261001_v038_v051_r2.sql
-- 只交付此 SQL；不用上传 Java、Node 或构建辅助文件。
-- 原 sh 负责全库备份、ON_ERROR_STOP 和单事务；务必整体运行，不拆段执行。
-- 执行前暂停所有应用实例的写入，成功后再启动本次配套代码。
-- 含 V038 网盘存储、V039 公式校准、V040-V046 任务中心、V047-V051 网盘业务文件融合。
-- 新拉取分支原 V040-V044 与本地任务中心重号；内容不变，统一顺延为 V047-V051。
-- 新字段规则与透视报表复用现有 JSON/业务表，无需额外 DDL 或存量配置批量转换。
-- 不清空已有数据，不导入开发样例；保留网盘配置、公式校准进度、既有任务及业务记录。
-- V040 按原迁移为已有“我的任务”角色继承新任务菜单；不会授予 manage-all 或网盘存储管理权限。
-- V043/V044 按原迁移回填任务计划来源及普通/流程任务类型；不改变原任务 ID 与业务关联。
-- 业务文件不迁移历史附件；新增清理任务处于待同步状态，部署后按需在定时任务页“同步任务”。
-- 兼容已手动执行 V038/V039 或其他中间版本；结构异常、校验和异常时整体回滚。
-- 历史完整才登记原始 Flyway 身份；无历史或历史缺项时不创建历史、不伪造旧迁移。
-- PostgreSQL 15+；UTF-8 无 BOM。历史版本的原始文件没有被修改。
-- 修订 R2：统一 public、pg_temp 别名及物理临时 schema 的结构定义，避免临时验证基准误报。

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '10min';
SET LOCAL search_path = pg_catalog, public;
SELECT current_database() AS target_database,current_user AS executing_user,
       inet_server_addr() AS server_address,inet_server_port() AS server_port;

CREATE TEMP TABLE nocode_release_identity ON COMMIT DROP AS
SELECT * FROM jsonb_to_recordset($json$[
  {
    "version": 1,
    "script": "V001__object_drafts.sql",
    "checksum": 464871112,
    "description": "object drafts"
  },
  {
    "version": 2,
    "script": "V002__object_menu.sql",
    "checksum": 772107053,
    "description": "object menu"
  },
  {
    "version": 3,
    "script": "V003__unify_nocode_namespace.sql",
    "checksum": 103808558,
    "description": "unify nocode namespace"
  },
  {
    "version": 4,
    "script": "V004__data_center.sql",
    "checksum": -1017428022,
    "description": "data center"
  },
  {
    "version": 5,
    "script": "V005__reconciliation_candidate.sql",
    "checksum": 622298112,
    "description": "reconciliation candidate"
  },
  {
    "version": 6,
    "script": "V006__table_bindings.sql",
    "checksum": -1520819192,
    "description": "table bindings"
  },
  {
    "version": 7,
    "script": "V007__application_foundation.sql",
    "checksum": -1674991728,
    "description": "application foundation"
  },
  {
    "version": 8,
    "script": "V008__application_authorization.sql",
    "checksum": 1040344160,
    "description": "application authorization"
  },
  {
    "version": 9,
    "script": "V009__business_counter.sql",
    "checksum": 1178555122,
    "description": "business counter"
  },
  {
    "version": 10,
    "script": "V010__record_process.sql",
    "checksum": 278323587,
    "description": "record process"
  },
  {
    "version": 11,
    "script": "V011__object_application_sharing.sql",
    "checksum": 1980929657,
    "description": "object application sharing"
  },
  {
    "version": 12,
    "script": "V012__business_table_prefix.sql",
    "checksum": 1064576315,
    "description": "business table prefix"
  },
  {
    "version": 13,
    "script": "V013__default_view_page_size.sql",
    "checksum": 1984877265,
    "description": "default view page size"
  },
  {
    "version": 14,
    "script": "V014__user_group_deleted_default.sql",
    "checksum": -1890412374,
    "description": "user group deleted default"
  },
  {
    "version": 15,
    "script": "V015__simple_system_feedback.sql",
    "checksum": -801461078,
    "description": "simple system feedback"
  },
  {
    "version": 16,
    "script": "V016__work_draft_submission.sql",
    "checksum": 1932856468,
    "description": "work draft submission"
  },
  {
    "version": 17,
    "script": "V017__flow_task_submission.sql",
    "checksum": 1301961598,
    "description": "flow task submission"
  },
  {
    "version": 18,
    "script": "V018__record_change_history.sql",
    "checksum": -305819195,
    "description": "record change history"
  },
  {
    "version": 19,
    "script": "V019__workbench_record_history_menu.sql",
    "checksum": 598763041,
    "description": "workbench record history menu"
  },
  {
    "version": 20,
    "script": "V020__record_history_query_visibility.sql",
    "checksum": -1199370510,
    "description": "record history query visibility"
  },
  {
    "version": 21,
    "script": "V021__internal_detail_reference_source.sql",
    "checksum": 173850327,
    "description": "internal detail reference source"
  },
  {
    "version": 22,
    "script": "V022__document_save_receipts.sql",
    "checksum": 70395272,
    "description": "document save receipts"
  },
  {
    "version": 23,
    "script": "V023__internal_detail_positions.sql",
    "checksum": -1011812305,
    "description": "internal detail positions"
  },
  {
    "version": 24,
    "script": "V024__document_work_draft_details.sql",
    "checksum": -1086312157,
    "description": "document work draft details"
  },
  {
    "version": 25,
    "script": "V025__task_entry_authorization.sql",
    "checksum": 599167467,
    "description": "task entry authorization"
  },
  {
    "version": 26,
    "script": "V026__task_entry_portal.sql",
    "checksum": 502476791,
    "description": "task entry portal"
  },
  {
    "version": 27,
    "script": "V027__business_handling_requests.sql",
    "checksum": 874790434,
    "description": "business handling requests"
  },
  {
    "version": 28,
    "script": "V028__bpm_task_action_permission.sql",
    "checksum": 151026076,
    "description": "bpm task action permission"
  },
  {
    "version": 29,
    "script": "V029__related_form_work_drafts.sql",
    "checksum": -765105897,
    "description": "related form work drafts"
  },
  {
    "version": 30,
    "script": "V030__object_application_categories.sql",
    "checksum": -1721153194,
    "description": "object application categories"
  },
  {
    "version": 31,
    "script": "V031__application_recycle_bin.sql",
    "checksum": 380286484,
    "description": "application recycle bin"
  },
  {
    "version": 32,
    "script": "V032__drive_foundation.sql",
    "checksum": 1990659847,
    "description": "drive foundation"
  },
  {
    "version": 33,
    "script": "V033__drive_menu_and_permissions.sql",
    "checksum": 95195785,
    "description": "drive menu and permissions"
  },
  {
    "version": 34,
    "script": "V034__drive_entry_file_name_unique.sql",
    "checksum": -580400549,
    "description": "drive entry file name unique"
  },
  {
    "version": 35,
    "script": "V035__remove_custom_codegen.sql",
    "checksum": -1900786649,
    "description": "remove custom codegen"
  },
  {
    "version": 36,
    "script": "V036__remove_knowledge_and_agent_modules.sql",
    "checksum": -474266132,
    "description": "remove knowledge and agent modules"
  },
  {
    "version": 37,
    "script": "V037__object_maintenance_receipts.sql",
    "checksum": -1670108893,
    "description": "object maintenance receipts"
  },
  {
    "version": 38,
    "script": "V038__drive_storage_setting.sql",
    "checksum": -1114903555,
    "description": "drive storage setting"
  },
  {
    "version": 39,
    "script": "V039__ordered_calculation_state.sql",
    "checksum": 1765820567,
    "description": "ordered calculation state"
  },
  {
    "version": 40,
    "script": "V040__task_center_instances.sql",
    "checksum": -997036472,
    "description": "task center instances"
  },
  {
    "version": 41,
    "script": "V041__task_center_management_permission.sql",
    "checksum": -398698749,
    "description": "task center management permission"
  },
  {
    "version": 42,
    "script": "V042__task_center_top_level_navigation.sql",
    "checksum": -1927998527,
    "description": "task center top level navigation"
  },
  {
    "version": 43,
    "script": "V043__task_record_links_and_plan_sources.sql",
    "checksum": -305202299,
    "description": "task record links and plan sources"
  },
  {
    "version": 44,
    "script": "V044__task_product_kinds.sql",
    "checksum": 627494405,
    "description": "task product kinds"
  },
  {
    "version": 45,
    "script": "V045__task_multiple_work_entries.sql",
    "checksum": -1313592250,
    "description": "task multiple work entries"
  },
  {
    "version": 46,
    "script": "V046__task_feedback_contributor_lookup.sql",
    "checksum": -1670075568,
    "description": "task feedback contributor lookup"
  },
  {
    "version": 47,
    "script": "V047__drive_biz_fusion.sql",
    "checksum": 1493284889,
    "description": "drive biz fusion"
  },
  {
    "version": 48,
    "script": "V048__biz_file_browse_index.sql",
    "checksum": -170479746,
    "description": "biz file browse index"
  },
  {
    "version": 49,
    "script": "V049__biz_file_retention_holder_index.sql",
    "checksum": 1067803427,
    "description": "biz file retention holder index"
  },
  {
    "version": 50,
    "script": "V050__biz_file_mark_and_menu.sql",
    "checksum": -630213751,
    "description": "biz file mark and menu"
  },
  {
    "version": 51,
    "script": "V051__biz_file_integrity.sql",
    "checksum": 1705167236,
    "description": "biz file integrity"
  }
]$json$::jsonb)
AS v(version integer,script text,checksum integer,description text);
CREATE TEMP TABLE nocode_release_plan (
    version integer PRIMARY KEY,markers jsonb NOT NULL,contract jsonb NOT NULL,body text NOT NULL
) ON COMMIT DROP;

-- V038 原文件 SHA-256: 3c1adcbddc74575daf6252467a670ae76d09e380433b5680256ba25b6e9bfa99
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(38,$json$[
  {
    "relation": "drive_storage_setting"
  }
]$json$::jsonb,$json$[
  {
    "table": "drive_storage_setting",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "config_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp(6) without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp(6) without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "drive_storage_setting_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX drive_storage_setting_pkey ON drive_storage_setting USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "drive_storage_setting_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "drive_storage_setting_id_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((id = 1))"
      },
      {
        "name": "drive_storage_setting_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  }
]$json$::jsonb,$v38$-- 网盘独立存储源选择。未指定时沿用既有文件主配置，保留所有存量文件的 config_id。
CREATE TABLE public.drive_storage_setting (
    id bigint PRIMARY KEY CHECK (id = 1),
    config_id bigint,
    creator varchar(64) DEFAULT '', create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '', update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0, 1))
);
INSERT INTO public.drive_storage_setting (id, config_id, creator, updater)
VALUES (1, NULL, 'drive-storage-migration', 'drive-storage-migration');
COMMENT ON TABLE public.drive_storage_setting IS '网盘后续写入使用的文件配置；空值沿用平台主配置';
COMMENT ON COLUMN public.drive_storage_setting.config_id IS '引用 infra_file_config.id；旧文件仍使用各自 infra_file.config_id';

-- 权限只补定义，不自动扩大存量角色授权。
INSERT INTO public.system_menu
(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
 visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
SELECT nextval('public.system_menu_seq'),v.name,v.permission,3,v.sort,parent.id,'',NULL,NULL,NULL,0,
       false,false,false,'drive-storage-migration','drive-storage-migration',now(),now(),0
FROM (VALUES
    ('查看网盘存储配置','drive:storage:query',8),
    ('切换网盘存储源','drive:storage:update',9)
) AS v(name,permission,sort)
JOIN public.system_menu parent ON parent.deleted=0 AND parent.type=2 AND parent.path='/drive/space'
WHERE NOT EXISTS (SELECT 1 FROM public.system_menu existing
                  WHERE existing.deleted=0 AND existing.permission=v.permission);
$v38$);

-- V039 原文件 SHA-256: 073fd405102213a5d77aebb12e7863ea9de37c82fc9aa723fc1456270e655e0e
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(39,$json$[
  {
    "relation": "nocode_ordered_calculation_state"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_ordered_calculation_state",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "signature",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(24)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "cursor_json",
        "type": "jsonb",
        "default": "'{}'::jsonb",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "total_rows",
        "type": "bigint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updated_rows",
        "type": "bigint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "completed_groups",
        "type": "bigint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "error_message",
        "type": "character varying(2000)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "bigint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_ordered_calculation_state_field",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_ordered_calculation_state_field ON nocode_ordered_calculation_state USING btree (object_id, field_id)"
      },
      {
        "name": "nocode_ordered_calculation_state_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_ordered_calculation_state_pkey ON nocode_ordered_calculation_state USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_ordered_calculation_state_code",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'BACKFILLING'::character varying, 'READY'::character varying, 'FAILED'::character varying])::text[])))"
      },
      {
        "name": "nocode_ordered_calculation_state_field",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (object_id, field_id)"
      },
      {
        "name": "nocode_ordered_calculation_state_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  }
]$json$::jsonb,$v39$-- 有序落库专用的轻量状态；不改变其他 ON_SAVE 的本记录快照语义。
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
$v39$);

-- V040 原文件 SHA-256: 451a937653888691b773c15e02368b73a115bf0d01c9fb3269c5482c74ee07b1
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(40,$json$[
  {
    "relation": "nocode_task_instance"
  },
  {
    "relation": "nocode_task_template"
  },
  {
    "relation": "nocode_task_template_version"
  },
  {
    "relation": "nocode_task_plan"
  },
  {
    "relation": "nocode_task_comment"
  },
  {
    "relation": "nocode_task_event"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_task_instance",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "root_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "parent_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "title",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "assignee_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "status",
        "type": "character varying(20)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "config_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "business_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "project_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "template_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "template_version",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "t0",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "baseline_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "baseline_end",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "expected_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "expected_end",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "actual_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "actual_end",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_key",
        "type": "character varying(120)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_hash",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_assignee_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_assignee_idx ON nocode_task_instance USING btree (assignee_id, status, expected_end) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_instance_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_instance_pkey ON nocode_task_instance USING btree (id)"
      },
      {
        "name": "nocode_task_request_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_request_uk ON nocode_task_instance USING btree (creator, request_key) WHERE (request_key IS NOT NULL)"
      },
      {
        "name": "nocode_task_root_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_root_idx ON nocode_task_instance USING btree (root_id) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_instance_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))"
      }
    ]
  },
  {
    "table": "nocode_task_template",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "name",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "description",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "published_version",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "nodes_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_template_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_template_pkey ON nocode_task_template USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_template_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "nocode_task_template_version",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "template_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "version_no",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "name",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "description",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "nodes_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "bindings_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_template_version_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_template_version_pkey ON nocode_task_template_version USING btree (id)"
      },
      {
        "name": "nocode_task_template_version_template_id_version_no_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_template_version_template_id_version_no_key ON nocode_task_template_version USING btree (template_id, version_no)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_template_version_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_template_version_template_id_version_no_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (template_id, version_no)"
      }
    ]
  },
  {
    "table": "nocode_task_plan",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "user_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "period",
        "type": "character varying(10)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "plan_date",
        "type": "date",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_plan_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_pkey ON nocode_task_plan USING btree (id)"
      },
      {
        "name": "nocode_task_plan_task_id_user_id_period_plan_date_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_task_id_user_id_period_plan_date_key ON nocode_task_plan USING btree (task_id, user_id, period, plan_date)"
      },
      {
        "name": "nocode_task_plan_user_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_plan_user_idx ON nocode_task_plan USING btree (user_id, period, plan_date) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_plan_period_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((period)::text = ANY ((ARRAY['DAY'::character varying, 'WEEK'::character varying, 'MONTH'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_plan_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_plan_task_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (task_id) REFERENCES nocode_task_instance(id)"
      },
      {
        "name": "nocode_task_plan_task_id_user_id_period_plan_date_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (task_id, user_id, period, plan_date)"
      }
    ]
  },
  {
    "table": "nocode_task_comment",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "parent_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "content",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "mentioned_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_key",
        "type": "character varying(120)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_hash",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_comment_creator_request_key_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_comment_creator_request_key_key ON nocode_task_comment USING btree (creator, request_key)"
      },
      {
        "name": "nocode_task_comment_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_comment_pkey ON nocode_task_comment USING btree (id)"
      },
      {
        "name": "nocode_task_comment_task_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_comment_task_idx ON nocode_task_comment USING btree (task_id, create_time) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_comment_creator_request_key_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (creator, request_key)"
      },
      {
        "name": "nocode_task_comment_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_comment_task_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (task_id) REFERENCES nocode_task_instance(id)"
      }
    ]
  },
  {
    "table": "nocode_task_event",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "root_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "event_type",
        "type": "character varying(30)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "note",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "material_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_key",
        "type": "character varying(120)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_hash",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_event_actor_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_event_actor_idx ON nocode_task_event USING btree (creator, create_time DESC) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_event_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_event_pkey ON nocode_task_event USING btree (id)"
      },
      {
        "name": "nocode_task_event_request_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_event_request_uk ON nocode_task_event USING btree (creator, request_key) WHERE (request_key IS NOT NULL)"
      },
      {
        "name": "nocode_task_event_root_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_event_root_idx ON nocode_task_event USING btree (root_id, create_time) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_event_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  }
]$json$::jsonb,$v40$-- 正式工作任务：不修改原 TASK_ENTRY、Flowable 或公共业务对象记录。
CREATE TABLE public.nocode_task_instance (
 id varchar(64) PRIMARY KEY, root_id varchar(64) NOT NULL, parent_id varchar(64),
 title varchar(200) NOT NULL, assignee_id bigint NOT NULL, status varchar(20) NOT NULL,
 lock_version integer NOT NULL DEFAULT 0, config_json text NOT NULL, business_json text,
 project_json text, template_id varchar(64), template_version integer,
 t0 timestamp NOT NULL, baseline_start timestamp, baseline_end timestamp,
 expected_start timestamp, expected_end timestamp, actual_start timestamp, actual_end timestamp,
 request_key varchar(120), request_hash varchar(64),
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0,
 CONSTRAINT nocode_task_state_ck CHECK(status IN ('PENDING','RUNNING','COMPLETED','CANCELLED'))
);
CREATE INDEX nocode_task_root_idx ON public.nocode_task_instance(root_id) WHERE deleted=0;
CREATE INDEX nocode_task_assignee_idx ON public.nocode_task_instance(assignee_id,status,expected_end) WHERE deleted=0;
CREATE UNIQUE INDEX nocode_task_request_uk ON public.nocode_task_instance(creator,request_key) WHERE request_key IS NOT NULL;
CREATE TABLE public.nocode_task_template (
 id varchar(64) PRIMARY KEY, name varchar(200) NOT NULL, description text,
 lock_version integer NOT NULL DEFAULT 0, published_version integer, nodes_json text NOT NULL,
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0
);
CREATE TABLE public.nocode_task_template_version (
 id varchar(64) PRIMARY KEY, template_id varchar(64) NOT NULL, version_no integer NOT NULL,
 name varchar(200) NOT NULL, description text, nodes_json text NOT NULL, bindings_json text NOT NULL,
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0,
 UNIQUE(template_id,version_no)
);
CREATE TABLE public.nocode_task_plan (
 id varchar(64) PRIMARY KEY, task_id varchar(64) NOT NULL REFERENCES public.nocode_task_instance(id),
 user_id bigint NOT NULL, period varchar(10) NOT NULL, plan_date date NOT NULL,
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0,
 UNIQUE(task_id,user_id,period,plan_date), CHECK(period IN ('DAY','WEEK','MONTH'))
);
CREATE INDEX nocode_task_plan_user_idx ON public.nocode_task_plan(user_id,period,plan_date) WHERE deleted=0;
CREATE TABLE public.nocode_task_comment (
 id varchar(64) PRIMARY KEY, task_id varchar(64) NOT NULL REFERENCES public.nocode_task_instance(id),
 parent_id varchar(64), content text NOT NULL, mentioned_json text NOT NULL,
 request_key varchar(120) NOT NULL, request_hash varchar(64) NOT NULL,
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0,
 UNIQUE(creator,request_key)
);
CREATE INDEX nocode_task_comment_task_idx ON public.nocode_task_comment(task_id,create_time) WHERE deleted=0;
CREATE TABLE public.nocode_task_event (
 id varchar(64) PRIMARY KEY, task_id varchar(64) NOT NULL, root_id varchar(64) NOT NULL,
 event_type varchar(30) NOT NULL, note text, material_json text,
 request_key varchar(120), request_hash varchar(64),
 creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0
);
CREATE INDEX nocode_task_event_root_idx ON public.nocode_task_event(root_id,create_time) WHERE deleted=0;
CREATE INDEX nocode_task_event_actor_idx ON public.nocode_task_event(creator,create_time DESC) WHERE deleted=0;
CREATE UNIQUE INDEX nocode_task_event_request_uk ON public.nocode_task_event(creator,request_key) WHERE request_key IS NOT NULL;

-- 使用 /message/list 对应的真实消息中心；正文由调用方按纯文本转义。
INSERT INTO public.sys_msg_template
 (id,code,name,priority,subscribe_able,template_title,template_content,template_url,notice_config,creator,updater)
SELECT -9040001,'nocode-task-comment','任务评论提醒',0,0,
 '任务协作提醒','任务有新的评论','/nocode-app/task-center?taskId=' || chr(36) || '{taskId}','[]','task-center-migration','task-center-migration'
WHERE NOT EXISTS(SELECT 1 FROM public.sys_msg_template WHERE code='nocode-task-comment' AND deleted=0);

-- 保留旧深链接；将旧入口页面作为“我的任务”，其办理入口仍可在页面中访问。
DO $$
DECLARE dashboard bigint; folder bigint; mine bigint; task_menu bigint; entry record;
BEGIN
 LOCK TABLE public.system_menu IN SHARE ROW EXCLUSIVE MODE;
 SELECT id INTO STRICT dashboard FROM public.system_menu WHERE path='/dashboard' AND parent_id=0 AND deleted=0;
 SELECT id INTO STRICT mine FROM public.system_menu WHERE path='/nocode-app/task-center' AND deleted=0;
 folder:=nextval('public.system_menu_seq');
 INSERT INTO public.system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
 VALUES(folder,'任务中心','',1,11,dashboard,'/task-center','CheckSquareOutlined',NULL,'NocodeTaskFolder',0,true,false,true,'task-center-migration','task-center-migration',0);
 UPDATE public.system_menu SET parent_id=folder,name='我的任务',sort=1,permission='nocode:task:query',updater='task-center-migration',update_time=now() WHERE id=mine;
 INSERT INTO public.system_role_menu(id,role_id,menu_id,creator,updater,create_time,update_time,deleted,tenant_id)
 SELECT nextval('public.system_role_menu_seq'),role_id,folder,'task-center-migration','task-center-migration',now(),now(),0,tenant_id
 FROM public.system_role_menu WHERE menu_id=mine AND deleted=0;
 FOR entry IN SELECT * FROM (VALUES
 ('任务管理','/nocode-app/task-center/manage','NocodeTaskManage','nocode:task:query',2),
 ('发起任务','/nocode-app/task-center/launch','NocodeTaskLaunch','nocode:task:create',3),
 ('任务模板','/nocode-app/task-center/templates','NocodeTaskTemplates','nocode:task:template',4)
 ) AS items(name,path,component_name,permission,sort) LOOP
  task_menu:=nextval('public.system_menu_seq');
  INSERT INTO public.system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)
  VALUES(task_menu,entry.name,entry.permission,2,entry.sort,folder,entry.path,'UnorderedListOutlined','nocode/task-center/index',entry.component_name,0,true,false,true,'task-center-migration','task-center-migration',0);
  INSERT INTO public.system_role_menu(id,role_id,menu_id,creator,updater,create_time,update_time,deleted,tenant_id)
  SELECT nextval('public.system_role_menu_seq'),role_id,task_menu,'task-center-migration','task-center-migration',now(),now(),0,tenant_id
  FROM public.system_role_menu WHERE public.system_role_menu.menu_id=mine AND deleted=0;
 END LOOP;
END $$;
$v40$);

-- V041 原文件 SHA-256: b60f85d3b8de42015d05ab5da3c8ff96e44365df989b31b6f9db8accc06d5a1a
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(41,$json$[
  {
    "permission": "nocode:task:manage-all"
  }
]$json$::jsonb,$json$[]$json$::jsonb,$v41$-- 可显式授权的任务管理能力；不默认授予普通角色，仍不扩大业务对象数据权限。
INSERT INTO public.system_menu
 (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
  visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
SELECT nextval('public.system_menu_seq'),'管理全部任务实例','nocode:task:manage-all',3,1,m.id,
 '',NULL,NULL,NULL,0,true,false,false,'task-center-migration','task-center-migration',now(),now(),0
FROM public.system_menu m WHERE m.path='/nocode-app/task-center/manage' AND m.deleted=0
 AND NOT EXISTS (SELECT 1 FROM public.system_menu existing
                 WHERE existing.permission='nocode:task:manage-all' AND existing.deleted=0);
$v41$);

-- V042 原文件 SHA-256: f146526b2dc7afdce84428e3406c97b6393e25493a5ef89b524956d1dd363c7e
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(42,$json$[
  {
    "topLevel": "/task-center"
  }
]$json$::jsonb,$json$[]$json$::jsonb,$v42$-- 正式布局只有 L1/L2 两层，任务中心必须作为独立 L1。
-- 只移动 V040 创建的任务中心目录，保留四子菜单、权限绑定与既有办理 URL。
UPDATE public.system_menu
SET parent_id=0, sort=6, updater='task-center-migration', update_time=now()
WHERE path='/task-center' AND type=1 AND creator='task-center-migration' AND deleted=0;
$v42$);

-- V043 原文件 SHA-256: 6c8b4e27515530756af5be53c115e9addd6983ddc1a6e0977198a1c1877f9314
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(43,$json$[
  {
    "relation": "nocode_task_record_link"
  },
  {
    "table": "nocode_task_plan",
    "column": "arranged_by_id"
  },
  {
    "table": "nocode_task_plan",
    "column": "source"
  },
  {
    "table": "nocode_task_plan",
    "column": "arranged_at"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_task_plan",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "user_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "period",
        "type": "character varying(10)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "plan_date",
        "type": "date",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "arranged_by_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source",
        "type": "character varying(20)",
        "default": "'SELF'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "arranged_at",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_plan_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_pkey ON nocode_task_plan USING btree (id)"
      },
      {
        "name": "nocode_task_plan_task_id_user_id_period_plan_date_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_task_id_user_id_period_plan_date_key ON nocode_task_plan USING btree (task_id, user_id, period, plan_date)"
      },
      {
        "name": "nocode_task_plan_user_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_plan_user_idx ON nocode_task_plan USING btree (user_id, period, plan_date) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_plan_period_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((period)::text = ANY ((ARRAY['DAY'::character varying, 'WEEK'::character varying, 'MONTH'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_plan_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_plan_source_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((source)::text = ANY ((ARRAY['SELF'::character varying, 'MANAGER'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_plan_task_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (task_id) REFERENCES nocode_task_instance(id)"
      },
      {
        "name": "nocode_task_plan_task_id_user_id_period_plan_date_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (task_id, user_id, period, plan_date)"
      }
    ]
  },
  {
    "table": "nocode_task_record_link",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "application_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "label",
        "type": "character varying(200)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_record_link_context_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_record_link_context_idx ON nocode_task_record_link USING btree (application_id, object_id, record_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_record_link_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_record_link_pkey ON nocode_task_record_link USING btree (id)"
      },
      {
        "name": "nocode_task_record_link_task_id_application_id_object_id_re_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_record_link_task_id_application_id_object_id_re_key ON nocode_task_record_link USING btree (task_id, application_id, object_id, record_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_record_link_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_record_link_task_id_application_id_object_id_re_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (task_id, application_id, object_id, record_id)"
      },
      {
        "name": "nocode_task_record_link_task_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (task_id) REFERENCES nocode_task_instance(id)"
      }
    ]
  }
]$json$::jsonb,$v43$-- 记录关联独立于办理主记录；计划记录保留安排者和来源，旧数据按原创建者回填。
CREATE TABLE public.nocode_task_record_link (
 id varchar(64) PRIMARY KEY, task_id varchar(64) NOT NULL REFERENCES public.nocode_task_instance(id),
 application_id varchar(64) NOT NULL, object_id varchar(64) NOT NULL, record_id varchar(200) NOT NULL,
 label varchar(200), creator varchar(64) NOT NULL, create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL, update_time timestamp NOT NULL DEFAULT now(), deleted smallint NOT NULL DEFAULT 0,
 UNIQUE(task_id,application_id,object_id,record_id)
);
CREATE INDEX nocode_task_record_link_context_idx ON public.nocode_task_record_link(application_id,object_id,record_id) WHERE deleted=0;
ALTER TABLE public.nocode_task_plan ADD COLUMN arranged_by_id bigint;
ALTER TABLE public.nocode_task_plan ADD COLUMN source varchar(20) NOT NULL DEFAULT 'SELF';
ALTER TABLE public.nocode_task_plan ADD COLUMN arranged_at timestamp;
UPDATE public.nocode_task_plan SET arranged_by_id=creator::bigint,arranged_at=create_time;
ALTER TABLE public.nocode_task_plan ALTER COLUMN arranged_by_id SET NOT NULL;
ALTER TABLE public.nocode_task_plan ALTER COLUMN arranged_at SET NOT NULL;
ALTER TABLE public.nocode_task_plan ADD CONSTRAINT nocode_task_plan_source_ck CHECK(source IN ('SELF','MANAGER'));

-- 只修改本任务域模板，既有消息中的任务定位仍兼容，新增消息同时定位具体评论。
UPDATE public.sys_msg_template SET template_url='/nocode-app/task-center?taskId=' || chr(36) || '{taskId}&commentId=' || chr(36) || '{commentId}',
 updater='task-center-migration',update_time=now()
WHERE code='nocode-task-comment' AND deleted=0;
$v43$);

-- V044 原文件 SHA-256: e2c50c1024db03890a640149c1160fbca773250cb9cf28c3adce3daa7150ef1d
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(44,$json$[
  {
    "table": "nocode_task_instance",
    "column": "kind"
  },
  {
    "table": "nocode_task_instance",
    "column": "template_node_id"
  },
  {
    "table": "nocode_task_template",
    "column": "kind"
  },
  {
    "table": "nocode_task_template_version",
    "column": "kind"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_task_instance",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "root_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "parent_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "title",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "assignee_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "status",
        "type": "character varying(20)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "config_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "business_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "project_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "template_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "template_version",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "t0",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "baseline_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "baseline_end",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "expected_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "expected_end",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "actual_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "actual_end",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_key",
        "type": "character varying(120)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_hash",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "kind",
        "type": "character varying(20)",
        "default": "'ORDINARY'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "template_node_id",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_assignee_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_assignee_idx ON nocode_task_instance USING btree (assignee_id, status, expected_end) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_instance_kind_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_instance_kind_idx ON nocode_task_instance USING btree (kind, assignee_id, status, expected_end) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_instance_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_instance_pkey ON nocode_task_instance USING btree (id)"
      },
      {
        "name": "nocode_task_request_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_request_uk ON nocode_task_instance USING btree (creator, request_key) WHERE (request_key IS NOT NULL)"
      },
      {
        "name": "nocode_task_root_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_root_idx ON nocode_task_instance USING btree (root_id) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_instance_kind_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((kind)::text = ANY ((ARRAY['ORDINARY'::character varying, 'PROCESS'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_instance_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))"
      }
    ]
  },
  {
    "table": "nocode_task_template",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "name",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "description",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "published_version",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "nodes_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "kind",
        "type": "character varying(20)",
        "default": "'ORDINARY'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_template_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_template_pkey ON nocode_task_template USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_template_kind_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((kind)::text = ANY ((ARRAY['ORDINARY'::character varying, 'PROCESS'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_template_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "nocode_task_template_version",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "template_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "version_no",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "name",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "description",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "nodes_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "bindings_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "kind",
        "type": "character varying(20)",
        "default": "'ORDINARY'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_template_version_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_template_version_pkey ON nocode_task_template_version USING btree (id)"
      },
      {
        "name": "nocode_task_template_version_template_id_version_no_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_template_version_template_id_version_no_key ON nocode_task_template_version USING btree (template_id, version_no)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_template_version_kind_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((kind)::text = ANY ((ARRAY['ORDINARY'::character varying, 'PROCESS'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_template_version_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_template_version_template_id_version_no_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (template_id, version_no)"
      }
    ]
  }
]$json$::jsonb,$v44$-- V043 后追加任务产品类型。类型不依据子任务数量或模板来源推断，已有明确执行依赖按流程兼容。
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
$v44$);

-- V045 原文件 SHA-256: 97c208fa425626732bd79b803e144344d542677ab09ec15567fd3dfe20bcd00f
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(45,$json$[
  {
    "relation": "nocode_task_entry_binding"
  },
  {
    "relation": "nocode_task_entry_record"
  },
  {
    "relation": "nocode_task_entry_template_version"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_task_entry_binding",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_key",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "dataset_id",
        "type": "character varying(160)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "config_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "business_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "inherited",
        "type": "boolean",
        "default": "false",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "submitted_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_entry_binding_dataset_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_entry_binding_dataset_idx ON nocode_task_entry_binding USING btree (dataset_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_entry_binding_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_binding_pkey ON nocode_task_entry_binding USING btree (id)"
      },
      {
        "name": "nocode_task_entry_binding_task_id_entry_key_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_binding_task_id_entry_key_key ON nocode_task_entry_binding USING btree (task_id, entry_key)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_entry_binding_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_entry_binding_task_id_entry_key_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (task_id, entry_key)"
      },
      {
        "name": "nocode_task_entry_binding_task_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (task_id) REFERENCES nocode_task_instance(id)"
      }
    ]
  },
  {
    "table": "nocode_task_entry_record",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_key",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "dataset_id",
        "type": "character varying(160)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "business_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "operation",
        "type": "character varying(20)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "superseded_by",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_revision",
        "type": "character varying(200)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "snapshot_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_key",
        "type": "character varying(120)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_hash",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_entry_record_creator_request_key_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_record_creator_request_key_key ON nocode_task_entry_record USING btree (creator, request_key)"
      },
      {
        "name": "nocode_task_entry_record_dataset_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_entry_record_dataset_idx ON nocode_task_entry_record USING btree (dataset_id, create_time) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_entry_record_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_record_pkey ON nocode_task_entry_record USING btree (id)"
      },
      {
        "name": "nocode_task_entry_record_request_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_entry_record_request_idx ON nocode_task_entry_record USING btree ((((business_json)::jsonb ->> 'requestId'::text))) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_entry_record_creator_request_key_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (creator, request_key)"
      },
      {
        "name": "nocode_task_entry_record_operation_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((operation)::text = ANY ((ARRAY['CREATED'::character varying, 'UPDATED'::character varying, 'LINKED'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_entry_record_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_entry_record_task_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (task_id) REFERENCES nocode_task_instance(id)"
      }
    ]
  },
  {
    "table": "nocode_task_entry_template_version",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "template_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "version_no",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "bindings_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_entry_template_version_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_template_version_pkey ON nocode_task_entry_template_version USING btree (id)"
      },
      {
        "name": "nocode_task_entry_template_version_template_id_version_no_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_template_version_template_id_version_no_key ON nocode_task_entry_template_version USING btree (template_id, version_no)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_entry_template_version_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_entry_template_version_template_id_version_no_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (template_id, version_no)"
      }
    ]
  }
]$json$::jsonb,$v45$-- 执行任务多入口与贡献记录：稳定入口 key 独立分组，业务值继续存公共记录。
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
$v45$);

-- V046 原文件 SHA-256: 22843e8ea3570baa781dda4775f7ec76ebb998cd34468a898a5a8dabc3fa25b2
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(46,$json$[
  {
    "relation": "nocode_task_entry_record_task_idx"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_task_entry_record",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_key",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "dataset_id",
        "type": "character varying(160)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "business_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "operation",
        "type": "character varying(20)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "superseded_by",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_revision",
        "type": "character varying(200)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "snapshot_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_key",
        "type": "character varying(120)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_hash",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_entry_record_creator_request_key_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_record_creator_request_key_key ON nocode_task_entry_record USING btree (creator, request_key)"
      },
      {
        "name": "nocode_task_entry_record_dataset_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_entry_record_dataset_idx ON nocode_task_entry_record USING btree (dataset_id, create_time) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_entry_record_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_record_pkey ON nocode_task_entry_record USING btree (id)"
      },
      {
        "name": "nocode_task_entry_record_request_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_entry_record_request_idx ON nocode_task_entry_record USING btree ((((business_json)::jsonb ->> 'requestId'::text))) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_entry_record_task_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_entry_record_task_idx ON nocode_task_entry_record USING btree (task_id) WHERE ((deleted = 0) AND (superseded_by IS NULL))"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_entry_record_creator_request_key_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (creator, request_key)"
      },
      {
        "name": "nocode_task_entry_record_operation_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((operation)::text = ANY ((ARRAY['CREATED'::character varying, 'UPDATED'::character varying, 'LINKED'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_entry_record_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_entry_record_task_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (task_id) REFERENCES nocode_task_instance(id)"
      }
    ]
  }
]$json$::jsonb,$v46$-- 反馈记录详情中的 TASKS 反查真实贡献任务，按任务定位当前有效贡献，避免逐任务全表扫描。
-- 审批尚未生效及已被重提替代的贡献由查询边界排除，索引不改变现有记录或权限。
CREATE INDEX nocode_task_entry_record_task_idx
    ON public.nocode_task_entry_record(task_id)
    WHERE deleted=0 AND superseded_by IS NULL;
$v46$);

-- V047 原文件 SHA-256: 1971380e952494b730e8ecb35532ff5748c994f3e72467aab48d52c59ce9de2d
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(47,$json$[
  {
    "relation": "nocode_biz_directory_binding"
  },
  {
    "relation": "nocode_biz_attachment_binding"
  },
  {
    "relation": "nocode_biz_upload_session"
  },
  {
    "relation": "nocode_biz_file_retention"
  },
  {
    "relation": "nocode_biz_file_task"
  },
  {
    "table": "drive_entry",
    "column": "managed_biz"
  }
]$json$::jsonb,$json$[
  {
    "table": "drive_entry",
    "columns": [
      {
        "name": "managed_biz",
        "type": "boolean",
        "default": "false",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [],
    "constraints": []
  },
  {
    "table": "nocode_biz_directory_binding",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(512)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "detail_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "row_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "space_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "rule_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "group_keys",
        "type": "character varying(512)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_directory_binding_entry_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_directory_binding_entry_idx ON nocode_biz_directory_binding USING btree (space_id, entry_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_directory_binding_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_directory_binding_pkey ON nocode_biz_directory_binding USING btree (id)"
      },
      {
        "name": "nocode_biz_directory_binding_record_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_directory_binding_record_idx ON nocode_biz_directory_binding USING btree (object_id, record_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_directory_binding_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_directory_binding_uk ON nocode_biz_directory_binding USING btree (object_id, record_id, detail_id, row_id, field_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_directory_binding_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_directory_binding_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_directory_binding_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (object_id, record_id, detail_id, row_id, field_id)"
      }
    ]
  },
  {
    "table": "nocode_biz_attachment_binding",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(512)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "detail_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "row_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "space_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(16)",
        "default": "'ACTIVE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_entry",
        "type": "character varying(32)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_attachment_binding_file_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_attachment_binding_file_idx ON nocode_biz_attachment_binding USING btree (file_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_attachment_binding_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_attachment_binding_pkey ON nocode_biz_attachment_binding USING btree (id)"
      },
      {
        "name": "nocode_biz_attachment_binding_record_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_attachment_binding_record_idx ON nocode_biz_attachment_binding USING btree (object_id, record_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_attachment_binding_state_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_attachment_binding_state_idx ON nocode_biz_attachment_binding USING btree (file_id, state) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_attachment_binding_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_attachment_binding_uk ON nocode_biz_attachment_binding USING btree (object_id, record_id, detail_id, row_id, field_id, file_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_attachment_binding_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_attachment_binding_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_attachment_binding_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'HISTORY'::character varying])::text[])))"
      },
      {
        "name": "nocode_biz_attachment_binding_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (object_id, record_id, detail_id, row_id, field_id, file_id)"
      }
    ]
  },
  {
    "table": "nocode_biz_upload_session",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "session_key",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "user_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(512)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_name",
        "type": "character varying(255)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(16)",
        "default": "'TEMPORARY'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "expires_at",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "idempotency_key",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_upload_session_expire_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_upload_session_expire_idx ON nocode_biz_upload_session USING btree (state, expires_at) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_upload_session_file_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_upload_session_file_idx ON nocode_biz_upload_session USING btree (file_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_upload_session_idem_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_upload_session_idem_uk ON nocode_biz_upload_session USING btree (user_id, idempotency_key) WHERE ((deleted = 0) AND ((idempotency_key)::text <> ''::text))"
      },
      {
        "name": "nocode_biz_upload_session_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_upload_session_pkey ON nocode_biz_upload_session USING btree (id)"
      },
      {
        "name": "nocode_biz_upload_session_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_upload_session_uk ON nocode_biz_upload_session USING btree (session_key, file_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_upload_session_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_upload_session_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_upload_session_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['TEMPORARY'::character varying, 'BOUND'::character varying, 'EXPIRED'::character varying, 'CLEANED'::character varying])::text[])))"
      },
      {
        "name": "nocode_biz_upload_session_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (session_key, file_id)"
      }
    ]
  },
  {
    "table": "nocode_biz_file_retention",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "file_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "holder_type",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "holder_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(512)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_file_retention_file_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_file_retention_file_idx ON nocode_biz_file_retention USING btree (file_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_file_retention_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_file_retention_pkey ON nocode_biz_file_retention USING btree (id)"
      },
      {
        "name": "nocode_biz_file_retention_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_file_retention_uk ON nocode_biz_file_retention USING btree (file_id, holder_type, holder_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_file_retention_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_file_retention_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_file_retention_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (file_id, holder_type, holder_id)"
      }
    ]
  },
  {
    "table": "nocode_biz_file_task",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "task_type",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "payload_json",
        "type": "jsonb",
        "default": "'{}'::jsonb",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(16)",
        "default": "'PENDING'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "attempts",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_error",
        "type": "character varying(2000)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_file_task_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_file_task_pkey ON nocode_biz_file_task USING btree (id)"
      },
      {
        "name": "nocode_biz_file_task_state_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_file_task_state_idx ON nocode_biz_file_task USING btree (task_type, state) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_file_task_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_file_task_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_file_task_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'DONE'::character varying, 'FAILED'::character varying])::text[])))"
      }
    ]
  }
]$json$::jsonb,$v47$-- DEC-20260928-01：网盘与业务融合。前置 V039。
-- 仅新增表与列：业务空间类型经 drive_space.type 新增 BIZ 编码（无 DDL 变更）；drive_entry 增加受管标记；
-- nocode 侧建立业务目录绑定、业务附件绑定、上传会话、保留引用与文件操作任务五张表。
-- 业务文件规则配置随对象版本 schemaJson 冻结，不建独立表；历史附件不迁移。
-- 身份列统一 NOT NULL DEFAULT ''（'' 表示该层级不适用），保证唯一约束对主表与明细行级身份同样生效。
-- 校验：Flyway verify、网盘业务融合专项集成测试（绑定/解绑/保留引用/清理任务）。

-- 受管节点标记：受业务目录规则管理的节点禁止普通网盘操作（移动、重命名、删除、分享、继承变更）
ALTER TABLE public.drive_entry
    ADD COLUMN managed_biz boolean NOT NULL DEFAULT false;
COMMENT ON COLUMN public.drive_entry.managed_biz IS '是否为业务受管节点：由无代码业务规则建立与调整，普通网盘操作入口拒绝修改';

-- 业务目录绑定：对象/记录/明细区/明细行/字段稳定身份 → 目录节点；幂等建目录与目录调整的依据
CREATE TABLE public.nocode_biz_directory_binding (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    object_id varchar(128) NOT NULL,
    record_id varchar(512) NOT NULL,
    detail_id varchar(128) NOT NULL DEFAULT '',
    row_id varchar(128) NOT NULL DEFAULT '',
    field_id varchar(128) NOT NULL DEFAULT '',
    space_id bigint NOT NULL,
    entry_id bigint NOT NULL,
    rule_version int NOT NULL,
    group_keys varchar(512) NOT NULL DEFAULT '',
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CONSTRAINT nocode_biz_directory_binding_uk UNIQUE (object_id, record_id, detail_id, row_id, field_id)
);
CREATE INDEX nocode_biz_directory_binding_entry_idx
    ON public.nocode_biz_directory_binding (space_id, entry_id) WHERE deleted=0;
CREATE INDEX nocode_biz_directory_binding_record_idx
    ON public.nocode_biz_directory_binding (object_id, record_id) WHERE deleted=0;
COMMENT ON TABLE public.nocode_biz_directory_binding IS '业务目录绑定：记录/明细区/明细行/字段身份到网盘目录节点的稳定映射；field_id 为空表示记录/明细区/明细行目录本身';
COMMENT ON COLUMN public.nocode_biz_directory_binding.detail_id IS '所属内部明细稳定 ID，空串表示主表';
COMMENT ON COLUMN public.nocode_biz_directory_binding.row_id IS '内部明细行持久 ID，空串表示非明细行级目录';
COMMENT ON COLUMN public.nocode_biz_directory_binding.field_id IS '附件字段稳定 ID，空串表示记录目录/明细区目录/明细行目录本身';
COMMENT ON COLUMN public.nocode_biz_directory_binding.group_keys IS '业务分组稳定键串（关联记录 ID 等），业务值变更时据此判断是否调整目录';
COMMENT ON COLUMN public.nocode_biz_directory_binding.rule_version IS '建立绑定时生效的对象发布版本号；已有记录沿用该版本，新规则只作用于新记录';

-- 业务附件绑定：业务表 fileId 与网盘文件节点的关联及当前/历史状态
CREATE TABLE public.nocode_biz_attachment_binding (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    object_id varchar(128) NOT NULL,
    record_id varchar(512) NOT NULL,
    detail_id varchar(128) NOT NULL DEFAULT '',
    row_id varchar(128) NOT NULL DEFAULT '',
    field_id varchar(128) NOT NULL,
    file_id bigint NOT NULL,
    entry_id bigint NOT NULL,
    space_id bigint NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'ACTIVE',
    source_entry varchar(32) NOT NULL DEFAULT '',
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CONSTRAINT nocode_biz_attachment_binding_uk UNIQUE (object_id, record_id, detail_id, row_id, field_id, file_id),
    CONSTRAINT nocode_biz_attachment_binding_state_ck CHECK (state IN ('ACTIVE','HISTORY'))
);
CREATE INDEX nocode_biz_attachment_binding_file_idx
    ON public.nocode_biz_attachment_binding (file_id) WHERE deleted=0;
CREATE INDEX nocode_biz_attachment_binding_record_idx
    ON public.nocode_biz_attachment_binding (object_id, record_id) WHERE deleted=0;
CREATE INDEX nocode_biz_attachment_binding_state_idx
    ON public.nocode_biz_attachment_binding (file_id, state) WHERE deleted=0;
COMMENT ON TABLE public.nocode_biz_attachment_binding IS '业务附件绑定：字段值中的 fileId 与网盘文件节点/空间的对应关系；移除附件转 HISTORY，不物理删除';
COMMENT ON COLUMN public.nocode_biz_attachment_binding.state IS 'ACTIVE 当前有效、HISTORY 已从当前值移除但历史引用仍保留';
COMMENT ON COLUMN public.nocode_biz_attachment_binding.source_entry IS '上传来源入口标识（应用/维护/任务），仅作来源信息，不参与归属判定';

-- 上传会话：受保护临时上传的归属与有效期；仅业务接入字段使用，普通系统附件不登记
CREATE TABLE public.nocode_biz_upload_session (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    session_key varchar(64) NOT NULL,
    user_id bigint NOT NULL,
    object_id varchar(128) NOT NULL,
    field_id varchar(128) NOT NULL,
    record_id varchar(512),
    file_id bigint NOT NULL,
    file_name varchar(255) NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'TEMPORARY',
    expires_at timestamp NOT NULL,
    idempotency_key varchar(128) NOT NULL DEFAULT '',
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CONSTRAINT nocode_biz_upload_session_uk UNIQUE (session_key, file_id),
    CONSTRAINT nocode_biz_upload_session_state_ck CHECK (state IN ('TEMPORARY','BOUND','EXPIRED','CLEANED'))
);
CREATE INDEX nocode_biz_upload_session_expire_idx
    ON public.nocode_biz_upload_session (state, expires_at) WHERE deleted=0;
CREATE INDEX nocode_biz_upload_session_file_idx
    ON public.nocode_biz_upload_session (file_id) WHERE deleted=0;
-- 幂等键只在显式提供时约束；空串表示未使用幂等，不参与唯一性
CREATE UNIQUE INDEX nocode_biz_upload_session_idem_uk
    ON public.nocode_biz_upload_session (user_id, idempotency_key)
    WHERE deleted=0 AND idempotency_key <> '';
COMMENT ON TABLE public.nocode_biz_upload_session IS '业务附件受保护上传会话：保存成功前仅上传者可用；有效期默认 24 小时，被有效草稿引用时续留';
COMMENT ON COLUMN public.nocode_biz_upload_session.state IS 'TEMPORARY 待保存、BOUND 已绑定记录、EXPIRED 已过期待清理、CLEANED 已清理';
COMMENT ON COLUMN public.nocode_biz_upload_session.idempotency_key IS '上传幂等键，同一用户重复提交不重复登记；空串表示不使用幂等';

-- 保留引用：业务修订、草稿、提交材料等持有者对文件的引用，阻止误删
CREATE TABLE public.nocode_biz_file_retention (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    file_id bigint NOT NULL,
    holder_type varchar(32) NOT NULL,
    holder_id varchar(128) NOT NULL,
    object_id varchar(128) NOT NULL,
    record_id varchar(512),
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CONSTRAINT nocode_biz_file_retention_uk UNIQUE (file_id, holder_type, holder_id)
);
CREATE INDEX nocode_biz_file_retention_file_idx
    ON public.nocode_biz_file_retention (file_id) WHERE deleted=0;
COMMENT ON TABLE public.nocode_biz_file_retention IS '文件保留引用：历史修订/工作草稿/提交材料等持有者的登记；有有效引用的文件不进入物理清理';
COMMENT ON COLUMN public.nocode_biz_file_retention.holder_type IS '持有者类型：RECORD_HISTORY 记录历史、WORK_DRAFT 工作草稿、WORK_SUBMISSION 提交材料';

-- 文件操作任务：对象存储补偿清理、目录批量整理、对账的执行状态与重试记录
CREATE TABLE public.nocode_biz_file_task (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    task_type varchar(32) NOT NULL,
    file_id bigint,
    payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    state varchar(16) NOT NULL DEFAULT 'PENDING',
    attempts integer NOT NULL DEFAULT 0,
    last_error varchar(2000),
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CONSTRAINT nocode_biz_file_task_state_ck CHECK (state IN ('PENDING','RUNNING','DONE','FAILED'))
);
CREATE INDEX nocode_biz_file_task_state_idx
    ON public.nocode_biz_file_task (task_type, state) WHERE deleted=0;
COMMENT ON TABLE public.nocode_biz_file_task IS '文件操作任务：物理删除补偿、目录整理等异步动作的持久状态；只登记已知操作，不扫描无归属文件';
$v47$);

-- V048 原文件 SHA-256: d5fb852acc85eec05b71cd413942f0b2cbe52f05956c2649424ccb6e49dacc66
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(48,$json$[
  {
    "table": "nocode_biz_attachment_binding",
    "column": "file_name"
  },
  {
    "table": "nocode_biz_attachment_binding",
    "column": "file_size"
  },
  {
    "table": "nocode_biz_attachment_binding",
    "column": "mime_type"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_biz_directory_binding",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(512)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "detail_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "row_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "space_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "rule_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "group_keys",
        "type": "character varying(512)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_directory_binding_entry_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_directory_binding_entry_idx ON nocode_biz_directory_binding USING btree (space_id, entry_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_directory_binding_group_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_directory_binding_group_idx ON nocode_biz_directory_binding USING btree (object_id, rule_version, group_keys) WHERE ((deleted = 0) AND ((detail_id)::text = ''::text) AND ((row_id)::text = ''::text) AND ((field_id)::text = ''::text))"
      },
      {
        "name": "nocode_biz_directory_binding_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_directory_binding_pkey ON nocode_biz_directory_binding USING btree (id)"
      },
      {
        "name": "nocode_biz_directory_binding_record_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_directory_binding_record_idx ON nocode_biz_directory_binding USING btree (object_id, record_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_directory_binding_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_directory_binding_uk ON nocode_biz_directory_binding USING btree (object_id, record_id, detail_id, row_id, field_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_directory_binding_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_directory_binding_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_directory_binding_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (object_id, record_id, detail_id, row_id, field_id)"
      }
    ]
  },
  {
    "table": "nocode_biz_attachment_binding",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(512)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "detail_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "row_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "space_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(16)",
        "default": "'ACTIVE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_entry",
        "type": "character varying(32)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_name",
        "type": "character varying(255)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_size",
        "type": "bigint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "mime_type",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_attachment_binding_browse_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_attachment_binding_browse_idx ON nocode_biz_attachment_binding USING btree (object_id, state, record_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_attachment_binding_file_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_attachment_binding_file_idx ON nocode_biz_attachment_binding USING btree (file_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_attachment_binding_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_attachment_binding_pkey ON nocode_biz_attachment_binding USING btree (id)"
      },
      {
        "name": "nocode_biz_attachment_binding_record_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_attachment_binding_record_idx ON nocode_biz_attachment_binding USING btree (object_id, record_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_attachment_binding_state_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_attachment_binding_state_idx ON nocode_biz_attachment_binding USING btree (file_id, state) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_attachment_binding_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_attachment_binding_uk ON nocode_biz_attachment_binding USING btree (object_id, record_id, detail_id, row_id, field_id, file_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_attachment_binding_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_attachment_binding_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_attachment_binding_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'HISTORY'::character varying])::text[])))"
      },
      {
        "name": "nocode_biz_attachment_binding_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (object_id, record_id, detail_id, row_id, field_id, file_id)"
      }
    ]
  }
]$json$::jsonb,$v48$-- P1-6：业务文件浏览/搜索/统计的权限过滤索引。前置 V040。
-- 业务附件绑定增加展示名、大小与类型冗余列：单业务入口的目录浏览、文件名搜索与容量统计
-- 必须与记录权限条件在同一条 SQL 内完成分页与计数，不能在取回全部文件后再过滤；
-- 受管节点展示名与内容在绑定后不可变（受管节点禁止普通重命名与覆盖），
-- 因此以绑定时网盘返回的节点信息写入冗余列，由对象/记录/字段/节点索引支撑查询。
-- 同时补充按（对象、状态、记录）与（对象、规则版本、分组键串）的浏览索引。

ALTER TABLE public.nocode_biz_attachment_binding
    ADD COLUMN file_name varchar(255) NOT NULL DEFAULT '',
    ADD COLUMN file_size bigint NOT NULL DEFAULT 0,
    ADD COLUMN mime_type varchar(128) NOT NULL DEFAULT '';
COMMENT ON COLUMN public.nocode_biz_attachment_binding.file_name IS '受管网盘节点展示名（绑定后不可变）；列表、搜索与统计与权限条件在同一 SQL 内过滤';
COMMENT ON COLUMN public.nocode_biz_attachment_binding.file_size IS '内容大小（字节），绑定时自文件底座冗余；用于权限过滤后的容量统计';
COMMENT ON COLUMN public.nocode_biz_attachment_binding.mime_type IS '内容 MIME 类型，绑定时自文件底座冗余；仅用于展示';

-- 既有绑定从网盘节点回填；节点已删除的历史绑定保持默认空值
UPDATE public.nocode_biz_attachment_binding b
SET file_name = COALESCE(e.name, ''),
    file_size = COALESCE(e.size, 0),
    mime_type = COALESCE(e.mime_type, '')
FROM public.drive_entry e
WHERE e.id = b.entry_id
  AND e.deleted = 0;

CREATE INDEX nocode_biz_attachment_binding_browse_idx
    ON public.nocode_biz_attachment_binding (object_id, state, record_id) WHERE deleted=0;
CREATE INDEX nocode_biz_directory_binding_group_idx
    ON public.nocode_biz_directory_binding (object_id, rule_version, group_keys)
    WHERE deleted=0 AND detail_id='' AND row_id='' AND field_id='';
$v48$);

-- V049 原文件 SHA-256: a02b150c26da114313d183d03455c03bce5d92107540ac4c1f6edac838b44b06
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(49,$json$[
  {
    "relation": "nocode_biz_file_retention_holder_idx"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_biz_file_retention",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "file_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "holder_type",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "holder_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(512)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_file_retention_file_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_file_retention_file_idx ON nocode_biz_file_retention USING btree (file_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_file_retention_holder_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_file_retention_holder_idx ON nocode_biz_file_retention USING btree (holder_type, holder_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_file_retention_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_file_retention_pkey ON nocode_biz_file_retention USING btree (id)"
      },
      {
        "name": "nocode_biz_file_retention_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_file_retention_uk ON nocode_biz_file_retention USING btree (file_id, holder_type, holder_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_file_retention_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_file_retention_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_file_retention_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (file_id, holder_type, holder_id)"
      }
    ]
  }
]$json$::jsonb,$v49$-- P1-6：文件保留引用按持有者查询索引。前置 V040。
-- 工作草稿重存与草稿作废需要按（持有者类型、持有者编号）整体物理释放登记；
-- 无该索引时只能全表扫描，保留引用表随历史修订持续增长。

CREATE INDEX nocode_biz_file_retention_holder_idx
    ON public.nocode_biz_file_retention (holder_type, holder_id) WHERE deleted=0;
$v49$);

-- V050 原文件 SHA-256: 1f0f2621ef55d8f5aab952d51e56c53df0e0ab8c359619d7f030e4219d7b049b
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(50,$json$[
  {
    "relation": "nocode_biz_file_mark"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_biz_file_mark",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "user_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "mark_type",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "access_time",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_file_mark_list_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_file_mark_list_idx ON nocode_biz_file_mark USING btree (user_id, object_id, mark_type, access_time DESC) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_file_mark_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_file_mark_pkey ON nocode_biz_file_mark USING btree (id)"
      },
      {
        "name": "nocode_biz_file_mark_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_file_mark_uk ON nocode_biz_file_mark USING btree (user_id, entry_id, mark_type) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_file_mark_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_file_mark_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_file_mark_type_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((mark_type)::text = ANY ((ARRAY['FAVORITE'::character varying, 'RECENT'::character varying])::text[])))"
      }
    ]
  }
]$json$::jsonb,$v50$-- DEC-20260928-01：网盘业务文件入口的用户标记与菜单。前置 V042。
-- 仅新增标记表与菜单定义：收藏/最近访问按用户独立保存于无代码侧（网盘侧标记按空间成员身份过滤，
-- 受管业务节点不在其授权链内），展示时仍按业务授权链重验；菜单只补定义，不向既有角色批量授权。
-- 校验：Flyway verify、表结构检查、菜单唯一性守卫、网盘业务文件专项集成测试（收藏/最近访问/定位链）。

-- 业务文件用户标记：收藏与最近访问；标记不参与任何授权判定
CREATE TABLE public.nocode_biz_file_mark (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL,
    object_id varchar(128) NOT NULL,
    entry_id bigint NOT NULL,
    mark_type varchar(16) NOT NULL,
    access_time timestamp,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CONSTRAINT nocode_biz_file_mark_type_ck CHECK (mark_type IN ('FAVORITE','RECENT'))
);
-- 部分唯一索引只覆盖未删除行：取消收藏走逻辑删除，再次收藏重新登记
CREATE UNIQUE INDEX nocode_biz_file_mark_uk
    ON public.nocode_biz_file_mark (user_id, entry_id, mark_type) WHERE deleted=0;
-- 列表按（用户、对象、类型）取标记时间倒序前 100
CREATE INDEX nocode_biz_file_mark_list_idx
    ON public.nocode_biz_file_mark (user_id, object_id, mark_type, access_time DESC) WHERE deleted=0;
COMMENT ON TABLE public.nocode_biz_file_mark IS '业务文件用户标记：收藏与最近访问，按用户独立，不改变节点授权也不作为读取依据';
COMMENT ON COLUMN public.nocode_biz_file_mark.entry_id IS '网盘受管节点身份，文件内容仍按业务位置身份重新校验';
COMMENT ON COLUMN public.nocode_biz_file_mark.mark_type IS '标记类型：FAVORITE 收藏、RECENT 最近访问';
COMMENT ON COLUMN public.nocode_biz_file_mark.access_time IS '最近访问时间，仅 RECENT 使用；收藏按登记时间排序';

-- 网盘上级菜单下新增「业务文件」二级菜单（组件 drive/business，权限 drive:business:query）
DO $$
DECLARE
    entry_id bigint;
    menu_count integer;
BEGIN
    SELECT min(id) INTO entry_id
    FROM public.system_menu WHERE deleted=0 AND type=1 AND path='/drive';
    IF entry_id IS NULL THEN
        RAISE EXCEPTION 'Drive entry menu missing; apply drive menu migration first';
    END IF;

    INSERT INTO public.system_menu
    (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
     visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
    SELECT nextval('public.system_menu_seq'),'业务文件','drive:business:query',2,8,entry_id,
           '/drive/business','FileSearchOutlined','drive/business','DriveBusiness',0,
           true,true,true,'biz-file-menu-migration','biz-file-menu-migration',now(),now(),0
    WHERE NOT EXISTS (
        SELECT 1 FROM public.system_menu existing
        WHERE existing.deleted=0 AND existing.path='/drive/business'
    );

    SELECT count(*) INTO menu_count
    FROM public.system_menu WHERE deleted=0 AND type=2 AND path='/drive/business';
    IF menu_count <> 1 THEN
        RAISE EXCEPTION 'Business file menu incomplete: % of 1 found', menu_count;
    END IF;
END $$;
$v50$);

-- V051 原文件 SHA-256: 1bff7e3a62d9046aabcdf731312e6addde19297d49154150216ca00fbe95236c
INSERT INTO nocode_release_plan(version,markers,contract,body) VALUES
(51,$json$[
  {
    "relation": "drive_space_biz_name_uk"
  }
]$json$::jsonb,$json$[
  {
    "table": "drive_space",
    "columns": [
      {
        "name": "owner_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "drive_space_biz_name_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX drive_space_biz_name_uk ON drive_space USING btree (name) WHERE (((type)::text = 'BIZ'::text) AND (deleted = 0))"
      }
    ],
    "constraints": []
  },
  {
    "table": "infra_job",
    "columns": [
      {
        "name": "powerjob_job_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [],
    "constraints": []
  },
  {
    "table": "nocode_biz_upload_session",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "session_key",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "user_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(512)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_name",
        "type": "character varying(255)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(16)",
        "default": "'TEMPORARY'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "expires_at",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "idempotency_key",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_upload_session_expire_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_upload_session_expire_idx ON nocode_biz_upload_session USING btree (state, expires_at) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_upload_session_file_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_upload_session_file_idx ON nocode_biz_upload_session USING btree (file_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_upload_session_idem_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_upload_session_idem_uk ON nocode_biz_upload_session USING btree (user_id, object_id, field_id, session_key, idempotency_key) WHERE ((deleted = 0) AND ((state)::text = ANY ((ARRAY['TEMPORARY'::character varying, 'BINDING'::character varying])::text[])) AND ((idempotency_key)::text <> ''::text))"
      },
      {
        "name": "nocode_biz_upload_session_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_upload_session_pkey ON nocode_biz_upload_session USING btree (id)"
      },
      {
        "name": "nocode_biz_upload_session_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_upload_session_uk ON nocode_biz_upload_session USING btree (session_key, file_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_upload_session_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_upload_session_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_upload_session_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['TEMPORARY'::character varying, 'BINDING'::character varying, 'BOUND'::character varying, 'EXPIRED'::character varying, 'CLEANED'::character varying])::text[])))"
      },
      {
        "name": "nocode_biz_upload_session_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (session_key, file_id)"
      }
    ]
  }
]$json$::jsonb,$v51$-- 网盘业务融合完整性修复。前置 V043。
-- 1. 业务空间不归属个人，owner_id 允许为空；同名活动业务空间唯一。
-- 2. 上传会话增加 BINDING 事务占用状态；幂等键收窄到同一用户/对象/字段/编辑会话的有效临时上传。
-- 3. 幂等登记过期上传清理任务；迁移后在定时任务页执行一次“同步任务”以创建 PowerJob 实例。

ALTER TABLE public.drive_space
    ALTER COLUMN owner_id DROP NOT NULL;

CREATE UNIQUE INDEX drive_space_biz_name_uk
    ON public.drive_space (name)
    WHERE type='BIZ' AND deleted=0;

COMMENT ON COLUMN public.drive_space.owner_id IS
    '归属主体用户编号：个人空间必填，团队空间为责任人，业务空间为空';

ALTER TABLE public.nocode_biz_upload_session
    DROP CONSTRAINT nocode_biz_upload_session_state_ck;
ALTER TABLE public.nocode_biz_upload_session
    ADD CONSTRAINT nocode_biz_upload_session_state_ck
        CHECK (state IN ('TEMPORARY','BINDING','BOUND','EXPIRED','CLEANED'));

DROP INDEX public.nocode_biz_upload_session_idem_uk;
CREATE UNIQUE INDEX nocode_biz_upload_session_idem_uk
    ON public.nocode_biz_upload_session
       (user_id, object_id, field_id, session_key, idempotency_key)
    WHERE deleted=0 AND state IN ('TEMPORARY','BINDING') AND idempotency_key <> '';
COMMENT ON COLUMN public.nocode_biz_upload_session.idempotency_key IS
    '上传幂等键：仅在同一用户/对象/字段/编辑会话的有效临时上传内唯一';

-- 旧库的 infra_job 尚未登记 PowerJob 远程标识；NULL 表示等待管理端“同步任务”建立并回填。
ALTER TABLE public.infra_job
    ADD COLUMN IF NOT EXISTS powerjob_job_id bigint;

INSERT INTO public.infra_job
    (id, powerjob_job_id, name, status, handler_name, handler_param,
     cron_expression, retry_count, retry_interval, monitor_timeout,
     creator, create_time, updater, update_time, deleted)
SELECT nextval('public.infra_job_seq'), NULL, '业务临时文件清理', 1,
       'bizUploadCleanJob', '', '0 */15 * * * ?', 3, 1000, 300000,
       'biz-file-migration', now(), 'biz-file-migration', now(), 0
WHERE NOT EXISTS (
    SELECT 1 FROM public.infra_job
    WHERE handler_name='bizUploadCleanJob' AND deleted=0
);
$v51$);

CREATE TEMP TABLE nocode_release_final (contract jsonb NOT NULL) ON COMMIT DROP;
INSERT INTO nocode_release_final VALUES ($json$[
  {
    "table": "drive_storage_setting",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "config_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp(6) without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp(6) without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "drive_storage_setting_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX drive_storage_setting_pkey ON drive_storage_setting USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "drive_storage_setting_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "drive_storage_setting_id_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((id = 1))"
      },
      {
        "name": "drive_storage_setting_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "nocode_ordered_calculation_state",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "signature",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(24)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "cursor_json",
        "type": "jsonb",
        "default": "'{}'::jsonb",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "total_rows",
        "type": "bigint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updated_rows",
        "type": "bigint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "completed_groups",
        "type": "bigint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "error_message",
        "type": "character varying(2000)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "bigint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_ordered_calculation_state_field",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_ordered_calculation_state_field ON nocode_ordered_calculation_state USING btree (object_id, field_id)"
      },
      {
        "name": "nocode_ordered_calculation_state_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_ordered_calculation_state_pkey ON nocode_ordered_calculation_state USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_ordered_calculation_state_code",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'BACKFILLING'::character varying, 'READY'::character varying, 'FAILED'::character varying])::text[])))"
      },
      {
        "name": "nocode_ordered_calculation_state_field",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (object_id, field_id)"
      },
      {
        "name": "nocode_ordered_calculation_state_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "nocode_task_instance",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "root_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "parent_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "title",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "assignee_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "status",
        "type": "character varying(20)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "config_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "business_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "project_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "template_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "template_version",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "t0",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "baseline_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "baseline_end",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "expected_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "expected_end",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "actual_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "actual_end",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_key",
        "type": "character varying(120)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_hash",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "kind",
        "type": "character varying(20)",
        "default": "'ORDINARY'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "template_node_id",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_assignee_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_assignee_idx ON nocode_task_instance USING btree (assignee_id, status, expected_end) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_instance_kind_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_instance_kind_idx ON nocode_task_instance USING btree (kind, assignee_id, status, expected_end) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_instance_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_instance_pkey ON nocode_task_instance USING btree (id)"
      },
      {
        "name": "nocode_task_request_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_request_uk ON nocode_task_instance USING btree (creator, request_key) WHERE (request_key IS NOT NULL)"
      },
      {
        "name": "nocode_task_root_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_root_idx ON nocode_task_instance USING btree (root_id) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_instance_kind_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((kind)::text = ANY ((ARRAY['ORDINARY'::character varying, 'PROCESS'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_instance_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))"
      }
    ]
  },
  {
    "table": "nocode_task_template",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "name",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "description",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "published_version",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "nodes_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "kind",
        "type": "character varying(20)",
        "default": "'ORDINARY'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_template_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_template_pkey ON nocode_task_template USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_template_kind_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((kind)::text = ANY ((ARRAY['ORDINARY'::character varying, 'PROCESS'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_template_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "nocode_task_template_version",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "template_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "version_no",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "name",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "description",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "nodes_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "bindings_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "kind",
        "type": "character varying(20)",
        "default": "'ORDINARY'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_template_version_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_template_version_pkey ON nocode_task_template_version USING btree (id)"
      },
      {
        "name": "nocode_task_template_version_template_id_version_no_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_template_version_template_id_version_no_key ON nocode_task_template_version USING btree (template_id, version_no)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_template_version_kind_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((kind)::text = ANY ((ARRAY['ORDINARY'::character varying, 'PROCESS'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_template_version_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_template_version_template_id_version_no_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (template_id, version_no)"
      }
    ]
  },
  {
    "table": "nocode_task_plan",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "user_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "period",
        "type": "character varying(10)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "plan_date",
        "type": "date",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "arranged_by_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source",
        "type": "character varying(20)",
        "default": "'SELF'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "arranged_at",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_plan_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_pkey ON nocode_task_plan USING btree (id)"
      },
      {
        "name": "nocode_task_plan_task_id_user_id_period_plan_date_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_task_id_user_id_period_plan_date_key ON nocode_task_plan USING btree (task_id, user_id, period, plan_date)"
      },
      {
        "name": "nocode_task_plan_user_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_plan_user_idx ON nocode_task_plan USING btree (user_id, period, plan_date) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_plan_period_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((period)::text = ANY ((ARRAY['DAY'::character varying, 'WEEK'::character varying, 'MONTH'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_plan_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_plan_source_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((source)::text = ANY ((ARRAY['SELF'::character varying, 'MANAGER'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_plan_task_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (task_id) REFERENCES nocode_task_instance(id)"
      },
      {
        "name": "nocode_task_plan_task_id_user_id_period_plan_date_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (task_id, user_id, period, plan_date)"
      }
    ]
  },
  {
    "table": "nocode_task_comment",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "parent_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "content",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "mentioned_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_key",
        "type": "character varying(120)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_hash",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_comment_creator_request_key_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_comment_creator_request_key_key ON nocode_task_comment USING btree (creator, request_key)"
      },
      {
        "name": "nocode_task_comment_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_comment_pkey ON nocode_task_comment USING btree (id)"
      },
      {
        "name": "nocode_task_comment_task_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_comment_task_idx ON nocode_task_comment USING btree (task_id, create_time) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_comment_creator_request_key_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (creator, request_key)"
      },
      {
        "name": "nocode_task_comment_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_comment_task_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (task_id) REFERENCES nocode_task_instance(id)"
      }
    ]
  },
  {
    "table": "nocode_task_event",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "root_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "event_type",
        "type": "character varying(30)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "note",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "material_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_key",
        "type": "character varying(120)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_hash",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_event_actor_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_event_actor_idx ON nocode_task_event USING btree (creator, create_time DESC) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_event_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_event_pkey ON nocode_task_event USING btree (id)"
      },
      {
        "name": "nocode_task_event_request_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_event_request_uk ON nocode_task_event USING btree (creator, request_key) WHERE (request_key IS NOT NULL)"
      },
      {
        "name": "nocode_task_event_root_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_event_root_idx ON nocode_task_event USING btree (root_id, create_time) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_event_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "nocode_task_record_link",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "application_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "label",
        "type": "character varying(200)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_record_link_context_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_record_link_context_idx ON nocode_task_record_link USING btree (application_id, object_id, record_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_record_link_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_record_link_pkey ON nocode_task_record_link USING btree (id)"
      },
      {
        "name": "nocode_task_record_link_task_id_application_id_object_id_re_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_record_link_task_id_application_id_object_id_re_key ON nocode_task_record_link USING btree (task_id, application_id, object_id, record_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_record_link_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_record_link_task_id_application_id_object_id_re_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (task_id, application_id, object_id, record_id)"
      },
      {
        "name": "nocode_task_record_link_task_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (task_id) REFERENCES nocode_task_instance(id)"
      }
    ]
  },
  {
    "table": "nocode_task_entry_binding",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_key",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "dataset_id",
        "type": "character varying(160)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "config_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "business_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "inherited",
        "type": "boolean",
        "default": "false",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "submitted_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_entry_binding_dataset_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_entry_binding_dataset_idx ON nocode_task_entry_binding USING btree (dataset_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_entry_binding_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_binding_pkey ON nocode_task_entry_binding USING btree (id)"
      },
      {
        "name": "nocode_task_entry_binding_task_id_entry_key_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_binding_task_id_entry_key_key ON nocode_task_entry_binding USING btree (task_id, entry_key)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_entry_binding_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_entry_binding_task_id_entry_key_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (task_id, entry_key)"
      },
      {
        "name": "nocode_task_entry_binding_task_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (task_id) REFERENCES nocode_task_instance(id)"
      }
    ]
  },
  {
    "table": "nocode_task_entry_record",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_key",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "dataset_id",
        "type": "character varying(160)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "business_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "operation",
        "type": "character varying(20)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "superseded_by",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_revision",
        "type": "character varying(200)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "snapshot_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_key",
        "type": "character varying(120)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_hash",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_entry_record_creator_request_key_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_record_creator_request_key_key ON nocode_task_entry_record USING btree (creator, request_key)"
      },
      {
        "name": "nocode_task_entry_record_dataset_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_entry_record_dataset_idx ON nocode_task_entry_record USING btree (dataset_id, create_time) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_entry_record_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_record_pkey ON nocode_task_entry_record USING btree (id)"
      },
      {
        "name": "nocode_task_entry_record_request_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_entry_record_request_idx ON nocode_task_entry_record USING btree ((((business_json)::jsonb ->> 'requestId'::text))) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_entry_record_task_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_entry_record_task_idx ON nocode_task_entry_record USING btree (task_id) WHERE ((deleted = 0) AND (superseded_by IS NULL))"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_entry_record_creator_request_key_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (creator, request_key)"
      },
      {
        "name": "nocode_task_entry_record_operation_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((operation)::text = ANY ((ARRAY['CREATED'::character varying, 'UPDATED'::character varying, 'LINKED'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_entry_record_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_entry_record_task_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (task_id) REFERENCES nocode_task_instance(id)"
      }
    ]
  },
  {
    "table": "nocode_task_entry_template_version",
    "columns": [
      {
        "name": "id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "template_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "version_no",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "bindings_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_entry_template_version_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_template_version_pkey ON nocode_task_entry_template_version USING btree (id)"
      },
      {
        "name": "nocode_task_entry_template_version_template_id_version_no_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_entry_template_version_template_id_version_no_key ON nocode_task_entry_template_version USING btree (template_id, version_no)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_task_entry_template_version_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_task_entry_template_version_template_id_version_no_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (template_id, version_no)"
      }
    ]
  },
  {
    "table": "drive_entry",
    "columns": [
      {
        "name": "managed_biz",
        "type": "boolean",
        "default": "false",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [],
    "constraints": []
  },
  {
    "table": "drive_space",
    "columns": [
      {
        "name": "owner_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "drive_space_biz_name_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX drive_space_biz_name_uk ON drive_space USING btree (name) WHERE (((type)::text = 'BIZ'::text) AND (deleted = 0))"
      }
    ],
    "constraints": []
  },
  {
    "table": "infra_job",
    "columns": [
      {
        "name": "powerjob_job_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [],
    "constraints": []
  },
  {
    "table": "nocode_biz_directory_binding",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(512)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "detail_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "row_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "space_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "rule_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "group_keys",
        "type": "character varying(512)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_directory_binding_entry_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_directory_binding_entry_idx ON nocode_biz_directory_binding USING btree (space_id, entry_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_directory_binding_group_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_directory_binding_group_idx ON nocode_biz_directory_binding USING btree (object_id, rule_version, group_keys) WHERE ((deleted = 0) AND ((detail_id)::text = ''::text) AND ((row_id)::text = ''::text) AND ((field_id)::text = ''::text))"
      },
      {
        "name": "nocode_biz_directory_binding_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_directory_binding_pkey ON nocode_biz_directory_binding USING btree (id)"
      },
      {
        "name": "nocode_biz_directory_binding_record_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_directory_binding_record_idx ON nocode_biz_directory_binding USING btree (object_id, record_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_directory_binding_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_directory_binding_uk ON nocode_biz_directory_binding USING btree (object_id, record_id, detail_id, row_id, field_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_directory_binding_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_directory_binding_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_directory_binding_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (object_id, record_id, detail_id, row_id, field_id)"
      }
    ]
  },
  {
    "table": "nocode_biz_attachment_binding",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(512)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "detail_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "row_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "space_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(16)",
        "default": "'ACTIVE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_entry",
        "type": "character varying(32)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_name",
        "type": "character varying(255)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_size",
        "type": "bigint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "mime_type",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_attachment_binding_browse_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_attachment_binding_browse_idx ON nocode_biz_attachment_binding USING btree (object_id, state, record_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_attachment_binding_file_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_attachment_binding_file_idx ON nocode_biz_attachment_binding USING btree (file_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_attachment_binding_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_attachment_binding_pkey ON nocode_biz_attachment_binding USING btree (id)"
      },
      {
        "name": "nocode_biz_attachment_binding_record_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_attachment_binding_record_idx ON nocode_biz_attachment_binding USING btree (object_id, record_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_attachment_binding_state_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_attachment_binding_state_idx ON nocode_biz_attachment_binding USING btree (file_id, state) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_attachment_binding_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_attachment_binding_uk ON nocode_biz_attachment_binding USING btree (object_id, record_id, detail_id, row_id, field_id, file_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_attachment_binding_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_attachment_binding_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_attachment_binding_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['ACTIVE'::character varying, 'HISTORY'::character varying])::text[])))"
      },
      {
        "name": "nocode_biz_attachment_binding_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (object_id, record_id, detail_id, row_id, field_id, file_id)"
      }
    ]
  },
  {
    "table": "nocode_biz_upload_session",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "session_key",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "user_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(512)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_name",
        "type": "character varying(255)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(16)",
        "default": "'TEMPORARY'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "expires_at",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "idempotency_key",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_upload_session_expire_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_upload_session_expire_idx ON nocode_biz_upload_session USING btree (state, expires_at) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_upload_session_file_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_upload_session_file_idx ON nocode_biz_upload_session USING btree (file_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_upload_session_idem_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_upload_session_idem_uk ON nocode_biz_upload_session USING btree (user_id, object_id, field_id, session_key, idempotency_key) WHERE ((deleted = 0) AND ((state)::text = ANY ((ARRAY['TEMPORARY'::character varying, 'BINDING'::character varying])::text[])) AND ((idempotency_key)::text <> ''::text))"
      },
      {
        "name": "nocode_biz_upload_session_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_upload_session_pkey ON nocode_biz_upload_session USING btree (id)"
      },
      {
        "name": "nocode_biz_upload_session_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_upload_session_uk ON nocode_biz_upload_session USING btree (session_key, file_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_upload_session_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_upload_session_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_upload_session_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['TEMPORARY'::character varying, 'BINDING'::character varying, 'BOUND'::character varying, 'EXPIRED'::character varying, 'CLEANED'::character varying])::text[])))"
      },
      {
        "name": "nocode_biz_upload_session_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (session_key, file_id)"
      }
    ]
  },
  {
    "table": "nocode_biz_file_retention",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "file_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "holder_type",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "holder_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "record_id",
        "type": "character varying(512)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_file_retention_file_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_file_retention_file_idx ON nocode_biz_file_retention USING btree (file_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_file_retention_holder_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_file_retention_holder_idx ON nocode_biz_file_retention USING btree (holder_type, holder_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_file_retention_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_file_retention_pkey ON nocode_biz_file_retention USING btree (id)"
      },
      {
        "name": "nocode_biz_file_retention_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_file_retention_uk ON nocode_biz_file_retention USING btree (file_id, holder_type, holder_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_file_retention_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_file_retention_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_file_retention_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (file_id, holder_type, holder_id)"
      }
    ]
  },
  {
    "table": "nocode_biz_file_task",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "task_type",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "file_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "payload_json",
        "type": "jsonb",
        "default": "'{}'::jsonb",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(16)",
        "default": "'PENDING'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "attempts",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_error",
        "type": "character varying(2000)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_file_task_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_file_task_pkey ON nocode_biz_file_task USING btree (id)"
      },
      {
        "name": "nocode_biz_file_task_state_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_file_task_state_idx ON nocode_biz_file_task USING btree (task_type, state) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_file_task_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_file_task_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_file_task_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'DONE'::character varying, 'FAILED'::character varying])::text[])))"
      }
    ]
  },
  {
    "table": "nocode_biz_file_mark",
    "columns": [
      {
        "name": "id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "d",
        "generated": ""
      },
      {
        "name": "user_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "object_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "mark_type",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "access_time",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "creator",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "updater",
        "type": "character varying(64)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "update_time",
        "type": "timestamp without time zone",
        "default": "now()",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_biz_file_mark_list_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_biz_file_mark_list_idx ON nocode_biz_file_mark USING btree (user_id, object_id, mark_type, access_time DESC) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_biz_file_mark_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_file_mark_pkey ON nocode_biz_file_mark USING btree (id)"
      },
      {
        "name": "nocode_biz_file_mark_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_biz_file_mark_uk ON nocode_biz_file_mark USING btree (user_id, entry_id, mark_type) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_biz_file_mark_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_biz_file_mark_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_biz_file_mark_type_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((mark_type)::text = ANY ((ARRAY['FAVORITE'::character varying, 'RECENT'::character varying])::text[])))"
      }
    ]
  }
]$json$::jsonb);

-- 所有校验辅助对象仅存在于当前连接的临时 schema，连接结束自动消失。
CREATE OR REPLACE FUNCTION pg_temp.nocode_release_contract(expected jsonb) RETURNS void
LANGUAGE plpgsql AS $contract$
DECLARE t jsonb; x jsonb; tab oid; final_table jsonb; alternatives jsonb;
BEGIN
 FOR t IN SELECT * FROM jsonb_array_elements(expected) LOOP
  tab:=to_regclass('public.' || (t->>'table'));
  SELECT value INTO final_table FROM nocode_release_final,
       LATERAL jsonb_array_elements(contract) WHERE value->>'table'=t->>'table';
  IF tab IS NULL OR NOT EXISTS(SELECT 1 FROM pg_class WHERE oid=tab AND relkind='r'
     AND NOT relrowsecurity AND NOT relforcerowsecurity) THEN
   RAISE EXCEPTION '表 % 缺失、类型错误或存在额外行安全策略，停止升级。',t->>'table';
  END IF;
  FOR x IN SELECT * FROM jsonb_array_elements(t->'columns') LOOP
   SELECT jsonb_build_array(x)||COALESCE(jsonb_agg(value),'[]'::jsonb) INTO alternatives
       FROM jsonb_array_elements(final_table->'columns') WHERE value->>'name'=x->>'name';
   IF NOT EXISTS(SELECT 1 FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid=a.attrelid AND d.adnum=a.attnum
       CROSS JOIN LATERAL jsonb_array_elements(alternatives) candidate
       WHERE a.attrelid=tab AND a.attname=x->>'name' AND a.attnum>0 AND NOT a.attisdropped
       AND format_type(a.atttypid,a.atttypmod)=candidate->>'type' AND a.attnotnull=(candidate->>'notnull')::boolean
       AND a.attidentity::text=candidate->>'identity' AND a.attgenerated::text=candidate->>'generated'
       AND pg_get_expr(d.adbin,d.adrelid) IS NOT DISTINCT FROM candidate->>'default') THEN
    RAISE EXCEPTION '列 %.% 与原始迁移定义不一致，停止升级。',t->>'table',x->>'name';
   END IF;
  END LOOP;
  FOR x IN SELECT * FROM jsonb_array_elements(t->'constraints') LOOP
   SELECT jsonb_build_array(x)||COALESCE(jsonb_agg(value),'[]'::jsonb) INTO alternatives
       FROM jsonb_array_elements(final_table->'constraints') WHERE value->>'name'=x->>'name';
   IF NOT EXISTS(SELECT 1 FROM pg_constraint c CROSS JOIN LATERAL jsonb_array_elements(alternatives) candidate
       WHERE c.conrelid=tab AND c.conname=x->>'name'
       AND c.convalidated AND NOT c.condeferrable
       AND regexp_replace(pg_get_constraintdef(c.oid),'(public|pg_temp(_[0-9]+)?)\.', '', 'g')=candidate->>'definition') THEN
    RAISE EXCEPTION '约束 %.% 与原始迁移定义不一致，停止升级。',t->>'table',x->>'name';
   END IF;
  END LOOP;
  FOR x IN SELECT * FROM jsonb_array_elements(t->'indexes') LOOP
   SELECT jsonb_build_array(x)||COALESCE(jsonb_agg(value),'[]'::jsonb) INTO alternatives
       FROM jsonb_array_elements(final_table->'indexes') WHERE value->>'name'=x->>'name';
   IF NOT EXISTS(SELECT 1 FROM pg_index i JOIN pg_class n ON n.oid=i.indexrelid
       CROSS JOIN LATERAL jsonb_array_elements(alternatives) candidate
       WHERE i.indrelid=tab AND n.relname=x->>'name' AND i.indisvalid AND i.indisready
       AND regexp_replace(pg_get_indexdef(i.indexrelid),'(public|pg_temp(_[0-9]+)?)\.', '', 'g')=candidate->>'definition') THEN
    RAISE EXCEPTION '索引 %.% 与原始迁移定义不一致，停止升级。',t->>'table',x->>'name';
   END IF;
  END LOOP;
 END LOOP;
END $contract$;

DO $release$
DECLARE
    has_history boolean:=to_regclass('public.nocode_schema_history') IS NOT NULL;
    complete_history boolean:=false;
    recorded boolean;
    p record; identity_row record; marker jsonb; marker_present boolean;
    present_count integer; max_version integer; required_name text;
    drive_menu bigint; dashboard bigint; mine bigint; folder bigint; menu_id bigint;
    expected_menu record; receipt oid; receipt_index oid;
    started_at timestamptz; applied integer:=0; checked integer:=0;
BEGIN
    IF current_setting('server_version_num')::integer<150000 THEN
        RAISE EXCEPTION '本增量要求 PostgreSQL 15 或更新版本。';
    END IF;
    PERFORM pg_advisory_xact_lock(20261001,3846);
    FOREACH required_name IN ARRAY ARRAY['nocode_object','nocode_application','nocode_document_receipt',
        'infra_file_config','system_menu','system_menu_seq','system_role_menu','system_role_menu_seq','sys_msg_template',
        'drive_entry','drive_space','infra_job','infra_job_seq'] LOOP
        IF to_regclass('public.'||required_name) IS NULL THEN
            RAISE EXCEPTION '缺少基线对象 %，目标库不符合本次升级前提。',required_name;
        END IF;
    END LOOP;
    -- V037 是本次增量的真实前提，不按部署日期猜测线上结构，也不重放之前的清理迁移。
    receipt:=to_regclass('public.nocode_document_receipt');
    receipt_index:=to_regclass('public.nocode_document_receipt_maintenance_request');
    IF NOT EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid=receipt AND attname='application_id'
        AND NOT attisdropped AND atttypid='bigint'::regtype AND NOT attnotnull)
       OR NOT EXISTS(SELECT 1 FROM pg_index i WHERE i.indexrelid=receipt_index AND i.indrelid=receipt
         AND i.indisunique AND i.indisvalid AND i.indisready AND NOT i.indnullsnotdistinct
         AND i.indnkeyatts=4 AND i.indnatts=4 AND i.indexprs IS NULL
         AND pg_get_expr(i.indpred,i.indrelid)='(application_id IS NULL)'
         AND (SELECT array_agg(a.attname::text ORDER BY k.n) FROM unnest(i.indkey) WITH ORDINALITY k(num,n)
              JOIN pg_attribute a ON a.attrelid=receipt AND a.attnum=k.num)
             =ARRAY['creator','object_id','operation','request_key']::text[]) THEN
        RAISE EXCEPTION 'V037 收据表实际结构未就绪；需先完成此前 V037 增量，本文件不会执行旧版本。';
    END IF;
    IF has_history THEN
        LOCK TABLE public.nocode_schema_history IN EXCLUSIVE MODE;
        IF EXISTS(SELECT 1 FROM public.nocode_schema_history WHERE NOT success)
           OR EXISTS(SELECT 1 FROM public.nocode_schema_history
                     WHERE version IS NOT NULL AND (version !~ '^[0-9]+$'
                     OR CASE WHEN version ~ '^[0-9]+$' THEN version::numeric>51 ELSE false END)) THEN
            RAISE EXCEPTION '迁移历史有失败项、未知版本或高于 V051，停止升级。';
        END IF;
        FOR identity_row IN SELECT * FROM nocode_release_identity LOOP
            IF (SELECT count(*) FROM public.nocode_schema_history
                WHERE ltrim(version,'0')=identity_row.version::text)>1
               OR EXISTS(SELECT 1 FROM public.nocode_schema_history h
                   WHERE ltrim(h.version,'0')=identity_row.version::text
                     AND (h.type='SQL' OR identity_row.version>=37)
                     AND (h.type<>'SQL' OR h.script IS DISTINCT FROM identity_row.script
                          OR h.checksum IS DISTINCT FROM identity_row.checksum)) THEN
                RAISE EXCEPTION 'V% 历史重复或校验和/文件身份不一致；不会自动 repair。',identity_row.version;
            END IF;
        END LOOP;
        SELECT max(version::integer) INTO max_version FROM public.nocode_schema_history WHERE version ~ '^[0-9]+$';
        SELECT max_version>=37 AND NOT EXISTS(SELECT 1 FROM generate_series(1,max_version) n
            WHERE NOT EXISTS(SELECT 1 FROM public.nocode_schema_history h
                WHERE ltrim(h.version,'0')=n::text AND h.type='SQL' AND h.success)) INTO complete_history;
        IF complete_history IS DISTINCT FROM true THEN
            RAISE NOTICE '历史不连续完整：按真实结构执行增量，保留原历史，不补造或跳号登记。';
        END IF;
    ELSE
        RAISE NOTICE '无迁移历史表：按真实结构执行增量，不创建历史表、不补造旧版本。';
    END IF;

    LOCK TABLE public.system_menu,public.system_role_menu,public.sys_msg_template IN SHARE ROW EXCLUSIVE MODE;
    SELECT id INTO STRICT drive_menu FROM public.system_menu WHERE deleted=0 AND type=2 AND path='/drive/space';
    SELECT id INTO STRICT dashboard FROM public.system_menu WHERE deleted=0 AND parent_id=0 AND path='/dashboard';
    SELECT id INTO STRICT mine FROM public.system_menu WHERE deleted=0 AND type=2 AND path='/nocode-app/task-center';

    FOR p IN SELECT * FROM nocode_release_plan ORDER BY version LOOP
        started_at:=clock_timestamp(); recorded:=false; present_count:=0;
        IF has_history THEN
            SELECT EXISTS(SELECT 1 FROM public.nocode_schema_history WHERE ltrim(version,'0')=p.version::text)
            INTO recorded;
        END IF;
        FOR marker IN SELECT * FROM jsonb_array_elements(p.markers) LOOP
            IF marker ? 'relation' THEN
                marker_present:=to_regclass('public.'||(marker->>'relation')) IS NOT NULL;
            ELSIF marker ? 'column' THEN
                SELECT EXISTS(SELECT 1 FROM pg_attribute WHERE attrelid=to_regclass('public.'||(marker->>'table'))
                  AND attname=marker->>'column' AND NOT attisdropped AND attnum>0) INTO marker_present;
            ELSIF marker ? 'permission' THEN
                SELECT EXISTS(SELECT 1 FROM public.system_menu WHERE deleted=0 AND permission=marker->>'permission') INTO marker_present;
            ELSIF marker ? 'topLevel' THEN
                SELECT EXISTS(SELECT 1 FROM public.system_menu WHERE deleted=0 AND path=marker->>'topLevel' AND parent_id=0) INTO marker_present;
            END IF;
            IF marker_present THEN present_count:=present_count+1; END IF;
        END LOOP;
        IF present_count<>0 AND present_count<>jsonb_array_length(p.markers) THEN
            RAISE EXCEPTION 'V% 结构仅存在一部分（%/%），保留现场并停止；不猜测修复或删除已有表。',p.version,present_count,jsonb_array_length(p.markers);
        END IF;
        IF recorded AND present_count=0 THEN
            RAISE EXCEPTION 'V% 已登记但结构或必要配置缺失，停止；不重建空表掩盖数据缺失。',p.version;
        END IF;
        IF present_count=0 THEN
            -- 对不可重复的数据迁移只执行一次，重跑不能覆盖后来用户维护的值。
            IF p.version=40 THEN
                IF EXISTS(SELECT 1 FROM public.system_menu WHERE deleted=0 AND path IN
                    ('/task-center','/nocode-app/task-center/manage','/nocode-app/task-center/launch','/nocode-app/task-center/templates'))
                    OR EXISTS(SELECT 1 FROM public.sys_msg_template WHERE id=-9040001 AND (code<>'nocode-task-comment' OR deleted<>0)) THEN
                    RAISE EXCEPTION '任务中心菜单或消息模板 ID 与旧数据冲突，停止升级。';
                END IF;
            END IF;
            EXECUTE p.body;
            applied:=applied+1;
            RAISE NOTICE 'V%：本次已执行原始增量。',p.version;
        ELSE
            checked:=checked+1;
            RAISE NOTICE 'V%：结构已存在，核验并保留已有数据。',p.version;
        END IF;
        PERFORM pg_temp.nocode_release_contract(p.contract);
        IF p.version=38 THEN
            IF (SELECT count(*) FROM public.drive_storage_setting WHERE id=1)<>1 THEN
                RAISE EXCEPTION '网盘存储配置单例缺失，停止升级。';
            END IF;
            FOREACH required_name IN ARRAY ARRAY['drive:storage:query','drive:storage:update'] LOOP
                IF (SELECT count(*) FROM public.system_menu WHERE permission=required_name AND deleted=0)<>1
                   OR NOT EXISTS(SELECT 1 FROM public.system_menu WHERE permission=required_name AND deleted=0 AND type=3 AND parent_id=drive_menu) THEN
                    RAISE EXCEPTION '网盘权限 % 缺失、重复或挂载错误。',required_name;
                END IF;
            END LOOP;
        ELSIF p.version=40 THEN
            SELECT id INTO STRICT folder FROM public.system_menu WHERE deleted=0 AND path='/task-center' AND type=1;
            IF NOT EXISTS(SELECT 1 FROM public.system_menu WHERE id=folder AND creator='task-center-migration' AND parent_id IN (0,dashboard)) THEN
                RAISE EXCEPTION '任务中心目录与原始迁移不一致，停止升级。';
            END IF;
            FOR expected_menu IN SELECT * FROM (VALUES
                ('/nocode-app/task-center','nocode:task:query'),
                ('/nocode-app/task-center/manage','nocode:task:query'),
                ('/nocode-app/task-center/launch','nocode:task:create'),
                ('/nocode-app/task-center/templates','nocode:task:template')) m(path,permission) LOOP
                IF (SELECT count(*) FROM public.system_menu WHERE path=expected_menu.path AND deleted=0)<>1
                   OR NOT EXISTS(SELECT 1 FROM public.system_menu WHERE path=expected_menu.path AND deleted=0
                         AND parent_id=folder AND type=2 AND permission=expected_menu.permission AND component='nocode/task-center/index') THEN
                    RAISE EXCEPTION '任务子菜单 % 缺失、重复或定义不一致。',expected_menu.path;
                END IF;
            END LOOP;
            IF (SELECT count(*) FROM public.sys_msg_template WHERE code='nocode-task-comment' AND deleted=0)<>1 THEN
                RAISE EXCEPTION '任务评论消息模板缺失或重复。';
            END IF;
        ELSIF p.version=41 THEN
            SELECT id INTO STRICT menu_id FROM public.system_menu WHERE path='/nocode-app/task-center/manage' AND deleted=0;
            IF (SELECT count(*) FROM public.system_menu WHERE permission='nocode:task:manage-all' AND deleted=0)<>1
               OR NOT EXISTS(SELECT 1 FROM public.system_menu WHERE permission='nocode:task:manage-all' AND deleted=0 AND type=3 AND parent_id=menu_id) THEN
                RAISE EXCEPTION '管理全部任务权限定义缺失、重复或挂载错误。';
            END IF;
        ELSIF p.version=42 THEN
            IF NOT EXISTS(SELECT 1 FROM public.system_menu WHERE id=folder AND parent_id=0) THEN
                RAISE EXCEPTION '任务中心未成为一级导航，停止升级。';
            END IF;
        ELSIF p.version=50 THEN
            SELECT id INTO STRICT menu_id FROM public.system_menu WHERE deleted=0 AND type=1 AND path='/drive';
            IF (SELECT count(*) FROM public.system_menu WHERE deleted=0 AND path='/drive/business')<>1
               OR NOT EXISTS(SELECT 1 FROM public.system_menu WHERE deleted=0 AND path='/drive/business'
                   AND type=2 AND parent_id=menu_id AND component='drive/business' AND permission='drive:business:query') THEN
                RAISE EXCEPTION '业务文件菜单缺失、重复或挂载错误。';
            END IF;
        ELSIF p.version=51 THEN
            IF (SELECT count(*) FROM public.infra_job WHERE deleted=0 AND handler_name='bizUploadCleanJob')<>1 THEN
                RAISE EXCEPTION '业务临时文件清理任务缺失或重复。';
            END IF;
        END IF;
        IF has_history AND complete_history AND NOT recorded THEN
            SELECT * INTO STRICT identity_row FROM nocode_release_identity WHERE version=p.version;
            INSERT INTO public.nocode_schema_history(installed_rank,version,description,type,script,checksum,
                installed_by,installed_on,execution_time,success)
            SELECT COALESCE(max(installed_rank),0)+1,lpad(p.version::text,3,'0'),identity_row.description,'SQL',
                identity_row.script,identity_row.checksum,current_user,localtimestamp,
                (extract(epoch FROM clock_timestamp()-started_at)*1000)::integer,true FROM public.nocode_schema_history;
        END IF;
    END LOOP;
    PERFORM pg_temp.nocode_release_contract((SELECT contract FROM nocode_release_final));
    RAISE NOTICE 'V038-V051 全部核验通过；本次执行 % 个版本，保留并核验 % 个版本。',applied,checked;
    RAISE NOTICE '最终是否提交以 run-sql.sh 的“执行成功，事务已提交”及退出码 0 为准。';
END $release$;

SELECT 'V051' AS target_version,'V038-V051 全部完成' AS result,
       (SELECT count(*) FROM public.system_menu WHERE deleted=0 AND path='/task-center' AND parent_id=0) AS task_center_l1,
       (SELECT count(*) FROM public.system_menu WHERE deleted=0 AND permission='nocode:task:manage-all') AS task_admin_permission,
       to_regclass('public.nocode_task_entry_record_task_idx') AS task_feedback_index;
