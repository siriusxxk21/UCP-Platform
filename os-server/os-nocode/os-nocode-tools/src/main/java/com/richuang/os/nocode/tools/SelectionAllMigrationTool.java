package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.SelectionAllMigrationReport.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.richuang.os.module.system.api.permission.RoleApi;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.nocode.api.ApplicationFollows;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.DataObjectApi;
import com.richuang.os.nocode.application.service.application.ApplicationFollowService;
import com.richuang.os.nocode.application.service.sharing.ObjectGrantValidator.Universe;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import javax.sql.DataSource;

/**
 * 把存量授权清单转成「全部」（契约第 7 章）。
 *
 * <pre>
 * dry-run       [--prefix 应用编码前缀] [--out report.json]            只读事务；逐行计划 + 不变式结果（默认子命令）
 * apply         --report report.json --actor 用户ID [--out selection-all-apply.json]
 *               在写事务里重新算一遍计划，与报告逐行比对：不一致的行跳过并记 CONFLICT，不变式不成立则整体中止回滚；
 *               对象→应用授权同时写日志表；另出给业务方看的留底清单 selection-all-留底.md（与 --out 同目录）
 * rollback      --report selection-all-apply.json --actor 用户ID [--out rollback.json]
 *               逐行核对「库里现状 == 转换后」才写回转换前（连同保存时间）；不等的行跳过并记 CONFLICT
 * expand-all    --actor 用户ID [--prefix 应用编码前缀] [--dry-run] [--out expand-all.json]
 *               把库里所有「全部」按当时的全集展开成显式清单：回滚到不认识「全部」的旧代码之前用
 * follow-behind --actor 用户ID [--dry-run] [--out follow.json]
 *               对所有开着自动跟随且落后的应用各补一次跟随（会产生应用新版本；上线当天要不要跑由负责人决定）
 * </pre>
 *
 * <p>扫描与判据见 {@link SelectionAllMigrationScan}、{@link
 * SelectionAllMigrationPlanner}。已发布的应用快照不改写（带校验和， 流程绑定按校验和引用它）；它们在第一次自动跟随或下一次人工发布时转换。除
 * follow-behind 外不产生任何应用版本。
 *
 * <p>经 {@link ToolProcessGuard} 启动，工具进程里不启动任何后台作业。启动方式见 deploy/selection-all-migration.sh。
 */
public class SelectionAllMigrationTool {
    static final String DRY_RUN = "dry-run", APPLY = "apply", ROLLBACK = "rollback";
    static final String EXPAND_ALL = "expand-all", FOLLOW_BEHIND = "follow-behind";
    static final String DRY_RUN_FLAG = "dry-run";
    static final String APPLY_REASON = "存量转换：授权清单转「全部」";
    static final String ROLLBACK_REASON = "存量转换回滚：恢复转换前的授权清单";
    static final String EXPAND_REASON = "回滚预案：把「全部」展开成显式清单";
    static final String RETAINED_FILE = "selection-all-留底.md";
    static final int EXIT_OK = 0, EXIT_USAGE = 2, EXIT_FAILED = 3;

    record Services(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            DataObjectApi objects,
            ApplicationFollowService follows,
            RoleApi roles,
            AdminUserApi users) {
        static Services of(ApplicationContext context) {
            return new Services(
                    context.getBean(JdbcTemplate.class),
                    context.getBean(PlatformTransactionManager.class),
                    context.getBean(DataObjectApi.class),
                    context.getBean(ApplicationFollowService.class),
                    context.getBeanProvider(RoleApi.class).getIfAvailable(),
                    context.getBeanProvider(AdminUserApi.class).getIfAvailable());
        }
    }

    private final Services services;
    private final ObjectMapper json;

    /** 做判定用的全集来源；不变式永远对着直接从对象定义算出的全集核对，与它无关。只有测试会换掉它。 */
    private final Function<DataCenter.Definition, Universe> planningUniverse;

    SelectionAllMigrationTool(Services services, ObjectMapper json) {
        this(services, json, Universe::of);
    }

    SelectionAllMigrationTool(
            Services services,
            ObjectMapper json,
            Function<DataCenter.Definition, Universe> planningUniverse) {
        this.services = services;
        this.json = json;
        this.planningUniverse = planningUniverse;
    }

    private SelectionAllMigrationScan scan(String prefix, boolean expand) {
        return new SelectionAllMigrationScan(
                        services.jdbc(),
                        services.objects(),
                        services.roles(),
                        services.users(),
                        json,
                        planningUniverse,
                        prefix,
                        expand)
                .run();
    }

    private SelectionAllMigrationReport report(
            String command,
            String prefix,
            List<Row> rows,
            List<String> violations,
            List<String> skipped) {
        return new SelectionAllMigrationReport(
                TOOL,
                FORMAT_VERSION,
                command,
                prefix == null ? "" : prefix,
                OffsetDateTime.now().toString(),
                services.jdbc().queryForObject("SELECT current_setting('TimeZone')", String.class),
                ZoneId.systemDefault().getId(),
                summarize(rows),
                List.copyOf(rows),
                List.copyOf(violations),
                List.copyOf(skipped));
    }

    // ── dry-run ──

    SelectionAllMigrationReport dryRun(String prefix) {
        TransactionTemplate tx = new TransactionTemplate(services.transactions());
        tx.setReadOnly(true);
        tx.setTimeout(300);
        return tx.execute(
                status -> {
                    services.jdbc().execute("SET TRANSACTION READ ONLY");
                    SelectionAllMigrationScan scan = scan(prefix, false);
                    return report(DRY_RUN, prefix, scan.rows, scan.violations, scan.skipped);
                });
    }

    // ── 写库的三个子命令 ──

    /** 与应用发布相同的拿锁顺序：目录独占锁 → 设计写锁。整个执行在一笔事务里，任何异常整笔回滚。 */
    private void lock() {
        for (String name : List.of("nocode-automation-catalog", "nocode-design-write"))
            services.jdbc()
                    .queryForObject(
                            "SELECT pg_advisory_xact_lock(hashtextextended(?,0))::text",
                            String.class,
                            name);
    }

    /**
     * 在写事务里重新算一遍计划，与报告逐行比对后写入。重新算出的计划里不变式不成立 ⇒ 抛出，整笔回滚。
     *
     * <p>行与报告不一致（内容、保存时间、判定结果任何一处不同，或这一行已不需要转换）⇒ 该行跳过并记 CONFLICT。 某个应用的对象→应用授权有一行没按报告写入时，
     * 这个应用的成员授权与入口成员授权也一并跳过：它们的全集是按「对象→应用授权转换之后」算的。
     */
    SelectionAllMigrationReport apply(SelectionAllMigrationReport report, long actor) {
        require(report, Set.of(DRY_RUN), actor);
        if (!report.violations().isEmpty())
            throw new IllegalStateException("报告里有不变式不成立的清单，不能执行：" + report.violations());
        return new TransactionTemplate(services.transactions())
                .execute(
                        status -> {
                            lock();
                            SelectionAllMigrationScan fresh = scan(report.prefix(), false);
                            if (!fresh.violations.isEmpty())
                                throw new IllegalStateException(
                                        "不变式不成立，执行中止，库未改动：" + fresh.violations);
                            Map<String, Row> planned = new HashMap<>();
                            for (Row row : report.rows()) planned.put(row.key(), row);
                            Map<String, Row> current = new HashMap<>();
                            Set<String> stale = new HashSet<>();
                            for (Row row : fresh.rows) {
                                current.put(row.key(), row);
                                Row expected = planned.get(row.key());
                                if (OBJECT_GRANT.equals(row.store())
                                        && (expected == null
                                                || !row.equals(expected.withStatus(row.status()))))
                                    stale.add(row.applicationId());
                            }
                            List<Row> result = new ArrayList<>();
                            for (Row row : report.rows()) {
                                Row now = current.get(row.key());
                                boolean member =
                                        APPLICATION_ACCESS.equals(row.store())
                                                || ENTRY_ACCESS.equals(row.store());
                                if (now == null
                                        || !now.equals(row.withStatus(now.status()))
                                        || member && stale.contains(row.applicationId())) {
                                    if (OBJECT_GRANT.equals(row.store()))
                                        stale.add(row.applicationId());
                                    result.add(row.withStatus(CONFLICT));
                                    continue;
                                }
                                if (row.before().equals(row.after())) {
                                    result.add(row.withStatus(PLANNED));
                                    continue;
                                }
                                store(row, row.after(), APPLY_REASON, actor, false);
                                result.add(row.withStatus(WRITTEN));
                            }
                            return report(APPLY, report.prefix(), result, List.of(), fresh.skipped);
                        });
    }

    /** 逐行核对「库里现状 == 转换后」才写回转换前（连同保存时间）；不等的行跳过并记 CONFLICT。只处理报告里写入过的行。 */
    SelectionAllMigrationReport rollback(SelectionAllMigrationReport report, long actor) {
        require(report, Set.of(APPLY, EXPAND_ALL), actor);
        return new TransactionTemplate(services.transactions())
                .execute(
                        status -> {
                            lock();
                            List<Row> result = new ArrayList<>();
                            for (Row row : report.rows()) {
                                if (!WRITTEN.equals(row.status())) {
                                    result.add(row.withStatus(PLANNED));
                                    continue;
                                }
                                JsonNode current = current(row);
                                if (current == null || !current.equals(row.after())) {
                                    result.add(row.withStatus(CONFLICT));
                                    continue;
                                }
                                store(row, row.before(), ROLLBACK_REASON, actor, true);
                                result.add(row.withStatus(WRITTEN));
                            }
                            return report(
                                    ROLLBACK, report.prefix(), result, List.of(), report.skipped());
                        });
    }

    /** 把库里所有「全部」展开成显式清单：在同一笔写事务里按当时的全集算出计划并逐行写入。dryRun 为真时只出计划。 */
    SelectionAllMigrationReport expandAll(String prefix, long actor, boolean dryRun) {
        if (actor <= 0) throw new IllegalArgumentException("--actor 必须是正整数");
        return new TransactionTemplate(services.transactions())
                .execute(
                        status -> {
                            if (!dryRun) lock();
                            SelectionAllMigrationScan scan = scan(prefix, true);
                            List<Row> result = new ArrayList<>();
                            for (Row row : scan.rows) {
                                if (dryRun) {
                                    result.add(row);
                                    continue;
                                }
                                store(row, row.after(), EXPAND_REASON, actor, false);
                                result.add(row.withStatus(WRITTEN));
                            }
                            return report(
                                    EXPAND_ALL, prefix, result, scan.violations, scan.skipped);
                        });
    }

    List<ApplicationFollows.FollowResult> followBehind(long actor, boolean dryRun) {
        if (actor <= 0) throw new IllegalArgumentException("--actor 必须是正整数");
        return services.follows().followBehind(actor, dryRun);
    }

    private static void require(
            SelectionAllMigrationReport report, Set<String> commands, long actor) {
        if (report == null
                || !TOOL.equals(report.tool())
                || report.formatVersion() != FORMAT_VERSION
                || !commands.contains(report.command()))
            throw new IllegalArgumentException("需要本工具 " + String.join(" 或 ", commands) + " 产出的报告");
        if (actor <= 0) throw new IllegalArgumentException("--actor 必须是正整数");
    }

    /** 库里这一行现在的原文（加行锁）；应用草稿只取报告里涉及的那些任务入口的允许范围。行已不存在时为 null。 */
    private JsonNode current(Row row) {
        long application = Long.parseLong(row.applicationId());
        List<String> found =
                switch (row.store()) {
                    case OBJECT_GRANT ->
                            services.jdbc()
                                    .queryForList(
                                            "SELECT grant_json::text FROM"
                                                + " public.nocode_object_application_grant WHERE"
                                                + " application_id=? AND object_id=? AND deleted=0"
                                                + " FOR UPDATE",
                                            String.class,
                                            application,
                                            Long.valueOf(row.objectId()));
                    case APPLICATION_ACCESS ->
                            services.jdbc()
                                    .queryForList(
                                            "SELECT policy_json::text FROM"
                                                    + " public.nocode_application_access WHERE"
                                                    + " application_id=? AND deleted=0 FOR UPDATE",
                                            String.class,
                                            application);
                    case ENTRY_ACCESS ->
                            services.jdbc()
                                    .queryForList(
                                            "SELECT policy_json::text FROM"
                                                + " public.nocode_task_entry_access WHERE"
                                                + " application_id=? AND entry_id=? AND deleted=0"
                                                + " FOR UPDATE",
                                            String.class,
                                            application,
                                            row.entryId());
                    default ->
                            services.jdbc()
                                    .queryForList(
                                            "SELECT design_json::text FROM"
                                                    + " public.nocode_application WHERE id=? AND"
                                                    + " deleted=0 FOR UPDATE",
                                            String.class,
                                            application);
                };
        if (found.isEmpty()) return null;
        JsonNode body = tree(found.getFirst());
        if (!ENTRY_LIMIT.equals(row.store())) return body;
        ObjectNode limits = json.createObjectNode();
        for (JsonNode resource : body.path("resources"))
            if ("TASK_ENTRY".equals(resource.path("kind").asText())
                    && row.after().has(resource.path("id").asText())
                    && resource.path("config").path("limits") instanceof ArrayNode value)
                limits.set(resource.path("id").asText(), value);
        return limits;
    }

    private JsonNode tree(String text) {
        try {
            return json.readTree(text);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("JSON 无法读取", e);
        }
    }

    /**
     * 写一行。修订号加一；对象→应用授权同时写日志表。回滚（backwards）时把保存时间恢复成转换前的：「后加」的判据靠它，恢复后再跑 dry-run
     * 的结果与第一次相同。应用草稿只替换各任务入口的允许范围，其余原样；已发布快照不动。
     */
    private void store(Row row, JsonNode target, String reason, long actor, boolean backwards) {
        long application = Long.parseLong(row.applicationId());
        String user = Long.toString(actor);
        String time = "COALESCE(CAST(? AS timestamp), clock_timestamp())";
        String savedAt = backwards ? row.savedAt() : null;
        int updated;
        switch (row.store()) {
            case OBJECT_GRANT -> {
                long object = Long.parseLong(row.objectId());
                updated =
                        services.jdbc()
                                .update(
                                        "UPDATE public.nocode_object_application_grant SET"
                                                + " grant_json=CAST(? AS jsonb), reason=?,"
                                                + " lock_version=lock_version+1, updater=?,"
                                                + " update_time="
                                                + time
                                                + " WHERE application_id=? AND object_id=? AND"
                                                + " deleted=0",
                                        target.toString(),
                                        reason,
                                        user,
                                        savedAt,
                                        application,
                                        object);
                services.jdbc()
                        .update(
                                "INSERT INTO public.nocode_object_application_grant_log(object_id,"
                                    + " application_id, lock_version, grant_json, reason, creator,"
                                    + " updater) SELECT object_id, application_id, lock_version,"
                                    + " grant_json, reason, ?, ? FROM"
                                    + " public.nocode_object_application_grant WHERE"
                                    + " application_id=? AND object_id=? AND deleted=0",
                                user,
                                user,
                                application,
                                object);
            }
            case APPLICATION_ACCESS ->
                    updated =
                            services.jdbc()
                                    .update(
                                            "UPDATE public.nocode_application_access SET"
                                                    + " policy_json=CAST(? AS jsonb),"
                                                    + " lock_version=lock_version+1, updater=?,"
                                                    + " update_time="
                                                    + time
                                                    + " WHERE application_id=? AND deleted=0",
                                            target.toString(),
                                            user,
                                            savedAt,
                                            application);
            case ENTRY_ACCESS ->
                    updated =
                            services.jdbc()
                                    .update(
                                            "UPDATE public.nocode_task_entry_access SET"
                                                    + " policy_json=CAST(? AS jsonb),"
                                                    + " lock_version=lock_version+1, updater=?,"
                                                    + " update_time="
                                                    + time
                                                    + " WHERE application_id=? AND entry_id=? AND"
                                                    + " deleted=0",
                                            target.toString(),
                                            user,
                                            savedAt,
                                            application,
                                            row.entryId());
            default -> {
                JsonNode design =
                        tree(
                                services.jdbc()
                                        .queryForObject(
                                                "SELECT design_json::text FROM"
                                                        + " public.nocode_application WHERE id=?"
                                                        + " FOR UPDATE",
                                                String.class,
                                                application));
                for (JsonNode resource : design.path("resources"))
                    if ("TASK_ENTRY".equals(resource.path("kind").asText())
                            && target.has(resource.path("id").asText())
                            && resource.path("config") instanceof ObjectNode config)
                        config.set("limits", target.get(resource.path("id").asText()));
                updated =
                        services.jdbc()
                                .update(
                                        "UPDATE public.nocode_application SET"
                                                + " design_json=CAST(? AS jsonb),"
                                                + " lock_version=lock_version+1, updater=?,"
                                                + " update_time="
                                                + time
                                                + " WHERE id=? AND deleted=0",
                                        design.toString(),
                                        user,
                                        savedAt,
                                        application);
            }
        }
        if (updated != 1) throw new IllegalStateException("写入失败：" + row.describe());
    }

    // ── 命令行 ──

    static final String USAGE =
            """
            用法：
              dry-run       [--prefix 应用编码前缀] [--out report.json]
              apply         --report report.json --actor 用户ID [--out selection-all-apply.json]
              rollback      --report selection-all-apply.json --actor 用户ID [--out rollback.json]
              expand-all    --actor 用户ID [--prefix 应用编码前缀] [--dry-run] [--out expand-all.json]
              follow-behind --actor 用户ID [--dry-run] [--out follow.json]
            退出码：0 成功；2 参数错误（未连库）；3 执行失败（含启动自检失败、不变式不成立）""";

    record Command(
            String name,
            SelectionAllMigrationReport report,
            String prefix,
            long actor,
            String out,
            boolean dryRun) {
        static Command parse(String[] args, ObjectMapper json) {
            Map<String, String> options = arguments(args);
            String name = options.getOrDefault("", DRY_RUN);
            Set<String> allowed =
                    switch (name) {
                        case DRY_RUN -> Set.of("", "prefix", "out");
                        case APPLY, ROLLBACK -> Set.of("", "report", "actor", "out");
                        case EXPAND_ALL -> Set.of("", "actor", "prefix", DRY_RUN_FLAG, "out");
                        case FOLLOW_BEHIND -> Set.of("", "actor", DRY_RUN_FLAG, "out");
                        default ->
                                throw new IllegalArgumentException(
                                        "子命令只能是 dry-run、apply、rollback、expand-all、follow-behind");
                    };
            for (String key : options.keySet())
                if (!allowed.contains(key))
                    throw new IllegalArgumentException(name + " 不接受参数 --" + key);
            String out =
                    options.getOrDefault(
                            "out",
                            switch (name) {
                                case DRY_RUN -> "report.json";
                                case APPLY -> "selection-all-apply.json";
                                case FOLLOW_BEHIND -> "follow.json";
                                default -> name + ".json";
                            });
            String prefix = options.getOrDefault("prefix", "");
            if (DRY_RUN.equals(name)) return new Command(name, null, prefix, 0, out, true);
            long actor = number(required(options, "actor"));
            if (EXPAND_ALL.equals(name) || FOLLOW_BEHIND.equals(name))
                return new Command(
                        name, null, prefix, actor, out, options.containsKey(DRY_RUN_FLAG));
            Path path = Path.of(required(options, "report"));
            try {
                return new Command(
                        name,
                        json.readValue(path.toFile(), SelectionAllMigrationReport.class),
                        prefix,
                        actor,
                        out,
                        false);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("无法读取报告 " + path + "：" + e.getMessage(), e);
            }
        }

        private static long number(String value) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
                // 与非正数同样按参数错误处理
            }
            throw new IllegalArgumentException("--actor 必须是正整数，实际为「" + value + "」");
        }
    }

    /** 第一个非 -- 参数是子命令（键为空串），其余是 --键 值；--dry-run 是开关，不带取值。 */
    static Map<String, String> arguments(String[] args) {
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--" + DRY_RUN_FLAG)) result.put(DRY_RUN_FLAG, "true");
            else if (args[i].startsWith("--")) {
                if (i + 1 >= args.length) throw new IllegalArgumentException(args[i] + " 缺少取值");
                result.put(args[i].substring(2), args[++i]);
            } else if (!result.containsKey("")) result.put("", args[i]);
            else throw new IllegalArgumentException("无法识别的参数：" + args[i]);
        }
        return result;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 --" + key);
        return value;
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        ObjectMapper json = ObjectRuleMigrationTool.mapper();
        Command command;
        try {
            command = Command.parse(args, json);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误：" + e.getMessage());
            System.err.println(USAGE);
            return EXIT_USAGE;
        }
        try (ConfigurableApplicationContext context =
                ToolProcessGuard.start(ObjectRuleMigrationTool.servicesApplication())) {
            NocodeToolContext.requireSingleDatabase(context.getBean(DataSource.class));
            SelectionAllMigrationTool tool =
                    new SelectionAllMigrationTool(Services.of(context), json);
            Path out = Path.of(command.out());
            if (FOLLOW_BEHIND.equals(command.name())) {
                List<ApplicationFollows.FollowResult> result =
                        tool.followBehind(command.actor(), command.dryRun());
                json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), result);
                System.out.println("{\"applications\":" + result.size() + "}");
                return EXIT_OK;
            }
            SelectionAllMigrationReport result =
                    switch (command.name()) {
                        case APPLY -> tool.apply(command.report(), command.actor());
                        case ROLLBACK -> tool.rollback(command.report(), command.actor());
                        case EXPAND_ALL ->
                                tool.expandAll(command.prefix(), command.actor(), command.dryRun());
                        default -> tool.dryRun(command.prefix());
                    };
            json.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), result);
            if (APPLY.equals(command.name()))
                Files.writeString(
                        out.toAbsolutePath().getParent().resolve(RETAINED_FILE), result.retained());
            System.out.println(json.writeValueAsString(result.summary()));
            if (!result.violations().isEmpty()) {
                System.err.println("不变式不成立：" + result.violations());
                return EXIT_FAILED;
            }
            return EXIT_OK;
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("执行失败：" + e.getMessage());
            return EXIT_FAILED;
        }
    }
}
