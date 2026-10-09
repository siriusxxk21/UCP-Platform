package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.ObjectRuleMigrationForms.*;
import static com.lingan.ucp.nocode.tools.ObjectRuleMigrationReport.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.enums.AuditOperationEnum;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDesignService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.rules.FieldRuleEvaluator;
import com.lingan.ucp.nocode.runtime.service.rules.RuleContext;
import com.lingan.ucp.nocode.schema.service.publish.SchemaPublishService;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;

import javax.sql.DataSource;

/**
 * 表单「关联带入」一次性迁到数据对象·数据联动，并清理选项类默认值（设计稿 9、15.5）。
 *
 * <pre>
 * dry-run [--prefix P] [--out report.json]                          只读，输出迁移报告（默认子命令）
 * apply    --report report.json --actor 用户ID [--out apply.json]      按报告写对象规则、发布对象、升级并发布受影响应用
 *          清理选项类默认值属于平台契约里的不兼容变更：对象发布时按平台流程暂停仍固定旧版本的应用，
 *          本工具随即把它们同步到新版本并发布启用（--actor 需有应用管理权限）；应用不在报告范围内或暂不能暂停时中止
 * rollback --report report.json --actor 用户ID [--out rollback.json]   恢复应用的对象固定版本与表单节点，发布应用
 * compare  --report report.json --actor 用户ID [--sample 50] [--out compare.json]
 *          旧「关联带入」与新数据联动逐字段对比；差异为 0 才允许在生产 apply
 * linkage-readonly --object-codes a,b --actor 用户ID [--dry-run] [--out linkage-readonly.json]
 *          只读口径变更（2026-09-29）：列出对象上 readOnly=false 的数据联动改为只读，发布对象并同步引用它们的应用
 * </pre>
 *
 * <p>四个子命令都拉起正式业务装配（懒加载），经 {@link ToolProcessGuard} 启动：工具进程里不启动任何后台作业。dry-run 在只读事务里查询，
 * 另用对象设计服务判断对象上的未发布草稿是否与已发布版本一致（{@link ObjectRuleMigrationDrafts}，设计稿 9.2 空草稿）；其余子命令经正式业务服务写入，
 * 校验、发布与授权口径与界面操作一致。启动方式见 deploy/object-rule-migration.sh。
 */
public class ObjectRuleMigrationTool {
    static final String DRY_RUN = "dry-run", APPLY = "apply", ROLLBACK = "rollback";
    static final String COMPARE = "compare";
    static final String LINKAGE_READONLY = "linkage-readonly";
    static final String LINKAGE_READONLY_REASON = "只读口径变更（2026-09-29）：数据联动改为只读";
    static final String APPLY_REASON = "对象规则迁移：表单关联带入迁至数据对象·数据联动，清理选项类默认值";
    static final String ROLLBACK_REASON = "对象规则迁移回滚：恢复对象固定版本与表单关联带入";
    static final int DEFAULT_SAMPLE = 50;
    private static final java.util.regex.Pattern PREFIX =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_]*");

    /** 退出码：0 成功（compare 差异为 0）；1 compare 有差异；2 参数错误（未连库）；3 执行失败（含启动自检失败）。 */
    static final int EXIT_OK = 0, EXIT_DIFFERENCES = 1, EXIT_USAGE = 2, EXIT_FAILED = 3;

    /** 写入与对比用的正式业务服务；dry-run 只需要 jdbc、transactions 与 designs（只读判断草稿）。 */
    record Services(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            ObjectDesignService designs,
            SchemaPublishService publisher,
            DataObjectApi objects,
            ApplicationService applications,
            ObjectSharingService sharing,
            RecordService records,
            FieldRuleEvaluator evaluator) {
        static Services of(ApplicationContext context) {
            return new Services(
                    context.getBean(JdbcTemplate.class),
                    context.getBean(PlatformTransactionManager.class),
                    context.getBean(ObjectDesignService.class),
                    context.getBean(SchemaPublishService.class),
                    context.getBean(DataObjectApi.class),
                    context.getBean(ApplicationService.class),
                    context.getBean(ObjectSharingService.class),
                    context.getBean(RecordService.class),
                    context.getBean(FieldRuleEvaluator.class));
        }

        static Services readOnly(ApplicationContext context) {
            return new Services(
                    context.getBean(JdbcTemplate.class),
                    context.getBean(PlatformTransactionManager.class),
                    context.getBean(ObjectDesignService.class),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null);
        }
    }

    /** apply / rollback 的逐步结果：action 为 PUBLISHED 或 UNCHANGED。 */
    public record Step(
            String kind,
            String id,
            String name,
            String action,
            Integer versionBefore,
            Integer versionAfter,
            List<String> details) {}

    public record Run(String command, String executedAt, List<Step> steps) {}

    public record Difference(
            String applicationCode,
            String formId,
            String objectId,
            String sourceRecordId,
            String targetFieldId,
            String targetFieldName,
            Object oldValue,
            String newState,
            Object newValue,
            String message) {}

    /** mode：RUNTIME 为应用固定版本上已生效的规则；PLANNED 为按报告在内存中套用的计划规则（迁移前基线）。 */
    public record Comparison(
            String formId, String applicationCode, String mode, int records, int fields) {}

    public record CompareResult(
            String executedAt,
            int sampleSize,
            List<Comparison> comparisons,
            int differences,
            List<Difference> diffs) {}

    private final Services services;
    private final ObjectMapper json;

    ObjectRuleMigrationTool(Services services, ObjectMapper json) {
        this.services = services;
        this.json = json;
    }

    /** 报告与表单树的 JSON：容忍快照里的新增属性，与平台读取快照的口径一致。 */
    static ObjectMapper mapper() {
        return JsonMapper.builder()
                .findAndAddModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    // ── dry-run ──

    ObjectRuleMigrationReport dryRun(String prefix) {
        var tx = new TransactionTemplate(services.transactions());
        tx.setReadOnly(true);
        tx.setTimeout(300);
        return tx.execute(
                status -> {
                    services.jdbc().execute("SET TRANSACTION READ ONLY");
                    return new ObjectRuleMigrationPlanner(
                                    new ObjectRuleMigrationPlanner.JdbcSource(
                                            services.jdbc(), json),
                                    id ->
                                            ObjectRuleMigrationDrafts.judge(
                                                    services.designs(),
                                                    services.transactions(),
                                                    id),
                                    json)
                            .plan(normalizePrefix(prefix));
                });
    }

    /**
     * 编码前缀按小写比较：对象与应用编码只允许 {@code [a-z][a-z0-9_]*}，库里没有大写编码，转小写不会多匹配；而 LIKE 区分大小写，不转换时 {@code SMK_}
     * 永远匹配 0 条、报告静默为空。转小写后仍不合编码规则的前缀同样不可能匹配，直接报错。
     */
    static String normalizePrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return "";
        var lower = prefix.toLowerCase(Locale.ROOT);
        if (!PREFIX.matcher(lower).matches())
            throw new IllegalArgumentException(
                    "--prefix 须是编码的开头一段：字母开头，其后为字母、数字或下划线（按小写比较），实际为「" + prefix + "」");
        return lower;
    }

    // ── apply ──

    Run apply(ObjectRuleMigrationReport report, long actor) {
        requireReport(report, actor);
        if (!report.blockers().isEmpty())
            throw new IllegalStateException("报告含阻断项，处理后重新 dry-run：" + report.blockers());
        var steps = new ArrayList<Step>();
        // 对象 → 报告里会被同步到该对象新版本的应用：对象发布只允许暂停这些应用，暂停后由下面的应用步骤发布启用。
        Map<String, Set<String>> synced = new HashMap<>();
        for (var app : report.applications())
            for (var id : app.upgradeObjectIds())
                synced.computeIfAbsent(id, k -> new HashSet<>()).add(app.applicationId());
        var paused = new Paused(new LinkedHashMap<>());
        for (var change : report.objects())
            steps.add(
                    applyObject(
                            change,
                            synced.getOrDefault(change.objectId(), Set.of()),
                            paused,
                            actor));
        for (var change : report.applications())
            steps.add(applyApplication(change, paused.before().get(change.applicationId()), actor));
        return new Run(APPLY, OffsetDateTime.now().toString(), steps);
    }

    /**
     * 本次运行里随对象发布被暂停的应用，值是暂停前的已发布定义（暂停后平台不再给出运行定义）。清理选项类默认值在平台契约里属于
     * 不兼容变更（ObjectContracts），对象发布要求确认暂停仍固定旧版本的应用；应用步骤同步到新版本后用「发布启用」恢复。
     */
    private record Paused(Map<String, ApplicationCenter.Published> before) {}

    private Step applyObject(
            ObjectChange change, Set<String> syncedApplications, Paused paused, long actor) {
        return publishObject(
                change.objectId(),
                change.objectName(),
                (main, details) -> objectEdits(main, details, change),
                ObjectRuleMigrationTool::describe,
                APPLY,
                APPLY_REASON,
                syncedApplications,
                paused,
                actor);
    }

    /** 对象上还要改的字段扩展属性：键为明细 ID（主表为 null）；空结果即无需改动（幂等）。 */
    @FunctionalInterface
    interface ObjectEdits {
        Map<String, Map<String, DataCenter.FieldOptions>> of(
                Map<String, DataCenter.FieldOptions> main, List<DataCenter.Detail> details);
    }

    /** 以已发布版本为底改对象并发布：没有要改的返回 UNCHANGED、不产生新版本；有未发布草稿时沿用与已发布版本一致的空草稿，不一致就拒绝；发布后核对并写审计。 */
    private Step publishObject(
            String objectId,
            String objectName,
            ObjectEdits plan,
            Function<Map<String, Map<String, DataCenter.FieldOptions>>, List<String>> describer,
            String step,
            String reason,
            Set<String> syncedApplications,
            Paused paused,
            long actor) {
        var latest = services.objects().getVersion(objectId, null);
        var pending = plan.of(latest.definition().fieldOptions(), latest.definition().details());
        if (pending.isEmpty())
            return new Step(
                    "OBJECT",
                    objectId,
                    objectName,
                    "UNCHANGED",
                    latest.versionNo(),
                    latest.versionNo(),
                    List.of());
        // 先取修订号再判断草稿：判断之后若有人再改草稿，下面按该修订号开草稿会因修订号变化而失败，不会写到改过的草稿上。
        var current = services.designs().get(objectId);
        var reused = reusableDraft(objectId, objectName);
        var design =
                services.designs()
                        .editPublished(
                                new DataCenter.Revision(
                                        objectId, current.draft().lockVersion(), reason),
                                actor);
        var edits = plan.of(design.fieldOptions(), design.details());
        var options = new LinkedHashMap<>(design.fieldOptions());
        options.putAll(edits.getOrDefault(null, Map.of()));
        var details =
                design.details().stream()
                        .map(t -> withOptions(t, edits.getOrDefault(t.id(), Map.of())))
                        .toList();
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
                                        options,
                                        design.relations(),
                                        design.indexes(),
                                        details,
                                        design.mainBinding()),
                                actor);
        var publishPlan =
                services.publisher()
                        .plan(
                                new DataCenter.Revision(
                                        objectId, saved.draft().lockVersion(), reason),
                                actor);
        var blocking = publishPlan.checks().stream().filter(DataCenter.Check::blocking).toList();
        if (!blocking.isEmpty())
            throw new IllegalStateException(
                    "对象「"
                            + objectName
                            + "」发布计划被阻断："
                            + blocking.stream().map(DataCenter.Check::message).toList());
        var pausing = pausing(objectName, publishPlan.applicationUpgrades(), syncedApplications);
        // 暂停之后平台不再给出应用的运行定义：趁应用还在运行先记下，应用步骤据此同步。
        for (var impact : pausing)
            paused.before()
                    .put(
                            impact.applicationId(),
                            services.applications().published(impact.applicationId()));
        var execution =
                services.publisher()
                        .execute(
                                new DataCenter.ExecutePlan(
                                        publishPlan.id(),
                                        reason,
                                        List.of(),
                                        pausing.stream()
                                                .map(ObjectApplicationUpgrade.Impact::applicationId)
                                                .toList()),
                                actor);
        if (!"SUCCEEDED".equals(execution.state()))
            throw new IllegalStateException("对象「" + objectName + "」发布失败：" + execution.error());
        var after = services.objects().getVersion(objectId, null);
        if (!plan.of(after.definition().fieldOptions(), after.definition().details()).isEmpty())
            throw new IllegalStateException("对象「" + objectName + "」发布后核对失败：规则或默认值未按计划生效");
        var detail = new ArrayList<>(describer.apply(edits));
        if (!pausing.isEmpty())
            detail.add(
                    "按平台契约暂停仍固定旧版本的应用（随后同步并发布启用）："
                            + pausing.stream()
                                    .map(ObjectApplicationUpgrade.Impact::applicationName)
                                    .toList());
        if (reused != null)
            detail.add(
                    0,
                    "沿用与已发布版本 v" + reused.publishedVersion() + " 一致的空草稿 v" + reused.draftVersion());
        var audit = new LinkedHashMap<String, Object>();
        audit.put("tool", TOOL);
        audit.put("step", step);
        audit.put("versionNo", after.versionNo());
        audit.put("changes", detail);
        if (reused != null) audit.put("reusedDraftVersion", reused.draftVersion());
        services.designs()
                .audit(
                        Long.parseLong(objectId),
                        actor,
                        AuditOperationEnum.OBJECT_DRAFT_SAVE.getCode(),
                        audit);
        return new Step(
                "OBJECT",
                objectId,
                objectName,
                "PUBLISHED",
                latest.versionNo(),
                after.versionNo(),
                detail);
    }

    /** 对象发布按平台契约要暂停的应用。只接受「报告里随后会同步到该对象新版本」且当前可以暂停的应用；否则中止， 不把报告范围之外的应用停掉，也不留下工具自己恢复不了的暂停。 */
    private static List<ObjectApplicationUpgrade.Impact> pausing(
            String objectName,
            List<ObjectApplicationUpgrade.Impact> impacts,
            Set<String> syncedApplications) {
        var blocked =
                impacts.stream()
                        .filter(i -> !i.blockers().isEmpty())
                        .map(i -> "「" + i.applicationName() + "」" + i.blockers())
                        .toList();
        if (!blocked.isEmpty())
            throw new IllegalStateException(
                    "对象「" + objectName + "」发布需要先暂停仍固定旧版本的应用，但这些应用暂时不能暂停：" + blocked);
        var outside =
                impacts.stream()
                        .filter(i -> !syncedApplications.contains(i.applicationId()))
                        .map(i -> "「" + i.applicationName() + "」" + i.reasons())
                        .toList();
        if (!outside.isEmpty())
            throw new IllegalStateException(
                    "对象「"
                            + objectName
                            + "」发布会打断不在本次迁移同步范围内的应用："
                            + outside
                            + "；请先在界面适配这些应用，或把它们纳入范围后重新 dry-run");
        return impacts;
    }

    /**
     * apply 前再判一次草稿（dry-run 之后草稿可能被开出或被改过）：没有草稿返回 null；与已发布版本一致的空草稿返回判断结果，随后 {@code editPublished}
     * 按系统既有语义直接返回这份草稿（不另开、不放弃）；不一致就拒绝，不按旧报告硬写。
     */
    private ObjectRuleMigrationDrafts.Verdict reusableDraft(String objectId, String objectName) {
        var verdict =
                ObjectRuleMigrationDrafts.judge(
                        services.designs(), services.transactions(), objectId);
        if (verdict != null && !verdict.identical())
            throw new IllegalStateException(
                    "对象「"
                            + objectName
                            + "」有未发布草稿 v"
                            + verdict.draftVersion()
                            + "，且与已发布版本不一致（dry-run 之后被改动），迁移中止：请处理草稿后重新 dry-run");
        return verdict;
    }

    /**
     * 对象上还要改的字段扩展属性：键为明细 ID（主表为 null）。已按计划的字段不再出现，因此空结果即幂等。 目标字段在 dry-run 之后被改成别的值来源时中止，避免覆盖人工配置。
     */
    private static Map<String, Map<String, DataCenter.FieldOptions>> objectEdits(
            Map<String, DataCenter.FieldOptions> main,
            List<DataCenter.Detail> details,
            ObjectChange change) {
        Map<String, Map<String, DataCenter.FieldOptions>> edits = new HashMap<>();
        for (var planned : change.linkages()) {
            var o = main.getOrDefault(planned.fieldId(), DataCenter.FieldOptions.defaults());
            var rules = o.rules();
            // 只读开关不算差异：linkage-readonly 改成只读后重跑 apply 不改回可编辑（2026-09-29）。
            if (rules != null
                    && ObjectRuleMigrationPlanner.sameIgnoringReadOnly(
                            rules.linkage(), planned.linkage())) continue;
            if (rules != null && (rules.linkage() != null || rules.defaultFormula() != null)
                    || o.defaultValue() != null)
                throw new IllegalStateException(
                        "对象「"
                                + change.objectName()
                                + "」字段「"
                                + planned.fieldName()
                                + "」在 dry-run 之后已配置其它值来源，请重新 dry-run");
            var merged =
                    new FieldRules(
                            rules == null ? null : rules.reference(),
                            planned.linkage(),
                            null,
                            null,
                            null,
                            null);
            edits.computeIfAbsent(null, k -> new LinkedHashMap<>())
                    .put(planned.fieldId(), o.withRules(merged));
        }
        for (var d : change.optionDefaults()) {
            var owner =
                    d.detailId() == null
                            ? main
                            : details.stream()
                                    .filter(t -> t.id().equals(d.detailId()))
                                    .findFirst()
                                    .map(DataCenter.Detail::fieldOptions)
                                    .orElse(Map.of());
            var o = owner.get(d.fieldId());
            if (o == null || o.defaultValue() == null) continue;
            edits.computeIfAbsent(d.detailId(), k -> new LinkedHashMap<>())
                    .put(d.fieldId(), DataCenter.FieldOptions.copyOf(o).defaultValue(null).build());
        }
        return edits;
    }

    private static DataCenter.Detail withOptions(
            DataCenter.Detail t, Map<String, DataCenter.FieldOptions> changes) {
        if (changes.isEmpty()) return t;
        var options = new LinkedHashMap<>(t.fieldOptions());
        options.putAll(changes);
        return new DataCenter.Detail(
                t.id(),
                t.code(),
                t.name(),
                t.tableName(),
                t.state(),
                t.fields(),
                options,
                t.indexes(),
                t.binding());
    }

    private static List<String> describe(Map<String, Map<String, DataCenter.FieldOptions>> edits) {
        var result = new ArrayList<String>();
        edits.forEach(
                (detail, fields) ->
                        fields.forEach(
                                (id, o) ->
                                        result.add(
                                                (detail == null ? "" : "明细 " + detail + " · ")
                                                        + "字段 "
                                                        + id
                                                        + (o.rules() != null
                                                                        && o.rules().linkage()
                                                                                != null
                                                                ? "：写入数据联动"
                                                                : "：清除选项类默认值"))));
        Collections.sort(result);
        return result;
    }

    private Step applyApplication(
            ApplicationChange change, ApplicationCenter.Published pausedBefore, long actor) {
        Map<String, ApplicationCenter.ObjectReference> latest = new HashMap<>();
        for (var id : change.upgradeObjectIds()) {
            var v = services.objects().getVersion(id, null);
            latest.put(id, new ApplicationCenter.ObjectReference(id, v.versionNo(), v.checksum()));
        }
        Map<String, Form> forms = new HashMap<>();
        for (var form : change.forms()) forms.put(form.formId(), form);
        return publishApplication(
                change.applicationId(),
                change.applicationName(),
                definition -> migrate(definition, latest, forms, change),
                APPLY_REASON,
                pausedBefore,
                actor);
    }

    /** 升级固定版本、去掉计划内的带入与选项类默认值；返回 null 表示无需改动。 */
    private ApplicationCenter.Definition migrate(
            ApplicationCenter.Definition definition,
            Map<String, ApplicationCenter.ObjectReference> latest,
            Map<String, Form> forms,
            ApplicationChange change) {
        boolean changed = false;
        var objects = new ArrayList<ApplicationCenter.ObjectReference>();
        for (var ref : definition.objects()) {
            var next = latest.getOrDefault(ref.objectId(), ref);
            changed |= !next.equals(ref);
            objects.add(next);
        }
        var resources = new ArrayList<ApplicationCenter.Resource>();
        for (var resource : definition.resources()) {
            var form = FORM.equals(resource.kind()) ? forms.get(resource.id()) : null;
            if (form == null) {
                resources.add(resource);
                continue;
            }
            JsonNode tree = json.valueToTree(resource.config());
            var nodes = fieldNodes(tree);
            boolean touched = false;
            for (var key : form.stripFillNodes()) {
                var node = nodes.get(key);
                if (node == null || fill(node) == null) continue;
                var before = json.valueToTree(form.nodesBefore().get(key));
                if (!Objects.equals(fill(node), fill(before)))
                    throw new IllegalStateException(
                            "应用「"
                                    + change.applicationName()
                                    + "」表单「"
                                    + form.formName()
                                    + "」的关联带入在 dry-run 之后被改动，请重新 dry-run");
                touched |= stripFill(node);
            }
            for (var d : form.optionDefaults()) {
                var node = nodes.get(d.nodeKey());
                if (node != null) touched |= stripSelectionDefault(node);
            }
            changed |= touched;
            resources.add(touched ? withConfig(resource, tree) : resource);
        }
        return changed ? new ApplicationCenter.Definition(objects, resources) : null;
    }

    // ── rollback ──

    Run rollback(ObjectRuleMigrationReport report, long actor) {
        requireReport(report, actor);
        // 清理过选项类默认值的对象，新版本已删掉列上的 DEFAULT：旧固定版本与当前表结构不兼容，不回退固定版本（D8，不可逆）。
        var keepPinned = new HashSet<String>();
        for (var object : report.objects())
            if (!object.optionDefaults().isEmpty()) keepPinned.add(object.objectId());
        var steps = new ArrayList<Step>();
        for (var change : report.applications()) {
            var step =
                    publishApplication(
                            change,
                            definition -> restore(definition, change, keepPinned),
                            ROLLBACK_REASON,
                            actor);
            var kept =
                    change.upgradeObjectIds().stream()
                            .filter(keepPinned::contains)
                            .map(id -> "对象 " + id + " 清理过选项类默认值，固定版本不回退")
                            .toList();
            if (!kept.isEmpty()) {
                var details = new ArrayList<>(step.details());
                details.addAll(kept);
                step =
                        new Step(
                                step.kind(),
                                step.id(),
                                step.name(),
                                step.action(),
                                step.versionBefore(),
                                step.versionAfter(),
                                details);
            }
            steps.add(step);
        }
        return new Run(ROLLBACK, OffsetDateTime.now().toString(), steps);
    }

    /**
     * 固定版本改回迁移前；表单节点换回迁移前 JSON。选项类默认值不回滚：发布校验已禁止（设计稿 D8）， 这类节点恢复后仍去掉
     * selection.defaultValue，清理过对象默认值的对象（keepPinned）不回退固定版本。 对象上的新版本保留（对象版本不可变）。
     */
    private ApplicationCenter.Definition restore(
            ApplicationCenter.Definition definition,
            ApplicationChange change,
            Set<String> keepPinned) {
        Map<String, ApplicationCenter.ObjectReference> before = new HashMap<>();
        for (var ref : change.objectsBefore())
            if (change.upgradeObjectIds().contains(ref.objectId())
                    && !keepPinned.contains(ref.objectId())) before.put(ref.objectId(), ref);
        boolean changed = false;
        var objects = new ArrayList<ApplicationCenter.ObjectReference>();
        for (var ref : definition.objects()) {
            var next = before.getOrDefault(ref.objectId(), ref);
            changed |= !next.equals(ref);
            objects.add(next);
        }
        Map<String, Form> forms = new HashMap<>();
        for (var form : change.forms()) forms.put(form.formId(), form);
        var resources = new ArrayList<ApplicationCenter.Resource>();
        for (var resource : definition.resources()) {
            var form = FORM.equals(resource.kind()) ? forms.get(resource.id()) : null;
            if (form == null) {
                resources.add(resource);
                continue;
            }
            JsonNode tree = json.valueToTree(resource.config());
            var nodes = fieldNodes(tree);
            var keepDefaultsOff = new HashSet<String>();
            form.optionDefaults().forEach(d -> keepDefaultsOff.add(d.nodeKey()));
            boolean touched = false;
            for (var entry : form.nodesBefore().entrySet()) {
                var node = nodes.get(entry.getKey());
                if (node == null) continue;
                ObjectNode target = json.valueToTree(entry.getValue());
                if (keepDefaultsOff.contains(entry.getKey())) stripSelectionDefault(target);
                touched |= ObjectRuleMigrationForms.restore(node, target);
            }
            changed |= touched;
            resources.add(touched ? withConfig(resource, tree) : resource);
        }
        return changed ? new ApplicationCenter.Definition(objects, resources) : null;
    }

    // ── linkage-readonly（2026-09-29 只读口径变更） ──

    /**
     * 把列出对象上 {@code rules.linkage.readOnly == false} 的数据联动改为只读（null
     * 不动：新口径下已是只读），发布对象；再把仍固定在含可编辑联动版本上的应用 升级到对象最新发布版本并发布（与 apply
     * 同口径：以已发布定义为底、草稿做同样改动、恢复共享授权）。已是只读的对象 UNCHANGED，不产生新版本；dryRun 只读， 列出将改的字段（PLANNED）与将同步的应用。
     */
    Run linkageReadOnly(List<String> objectCodes, long actor, boolean dryRun) {
        if (actor <= 0) throw new IllegalArgumentException("--actor 必须是有对象与应用设计权限的用户 ID");
        if (!dryRun) return linkageReadOnlySteps(objectCodes, actor, false);
        // 对象设计与应用读取会对头记录加共享锁（FOR SHARE），PostgreSQL 不允许在只读事务里执行；同 ObjectRuleMigrationDrafts，
        // dry-run 放在只回滚事务里：本身只调读取接口，只回滚再保证即便有写入也不会留下。
        var tx = new TransactionTemplate(services.transactions());
        tx.setTimeout(300);
        return tx.execute(
                status -> {
                    status.setRollbackOnly();
                    return linkageReadOnlySteps(objectCodes, actor, true);
                });
    }

    private Run linkageReadOnlySteps(List<String> objectCodes, long actor, boolean dryRun) {
        var targets = resolveObjects(objectCodes);
        var steps = new ArrayList<Step>();
        Set<String> planned = new HashSet<>();
        for (var target : targets.entrySet()) {
            var step = readOnlyObject(target.getKey(), target.getValue(), actor, dryRun);
            if ("PLANNED".equals(step.action())) planned.add(step.id());
            steps.add(step);
        }
        var source = new ObjectRuleMigrationPlanner.JdbcSource(services.jdbc(), json);
        for (var app : source.applications("")) {
            if (app.published().objects().stream()
                    .noneMatch(r -> targets.containsKey(r.objectId()))) continue;
            steps.add(
                    readOnlyApplication(
                            app.id(), app.name(), targets.keySet(), planned, actor, dryRun));
        }
        return new Run(LINKAGE_READONLY, OffsetDateTime.now().toString(), steps);
    }

    /** 对象编码 → 对象 ID（按参数顺序）；找不到或未发布的编码一次列全后中止，不做任何改动。 */
    private Map<String, String> resolveObjects(List<String> codes) {
        Map<String, String> result = new LinkedHashMap<>();
        var missing = new ArrayList<String>();
        for (var code : codes) {
            var rows =
                    services.jdbc()
                            .queryForList(
                                    "SELECT o.id::text AS id, o.object_name AS name FROM"
                                        + " public.nocode_object o WHERE o.deleted=0 AND"
                                        + " o.status='ACTIVE' AND o.current_published_version_no IS"
                                        + " NOT NULL AND o.object_code=?",
                                    code);
            if (rows.size() != 1) missing.add(code);
            else
                result.put(
                        (String) rows.getFirst().get("id"), (String) rows.getFirst().get("name"));
        }
        if (!missing.isEmpty())
            throw new IllegalStateException("找不到已发布的对象（编码）：" + String.join("、", missing));
        return result;
    }

    private Step readOnlyObject(String objectId, String objectName, long actor, boolean dryRun) {
        if (!dryRun)
            return publishObject(
                    objectId,
                    objectName,
                    ObjectRuleMigrationTool::readOnlyEdits,
                    ObjectRuleMigrationTool::describeReadOnly,
                    LINKAGE_READONLY,
                    LINKAGE_READONLY_REASON,
                    // 只读开关不在平台的不兼容变更之列，不会要求暂停应用；万一要求，一律中止而不是停掉应用。
                    Set.of(),
                    new Paused(new HashMap<>()),
                    actor);
        var latest = services.objects().getVersion(objectId, null);
        var edits =
                readOnlyEdits(latest.definition().fieldOptions(), latest.definition().details());
        if (edits.isEmpty())
            return new Step(
                    "OBJECT",
                    objectId,
                    objectName,
                    "UNCHANGED",
                    latest.versionNo(),
                    latest.versionNo(),
                    List.of());
        var details = new ArrayList<>(describeReadOnly(edits));
        var verdict =
                ObjectRuleMigrationDrafts.judge(
                        services.designs(), services.transactions(), objectId);
        if (verdict != null)
            details.add(
                    0,
                    verdict.identical()
                            ? "将沿用与已发布版本 v"
                                    + verdict.publishedVersion()
                                    + " 一致的空草稿 v"
                                    + verdict.draftVersion()
                            : "有未发布草稿 v" + verdict.draftVersion() + " 且与已发布版本不一致：执行时将拒绝，请先处理草稿");
        return new Step(
                "OBJECT", objectId, objectName, "PLANNED", latest.versionNo(), null, details);
    }

    /** 主表与各明细上 readOnly 显式为 false 的数据联动改为 true；其余配置原样保留，存储快照里的投影字段仍为 null。 */
    static Map<String, Map<String, DataCenter.FieldOptions>> readOnlyEdits(
            Map<String, DataCenter.FieldOptions> main, List<DataCenter.Detail> details) {
        Map<String, Map<String, DataCenter.FieldOptions>> edits = new LinkedHashMap<>();
        readOnlyEdits(null, main, edits);
        for (var detail : details == null ? List.<DataCenter.Detail>of() : details)
            readOnlyEdits(detail.id(), detail.fieldOptions(), edits);
        return edits;
    }

    private static void readOnlyEdits(
            String detailId,
            Map<String, DataCenter.FieldOptions> options,
            Map<String, Map<String, DataCenter.FieldOptions>> edits) {
        if (options == null) return;
        options.forEach(
                (id, o) -> {
                    var rules = o == null ? null : o.rules();
                    var l = rules == null ? null : rules.linkage();
                    if (l == null || !Boolean.FALSE.equals(l.readOnly())) return;
                    var linkage =
                            new FieldRules.Linkage(
                                    l.sourceObjectId(),
                                    l.conditions(),
                                    l.valueFieldId(),
                                    l.multiRow(),
                                    true,
                                    l.autoUpdate(),
                                    l.emptyValue());
                    edits.computeIfAbsent(detailId, k -> new LinkedHashMap<>())
                            .put(
                                    id,
                                    o.withRules(
                                            new FieldRules(
                                                    rules.reference(),
                                                    linkage,
                                                    rules.defaultFormula(),
                                                    rules.rounding(),
                                                    null,
                                                    null)));
                });
    }

    private static List<String> describeReadOnly(
            Map<String, Map<String, DataCenter.FieldOptions>> edits) {
        var result = new ArrayList<String>();
        edits.forEach(
                (detail, fields) ->
                        fields.keySet()
                                .forEach(
                                        id ->
                                                result.add(
                                                        (detail == null
                                                                        ? ""
                                                                        : "明细 " + detail + " · ")
                                                                + "字段 "
                                                                + id
                                                                + "：数据联动改为只读")));
        Collections.sort(result);
        return result;
    }

    /**
     * 应用上仍固定在「含 readOnly=false 数据联动」版本的目标对象，升级到对象最新发布版本（已发布定义与草稿同样处理）；都已不含时 UNCHANGED（幂等，也可在对象已发布、
     * 应用同步中途失败后重跑补齐）。
     */
    private Step readOnlyApplication(
            String appId,
            String appName,
            Set<String> objectIds,
            Set<String> planned,
            long actor,
            boolean dryRun) {
        Map<String, ApplicationCenter.ObjectReference> latest = new HashMap<>();
        for (var id : objectIds) {
            var v = services.objects().getVersion(id, null);
            latest.put(id, new ApplicationCenter.ObjectReference(id, v.versionNo(), v.checksum()));
        }
        Function<ApplicationCenter.Definition, ApplicationCenter.Definition> edit =
                definition -> upgradeEditable(definition, latest, planned);
        if (!dryRun)
            return publishApplication(appId, appName, edit, LINKAGE_READONLY_REASON, null, actor);
        var apps = services.applications();
        int version = apps.published(appId).versionNo();
        var published = edit.apply(apps.published(appId).definition());
        var draft = edit.apply(apps.get(appId).draft());
        var details = new ArrayList<String>();
        if (published != null) details.add("将升级对象固定版本并发布新版本");
        if (draft != null) details.add("草稿将做同样的固定版本升级");
        return new Step(
                "APPLICATION",
                appId,
                appName,
                published == null && draft == null ? "UNCHANGED" : "PLANNED",
                version,
                null,
                details);
    }

    /** planned：dry-run 中将发布新版本的对象（此时最新版本尚未产生）。 */
    private ApplicationCenter.Definition upgradeEditable(
            ApplicationCenter.Definition definition,
            Map<String, ApplicationCenter.ObjectReference> latest,
            Set<String> planned) {
        boolean changed = false;
        var objects = new ArrayList<ApplicationCenter.ObjectReference>();
        for (var ref : definition.objects()) {
            var next = latest.get(ref.objectId());
            boolean upgrade =
                    next != null
                            && (planned.contains(ref.objectId()) || !next.equals(ref))
                            && editable(ref);
            objects.add(upgrade ? next : ref);
            changed |= upgrade;
        }
        return changed ? new ApplicationCenter.Definition(objects, definition.resources()) : null;
    }

    /** 该固定版本上是否还有 readOnly=false 的数据联动。 */
    private boolean editable(ApplicationCenter.ObjectReference ref) {
        var pinned = services.objects().getVersion(ref.objectId(), ref.versionNo()).definition();
        return !readOnlyEdits(pinned.fieldOptions(), pinned.details()).isEmpty();
    }

    // ── 应用：草稿与发布 ──

    /** 以已发布定义为底改动并发布；应用草稿做同样的改动后保存，草稿里其它未发布修改原样保留、不发布。 两边都无需改动时不产生新版本（幂等）。 */
    private Step publishApplication(
            ApplicationChange change,
            Function<ApplicationCenter.Definition, ApplicationCenter.Definition> edit,
            String reason,
            long actor) {
        return publishApplication(
                change.applicationId(), change.applicationName(), edit, reason, null, actor);
    }

    private Step publishApplication(
            String id,
            String name,
            Function<ApplicationCenter.Definition, ApplicationCenter.Definition> edit,
            String reason,
            ApplicationCenter.Published pausedBefore,
            long actor) {
        var apps = services.applications();
        var detail = apps.get(id);
        // pausedBefore 非空：应用在本次运行里随对象发布被暂停，已发布定义取暂停前记下的那份。
        var publishedBefore = pausedBefore == null ? apps.published(id) : pausedBefore;
        int before = publishedBefore.versionNo();
        var published = edit.apply(publishedBefore.definition());
        var draftBase = detail.draft();
        var draft = edit.apply(draftBase);
        if (pausedBefore != null && published == null)
            throw new IllegalStateException(
                    "应用「" + name + "」已随对象发布被暂停，但没有要同步的改动，工具无法恢复：请在界面适配后发布启用");
        if (published == null && draft == null)
            return new Step("APPLICATION", id, name, "UNCHANGED", before, before, List.of());
        var details = new ArrayList<String>();
        var grants =
                grantsOfChangedObjects(
                        id, publishedBefore.definition(), published, draftBase, draft);
        if (published != null) {
            var saved = saveDraft(detail.application(), published, actor);
            var revision =
                    new ApplicationCenter.Revision(id, saved.application().revision(), reason);
            if (pausedBefore == null) {
                apps.publish(revision, actor);
                details.add("发布新版本");
            } else {
                apps.publishAndEnable(revision, actor);
                details.add("对象发布时按平台契约暂停，已同步到新版本并发布启用");
            }
        }
        var desired = draft == null ? draftBase : draft;
        var current = apps.get(id);
        if (!json.valueToTree(current.draft()).equals(json.valueToTree(desired))) {
            saveDraft(current.application(), desired, actor);
            details.add("草稿保留未发布修改，已做同样的节点改动");
        }
        details.addAll(restoreGrants(id, grants, actor));
        int after = apps.published(id).versionNo();
        return new Step(
                "APPLICATION",
                id,
                name,
                after == before ? "UNCHANGED" : "PUBLISHED",
                before,
                after,
                details);
    }

    /** 同步对象版本会触发平台「引用即授权」，把该对象对本应用的共享授权覆盖为默认上限。迁移不应改变授权：先记下固定版本 要变的对象的现有授权，保存与发布后原样恢复。 */
    private List<ObjectSharing.Grant> grantsOfChangedObjects(
            String app,
            ApplicationCenter.Definition published,
            ApplicationCenter.Definition publishedTarget,
            ApplicationCenter.Definition draft,
            ApplicationCenter.Definition draftTarget) {
        var changed = new HashSet<String>();
        changedRefs(published, publishedTarget, changed);
        changedRefs(draft, draftTarget, changed);
        if (changed.isEmpty()) return List.of();
        return services.sharing().forApplication(app).stream()
                .filter(g -> changed.contains(g.objectId()) && g.permission() != null)
                .toList();
    }

    private static void changedRefs(
            ApplicationCenter.Definition before,
            ApplicationCenter.Definition after,
            Set<String> result) {
        if (after == null) return;
        var old = new HashMap<String, ApplicationCenter.ObjectReference>();
        before.objects().forEach(r -> old.put(r.objectId(), r));
        for (var ref : after.objects())
            if (!ref.equals(old.get(ref.objectId()))) result.add(ref.objectId());
    }

    private List<String> restoreGrants(String app, List<ObjectSharing.Grant> grants, long actor) {
        var result = new ArrayList<String>();
        for (var before : grants) {
            var current =
                    services.sharing().forApplication(app).stream()
                            .filter(g -> g.objectId().equals(before.objectId()))
                            .findFirst()
                            .orElse(null);
            if (current != null && Objects.equals(current.permission(), before.permission()))
                continue;
            services.sharing()
                    .save(
                            new ObjectSharing.Save(
                                    before.objectId(),
                                    app,
                                    current == null ? 0 : current.revision(),
                                    before.permission(),
                                    "对象规则迁移：保持迁移前的共享授权"),
                            actor);
            result.add("对象 " + before.objectId() + " 的共享授权已恢复为迁移前");
        }
        return result;
    }

    private ApplicationCenter.Detail saveDraft(
            ApplicationCenter.Row row, ApplicationCenter.Definition definition, long actor) {
        return services.applications()
                .save(
                        new ApplicationCenter.Save(
                                row.id(),
                                row.revision(),
                                row.code(),
                                row.name(),
                                row.description(),
                                row.icon(),
                                definition,
                                row.category()),
                        actor);
    }

    private ApplicationCenter.Resource withConfig(
            ApplicationCenter.Resource resource, JsonNode tree) {
        return new ApplicationCenter.Resource(
                resource.id(),
                resource.kind(),
                resource.code(),
                resource.name(),
                json.convertValue(tree, new TypeReference<Map<String, Object>>() {}));
    }

    // ── compare ──

    /** 旧带入（迁移前表单快照）与新数据联动逐字段对比；旧值为空对新状态 NO_MATCH 视为一致（9.2）。 */
    CompareResult compare(ObjectRuleMigrationReport report, long actor, int sample) {
        requireReport(report, actor);
        Map<String, List<Fill>> groups = new LinkedHashMap<>();
        for (var f : report.fills())
            if (PLANNED.equals(f.status()) || ALREADY_APPLIED.equals(f.status()))
                groups.computeIfAbsent(
                                f.applicationId() + "|" + f.formId() + "|" + f.sourceFieldId(),
                                k -> new ArrayList<>())
                        .add(f);
        var comparisons = new ArrayList<Comparison>();
        var diffs = new ArrayList<Difference>();
        for (var group : groups.values())
            comparisons.add(compareGroup(report, group, actor, sample, diffs));
        return new CompareResult(
                OffsetDateTime.now().toString(), sample, comparisons, diffs.size(), diffs);
    }

    private Comparison compareGroup(
            ObjectRuleMigrationReport report,
            List<Fill> group,
            long actor,
            int sample,
            List<Difference> diffs) {
        var first = group.getFirst();
        var app =
                report.applications().stream()
                        .filter(a -> a.applicationId().equals(first.applicationId()))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalStateException(
                                                "报告缺少应用：" + first.applicationCode()));
        var form =
                app.forms().stream()
                        .filter(f -> f.formId().equals(first.formId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("报告缺少表单：" + first.formName()));
        var formBefore = json.convertValue(form.configBefore(), ApplicationUi.Form.class);
        var targets = group.stream().map(Fill::targetFieldId).distinct().toList();
        var planned = plannedDefinition(first, group);
        var records =
                services.records()
                        .page(
                                new ApplicationRecords.Query(
                                        first.applicationId(),
                                        first.sourceObjectId(),
                                        1,
                                        sample,
                                        null,
                                        Map.of(),
                                        null,
                                        true,
                                        null,
                                        null),
                                actor)
                        .getList();
        for (var record : records) {
            var old =
                    services.records()
                            .previewFormFill(
                                    new FormFills.PreviewQuery(
                                            new FormFills.Query(
                                                    first.applicationId(),
                                                    first.objectId(),
                                                    first.formId(),
                                                    first.sourceFieldId(),
                                                    record.id()),
                                            app.objectsBefore(),
                                            formBefore),
                                    actor);
            var results = evaluate(first, planned, targets, record.id(), actor);
            for (var f : group) {
                var result =
                        results.stream()
                                .filter(
                                        r ->
                                                r.fieldId().equals(f.targetFieldId())
                                                        && r.rowKey() == null)
                                .findFirst()
                                .orElse(null);
                Object oldValue = old.get(f.targetFieldId());
                if (result != null && consistent(oldValue, result)) continue;
                diffs.add(
                        new Difference(
                                f.applicationCode(),
                                f.formId(),
                                f.objectId(),
                                record.id(),
                                f.targetFieldId(),
                                f.targetFieldName(),
                                oldValue,
                                result == null ? null : result.state(),
                                result == null ? null : result.value(),
                                result == null ? "新规则没有返回该字段" : result.message()));
            }
        }
        return new Comparison(
                first.formId(),
                first.applicationCode(),
                planned.definition() == null ? "RUNTIME" : "PLANNED",
                records.size(),
                targets.size());
    }

    /** 应用固定版本上还没有计划规则时（迁移前），在内存中把计划规则套到固定版本上求值。 */
    private record Planned(DataCenter.Definition definition) {}

    private Planned plannedDefinition(Fill first, List<Fill> group) {
        var pinned =
                services
                        .applications()
                        .published(first.applicationId())
                        .definition()
                        .objects()
                        .stream()
                        .filter(r -> r.objectId().equals(first.objectId()))
                        .findFirst()
                        .orElseThrow(
                                () -> new IllegalStateException("应用未固定对象：" + first.objectName()));
        var d = services.objects().getVersion(pinned.objectId(), pinned.versionNo()).definition();
        boolean live = true;
        var options = new LinkedHashMap<>(d.fieldOptions());
        for (var f : group) {
            var o = options.getOrDefault(f.targetFieldId(), DataCenter.FieldOptions.defaults());
            if (o.rules() != null
                    && ObjectRuleMigrationPlanner.sameIgnoringReadOnly(
                            o.rules().linkage(), f.linkage())) continue;
            live = false;
            var reference = o.rules() == null ? null : o.rules().reference();
            options.put(
                    f.targetFieldId(),
                    o.withRules(new FieldRules(reference, f.linkage(), null, null, null, null)));
        }
        if (live) return new Planned(null);
        return new Planned(
                new DataCenter.Definition(
                        d.objectId(),
                        d.objectCode(),
                        d.objectName(),
                        d.description(),
                        d.schemaName(),
                        d.tableName(),
                        d.source(),
                        d.readOnly(),
                        d.titleFieldId(),
                        d.settings(),
                        d.fields(),
                        options,
                        d.relations(),
                        d.indexes(),
                        d.details(),
                        d.mainBinding()));
    }

    private List<FieldRules.Result> evaluate(
            Fill first, Planned planned, List<String> targets, String sourceId, long actor) {
        Map<String, Object> values = Map.of(first.sourceFieldId(), sourceId);
        if (planned.definition() == null)
            return services.records()
                    .evaluateRules(
                            new FieldRules.EvaluateQuery(
                                    first.applicationId(),
                                    first.objectId(),
                                    first.formId(),
                                    null,
                                    true,
                                    values,
                                    List.of(first.sourceFieldId()),
                                    targets,
                                    null),
                            actor)
                    .results();
        // 求值会加共享行锁（SELECT FOR SHARE），不能放在只读事务里；本事务不写任何数据。
        return new TransactionTemplate(services.transactions())
                .execute(
                        status ->
                                services.evaluator()
                                        .evaluate(
                                                new RuleContext(
                                                        first.applicationId(),
                                                        planned.definition(),
                                                        actor,
                                                        null,
                                                        null),
                                                values,
                                                new LinkedHashSet<>(targets),
                                                new LinkedHashSet<>(targets),
                                                true));
    }

    static boolean consistent(Object oldValue, FieldRules.Result result) {
        if ("NO_MATCH".equals(result.state())) return oldValue == null;
        if (!"APPLIED".equals(result.state())) return false;
        return same(oldValue, result.value());
    }

    /** 数值按数值比（金额 100 与 100.00 相同），其它按文本比。 */
    static boolean same(Object a, Object b) {
        if (a == null || b == null) return a == null && b == null;
        String x = a.toString(), y = b.toString();
        try {
            return new BigDecimal(x).compareTo(new BigDecimal(y)) == 0;
        } catch (NumberFormatException notNumeric) {
            return x.equals(y);
        }
    }

    private static void requireReport(ObjectRuleMigrationReport report, long actor) {
        if (report == null
                || !TOOL.equals(report.tool())
                || report.formatVersion() != FORMAT_VERSION)
            throw new IllegalArgumentException("不是本工具生成的迁移报告");
        if (actor <= 0) throw new IllegalArgumentException("--actor 必须是有应用设计权限的用户 ID");
    }

    // ── 命令行 ──

    static final String USAGE =
            """
用法（启动方式见 deploy/object-rule-migration.sh）：
  dry-run  [--prefix P] [--out report.json]
  apply    --report report.json --actor 用户ID [--out apply.json]
  rollback --report report.json --actor 用户ID [--out rollback.json]
  compare  --report report.json --actor 用户ID [--sample 50] [--out compare.json]
  linkage-readonly --object-codes a,b --actor 用户ID [--dry-run] [--out linkage-readonly.json]
退出码：0 成功（compare 差异为 0）；1 compare 有差异；2 参数错误（未连库）；3 执行失败（含启动自检失败）""";

    /** 解析并校验后的命令。解析不连库：参数、报告文件与操作人的错误都在这里暴露（退出码 2）。 */
    record Command(
            String name,
            String prefix,
            ObjectRuleMigrationReport report,
            long actor,
            int sample,
            String out,
            List<String> objectCodes,
            boolean dryRun) {
        static Command parse(String[] args, ObjectMapper json) {
            var options = arguments(args);
            String name = options.getOrDefault("", DRY_RUN);
            if (!Set.of(DRY_RUN, APPLY, ROLLBACK, COMPARE, LINKAGE_READONLY).contains(name))
                throw new IllegalArgumentException(
                        "子命令只能是 dry-run、apply、rollback、compare、linkage-readonly");
            var allowed =
                    switch (name) {
                        case DRY_RUN -> Set.of("", "prefix", "out");
                        case COMPARE -> Set.of("", "report", "actor", "sample", "out");
                        case LINKAGE_READONLY ->
                                Set.of("", "object-codes", "actor", DRY_RUN_FLAG, "out");
                        default -> Set.of("", "report", "actor", "out");
                    };
            for (var key : options.keySet())
                if (!allowed.contains(key))
                    throw new IllegalArgumentException(name + " 不接受参数 --" + key);
            String out =
                    options.getOrDefault(
                            "out", DRY_RUN.equals(name) ? "report.json" : name + ".json");
            if (DRY_RUN.equals(name))
                return new Command(
                        name,
                        normalizePrefix(options.get("prefix")),
                        null,
                        0,
                        0,
                        out,
                        List.of(),
                        false);
            long actor = number(required(options, "actor"), "--actor");
            if (LINKAGE_READONLY.equals(name))
                return new Command(
                        name,
                        null,
                        null,
                        actor,
                        0,
                        out,
                        objectCodes(required(options, "object-codes")),
                        options.containsKey(DRY_RUN_FLAG));
            long sample =
                    number(
                            options.getOrDefault("sample", String.valueOf(DEFAULT_SAMPLE)),
                            "--sample");
            if (sample > Integer.MAX_VALUE)
                throw new IllegalArgumentException("--sample 过大：" + sample);
            var path = Path.of(required(options, "report"));
            ObjectRuleMigrationReport report;
            try {
                report = json.readValue(path.toFile(), ObjectRuleMigrationReport.class);
            } catch (java.io.IOException e) {
                throw new IllegalArgumentException("无法读取迁移报告 " + path + "：" + e.getMessage(), e);
            }
            requireReport(report, actor);
            return new Command(name, null, report, actor, (int) sample, out, List.of(), false);
        }

        /** 逗号分隔的对象编码：按小写比较（同 --prefix），去重保序；任一不合编码规则即参数错误。 */
        static List<String> objectCodes(String value) {
            var result = new LinkedHashSet<String>();
            for (var part : value.split(",", -1)) {
                var code = part.trim().toLowerCase(Locale.ROOT);
                if (!PREFIX.matcher(code).matches())
                    throw new IllegalArgumentException(
                            "--object-codes 须是逗号分隔的对象编码（字母开头，其后为字母、数字或下划线），实际为「" + value + "」");
                result.add(code);
            }
            return List.copyOf(result);
        }

        private static long number(String value, String option) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed > 0) return parsed;
            } catch (NumberFormatException ignored) {
                // 与非正数同样按参数错误处理
            }
            throw new IllegalArgumentException(option + " 必须是正整数，实际为「" + value + "」");
        }
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    /** 返回退出码；参数错误不连库，其余失败打印原因。 */
    static int run(String[] args) {
        var json = mapper();
        Command command;
        try {
            command = Command.parse(args, json);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误：" + e.getMessage());
            System.err.println(USAGE);
            return EXIT_USAGE;
        }
        try {
            return execute(command, json);
        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("执行失败：" + e.getMessage());
            return EXIT_FAILED;
        }
    }

    private static int execute(Command command, ObjectMapper json) throws Exception {
        if (DRY_RUN.equals(command.name())) {
            // 判断空草稿要用对象设计服务读草稿定义，所以 dry-run 也拉起正式业务装配；扫描仍在只读事务里，草稿判断在只回滚事务里。
            try (var context = ToolProcessGuard.start(servicesApplication())) {
                NocodeToolContext.requireSingleDatabase(context.getBean(DataSource.class));
                var report =
                        new ObjectRuleMigrationTool(Services.readOnly(context), json)
                                .dryRun(command.prefix());
                write(json, command.out(), report);
                System.out.println(json.writeValueAsString(report.summary()));
            }
            return EXIT_OK;
        }
        try (var context = ToolProcessGuard.start(servicesApplication())) {
            var tool = new ObjectRuleMigrationTool(Services.of(context), json);
            Object result =
                    switch (command.name()) {
                        case APPLY -> tool.apply(command.report(), command.actor());
                        case ROLLBACK -> tool.rollback(command.report(), command.actor());
                        case LINKAGE_READONLY ->
                                tool.linkageReadOnly(
                                        command.objectCodes(), command.actor(), command.dryRun());
                        default ->
                                tool.compare(command.report(), command.actor(), command.sample());
                    };
            write(json, command.out(), result);
            if (result instanceof CompareResult compared) {
                System.out.println("差异数：" + compared.differences());
                return compared.differences() > 0 ? EXIT_DIFFERENCES : EXIT_OK;
            }
            var summary = summary((Run) result);
            if (LINKAGE_READONLY.equals(command.name())) {
                summary.put("dryRun", command.dryRun());
                summary.put(
                        "planned",
                        ((Run) result)
                                .steps().stream()
                                        .filter(s -> "PLANNED".equals(s.action()))
                                        .count());
            }
            System.out.println(json.writeValueAsString(summary));
            return EXIT_OK;
        }
    }

    /** apply / rollback 的一行摘要：步骤数与发布、未变更的个数。 */
    static Map<String, Object> summary(Run run) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", run.command());
        result.put("steps", run.steps().size());
        result.put(
                "published",
                run.steps().stream().filter(s -> "PUBLISHED".equals(s.action())).count());
        result.put(
                "unchanged",
                run.steps().stream().filter(s -> "UNCHANGED".equals(s.action())).count());
        return result;
    }

    /**
     * 写入须走正式业务服务：复用启动模块的完整装配（不启动 Web，懒加载）。工具 jar 须挂在正式包上运行（loader.path）， 由 {@link
     * ToolProcessGuard#start} 启动，不启动任何后台作业。
     */
    static SpringApplicationBuilder servicesApplication() {
        try {
            return NocodeToolContext.application(
                            Class.forName("com.lingan.ucp.OSServerApplication"))
                    .lazyInitialization(true);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "找不到正式服务启动类：对象规则迁移工具须挂在 os.jar 上运行，见 deploy/object-rule-migration.sh", e);
        }
    }

    /** 不带取值的开关参数（linkage-readonly 的 --dry-run）。 */
    static final String DRY_RUN_FLAG = "dry-run";

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
        var value = options.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少 --" + key);
        return value;
    }

    private static void write(ObjectMapper json, String path, Object value) throws Exception {
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of(path).toFile(), value);
    }
}
