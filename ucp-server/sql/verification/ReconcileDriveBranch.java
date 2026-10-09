import com.lingan.ucp.nocode.tools.NocodeToolContext;

import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.filesystem.FileSystemResource;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

/** 旧网盘分支到合并后 V051 的受限手工升级；dry-run 默认回滚，复用现有发布 SQL 和 Spring 数据源。 */
public class ReconcileDriveBranch {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("dry-run", "apply").contains(args[0])) {
            throw new IllegalArgumentException("dry-run | apply");
        }
        String release =
                Files.readString(Path.of("sql/postgresql/manual/upgrade_20261001_v038_v051.sql"));
        String marker = "DO $release$";
        int position = release.indexOf(marker);
        if (position < 0 || position != release.lastIndexOf(marker)) {
            throw new IllegalStateException("既有升级 SQL 结构变化，停止装配");
        }
        try (ConfigurableApplicationContext context = NocodeToolContext.open();
                Connection connection = context.getBean(DataSource.class).getConnection()) {
            connection.setAutoCommit(false);
            try {
                execute(
                        connection,
                        "SET LOCAL lock_timeout='10s'; SET LOCAL statement_timeout='10min'");
                List<String> tables = new ArrayList<>();
                try (Statement statement = connection.createStatement();
                        ResultSet rows =
                                statement.executeQuery(
                                        "SELECT tablename FROM pg_tables WHERE schemaname='public'"
                                                + " ORDER BY tablename")) {
                    while (rows.next()) tables.add(rows.getString(1));
                }
                // 锁定已有表阻止并发写入，使业务行摘要比较有确定含义。
                for (String table : tables)
                    execute(
                            connection,
                            "LOCK TABLE public." + quote(table) + " IN SHARE ROW EXCLUSIVE MODE");
                Map<String, String> before = snapshot(connection, tables);
                Map<String, String> configBefore = protectedConfig(connection);
                execute(connection, release.substring(0, position));
                verifySources(connection);
                execute(
                        connection,
                        Files.readString(
                                Path.of(
                                        "sql/postgresql/manual/reconcile_20261002_drive_branch.sql")));
                // 完整复用既有升级包的最终表、索引、约束、菜单及迁移身份验证；此时所有版本已登记。
                execute(connection, release.substring(position));
                if (!before.equals(snapshot(connection, tables))) {
                    throw new IllegalStateException("已有业务数据发生非预期变化，回滚");
                }
                if (!protectedConfig(connection).entrySet().containsAll(configBefore.entrySet())) {
                    throw new IllegalStateException("非任务入口的已有菜单、授权或消息模板发生变化，回滚");
                }
                System.out.println(
                        "DATA PRESERVATION PASS: "
                                + before.size()
                                + " existing tables; row counts and sorted row hashes unchanged");
                System.out.println(
                        "CONFIG PRESERVATION PASS: "
                                + configBefore.size()
                                + " existing unrelated configuration rows unchanged");
                if (args[0].equals("apply")) {
                    connection.commit();
                    System.out.println(
                            "COMMITTED: V040-V046 executed, old drive identities mapped to"
                                    + " V047-V051");
                } else {
                    connection.rollback();
                    System.out.println(
                            "DRY RUN PASS: schema, rows and history rolled back; PostgreSQL"
                                    + " sequences may retain gaps");
                }
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    private static void verifySources(Connection connection) throws Exception {
        try (java.util.stream.Stream<Path> paths =
                Files.list(Path.of("sql/postgresql/migrations"))) {
            List<Path> files =
                    paths.filter(path -> path.getFileName().toString().matches("V\\d+__.*\\.sql"))
                            .sorted()
                            .toList();
            if (files.size() != 51) throw new IllegalStateException("只支持已审阅的 51 个源迁移");
            for (Path path : files) {
                String name = path.getFileName().toString();
                int version = Integer.parseInt(name.substring(1, name.indexOf("__")));
                int checksum =
                        ChecksumCalculator.calculate(
                                new FileSystemResource(
                                        new org.flywaydb.core.api.Location(
                                                "filesystem:sql/postgresql/migrations"),
                                        path.toAbsolutePath().toString(),
                                        StandardCharsets.UTF_8,
                                        false));
                try (PreparedStatement statement =
                        connection.prepareStatement(
                                "SELECT i.script,i.checksum,p.body FROM nocode_release_identity i"
                                        + " LEFT JOIN nocode_release_plan p USING(version) WHERE"
                                        + " i.version=?")) {
                    statement.setInt(1, version);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()
                                || !name.equals(rows.getString(1))
                                || checksum != rows.getInt(2)
                                || (version >= 38
                                        && !Files.readString(path)
                                                .replace("\r\n", "\n")
                                                .equals(rows.getString(3)))) {
                            throw new IllegalStateException("发布包与当前源码不匹配：" + name);
                        }
                    }
                }
            }
            System.out.println(
                    "SOURCE PASS: 51 identities and V038-V051 original SQL bodies match current"
                            + " sources");
        }
    }

    private static Map<String, String> snapshot(Connection connection, List<String> tables)
            throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : tables) {
            // 三个配置表由原始任务迁移按明确条件调整；历史按独立守卫核对。
            if (List.of(
                            "nocode_schema_history",
                            "system_menu",
                            "system_role_menu",
                            "sys_msg_template")
                    .contains(table)) continue;
            String query =
                    "SELECT count(*)::text || ':' || coalesce(md5(string_agg(h, '' ORDER BY h)),"
                            + " '') FROM (SELECT md5(row_to_json(t)::text) h FROM public."
                            + quote(table)
                            + " t) hashes";
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery(query)) {
                rows.next();
                result.put(table, rows.getString(1));
            }
        }
        return result;
    }

    private static Map<String, String> protectedConfig(Connection connection) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : List.of("system_menu", "system_role_menu", "sys_msg_template")) {
            String predicate =
                    switch (table) {
                        case "system_menu" -> "NOT (path='/nocode-app/task-center' AND deleted=0)";
                        case "sys_msg_template" -> "code IS DISTINCT FROM 'nocode-task-comment'";
                        default -> "true";
                    };
            try (Statement statement = connection.createStatement();
                    ResultSet rows =
                            statement.executeQuery(
                                    "SELECT id,md5(row_to_json(t)::text) FROM public."
                                            + quote(table)
                                            + " t WHERE "
                                            + predicate)) {
                while (rows.next()) result.put(table + ":" + rows.getString(1), rows.getString(2));
            }
        }
        return result;
    }

    private static void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
            for (SQLWarning warning = statement.getWarnings();
                    warning != null;
                    warning = warning.getNextWarning()) {
                System.out.println(warning.getMessage());
            }
        }
    }
}
