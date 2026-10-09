package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.ReferenceConstantMigrationPlanner.CONVERT;
import static com.lingan.ucp.nocode.tools.ReferenceConstantMigrationReport.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.DataObjectApi;
import com.lingan.ucp.nocode.api.SaveObjectDraft;
import com.lingan.ucp.nocode.enums.AuditOperationEnum;
import com.lingan.ucp.nocode.metadata.service.object.FieldRuleValidator;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDesignService;
import com.lingan.ucp.nocode.runtime.service.record.RecordQueryAccess;
import com.lingan.ucp.nocode.schema.service.publish.SchemaPublishService;
import com.lingan.ucp.nocode.tools.ReferenceConstantMigrationReport.Row;

import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 引用字段的条件固定值存量转换（业务方 2026-10-04：条件行原先给引用字段的是自由文本框，存了名称「民宿管理」去比记录 ID，候选恒为空、保存被拦）。
 *
 * <pre>
 * dry-run  [--out report.json]                                       只回滚事务里读；逐个条件值出判定（默认子命令）
 * apply    --report report.json --actor 用户ID [--out apply.json]     逐对象按报告重算比对，一致的 CONVERT 行改成记录 ID 并发布对象新版本
 * rollback --report apply.json --actor 用户ID [--out rollback.json]   逐行核对「当前值 == 转换后的 ID」才改回原值，并发布对象新版本
 * </pre>
 *
 * <p>判定见 {@link ReferenceConstantMigrationPlanner}。写对象定义照 {@link ObjectRuleMigrationTool}
 * 的先例：以已发布版为底开草稿、保存、发布计划、执行；
 * 有与已发布版本不一致的未发布草稿时拒绝这个对象。对象发布走正式发布服务，<b>会触发应用自动跟随</b>（开着自动跟随的应用由平台同步并发布到新版本）；
 * 发布后仍固定旧版本的应用逐个列进报告。改的是兼容改动，发布计划若要求暂停应用即视为异常，该对象 FAILED，不暂停任何应用。
 *
 * <p>经 {@link ToolProcessGuard} 启动，工具进程里不启动任何后台作业。启动方式见 deploy/reference-constant-migration.sh。
 */
public class ReferenceConstantMigrationTool {
    static final String DRY_RUN = "dry-run", APPLY = "apply", ROLLBACK = "rollback";
    static final String APPLY_REASON = "存量转换：引用字段的条件固定值改为记录 ID";
    static final String ROLLBACK_REASON = "存量转换回滚：引用字段的条件固定值恢复原值";
    static final int EXIT_OK = 0, EXIT_USAGE = 2, EXIT_FAILED = 3;

    record Services(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            ObjectDesignService designs,
            SchemaPublishService publisher,
            DataObjectApi objects,
            RecordQueryAccess records) {
        static Services of(ApplicationContext context) {
            return new Services(
                    context.getBean(JdbcTemplate.class),
                    context.getBean(PlatformTransactionManager.class),
                    context.getBean(ObjectDesignService.class),
                    context.getBean(SchemaPublishService.class),
                    context.getBean(DataObjectApi.class),
                    context.getBean(RecordQueryAccess.class));
        }
    }

    private final Services services;
    private final ObjectMapper json;

    ReferenceConstantMigrationTool(Services services, ObjectMapper json) {
        this.services = services;
        this.json = json;
    }

    // ── 读 ──

    /** 启用且已发布的对象：ID → 名称（按 ID 排序）。 */
    private Map<String, String> activeObjects() {
        Map<String, String> result = new LinkedHashMap<>();
        services.jdbc()
                .query(
                        "SELECT o.id::text AS id, o.object_name AS name FROM public.nocode_object o"
                                + " WHERE o.deleted=0 AND o.status='ACTIVE' AND"
                                + " o.current_published_version_no IS NOT NULL ORDER BY o.id",
                        rs -> {
                            result.put(rs.getString("id"), rs.getString("name"));
                        });
        return result;
    }

    /** 一次运行内缓存的读库实现：目标对象按当前发布版，行按主键升序分页扫完。 */
    private final class JdbcTargets implements ReferenceConstantMigrationPlanner.Targets {
        private final Set<String> active;
        private final Map<String, DataCenter.Definition> definitions = new HashMap<>();
        private final Map<String, Boolean> keys = new HashMap<>();
        private final Map<String, List<ReferenceConstantMigrationPlanner.Stored>> rows =
                new HashMap<>();

        JdbcTargets(Set<String> active) {
            this.active = active;
        }

        @Override
        public DataCenter.Definition definition(String objectId) {
            if (!active.contains(objectId)) return null;
            return definitions.computeIfAbsent(
                    objectId, id -> services.objects().getVersion(id, null).definition());
        }

        @Override
        public Set<String> existing(String objectId, Collection<String> ids) {
            Set<String> found = new LinkedHashSet<>();
            for (String id : ids)
                if (keys.computeIfAbsent(objectId + "|" + id, k -> stored(objectId, id)))
                    found.add(id);
            return found;
        }

        private boolean stored(String objectId, String id) {
            if (id == null || id.isBlank() || id.length() > 500) return false;
            try {
                return services.records().storedRow(objectId, id, 0L) != null;
            } catch (ServiceException unreadable) {
                return false;
            }
        }

        @Override
        public List<ReferenceConstantMigrationPlanner.Stored> rows(String objectId, int cap) {
            if (rows.containsKey(objectId)) return rows.get(objectId);
            List<ReferenceConstantMigrationPlanner.Stored> result = new ArrayList<>();
            String cursor = null;
            while (true) {
                var page = services.records().scanStored(objectId, cursor, 1000, 0L);
                for (var row : page.subList(0, Math.min(page.size(), 1000)))
                    result.add(
                            new ReferenceConstantMigrationPlanner.Stored(row.id(), row.values()));
                if (result.size() > cap) {
                    result = null;
                    break;
                }
                if (page.size() <= 1000) break;
                cursor = page.get(999).id();
            }
            rows.put(objectId, result);
            return result;
        }
    }

    /** 当前发布版上的全部判定行（不合法的条件值）。 */
    private List<Row> scan(Set<String> only) {
        var objects = activeObjects();
        var targets = new JdbcTargets(objects.keySet());
        List<Row> rows = new ArrayList<>();
        for (var id : objects.keySet()) {
            if (only != null && !only.contains(id)) continue;
            var version = services.objects().getVersion(id, null);
            rows.addAll(
                    ReferenceConstantMigrationPlanner.plan(
                            version.definition(), version.versionNo(), targets));
        }
        return rows;
    }

    private <T> T readOnly(java.util.function.Supplier<T> action) {
        // 记录读取经运行层事务模板，对象设计读取会加共享锁：放在只回滚事务里，保证读的过程不留下任何写入。
        var tx = new TransactionTemplate(services.transactions());
        tx.setTimeout(600);
        return tx.execute(
                status -> {
                    status.setRollbackOnly();
                    return action.get();
                });
    }

    ReferenceConstantMigrationReport dryRun() {
        List<Row> rows = readOnly(() -> scan(null));
        return report(DRY_RUN, rows, List.of());
    }

    private ReferenceConstantMigrationReport report(
            String command, List<Row> rows, List<ObjectStep> steps) {
        return new ReferenceConstantMigrationReport(
                TOOL,
                FORMAT_VERSION,
                command,
                OffsetDateTime.now().toString(),
                summarize(rows),
                List.copyOf(rows),
                List.copyOf(steps));
    }

    // ── 写 ──

    ReferenceConstantMigrationReport apply(ReferenceConstantMigrationReport report, long actor) {
        require(report, DRY_RUN, actor);
        return write(report, actor, true);
    }

    ReferenceConstantMigrationReport rollback(ReferenceConstantMigrationReport report, long actor) {
        require(report, APPLY, actor);
        return write(report, actor, false);
    }

    private static void require(
            ReferenceConstantMigrationReport report, String command, long actor) {
        if (actor <= 0) throw new IllegalArgumentException("--actor 必须是有对象设计权限的用户 ID");
        if (report == null
                || !TOOL.equals(report.tool())
                || report.formatVersion() != FORMAT_VERSION)
            throw new IllegalArgumentException("报告不是本工具产出的");
        if (!command.equals(report.command()))
            throw new IllegalArgumentException("这里要的是 " + command + " 的结果，报告是 " + report.command());
    }

    /** forward：apply（CONVERT → CONVERTED）；否则 rollback（CONVERTED → RESTORED）。逐对象处理，一个对象失败不影响其它对象。 */
    private ReferenceConstantMigrationReport write(
            ReferenceConstantMigrationReport report, long actor, boolean forward) {
        String wanted = forward ? CONVERT : CONVERTED;
        Map<String, List<Row>> byObject = new LinkedHashMap<>();
        for (var row : report.rows())
            byObject.computeIfAbsent(row.objectId(), k -> new ArrayList<>()).add(row);
        List<Row> result = new ArrayList<>();
        List<ObjectStep> steps = new ArrayList<>();
        for (var entry : byObject.entrySet()) {
            var rows = entry.getValue();
            if (rows.stream().noneMatch(r -> wanted.equals(r.status()))) {
                result.addAll(rows);
                continue;
            }
            var ready = new ArrayList<Row>();
            var checked = check(entry.getKey(), rows, wanted, forward, ready);
            if (ready.isEmpty()) {
                result.addAll(checked);
                continue;
            }
            try {
                String objectId = entry.getKey(), objectName = rows.getFirst().objectName();
                // 回滚写回的是转换前的存量值（多半是名称文本），对象设计保存现在会拒绝它：只在这一步让「引用字段固定值」这一项校验让路，其余校验照常。
                steps.add(
                        forward
                                ? publish(objectId, objectName, ready, true, actor)
                                : FieldRuleValidator.restoringStoredConstants(
                                        () -> publish(objectId, objectName, ready, false, actor)));
                for (var row : checked)
                    result.add(
                            ready.contains(row)
                                    ? row.status(forward ? CONVERTED : RESTORED, null)
                                    : row);
            } catch (RuntimeException e) {
                String reason =
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                steps.add(
                        new ObjectStep(
                                entry.getKey(),
                                rows.getFirst().objectName(),
                                "FAILED",
                                null,
                                null,
                                List.of(reason),
                                List.of()));
                for (var row : checked)
                    result.add(ready.contains(row) ? row.status(FAILED, reason) : row);
            }
        }
        return report(forward ? APPLY : ROLLBACK, result, steps);
    }

    /** apply：按当前发布版重算，与报告逐行比对，判定一致的进 ready；rollback：当前值仍是转换后的 ID 才进 ready。其余 wanted 行记 CONFLICT。 */
    private List<Row> check(
            String objectId, List<Row> rows, String wanted, boolean forward, List<Row> ready) {
        var current = services.objects().getVersion(objectId, null).definition();
        Map<String, Row> fresh = new HashMap<>();
        if (forward)
            for (var row : readOnly(() -> scan(Set.of(objectId)))) fresh.put(row.key(), row);
        List<Row> result = new ArrayList<>();
        for (var row : rows) {
            if (!wanted.equals(row.status())) {
                result.add(row);
                continue;
            }
            var now = fresh.get(row.key());
            boolean same =
                    forward
                            ? row.samePlan(now) && CONVERT.equals(now.status())
                            : row.after().equals(ReferenceConstantEdits.value(current, row));
            if (same) {
                ready.add(row);
                result.add(row);
            } else
                result.add(
                        row.status(
                                CONFLICT,
                                forward
                                        ? "现状与报告不一致（报告之后配置或目标记录变了），请重新 dry-run"
                                        : "当前值已不是转换后的记录 ID，未改回"));
        }
        return result;
    }

    /** 以已发布版为底开草稿、改条件值、保存并发布；返回发布结果与仍固定旧版本的应用。 */
    private ObjectStep publish(
            String objectId, String objectName, List<Row> rows, boolean forward, long actor) {
        String reason = forward ? APPLY_REASON : ROLLBACK_REASON;
        var latest = services.objects().getVersion(objectId, null);
        var verdict =
                ObjectRuleMigrationDrafts.judge(
                        services.designs(), services.transactions(), objectId);
        if (verdict != null && !verdict.identical())
            throw new IllegalStateException(
                    "对象「"
                            + objectName
                            + "」有未发布草稿 v"
                            + verdict.draftVersion()
                            + " 且与已发布版本不一致：请先处理草稿后重新 dry-run");
        var current = services.designs().get(objectId);
        var design =
                services.designs()
                        .editPublished(
                                new DataCenter.Revision(
                                        objectId, current.draft().lockVersion(), reason),
                                actor);
        var edited =
                ReferenceConstantEdits.apply(
                        design.fieldOptions(), design.details(), rows, forward);
        var draft = design.draft();
        var saved =
                services.designs()
                        .save(
                                new DataCenter.SaveDesign(
                                        new SaveObjectDraft(
                                                draft.id(),
                                                draft.lockVersion(),
                                                draft.objectCode(),
                                                draft.objectName(),
                                                draft.description(),
                                                draft.tableName(),
                                                draft.titleFieldId(),
                                                draft.fields(),
                                                List.of(),
                                                null,
                                                draft.category()),
                                        design.settings(),
                                        edited.main(),
                                        design.relations(),
                                        design.indexes(),
                                        edited.details(),
                                        design.mainBinding()),
                                actor);
        var plan =
                services.publisher()
                        .plan(
                                new DataCenter.Revision(
                                        objectId, saved.draft().lockVersion(), reason),
                                actor);
        var blocking = plan.checks().stream().filter(DataCenter.Check::blocking).toList();
        if (!blocking.isEmpty())
            throw new IllegalStateException(
                    "发布计划被阻断：" + blocking.stream().map(DataCenter.Check::message).toList());
        if (!plan.applicationUpgrades().isEmpty())
            throw new IllegalStateException("发布会要求暂停仍固定旧版本的应用（本改动应当兼容，不该出现），未发布");
        var execution =
                services.publisher().execute(new DataCenter.ExecutePlan(plan.id(), reason), actor);
        if (!"SUCCEEDED".equals(execution.state()))
            throw new IllegalStateException("发布失败：" + execution.error());
        var after = services.objects().getVersion(objectId, null);
        for (var row : rows)
            if (!(forward ? row.after() : row.before())
                    .equals(ReferenceConstantEdits.value(after.definition(), row)))
                throw new IllegalStateException("发布后核对失败：" + row.describe() + " 的值未按计划生效");
        List<String> details = new ArrayList<>();
        for (var row : rows)
            details.add(
                    row.describe()
                            + "：「"
                            + (forward ? row.before() : row.after())
                            + "」→「"
                            + (forward ? row.after() : row.before())
                            + "」");
        if (verdict != null) details.add(0, "沿用与已发布版本一致的空草稿 v" + verdict.draftVersion());
        var audit = new LinkedHashMap<String, Object>();
        audit.put("tool", TOOL);
        audit.put("step", forward ? APPLY : ROLLBACK);
        audit.put("versionNo", after.versionNo());
        audit.put("changes", details);
        services.designs()
                .audit(
                        Long.parseLong(objectId),
                        actor,
                        AuditOperationEnum.OBJECT_DRAFT_SAVE.getCode(),
                        audit);
        return new ObjectStep(
                objectId,
                objectName,
                "PUBLISHED",
                latest.versionNo(),
                after.versionNo(),
                details,
                behind(objectId, after.versionNo()));
    }

    /** 发布后仍固定在旧版本的启用应用（关着自动跟随，或跟随没成功）：需要人工同步。 */
    private List<String> behind(String objectId, int version) {
        List<String> result = new ArrayList<>();
        try {
            for (var app :
                    new ObjectRuleMigrationPlanner.JdbcSource(services.jdbc(), json)
                            .applications(""))
                for (var ref : app.published().objects())
                    if (ref.objectId().equals(objectId) && ref.versionNo() < version)
                        result.add(app.name() + "（" + app.code() + "，固定 v" + ref.versionNo() + "）");
        } catch (RuntimeException unreadable) {
            // 对象已发布成功：列不出来只影响报告，不能把这一步记成失败。
            result.add("未能列出仍固定旧版本的应用：" + unreadable.getMessage());
        }
        return result;
    }

    // ── 命令行 ──

    static final String USAGE =
            """
            用法：
              dry-run  [--out report.json]
              apply    --report report.json --actor 用户ID [--out apply.json]
              rollback --report apply.json --actor 用户ID [--out rollback.json]
            退出码：0 成功；2 参数错误（未连库）；3 执行失败（含启动自检失败、有对象发布失败）""";

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        ObjectMapper json = ObjectRuleMigrationTool.mapper();
        ReferenceConstantMigrationCommand command;
        try {
            command = ReferenceConstantMigrationCommand.parse(args, json);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误：" + e.getMessage());
            System.err.println(USAGE);
            return EXIT_USAGE;
        }
        try (var context = ToolProcessGuard.start(ObjectRuleMigrationTool.servicesApplication())) {
            NocodeToolContext.requireSingleDatabase(context.getBean(javax.sql.DataSource.class));
            var tool = new ReferenceConstantMigrationTool(Services.of(context), json);
            var result =
                    switch (command.name()) {
                        case APPLY -> tool.apply(command.report(), command.actor());
                        case ROLLBACK -> tool.rollback(command.report(), command.actor());
                        default -> tool.dryRun();
                    };
            json.writerWithDefaultPrettyPrinter()
                    .writeValue(java.nio.file.Path.of(command.out()).toFile(), result);
            System.out.println(json.writeValueAsString(result.summary()));
            return result.objects().stream().anyMatch(s -> "FAILED".equals(s.action()))
                    ? EXIT_FAILED
                    : EXIT_OK;
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("执行失败：" + e.getMessage());
            return EXIT_FAILED;
        }
    }
}
