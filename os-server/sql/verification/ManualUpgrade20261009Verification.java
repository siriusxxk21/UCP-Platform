import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.nocode.tools.NocodeToolContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.filesystem.FileSystemResource;
import org.springframework.context.ConfigurableApplicationContext;

/** V051 至 V079 手工发布验证；复用 Spring 配置，仅操作开发库连接内的临时表。 */
public class ManualUpgrade20261009Verification extends ManualUpgradeVerification {
    static final int TARGET = 79;
    static final Set<String> RELEASE_TABLES = new LinkedHashSet<>(TABLES);

    public static void main(String[] args) throws Exception {
        try (java.util.stream.Stream<Path> files = Files.list(ROOT.resolve("postgresql/migrations"))) {
            for (Path path : files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql")).sorted().toList()) {
                String name = path.getFileName().toString();
                int version = Integer.parseInt(name.substring(1, name.indexOf("__")));
                int checksum = ChecksumCalculator.calculate(new FileSystemResource(new Location("filesystem:" + path.getParent()),
                        path.toAbsolutePath().toString(), StandardCharsets.UTF_8, false));
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("version", version);
                item.put("script", name);
                item.put("checksum", checksum);
                item.put("description", name.substring(name.indexOf("__") + 2, name.length() - 4).replace('_', ' '));
                String sql = Files.readString(path);
                item.put("sql", sql);
                migrations.put(version, item);
                if (version >= 52) {
                    java.util.regex.Matcher tables = java.util.regex.Pattern.compile("CREATE TABLE public\\.(\\w+)").matcher(sql);
                    while (tables.find()) RELEASE_TABLES.add(tables.group(1));
                }
            }
        }
        RELEASE_TABLES.add("nocode_resource_dependency");
        try (ConfigurableApplicationContext context = NocodeToolContext.open()) {
            DataSource ds = context.getBean(DataSource.class);
            if (args[0].equals("catalog")) {
                try (Connection c = ds.getConnection()) {
                    releaseFixture(c, 51, true);
                    Map<String, Object> catalog = new LinkedHashMap<>();
                    catalog.put("migrations", migrations.values());
                    Map<Integer, Object> stages = new LinkedHashMap<>();
                    stages.put(51, releaseSnapshot(c));
                    for (int version = 52; version <= TARGET; version++) {
                        exec(c, temporary((String) migrations.get(version).get("sql")));
                        stages.put(version, releaseSnapshot(c));
                    }
                    catalog.put("stages", stages);
                    Files.createDirectories(Path.of(args[1]).getParent());
                    Files.writeString(Path.of(args[1]), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(catalog));
                    reset(c);
                    System.out.println("CATALOG PASS: original V052-V079 on PostgreSQL temporary fixtures");
                }
            } else {
                verifyRelease(ds, Files.readString(Path.of(args[1])));
            }
        }
    }

    static void releaseFixture(Connection c, int version, boolean history) throws Exception {
        fixture(c, 51, history);
        // INCLUDING IDENTITY 建立临时表自身序列，避免消耗正式库序列。
        exec(c, "CREATE TEMP TABLE nocode_resource_dependency (LIKE public.nocode_resource_dependency INCLUDING ALL)");
        exec(c, """
                UPDATE pg_temp.nocode_task_template SET published_version=3 WHERE id='existing-template';
                INSERT INTO pg_temp.nocode_task_entry_binding
                    (id,task_id,entry_key,dataset_id,config_json,business_json,creator,updater)
                VALUES ('old-binding','existing-task','form','dataset',
                    '{"workRule":{"mode":"RECORD_ONCE","minutes":30}}','{}','701','701');
                INSERT INTO pg_temp.nocode_task_entry_record
                    (id,task_id,entry_key,dataset_id,business_json,operation,request_key,request_hash,creator,updater)
                VALUES ('old-work','existing-task','form','dataset','{}','CREATED','old-save','hash','701','701');
                """);
        for (int i = 52; i <= version; i++) {
            exec(c, temporary((String) migrations.get(i).get("sql")));
            if (history) record(c, i);
            if (i == 67) seedReport(c);
        }
    }

    static void seedReport(Connection c) throws Exception {
        exec(c, """
                INSERT INTO pg_temp.nocode_report_dataset(id,name,owner_id,draft_json,draft_checksum)
                    VALUES(3003,'原报表',701,'{}','original-checksum');
                INSERT INTO pg_temp.nocode_report_object_grant(dataset_id,object_id,grant_json)
                    VALUES(3003,901,'{"actionScopes":{"UPDATE":{"conditions":[{"fieldId":"10001"}]}}}');
                """);
    }

    static List<JsonNode> releaseSnapshot(Connection c) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        String query = Files.readString(ROOT.resolve("verification/table-contract.sql"));
        for (String table : RELEASE_TABLES) {
            try (PreparedStatement s = c.prepareStatement(query)) {
                s.setString(1, "pg_temp." + table);
                try (ResultSet rs = s.executeQuery()) {
                    if (rs.next() && rs.getString(1) != null) result.add(JSON.readTree(rs.getString(1)));
                }
            }
        }
        return result;
    }

    static String releaseFingerprint(Connection c) throws Exception {
        List<String> rows = new ArrayList<>();
        Set<String> tables = new LinkedHashSet<>(RELEASE_TABLES);
        tables.addAll(List.of("system_menu", "system_role_menu", "sys_msg_template", "nocode_schema_history",
                "nocode_object", "nocode_application", "nocode_document_receipt"));
        for (String table : tables) {
            if (number(c, "SELECT count(*) FROM pg_class WHERE relnamespace=pg_my_temp_schema() AND relname='" + table + "'") == 0) continue;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                    "SELECT row_to_json(t)::text FROM pg_temp." + table + " t ORDER BY row_to_json(t)::text")) {
                while (rs.next()) rows.add(table + ":" + rs.getString(1));
            }
        }
        return String.join("\n", rows);
    }

    static void assertRelease(Connection c, int start, boolean history) throws Exception {
        require(number(c, "SELECT count(*) FROM pg_temp.nocode_task_instance WHERE id='existing-task' AND status='RUNNING' AND assignee_id=701") == 1, "原任务被改写");
        require(number(c, "SELECT count(*) FROM pg_temp.nocode_task_entry_record WHERE id='old-work' AND operation='CREATED' AND work_rule_json::jsonb->>'minutes'='30'") == 1, "历史工时规则留底错误");
        require(number(c, "SELECT primary_version FROM pg_temp.nocode_task_template WHERE id='existing-template'") == 3, "主版本回填错误");
        require(number(c, "SELECT count(*) FROM pg_temp.nocode_task_plan WHERE id='existing-plan' AND plan_mode='SCHEDULE' AND end_date=plan_date") == 1, "旧计划被转换或丢失");
        require(number(c, "SELECT count(*) FROM pg_temp.nocode_document_receipt WHERE request_key='existing-receipt'") == 1, "旧收据丢失");
        require(number(c, "SELECT config_id FROM pg_temp.drive_storage_setting WHERE id=1") == 903, "网盘配置被覆盖");
        require(number(c, "SELECT count(*) FROM pg_temp.system_role_menu r JOIN pg_temp.system_menu m ON m.id=r.menu_id WHERE m.permission='nocode:task:manage-all' OR m.permission LIKE 'nocode:report:%'") == 0, "新增敏感角色授权");
        if (history) require(number(c, "SELECT count(*) FROM pg_temp.nocode_schema_history") == TARGET, "迁移历史数错误");
        else require(number(c, "SELECT count(*) FROM pg_class WHERE relnamespace=pg_my_temp_schema() AND relname='nocode_schema_history'") == 0, "不应创建历史表");
        if (start >= 67) require(number(c, "SELECT count(*) FROM pg_temp.nocode_resource_dependency WHERE source_kind='DATASET' AND source_key='3003:ceiling:901' AND field_ids_json='[\"10001\"]'::jsonb AND deleted=0") == 1, "条件依赖漏回填");
    }

    static void verifyRelease(DataSource ds, String original) throws Exception {
        String sql = temporary(original);
        int passed = 0;
        try (Connection c = ds.getConnection()) {
            // 原样交付文件只装配临时辅助对象、检查真实 public 最终结构，绝不运行升级正文。
            reset(c);
            c.setAutoCommit(false);
            try {
                exec(c, original.substring(0, original.indexOf("DO $release$")));
                exec(c, "SELECT pg_temp.nocode_release_contract(contract, true) FROM nocode_release_final");
                c.rollback();
                System.out.println("PASS original-delivery-contract-on-current-public"); passed++;
            } finally { c.rollback(); c.setAutoCommit(true); }
            for (int start = 51; start <= TARGET; start++) {
                releaseFixture(c, start, true);
                apply(c, sql, null);
                assertRelease(c, start, true);
                String fingerprint = releaseFingerprint(c);
                apply(c, sql, null);
                require(fingerprint.equals(releaseFingerprint(c)), "重复运行改写了数据，V" + start);
                System.out.println("PASS upgrade-and-rerun-V" + start); passed++;
            }
            for (int start : List.of(51, 60, 68, 75, 79)) {
                releaseFixture(c, start, false);
                apply(c, sql, null);
                assertRelease(c, start, false);
                String fingerprint = releaseFingerprint(c);
                apply(c, sql, null);
                require(fingerprint.equals(releaseFingerprint(c)), "无历史重跑改写了数据");
                System.out.println("PASS no-history-and-rerun-V" + start); passed++;
            }
            releaseFixture(c, 51, true);
            exec(c, "DELETE FROM pg_temp.nocode_schema_history WHERE version='025'");
            apply(c, sql, null);
            require(number(c, "SELECT count(*) FROM pg_temp.nocode_schema_history") == 50, "不应补造缺项历史");
            System.out.println("PASS preserve-history-gap"); passed++;
            for (String corruption : List.of(
                    "UPDATE pg_temp.nocode_schema_history SET checksum=0 WHERE version='051'",
                    "UPDATE pg_temp.nocode_schema_history SET success=false WHERE version='051'",
                    "UPDATE pg_temp.nocode_schema_history SET version='080' WHERE version='051'",
                    "UPDATE pg_temp.nocode_schema_history SET version='050' WHERE version='051'")) {
                releaseFixture(c, 51, true);
                exec(c, corruption);
                String before = releaseFingerprint(c);
                apply(c, sql, "历史");
                require(before.equals(releaseFingerprint(c)), "历史异常未回滚");
                System.out.println("PASS reject-history-corruption"); passed++;
            }
            releaseFixture(c, 66, true);
            exec(c, "UPDATE pg_temp.nocode_schema_history SET checksum=958506139,script='V066__task_template_primary_version.sql' WHERE version='066'");
            apply(c, sql, "历史");
            System.out.println("PASS reject-old-branch-V066-conflict"); passed++;
            releaseFixture(c, 51, true);
            exec(c, "INSERT INTO pg_temp.system_menu(id,name,type,parent_id,permission) VALUES(99000,'冲突菜单',3,0,'nocode:report:create')");
            String before = releaseFingerprint(c);
            apply(c, sql, "仅存在一部分");
            require(before.equals(releaseFingerprint(c)), "后段失败未回滚数据和历史");
            require(number(c, "SELECT count(*) FROM pg_class WHERE relnamespace=pg_my_temp_schema() AND relname='nocode_task_launch_draft'") == 0, "后段失败未回滚 DDL");
            System.out.println("PASS late-failure-rolls-back-all-DDL-data-history"); passed++;
            releaseFixture(c, 79, true);
            exec(c, "ALTER TABLE pg_temp.nocode_task_entry_record ALTER COLUMN work_rule_json TYPE varchar(30)");
            apply(c, sql, "列");
            System.out.println("PASS reject-column-type-drift"); passed++;
            releaseFixture(c, 79, false);
            exec(c, "UPDATE pg_temp.nocode_task_entry_binding SET config_json='{\"workRule\":{\"mode\":\"RECORD_ONCE\",\"minutes\":60}}'; UPDATE pg_temp.nocode_task_template SET primary_version=1;");
            String preserved = releaseFingerprint(c);
            apply(c, sql, null);
            require(preserved.equals(releaseFingerprint(c)), "重跑覆盖历史计价规则或老板主版本设置");
            System.out.println("PASS preserve-frozen-rates-and-user-primary-version"); passed++;
            releaseFixture(c, 79, false);
            exec(c, "UPDATE pg_temp.nocode_task_template SET primary_version=NULL");
            apply(c, sql, null);
            require(number(c, "SELECT count(*) FROM pg_temp.nocode_task_template WHERE primary_version IS NULL") == 1, "重跑回填了用户的 NULL 主版本");
            System.out.println("PASS preserve-null-primary-version"); passed++;
            releaseFixture(c, 51, true);
            exec(c, "ALTER TABLE pg_temp.nocode_task_instance ADD COLUMN planned_start timestamp");
            apply(c, sql, "仅存在一部分");
            System.out.println("PASS reject-partial-V054-schema"); passed++;
            releaseFixture(c, 51, true);
            exec(c, "DROP INDEX pg_temp.nocode_task_entry_record_task_idx");
            apply(c, sql, "索引");
            System.out.println("PASS reject-missing-V051-baseline-index"); passed++;
            releaseFixture(c, 79, true);
            exec(c, "DROP INDEX pg_temp.nocode_task_checklist_membership_idx; CREATE INDEX nocode_task_checklist_membership_idx ON pg_temp.nocode_task_plan(user_id,period,plan_date,task_id)");
            apply(c, sql, "索引");
            System.out.println("PASS reject-index-predicate-drift"); passed++;
            reset(c);
        }
        System.out.println("VERIFICATION PASS: " + passed + " PostgreSQL cases; temporary fixtures only");
    }
}
