import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.tools.NocodeToolContext;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.filesystem.FileSystemResource;
import org.springframework.context.ConfigurableApplicationContext;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** 手工增量的真实 PostgreSQL 验证入口；仅使用当前开发库的会话临时表，不访问线上。 */
public class ManualUpgradeVerification {
    static final Path ROOT = Path.of("sql");
    static final ObjectMapper JSON = new ObjectMapper();
    static final List<String> TABLES = List.of("drive_storage_setting", "nocode_ordered_calculation_state",
            "nocode_task_instance", "nocode_task_template", "nocode_task_template_version", "nocode_task_plan",
            "nocode_task_comment", "nocode_task_event", "nocode_task_record_link", "nocode_task_entry_binding",
            "nocode_task_entry_record", "nocode_task_entry_template_version", "drive_entry", "drive_space", "infra_job",
            "nocode_biz_directory_binding", "nocode_biz_attachment_binding", "nocode_biz_upload_session",
            "nocode_biz_file_retention", "nocode_biz_file_task", "nocode_biz_file_mark");
    static Map<Integer, Map<String, Object>> migrations = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.list(ROOT.resolve("postgresql/migrations"))) {
            for (Path path : files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql")).sorted().toList()) {
                String name = path.getFileName().toString();
                int version = Integer.parseInt(name.substring(1, name.indexOf("__")));
                int checksum = ChecksumCalculator.calculate(new FileSystemResource(new Location("filesystem:" + path.getParent()),
                        path.toAbsolutePath().toString(), StandardCharsets.UTF_8, false));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("version", version); item.put("script", name); item.put("checksum", checksum);
                item.put("description", name.substring(name.indexOf("__") + 2, name.length() - 4).replace('_', ' '));
                item.put("sql", Files.readString(path));
                if (migrations.put(version, item) != null) throw new IllegalStateException("重复迁移版本："+version);
            }
        }
        try (ConfigurableApplicationContext context = NocodeToolContext.open()) {
            DataSource ds = context.getBean(DataSource.class);
            if (args[0].equals("catalog")) {
                try (Connection c = ds.getConnection()) {
                    fixture(c, 37, true);
                    Map<String, Object> catalog = new LinkedHashMap<>();
                    catalog.put("migrations", migrations.values());
                    Map<Integer, Object> stages = new LinkedHashMap<>();
                    for (int version = 38; version <= 51; version++) {
                        exec(c, temporary((String) migrations.get(version).get("sql")));
                        stages.put(version, snapshot(c));
                    }
                    catalog.put("stages", stages);
                    Files.writeString(Path.of(args[1]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(catalog));
                    reset(c);
                    System.out.println("CATALOG PASS: original V038-V051 executed on development PostgreSQL temporary fixtures");
                }
            } else {
                verify(ds, Files.readString(Path.of(args[1])));
            }
        }
    }

    static void exec(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement()) { s.execute(sql); }
    }
    static String temporary(String sql) {
        return sql.replace("public.", "pg_temp.").replace("CREATE TABLE pg_temp.", "CREATE TEMP TABLE pg_temp.")
                .replace("CREATE SEQUENCE pg_temp.", "CREATE TEMP SEQUENCE pg_temp.");
    }
    static void reset(Connection c) throws Exception {
        if (!c.getAutoCommit()) { c.rollback(); c.setAutoCommit(true); }
        exec(c, "DISCARD TEMP");
    }
    static void fixture(Connection c, int version, boolean history) throws Exception {
        reset(c);
        // LIKE 仅读取底座表定义；显式序列保证夹具不消耗或修改 public 序列。
        for (String table : List.of("system_menu", "system_role_menu", "sys_msg_template", "drive_entry", "drive_space", "infra_job"))
            exec(c, "CREATE TEMP TABLE " + table + " (LIKE public." + table + " INCLUDING DEFAULTS INCLUDING CONSTRAINTS INCLUDING INDEXES)");
        exec(c, """
                CREATE TEMP SEQUENCE system_menu_seq START 2000;
                CREATE TEMP SEQUENCE system_role_menu_seq START 2000;
                CREATE TEMP SEQUENCE infra_job_seq START 2000;
                ALTER TABLE pg_temp.drive_entry DROP COLUMN IF EXISTS managed_biz;
                ALTER TABLE pg_temp.drive_space ALTER COLUMN owner_id SET NOT NULL;
                ALTER TABLE pg_temp.infra_job DROP COLUMN IF EXISTS powerjob_job_id;
                CREATE TEMP TABLE nocode_object (id bigint PRIMARY KEY, marker text);
                CREATE TEMP TABLE nocode_application (id bigint PRIMARY KEY, marker text);
                CREATE TEMP TABLE infra_file_config (id bigint PRIMARY KEY, marker text);
                CREATE TEMP TABLE nocode_document_receipt (application_id bigint, object_id bigint,
                    creator varchar(64), operation varchar(64), request_key varchar(120));
                CREATE UNIQUE INDEX nocode_document_receipt_maintenance_request
                    ON nocode_document_receipt (creator, object_id, operation, request_key) WHERE application_id IS NULL;
                INSERT INTO nocode_object VALUES (901,'existing-object');
                INSERT INTO nocode_application VALUES (902,'existing-application');
                INSERT INTO infra_file_config VALUES (903,'existing-file-setting');
                INSERT INTO nocode_document_receipt VALUES (NULL,901,'1','SAVE','existing-receipt');
                INSERT INTO pg_temp.drive_space(id,name,type,owner_id) VALUES(990,'原个人空间','PERSONAL',701);
                INSERT INTO pg_temp.drive_entry(id,space_id,name,type,file_id,size,mime_type)
                    VALUES(991,990,'原文件.pdf','FILE',9001,128,'application/pdf'),
                          (992,990,'原业务文件.pdf','FILE',9002,256,'application/pdf');
                INSERT INTO system_menu (id,name,type,parent_id,path,component,component_name) VALUES
                    (1,'工作台',1,0,'/dashboard',NULL,NULL),
                    (2,'任务中心',2,1,'/nocode-app/task-center','nocode/task-center/index','NocodeTaskCenter'),
                    (3,'网盘管理',2,4,'/drive/space','drive/space','DriveSpace'),
                    (4,'网盘',1,0,'/drive',NULL,'Drive');
                INSERT INTO system_role_menu (id,role_id,menu_id,tenant_id) VALUES (1,701,2,0),(2,702,3,0);
                """);
        if (history) {
            exec(c, "CREATE TEMP TABLE nocode_schema_history (LIKE public.nocode_schema_history INCLUDING ALL)");
            for (int i = 1; i <= 37; i++) record(c, i);
        }
        for (int i = 38; i <= version; i++) {
            exec(c, temporary((String) migrations.get(i).get("sql")));
            if (history) record(c, i);
            if (i == 38) exec(c, "UPDATE pg_temp.drive_storage_setting SET config_id=903 WHERE id=1");
            if (i == 39) exec(c, "INSERT INTO pg_temp.nocode_ordered_calculation_state(object_id,field_id,signature,state,total_rows,updated_rows) VALUES(901,904,'saved-progress','BACKFILLING',100,23)");
            if (i == 40) seedTasks(c);
            if (i == 47) {
                exec(c,"""
                        UPDATE pg_temp.drive_entry SET managed_biz=true WHERE id=992;
                        INSERT INTO pg_temp.nocode_biz_attachment_binding(object_id,record_id,field_id,file_id,entry_id,space_id)
                            VALUES('901','saved-business-record','attachment',9002,992,990);
                        INSERT INTO pg_temp.nocode_biz_upload_session(session_key,user_id,object_id,field_id,file_id,file_name,expires_at)
                            VALUES('saved-session',701,'901','attachment',9003,'待提交.pdf',now()+interval '1 day');
                        INSERT INTO pg_temp.nocode_biz_file_retention(file_id,holder_type,holder_id,object_id)
                            VALUES(9002,'RECORD_HISTORY','saved-history','901');
                        """);
            }
        }
    }
    static void seedTasks(Connection c) throws Exception {
        exec(c, """
                INSERT INTO pg_temp.nocode_task_instance(id,root_id,title,assignee_id,status,config_json,t0,creator,updater)
                  VALUES('existing-task','existing-task','原任务',701,'RUNNING','{"predecessorIds":["prior"]}',now(),'701','701');
                INSERT INTO pg_temp.nocode_task_plan(id,task_id,user_id,period,plan_date,creator,updater)
                  VALUES('existing-plan','existing-task',701,'DAY',current_date,'701','701');
                INSERT INTO pg_temp.nocode_task_template(id,name,nodes_json,creator,updater)
                  VALUES('existing-template','原模板','[{"schedule":{"mode":"PREDECESSOR"}}]','701','701');
                INSERT INTO pg_temp.nocode_task_template_version(id,template_id,version_no,name,nodes_json,bindings_json,creator,updater)
                  VALUES('existing-template-v1','existing-template',1,'原模板','[{"schedule":{"mode":"PREDECESSOR"}}]','{}','701','701');
                """);
    }
    static void record(Connection c, int version) throws Exception {
        Map<String, Object> m = migrations.get(version);
        try (PreparedStatement s = c.prepareStatement("INSERT INTO pg_temp.nocode_schema_history(installed_rank,version,description,type,script,checksum,installed_by,execution_time,success) VALUES (?,?,?,'SQL',?,?,'fixture',0,true)")) {
            s.setInt(1,version); s.setString(2,String.format("%03d",version)); s.setString(3,(String)m.get("description"));
            s.setString(4,(String)m.get("script")); s.setInt(5,(Integer)m.get("checksum")); s.executeUpdate();
        }
    }
    static List<JsonNode> snapshot(Connection c) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        String query = Files.readString(ROOT.resolve("verification/table-contract.sql"));
        for (String table : TABLES) {
            try (PreparedStatement s = c.prepareStatement(query)) {
                s.setString(1, "pg_temp." + table);
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next()) continue;
                    String value = rs.getString(1);
                    if (value != null) result.add(JSON.readTree(value));
                }
            }
        }
        return result;
    }
    static long number(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) { rs.next(); return rs.getLong(1); }
    }
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static void verify(DataSource ds, String sql) throws Exception {
        String release = temporary(sql);
        int passed = 0;
        try (Connection c = ds.getConnection()) {
            // 保留交付 SQL 的 public 引用：仅创建会话辅助对象并核对现有 V038/V039，绝不执行迁移正文。
            // 此测试专门覆盖临时夹具与正式 public 命名空间的差异，全部变更（只有临时对象）回滚。
            reset(c);
            c.setAutoCommit(false);
            try {
                exec(c,sql.substring(0,sql.indexOf("DO $release$")));
                exec(c,"SELECT pg_temp.nocode_release_contract(contract) FROM nocode_release_plan WHERE version IN (38,39)");
                c.rollback();
                System.out.println("PASS unchanged-delivery-contract-on-real-public-V038-V039"); passed++;
            } finally { c.rollback(); c.setAutoCommit(true); }
            for (int start : List.of(37,38,39,40,41,42,43,44,45,46,47,48,49,50,51)) {
                fixture(c, start, true);
                apply(c, release, null);
                assertResult(c, start, true);
                String fingerprint = contentFingerprint(c);
                apply(c, release, null);
                assertResult(c, start, true);
                require(fingerprint.equals(contentFingerprint(c)), "重复执行更改了已有行，起点 V"+start);
                System.out.println("PASS upgrade-and-rerun-from-V"+start); passed++;
            }
            for (int start : List.of(37,39,46,51)) {
                fixture(c, start, false);
                apply(c, release, null);
                assertResult(c, start, false);
                String fingerprint = contentFingerprint(c);
                apply(c, release, null);
                require(fingerprint.equals(contentFingerprint(c)), "无历史重复执行更改了已有行");
                System.out.println("PASS no-history-from-V"+start); passed++;
            }
            fixture(c,39,true);
            exec(c,"DELETE FROM pg_temp.nocode_schema_history WHERE version='025'");
            apply(c,release,null);
            require(number(c,"SELECT count(*) FROM pg_temp.nocode_schema_history")==38,"不应补造有缺项的历史");
            System.out.println("PASS history-gap-preserved"); passed++;
            // 不同版本身份、重复记录及失败记录必须在增量执行前拒绝。
            for (String corruption : List.of(
                    "UPDATE pg_temp.nocode_schema_history SET checksum=0 WHERE version='037'",
                    "UPDATE pg_temp.nocode_schema_history SET checksum=0 WHERE version='039'",
                    "UPDATE pg_temp.nocode_schema_history SET success=false WHERE version='039'",
                    "UPDATE pg_temp.nocode_schema_history SET version='052' WHERE version='039'",
                    "UPDATE pg_temp.nocode_schema_history SET version='038' WHERE version='039'")) {
                fixture(c,39,true); exec(c,corruption);
                String before = contentFingerprint(c);
                apply(c,release,"历史");
                require(before.equals(contentFingerprint(c)),"历史失败不应写数据");
                System.out.println("PASS reject-history-corruption-"+passed); passed++;
            }
            fixture(c,39,true);
            exec(c,"DROP INDEX pg_temp.nocode_document_receipt_maintenance_request");
            apply(c,release,"V037");
            System.out.println("PASS reject-missing-baseline"); passed++;
            fixture(c,39,true);
            exec(c,"DROP TABLE pg_temp.nocode_ordered_calculation_state");
            apply(c,release,"已登记");
            require(number(c,"SELECT count(*) FROM pg_class WHERE relnamespace=pg_my_temp_schema() AND relname='nocode_task_instance'")==0,"不应创建任务表");
            System.out.println("PASS reject-recorded-table-missing"); passed++;
            fixture(c,40,true);
            exec(c,"DROP TABLE pg_temp.nocode_task_comment");
            apply(c,release,"仅存在一部分");
            System.out.println("PASS reject-partial-task-schema"); passed++;
            fixture(c,39,true);
            exec(c,"UPDATE pg_temp.system_menu SET path='/drive-lost' WHERE path='/drive'");
            String beforeLateFailure = contentFingerprint(c);
            apply(c,release,"Drive entry menu missing");
            require(beforeLateFailure.equals(contentFingerprint(c)),"V050 中途失败未回滚原始数据");
            require(number(c,"SELECT count(*) FROM pg_class WHERE relnamespace=pg_my_temp_schema() AND relname='nocode_task_instance'")==0,"V050 中途失败应回滚 V040 DDL");
            System.out.println("PASS late-failure-rolls-back-DDL-and-data-and-history"); passed++;
            fixture(c,46,true);
            exec(c,"ALTER TABLE pg_temp.nocode_task_plan ALTER COLUMN source TYPE text");
            apply(c,release,"列");
            System.out.println("PASS reject-wrong-column-type"); passed++;
            fixture(c,46,true);
            exec(c,"DROP INDEX pg_temp.nocode_task_entry_record_task_idx; CREATE INDEX nocode_task_entry_record_task_idx ON pg_temp.nocode_task_entry_record(task_id)");
            apply(c,release,"索引");
            System.out.println("PASS reject-wrong-index-predicate"); passed++;
            fixture(c,51,true);
            exec(c,"ALTER TABLE pg_temp.nocode_biz_upload_session DROP CONSTRAINT nocode_biz_upload_session_state_ck");
            apply(c,release,"约束");
            System.out.println("PASS reject-biz-file-constraint-drift"); passed++;
            reset(c);
        }
        System.out.println("VERIFICATION PASS: "+passed+" real PostgreSQL cases; temporary fixtures only");
    }
    static void apply(Connection c,String sql,String expectedFailure) throws Exception {
        c.setAutoCommit(false);
        try {
            exec(c,sql);
            if(expectedFailure!=null) throw new AssertionError("预期拒绝，但执行成功："+expectedFailure);
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            if(expectedFailure==null || !e.getMessage().contains(expectedFailure)) throw e;
        } finally {
            if(!c.getAutoCommit()) { c.rollback(); c.setAutoCommit(true); }
        }
    }
    static String contentFingerprint(Connection c) throws Exception {
        List<String> rows=new ArrayList<>();
        for(String table : List.of("system_menu","system_role_menu","sys_msg_template","nocode_schema_history",
                "nocode_object","nocode_application","nocode_document_receipt","drive_storage_setting",
                "nocode_ordered_calculation_state","nocode_task_instance","nocode_task_plan",
                "nocode_task_template","nocode_task_template_version","drive_entry","drive_space","infra_job",
                "nocode_biz_attachment_binding","nocode_biz_upload_session","nocode_biz_file_retention")) {
            if(number(c,"SELECT count(*) FROM pg_class WHERE relnamespace=pg_my_temp_schema() AND relname='"+table+"'")==0) continue;
            try(Statement s=c.createStatement(); ResultSet rs=s.executeQuery("SELECT row_to_json(t)::text FROM pg_temp."+table+" t ORDER BY row_to_json(t)::text")) {
                while(rs.next()) rows.add(table+":"+rs.getString(1));
            }
        }
        return String.join("\n",rows);
    }
    static void assertResult(Connection c,int start,boolean history) throws Exception {
        require(number(c,"SELECT count(*) FROM pg_temp.nocode_object WHERE id=901 AND marker='existing-object'")==1,"已有对象被改写");
        require(number(c,"SELECT count(*) FROM pg_temp.nocode_document_receipt WHERE request_key='existing-receipt'")==1,"原收据丢失");
        require(number(c,"SELECT count(*) FROM pg_temp.system_role_menu WHERE id IN (1,2)")==2,"原授权丢失");
        require(number(c,"SELECT count(*) FROM pg_temp.system_role_menu r JOIN pg_temp.system_menu m ON m.id=r.menu_id WHERE m.permission IN ('nocode:task:manage-all','drive:storage:update','drive:storage:query','drive:business:query')")==0,"不应扩大敏感权限");
        require(number(c,"SELECT count(*) FROM pg_temp.system_menu WHERE path='/task-center' AND parent_id=0 AND deleted=0")==1,"任务中心入口异常");
        require(number(c,"SELECT count(*) FROM pg_temp.system_menu WHERE path='/drive/business' AND permission='drive:business:query' AND deleted=0")==1,"业务文件入口异常");
        require(number(c,"SELECT count(*) FROM pg_temp.infra_job WHERE handler_name='bizUploadCleanJob' AND deleted=0")==1,"业务清理任务重复或缺失");
        require(number(c,"SELECT count(*) FROM pg_temp.drive_entry WHERE id=991 AND name='原文件.pdf' AND file_id=9001 AND NOT managed_biz")==1,"已有普通文件被覆盖或错误纳管");
        require(number(c,"SELECT owner_id FROM pg_temp.drive_space WHERE id=990")==701,"已有空间归属被覆盖");
        if(history) require(number(c,"SELECT count(*) FROM pg_temp.nocode_schema_history")==51,"历史不是51项");
        else require(number(c,"SELECT count(*) FROM pg_class WHERE relnamespace=pg_my_temp_schema() AND relname='nocode_schema_history'")==0,"不应创建历史");
        if(start>=38) require(number(c,"SELECT config_id FROM pg_temp.drive_storage_setting WHERE id=1")==903,"存储配置被覆盖");
        if(start>=39) require(number(c,"SELECT updated_rows FROM pg_temp.nocode_ordered_calculation_state WHERE object_id=901")==23,"校准进度被覆盖");
        if(start>=40) {
            require(number(c,"SELECT count(*) FROM pg_temp.nocode_task_instance WHERE id='existing-task' AND kind='PROCESS' AND status='RUNNING'")==1,"原任务或类型回填异常");
            require(number(c,"SELECT arranged_by_id FROM pg_temp.nocode_task_plan WHERE id='existing-plan'")==701,"原计划来源回填异常");
        }
        if(start>=47) {
            require(number(c,"SELECT count(*) FROM pg_temp.nocode_biz_attachment_binding WHERE file_id=9002 AND record_id='saved-business-record' AND file_name='原业务文件.pdf' AND file_size=256")==1,"业务附件绑定或信息回填异常");
            require(number(c,"SELECT count(*) FROM pg_temp.nocode_biz_upload_session WHERE session_key='saved-session' AND state='TEMPORARY'")==1,"上传会话被覆盖");
            require(number(c,"SELECT count(*) FROM pg_temp.nocode_biz_file_retention WHERE holder_id='saved-history'")==1,"文件保留引用丢失");
        }
    }
}
