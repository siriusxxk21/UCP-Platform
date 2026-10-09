package com.lingan.ucp.nocode.tools;

import com.alibaba.druid.pool.DruidDataSource;

import jakarta.annotation.Resource;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import javax.sql.DataSource;

/** 显式执行迁移、核对或导出的开发工具；本模块不进入服务运行依赖，也不随启动自动改库。 */
public class NocodeDatabaseTool {
    @Resource private DataSource dataSource;
    @Resource private JdbcTemplate jdbc;

    /** 显式迁移复用当前受管数据源，禁止自动基线、清库和新建 schema。 */
    public Flyway flyway() {
        return Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema("public")
                .schemas("public")
                .table("nocode_schema_history")
                .locations("classpath:db/nocode")
                .baselineVersion("0")
                .baselineOnMigrate(false)
                .validateMigrationNaming(true)
                .validateOnMigrate(true)
                .outOfOrder(false)
                .cleanDisabled(true)
                .createSchemas(false)
                .load();
    }

    /** 命令仅指定操作和导出文件，不接受另一套数据库连接参数。 */
    public static void main(String[] args) {
        if (!((args.length == 1 && Set.of("info", "migrate", "verify").contains(args[0]))
                || (args.length == 3 && Set.of("dump", "dump-all").contains(args[0])))) {
            throw new IllegalArgumentException(
                    "info | migrate | verify | dump[-all] output.sql pg-bin");
        }
        try (var context = NocodeToolContext.open()) {
            context.getBean(NocodeDatabaseTool.class).execute(args);
        } catch (Exception ex) {
            System.err.println(
                    "Nocode database command failed: "
                            + ex.getClass().getSimpleName()
                            + "; connection details suppressed.");
            System.exit(1);
        }
    }

    private void execute(String[] args) throws Exception {
        switch (args[0]) {
            case "info" -> {
                // 只读列出已执行／待执行状态，发布前先检查，不隐式应用新版本。
                var info = flyway().info();
                System.out.println("Migration history: public.nocode_schema_history");
                for (var migration : info.all()) {
                    System.out.printf(
                            "%s | %s | %s%n",
                            migration.getVersion(), migration.getState(), migration.getScript());
                }
                System.out.println("Pending migrations=" + info.pending().length);
            }
            case "migrate" -> {
                Flyway flyway = flyway();
                if (!Boolean.TRUE.equals(
                        jdbc.queryForObject(
                                "SELECT to_regclass('public.nocode_schema_history') IS NOT"
                                        + " NULL",
                                Boolean.class))) {
                    Long existing =
                            jdbc.queryForObject(
                                    """
SELECT count(*) FROM information_schema.tables WHERE table_schema='public'
AND table_name IN ('nocode_object','nocode_object_version','nocode_object_table','nocode_field','nocode_operation_log',
                  'lc_object','lc_object_version','lc_object_table','lc_field','aud_operation_log')
""",
                                    Long.class);
                    if (existing != null && existing > 0)
                        throw new IllegalStateException(
                                "Existing nocode tables without migration history");
                    if (!Boolean.TRUE.equals(
                            jdbc.queryForObject(
                                    "SELECT to_regclass('public.system_users') IS NOT NULL AND"
                                            + " to_regclass('public.system_menu') IS NOT NULL",
                                    Boolean.class)))
                        throw new IllegalStateException("Current OS development database required");
                    flyway.baseline();
                }
                var result = flyway.migrate();
                flyway.validate();
                System.out.println(
                        "Nocode migrations applied="
                                + result.migrationsExecuted
                                + "; validation passed.");
            }
            case "verify" -> {
                flyway().validate();
                System.out.println(
                        "public tables="
                                + jdbc.queryForObject(
                                        "SELECT count(*) FROM pg_tables WHERE"
                                                + " schemaname='public'",
                                        Integer.class));
                System.out.println(
                        "nocode objects="
                                + jdbc.queryForObject(
                                        "SELECT count(*) FROM public.nocode_object",
                                        Integer.class));
                System.out.println(
                        "object permissions="
                                + jdbc.queryForObject(
                                        "SELECT count(*) FROM public.system_menu WHERE"
                                                + " permission LIKE 'nocode:object:%' AND"
                                                + " deleted=0",
                                        Integer.class));
            }
            case "dump", "dump-all" -> {
                boolean fullDatabase = "dump-all".equals(args[0]);
                Path output = Path.of(args[1]).toAbsolutePath();
                Files.createDirectories(output.getParent());
                Path staging = output.resolveSibling(output.getFileName() + ".pending");
                // 原生 pg_dump 不能接收 Java DataSource，从已装配的底座连接池获取有效配置。
                // 不重新读取 YAML，不创建额外 Java 数据源，密码仅传入子进程环境。
                DruidDataSource managed = dataSource.unwrap(DruidDataSource.class);
                if (!managed.getUrl().startsWith("jdbc:postgresql:")) {
                    throw new IllegalStateException("PostgreSQL required");
                }
                URI uri = URI.create(managed.getUrl().substring(5));
                var command =
                        new ArrayList<>(
                                List.of(
                                        Path.of(
                                                        args[2],
                                                        System.getProperty("os.name")
                                                                        .startsWith("Windows")
                                                                ? "pg_dump.exe"
                                                                : "pg_dump")
                                                .toString(),
                                        "--host",
                                        uri.getHost(),
                                        "--port",
                                        String.valueOf(uri.getPort() < 0 ? 5432 : uri.getPort()),
                                        "--username",
                                        managed.getUsername(),
                                        "--dbname",
                                        uri.getPath().substring(1),
                                        "--format=p",
                                        "--clean",
                                        "--if-exists",
                                        "--encoding=UTF8",
                                        "--no-password",
                                        "--file",
                                        staging.toString()));
                // 全库备份保留全部 schema、大对象、权限和建库信息；旧快照保持可移植的 public 范围。
                if (fullDatabase) {
                    command.add("--create");
                } else {
                    command.addAll(List.of("--schema=public", "--no-owner", "--no-privileges"));
                }
                var builder = new ProcessBuilder(command);
                builder.environment().put("PGPASSWORD", managed.getPassword());
                builder.redirectError(
                        output.resolveSibling(output.getFileName() + ".dump.log").toFile());
                try {
                    Process process = builder.start();
                    if (process.waitFor() != 0 || Files.size(staging) < 100) {
                        throw new IllegalStateException("pg_dump failed; previous SQL preserved");
                    }
                    Files.move(staging, output, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(staging);
                }
                System.out.println(
                        (fullDatabase
                                        ? "Full database export completed at "
                                        : "Full public schema/data export completed at ")
                                + Instant.now()
                                + "; bytes="
                                + Files.size(output));
            }
            default -> throw new IllegalArgumentException("Unknown command");
        }
    }
}
