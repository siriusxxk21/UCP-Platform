-- 日创 OS 线上增量：已完成用户提供的 V038-V051 R2 后，升级到当前工作区 V079。
-- 生成日期：2026-10-09；包含已提交代码及未提交工时调整代码所需的 V079。
-- 执行：sh run-sql.sh upgrade_20261009_v052_v079.sql
-- 沿用原 run-sql.sh 的全库备份、ON_ERROR_STOP 和 --single-transaction；整体运行，不拆段。
-- 执行前停止所有业务写入/后台任务，成功后部署同一工作区构建的前后端，再恢复服务。
-- 本文件不执行 V001-V051、不导入测试/业务数据、不重建缺失表、不自动 repair 历史。
-- 含任务池/模板/计划/验收/暂停/工作流节点/能效/历史工时规则，联动/跟随/文件夹/日期账本及报表中心。
-- 保留已有应用/对象发布快照、任务状态和办理事实；V058 仅补计划结束日，V075 首次回填主版本。
-- V079 按升级时实例规则留底历史办理工时，未来调整不反算历史；不改数量、业务材料或实际日期。
-- 引用条件常量、授权清单转“全部”、对象规则转换是需要业务核对的独立工具，不混入通用 SQL。
-- 无/缺项历史保留原样，仅按实际结构升级，不伪造旧记录；完整历史才按原始 checksum 追加版本。
-- 旧分支 V066 冲突、部分结构、版本/摘要漂移均阻断；不得自行清库或编辑历史绕过。
-- PostgreSQL 15+；UTF-8 无 BOM；本文件没有密码，不包含显式顶层事务命令/psql 元命令。

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '15min';
SET LOCAL search_path = pg_catalog, public;
SELECT current_database() AS target_database, current_user AS executing_user,
       inet_server_addr() AS server_address, inet_server_port() AS server_port;

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
  },
  {
    "version": 52,
    "script": "V052__task_center_navigation.sql",
    "checksum": 963432395,
    "description": "task center navigation"
  },
  {
    "version": 53,
    "script": "V053__task_application_scope.sql",
    "checksum": 482162194,
    "description": "task application scope"
  },
  {
    "version": 54,
    "script": "V054__task_pool_and_launch_drafts.sql",
    "checksum": 2119362094,
    "description": "task pool and launch drafts"
  },
  {
    "version": 55,
    "script": "V055__task_group_data_policy.sql",
    "checksum": 488137415,
    "description": "task group data policy"
  },
  {
    "version": 56,
    "script": "V056__task_data_deletion_audit.sql",
    "checksum": 759703061,
    "description": "task data deletion audit"
  },
  {
    "version": 57,
    "script": "V057__task_unchanged_receipt.sql",
    "checksum": 382135558,
    "description": "task unchanged receipt"
  },
  {
    "version": 58,
    "script": "V058__task_unified_schedule.sql",
    "checksum": -318490363,
    "description": "task unified schedule"
  },
  {
    "version": 59,
    "script": "V059__task_acceptance.sql",
    "checksum": -483730791,
    "description": "task acceptance"
  },
  {
    "version": 60,
    "script": "V060__task_acceptance_notification.sql",
    "checksum": -584071068,
    "description": "task acceptance notification"
  },
  {
    "version": 61,
    "script": "V061__task_daily_weekly_checklists.sql",
    "checksum": 1602385287,
    "description": "task daily weekly checklists"
  },
  {
    "version": 62,
    "script": "V062__linkage_trigger.sql",
    "checksum": 1227266896,
    "description": "linkage trigger"
  },
  {
    "version": 63,
    "script": "V063__application_object_follow.sql",
    "checksum": 289549348,
    "description": "application object follow"
  },
  {
    "version": 64,
    "script": "V064__record_folder.sql",
    "checksum": 13939326,
    "description": "record folder"
  },
  {
    "version": 65,
    "script": "V065__date_trigger.sql",
    "checksum": 1032736137,
    "description": "date trigger"
  },
  {
    "version": 66,
    "script": "V066__report_dataset_lifecycle.sql",
    "checksum": 1087040939,
    "description": "report dataset lifecycle"
  },
  {
    "version": 67,
    "script": "V067__report_dataset_authorization.sql",
    "checksum": 1015554861,
    "description": "report dataset authorization"
  },
  {
    "version": 68,
    "script": "V068__report_authorization_dependencies.sql",
    "checksum": 1448404856,
    "description": "report authorization dependencies"
  },
  {
    "version": 69,
    "script": "V069__report_center_menu.sql",
    "checksum": 756361659,
    "description": "report center menu"
  },
  {
    "version": 70,
    "script": "V070__report_dependency_index.sql",
    "checksum": 1472097016,
    "description": "report dependency index"
  },
  {
    "version": 71,
    "script": "V071__report_data_authorization_menu.sql",
    "checksum": -843940888,
    "description": "report data authorization menu"
  },
  {
    "version": 72,
    "script": "V072__report_dataset_folders.sql",
    "checksum": 449313904,
    "description": "report dataset folders"
  },
  {
    "version": 73,
    "script": "V073__report_dashboards.sql",
    "checksum": -1524098978,
    "description": "report dashboards"
  },
  {
    "version": 74,
    "script": "V074__report_dashboard_lifecycle.sql",
    "checksum": -541623985,
    "description": "report dashboard lifecycle"
  },
  {
    "version": 75,
    "script": "V075__task_template_primary_version.sql",
    "checksum": 1268067108,
    "description": "task template primary version"
  },
  {
    "version": 76,
    "script": "V076__task_pause_resume.sql",
    "checksum": 730202705,
    "description": "task pause resume"
  },
  {
    "version": 77,
    "script": "V077__workflow_task_nodes.sql",
    "checksum": 1614605753,
    "description": "workflow task nodes"
  },
  {
    "version": 78,
    "script": "V078__task_efficiency_menu.sql",
    "checksum": -205045988,
    "description": "task efficiency menu"
  },
  {
    "version": 79,
    "script": "V079__task_work_rule_snapshots.sql",
    "checksum": -518778705,
    "description": "task work rule snapshots"
  }
]$json$::jsonb)
AS v(version integer, script text, checksum integer, description text);
CREATE TEMP TABLE nocode_release_plan (
    version integer PRIMARY KEY, markers jsonb NOT NULL, contract jsonb NOT NULL, body text NOT NULL
) ON COMMIT DROP;

-- V052__task_center_navigation.sql；原文件 SHA-256: 319679a79cfeadc610649c59467892b358e8cc9010c672b289566f8ad95e2a57
INSERT INTO nocode_release_plan VALUES
(52,$json$[
  {
    "navigation": "/nocode-app/task-center/launch"
  }
]$json$::jsonb,$json$[]$json$::jsonb,$v52$-- 任务中心收敛为“我的任务、任务管理、任务模板”；发起任务改为页面内操作。
-- 前置 V051；沿用 V040 菜单身份、V041 独立的 manage-all 权限及 V042 顶层目录，不改业务数据。
-- 旧发起菜单保留 ID、父级、权限码、启停状态与全部角色关联，旧 URL 由前端兼容。
-- 仅为原已有效拥有任务中心目录和 query 的角色补任务管理导航，不增加任何权限码。
-- 缺失祖先、禁用菜单或补父级会恢复额外孤儿子权限的异常角色保持原状，仍可使用旧链接。
-- 验证：TaskMenuMigrationTest 在事务临时表执行本文件，核对权限树、精确授权与重复执行。
DO $$
DECLARE
    folder_id bigint;
    launch_id bigint;
    manage_id bigint;
BEGIN
    LOCK TABLE public.system_menu, public.system_role_menu IN SHARE ROW EXCLUSIVE MODE;

    SELECT id INTO STRICT folder_id
    FROM public.system_menu
    WHERE path='/task-center' AND parent_id=0
      AND component_name='NocodeTaskFolder' AND type=1 AND deleted=0;

    SELECT id INTO STRICT launch_id
    FROM public.system_menu
    WHERE path='/nocode-app/task-center/launch' AND parent_id=folder_id
      AND component_name='NocodeTaskLaunch' AND permission='nocode:task:create'
      AND type IN (2,3) AND deleted=0;

    SELECT id INTO STRICT manage_id
    FROM public.system_menu
    WHERE path='/nocode-app/task-center/manage' AND parent_id=folder_id
      AND component_name='NocodeTaskManage' AND permission='nocode:task:query'
      AND type=2 AND deleted=0;

    -- 按钮仍是目录的直接子项，避免旧角色未获 manage 菜单时被底座连带过滤 create。
    UPDATE public.system_menu
    SET type=3, visible=false, updater='task-navigation-migration', update_time=now()
    WHERE id=launch_id AND (type IS DISTINCT FROM 3 OR visible IS DISTINCT FROM false);

    -- 与底座 filterDisableMenus 一致：只沿同租户、同角色已授权且启用的完整祖先链取权限。
    WITH RECURSIVE effective_menus(role_id, tenant_id, menu_id, permission) AS (
        SELECT grants.role_id, grants.tenant_id, menu.id, menu.permission
        FROM public.system_role_menu grants
        JOIN public.system_menu menu ON menu.id=grants.menu_id
        WHERE grants.deleted=0 AND menu.deleted=0 AND menu.status=0 AND menu.parent_id=0
        UNION
        SELECT grants.role_id, grants.tenant_id, menu.id, menu.permission
        FROM effective_menus parent
        JOIN public.system_menu menu ON menu.parent_id=parent.menu_id
        JOIN public.system_role_menu grants ON grants.menu_id=menu.id
          AND grants.role_id=parent.role_id AND grants.tenant_id=parent.tenant_id
        WHERE grants.deleted=0 AND menu.deleted=0 AND menu.status=0
    ), candidates AS (
        SELECT DISTINCT folder.role_id, folder.tenant_id
        FROM effective_menus folder
        JOIN public.system_menu manage ON manage.id=manage_id AND manage.status=0 AND manage.visible=true
        WHERE folder.menu_id=folder_id
          AND EXISTS (
              SELECT 1 FROM effective_menus query_menu
              WHERE query_menu.role_id=folder.role_id AND query_menu.tenant_id=folder.tenant_id
                AND query_menu.permission='nocode:task:query'
          )
          AND NOT EXISTS (
              SELECT 1 FROM public.system_role_menu existing
              WHERE existing.role_id=folder.role_id AND existing.tenant_id=folder.tenant_id
                AND existing.menu_id=manage_id AND existing.deleted=0
          )
    ), reachable_menus(role_id, tenant_id, menu_id, permission) AS (
        SELECT candidate.role_id, candidate.tenant_id, manage.id, manage.permission
        FROM candidates candidate
        JOIN public.system_menu manage ON manage.id=manage_id
        UNION
        SELECT grants.role_id, grants.tenant_id, menu.id, menu.permission
        FROM reachable_menus parent
        JOIN public.system_menu menu ON menu.parent_id=parent.menu_id
        JOIN public.system_role_menu grants ON grants.menu_id=menu.id
          AND grants.role_id=parent.role_id AND grants.tenant_id=parent.tenant_id
        WHERE grants.deleted=0 AND menu.deleted=0 AND menu.status=0
    )
    INSERT INTO public.system_role_menu
        (id,role_id,menu_id,creator,updater,create_time,update_time,deleted,tenant_id)
    SELECT nextval('public.system_role_menu_seq'),candidate.role_id,manage_id,
           'task-navigation-migration','task-navigation-migration',now(),now(),0,candidate.tenant_id
    FROM candidates candidate
    WHERE NOT EXISTS (
        -- 补导航不能让原先缺父节点的 manage-all 等额外子权限重新进入登录权限集合。
        SELECT 1 FROM reachable_menus reachable
        WHERE reachable.role_id=candidate.role_id AND reachable.tenant_id=candidate.tenant_id
          AND coalesce(reachable.permission,'')<>''
          AND NOT EXISTS (
              SELECT 1 FROM effective_menus original
              WHERE original.role_id=reachable.role_id AND original.tenant_id=reachable.tenant_id
                AND original.permission=reachable.permission
          )
    );
END $$;
$v52$);

-- V053__task_application_scope.sql；原文件 SHA-256: 064b00c9884bdb5f8385c0ec9ce2ac5214192d19131111fadc051197df1b42f1
INSERT INTO nocode_release_plan VALUES
(53,$json$[
  {
    "table": "nocode_task_instance",
    "column": "application_id"
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
      },
      {
        "name": "application_id",
        "type": "character varying(64)",
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
        "name": "nocode_task_instance_application_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_instance_application_idx ON nocode_task_instance USING btree (application_id, expected_end, id) WHERE (deleted = 0)"
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
  }
]$json$::jsonb,$v53$-- 任务所属应用与主业务表单独立；保留历史任务，通过运行查询兼容其项目与主业务绑定。
ALTER TABLE public.nocode_task_instance ADD COLUMN application_id varchar(64);
COMMENT ON COLUMN public.nocode_task_instance.application_id IS '任务所属应用；独立任务为空，子任务沿根继承';
CREATE INDEX nocode_task_instance_application_idx
    ON public.nocode_task_instance (application_id, expected_end, id) WHERE deleted = 0;
$v53$);

-- V054__task_pool_and_launch_drafts.sql；原文件 SHA-256: 3263c66a569f9e94f1cea2d8f8fa53bec976b5a4a83bf29802f72900dd7d89f7
INSERT INTO nocode_release_plan VALUES
(54,$json$[
  {
    "table": "nocode_task_instance",
    "column": "planned_start"
  },
  {
    "nullable": "assignee_id",
    "table": "nocode_task_instance"
  },
  {
    "relation": "nocode_task_launch_draft"
  },
  {
    "relation": "nocode_task_claimable_idx"
  },
  {
    "message": "nocode-task-assignment"
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
        "notnull": false,
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
      },
      {
        "name": "application_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "planned_start",
        "type": "timestamp without time zone",
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
        "name": "nocode_task_claimable_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_claimable_idx ON nocode_task_instance USING btree (create_time, id) WHERE ((deleted = 0) AND ((status)::text = 'PENDING'::text) AND (assignee_id IS NULL) AND (((config_json)::jsonb ->> 'assignmentMode'::text) = 'OPEN'::text))"
      },
      {
        "name": "nocode_task_instance_application_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_instance_application_idx ON nocode_task_instance USING btree (application_id, expected_end, id) WHERE (deleted = 0)"
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
    "table": "nocode_task_launch_draft",
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
        "name": "lock_version",
        "type": "integer",
        "default": "1",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "content_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "published_task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "publish_key",
        "type": "character varying(120)",
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
        "default": "clock_timestamp()",
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
        "default": "clock_timestamp()",
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
        "name": "nocode_task_launch_draft_owner_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_launch_draft_owner_idx ON nocode_task_launch_draft USING btree (creator, update_time DESC, id DESC) WHERE ((deleted = 0) AND (published_task_id IS NULL))"
      },
      {
        "name": "nocode_task_launch_draft_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_launch_draft_pkey ON nocode_task_launch_draft USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "ck_nocode_task_launch_draft_publish",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((((published_task_id IS NULL) AND (publish_key IS NULL)) OR ((published_task_id IS NOT NULL) AND (publish_key IS NOT NULL))))"
      },
      {
        "name": "ck_nocode_task_launch_draft_revision",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_task_launch_draft_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  }
]$json$::jsonb,$v54$-- 人员安排与执行状态分离；历史节点仍以缺省 assignmentMode 保留原 ASSIGNED 语义。
ALTER TABLE public.nocode_task_instance ALTER COLUMN assignee_id DROP NOT NULL;
ALTER TABLE public.nocode_task_instance ADD COLUMN planned_start timestamp;
COMMENT ON COLUMN public.nocode_task_instance.planned_start IS '显式计划起点；旧任务 T0 继续使用原创建起点';

CREATE INDEX nocode_task_claimable_idx ON public.nocode_task_instance (create_time, id)
    WHERE deleted = 0 AND status = 'PENDING' AND assignee_id IS NULL
        AND config_json::jsonb->>'assignmentMode' = 'OPEN';

-- 实例编排草稿与模板草稿、业务表单草稿分开，始终只允许创建人访问。
CREATE TABLE public.nocode_task_launch_draft (
    id varchar(64) PRIMARY KEY,
    lock_version integer NOT NULL DEFAULT 1,
    content_json text NOT NULL,
    published_task_id varchar(64),
    publish_key varchar(120),
    creator varchar(64) NOT NULL,
    create_time timestamp NOT NULL DEFAULT clock_timestamp(),
    updater varchar(64) NOT NULL,
    update_time timestamp NOT NULL DEFAULT clock_timestamp(),
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT ck_nocode_task_launch_draft_revision CHECK (lock_version > 0),
    CONSTRAINT ck_nocode_task_launch_draft_publish CHECK (
        (published_task_id IS NULL AND publish_key IS NULL)
        OR (published_task_id IS NOT NULL AND publish_key IS NOT NULL))
);
COMMENT ON TABLE public.nocode_task_launch_draft IS '本人任务编排草稿及原子加入任务池回执';
CREATE INDEX nocode_task_launch_draft_owner_idx ON public.nocode_task_launch_draft
    (creator, update_time DESC, id DESC) WHERE deleted = 0 AND published_task_id IS NULL;

-- 复用消息中心已有站内信投递，不授予新的任务或业务权限。
INSERT INTO public.sys_msg_template
    (id,code,name,priority,subscribe_able,template_title,template_content,template_url,notice_config,creator,updater)
SELECT -9054001,'nocode-task-assignment','任务人员安排提醒',0,0,
    '任务人员安排提醒','任务的人员安排已更新',
    '/nocode-app/task-center?taskId=' || chr(36) || '{taskId}','[]','task-center-migration','task-center-migration'
WHERE NOT EXISTS(SELECT 1 FROM public.sys_msg_template WHERE code='nocode-task-assignment' AND deleted=0);
$v54$);

-- V055__task_group_data_policy.sql；原文件 SHA-256: 2b4a56c4dc58ffd11a8c466fc13485a918b4d0a667cb781cd0d445bbfa13c3c1
INSERT INTO nocode_release_plan VALUES
(55,$json$[
  {
    "table": "nocode_task_instance",
    "column": "authorization_json"
  },
  {
    "table": "nocode_task_template",
    "column": "root_json"
  },
  {
    "table": "nocode_task_template",
    "column": "authorization_json"
  },
  {
    "table": "nocode_task_template_version",
    "column": "root_json"
  },
  {
    "table": "nocode_task_template_version",
    "column": "authorization_json"
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
        "notnull": false,
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
      },
      {
        "name": "application_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "planned_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "authorization_json",
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
        "name": "nocode_task_claimable_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_claimable_idx ON nocode_task_instance USING btree (create_time, id) WHERE ((deleted = 0) AND ((status)::text = 'PENDING'::text) AND (assignee_id IS NULL) AND (((config_json)::jsonb ->> 'assignmentMode'::text) = 'OPEN'::text))"
      },
      {
        "name": "nocode_task_instance_application_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_instance_application_idx ON nocode_task_instance USING btree (application_id, expected_end, id) WHERE (deleted = 0)"
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
      },
      {
        "name": "root_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "authorization_json",
        "type": "text",
        "default": null,
        "notnull": false,
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
      },
      {
        "name": "root_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "authorization_json",
        "type": "text",
        "default": null,
        "notnull": false,
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
]$json$::jsonb,$v55$-- 总任务数据授权快照与模板总任务配置；不改写旧任务，不给旧任务追加授权。
ALTER TABLE public.nocode_task_instance ADD COLUMN authorization_json text;
ALTER TABLE public.nocode_task_template ADD COLUMN root_json text;
ALTER TABLE public.nocode_task_template ADD COLUMN authorization_json text;
ALTER TABLE public.nocode_task_template_version ADD COLUMN root_json text;
ALTER TABLE public.nocode_task_template_version ADD COLUMN authorization_json text;
COMMENT ON COLUMN public.nocode_task_instance.authorization_json IS '服务端批准的总任务数据权限及授权来源快照';
COMMENT ON COLUMN public.nocode_task_template_version.authorization_json IS '模板发布者批准的不可扩权授权快照';
$v55$);

-- V056__task_data_deletion_audit.sql；原文件 SHA-256: deb5010c93d896d57335a2f2bcc22063c2f4221dd4ffac537efc57db55a11159
INSERT INTO nocode_release_plan VALUES
(56,$json$[
  {
    "table": "nocode_task_entry_record",
    "constraint": "nocode_task_entry_record_operation_check",
    "token": "'DELETED'"
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
        "definition": "CHECK (((operation)::text = ANY ((ARRAY['CREATED'::character varying, 'UPDATED'::character varying, 'LINKED'::character varying, 'DELETED'::character varying])::text[])))"
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
]$json$::jsonb,$v56$-- 允许记录总任务授权下的删除事实，保留 V045 的全部贡献类型；失败的公共记录删除仍整体回滚。
ALTER TABLE public.nocode_task_entry_record
    DROP CONSTRAINT nocode_task_entry_record_operation_check;
ALTER TABLE public.nocode_task_entry_record
    ADD CONSTRAINT nocode_task_entry_record_operation_check
    CHECK (operation IN ('CREATED', 'UPDATED', 'LINKED', 'DELETED'));
$v56$);

-- V057__task_unchanged_receipt.sql；原文件 SHA-256: 2a082dbbdb8506a5ddb76018d14231ce181c26a5e0d3374ba740c01b676a6f5d
INSERT INTO nocode_release_plan VALUES
(57,$json$[
  {
    "table": "nocode_task_entry_record",
    "constraint": "nocode_task_entry_record_operation_check",
    "token": "'UNCHANGED'"
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
        "definition": "CHECK (((operation)::text = ANY ((ARRAY['CREATED'::character varying, 'UPDATED'::character varying, 'LINKED'::character varying, 'DELETED'::character varying, 'UNCHANGED'::character varying])::text[])))"
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
]$json$::jsonb,$v57$-- 无变化保存保留幂等收据，但不计为新增、修改、关联或任务完成材料。
-- 仅扩展既有枚举约束，不新增审计存储、不更改历史贡献。
ALTER TABLE public.nocode_task_entry_record
    DROP CONSTRAINT nocode_task_entry_record_operation_check;
ALTER TABLE public.nocode_task_entry_record
    ADD CONSTRAINT nocode_task_entry_record_operation_check
    CHECK (operation IN ('CREATED', 'UPDATED', 'LINKED', 'DELETED', 'UNCHANGED'));
$v57$);

-- V058__task_unified_schedule.sql；原文件 SHA-256: de2e1dbfadcf6c4b77227af3bc18b439e48335a25a5597550e4ff8a5730ef92c
INSERT INTO nocode_release_plan VALUES
(58,$json$[
  {
    "table": "nocode_task_instance",
    "column": "schedule_version"
  },
  {
    "table": "nocode_task_plan",
    "column": "end_date"
  },
  {
    "table": "nocode_task_plan",
    "column": "history_reason"
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
        "notnull": false,
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
      },
      {
        "name": "application_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "planned_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "authorization_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "schedule_version",
        "type": "integer",
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
        "name": "nocode_task_claimable_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_claimable_idx ON nocode_task_instance USING btree (create_time, id) WHERE ((deleted = 0) AND ((status)::text = 'PENDING'::text) AND (assignee_id IS NULL) AND (((config_json)::jsonb ->> 'assignmentMode'::text) = 'OPEN'::text))"
      },
      {
        "name": "nocode_task_instance_application_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_instance_application_idx ON nocode_task_instance USING btree (application_id, expected_end, id) WHERE (deleted = 0)"
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
      },
      {
        "name": "end_date",
        "type": "date",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "history_reason",
        "type": "character varying(80)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_plan_active_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_active_uk ON nocode_task_plan USING btree (task_id, user_id, period, plan_date, end_date, source) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_plan_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_pkey ON nocode_task_plan USING btree (id)"
      },
      {
        "name": "nocode_task_plan_range_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_plan_range_idx ON nocode_task_plan USING btree (user_id, plan_date, end_date) WHERE (deleted = 0)"
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
        "name": "nocode_task_plan_range_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((end_date >= plan_date))"
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
      }
    ]
  }
]$json$::jsonb,$v58$-- 统一任务计划：保留历史引用、范围日期与独立并发修订。前置 V057；不改业务记录。
ALTER TABLE public.nocode_task_instance ADD COLUMN schedule_version integer NOT NULL DEFAULT 0;
ALTER TABLE public.nocode_task_plan ADD COLUMN end_date date;
ALTER TABLE public.nocode_task_plan ADD COLUMN history_reason varchar(80);
UPDATE public.nocode_task_plan SET end_date = CASE period
    WHEN 'WEEK' THEN plan_date + 6
    WHEN 'MONTH' THEN (plan_date + INTERVAL '1 month' - INTERVAL '1 day')::date
    ELSE plan_date END;
ALTER TABLE public.nocode_task_plan ALTER COLUMN end_date SET NOT NULL;
ALTER TABLE public.nocode_task_plan ADD CONSTRAINT nocode_task_plan_range_ck CHECK (end_date >= plan_date);
-- 旧唯一约束包含已删除记录，会覆盖改期/撤销历史；改为仅限制有效相同来源安排。
ALTER TABLE public.nocode_task_plan DROP CONSTRAINT nocode_task_plan_task_id_user_id_period_plan_date_key;
CREATE UNIQUE INDEX nocode_task_plan_active_uk
    ON public.nocode_task_plan(task_id,user_id,period,plan_date,end_date,source) WHERE deleted=0;
CREATE INDEX nocode_task_plan_range_idx
    ON public.nocode_task_plan(user_id,plan_date,end_date) WHERE deleted=0;
$v58$);

-- V059__task_acceptance.sql；原文件 SHA-256: 9a6ab4310a532fc23322ddd160ea3883f6d3b33bcd5146c2925219254628f9c8
INSERT INTO nocode_release_plan VALUES
(59,$json$[
  {
    "table": "nocode_task_instance",
    "constraint": "nocode_task_state_ck",
    "token": "'PENDING_ACCEPTANCE'"
  },
  {
    "relation": "nocode_task_acceptor_idx"
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
        "notnull": false,
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
      },
      {
        "name": "application_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "planned_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "authorization_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "schedule_version",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_acceptor_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_acceptor_idx ON nocode_task_instance USING btree ((((config_json)::jsonb ->> 'acceptorId'::text)), status) WHERE ((deleted = 0) AND (parent_id IS NULL))"
      },
      {
        "name": "nocode_task_assignee_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_assignee_idx ON nocode_task_instance USING btree (assignee_id, status, expected_end) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_claimable_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_claimable_idx ON nocode_task_instance USING btree (create_time, id) WHERE ((deleted = 0) AND ((status)::text = 'PENDING'::text) AND (assignee_id IS NULL) AND (((config_json)::jsonb ->> 'assignmentMode'::text) = 'OPEN'::text))"
      },
      {
        "name": "nocode_task_instance_application_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_instance_application_idx ON nocode_task_instance USING btree (application_id, expected_end, id) WHERE (deleted = 0)"
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
        "definition": "CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'PENDING_ACCEPTANCE'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))"
      }
    ]
  }
]$json$::jsonb,$v59$-- 验收为总任务的可选收尾环节；旧配置缺少 acceptorId 时保持直接完成。
ALTER TABLE public.nocode_task_instance DROP CONSTRAINT nocode_task_state_ck;
ALTER TABLE public.nocode_task_instance ADD CONSTRAINT nocode_task_state_ck
    CHECK (status IN ('PENDING','RUNNING','PENDING_ACCEPTANCE','COMPLETED','CANCELLED'));
CREATE INDEX nocode_task_acceptor_idx
    ON public.nocode_task_instance ((config_json::jsonb->>'acceptorId'), status)
    WHERE deleted=0 AND parent_id IS NULL;
$v59$);

-- V060__task_acceptance_notification.sql；原文件 SHA-256: e155f70e7ded059da1bb8ff26d44f2ff09902b9c784435a0fada84bc3be9b966
INSERT INTO nocode_release_plan VALUES
(60,$json$[
  {
    "message": "nocode-task-acceptance"
  }
]$json$::jsonb,$json$[]$json$::jsonb,$v60$-- 使用消息中心的既有站内信模板和任务详情跳转，不覆盖管理员维护的现有配置。
INSERT INTO public.sys_msg_template
    (id,code,name,priority,subscribe_able,template_title,template_content,template_url,notice_config,creator,updater)
SELECT -9060001,'nocode-task-acceptance','任务验收提醒',0,0,
    '任务验收提醒','任务的验收状态已更新',
    '/nocode-app/task-center?taskId=' || chr(36) || '{taskId}','[]','task-center-migration','task-center-migration'
WHERE NOT EXISTS(SELECT 1 FROM public.sys_msg_template WHERE code='nocode-task-acceptance' AND deleted=0);
$v60$);

-- V061__task_daily_weekly_checklists.sql；原文件 SHA-256: 0a8e56ac5df4ec3cb075daa7ae603cf5149b20c9ce6b10a122d8098de8688c7b
INSERT INTO nocode_release_plan VALUES
(61,$json$[
  {
    "table": "nocode_task_plan",
    "column": "plan_mode"
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
      },
      {
        "name": "end_date",
        "type": "date",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "history_reason",
        "type": "character varying(80)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "plan_mode",
        "type": "character varying(16)",
        "default": "'SCHEDULE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_checklist_membership_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_checklist_membership_idx ON nocode_task_plan USING btree (user_id, period, plan_date, task_id) WHERE ((deleted = 0) AND ((plan_mode)::text = 'CHECKLIST'::text))"
      },
      {
        "name": "nocode_task_plan_active_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_active_uk ON nocode_task_plan USING btree (task_id, user_id, plan_mode, period, plan_date, end_date, source) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_plan_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_pkey ON nocode_task_plan USING btree (id)"
      },
      {
        "name": "nocode_task_plan_range_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_plan_range_idx ON nocode_task_plan USING btree (user_id, plan_date, end_date) WHERE (deleted = 0)"
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
        "name": "nocode_task_checklist_period_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((((plan_mode)::text <> 'CHECKLIST'::text) OR (((period)::text = 'DAY'::text) AND (end_date = plan_date)) OR (((period)::text = 'WEEK'::text) AND (EXTRACT(isodow FROM plan_date) = (1)::numeric) AND (end_date = (plan_date + 6)))))"
      },
      {
        "name": "nocode_task_plan_mode_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((plan_mode)::text = ANY ((ARRAY['SCHEDULE'::character varying, 'CHECKLIST'::character varying])::text[])))"
      },
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
        "name": "nocode_task_plan_range_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((end_date >= plan_date))"
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
      }
    ]
  }
]$json$::jsonb,$v61$-- 前置 V060。新日/周清单与旧区间排期并存；不转换、归档或删除既有安排。
-- 校验：迁移目录摘要检查、Flyway info/verify，以及日周独立性/旧排期隔离集成测试。
-- 影响仅为计划类型列和约束/索引；不修改任务归属、执行状态、预计或实际日期。
ALTER TABLE public.nocode_task_plan ADD COLUMN plan_mode varchar(16) NOT NULL DEFAULT 'SCHEDULE';
ALTER TABLE public.nocode_task_plan ADD CONSTRAINT nocode_task_plan_mode_ck
    CHECK (plan_mode IN ('SCHEDULE', 'CHECKLIST'));
ALTER TABLE public.nocode_task_plan ADD CONSTRAINT nocode_task_checklist_period_ck
    CHECK (plan_mode != 'CHECKLIST' OR
        (period = 'DAY' AND end_date = plan_date) OR
        (period = 'WEEK' AND extract(isodow FROM plan_date) = 1 AND end_date = plan_date + 6));
DROP INDEX public.nocode_task_plan_active_uk;
CREATE UNIQUE INDEX nocode_task_plan_active_uk
    ON public.nocode_task_plan(task_id,user_id,plan_mode,period,plan_date,end_date,source) WHERE deleted=0;
CREATE INDEX nocode_task_checklist_membership_idx
    ON public.nocode_task_plan(user_id,period,plan_date,task_id)
    WHERE deleted=0 AND plan_mode='CHECKLIST';
$v61$);

-- V062__linkage_trigger.sql；原文件 SHA-256: 3ac612005d6426554b0d7e91648bb44458bcf233cdea3ce52a5a4f51dd7cd92e
INSERT INTO nocode_release_plan VALUES
(62,$json$[
  {
    "relation": "nocode_linkage_trigger"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_linkage_trigger",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "application_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_object_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_object_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_object_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_field_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "anchor",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "anchor_field_id",
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
        "name": "nocode_linkage_trigger_field",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_linkage_trigger_field ON nocode_linkage_trigger USING btree (application_id, application_version, target_object_id, target_field_id)"
      },
      {
        "name": "nocode_linkage_trigger_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_linkage_trigger_pkey ON nocode_linkage_trigger USING btree (id)"
      },
      {
        "name": "nocode_linkage_trigger_source",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_linkage_trigger_source ON nocode_linkage_trigger USING btree (application_id, application_version, source_object_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_linkage_trigger_source_any",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_linkage_trigger_source_any ON nocode_linkage_trigger USING btree (source_object_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_linkage_trigger_target_any",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_linkage_trigger_target_any ON nocode_linkage_trigger USING btree (target_object_id) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_linkage_trigger_anchor",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((anchor)::text = ANY ((ARRAY['CURRENT_RECORD'::character varying, 'RECORD_KEY'::character varying])::text[])))"
      },
      {
        "name": "nocode_linkage_trigger_field",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (application_id, application_version, target_object_id, target_field_id)"
      },
      {
        "name": "nocode_linkage_trigger_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  }
]$json$::jsonb,$v62$-- 数据联动自动更新的反向索引：一行 = 某应用某个发布版本里一个开启自动更新的目标字段。
-- 全部列都由不可变的应用发布快照与对象版本推导，登记后不修改。
CREATE TABLE public.nocode_linkage_trigger (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    application_id bigint NOT NULL,
    application_version integer NOT NULL,
    source_object_id bigint NOT NULL,
    target_object_id bigint NOT NULL,
    target_object_version integer NOT NULL,
    target_field_id bigint NOT NULL,
    anchor varchar(16) NOT NULL,
    anchor_field_id bigint NOT NULL,
    signature varchar(64) NOT NULL,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0,
    CONSTRAINT nocode_linkage_trigger_anchor CHECK (anchor IN ('CURRENT_RECORD', 'RECORD_KEY')),
    CONSTRAINT nocode_linkage_trigger_field
        UNIQUE (application_id, application_version, target_object_id, target_field_id)
);
CREATE INDEX nocode_linkage_trigger_source
    ON public.nocode_linkage_trigger (application_id, application_version, source_object_id)
    WHERE deleted = 0;
CREATE INDEX nocode_linkage_trigger_source_any
    ON public.nocode_linkage_trigger (source_object_id) WHERE deleted = 0;
CREATE INDEX nocode_linkage_trigger_target_any
    ON public.nocode_linkage_trigger (target_object_id) WHERE deleted = 0;
$v62$);

-- V063__application_object_follow.sql；原文件 SHA-256: f66de13e1355086ba1d27992540c3e06de0360bebb471ad892bea63fdf41bba9
INSERT INTO nocode_release_plan VALUES
(63,$json$[
  {
    "relation": "nocode_application_object_follow"
  },
  {
    "relation": "nocode_application_object_follow_log"
  },
  {
    "job": "applicationFollowRetryJob"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_application_object_follow",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
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
        "name": "enabled",
        "type": "boolean",
        "default": "true",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(16)",
        "default": "'FOLLOWING'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "pending_version",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "pending_code",
        "type": "character varying(24)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "pending_reason",
        "type": "character varying(1000)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "followed_version",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "followed_at",
        "type": "timestamp(6) without time zone",
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
        "name": "nocode_application_object_follow_object",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_application_object_follow_object ON nocode_application_object_follow USING btree (object_id)"
      },
      {
        "name": "nocode_application_object_follow_pending_ix",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_application_object_follow_pending_ix ON nocode_application_object_follow USING btree (state) WHERE (((state)::text = 'PENDING'::text) AND (deleted = 0))"
      },
      {
        "name": "nocode_application_object_follow_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_application_object_follow_pkey ON nocode_application_object_follow USING btree (id)"
      },
      {
        "name": "nocode_application_object_follow_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_application_object_follow_uk ON nocode_application_object_follow USING btree (application_id, object_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_application_object_follow_application_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (application_id) REFERENCES nocode_application(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_application_object_follow_code",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((pending_code IS NULL) OR ((pending_code)::text = ANY ((ARRAY['IN_FLIGHT'::character varying, 'VALIDATION'::character varying, 'ERROR'::character varying])::text[]))))"
      },
      {
        "name": "nocode_application_object_follow_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_application_object_follow_object_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (object_id) REFERENCES nocode_object(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_application_object_follow_pending",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((((state)::text = 'PENDING'::text) = ((pending_version IS NOT NULL) AND (pending_code IS NOT NULL))))"
      },
      {
        "name": "nocode_application_object_follow_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_application_object_follow_state",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['FOLLOWING'::character varying, 'PENDING'::character varying])::text[])))"
      },
      {
        "name": "nocode_application_object_follow_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (application_id, object_id)"
      }
    ]
  },
  {
    "table": "nocode_application_object_follow_log",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
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
        "name": "from_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "to_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "application_version_before",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "application_version_after",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "outcome",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "pending_code",
        "type": "character varying(24)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "reason",
        "type": "character varying(1000)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "trigger_kind",
        "type": "character varying(24)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "plan_id",
        "type": "uuid",
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
        "name": "nocode_application_object_follow_log_app",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_application_object_follow_log_app ON nocode_application_object_follow_log USING btree (application_id, object_id, id)"
      },
      {
        "name": "nocode_application_object_follow_log_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_application_object_follow_log_pkey ON nocode_application_object_follow_log USING btree (id)"
      },
      {
        "name": "nocode_application_object_follow_log_plan",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_application_object_follow_log_plan ON nocode_application_object_follow_log USING btree (plan_id) WHERE (plan_id IS NOT NULL)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_application_object_follow_log_application_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (application_id) REFERENCES nocode_application(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_application_object_follow_log_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_application_object_follow_log_object_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (object_id) REFERENCES nocode_object(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_application_object_follow_log_outcome",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((outcome)::text = ANY ((ARRAY['FOLLOWED'::character varying, 'PENDING'::character varying])::text[])))"
      },
      {
        "name": "nocode_application_object_follow_log_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_application_object_follow_log_trigger",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((trigger_kind)::text = ANY ((ARRAY['OBJECT_PUBLISH'::character varying, 'SWITCH_ON'::character varying, 'MANUAL'::character varying, 'RETRY_JOB'::character varying, 'APPLICATION_PUBLISH'::character varying, 'MIGRATION'::character varying])::text[])))"
      }
    ]
  }
]$json$::jsonb,$v63$-- 应用对数据对象的「自动跟随」开关与状态。前置 V045。
-- 独立于应用草稿与发布版本：拨动立即生效，不随应用回退。没有行 = 默认开启且正常跟随。
-- 影响范围：新增两张表与一条定时任务登记；不改任何现有表与数据。
-- 应用或对象的行被物理删除时（正式环境只做逻辑删除；集成测试夹具清理会物理删除）跟随状态与日志随之删除。
-- 校验：SELECT count(*) FROM public.nocode_application_object_follow; 迁移后应为 0。
--       SELECT count(*) FROM public.infra_job WHERE handler_name='applicationFollowRetryJob' AND deleted=0; 应为 1。
CREATE TABLE public.nocode_application_object_follow (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    application_id bigint NOT NULL REFERENCES public.nocode_application(id) ON DELETE CASCADE,
    object_id bigint NOT NULL REFERENCES public.nocode_object(id) ON DELETE CASCADE,
    enabled boolean NOT NULL DEFAULT true,
    state varchar(16) NOT NULL DEFAULT 'FOLLOWING',
    pending_version integer,
    pending_code varchar(24),
    pending_reason varchar(1000),
    followed_version integer,
    followed_at timestamp(6),
    lock_version integer NOT NULL DEFAULT 0,
    creator varchar(64) DEFAULT '', create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '', update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0 CHECK(deleted IN (0,1)),
    CONSTRAINT nocode_application_object_follow_uk UNIQUE (application_id, object_id),
    CONSTRAINT nocode_application_object_follow_state CHECK (state IN ('FOLLOWING','PENDING')),
    CONSTRAINT nocode_application_object_follow_code CHECK (pending_code IS NULL OR pending_code IN ('IN_FLIGHT','VALIDATION','ERROR')),
    CONSTRAINT nocode_application_object_follow_pending
        CHECK ((state = 'PENDING') = (pending_version IS NOT NULL AND pending_code IS NOT NULL))
);
CREATE INDEX nocode_application_object_follow_object ON public.nocode_application_object_follow(object_id);
CREATE INDEX nocode_application_object_follow_pending_ix ON public.nocode_application_object_follow(state) WHERE state = 'PENDING' AND deleted = 0;
COMMENT ON TABLE public.nocode_application_object_follow IS '应用对数据对象的自动跟随开关与状态；没有行表示默认开启';

CREATE TABLE public.nocode_application_object_follow_log (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    application_id bigint NOT NULL REFERENCES public.nocode_application(id) ON DELETE CASCADE,
    object_id bigint NOT NULL REFERENCES public.nocode_object(id) ON DELETE CASCADE,
    from_version integer NOT NULL,
    to_version integer NOT NULL,
    application_version_before integer,
    application_version_after integer,
    outcome varchar(16) NOT NULL,
    pending_code varchar(24),
    reason varchar(1000),
    trigger_kind varchar(24) NOT NULL,
    plan_id uuid,
    creator varchar(64) DEFAULT '', create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '', update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0 CHECK(deleted IN (0,1)),
    CONSTRAINT nocode_application_object_follow_log_outcome CHECK (outcome IN ('FOLLOWED','PENDING')),
    CONSTRAINT nocode_application_object_follow_log_trigger
        CHECK (trigger_kind IN ('OBJECT_PUBLISH','SWITCH_ON','MANUAL','RETRY_JOB','APPLICATION_PUBLISH','MIGRATION'))
);
CREATE INDEX nocode_application_object_follow_log_app ON public.nocode_application_object_follow_log(application_id, object_id, id);
CREATE INDEX nocode_application_object_follow_log_plan ON public.nocode_application_object_follow_log(plan_id) WHERE plan_id IS NOT NULL;
COMMENT ON TABLE public.nocode_application_object_follow_log IS '自动跟随的逐次结果，只增不改';

-- 定时重试「跟随待处理」。迁移后在定时任务页执行一次“同步任务”以创建调度实例（同 V044 的做法）。
INSERT INTO public.infra_job
    (id, powerjob_job_id, name, status, handler_name, handler_param,
     cron_expression, retry_count, retry_interval, monitor_timeout,
     creator, create_time, updater, update_time, deleted)
SELECT nextval('public.infra_job_seq'), NULL, '应用自动跟随重试', 1,
       'applicationFollowRetryJob', '', '0 */10 * * * ?', 0, 0, 300000,
       'application-follow-migration', now(), 'application-follow-migration', now(), 0
WHERE NOT EXISTS (
    SELECT 1 FROM public.infra_job WHERE handler_name='applicationFollowRetryJob' AND deleted=0
);
$v63$);

-- V064__record_folder.sql；原文件 SHA-256: 611324c5bb2a4e408e834630b8a5d3a87f851e7999f32a3fdc09721e98919600
INSERT INTO nocode_release_plan VALUES
(64,$json$[
  {
    "relation": "nocode_record_folder_source"
  },
  {
    "relation": "nocode_record_folder_binding"
  },
  {
    "relation": "drive_entry_origin"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_record_folder_source",
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
        "name": "sort_no",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "label",
        "type": "character varying(40)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "kind",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "placement",
        "type": "character varying(24)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "space_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "relation_field_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_source_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_mode",
        "type": "character varying(16)",
        "default": "'ON_FIRST_WRITE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "name_template",
        "type": "character varying(2000)",
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
        "name": "nocode_record_folder_source_object_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_record_folder_source_object_idx ON nocode_record_folder_source USING btree (object_id, sort_no) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_record_folder_source_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_record_folder_source_pkey ON nocode_record_folder_source USING btree (id)"
      },
      {
        "name": "nocode_record_folder_source_target_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_record_folder_source_target_idx ON nocode_record_folder_source USING btree (target_source_id) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_record_folder_source_create_mode",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((create_mode)::text = ANY ((ARRAY['ON_FIRST_WRITE'::character varying, 'ON_SAVE'::character varying])::text[])))"
      },
      {
        "name": "nocode_record_folder_source_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_record_folder_source_kind",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((kind)::text = ANY ((ARRAY['FOLDER'::character varying, 'RELATION'::character varying])::text[])))"
      },
      {
        "name": "nocode_record_folder_source_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_record_folder_source_placement",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((placement)::text = ANY ((ARRAY['DIRECT'::character varying, 'RECORD_SUBFOLDER'::character varying])::text[])))"
      },
      {
        "name": "nocode_record_folder_source_shape",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((((kind)::text = 'FOLDER'::text) AND (space_id IS NOT NULL) AND (entry_id IS NOT NULL) AND ((relation_field_id)::text = ''::text) AND (target_source_id IS NULL)) OR (((kind)::text = 'RELATION'::text) AND (space_id IS NULL) AND (entry_id IS NULL) AND ((relation_field_id)::text <> ''::text) AND (target_source_id IS NOT NULL))))"
      }
    ]
  },
  {
    "table": "nocode_record_folder_binding",
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
        "name": "source_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "anchor_entry_id",
        "type": "bigint",
        "default": "0",
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
        "name": "origin",
        "type": "character varying(16)",
        "default": "'AUTO'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "auto_name",
        "type": "character varying(255)",
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
        "name": "nocode_record_folder_binding_entry_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_record_folder_binding_entry_idx ON nocode_record_folder_binding USING btree (entry_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_record_folder_binding_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_record_folder_binding_pkey ON nocode_record_folder_binding USING btree (id)"
      },
      {
        "name": "nocode_record_folder_binding_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_record_folder_binding_uk ON nocode_record_folder_binding USING btree (object_id, record_id, source_id, anchor_entry_id) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_record_folder_binding_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_record_folder_binding_origin",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((origin)::text = ANY ((ARRAY['AUTO'::character varying, 'MANUAL'::character varying])::text[])))"
      },
      {
        "name": "nocode_record_folder_binding_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "drive_entry_origin",
    "columns": [
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "origin_key",
        "type": "character varying(700)",
        "default": null,
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
        "name": "drive_entry_origin_key_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX drive_entry_origin_key_idx ON drive_entry_origin USING btree (origin_key) WHERE (deleted = 0)"
      },
      {
        "name": "drive_entry_origin_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX drive_entry_origin_pkey ON drive_entry_origin USING btree (entry_id)"
      }
    ],
    "constraints": [
      {
        "name": "drive_entry_origin_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "drive_entry_origin_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (entry_id)"
      }
    ]
  }
]$json$::jsonb,$v64$-- 记录文件夹：对象上的文件夹来源配置，以及「记录 → 网盘文件夹」的对应关系。前置 V046。
-- 仅新增三张表；不改任何既有表、不动菜单与权限码。配置不随对象版本冻结，保存即生效。
-- 校验：Flyway verify、记录文件夹专项集成测试（配置校验 / 解析 / 首次写入建目录 / 越界拒绝）。

CREATE TABLE public.nocode_record_folder_source (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    object_id varchar(128) NOT NULL,
    sort_no int NOT NULL DEFAULT 0,
    label varchar(40) NOT NULL DEFAULT '',
    kind varchar(16) NOT NULL,
    placement varchar(24) NOT NULL,
    space_id bigint,
    entry_id bigint,
    relation_field_id varchar(128) NOT NULL DEFAULT '',
    target_source_id bigint,
    create_mode varchar(16) NOT NULL DEFAULT 'ON_FIRST_WRITE',
    name_template varchar(2000) NOT NULL DEFAULT '',
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CONSTRAINT nocode_record_folder_source_kind CHECK (kind IN ('FOLDER','RELATION')),
    CONSTRAINT nocode_record_folder_source_placement CHECK (placement IN ('DIRECT','RECORD_SUBFOLDER')),
    CONSTRAINT nocode_record_folder_source_create_mode CHECK (create_mode IN ('ON_FIRST_WRITE','ON_SAVE')),
    CONSTRAINT nocode_record_folder_source_shape CHECK (
        (kind = 'FOLDER'   AND space_id IS NOT NULL AND entry_id IS NOT NULL
                           AND relation_field_id = '' AND target_source_id IS NULL)
     OR (kind = 'RELATION' AND space_id IS NULL AND entry_id IS NULL
                           AND relation_field_id <> '' AND target_source_id IS NOT NULL))
);
CREATE INDEX nocode_record_folder_source_object_idx
    ON public.nocode_record_folder_source (object_id, sort_no) WHERE deleted = 0;
CREATE INDEX nocode_record_folder_source_target_idx
    ON public.nocode_record_folder_source (target_source_id) WHERE deleted = 0;
COMMENT ON TABLE public.nocode_record_folder_source IS '记录文件夹来源：一行 = 某对象表单下方的一个文件夹页签；不随对象版本冻结';
COMMENT ON COLUMN public.nocode_record_folder_source.kind IS 'FOLDER 指定网盘里的一个文件夹；RELATION 用关联记录的文件夹';
COMMENT ON COLUMN public.nocode_record_folder_source.placement IS 'DIRECT 直接用那个文件夹；RECORD_SUBFOLDER 在其中为本记录建一个子文件夹';
COMMENT ON COLUMN public.nocode_record_folder_source.entry_id IS 'FOLDER：指定的网盘目录节点编号（普通节点）';
COMMENT ON COLUMN public.nocode_record_folder_source.relation_field_id IS 'RELATION：本对象主表上的单值关联字段稳定 ID';
COMMENT ON COLUMN public.nocode_record_folder_source.target_source_id IS 'RELATION：对方对象的文件夹来源编号';
COMMENT ON COLUMN public.nocode_record_folder_source.create_mode IS '子文件夹的建立时机：ON_FIRST_WRITE 第一次写入时；ON_SAVE 记录保存提交后由后台建立。仅 RECORD_SUBFOLDER 有意义';
COMMENT ON COLUMN public.nocode_record_folder_source.name_template IS '子文件夹命名模板的 JSON 文本；空串表示用记录名称。仅 RECORD_SUBFOLDER 有意义';

CREATE TABLE public.nocode_record_folder_binding (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    object_id varchar(128) NOT NULL,
    record_id varchar(512) NOT NULL,
    source_id bigint NOT NULL,
    anchor_entry_id bigint NOT NULL DEFAULT 0,
    space_id bigint NOT NULL,
    entry_id bigint NOT NULL,
    origin varchar(16) NOT NULL DEFAULT 'AUTO',
    auto_name varchar(255) NOT NULL DEFAULT '',
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CONSTRAINT nocode_record_folder_binding_origin CHECK (origin IN ('AUTO','MANUAL'))
);
CREATE UNIQUE INDEX nocode_record_folder_binding_uk
    ON public.nocode_record_folder_binding (object_id, record_id, source_id, anchor_entry_id)
    WHERE deleted = 0;
CREATE INDEX nocode_record_folder_binding_entry_idx
    ON public.nocode_record_folder_binding (entry_id) WHERE deleted = 0;
COMMENT ON TABLE public.nocode_record_folder_binding IS '记录文件夹对应关系：记录在某来源下的子文件夹；按网盘节点编号绑定，文件夹改名或移动不影响';
COMMENT ON COLUMN public.nocode_record_folder_binding.anchor_entry_id IS '子文件夹建在谁下面：FOLDER 来源恒为 0；RELATION 来源为关联记录解析出的文件夹编号';
COMMENT ON COLUMN public.nocode_record_folder_binding.origin IS 'AUTO 系统在首次写入时建立；MANUAL 人工指定（二期）';
COMMENT ON COLUMN public.nocode_record_folder_binding.auto_name IS '建立时系统取的名字，供二期判断文件夹是否被人工改过名';

-- 网盘节点的来源标记：经由业务表单放进去的节点记一笔「是哪条记录放的」；只在创建时写一次，之后不改。
CREATE TABLE public.drive_entry_origin (
    entry_id bigint PRIMARY KEY,
    origin_key varchar(700) NOT NULL,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1))
);
CREATE INDEX drive_entry_origin_key_idx ON public.drive_entry_origin (origin_key) WHERE deleted = 0;
COMMENT ON TABLE public.drive_entry_origin IS '网盘节点来源标记：节点经由哪个业务来源放入；对网盘是不透明字符串。没有有效行（deleted = 0）= 没有来源（直接在网盘里放的）';
COMMENT ON COLUMN public.drive_entry_origin.origin_key IS '来源键；无代码侧写入「对象编号:记录编号」';
$v64$);

-- V065__date_trigger.sql；原文件 SHA-256: 302a8a764c38aefd5d01ac6c98cbffd06dcee12e25de9a7d6a53446bf47c918b
INSERT INTO nocode_release_plan VALUES
(65,$json$[
  {
    "relation": "nocode_date_trigger_state"
  },
  {
    "relation": "nocode_date_trigger_done"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_date_trigger_state",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "resource_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "armed",
        "type": "boolean",
        "default": "true",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "closed_date",
        "type": "date",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "armed_at",
        "type": "timestamp(6) without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_scan_at",
        "type": "timestamp(6) without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_scan_date",
        "type": "date",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_trigger",
        "type": "character varying(16)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_error",
        "type": "character varying(1000)",
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
        "name": "nocode_date_trigger_state_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_date_trigger_state_pkey ON nocode_date_trigger_state USING btree (id)"
      },
      {
        "name": "nocode_date_trigger_state_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_date_trigger_state_uk ON nocode_date_trigger_state USING btree (application_id, resource_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_date_trigger_state_application_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (application_id) REFERENCES nocode_application(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_date_trigger_state_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_date_trigger_state_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_date_trigger_state_trigger",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((last_trigger IS NULL) OR ((last_trigger)::text = ANY ((ARRAY['AUTO'::character varying, 'MANUAL'::character varying])::text[]))))"
      },
      {
        "name": "nocode_date_trigger_state_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (application_id, resource_id)"
      }
    ]
  },
  {
    "table": "nocode_date_trigger_done",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "resource_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "business_date",
        "type": "date",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_record_id",
        "type": "character varying(500)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "outcome",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_count",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "message",
        "type": "character varying(1000)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "done_at",
        "type": "timestamp(6) without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
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
        "name": "nocode_date_trigger_done_date",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_date_trigger_done_date ON nocode_date_trigger_done USING btree (business_date)"
      },
      {
        "name": "nocode_date_trigger_done_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_date_trigger_done_pkey ON nocode_date_trigger_done USING btree (id)"
      },
      {
        "name": "nocode_date_trigger_done_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_date_trigger_done_uk ON nocode_date_trigger_done USING btree (application_id, resource_id, business_date, source_record_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_date_trigger_done_application_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (application_id) REFERENCES nocode_application(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_date_trigger_done_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_date_trigger_done_outcome",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((outcome)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCESS'::character varying, 'UNCHANGED'::character varying, 'FAILED'::character varying])::text[])))"
      },
      {
        "name": "nocode_date_trigger_done_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_date_trigger_done_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (application_id, resource_id, business_date, source_record_id)"
      }
    ]
  }
]$json$::jsonb,$v65$-- 业务动作「按日期自动执行」的执行账本。前置 V047。
-- 规则本身仍在应用发布快照里（自动更新 mode=DATE），这里只记「每条规则封账到哪一天」与「每天哪条来源记录已处理」。
-- 影响范围：新增两张表；不改任何现有表与数据。应用的行被物理删除时（正式环境只做逻辑删除；集成测试夹具清理会物理删除）账本随之删除。
-- 校验：SELECT count(*) FROM public.nocode_date_trigger_state; 迁移后应为 0。
--       SELECT count(*) FROM public.nocode_date_trigger_done;  迁移后应为 0。
CREATE TABLE public.nocode_date_trigger_state (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    application_id bigint NOT NULL REFERENCES public.nocode_application(id) ON DELETE CASCADE,
    resource_id varchar(64) NOT NULL,
    armed boolean NOT NULL DEFAULT true,
    closed_date date NOT NULL,
    armed_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_scan_at timestamp(6),
    last_scan_date date,
    last_trigger varchar(16),
    last_error varchar(1000),
    creator varchar(64) DEFAULT '', create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '', update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0 CHECK(deleted IN (0,1)),
    CONSTRAINT nocode_date_trigger_state_uk UNIQUE (application_id, resource_id),
    CONSTRAINT nocode_date_trigger_state_trigger CHECK (last_trigger IS NULL OR last_trigger IN ('AUTO','MANUAL'))
);
COMMENT ON TABLE public.nocode_date_trigger_state IS '按日期自动执行：每条规则的账本；closed_date 及以前的日期不再处理';

CREATE TABLE public.nocode_date_trigger_done (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    application_id bigint NOT NULL REFERENCES public.nocode_application(id) ON DELETE CASCADE,
    resource_id varchar(64) NOT NULL,
    business_date date NOT NULL,
    source_record_id varchar(500) NOT NULL,
    outcome varchar(16) NOT NULL,
    target_count integer NOT NULL DEFAULT 0,
    message varchar(1000),
    done_at timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    creator varchar(64) DEFAULT '', create_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) DEFAULT '', update_time timestamp(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted smallint NOT NULL DEFAULT 0 CHECK(deleted IN (0,1)),
    CONSTRAINT nocode_date_trigger_done_uk UNIQUE (application_id, resource_id, business_date, source_record_id),
    CONSTRAINT nocode_date_trigger_done_outcome CHECK (outcome IN ('RUNNING','SUCCESS','UNCHANGED','FAILED'))
);
CREATE INDEX nocode_date_trigger_done_date ON public.nocode_date_trigger_done(business_date);
COMMENT ON TABLE public.nocode_date_trigger_done IS '按日期自动执行：某规则某业务日已处理的来源记录（同一天不重复执行的唯一性）；保留 90 天';
$v65$);

-- V066__report_dataset_lifecycle.sql；原文件 SHA-256: ba96fc7b3583ae34fb2956de6795ea1c3b381d5e56f9d189d557cfbeb88ea217
INSERT INTO nocode_release_plan VALUES
(66,$json$[
  {
    "relation": "nocode_report_dataset"
  },
  {
    "relation": "nocode_report_dataset_version"
  },
  {
    "relation": "nocode_report_operation_log"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_report_dataset",
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
        "name": "name",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "description",
        "type": "character varying(1000)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "owner_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "status",
        "type": "character varying(16)",
        "default": "'INACTIVE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "nocode_report_dataset_owner_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dataset_owner_idx ON nocode_report_dataset USING btree (owner_id, update_time DESC, id DESC) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_dataset_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_pkey ON nocode_report_dataset USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dataset_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dataset_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_dataset_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dataset_published_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((published_version > 0))"
      },
      {
        "name": "nocode_report_dataset_status_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))"
      }
    ]
  },
  {
    "table": "nocode_report_dataset_version",
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
        "name": "dataset_id",
        "type": "bigint",
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
        "name": "definition_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "reason",
        "type": "character varying(1000)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_id",
        "type": "character varying(80)",
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
        "name": "nocode_report_dataset_version_dataset_id_creator_request_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_version_dataset_id_creator_request_id_key ON nocode_report_dataset_version USING btree (dataset_id, creator, request_id)"
      },
      {
        "name": "nocode_report_dataset_version_dataset_id_version_no_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_version_dataset_id_version_no_key ON nocode_report_dataset_version USING btree (dataset_id, version_no)"
      },
      {
        "name": "nocode_report_dataset_version_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_version_pkey ON nocode_report_dataset_version USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dataset_version_dataset_id_creator_request_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dataset_id, creator, request_id)"
      },
      {
        "name": "nocode_report_dataset_version_dataset_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dataset_id) REFERENCES nocode_report_dataset(id)"
      },
      {
        "name": "nocode_report_dataset_version_dataset_id_version_no_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dataset_id, version_no)"
      },
      {
        "name": "nocode_report_dataset_version_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dataset_version_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dataset_version_version_no_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((version_no > 0))"
      }
    ]
  },
  {
    "table": "nocode_report_operation_log",
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
        "name": "resource_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "resource_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "action",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "revision",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "before_json",
        "type": "jsonb",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "after_json",
        "type": "jsonb",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "reason",
        "type": "character varying(1000)",
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
        "name": "nocode_report_operation_log_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_operation_log_pkey ON nocode_report_operation_log USING btree (id)"
      },
      {
        "name": "nocode_report_operation_resource_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_operation_resource_idx ON nocode_report_operation_log USING btree (resource_kind, resource_id, id DESC)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_operation_log_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_operation_log_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  }
]$json$::jsonb,$v66$-- 独立报表数据集生命周期；不复制业务记录，不生成对象共享授权，不变更现有应用。
CREATE TABLE public.nocode_report_dataset (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    name varchar(80) NOT NULL,
    description varchar(1000) NOT NULL DEFAULT '',
    owner_id bigint NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'INACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    draft_json jsonb NOT NULL,
    draft_checksum varchar(64) NOT NULL,
    lock_version integer NOT NULL DEFAULT 1 CHECK (lock_version > 0),
    published_version integer CHECK (published_version > 0),
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1))
);
CREATE INDEX nocode_report_dataset_owner_idx ON public.nocode_report_dataset(owner_id,update_time DESC,id DESC) WHERE deleted=0;
COMMENT ON TABLE public.nocode_report_dataset IS '报表数据集草稿和发布指针；所有权不授予业务数据读取权限';

CREATE TABLE public.nocode_report_dataset_version (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    dataset_id bigint NOT NULL REFERENCES public.nocode_report_dataset(id),
    version_no integer NOT NULL CHECK (version_no > 0),
    definition_json jsonb NOT NULL,
    checksum varchar(64) NOT NULL,
    reason varchar(1000) NOT NULL,
    request_id varchar(80) NOT NULL,
    request_hash varchar(64) NOT NULL,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(dataset_id,version_no),
    UNIQUE(dataset_id,creator,request_id)
);
COMMENT ON TABLE public.nocode_report_dataset_version IS '不可变数据集发布快照；发布幂等键绑定资源和操作者';

CREATE TABLE public.nocode_report_operation_log (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    resource_kind varchar(32) NOT NULL,
    resource_id bigint NOT NULL,
    action varchar(32) NOT NULL,
    revision integer NOT NULL,
    before_json jsonb,
    after_json jsonb,
    reason varchar(1000) NOT NULL DEFAULT '',
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1))
);
CREATE INDEX nocode_report_operation_resource_idx ON public.nocode_report_operation_log(resource_kind,resource_id,id DESC);
COMMENT ON TABLE public.nocode_report_operation_log IS '报表资源配置与授权变更审计；同事务记录，不存查询业务结果';
$v66$);

-- V067__report_dataset_authorization.sql；原文件 SHA-256: 6b5676ec4cf477a79970bae906d9cd16e84a686756aef9f33a746fcf5913f698
INSERT INTO nocode_release_plan VALUES
(67,$json$[
  {
    "relation": "nocode_report_object_grant"
  },
  {
    "relation": "nocode_report_dataset_policy"
  },
  {
    "relation": "nocode_report_resource_acl"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_report_object_grant",
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
        "name": "dataset_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
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
        "name": "grant_json",
        "type": "jsonb",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "reason",
        "type": "character varying(1000)",
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
        "name": "nocode_report_object_grant_dataset_id_object_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_object_grant_dataset_id_object_id_key ON nocode_report_object_grant USING btree (dataset_id, object_id)"
      },
      {
        "name": "nocode_report_object_grant_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_object_grant_pkey ON nocode_report_object_grant USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_object_grant_dataset_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dataset_id) REFERENCES nocode_report_dataset(id)"
      },
      {
        "name": "nocode_report_object_grant_dataset_id_object_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dataset_id, object_id)"
      },
      {
        "name": "nocode_report_object_grant_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_object_grant_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_object_grant_object_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (object_id) REFERENCES nocode_object(id)"
      },
      {
        "name": "nocode_report_object_grant_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "nocode_report_dataset_policy",
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
        "name": "dataset_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "members_json",
        "type": "jsonb",
        "default": "'[]'::jsonb",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "nocode_report_dataset_policy_dataset_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_policy_dataset_id_key ON nocode_report_dataset_policy USING btree (dataset_id)"
      },
      {
        "name": "nocode_report_dataset_policy_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_policy_pkey ON nocode_report_dataset_policy USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dataset_policy_dataset_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dataset_id) REFERENCES nocode_report_dataset(id)"
      },
      {
        "name": "nocode_report_dataset_policy_dataset_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dataset_id)"
      },
      {
        "name": "nocode_report_dataset_policy_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dataset_policy_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_dataset_policy_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "nocode_report_resource_acl",
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
        "name": "resource_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "resource_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "policy_json",
        "type": "jsonb",
        "default": "'[]'::jsonb",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "nocode_report_resource_acl_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_resource_acl_pkey ON nocode_report_resource_acl USING btree (id)"
      },
      {
        "name": "nocode_report_resource_acl_resource_kind_resource_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_resource_acl_resource_kind_resource_id_key ON nocode_report_resource_acl USING btree (resource_kind, resource_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_resource_acl_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_resource_acl_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_resource_acl_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_resource_acl_resource_kind_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((resource_kind)::text = ANY ((ARRAY['DATASET'::character varying, 'DASHBOARD'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_resource_acl_resource_kind_resource_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (resource_kind, resource_id)"
      }
    ]
  }
]$json$::jsonb,$v67$-- 报表数据集的三个独立授权层；不预填上限/成员策略，不向任何已有角色授予新权限。
CREATE TABLE public.nocode_report_object_grant (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    dataset_id bigint NOT NULL REFERENCES public.nocode_report_dataset(id),
    object_id bigint NOT NULL REFERENCES public.nocode_object(id),
    grant_json jsonb,
    lock_version integer NOT NULL DEFAULT 1 CHECK (lock_version>0),
    reason varchar(1000) NOT NULL DEFAULT '',
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(dataset_id,object_id)
);
COMMENT ON TABLE public.nocode_report_object_grant IS '数据管理员授予数据集的对象范围上限，独立于应用共享授权';

CREATE TABLE public.nocode_report_dataset_policy (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    dataset_id bigint NOT NULL REFERENCES public.nocode_report_dataset(id),
    members_json jsonb NOT NULL DEFAULT '[]',
    lock_version integer NOT NULL DEFAULT 1 CHECK (lock_version>0),
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(dataset_id)
);
COMMENT ON TABLE public.nocode_report_dataset_policy IS '数据集成员读/导出策略；创建者不自动免除成员登记';

CREATE TABLE public.nocode_report_resource_acl (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    resource_kind varchar(32) NOT NULL CHECK (resource_kind IN ('DATASET','DASHBOARD')),
    resource_id bigint NOT NULL,
    policy_json jsonb NOT NULL DEFAULT '[]',
    lock_version integer NOT NULL DEFAULT 1 CHECK (lock_version>0),
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(resource_kind,resource_id)
);
COMMENT ON TABLE public.nocode_report_resource_acl IS '报表资源协作管理权限；不等价于业务数据授权';
$v67$);

-- V068__report_authorization_dependencies.sql；原文件 SHA-256: 7c94cc23ea341cb0c403502c4a34f2248e17fd582556cba24760f98ddeafcc3e
INSERT INTO nocode_release_plan VALUES
(68,$json$[
  {
    "dependencies": true
  }
]$json$::jsonb,$json$[]$json$::jsonb,$v68$-- 为已有授权补登记条件字段；不修改授权内容、发布版本或业务记录。
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
$v68$);

-- V069__report_center_menu.sql；原文件 SHA-256: dffbe35ff14127aee54cd43c31e1cd1c619f5e8080477a85909a3e5fbbce536f
INSERT INTO nocode_release_plan VALUES
(69,$json$[
  {
    "menu": "/nocode/report-center"
  },
  {
    "menu": "/nocode/report-center/datasets"
  },
  {
    "permission": "nocode:report:query"
  },
  {
    "permission": "nocode:report:create"
  },
  {
    "permission": "nocode:report:update"
  },
  {
    "permission": "nocode:report:publish"
  },
  {
    "permission": "nocode:report:manage"
  },
  {
    "permission": "nocode:report:authorize"
  }
]$json$::jsonb,$json$[]$json$::jsonb,$v69$-- 报表中心与数据中心、应用中心并列；仅注册已实现的数据集页面和权限定义。
-- 不给任何既有普通角色自动授权，资源权限与数据权限仍由各自策略控制。
DO $$
DECLARE
    entry_id bigint;
    dataset_id bigint;
BEGIN
    LOCK TABLE public.system_menu IN SHARE ROW EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM public.system_menu WHERE deleted=0
               AND (path='/nocode/report-center' OR permission LIKE 'nocode:report:%')) THEN
        RAISE EXCEPTION 'Report-center menu or permissions already exist; review before migration';
    END IF;
    entry_id := nextval('public.system_menu_seq');
    dataset_id := nextval('public.system_menu_seq');
    INSERT INTO public.system_menu
    (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
     visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
    VALUES
    (entry_id,'报表中心','',1,28,0,'/nocode/report-center','BarChartOutlined',NULL,NULL,0,
     true,true,true,'report-menu-migration','report-menu-migration',now(),now(),0),
    (dataset_id,'数据集','nocode:report:query',2,1,entry_id,'/nocode/report-center/datasets',
     'DatabaseOutlined','nocode/report-center/datasets','NocodeReportDatasets',0,
     true,false,true,'report-menu-migration','report-menu-migration',now(),now(),0);
    INSERT INTO public.system_menu
    (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
     visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
    SELECT nextval('public.system_menu_seq'),v.name,v.permission,3,v.sort,dataset_id,'',NULL,NULL,NULL,0,
           false,false,false,'report-menu-migration','report-menu-migration',now(),now(),0
    FROM (VALUES
        ('创建数据集','nocode:report:create',1),
        ('编辑数据集','nocode:report:update',2),
        ('发布数据集','nocode:report:publish',3),
        ('管理数据集','nocode:report:manage',4),
        ('数据集成员授权','nocode:report:authorize',5)
    ) AS v(name,permission,sort);
END $$;
$v69$);

-- V070__report_dependency_index.sql；原文件 SHA-256: 5dd58bc1ddac0426b1ac5a39863047ddc24de2d8d9914f84093749a313dbe21f
INSERT INTO nocode_release_plan VALUES
(70,$json$[
  {
    "relation": "nocode_report_dependency"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_report_dependency",
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
        "name": "source_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_stage",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_refs_json",
        "type": "jsonb",
        "default": "'[]'::jsonb",
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
        "name": "nocode_report_dependency_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dependency_pkey ON nocode_report_dependency USING btree (id)"
      },
      {
        "name": "nocode_report_dependency_source_kind_source_id_source_stage_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dependency_source_kind_source_id_source_stage_key ON nocode_report_dependency USING btree (source_kind, source_id, source_stage, source_version, target_kind, target_id, target_version)"
      },
      {
        "name": "nocode_report_dependency_target_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dependency_target_idx ON nocode_report_dependency USING btree (target_kind, target_id) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dependency_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((((source_stage)::text = 'DRAFT'::text) AND (source_version = 0)) OR (((source_stage)::text = 'VERSION'::text) AND (source_version > 0))))"
      },
      {
        "name": "nocode_report_dependency_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dependency_field_refs_json_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((jsonb_typeof(field_refs_json) = 'array'::text))"
      },
      {
        "name": "nocode_report_dependency_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dependency_source_kind_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((source_kind)::text = ANY ((ARRAY['DASHBOARD'::character varying, 'APPLICATION'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_dependency_source_kind_source_id_source_stage_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (source_kind, source_id, source_stage, source_version, target_kind, target_id, target_version)"
      },
      {
        "name": "nocode_report_dependency_source_stage_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((source_stage)::text = ANY ((ARRAY['DRAFT'::character varying, 'VERSION'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_dependency_source_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((source_version >= 0))"
      },
      {
        "name": "nocode_report_dependency_target_kind_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((target_kind)::text = ANY ((ARRAY['DATASET'::character varying, 'DASHBOARD'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_dependency_target_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((target_version > 0))"
      }
    ]
  }
]$json$::jsonb,$v70$-- 提前落地报表资源反向依赖索引，供数据集删除预检及后续仪表板/应用固定引用共用。
-- 对象/字段依赖仍由 nocode_resource_dependency 维护，不复制业务数据。
CREATE TABLE public.nocode_report_dependency (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    source_kind varchar(32) NOT NULL CHECK (source_kind IN ('DASHBOARD','APPLICATION')),
    source_id bigint NOT NULL,
    source_stage varchar(16) NOT NULL CHECK (source_stage IN ('DRAFT','VERSION')),
    source_version integer NOT NULL CHECK (source_version >= 0),
    target_kind varchar(32) NOT NULL CHECK (target_kind IN ('DATASET','DASHBOARD')),
    target_id bigint NOT NULL,
    target_version integer NOT NULL CHECK (target_version > 0),
    field_refs_json jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(field_refs_json)='array'),
    creator varchar(64) NOT NULL DEFAULT '', create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '', update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CHECK ((source_stage='DRAFT' AND source_version=0) OR (source_stage='VERSION' AND source_version>0)),
    UNIQUE(source_kind,source_id,source_stage,source_version,target_kind,target_id,target_version)
);
CREATE INDEX nocode_report_dependency_target_idx ON public.nocode_report_dependency(target_kind,target_id) WHERE deleted=0;
COMMENT ON TABLE public.nocode_report_dependency IS '报表固定引用反向索引，保留草稿及可恢复历史版本；写入方须先持全局设计锁并校验目标未删除';
$v70$);

-- V071__report_data_authorization_menu.sql；原文件 SHA-256: 80b14c4376bf255bc2d82b6b0d12aa1368b6c2f7176dcb2566ebc5783392c7fc
INSERT INTO nocode_release_plan VALUES
(71,$json$[
  {
    "menu": "/nocode/report-center/data-authorization"
  }
]$json$::jsonb,$json$[]$json$::jsonb,$v71$-- 复用对象共享权限，提供独立于报表制作权限的授权入口；不为现有角色自动授予菜单。
DO $$
DECLARE
    entry_id bigint;
BEGIN
    LOCK TABLE public.system_menu IN SHARE ROW EXCLUSIVE MODE;
    SELECT id INTO STRICT entry_id FROM public.system_menu
        WHERE path='/nocode/report-center' AND deleted=0;
    IF EXISTS (SELECT 1 FROM public.system_menu WHERE deleted=0
               AND path='/nocode/report-center/data-authorization') THEN
        RAISE EXCEPTION 'Report data authorization menu already exists; review before migration';
    END IF;
    INSERT INTO public.system_menu
    (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
     visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
    VALUES
    (nextval('public.system_menu_seq'),'数据授权','nocode:object:share',2,2,entry_id,
     '/nocode/report-center/data-authorization','SafetyCertificateOutlined',
     'nocode/report-center/data-authorization','NocodeReportDataAuthorization',0,
     true,false,true,'report-menu-migration','report-menu-migration',now(),now(),0);
END $$;
$v71$);

-- V072__report_dataset_folders.sql；原文件 SHA-256: 8dd539de4ec88222d3192ae065b340986a53e7a7272caba1e683c332b8ae206e
INSERT INTO nocode_release_plan VALUES
(72,$json$[
  {
    "relation": "nocode_report_folder"
  },
  {
    "table": "nocode_report_dataset",
    "column": "folder_id"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_report_dataset",
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
        "name": "name",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "description",
        "type": "character varying(1000)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "owner_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "status",
        "type": "character varying(16)",
        "default": "'INACTIVE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "folder_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_dataset_folder_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dataset_folder_idx ON nocode_report_dataset USING btree (folder_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_dataset_owner_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dataset_owner_idx ON nocode_report_dataset USING btree (owner_id, update_time DESC, id DESC) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_dataset_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_pkey ON nocode_report_dataset USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dataset_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dataset_folder_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (folder_id) REFERENCES nocode_report_folder(id)"
      },
      {
        "name": "nocode_report_dataset_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_dataset_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dataset_published_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((published_version > 0))"
      },
      {
        "name": "nocode_report_dataset_status_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))"
      }
    ]
  },
  {
    "table": "nocode_report_folder",
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
        "name": "resource_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "parent_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "name",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "sort_no",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "nocode_report_folder_parent_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_folder_parent_idx ON nocode_report_folder USING btree (resource_kind, parent_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_folder_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_folder_pkey ON nocode_report_folder USING btree (id)"
      },
      {
        "name": "nocode_report_folder_sibling_name_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_folder_sibling_name_idx ON nocode_report_folder USING btree (resource_kind, COALESCE(parent_id, (0)::bigint), lower((name)::text)) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_folder_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((parent_id IS DISTINCT FROM id))"
      },
      {
        "name": "nocode_report_folder_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_folder_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_folder_parent_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (parent_id) REFERENCES nocode_report_folder(id)"
      },
      {
        "name": "nocode_report_folder_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_folder_resource_kind_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((resource_kind)::text = ANY ((ARRAY['DATASET'::character varying, 'DASHBOARD'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_folder_sort_no_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((sort_no >= 0) AND (sort_no <= 9999)))"
      }
    ]
  }
]$json$::jsonb,$v72$-- 目录仅分类，不携带资源 ACL；已有数据集默认未分类，发布快照字节保持不变。
CREATE TABLE public.nocode_report_folder (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    resource_kind varchar(32) NOT NULL CHECK (resource_kind IN ('DATASET','DASHBOARD')),
    parent_id bigint REFERENCES public.nocode_report_folder(id),
    name varchar(80) NOT NULL,
    sort_no integer NOT NULL DEFAULT 0 CHECK (sort_no BETWEEN 0 AND 9999),
    lock_version integer NOT NULL DEFAULT 1 CHECK (lock_version > 0),
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    CHECK (parent_id IS DISTINCT FROM id)
);
CREATE UNIQUE INDEX nocode_report_folder_sibling_name_idx
    ON public.nocode_report_folder(resource_kind,COALESCE(parent_id,0),lower(name)) WHERE deleted=0;
CREATE INDEX nocode_report_folder_parent_idx ON public.nocode_report_folder(resource_kind,parent_id) WHERE deleted=0;
ALTER TABLE public.nocode_report_dataset ADD COLUMN folder_id bigint REFERENCES public.nocode_report_folder(id);
CREATE INDEX nocode_report_dataset_folder_idx ON public.nocode_report_dataset(folder_id) WHERE deleted=0;
COMMENT ON TABLE public.nocode_report_folder IS '报表分类目录；同类层级、禁止环、非空不可删除；不继承内容权限';
COMMENT ON COLUMN public.nocode_report_dataset.folder_id IS '当前分类位置，不参与发布快照与内容校验和';
$v72$);

-- V073__report_dashboards.sql；原文件 SHA-256: c2217123656c52dd5ec998c6a974df51734ba3b0a96dd0d7bc52916bb5e994fe
INSERT INTO nocode_release_plan VALUES
(73,$json$[
  {
    "relation": "nocode_report_dashboard"
  },
  {
    "relation": "nocode_report_dashboard_version"
  },
  {
    "menu": "/nocode/report-center/dashboards"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_report_dashboard",
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
        "name": "name",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "owner_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "nocode_report_dashboard_owner_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dashboard_owner_idx ON nocode_report_dashboard USING btree (owner_id, update_time DESC, id DESC) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_dashboard_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dashboard_pkey ON nocode_report_dashboard USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dashboard_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dashboard_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_dashboard_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dashboard_published_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((published_version > 0))"
      }
    ]
  },
  {
    "table": "nocode_report_dashboard_version",
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
        "name": "dashboard_id",
        "type": "bigint",
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
        "name": "definition_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_id",
        "type": "character varying(80)",
        "default": null,
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
        "name": "nocode_report_dashboard_versi_dashboard_id_creator_request__key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dashboard_versi_dashboard_id_creator_request__key ON nocode_report_dashboard_version USING btree (dashboard_id, creator, request_id)"
      },
      {
        "name": "nocode_report_dashboard_version_dashboard_id_version_no_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dashboard_version_dashboard_id_version_no_key ON nocode_report_dashboard_version USING btree (dashboard_id, version_no)"
      },
      {
        "name": "nocode_report_dashboard_version_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dashboard_version_pkey ON nocode_report_dashboard_version USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dashboard_versi_dashboard_id_creator_request__key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dashboard_id, creator, request_id)"
      },
      {
        "name": "nocode_report_dashboard_version_dashboard_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dashboard_id) REFERENCES nocode_report_dashboard(id)"
      },
      {
        "name": "nocode_report_dashboard_version_dashboard_id_version_no_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dashboard_id, version_no)"
      },
      {
        "name": "nocode_report_dashboard_version_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dashboard_version_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dashboard_version_version_no_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((version_no > 0))"
      }
    ]
  }
]$json$::jsonb,$v73$-- 独立仪表板首批拥有者制作/发布闭环；数据读取仍沿用数据集授权，未向普通角色自动授予权限。
CREATE TABLE public.nocode_report_dashboard (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
 name varchar(80) NOT NULL, owner_id bigint NOT NULL,
 draft_json jsonb NOT NULL,draft_checksum varchar(64) NOT NULL,
 lock_version integer NOT NULL DEFAULT 1 CHECK(lock_version>0),published_version integer CHECK(published_version>0),
 creator varchar(64) NOT NULL DEFAULT '',create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL DEFAULT '',update_time timestamp NOT NULL DEFAULT now(),deleted smallint NOT NULL DEFAULT 0 CHECK(deleted IN (0,1))
);
CREATE INDEX nocode_report_dashboard_owner_idx ON public.nocode_report_dashboard(owner_id,update_time DESC,id DESC) WHERE deleted=0;
CREATE TABLE public.nocode_report_dashboard_version (
 id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
 dashboard_id bigint NOT NULL REFERENCES public.nocode_report_dashboard(id),version_no integer NOT NULL CHECK(version_no>0),
 definition_json jsonb NOT NULL,checksum varchar(64) NOT NULL,request_id varchar(80) NOT NULL,
 creator varchar(64) NOT NULL DEFAULT '',create_time timestamp NOT NULL DEFAULT now(),
 updater varchar(64) NOT NULL DEFAULT '',update_time timestamp NOT NULL DEFAULT now(),deleted smallint NOT NULL DEFAULT 0 CHECK(deleted IN (0,1)),
 UNIQUE(dashboard_id,version_no),UNIQUE(dashboard_id,creator,request_id)
);
COMMENT ON TABLE public.nocode_report_dashboard IS '独立仪表板草稿；拥有者不自动获得业务读取权';
COMMENT ON TABLE public.nocode_report_dashboard_version IS '仪表板不可变发布版本，引用固定数据集版本';
INSERT INTO public.system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
SELECT nextval('public.system_menu_seq'),'仪表板','nocode:report:query',2,0,id,'/nocode/report-center/dashboards','DashboardOutlined','nocode/report-center/dashboards','NocodeReportDashboards',0,true,false,true,'report-dashboard-migration','report-dashboard-migration',now(),now(),0
FROM public.system_menu WHERE path='/nocode/report-center' AND deleted=0;
$v73$);

-- V074__report_dashboard_lifecycle.sql；原文件 SHA-256: 05b683d0cbc9ed23dec480e612be67f1b1933992279fc7c92a77ffde2ccc28d0
INSERT INTO nocode_release_plan VALUES
(74,$json$[
  {
    "table": "nocode_report_dashboard",
    "column": "status"
  },
  {
    "table": "nocode_report_dashboard",
    "column": "folder_id"
  },
  {
    "relation": "nocode_report_preference"
  },
  {
    "menu": "/nocode/report-center/home"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_report_dashboard",
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
        "name": "name",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "owner_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "status",
        "type": "character varying(16)",
        "default": "'ACTIVE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "folder_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_dashboard_folder_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dashboard_folder_idx ON nocode_report_dashboard USING btree (folder_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_dashboard_owner_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dashboard_owner_idx ON nocode_report_dashboard USING btree (owner_id, update_time DESC, id DESC) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_dashboard_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dashboard_pkey ON nocode_report_dashboard USING btree (id)"
      },
      {
        "name": "nocode_report_dashboard_status_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dashboard_status_idx ON nocode_report_dashboard USING btree (status, update_time DESC, id DESC) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dashboard_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dashboard_folder_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (folder_id) REFERENCES nocode_report_folder(id)"
      },
      {
        "name": "nocode_report_dashboard_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_dashboard_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dashboard_published_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((published_version > 0))"
      },
      {
        "name": "nocode_report_dashboard_status_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))"
      }
    ]
  },
  {
    "table": "nocode_report_preference",
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
        "name": "dashboard_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "favorite",
        "type": "boolean",
        "default": "false",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_visited_at",
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
        "name": "nocode_report_preference_favorite_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_preference_favorite_idx ON nocode_report_preference USING btree (user_id, update_time DESC, dashboard_id DESC) WHERE ((deleted = 0) AND favorite)"
      },
      {
        "name": "nocode_report_preference_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_preference_pkey ON nocode_report_preference USING btree (id)"
      },
      {
        "name": "nocode_report_preference_recent_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_preference_recent_idx ON nocode_report_preference USING btree (user_id, last_visited_at DESC, dashboard_id DESC) WHERE ((deleted = 0) AND (last_visited_at IS NOT NULL))"
      },
      {
        "name": "nocode_report_preference_user_id_dashboard_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_preference_user_id_dashboard_id_key ON nocode_report_preference USING btree (user_id, dashboard_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_preference_dashboard_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dashboard_id) REFERENCES nocode_report_dashboard(id)"
      },
      {
        "name": "nocode_report_preference_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_preference_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_preference_user_id_dashboard_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (user_id, dashboard_id)"
      }
    ]
  }
]$json$::jsonb,$v74$-- 仪表板分类与停用属于资源头，不改写任何已发布内容或校验和。
ALTER TABLE public.nocode_report_dashboard
    ADD COLUMN status varchar(16) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    ADD COLUMN folder_id bigint REFERENCES public.nocode_report_folder(id);
CREATE INDEX nocode_report_dashboard_folder_idx
    ON public.nocode_report_dashboard(folder_id) WHERE deleted=0;
CREATE INDEX nocode_report_dashboard_status_idx
    ON public.nocode_report_dashboard(status,update_time DESC,id DESC) WHERE deleted=0;
COMMENT ON COLUMN public.nocode_report_dashboard.status IS '停用作用于全部发布版本；发布不自动激活显式停用资源';
COMMENT ON COLUMN public.nocode_report_dashboard.folder_id IS '同类型分类目录，不参与发布快照及内容校验和';

CREATE TABLE public.nocode_report_preference (
    id bigint GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL,
    dashboard_id bigint NOT NULL REFERENCES public.nocode_report_dashboard(id),
    favorite boolean NOT NULL DEFAULT false,
    last_visited_at timestamp,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT now(),
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT now(),
    deleted smallint NOT NULL DEFAULT 0 CHECK (deleted IN (0,1)),
    UNIQUE(user_id,dashboard_id)
);
CREATE INDEX nocode_report_preference_recent_idx
    ON public.nocode_report_preference(user_id,last_visited_at DESC,dashboard_id DESC)
    WHERE deleted=0 AND last_visited_at IS NOT NULL;
CREATE INDEX nocode_report_preference_favorite_idx
    ON public.nocode_report_preference(user_id,update_time DESC,dashboard_id DESC)
    WHERE deleted=0 AND favorite;
COMMENT ON TABLE public.nocode_report_preference IS '个人仪表板收藏与最近访问；读取实时过滤当前资源访问权限';

-- 复用现有报表中心菜单和动态路由；不替普通角色自动授予资源或系统权限。
-- 仅调整两个已知入口的默认顺序，不改权限、路由身份或其他自定义菜单排序。
UPDATE public.system_menu SET sort=1,updater='report-lifecycle-migration',update_time=now()
WHERE path='/nocode/report-center/dashboards' AND sort=0 AND deleted=0
    AND parent_id IN (SELECT id FROM public.system_menu WHERE path='/nocode/report-center' AND deleted=0);
UPDATE public.system_menu SET sort=2,updater='report-lifecycle-migration',update_time=now()
WHERE path='/nocode/report-center/datasets' AND sort=1 AND deleted=0
    AND parent_id IN (SELECT id FROM public.system_menu WHERE path='/nocode/report-center' AND deleted=0);
INSERT INTO public.system_menu(id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
SELECT nextval('public.system_menu_seq'),'报表工作台','nocode:report:query',2,0,id,
    '/nocode/report-center/home','HomeOutlined','nocode/report-center/home','NocodeReportHome',
    0,true,false,true,'report-lifecycle-migration','report-lifecycle-migration',now(),now(),0
FROM public.system_menu WHERE path='/nocode/report-center' AND deleted=0;
$v74$);

-- V075__task_template_primary_version.sql；原文件 SHA-256: 9c8b56e8f8e2051e8c0265584470b3810252ca632e26f71b2327246d3374d58a
INSERT INTO nocode_release_plan VALUES
(75,$json$[
  {
    "table": "nocode_task_template",
    "column": "primary_version"
  },
  {
    "table": "nocode_task_template",
    "constraint": "nocode_task_template_primary_version_ck",
    "token": "primary_version"
  }
]$json$::jsonb,$json$[
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
      },
      {
        "name": "root_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "authorization_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "primary_version",
        "type": "integer",
        "default": null,
        "notnull": false,
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
      },
      {
        "name": "nocode_task_template_primary_version_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((primary_version IS NULL) OR ((published_version IS NOT NULL) AND ((primary_version >= 1) AND (primary_version <= published_version)))))"
      }
    ]
  }
]$json$::jsonb,$v75$-- 前置 V074：在统一 dev 序列中引入任务模板主版本，不修改发布快照、实例和草稿。
-- 从未安装该字段的环境新增并回填；已执行旧分支任务 V066 的环境仅验证原结构。
-- 旧分支历史冲突须先按 manual/20261006_reconcile_task_v066.sql 审核处理，禁止自动 repair。
-- 验证：primary_version 可回指历史版本；兼容路径保留所有模板头值（包括 NULL）。
DO $$
BEGIN
    LOCK TABLE public.nocode_task_template IN ACCESS EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM pg_attribute
               WHERE attrelid='public.nocode_task_template'::regclass
                 AND attname='primary_version' AND NOT attisdropped) THEN
        IF NOT EXISTS (SELECT 1 FROM pg_attribute
                       WHERE attrelid='public.nocode_task_template'::regclass
                         AND attname='primary_version' AND NOT attisdropped
                         AND atttypid='integer'::regtype AND NOT attnotnull AND NOT atthasdef)
           OR NOT EXISTS (
               SELECT 1 FROM pg_constraint
               WHERE conrelid='public.nocode_task_template'::regclass
                 AND conname='nocode_task_template_primary_version_ck'
                 AND contype='c' AND convalidated
                 AND pg_get_constraintdef(oid) = 'CHECK (((primary_version IS NULL) OR ((published_version IS NOT NULL) AND ((primary_version >= 1) AND (primary_version <= published_version)))))'
           ) THEN
            RAISE EXCEPTION 'Unexpected task primary-version structure; inspect before migration';
        END IF;
        -- 不重复回填：用户可能已将默认发起版本切回早期版本。
    ELSE
        IF EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid='public.nocode_task_template'::regclass
                     AND conname='nocode_task_template_primary_version_ck') THEN
            RAISE EXCEPTION 'Unexpected task primary-version constraint without column';
        END IF;
        ALTER TABLE public.nocode_task_template ADD COLUMN primary_version integer;
        UPDATE public.nocode_task_template
        SET primary_version = published_version
        WHERE published_version IS NOT NULL;
        ALTER TABLE public.nocode_task_template
            ADD CONSTRAINT nocode_task_template_primary_version_ck
            CHECK (primary_version IS NULL OR
                   (published_version IS NOT NULL AND primary_version BETWEEN 1 AND published_version));
    END IF;
END $$;

COMMENT ON COLUMN public.nocode_task_template.published_version IS '最新发布序号，仅随新版本发布递增';
COMMENT ON COLUMN public.nocode_task_template.primary_version IS '默认发起版本；切换不修改模板草稿和已有实例';
$v75$);

-- V076__task_pause_resume.sql；原文件 SHA-256: 700f1e2ecca3cf0c3bd558d86a81e60c0185777fc11452878a3a26c44ec1007d
INSERT INTO nocode_release_plan VALUES
(76,$json$[
  {
    "table": "nocode_task_instance",
    "constraint": "nocode_task_state_ck",
    "token": "'PAUSED'"
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
        "notnull": false,
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
      },
      {
        "name": "application_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "planned_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "authorization_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "schedule_version",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_acceptor_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_acceptor_idx ON nocode_task_instance USING btree ((((config_json)::jsonb ->> 'acceptorId'::text)), status) WHERE ((deleted = 0) AND (parent_id IS NULL))"
      },
      {
        "name": "nocode_task_assignee_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_assignee_idx ON nocode_task_instance USING btree (assignee_id, status, expected_end) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_claimable_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_claimable_idx ON nocode_task_instance USING btree (create_time, id) WHERE ((deleted = 0) AND ((status)::text = 'PENDING'::text) AND (assignee_id IS NULL) AND (((config_json)::jsonb ->> 'assignmentMode'::text) = 'OPEN'::text))"
      },
      {
        "name": "nocode_task_instance_application_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_instance_application_idx ON nocode_task_instance USING btree (application_id, expected_end, id) WHERE (deleted = 0)"
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
        "definition": "CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'PAUSED'::character varying, 'PENDING_ACCEPTANCE'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))"
      }
    ]
  }
]$json$::jsonb,$v76$-- 任务暂停保存于当前节点；子任务通过祖先门控暂停，不改写其执行及完成事实。
-- 不改变已执行迁移，仅扩展状态约束；暂停/恢复事件复用现有任务事件表。
ALTER TABLE public.nocode_task_instance DROP CONSTRAINT nocode_task_state_ck;
ALTER TABLE public.nocode_task_instance ADD CONSTRAINT nocode_task_state_ck
    CHECK (status IN ('PENDING','RUNNING','PAUSED','PENDING_ACCEPTANCE','COMPLETED','CANCELLED'));
$v76$);

-- V077__workflow_task_nodes.sql；原文件 SHA-256: 4ad242dffa42c74a2c3e630cba27e9240687dec88f1eb66fc3c534abbf9bca68
INSERT INTO nocode_release_plan VALUES
(77,$json$[
  {
    "relation": "nocode_workflow_task_node"
  }
]$json$::jsonb,$json$[
  {
    "table": "nocode_workflow_task_node",
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
        "name": "tenant_id",
        "type": "bigint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "execution_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "process_instance_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "process_definition_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "node_id",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "node_name",
        "type": "character varying(255)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "initiator_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "publisher_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "configuration_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "people_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(32)",
        "default": "'CREATING'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_error",
        "type": "character varying(1000)",
        "default": null,
        "notnull": false,
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
        "name": "next_attempt_time",
        "type": "timestamp without time zone",
        "default": "CURRENT_TIMESTAMP",
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
        "type": "boolean",
        "default": "false",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_workflow_task_node_execution_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_workflow_task_node_execution_uk ON nocode_workflow_task_node USING btree (execution_id, process_instance_id, node_id)"
      },
      {
        "name": "nocode_workflow_task_node_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_workflow_task_node_pkey ON nocode_workflow_task_node USING btree (id)"
      },
      {
        "name": "nocode_workflow_task_node_process_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_workflow_task_node_process_idx ON nocode_workflow_task_node USING btree (tenant_id, process_instance_id) WHERE (deleted = false)"
      },
      {
        "name": "nocode_workflow_task_node_retry_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_workflow_task_node_retry_idx ON nocode_workflow_task_node USING btree (next_attempt_time) WHERE ((deleted = false) AND ((state)::text = ANY ((ARRAY['CREATING'::character varying, 'WAITING'::character varying])::text[])))"
      },
      {
        "name": "nocode_workflow_task_node_task_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_workflow_task_node_task_uk ON nocode_workflow_task_node USING btree (task_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_workflow_task_node_execution_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (execution_id, process_instance_id, node_id)"
      },
      {
        "name": "nocode_workflow_task_node_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_workflow_task_node_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['CREATING'::character varying, 'WAITING'::character varying, 'COMPLETED'::character varying, 'INVALIDATED'::character varying])::text[])))"
      },
      {
        "name": "nocode_workflow_task_node_task_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (task_id)"
      }
    ]
  }
]$json$::jsonb,$v77$-- 流程任务节点的持久交接记录；引擎与任务仍分别使用各自既有状态机。
CREATE TABLE public.nocode_workflow_task_node (
    id varchar(64) PRIMARY KEY,
    tenant_id bigint NOT NULL DEFAULT 0,
    execution_id varchar(64) NOT NULL,
    process_instance_id varchar(64) NOT NULL,
    process_definition_id varchar(128) NOT NULL,
    node_id varchar(200) NOT NULL,
    node_name varchar(255) NOT NULL,
    initiator_id bigint NOT NULL,
    publisher_id bigint NOT NULL,
    configuration_json text NOT NULL,
    people_json text NOT NULL,
    task_id varchar(64),
    state varchar(32) NOT NULL DEFAULT 'CREATING',
    last_error varchar(1000),
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    creator varchar(64) NOT NULL DEFAULT '',
    create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updater varchar(64) NOT NULL DEFAULT '',
    update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted boolean NOT NULL DEFAULT false,
    CONSTRAINT nocode_workflow_task_node_execution_uk UNIQUE (execution_id, process_instance_id, node_id),
    CONSTRAINT nocode_workflow_task_node_task_uk UNIQUE (task_id),
    CONSTRAINT nocode_workflow_task_node_state_ck CHECK (state IN ('CREATING','WAITING','COMPLETED','INVALIDATED'))
);
CREATE INDEX nocode_workflow_task_node_retry_idx ON public.nocode_workflow_task_node(next_attempt_time)
    WHERE deleted = false AND state IN ('CREATING','WAITING');
CREATE INDEX nocode_workflow_task_node_process_idx ON public.nocode_workflow_task_node(tenant_id,process_instance_id)
    WHERE deleted = false;
COMMENT ON TABLE public.nocode_workflow_task_node IS '工作流任务节点激活与完成交接；失败重试不重复创建或推进';
$v77$);

-- V078__task_efficiency_menu.sql；原文件 SHA-256: f5532e5be82c496297e36d81bf7b2947cdffe4317535042858d2629aa816e26d
INSERT INTO nocode_release_plan VALUES
(78,$json$[
  {
    "menu": "/nocode-app/task-center/efficiency"
  }
]$json$::jsonb,$json$[]$json$::jsonb,$v78$-- 前置V077：注册任务中心能效统计导航，不新增管理权限、不变更任务或办理数据。
-- 仅为已有有效query与create/manage-all的角色补入口；迁移测试在临时表执行核对授权。
DO $$
DECLARE folder_id bigint; efficiency_id bigint;
BEGIN
    LOCK TABLE public.system_menu, public.system_role_menu IN SHARE ROW EXCLUSIVE MODE;
    SELECT id INTO STRICT folder_id FROM public.system_menu
    WHERE path='/task-center' AND parent_id=0 AND component_name='NocodeTaskFolder' AND type=1 AND deleted=0;

    SELECT id INTO efficiency_id FROM public.system_menu
    WHERE path='/nocode-app/task-center/efficiency' AND deleted=0;
    IF efficiency_id IS NULL THEN
        efficiency_id:=nextval('public.system_menu_seq');
        INSERT INTO public.system_menu
            (id,name,permission,type,sort,parent_id,path,icon,component,component_name,status,
             visible,keep_alive,always_show,creator,updater,deleted)
        VALUES(efficiency_id,'能效统计','nocode:task:query',2,5,folder_id,
            '/nocode-app/task-center/efficiency','BarChartOutlined','nocode/task-center/TaskEfficiency',
            'NocodeTaskEfficiency',0,true,false,true,'task-efficiency-migration','task-efficiency-migration',0);
    END IF;

    WITH RECURSIVE effective_menus(role_id,tenant_id,menu_id,permission) AS (
        SELECT g.role_id,g.tenant_id,m.id,m.permission
        FROM public.system_role_menu g JOIN public.system_menu m ON m.id=g.menu_id
        WHERE g.deleted=0 AND m.deleted=0 AND m.status=0 AND m.parent_id=0
        UNION
        SELECT g.role_id,g.tenant_id,m.id,m.permission
        FROM effective_menus p JOIN public.system_menu m ON m.parent_id=p.menu_id
        JOIN public.system_role_menu g ON g.menu_id=m.id AND g.role_id=p.role_id AND g.tenant_id=p.tenant_id
        WHERE g.deleted=0 AND m.deleted=0 AND m.status=0
    )
    INSERT INTO public.system_role_menu
        (id,role_id,menu_id,creator,updater,create_time,update_time,deleted,tenant_id)
    SELECT nextval('public.system_role_menu_seq'),p.role_id,efficiency_id,
        'task-efficiency-migration','task-efficiency-migration',now(),now(),0,p.tenant_id
    FROM effective_menus p WHERE p.menu_id=folder_id
        AND EXISTS(SELECT 1 FROM effective_menus q WHERE q.role_id=p.role_id AND q.tenant_id=p.tenant_id AND q.permission='nocode:task:query')
        AND EXISTS(SELECT 1 FROM effective_menus manage WHERE manage.role_id=p.role_id AND manage.tenant_id=p.tenant_id AND manage.permission IN ('nocode:task:create','nocode:task:manage-all'))
        AND NOT EXISTS(SELECT 1 FROM public.system_role_menu old WHERE old.role_id=p.role_id AND old.tenant_id=p.tenant_id AND old.menu_id=efficiency_id AND old.deleted=0);
END $$;
$v78$);

-- V079__task_work_rule_snapshots.sql；原文件 SHA-256: 2960aeb961813711953501c52b4ce77338f49a5184da5c0dd6fa8882208abbcf
INSERT INTO nocode_release_plan VALUES
(79,$json$[
  {
    "table": "nocode_task_entry_record",
    "column": "work_rule_json"
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
      },
      {
        "name": "work_rule_json",
        "type": "text",
        "default": null,
        "notnull": false,
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
        "definition": "CHECK (((operation)::text = ANY ((ARRAY['CREATED'::character varying, 'UPDATED'::character varying, 'LINKED'::character varying, 'DELETED'::character varying, 'UNCHANGED'::character varying])::text[])))"
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
]$json$::jsonb,$v79$-- 固化办理时的计量标准；存量事实以升级时实例仍有效的规则留底。
-- 不改业务记录、审批材料、数量或既有时长，只封存以前动态引用的规则。
ALTER TABLE public.nocode_task_entry_record ADD COLUMN work_rule_json text;
COMMENT ON COLUMN public.nocode_task_entry_record.work_rule_json IS '办理提交时工时规则；存量由 V079 按升级时实例标准封存';
UPDATE public.nocode_task_entry_record f
SET work_rule_json=coalesce(b.config_json::jsonb->'workRule','null'::jsonb)::text
FROM public.nocode_task_entry_binding b
WHERE b.task_id=f.task_id AND b.entry_key=f.entry_key AND f.work_rule_json IS NULL;
UPDATE public.nocode_task_entry_record SET work_rule_json='null' WHERE work_rule_json IS NULL;
$v79$);

CREATE TEMP TABLE nocode_release_baseline (contract jsonb NOT NULL) ON COMMIT DROP;
INSERT INTO nocode_release_baseline VALUES ($json$[
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
  },
  {
    "table": "nocode_resource_dependency",
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
        "name": "source_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_key",
        "type": "character varying(160)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_name",
        "type": "character varying(160)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_object_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_ids_json",
        "type": "jsonb",
        "default": "'[]'::jsonb",
        "notnull": true,
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
        "name": "nocode_resource_dependency_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_resource_dependency_pkey ON nocode_resource_dependency USING btree (id)"
      },
      {
        "name": "nocode_resource_dependency_source_kind_source_key_target_ob_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_resource_dependency_source_kind_source_key_target_ob_key ON nocode_resource_dependency USING btree (source_kind, source_key, target_object_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_resource_dependency_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_resource_dependency_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_resource_dependency_source_kind_source_key_target_ob_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (source_kind, source_key, target_object_id)"
      }
    ]
  }
]$json$::jsonb);
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
        "notnull": false,
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
      },
      {
        "name": "application_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "planned_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "authorization_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "schedule_version",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_acceptor_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_acceptor_idx ON nocode_task_instance USING btree ((((config_json)::jsonb ->> 'acceptorId'::text)), status) WHERE ((deleted = 0) AND (parent_id IS NULL))"
      },
      {
        "name": "nocode_task_assignee_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_assignee_idx ON nocode_task_instance USING btree (assignee_id, status, expected_end) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_claimable_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_claimable_idx ON nocode_task_instance USING btree (create_time, id) WHERE ((deleted = 0) AND ((status)::text = 'PENDING'::text) AND (assignee_id IS NULL) AND (((config_json)::jsonb ->> 'assignmentMode'::text) = 'OPEN'::text))"
      },
      {
        "name": "nocode_task_instance_application_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_instance_application_idx ON nocode_task_instance USING btree (application_id, expected_end, id) WHERE (deleted = 0)"
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
        "definition": "CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'PAUSED'::character varying, 'PENDING_ACCEPTANCE'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))"
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
      },
      {
        "name": "root_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "authorization_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "primary_version",
        "type": "integer",
        "default": null,
        "notnull": false,
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
      },
      {
        "name": "nocode_task_template_primary_version_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((primary_version IS NULL) OR ((published_version IS NOT NULL) AND ((primary_version >= 1) AND (primary_version <= published_version)))))"
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
      },
      {
        "name": "root_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "authorization_json",
        "type": "text",
        "default": null,
        "notnull": false,
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
      },
      {
        "name": "end_date",
        "type": "date",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "history_reason",
        "type": "character varying(80)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "plan_mode",
        "type": "character varying(16)",
        "default": "'SCHEDULE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_checklist_membership_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_checklist_membership_idx ON nocode_task_plan USING btree (user_id, period, plan_date, task_id) WHERE ((deleted = 0) AND ((plan_mode)::text = 'CHECKLIST'::text))"
      },
      {
        "name": "nocode_task_plan_active_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_active_uk ON nocode_task_plan USING btree (task_id, user_id, plan_mode, period, plan_date, end_date, source) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_plan_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_pkey ON nocode_task_plan USING btree (id)"
      },
      {
        "name": "nocode_task_plan_range_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_plan_range_idx ON nocode_task_plan USING btree (user_id, plan_date, end_date) WHERE (deleted = 0)"
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
        "name": "nocode_task_checklist_period_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((((plan_mode)::text <> 'CHECKLIST'::text) OR (((period)::text = 'DAY'::text) AND (end_date = plan_date)) OR (((period)::text = 'WEEK'::text) AND (EXTRACT(isodow FROM plan_date) = (1)::numeric) AND (end_date = (plan_date + 6)))))"
      },
      {
        "name": "nocode_task_plan_mode_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((plan_mode)::text = ANY ((ARRAY['SCHEDULE'::character varying, 'CHECKLIST'::character varying])::text[])))"
      },
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
        "name": "nocode_task_plan_range_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((end_date >= plan_date))"
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
      },
      {
        "name": "work_rule_json",
        "type": "text",
        "default": null,
        "notnull": false,
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
        "definition": "CHECK (((operation)::text = ANY ((ARRAY['CREATED'::character varying, 'UPDATED'::character varying, 'LINKED'::character varying, 'DELETED'::character varying, 'UNCHANGED'::character varying])::text[])))"
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
  },
  {
    "table": "nocode_task_launch_draft",
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
        "name": "lock_version",
        "type": "integer",
        "default": "1",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "content_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "published_task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "publish_key",
        "type": "character varying(120)",
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
        "default": "clock_timestamp()",
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
        "default": "clock_timestamp()",
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
        "name": "nocode_task_launch_draft_owner_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_launch_draft_owner_idx ON nocode_task_launch_draft USING btree (creator, update_time DESC, id DESC) WHERE ((deleted = 0) AND (published_task_id IS NULL))"
      },
      {
        "name": "nocode_task_launch_draft_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_launch_draft_pkey ON nocode_task_launch_draft USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "ck_nocode_task_launch_draft_publish",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((((published_task_id IS NULL) AND (publish_key IS NULL)) OR ((published_task_id IS NOT NULL) AND (publish_key IS NOT NULL))))"
      },
      {
        "name": "ck_nocode_task_launch_draft_revision",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_task_launch_draft_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "nocode_linkage_trigger",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "application_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_object_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_object_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_object_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_field_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "anchor",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "anchor_field_id",
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
        "name": "nocode_linkage_trigger_field",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_linkage_trigger_field ON nocode_linkage_trigger USING btree (application_id, application_version, target_object_id, target_field_id)"
      },
      {
        "name": "nocode_linkage_trigger_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_linkage_trigger_pkey ON nocode_linkage_trigger USING btree (id)"
      },
      {
        "name": "nocode_linkage_trigger_source",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_linkage_trigger_source ON nocode_linkage_trigger USING btree (application_id, application_version, source_object_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_linkage_trigger_source_any",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_linkage_trigger_source_any ON nocode_linkage_trigger USING btree (source_object_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_linkage_trigger_target_any",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_linkage_trigger_target_any ON nocode_linkage_trigger USING btree (target_object_id) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_linkage_trigger_anchor",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((anchor)::text = ANY ((ARRAY['CURRENT_RECORD'::character varying, 'RECORD_KEY'::character varying])::text[])))"
      },
      {
        "name": "nocode_linkage_trigger_field",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (application_id, application_version, target_object_id, target_field_id)"
      },
      {
        "name": "nocode_linkage_trigger_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "nocode_application_object_follow",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
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
        "name": "enabled",
        "type": "boolean",
        "default": "true",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(16)",
        "default": "'FOLLOWING'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "pending_version",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "pending_code",
        "type": "character varying(24)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "pending_reason",
        "type": "character varying(1000)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "followed_version",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "followed_at",
        "type": "timestamp(6) without time zone",
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
        "name": "nocode_application_object_follow_object",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_application_object_follow_object ON nocode_application_object_follow USING btree (object_id)"
      },
      {
        "name": "nocode_application_object_follow_pending_ix",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_application_object_follow_pending_ix ON nocode_application_object_follow USING btree (state) WHERE (((state)::text = 'PENDING'::text) AND (deleted = 0))"
      },
      {
        "name": "nocode_application_object_follow_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_application_object_follow_pkey ON nocode_application_object_follow USING btree (id)"
      },
      {
        "name": "nocode_application_object_follow_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_application_object_follow_uk ON nocode_application_object_follow USING btree (application_id, object_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_application_object_follow_application_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (application_id) REFERENCES nocode_application(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_application_object_follow_code",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((pending_code IS NULL) OR ((pending_code)::text = ANY ((ARRAY['IN_FLIGHT'::character varying, 'VALIDATION'::character varying, 'ERROR'::character varying])::text[]))))"
      },
      {
        "name": "nocode_application_object_follow_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_application_object_follow_object_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (object_id) REFERENCES nocode_object(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_application_object_follow_pending",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((((state)::text = 'PENDING'::text) = ((pending_version IS NOT NULL) AND (pending_code IS NOT NULL))))"
      },
      {
        "name": "nocode_application_object_follow_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_application_object_follow_state",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['FOLLOWING'::character varying, 'PENDING'::character varying])::text[])))"
      },
      {
        "name": "nocode_application_object_follow_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (application_id, object_id)"
      }
    ]
  },
  {
    "table": "nocode_application_object_follow_log",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
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
        "name": "from_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "to_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "application_version_before",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "application_version_after",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "outcome",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "pending_code",
        "type": "character varying(24)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "reason",
        "type": "character varying(1000)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "trigger_kind",
        "type": "character varying(24)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "plan_id",
        "type": "uuid",
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
        "name": "nocode_application_object_follow_log_app",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_application_object_follow_log_app ON nocode_application_object_follow_log USING btree (application_id, object_id, id)"
      },
      {
        "name": "nocode_application_object_follow_log_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_application_object_follow_log_pkey ON nocode_application_object_follow_log USING btree (id)"
      },
      {
        "name": "nocode_application_object_follow_log_plan",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_application_object_follow_log_plan ON nocode_application_object_follow_log USING btree (plan_id) WHERE (plan_id IS NOT NULL)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_application_object_follow_log_application_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (application_id) REFERENCES nocode_application(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_application_object_follow_log_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_application_object_follow_log_object_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (object_id) REFERENCES nocode_object(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_application_object_follow_log_outcome",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((outcome)::text = ANY ((ARRAY['FOLLOWED'::character varying, 'PENDING'::character varying])::text[])))"
      },
      {
        "name": "nocode_application_object_follow_log_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_application_object_follow_log_trigger",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((trigger_kind)::text = ANY ((ARRAY['OBJECT_PUBLISH'::character varying, 'SWITCH_ON'::character varying, 'MANUAL'::character varying, 'RETRY_JOB'::character varying, 'APPLICATION_PUBLISH'::character varying, 'MIGRATION'::character varying])::text[])))"
      }
    ]
  },
  {
    "table": "nocode_record_folder_source",
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
        "name": "sort_no",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "label",
        "type": "character varying(40)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "kind",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "placement",
        "type": "character varying(24)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "space_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "relation_field_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_source_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_mode",
        "type": "character varying(16)",
        "default": "'ON_FIRST_WRITE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "name_template",
        "type": "character varying(2000)",
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
        "name": "nocode_record_folder_source_object_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_record_folder_source_object_idx ON nocode_record_folder_source USING btree (object_id, sort_no) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_record_folder_source_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_record_folder_source_pkey ON nocode_record_folder_source USING btree (id)"
      },
      {
        "name": "nocode_record_folder_source_target_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_record_folder_source_target_idx ON nocode_record_folder_source USING btree (target_source_id) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_record_folder_source_create_mode",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((create_mode)::text = ANY ((ARRAY['ON_FIRST_WRITE'::character varying, 'ON_SAVE'::character varying])::text[])))"
      },
      {
        "name": "nocode_record_folder_source_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_record_folder_source_kind",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((kind)::text = ANY ((ARRAY['FOLDER'::character varying, 'RELATION'::character varying])::text[])))"
      },
      {
        "name": "nocode_record_folder_source_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_record_folder_source_placement",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((placement)::text = ANY ((ARRAY['DIRECT'::character varying, 'RECORD_SUBFOLDER'::character varying])::text[])))"
      },
      {
        "name": "nocode_record_folder_source_shape",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((((kind)::text = 'FOLDER'::text) AND (space_id IS NOT NULL) AND (entry_id IS NOT NULL) AND ((relation_field_id)::text = ''::text) AND (target_source_id IS NULL)) OR (((kind)::text = 'RELATION'::text) AND (space_id IS NULL) AND (entry_id IS NULL) AND ((relation_field_id)::text <> ''::text) AND (target_source_id IS NOT NULL))))"
      }
    ]
  },
  {
    "table": "nocode_record_folder_binding",
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
        "name": "source_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "anchor_entry_id",
        "type": "bigint",
        "default": "0",
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
        "name": "origin",
        "type": "character varying(16)",
        "default": "'AUTO'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "auto_name",
        "type": "character varying(255)",
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
        "name": "nocode_record_folder_binding_entry_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_record_folder_binding_entry_idx ON nocode_record_folder_binding USING btree (entry_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_record_folder_binding_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_record_folder_binding_pkey ON nocode_record_folder_binding USING btree (id)"
      },
      {
        "name": "nocode_record_folder_binding_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_record_folder_binding_uk ON nocode_record_folder_binding USING btree (object_id, record_id, source_id, anchor_entry_id) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_record_folder_binding_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_record_folder_binding_origin",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((origin)::text = ANY ((ARRAY['AUTO'::character varying, 'MANUAL'::character varying])::text[])))"
      },
      {
        "name": "nocode_record_folder_binding_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "drive_entry_origin",
    "columns": [
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "origin_key",
        "type": "character varying(700)",
        "default": null,
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
        "name": "drive_entry_origin_key_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX drive_entry_origin_key_idx ON drive_entry_origin USING btree (origin_key) WHERE (deleted = 0)"
      },
      {
        "name": "drive_entry_origin_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX drive_entry_origin_pkey ON drive_entry_origin USING btree (entry_id)"
      }
    ],
    "constraints": [
      {
        "name": "drive_entry_origin_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "drive_entry_origin_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (entry_id)"
      }
    ]
  },
  {
    "table": "nocode_date_trigger_state",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "resource_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "armed",
        "type": "boolean",
        "default": "true",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "closed_date",
        "type": "date",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "armed_at",
        "type": "timestamp(6) without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_scan_at",
        "type": "timestamp(6) without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_scan_date",
        "type": "date",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_trigger",
        "type": "character varying(16)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_error",
        "type": "character varying(1000)",
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
        "name": "nocode_date_trigger_state_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_date_trigger_state_pkey ON nocode_date_trigger_state USING btree (id)"
      },
      {
        "name": "nocode_date_trigger_state_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_date_trigger_state_uk ON nocode_date_trigger_state USING btree (application_id, resource_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_date_trigger_state_application_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (application_id) REFERENCES nocode_application(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_date_trigger_state_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_date_trigger_state_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_date_trigger_state_trigger",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((last_trigger IS NULL) OR ((last_trigger)::text = ANY ((ARRAY['AUTO'::character varying, 'MANUAL'::character varying])::text[]))))"
      },
      {
        "name": "nocode_date_trigger_state_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (application_id, resource_id)"
      }
    ]
  },
  {
    "table": "nocode_date_trigger_done",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "resource_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "business_date",
        "type": "date",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_record_id",
        "type": "character varying(500)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "outcome",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_count",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "message",
        "type": "character varying(1000)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "done_at",
        "type": "timestamp(6) without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
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
        "name": "nocode_date_trigger_done_date",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_date_trigger_done_date ON nocode_date_trigger_done USING btree (business_date)"
      },
      {
        "name": "nocode_date_trigger_done_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_date_trigger_done_pkey ON nocode_date_trigger_done USING btree (id)"
      },
      {
        "name": "nocode_date_trigger_done_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_date_trigger_done_uk ON nocode_date_trigger_done USING btree (application_id, resource_id, business_date, source_record_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_date_trigger_done_application_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (application_id) REFERENCES nocode_application(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_date_trigger_done_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_date_trigger_done_outcome",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((outcome)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCESS'::character varying, 'UNCHANGED'::character varying, 'FAILED'::character varying])::text[])))"
      },
      {
        "name": "nocode_date_trigger_done_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_date_trigger_done_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (application_id, resource_id, business_date, source_record_id)"
      }
    ]
  },
  {
    "table": "nocode_report_dataset",
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
        "name": "name",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "description",
        "type": "character varying(1000)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "owner_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "status",
        "type": "character varying(16)",
        "default": "'INACTIVE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "folder_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_dataset_folder_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dataset_folder_idx ON nocode_report_dataset USING btree (folder_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_dataset_owner_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dataset_owner_idx ON nocode_report_dataset USING btree (owner_id, update_time DESC, id DESC) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_dataset_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_pkey ON nocode_report_dataset USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dataset_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dataset_folder_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (folder_id) REFERENCES nocode_report_folder(id)"
      },
      {
        "name": "nocode_report_dataset_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_dataset_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dataset_published_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((published_version > 0))"
      },
      {
        "name": "nocode_report_dataset_status_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))"
      }
    ]
  },
  {
    "table": "nocode_report_dataset_version",
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
        "name": "dataset_id",
        "type": "bigint",
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
        "name": "definition_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "reason",
        "type": "character varying(1000)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_id",
        "type": "character varying(80)",
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
        "name": "nocode_report_dataset_version_dataset_id_creator_request_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_version_dataset_id_creator_request_id_key ON nocode_report_dataset_version USING btree (dataset_id, creator, request_id)"
      },
      {
        "name": "nocode_report_dataset_version_dataset_id_version_no_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_version_dataset_id_version_no_key ON nocode_report_dataset_version USING btree (dataset_id, version_no)"
      },
      {
        "name": "nocode_report_dataset_version_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_version_pkey ON nocode_report_dataset_version USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dataset_version_dataset_id_creator_request_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dataset_id, creator, request_id)"
      },
      {
        "name": "nocode_report_dataset_version_dataset_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dataset_id) REFERENCES nocode_report_dataset(id)"
      },
      {
        "name": "nocode_report_dataset_version_dataset_id_version_no_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dataset_id, version_no)"
      },
      {
        "name": "nocode_report_dataset_version_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dataset_version_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dataset_version_version_no_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((version_no > 0))"
      }
    ]
  },
  {
    "table": "nocode_report_operation_log",
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
        "name": "resource_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "resource_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "action",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "revision",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "before_json",
        "type": "jsonb",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "after_json",
        "type": "jsonb",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "reason",
        "type": "character varying(1000)",
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
        "name": "nocode_report_operation_log_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_operation_log_pkey ON nocode_report_operation_log USING btree (id)"
      },
      {
        "name": "nocode_report_operation_resource_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_operation_resource_idx ON nocode_report_operation_log USING btree (resource_kind, resource_id, id DESC)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_operation_log_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_operation_log_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "nocode_report_object_grant",
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
        "name": "dataset_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
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
        "name": "grant_json",
        "type": "jsonb",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "reason",
        "type": "character varying(1000)",
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
        "name": "nocode_report_object_grant_dataset_id_object_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_object_grant_dataset_id_object_id_key ON nocode_report_object_grant USING btree (dataset_id, object_id)"
      },
      {
        "name": "nocode_report_object_grant_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_object_grant_pkey ON nocode_report_object_grant USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_object_grant_dataset_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dataset_id) REFERENCES nocode_report_dataset(id)"
      },
      {
        "name": "nocode_report_object_grant_dataset_id_object_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dataset_id, object_id)"
      },
      {
        "name": "nocode_report_object_grant_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_object_grant_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_object_grant_object_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (object_id) REFERENCES nocode_object(id)"
      },
      {
        "name": "nocode_report_object_grant_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "nocode_report_dataset_policy",
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
        "name": "dataset_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "members_json",
        "type": "jsonb",
        "default": "'[]'::jsonb",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "nocode_report_dataset_policy_dataset_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_policy_dataset_id_key ON nocode_report_dataset_policy USING btree (dataset_id)"
      },
      {
        "name": "nocode_report_dataset_policy_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_policy_pkey ON nocode_report_dataset_policy USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dataset_policy_dataset_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dataset_id) REFERENCES nocode_report_dataset(id)"
      },
      {
        "name": "nocode_report_dataset_policy_dataset_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dataset_id)"
      },
      {
        "name": "nocode_report_dataset_policy_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dataset_policy_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_dataset_policy_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ]
  },
  {
    "table": "nocode_report_resource_acl",
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
        "name": "resource_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "resource_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "policy_json",
        "type": "jsonb",
        "default": "'[]'::jsonb",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "nocode_report_resource_acl_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_resource_acl_pkey ON nocode_report_resource_acl USING btree (id)"
      },
      {
        "name": "nocode_report_resource_acl_resource_kind_resource_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_resource_acl_resource_kind_resource_id_key ON nocode_report_resource_acl USING btree (resource_kind, resource_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_resource_acl_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_resource_acl_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_resource_acl_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_resource_acl_resource_kind_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((resource_kind)::text = ANY ((ARRAY['DATASET'::character varying, 'DASHBOARD'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_resource_acl_resource_kind_resource_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (resource_kind, resource_id)"
      }
    ]
  },
  {
    "table": "nocode_report_dependency",
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
        "name": "source_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_stage",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_refs_json",
        "type": "jsonb",
        "default": "'[]'::jsonb",
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
        "name": "nocode_report_dependency_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dependency_pkey ON nocode_report_dependency USING btree (id)"
      },
      {
        "name": "nocode_report_dependency_source_kind_source_id_source_stage_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dependency_source_kind_source_id_source_stage_key ON nocode_report_dependency USING btree (source_kind, source_id, source_stage, source_version, target_kind, target_id, target_version)"
      },
      {
        "name": "nocode_report_dependency_target_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dependency_target_idx ON nocode_report_dependency USING btree (target_kind, target_id) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dependency_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((((source_stage)::text = 'DRAFT'::text) AND (source_version = 0)) OR (((source_stage)::text = 'VERSION'::text) AND (source_version > 0))))"
      },
      {
        "name": "nocode_report_dependency_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dependency_field_refs_json_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((jsonb_typeof(field_refs_json) = 'array'::text))"
      },
      {
        "name": "nocode_report_dependency_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dependency_source_kind_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((source_kind)::text = ANY ((ARRAY['DASHBOARD'::character varying, 'APPLICATION'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_dependency_source_kind_source_id_source_stage_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (source_kind, source_id, source_stage, source_version, target_kind, target_id, target_version)"
      },
      {
        "name": "nocode_report_dependency_source_stage_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((source_stage)::text = ANY ((ARRAY['DRAFT'::character varying, 'VERSION'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_dependency_source_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((source_version >= 0))"
      },
      {
        "name": "nocode_report_dependency_target_kind_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((target_kind)::text = ANY ((ARRAY['DATASET'::character varying, 'DASHBOARD'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_dependency_target_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((target_version > 0))"
      }
    ]
  },
  {
    "table": "nocode_report_folder",
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
        "name": "resource_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "parent_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "name",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "sort_no",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "nocode_report_folder_parent_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_folder_parent_idx ON nocode_report_folder USING btree (resource_kind, parent_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_folder_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_folder_pkey ON nocode_report_folder USING btree (id)"
      },
      {
        "name": "nocode_report_folder_sibling_name_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_folder_sibling_name_idx ON nocode_report_folder USING btree (resource_kind, COALESCE(parent_id, (0)::bigint), lower((name)::text)) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_folder_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((parent_id IS DISTINCT FROM id))"
      },
      {
        "name": "nocode_report_folder_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_folder_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_folder_parent_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (parent_id) REFERENCES nocode_report_folder(id)"
      },
      {
        "name": "nocode_report_folder_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_folder_resource_kind_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((resource_kind)::text = ANY ((ARRAY['DATASET'::character varying, 'DASHBOARD'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_folder_sort_no_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((sort_no >= 0) AND (sort_no <= 9999)))"
      }
    ]
  },
  {
    "table": "nocode_report_dashboard",
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
        "name": "name",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "owner_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "status",
        "type": "character varying(16)",
        "default": "'ACTIVE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "folder_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_dashboard_folder_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dashboard_folder_idx ON nocode_report_dashboard USING btree (folder_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_dashboard_owner_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dashboard_owner_idx ON nocode_report_dashboard USING btree (owner_id, update_time DESC, id DESC) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_dashboard_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dashboard_pkey ON nocode_report_dashboard USING btree (id)"
      },
      {
        "name": "nocode_report_dashboard_status_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dashboard_status_idx ON nocode_report_dashboard USING btree (status, update_time DESC, id DESC) WHERE (deleted = 0)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dashboard_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dashboard_folder_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (folder_id) REFERENCES nocode_report_folder(id)"
      },
      {
        "name": "nocode_report_dashboard_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_dashboard_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dashboard_published_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((published_version > 0))"
      },
      {
        "name": "nocode_report_dashboard_status_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))"
      }
    ]
  },
  {
    "table": "nocode_report_dashboard_version",
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
        "name": "dashboard_id",
        "type": "bigint",
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
        "name": "definition_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_id",
        "type": "character varying(80)",
        "default": null,
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
        "name": "nocode_report_dashboard_versi_dashboard_id_creator_request__key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dashboard_versi_dashboard_id_creator_request__key ON nocode_report_dashboard_version USING btree (dashboard_id, creator, request_id)"
      },
      {
        "name": "nocode_report_dashboard_version_dashboard_id_version_no_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dashboard_version_dashboard_id_version_no_key ON nocode_report_dashboard_version USING btree (dashboard_id, version_no)"
      },
      {
        "name": "nocode_report_dashboard_version_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dashboard_version_pkey ON nocode_report_dashboard_version USING btree (id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dashboard_versi_dashboard_id_creator_request__key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dashboard_id, creator, request_id)"
      },
      {
        "name": "nocode_report_dashboard_version_dashboard_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dashboard_id) REFERENCES nocode_report_dashboard(id)"
      },
      {
        "name": "nocode_report_dashboard_version_dashboard_id_version_no_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dashboard_id, version_no)"
      },
      {
        "name": "nocode_report_dashboard_version_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dashboard_version_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dashboard_version_version_no_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((version_no > 0))"
      }
    ]
  },
  {
    "table": "nocode_report_preference",
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
        "name": "dashboard_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "favorite",
        "type": "boolean",
        "default": "false",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_visited_at",
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
        "name": "nocode_report_preference_favorite_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_preference_favorite_idx ON nocode_report_preference USING btree (user_id, update_time DESC, dashboard_id DESC) WHERE ((deleted = 0) AND favorite)"
      },
      {
        "name": "nocode_report_preference_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_preference_pkey ON nocode_report_preference USING btree (id)"
      },
      {
        "name": "nocode_report_preference_recent_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_preference_recent_idx ON nocode_report_preference USING btree (user_id, last_visited_at DESC, dashboard_id DESC) WHERE ((deleted = 0) AND (last_visited_at IS NOT NULL))"
      },
      {
        "name": "nocode_report_preference_user_id_dashboard_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_preference_user_id_dashboard_id_key ON nocode_report_preference USING btree (user_id, dashboard_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_preference_dashboard_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dashboard_id) REFERENCES nocode_report_dashboard(id)"
      },
      {
        "name": "nocode_report_preference_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_preference_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_preference_user_id_dashboard_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (user_id, dashboard_id)"
      }
    ]
  },
  {
    "table": "nocode_workflow_task_node",
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
        "name": "tenant_id",
        "type": "bigint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "execution_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "process_instance_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "process_definition_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "node_id",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "node_name",
        "type": "character varying(255)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "initiator_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "publisher_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "configuration_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "people_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(32)",
        "default": "'CREATING'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_error",
        "type": "character varying(1000)",
        "default": null,
        "notnull": false,
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
        "name": "next_attempt_time",
        "type": "timestamp without time zone",
        "default": "CURRENT_TIMESTAMP",
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
        "type": "boolean",
        "default": "false",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "indexes": [
      {
        "name": "nocode_workflow_task_node_execution_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_workflow_task_node_execution_uk ON nocode_workflow_task_node USING btree (execution_id, process_instance_id, node_id)"
      },
      {
        "name": "nocode_workflow_task_node_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_workflow_task_node_pkey ON nocode_workflow_task_node USING btree (id)"
      },
      {
        "name": "nocode_workflow_task_node_process_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_workflow_task_node_process_idx ON nocode_workflow_task_node USING btree (tenant_id, process_instance_id) WHERE (deleted = false)"
      },
      {
        "name": "nocode_workflow_task_node_retry_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_workflow_task_node_retry_idx ON nocode_workflow_task_node USING btree (next_attempt_time) WHERE ((deleted = false) AND ((state)::text = ANY ((ARRAY['CREATING'::character varying, 'WAITING'::character varying])::text[])))"
      },
      {
        "name": "nocode_workflow_task_node_task_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_workflow_task_node_task_uk ON nocode_workflow_task_node USING btree (task_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_workflow_task_node_execution_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (execution_id, process_instance_id, node_id)"
      },
      {
        "name": "nocode_workflow_task_node_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_workflow_task_node_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['CREATING'::character varying, 'WAITING'::character varying, 'COMPLETED'::character varying, 'INVALIDATED'::character varying])::text[])))"
      },
      {
        "name": "nocode_workflow_task_node_task_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (task_id)"
      }
    ]
  },
  {
    "table": "nocode_resource_dependency",
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
        "name": "source_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_key",
        "type": "character varying(160)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_name",
        "type": "character varying(160)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_object_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_ids_json",
        "type": "jsonb",
        "default": "'[]'::jsonb",
        "notnull": true,
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
        "name": "nocode_resource_dependency_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_resource_dependency_pkey ON nocode_resource_dependency USING btree (id)"
      },
      {
        "name": "nocode_resource_dependency_source_kind_source_key_target_ob_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_resource_dependency_source_kind_source_key_target_ob_key ON nocode_resource_dependency USING btree (source_kind, source_key, target_object_id)"
      }
    ],
    "constraints": [
      {
        "name": "nocode_resource_dependency_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_resource_dependency_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_resource_dependency_source_kind_source_key_target_ob_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (source_kind, source_key, target_object_id)"
      }
    ]
  }
]$json$::jsonb);
CREATE TEMP TABLE nocode_release_alternatives (contract jsonb NOT NULL) ON COMMIT DROP;
INSERT INTO nocode_release_alternatives VALUES ($json$[
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
    ],
    "indexes": [
      {
        "name": "drive_storage_setting_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX drive_storage_setting_pkey ON drive_storage_setting USING btree (id)"
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
      },
      {
        "name": "application_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "assignee_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "planned_start",
        "type": "timestamp without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "authorization_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "schedule_version",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
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
      },
      {
        "name": "nocode_task_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'PENDING_ACCEPTANCE'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'RUNNING'::character varying, 'PAUSED'::character varying, 'PENDING_ACCEPTANCE'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[])))"
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
      },
      {
        "name": "nocode_task_instance_application_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_instance_application_idx ON nocode_task_instance USING btree (application_id, expected_end, id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_claimable_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_claimable_idx ON nocode_task_instance USING btree (create_time, id) WHERE ((deleted = 0) AND ((status)::text = 'PENDING'::text) AND (assignee_id IS NULL) AND (((config_json)::jsonb ->> 'assignmentMode'::text) = 'OPEN'::text))"
      },
      {
        "name": "nocode_task_acceptor_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_acceptor_idx ON nocode_task_instance USING btree ((((config_json)::jsonb ->> 'acceptorId'::text)), status) WHERE ((deleted = 0) AND (parent_id IS NULL))"
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
      },
      {
        "name": "root_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "authorization_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "primary_version",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
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
      },
      {
        "name": "nocode_task_template_primary_version_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((primary_version IS NULL) OR ((published_version IS NOT NULL) AND ((primary_version >= 1) AND (primary_version <= published_version)))))"
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_template_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_template_pkey ON nocode_task_template USING btree (id)"
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
      },
      {
        "name": "root_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "authorization_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
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
      },
      {
        "name": "end_date",
        "type": "date",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "history_reason",
        "type": "character varying(80)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "plan_mode",
        "type": "character varying(16)",
        "default": "'SCHEDULE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
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
      },
      {
        "name": "nocode_task_plan_range_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((end_date >= plan_date))"
      },
      {
        "name": "nocode_task_checklist_period_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((((plan_mode)::text <> 'CHECKLIST'::text) OR (((period)::text = 'DAY'::text) AND (end_date = plan_date)) OR (((period)::text = 'WEEK'::text) AND (EXTRACT(isodow FROM plan_date) = (1)::numeric) AND (end_date = (plan_date + 6)))))"
      },
      {
        "name": "nocode_task_plan_mode_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((plan_mode)::text = ANY ((ARRAY['SCHEDULE'::character varying, 'CHECKLIST'::character varying])::text[])))"
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
      },
      {
        "name": "nocode_task_plan_active_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_active_uk ON nocode_task_plan USING btree (task_id, user_id, period, plan_date, end_date, source) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_plan_range_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_plan_range_idx ON nocode_task_plan USING btree (user_id, plan_date, end_date) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_task_checklist_membership_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_checklist_membership_idx ON nocode_task_plan USING btree (user_id, period, plan_date, task_id) WHERE ((deleted = 0) AND ((plan_mode)::text = 'CHECKLIST'::text))"
      },
      {
        "name": "nocode_task_plan_active_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_plan_active_uk ON nocode_task_plan USING btree (task_id, user_id, plan_mode, period, plan_date, end_date, source) WHERE (deleted = 0)"
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
    "constraints": [
      {
        "name": "nocode_task_event_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
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
      },
      {
        "name": "work_rule_json",
        "type": "text",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
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
      },
      {
        "name": "nocode_task_entry_record_operation_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((operation)::text = ANY ((ARRAY['CREATED'::character varying, 'UPDATED'::character varying, 'LINKED'::character varying, 'DELETED'::character varying])::text[])))"
      },
      {
        "name": "nocode_task_entry_record_operation_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((operation)::text = ANY ((ARRAY['CREATED'::character varying, 'UPDATED'::character varying, 'LINKED'::character varying, 'DELETED'::character varying, 'UNCHANGED'::character varying])::text[])))"
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
    "constraints": [],
    "indexes": []
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
    "constraints": [],
    "indexes": [
      {
        "name": "drive_space_biz_name_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX drive_space_biz_name_uk ON drive_space USING btree (name) WHERE (((type)::text = 'BIZ'::text) AND (deleted = 0))"
      }
    ]
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
    "constraints": [],
    "indexes": []
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
    ]
  },
  {
    "table": "nocode_resource_dependency",
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
        "name": "source_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_key",
        "type": "character varying(160)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_name",
        "type": "character varying(160)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_object_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_ids_json",
        "type": "jsonb",
        "default": "'[]'::jsonb",
        "notnull": true,
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
        "name": "deleted",
        "type": "smallint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "constraints": [
      {
        "name": "nocode_resource_dependency_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_resource_dependency_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_resource_dependency_source_kind_source_key_target_ob_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (source_kind, source_key, target_object_id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_resource_dependency_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_resource_dependency_pkey ON nocode_resource_dependency USING btree (id)"
      },
      {
        "name": "nocode_resource_dependency_source_kind_source_key_target_ob_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_resource_dependency_source_kind_source_key_target_ob_key ON nocode_resource_dependency USING btree (source_kind, source_key, target_object_id)"
      }
    ]
  },
  {
    "table": "nocode_task_launch_draft",
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
        "name": "lock_version",
        "type": "integer",
        "default": "1",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "content_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "published_task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "publish_key",
        "type": "character varying(120)",
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
        "default": "clock_timestamp()",
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
        "default": "clock_timestamp()",
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
    "constraints": [
      {
        "name": "ck_nocode_task_launch_draft_publish",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((((published_task_id IS NULL) AND (publish_key IS NULL)) OR ((published_task_id IS NOT NULL) AND (publish_key IS NOT NULL))))"
      },
      {
        "name": "ck_nocode_task_launch_draft_revision",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_task_launch_draft_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_task_launch_draft_owner_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_task_launch_draft_owner_idx ON nocode_task_launch_draft USING btree (creator, update_time DESC, id DESC) WHERE ((deleted = 0) AND (published_task_id IS NULL))"
      },
      {
        "name": "nocode_task_launch_draft_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_task_launch_draft_pkey ON nocode_task_launch_draft USING btree (id)"
      }
    ]
  },
  {
    "table": "nocode_linkage_trigger",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "application_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_object_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_object_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_object_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_field_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "anchor",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "anchor_field_id",
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
    "constraints": [
      {
        "name": "nocode_linkage_trigger_anchor",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((anchor)::text = ANY ((ARRAY['CURRENT_RECORD'::character varying, 'RECORD_KEY'::character varying])::text[])))"
      },
      {
        "name": "nocode_linkage_trigger_field",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (application_id, application_version, target_object_id, target_field_id)"
      },
      {
        "name": "nocode_linkage_trigger_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_linkage_trigger_field",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_linkage_trigger_field ON nocode_linkage_trigger USING btree (application_id, application_version, target_object_id, target_field_id)"
      },
      {
        "name": "nocode_linkage_trigger_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_linkage_trigger_pkey ON nocode_linkage_trigger USING btree (id)"
      },
      {
        "name": "nocode_linkage_trigger_source",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_linkage_trigger_source ON nocode_linkage_trigger USING btree (application_id, application_version, source_object_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_linkage_trigger_source_any",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_linkage_trigger_source_any ON nocode_linkage_trigger USING btree (source_object_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_linkage_trigger_target_any",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_linkage_trigger_target_any ON nocode_linkage_trigger USING btree (target_object_id) WHERE (deleted = 0)"
      }
    ]
  },
  {
    "table": "nocode_application_object_follow",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
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
        "name": "enabled",
        "type": "boolean",
        "default": "true",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(16)",
        "default": "'FOLLOWING'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "pending_version",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "pending_code",
        "type": "character varying(24)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "pending_reason",
        "type": "character varying(1000)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "followed_version",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "followed_at",
        "type": "timestamp(6) without time zone",
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
    "constraints": [
      {
        "name": "nocode_application_object_follow_application_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (application_id) REFERENCES nocode_application(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_application_object_follow_code",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((pending_code IS NULL) OR ((pending_code)::text = ANY ((ARRAY['IN_FLIGHT'::character varying, 'VALIDATION'::character varying, 'ERROR'::character varying])::text[]))))"
      },
      {
        "name": "nocode_application_object_follow_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_application_object_follow_object_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (object_id) REFERENCES nocode_object(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_application_object_follow_pending",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((((state)::text = 'PENDING'::text) = ((pending_version IS NOT NULL) AND (pending_code IS NOT NULL))))"
      },
      {
        "name": "nocode_application_object_follow_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_application_object_follow_state",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['FOLLOWING'::character varying, 'PENDING'::character varying])::text[])))"
      },
      {
        "name": "nocode_application_object_follow_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (application_id, object_id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_application_object_follow_object",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_application_object_follow_object ON nocode_application_object_follow USING btree (object_id)"
      },
      {
        "name": "nocode_application_object_follow_pending_ix",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_application_object_follow_pending_ix ON nocode_application_object_follow USING btree (state) WHERE (((state)::text = 'PENDING'::text) AND (deleted = 0))"
      },
      {
        "name": "nocode_application_object_follow_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_application_object_follow_pkey ON nocode_application_object_follow USING btree (id)"
      },
      {
        "name": "nocode_application_object_follow_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_application_object_follow_uk ON nocode_application_object_follow USING btree (application_id, object_id)"
      }
    ]
  },
  {
    "table": "nocode_application_object_follow_log",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
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
        "name": "from_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "to_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "application_version_before",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "application_version_after",
        "type": "integer",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "outcome",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "pending_code",
        "type": "character varying(24)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "reason",
        "type": "character varying(1000)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "trigger_kind",
        "type": "character varying(24)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "plan_id",
        "type": "uuid",
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
    "constraints": [
      {
        "name": "nocode_application_object_follow_log_application_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (application_id) REFERENCES nocode_application(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_application_object_follow_log_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_application_object_follow_log_object_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (object_id) REFERENCES nocode_object(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_application_object_follow_log_outcome",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((outcome)::text = ANY ((ARRAY['FOLLOWED'::character varying, 'PENDING'::character varying])::text[])))"
      },
      {
        "name": "nocode_application_object_follow_log_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_application_object_follow_log_trigger",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((trigger_kind)::text = ANY ((ARRAY['OBJECT_PUBLISH'::character varying, 'SWITCH_ON'::character varying, 'MANUAL'::character varying, 'RETRY_JOB'::character varying, 'APPLICATION_PUBLISH'::character varying, 'MIGRATION'::character varying])::text[])))"
      }
    ],
    "indexes": [
      {
        "name": "nocode_application_object_follow_log_app",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_application_object_follow_log_app ON nocode_application_object_follow_log USING btree (application_id, object_id, id)"
      },
      {
        "name": "nocode_application_object_follow_log_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_application_object_follow_log_pkey ON nocode_application_object_follow_log USING btree (id)"
      },
      {
        "name": "nocode_application_object_follow_log_plan",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_application_object_follow_log_plan ON nocode_application_object_follow_log USING btree (plan_id) WHERE (plan_id IS NOT NULL)"
      }
    ]
  },
  {
    "table": "nocode_record_folder_source",
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
        "name": "sort_no",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "label",
        "type": "character varying(40)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "kind",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "placement",
        "type": "character varying(24)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "space_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "relation_field_id",
        "type": "character varying(128)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_source_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "create_mode",
        "type": "character varying(16)",
        "default": "'ON_FIRST_WRITE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "name_template",
        "type": "character varying(2000)",
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
    "constraints": [
      {
        "name": "nocode_record_folder_source_create_mode",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((create_mode)::text = ANY ((ARRAY['ON_FIRST_WRITE'::character varying, 'ON_SAVE'::character varying])::text[])))"
      },
      {
        "name": "nocode_record_folder_source_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_record_folder_source_kind",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((kind)::text = ANY ((ARRAY['FOLDER'::character varying, 'RELATION'::character varying])::text[])))"
      },
      {
        "name": "nocode_record_folder_source_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_record_folder_source_placement",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((placement)::text = ANY ((ARRAY['DIRECT'::character varying, 'RECORD_SUBFOLDER'::character varying])::text[])))"
      },
      {
        "name": "nocode_record_folder_source_shape",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((((kind)::text = 'FOLDER'::text) AND (space_id IS NOT NULL) AND (entry_id IS NOT NULL) AND ((relation_field_id)::text = ''::text) AND (target_source_id IS NULL)) OR (((kind)::text = 'RELATION'::text) AND (space_id IS NULL) AND (entry_id IS NULL) AND ((relation_field_id)::text <> ''::text) AND (target_source_id IS NOT NULL))))"
      }
    ],
    "indexes": [
      {
        "name": "nocode_record_folder_source_object_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_record_folder_source_object_idx ON nocode_record_folder_source USING btree (object_id, sort_no) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_record_folder_source_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_record_folder_source_pkey ON nocode_record_folder_source USING btree (id)"
      },
      {
        "name": "nocode_record_folder_source_target_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_record_folder_source_target_idx ON nocode_record_folder_source USING btree (target_source_id) WHERE (deleted = 0)"
      }
    ]
  },
  {
    "table": "nocode_record_folder_binding",
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
        "name": "source_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "anchor_entry_id",
        "type": "bigint",
        "default": "0",
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
        "name": "origin",
        "type": "character varying(16)",
        "default": "'AUTO'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "auto_name",
        "type": "character varying(255)",
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
    "constraints": [
      {
        "name": "nocode_record_folder_binding_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_record_folder_binding_origin",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((origin)::text = ANY ((ARRAY['AUTO'::character varying, 'MANUAL'::character varying])::text[])))"
      },
      {
        "name": "nocode_record_folder_binding_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_record_folder_binding_entry_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_record_folder_binding_entry_idx ON nocode_record_folder_binding USING btree (entry_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_record_folder_binding_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_record_folder_binding_pkey ON nocode_record_folder_binding USING btree (id)"
      },
      {
        "name": "nocode_record_folder_binding_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_record_folder_binding_uk ON nocode_record_folder_binding USING btree (object_id, record_id, source_id, anchor_entry_id) WHERE (deleted = 0)"
      }
    ]
  },
  {
    "table": "drive_entry_origin",
    "columns": [
      {
        "name": "entry_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "origin_key",
        "type": "character varying(700)",
        "default": null,
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
    "constraints": [
      {
        "name": "drive_entry_origin_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "drive_entry_origin_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (entry_id)"
      }
    ],
    "indexes": [
      {
        "name": "drive_entry_origin_key_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX drive_entry_origin_key_idx ON drive_entry_origin USING btree (origin_key) WHERE (deleted = 0)"
      },
      {
        "name": "drive_entry_origin_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX drive_entry_origin_pkey ON drive_entry_origin USING btree (entry_id)"
      }
    ]
  },
  {
    "table": "nocode_date_trigger_state",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "resource_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "armed",
        "type": "boolean",
        "default": "true",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "closed_date",
        "type": "date",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "armed_at",
        "type": "timestamp(6) without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_scan_at",
        "type": "timestamp(6) without time zone",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_scan_date",
        "type": "date",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_trigger",
        "type": "character varying(16)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_error",
        "type": "character varying(1000)",
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
    "constraints": [
      {
        "name": "nocode_date_trigger_state_application_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (application_id) REFERENCES nocode_application(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_date_trigger_state_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_date_trigger_state_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_date_trigger_state_trigger",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((last_trigger IS NULL) OR ((last_trigger)::text = ANY ((ARRAY['AUTO'::character varying, 'MANUAL'::character varying])::text[]))))"
      },
      {
        "name": "nocode_date_trigger_state_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (application_id, resource_id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_date_trigger_state_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_date_trigger_state_pkey ON nocode_date_trigger_state USING btree (id)"
      },
      {
        "name": "nocode_date_trigger_state_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_date_trigger_state_uk ON nocode_date_trigger_state USING btree (application_id, resource_id)"
      }
    ]
  },
  {
    "table": "nocode_date_trigger_done",
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
        "name": "application_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "resource_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "business_date",
        "type": "date",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_record_id",
        "type": "character varying(500)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "outcome",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_count",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "message",
        "type": "character varying(1000)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "done_at",
        "type": "timestamp(6) without time zone",
        "default": "CURRENT_TIMESTAMP",
        "notnull": true,
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
    "constraints": [
      {
        "name": "nocode_date_trigger_done_application_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (application_id) REFERENCES nocode_application(id) ON DELETE CASCADE"
      },
      {
        "name": "nocode_date_trigger_done_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_date_trigger_done_outcome",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((outcome)::text = ANY ((ARRAY['RUNNING'::character varying, 'SUCCESS'::character varying, 'UNCHANGED'::character varying, 'FAILED'::character varying])::text[])))"
      },
      {
        "name": "nocode_date_trigger_done_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_date_trigger_done_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (application_id, resource_id, business_date, source_record_id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_date_trigger_done_date",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_date_trigger_done_date ON nocode_date_trigger_done USING btree (business_date)"
      },
      {
        "name": "nocode_date_trigger_done_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_date_trigger_done_pkey ON nocode_date_trigger_done USING btree (id)"
      },
      {
        "name": "nocode_date_trigger_done_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_date_trigger_done_uk ON nocode_date_trigger_done USING btree (application_id, resource_id, business_date, source_record_id)"
      }
    ]
  },
  {
    "table": "nocode_report_dataset",
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
        "name": "name",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "description",
        "type": "character varying(1000)",
        "default": "''::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "owner_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "status",
        "type": "character varying(16)",
        "default": "'INACTIVE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "folder_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dataset_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dataset_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_dataset_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dataset_published_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((published_version > 0))"
      },
      {
        "name": "nocode_report_dataset_status_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_dataset_folder_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (folder_id) REFERENCES nocode_report_folder(id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_dataset_owner_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dataset_owner_idx ON nocode_report_dataset USING btree (owner_id, update_time DESC, id DESC) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_dataset_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_pkey ON nocode_report_dataset USING btree (id)"
      },
      {
        "name": "nocode_report_dataset_folder_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dataset_folder_idx ON nocode_report_dataset USING btree (folder_id) WHERE (deleted = 0)"
      }
    ]
  },
  {
    "table": "nocode_report_dataset_version",
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
        "name": "dataset_id",
        "type": "bigint",
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
        "name": "definition_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "reason",
        "type": "character varying(1000)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_id",
        "type": "character varying(80)",
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
    "constraints": [
      {
        "name": "nocode_report_dataset_version_dataset_id_creator_request_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dataset_id, creator, request_id)"
      },
      {
        "name": "nocode_report_dataset_version_dataset_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dataset_id) REFERENCES nocode_report_dataset(id)"
      },
      {
        "name": "nocode_report_dataset_version_dataset_id_version_no_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dataset_id, version_no)"
      },
      {
        "name": "nocode_report_dataset_version_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dataset_version_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dataset_version_version_no_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((version_no > 0))"
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_dataset_version_dataset_id_creator_request_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_version_dataset_id_creator_request_id_key ON nocode_report_dataset_version USING btree (dataset_id, creator, request_id)"
      },
      {
        "name": "nocode_report_dataset_version_dataset_id_version_no_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_version_dataset_id_version_no_key ON nocode_report_dataset_version USING btree (dataset_id, version_no)"
      },
      {
        "name": "nocode_report_dataset_version_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_version_pkey ON nocode_report_dataset_version USING btree (id)"
      }
    ]
  },
  {
    "table": "nocode_report_operation_log",
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
        "name": "resource_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "resource_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "action",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "revision",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "before_json",
        "type": "jsonb",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "after_json",
        "type": "jsonb",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "reason",
        "type": "character varying(1000)",
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
    "constraints": [
      {
        "name": "nocode_report_operation_log_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_operation_log_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_operation_log_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_operation_log_pkey ON nocode_report_operation_log USING btree (id)"
      },
      {
        "name": "nocode_report_operation_resource_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_operation_resource_idx ON nocode_report_operation_log USING btree (resource_kind, resource_id, id DESC)"
      }
    ]
  },
  {
    "table": "nocode_report_object_grant",
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
        "name": "dataset_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
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
        "name": "grant_json",
        "type": "jsonb",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "reason",
        "type": "character varying(1000)",
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
    "constraints": [
      {
        "name": "nocode_report_object_grant_dataset_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dataset_id) REFERENCES nocode_report_dataset(id)"
      },
      {
        "name": "nocode_report_object_grant_dataset_id_object_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dataset_id, object_id)"
      },
      {
        "name": "nocode_report_object_grant_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_object_grant_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_object_grant_object_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (object_id) REFERENCES nocode_object(id)"
      },
      {
        "name": "nocode_report_object_grant_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_object_grant_dataset_id_object_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_object_grant_dataset_id_object_id_key ON nocode_report_object_grant USING btree (dataset_id, object_id)"
      },
      {
        "name": "nocode_report_object_grant_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_object_grant_pkey ON nocode_report_object_grant USING btree (id)"
      }
    ]
  },
  {
    "table": "nocode_report_dataset_policy",
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
        "name": "dataset_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "members_json",
        "type": "jsonb",
        "default": "'[]'::jsonb",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
    "constraints": [
      {
        "name": "nocode_report_dataset_policy_dataset_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dataset_id) REFERENCES nocode_report_dataset(id)"
      },
      {
        "name": "nocode_report_dataset_policy_dataset_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dataset_id)"
      },
      {
        "name": "nocode_report_dataset_policy_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dataset_policy_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_dataset_policy_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_dataset_policy_dataset_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_policy_dataset_id_key ON nocode_report_dataset_policy USING btree (dataset_id)"
      },
      {
        "name": "nocode_report_dataset_policy_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dataset_policy_pkey ON nocode_report_dataset_policy USING btree (id)"
      }
    ]
  },
  {
    "table": "nocode_report_resource_acl",
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
        "name": "resource_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "resource_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "policy_json",
        "type": "jsonb",
        "default": "'[]'::jsonb",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
    "constraints": [
      {
        "name": "nocode_report_resource_acl_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_resource_acl_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_resource_acl_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_resource_acl_resource_kind_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((resource_kind)::text = ANY ((ARRAY['DATASET'::character varying, 'DASHBOARD'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_resource_acl_resource_kind_resource_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (resource_kind, resource_id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_resource_acl_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_resource_acl_pkey ON nocode_report_resource_acl USING btree (id)"
      },
      {
        "name": "nocode_report_resource_acl_resource_kind_resource_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_resource_acl_resource_kind_resource_id_key ON nocode_report_resource_acl USING btree (resource_kind, resource_id)"
      }
    ]
  },
  {
    "table": "nocode_report_dependency",
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
        "name": "source_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_stage",
        "type": "character varying(16)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "source_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "target_version",
        "type": "integer",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "field_refs_json",
        "type": "jsonb",
        "default": "'[]'::jsonb",
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
    "constraints": [
      {
        "name": "nocode_report_dependency_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((((source_stage)::text = 'DRAFT'::text) AND (source_version = 0)) OR (((source_stage)::text = 'VERSION'::text) AND (source_version > 0))))"
      },
      {
        "name": "nocode_report_dependency_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dependency_field_refs_json_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((jsonb_typeof(field_refs_json) = 'array'::text))"
      },
      {
        "name": "nocode_report_dependency_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dependency_source_kind_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((source_kind)::text = ANY ((ARRAY['DASHBOARD'::character varying, 'APPLICATION'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_dependency_source_kind_source_id_source_stage_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (source_kind, source_id, source_stage, source_version, target_kind, target_id, target_version)"
      },
      {
        "name": "nocode_report_dependency_source_stage_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((source_stage)::text = ANY ((ARRAY['DRAFT'::character varying, 'VERSION'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_dependency_source_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((source_version >= 0))"
      },
      {
        "name": "nocode_report_dependency_target_kind_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((target_kind)::text = ANY ((ARRAY['DATASET'::character varying, 'DASHBOARD'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_dependency_target_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((target_version > 0))"
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_dependency_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dependency_pkey ON nocode_report_dependency USING btree (id)"
      },
      {
        "name": "nocode_report_dependency_source_kind_source_id_source_stage_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dependency_source_kind_source_id_source_stage_key ON nocode_report_dependency USING btree (source_kind, source_id, source_stage, source_version, target_kind, target_id, target_version)"
      },
      {
        "name": "nocode_report_dependency_target_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dependency_target_idx ON nocode_report_dependency USING btree (target_kind, target_id) WHERE (deleted = 0)"
      }
    ]
  },
  {
    "table": "nocode_report_folder",
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
        "name": "resource_kind",
        "type": "character varying(32)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "parent_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "name",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "sort_no",
        "type": "integer",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
    "constraints": [
      {
        "name": "nocode_report_folder_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((parent_id IS DISTINCT FROM id))"
      },
      {
        "name": "nocode_report_folder_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_folder_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_folder_parent_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (parent_id) REFERENCES nocode_report_folder(id)"
      },
      {
        "name": "nocode_report_folder_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_folder_resource_kind_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((resource_kind)::text = ANY ((ARRAY['DATASET'::character varying, 'DASHBOARD'::character varying])::text[])))"
      },
      {
        "name": "nocode_report_folder_sort_no_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((sort_no >= 0) AND (sort_no <= 9999)))"
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_folder_parent_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_folder_parent_idx ON nocode_report_folder USING btree (resource_kind, parent_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_folder_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_folder_pkey ON nocode_report_folder USING btree (id)"
      },
      {
        "name": "nocode_report_folder_sibling_name_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_folder_sibling_name_idx ON nocode_report_folder USING btree (resource_kind, COALESCE(parent_id, (0)::bigint), lower((name)::text)) WHERE (deleted = 0)"
      }
    ]
  },
  {
    "table": "nocode_report_dashboard",
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
        "name": "name",
        "type": "character varying(80)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "owner_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "draft_checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "lock_version",
        "type": "integer",
        "default": "1",
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
        "name": "status",
        "type": "character varying(16)",
        "default": "'ACTIVE'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "folder_id",
        "type": "bigint",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      }
    ],
    "constraints": [
      {
        "name": "nocode_report_dashboard_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dashboard_lock_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((lock_version > 0))"
      },
      {
        "name": "nocode_report_dashboard_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dashboard_published_version_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((published_version > 0))"
      },
      {
        "name": "nocode_report_dashboard_folder_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (folder_id) REFERENCES nocode_report_folder(id)"
      },
      {
        "name": "nocode_report_dashboard_status_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[])))"
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_dashboard_owner_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dashboard_owner_idx ON nocode_report_dashboard USING btree (owner_id, update_time DESC, id DESC) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_dashboard_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dashboard_pkey ON nocode_report_dashboard USING btree (id)"
      },
      {
        "name": "nocode_report_dashboard_folder_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dashboard_folder_idx ON nocode_report_dashboard USING btree (folder_id) WHERE (deleted = 0)"
      },
      {
        "name": "nocode_report_dashboard_status_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_dashboard_status_idx ON nocode_report_dashboard USING btree (status, update_time DESC, id DESC) WHERE (deleted = 0)"
      }
    ]
  },
  {
    "table": "nocode_report_dashboard_version",
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
        "name": "dashboard_id",
        "type": "bigint",
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
        "name": "definition_json",
        "type": "jsonb",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "checksum",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "request_id",
        "type": "character varying(80)",
        "default": null,
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
    "constraints": [
      {
        "name": "nocode_report_dashboard_versi_dashboard_id_creator_request__key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dashboard_id, creator, request_id)"
      },
      {
        "name": "nocode_report_dashboard_version_dashboard_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dashboard_id) REFERENCES nocode_report_dashboard(id)"
      },
      {
        "name": "nocode_report_dashboard_version_dashboard_id_version_no_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (dashboard_id, version_no)"
      },
      {
        "name": "nocode_report_dashboard_version_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_dashboard_version_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_dashboard_version_version_no_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((version_no > 0))"
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_dashboard_versi_dashboard_id_creator_request__key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dashboard_versi_dashboard_id_creator_request__key ON nocode_report_dashboard_version USING btree (dashboard_id, creator, request_id)"
      },
      {
        "name": "nocode_report_dashboard_version_dashboard_id_version_no_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dashboard_version_dashboard_id_version_no_key ON nocode_report_dashboard_version USING btree (dashboard_id, version_no)"
      },
      {
        "name": "nocode_report_dashboard_version_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_dashboard_version_pkey ON nocode_report_dashboard_version USING btree (id)"
      }
    ]
  },
  {
    "table": "nocode_report_preference",
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
        "name": "dashboard_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "favorite",
        "type": "boolean",
        "default": "false",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_visited_at",
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
    "constraints": [
      {
        "name": "nocode_report_preference_dashboard_id_fkey",
        "validated": true,
        "deferrable": false,
        "definition": "FOREIGN KEY (dashboard_id) REFERENCES nocode_report_dashboard(id)"
      },
      {
        "name": "nocode_report_preference_deleted_check",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK ((deleted = ANY (ARRAY[0, 1])))"
      },
      {
        "name": "nocode_report_preference_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_report_preference_user_id_dashboard_id_key",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (user_id, dashboard_id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_report_preference_favorite_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_preference_favorite_idx ON nocode_report_preference USING btree (user_id, update_time DESC, dashboard_id DESC) WHERE ((deleted = 0) AND favorite)"
      },
      {
        "name": "nocode_report_preference_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_preference_pkey ON nocode_report_preference USING btree (id)"
      },
      {
        "name": "nocode_report_preference_recent_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_report_preference_recent_idx ON nocode_report_preference USING btree (user_id, last_visited_at DESC, dashboard_id DESC) WHERE ((deleted = 0) AND (last_visited_at IS NOT NULL))"
      },
      {
        "name": "nocode_report_preference_user_id_dashboard_id_key",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_report_preference_user_id_dashboard_id_key ON nocode_report_preference USING btree (user_id, dashboard_id)"
      }
    ]
  },
  {
    "table": "nocode_workflow_task_node",
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
        "name": "tenant_id",
        "type": "bigint",
        "default": "0",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "execution_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "process_instance_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "process_definition_id",
        "type": "character varying(128)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "node_id",
        "type": "character varying(200)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "node_name",
        "type": "character varying(255)",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "initiator_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "publisher_id",
        "type": "bigint",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "configuration_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "people_json",
        "type": "text",
        "default": null,
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "task_id",
        "type": "character varying(64)",
        "default": null,
        "notnull": false,
        "identity": "",
        "generated": ""
      },
      {
        "name": "state",
        "type": "character varying(32)",
        "default": "'CREATING'::character varying",
        "notnull": true,
        "identity": "",
        "generated": ""
      },
      {
        "name": "last_error",
        "type": "character varying(1000)",
        "default": null,
        "notnull": false,
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
        "name": "next_attempt_time",
        "type": "timestamp without time zone",
        "default": "CURRENT_TIMESTAMP",
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
        "type": "boolean",
        "default": "false",
        "notnull": true,
        "identity": "",
        "generated": ""
      }
    ],
    "constraints": [
      {
        "name": "nocode_workflow_task_node_execution_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (execution_id, process_instance_id, node_id)"
      },
      {
        "name": "nocode_workflow_task_node_pkey",
        "validated": true,
        "deferrable": false,
        "definition": "PRIMARY KEY (id)"
      },
      {
        "name": "nocode_workflow_task_node_state_ck",
        "validated": true,
        "deferrable": false,
        "definition": "CHECK (((state)::text = ANY ((ARRAY['CREATING'::character varying, 'WAITING'::character varying, 'COMPLETED'::character varying, 'INVALIDATED'::character varying])::text[])))"
      },
      {
        "name": "nocode_workflow_task_node_task_uk",
        "validated": true,
        "deferrable": false,
        "definition": "UNIQUE (task_id)"
      }
    ],
    "indexes": [
      {
        "name": "nocode_workflow_task_node_execution_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_workflow_task_node_execution_uk ON nocode_workflow_task_node USING btree (execution_id, process_instance_id, node_id)"
      },
      {
        "name": "nocode_workflow_task_node_pkey",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_workflow_task_node_pkey ON nocode_workflow_task_node USING btree (id)"
      },
      {
        "name": "nocode_workflow_task_node_process_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_workflow_task_node_process_idx ON nocode_workflow_task_node USING btree (tenant_id, process_instance_id) WHERE (deleted = false)"
      },
      {
        "name": "nocode_workflow_task_node_retry_idx",
        "ready": true,
        "valid": true,
        "definition": "CREATE INDEX nocode_workflow_task_node_retry_idx ON nocode_workflow_task_node USING btree (next_attempt_time) WHERE ((deleted = false) AND ((state)::text = ANY ((ARRAY['CREATING'::character varying, 'WAITING'::character varying])::text[])))"
      },
      {
        "name": "nocode_workflow_task_node_task_uk",
        "ready": true,
        "valid": true,
        "definition": "CREATE UNIQUE INDEX nocode_workflow_task_node_task_uk ON nocode_workflow_task_node USING btree (task_id)"
      }
    ]
  }
]$json$::jsonb);

-- 所有校验辅助对象仅存在于当前连接的临时 schema，连接结束自动消失。
CREATE OR REPLACE FUNCTION pg_temp.nocode_release_contract(expected jsonb, exact boolean DEFAULT false) RETURNS void
LANGUAGE plpgsql AS $contract$
DECLARE t jsonb; x jsonb; tab oid; final_table jsonb; alternatives jsonb;
BEGIN
 FOR t IN SELECT * FROM jsonb_array_elements(expected) LOOP
  tab:=to_regclass('public.' || (t->>'table'));
  SELECT value INTO final_table FROM nocode_release_alternatives,
       LATERAL jsonb_array_elements(contract) WHERE value->>'table'=t->>'table';
  IF tab IS NULL OR NOT EXISTS(SELECT 1 FROM pg_class WHERE oid=tab AND relkind='r'
     AND NOT relrowsecurity AND NOT relforcerowsecurity) THEN
   RAISE EXCEPTION '表 % 缺失、类型错误或存在额外行安全策略，停止升级。',t->>'table';
  END IF;
  FOR x IN SELECT * FROM jsonb_array_elements(t->'columns') LOOP
   SELECT jsonb_build_array(x)||COALESCE(jsonb_agg(value),'[]'::jsonb) INTO alternatives
       FROM jsonb_array_elements(final_table->'columns') WHERE value->>'name'=x->>'name' AND NOT exact;
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
       FROM jsonb_array_elements(final_table->'constraints') WHERE value->>'name'=x->>'name' AND NOT exact;
   IF NOT EXISTS(SELECT 1 FROM pg_constraint c CROSS JOIN LATERAL jsonb_array_elements(alternatives) candidate
       WHERE c.conrelid=tab AND c.conname=x->>'name'
       AND c.convalidated AND NOT c.condeferrable
       AND regexp_replace(pg_get_constraintdef(c.oid),'(public|pg_temp(_[0-9]+)?)\.', '', 'g')=candidate->>'definition') THEN
    RAISE EXCEPTION '约束 %.% 与原始迁移定义不一致，停止升级。',t->>'table',x->>'name';
   END IF;
  END LOOP;
  FOR x IN SELECT * FROM jsonb_array_elements(t->'indexes') LOOP
   SELECT jsonb_build_array(x)||COALESCE(jsonb_agg(value),'[]'::jsonb) INTO alternatives
       FROM jsonb_array_elements(final_table->'indexes') WHERE value->>'name'=x->>'name' AND NOT exact;
   IF NOT EXISTS(SELECT 1 FROM pg_index i JOIN pg_class n ON n.oid=i.indexrelid
       CROSS JOIN LATERAL jsonb_array_elements(alternatives) candidate
       WHERE i.indrelid=tab AND n.relname=x->>'name' AND i.indisvalid AND i.indisready
       AND regexp_replace(pg_get_indexdef(i.indexrelid),'(public|pg_temp(_[0-9]+)?)\.', '', 'g')=candidate->>'definition') THEN
    RAISE EXCEPTION '索引 %.% 与原始迁移定义不一致，停止升级。',t->>'table',x->>'name';
   END IF;
  END LOOP;
 END LOOP;
END $contract$;



-- 数据依赖回填没有结构标记：核对可推导的实际依赖，已存在时不改写审计时间。
CREATE OR REPLACE FUNCTION pg_temp.nocode_release_dependencies_present() RETURNS boolean
LANGUAGE plpgsql AS $dependencies$
BEGIN
RETURN (
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
    SELECT object_id,source_key,condition->>'fieldId' AS field_id
    FROM scopes CROSS JOIN LATERAL jsonb_array_elements(COALESCE(scope->'conditions','[]'::jsonb)) condition
), expected AS (
    SELECT object_id::bigint AS object_id,source_key,jsonb_agg(DISTINCT field_id ORDER BY field_id) AS field_ids
    FROM fields WHERE field_id IS NOT NULL GROUP BY object_id,source_key
)
SELECT NOT EXISTS (
    SELECT 1 FROM expected e WHERE NOT EXISTS (
        SELECT 1 FROM public.nocode_resource_dependency d
        WHERE d.source_kind='DATASET' AND d.source_key=e.source_key
          AND d.target_object_id=e.object_id AND d.deleted=0 AND d.field_ids_json @> e.field_ids
    )
)
);
END;
$dependencies$;

DO $release$
DECLARE
    has_history boolean := to_regclass('public.nocode_schema_history') IS NOT NULL;
    complete_history boolean := false;
    recorded boolean; p record; identity_row record; marker jsonb; marker_present boolean;
    present_count integer; max_version integer; required_name text; started_at timestamptz;
    task_folder bigint; report_folder bigint; expected_menu record;
    applied integer := 0; checked integer := 0;
BEGIN
    IF current_setting('server_version_num')::integer < 150000 THEN
        RAISE EXCEPTION '本增量要求 PostgreSQL 15 或更新版本。';
    END IF;
    PERFORM pg_advisory_xact_lock(20261009,5279);
    FOREACH required_name IN ARRAY ARRAY['system_menu','system_role_menu','sys_msg_template','infra_job',
        'system_menu_seq','system_role_menu_seq','infra_job_seq','nocode_object','nocode_application',
        'nocode_resource_dependency','nocode_document_receipt'] LOOP
        IF to_regclass('public.' || required_name) IS NULL THEN
            RAISE EXCEPTION '缺少 V051 基线对象 %，请先完成原 V038-V051 R2 增量。',required_name;
        END IF;
    END LOOP;
    IF has_history THEN
        LOCK TABLE public.nocode_schema_history IN EXCLUSIVE MODE;
        IF EXISTS (SELECT 1 FROM public.nocode_schema_history WHERE NOT success)
           OR EXISTS (SELECT 1 FROM public.nocode_schema_history WHERE version IS NOT NULL
              AND (version !~ '^[0-9]+$' OR CASE WHEN version ~ '^[0-9]+$' THEN version::numeric > 79 ELSE false END)) THEN
            RAISE EXCEPTION '迁移历史有失败项、未知版本或高于 V079，停止升级。';
        END IF;
        FOR identity_row IN SELECT * FROM nocode_release_identity LOOP
            IF (SELECT count(*) FROM public.nocode_schema_history WHERE ltrim(version,'0')=identity_row.version::text)>1
               OR EXISTS (SELECT 1 FROM public.nocode_schema_history h
                   WHERE ltrim(h.version,'0')=identity_row.version::text
                     AND (h.type='SQL' OR identity_row.version>=37)
                     AND (h.type<>'SQL' OR h.script IS DISTINCT FROM identity_row.script
                         OR h.checksum IS DISTINCT FROM identity_row.checksum)) THEN
                RAISE EXCEPTION 'V% 历史重复或文件/校验和冲突；旧分支 V066 须先人工核对，不自动 repair。',identity_row.version;
            END IF;
        END LOOP;
        SELECT max(version::integer) INTO max_version FROM public.nocode_schema_history WHERE version ~ '^[0-9]+$';
        SELECT max_version>=51 AND NOT EXISTS (
            SELECT 1 FROM generate_series(1,max_version) n WHERE NOT EXISTS (
                SELECT 1 FROM public.nocode_schema_history h WHERE ltrim(h.version,'0')=n::text AND h.type='SQL' AND h.success
            )
        ) INTO complete_history;
        IF complete_history IS DISTINCT FROM true THEN
            RAISE NOTICE '迁移历史不连续完整：保留原历史，按实际结构升级，不补造/跳号登记。';
        END IF;
    ELSE
        RAISE NOTICE '无迁移历史表：按实际结构升级，不创建历史表或伪造旧记录。';
    END IF;

    PERFORM pg_temp.nocode_release_contract((SELECT contract FROM nocode_release_baseline));
    LOCK TABLE public.system_menu, public.system_role_menu, public.sys_msg_template, public.infra_job IN SHARE ROW EXCLUSIVE MODE;
    SELECT id INTO STRICT task_folder FROM public.system_menu
      WHERE path='/task-center' AND parent_id=0 AND type=1 AND component_name='NocodeTaskFolder' AND deleted=0;
    -- 不覆盖用户自定义名称、排序、启停；只检查当前代码依赖的菜单身份与权限挂载。
    FOR expected_menu IN SELECT * FROM (VALUES
        ('/nocode-app/task-center','nocode:task:query','NocodeTaskCenter'),
        ('/nocode-app/task-center/manage','nocode:task:query','NocodeTaskManage'),
        ('/nocode-app/task-center/launch','nocode:task:create','NocodeTaskLaunch'),
        ('/nocode-app/task-center/templates','nocode:task:template','NocodeTaskTemplates')
    ) m(path,permission,component_name) LOOP
        IF (SELECT count(*) FROM public.system_menu WHERE path=expected_menu.path AND deleted=0)<>1
           OR NOT EXISTS (SELECT 1 FROM public.system_menu WHERE path=expected_menu.path AND deleted=0
             AND parent_id=task_folder AND permission=expected_menu.permission AND component_name=expected_menu.component_name) THEN
            RAISE EXCEPTION 'V051 任务菜单 % 缺失、重复或身份不一致。',expected_menu.path;
        END IF;
    END LOOP;

    FOR p IN SELECT * FROM nocode_release_plan ORDER BY version LOOP
        started_at:=clock_timestamp(); recorded:=false; present_count:=0;
        IF has_history THEN
            SELECT EXISTS (SELECT 1 FROM public.nocode_schema_history WHERE ltrim(version,'0')=p.version::text) INTO recorded;
        END IF;
        FOR marker IN SELECT * FROM jsonb_array_elements(p.markers) LOOP
            marker_present:=false;
            IF marker ? 'relation' THEN
                marker_present:=to_regclass('public.' || (marker->>'relation')) IS NOT NULL;
            ELSIF marker ? 'column' OR marker ? 'nullable' THEN
                SELECT EXISTS (SELECT 1 FROM pg_attribute
                  WHERE attrelid=to_regclass('public.' || (marker->>'table'))
                    AND attname=COALESCE(marker->>'column',marker->>'nullable') AND attnum>0 AND NOT attisdropped
                    AND (NOT (marker ? 'nullable') OR NOT attnotnull)) INTO marker_present;
            ELSIF marker ? 'constraint' THEN
                SELECT EXISTS (SELECT 1 FROM pg_constraint
                  WHERE conrelid=to_regclass('public.' || (marker->>'table')) AND conname=marker->>'constraint'
                    AND convalidated AND position(marker->>'token' IN pg_get_constraintdef(oid))>0) INTO marker_present;
            ELSIF marker ? 'menu' THEN
                SELECT EXISTS (SELECT 1 FROM public.system_menu WHERE path=marker->>'menu' AND deleted=0) INTO marker_present;
            ELSIF marker ? 'permission' THEN
                SELECT EXISTS (SELECT 1 FROM public.system_menu WHERE permission=marker->>'permission' AND deleted=0) INTO marker_present;
            ELSIF marker ? 'navigation' THEN
                SELECT EXISTS (SELECT 1 FROM public.system_menu WHERE path=marker->>'navigation' AND type=3 AND NOT visible AND deleted=0) INTO marker_present;
            ELSIF marker ? 'message' THEN
                SELECT EXISTS (SELECT 1 FROM public.sys_msg_template WHERE code=marker->>'message' AND deleted=0) INTO marker_present;
            ELSIF marker ? 'job' THEN
                SELECT EXISTS (SELECT 1 FROM public.infra_job WHERE handler_name=marker->>'job' AND deleted=0) INTO marker_present;
            ELSIF marker ? 'dependencies' THEN
                marker_present:=pg_temp.nocode_release_dependencies_present();
            END IF;
            IF marker_present THEN present_count:=present_count+1; END IF;
        END LOOP;
        IF present_count<>0 AND present_count<>jsonb_array_length(p.markers) THEN
            RAISE EXCEPTION 'V% 结构/必要配置仅存在一部分（%/%），停止并保留现场。',p.version,present_count,jsonb_array_length(p.markers);
        END IF;
        IF recorded AND present_count=0 THEN
            RAISE EXCEPTION 'V% 已登记但结构/必要配置缺失，停止，不重建空表掩盖数据丢失。',p.version;
        END IF;
        IF present_count=0 THEN
            EXECUTE p.body;
            applied:=applied+1;
            RAISE NOTICE 'V%：已执行原始增量。',p.version;
        ELSE
            checked:=checked+1;
            RAISE NOTICE 'V%：已存在或无待回填数据，核验并保留原值。',p.version;
        END IF;
        PERFORM pg_temp.nocode_release_contract(p.contract);
        IF p.version IN (54,60) AND
          (SELECT count(*) FROM public.sys_msg_template WHERE deleted=0 AND code=CASE p.version
            WHEN 54 THEN 'nocode-task-assignment' ELSE 'nocode-task-acceptance' END)<>1 THEN
            RAISE EXCEPTION 'V% 任务消息模板缺失或重复。',p.version;
        END IF;
        IF p.version=63 AND
          (SELECT count(*) FROM public.infra_job WHERE handler_name='applicationFollowRetryJob' AND deleted=0)<>1 THEN
            RAISE EXCEPTION '自动跟随重试任务缺失或重复。';
        END IF;
        IF p.version=68 AND NOT pg_temp.nocode_release_dependencies_present() THEN
            RAISE EXCEPTION 'V068 报表条件依赖回填未完成。';
        END IF;
        IF has_history AND complete_history AND NOT recorded THEN
            SELECT * INTO STRICT identity_row FROM nocode_release_identity WHERE version=p.version;
            INSERT INTO public.nocode_schema_history
                (installed_rank,version,description,type,script,checksum,installed_by,installed_on,execution_time,success)
            SELECT COALESCE(max(installed_rank),0)+1,lpad(p.version::text,3,'0'),identity_row.description,'SQL',
              identity_row.script,identity_row.checksum,current_user,localtimestamp,
              (extract(epoch FROM clock_timestamp()-started_at)*1000)::integer,true FROM public.nocode_schema_history;
        END IF;
    END LOOP;

    PERFORM pg_temp.nocode_release_contract((SELECT contract FROM nocode_release_final), true);
    SELECT id INTO STRICT report_folder FROM public.system_menu WHERE path='/nocode/report-center' AND type=1 AND parent_id=0 AND deleted=0;
    FOR expected_menu IN SELECT * FROM (VALUES
        ('/nocode/report-center/datasets','nocode:report:query','nocode/report-center/datasets'),
        ('/nocode/report-center/data-authorization','nocode:object:share','nocode/report-center/data-authorization'),
        ('/nocode/report-center/dashboards','nocode:report:query','nocode/report-center/dashboards'),
        ('/nocode/report-center/home','nocode:report:query','nocode/report-center/home')
    ) m(path,permission,component) LOOP
        IF (SELECT count(*) FROM public.system_menu WHERE path=expected_menu.path AND deleted=0)<>1
           OR NOT EXISTS (SELECT 1 FROM public.system_menu WHERE path=expected_menu.path AND deleted=0 AND type=2
              AND parent_id=report_folder AND permission=expected_menu.permission AND component=expected_menu.component) THEN
            RAISE EXCEPTION '报表菜单 % 缺失、重复或挂载错误。',expected_menu.path;
        END IF;
    END LOOP;
    FOREACH required_name IN ARRAY ARRAY['nocode:report:create','nocode:report:update','nocode:report:publish','nocode:report:manage','nocode:report:authorize'] LOOP
        IF (SELECT count(*) FROM public.system_menu WHERE permission=required_name AND deleted=0)<>1
           OR NOT EXISTS (SELECT 1 FROM public.system_menu WHERE permission=required_name AND deleted=0 AND type=3
              AND parent_id IN (SELECT id FROM public.system_menu WHERE path='/nocode/report-center/datasets' AND deleted=0)) THEN
            RAISE EXCEPTION '报表权限 % 缺失、重复或挂载错误。',required_name;
        END IF;
    END LOOP;
    IF (SELECT count(*) FROM public.system_menu WHERE path='/nocode-app/task-center/efficiency' AND deleted=0)<>1
       OR NOT EXISTS (SELECT 1 FROM public.system_menu WHERE path='/nocode-app/task-center/efficiency' AND deleted=0
          AND type=2 AND parent_id=task_folder AND permission='nocode:task:query' AND component='nocode/task-center/TaskEfficiency') THEN
        RAISE EXCEPTION '任务能效统计菜单缺失、重复或挂载错误。';
    END IF;
    RAISE NOTICE 'V052-V079 全部核验通过；执行 % 个版本，保留/核验 % 个版本。',applied,checked;
    RAISE NOTICE '最终提交以 run-sql.sh 的“执行成功，事务已提交”及退出码 0 为准。';
END $release$;

SELECT 'V079' AS target_version, 'V052-V079 全部核验通过' AS result,
       to_regclass('public.nocode_workflow_task_node') AS workflow_task_nodes,
       (SELECT count(*) FROM public.system_menu WHERE path='/nocode/report-center' AND deleted=0) AS report_center_entries,
       (SELECT count(*) FROM pg_attribute WHERE attrelid='public.nocode_task_entry_record'::regclass
         AND attname='work_rule_json' AND NOT attisdropped) AS historical_work_rule_column;
