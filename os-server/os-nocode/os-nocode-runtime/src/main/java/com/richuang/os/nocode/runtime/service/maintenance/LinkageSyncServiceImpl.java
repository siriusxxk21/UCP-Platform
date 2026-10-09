package com.richuang.os.nocode.runtime.service.maintenance;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.resource.ApplicationAutomationCatalog;
import com.richuang.os.nocode.application.service.resource.ApplicationLinkageTriggers;
import com.richuang.os.nocode.enums.ApplicationStatusEnum;
import com.richuang.os.nocode.enums.FieldRuleKindEnum;
import com.richuang.os.nocode.enums.FieldRuleStateEnum;
import com.richuang.os.nocode.metadata.service.object.LinkageTriggerPlan;
import com.richuang.os.nocode.runtime.service.record.RecordLinkageSync;
import com.richuang.os.nocode.runtime.service.rules.FieldRuleEnforcer;
import com.richuang.os.nocode.runtime.service.rules.FieldRuleEvaluator;
import com.richuang.os.nocode.runtime.service.rules.RuleContext;
import com.richuang.os.nocode.runtime.service.selection.SelectionCatalog;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 数据联动自动更新的总览、预告与回填。
 *
 * <p>生效点是应用发布，所以三个接口都以应用为入口：PUBLISHED 基准读索引表（应用当前发布版登记的行）；DRAFT 基准按应用草稿引用的对象版本现算
 * （同一个纯函数）。预告与回填都逐条经过与保存相同的求值入口，不另写一份求值。
 *
 * <p>取锁约定（与「发布应用」「发布对象」并发时不死锁、不互相拖住）：读应用草稿、读发布快照会对应用头加共享行锁，读对象版本会对对象头加共享行锁； 发布应用是「目录独占锁 →
 * 应用头」，发布对象是「目录独占锁 → 对象头 → 应用头」。所以 ① 总览、预告核对规则这两段进事务先取目录共享锁， 与运行端同一个顺序；②
 * 预告把「核对规则」与「逐条求值」分成两个事务，目录与应用头的锁不带进求值那一段——一页求值可能要几秒到几十秒， 发布在等这些锁时已经握着或排着全局目录独占锁，会把所有运行端读取一起挡住。
 */
@Service
public class LinkageSyncServiceImpl implements LinkageSyncService {
    private static final int PREVIEW_DEFAULT = 200;
    private static final int PREVIEW_MAX = 500;
    private static final int BACKFILL_DEFAULT = 100;
    private static final int BACKFILL_MAX = 200;
    private static final int FAILURE_SAMPLES = 20;
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(LinkageSyncServiceImpl.class);

    @Resource private ApplicationService applications;
    @Resource private ApplicationAutomationCatalog automations;
    @Resource private ApplicationLinkageTriggers triggers;
    @Resource private DataObjectApi objects;
    @Resource private RecordLinkageSync linkageSync;
    @Resource private FieldRuleEvaluator evaluator;
    @Resource private SelectionCatalog selectionCatalog;
    @Resource private PlatformTransactionManager manager;
    private TransactionTemplate transaction;

    @PostConstruct
    void initialize() {
        transaction = new TransactionTemplate(manager);
    }

    /** 一个基准下的规则与固定对象定义。version 为 null 表示草稿基准。 */
    private record Basis(
            Integer version,
            List<LinkageTriggerPlan.Row> rows,
            Map<String, DataCenter.Definition> definitions) {
        LinkageTriggerPlan.Row row(String object, String field) {
            return rows.stream()
                    .filter(
                            r ->
                                    r.targetObjectId().equals(object)
                                            && r.targetFieldId().equals(field))
                    .findFirst()
                    .orElse(null);
        }
    }

    @Override
    public LinkageSync.Overview overview(String applicationId, String basis, long actor) {
        String mode = basis(basis);
        applications.requireDesigner(applicationId, actor);
        return transaction.execute(
                status -> {
                    // 先目录共享锁、再应用头：草稿基准先读草稿（应用头）、之后比较基准又读发布快照（目录锁），不先取就与发布倒序。
                    automations.lock(false);
                    var current = resolve(applicationId, mode);
                    var baseline = baseline(applicationId, mode, current);
                    Map<String, List<ApplicationLinkageTriggers.Pinning>> pinning = new HashMap<>();
                    Map<String, List<ApplicationLinkageTriggers.Trigger>> others = new HashMap<>();
                    List<LinkageSync.Field> fields = new ArrayList<>();
                    for (var row : current.rows()) {
                        var target = current.definitions().get(row.targetObjectId());
                        var source = current.definitions().get(row.sourceObjectId());
                        boolean onSource =
                                LinkageTriggerPlan.ANCHOR_CURRENT_RECORD.equals(row.anchor());
                        var before = baseline.row(row.targetObjectId(), row.targetFieldId());
                        List<LinkageSync.Divergent> divergent = new ArrayList<>();
                        for (var other :
                                pinning.computeIfAbsent(
                                        row.sourceObjectId(),
                                        id -> triggers.activePinning(id, applicationId))) {
                            String reason = null;
                            if (!other.objects().contains(row.targetObjectId()))
                                reason = LinkageSync.REASON_TARGET_NOT_PINNED;
                            else {
                                var theirs =
                                        others
                                                .computeIfAbsent(
                                                        other.applicationId(),
                                                        id ->
                                                                triggers.ofApplication(
                                                                        id, other.version()))
                                                .stream()
                                                .filter(
                                                        t ->
                                                                t.targetObjectId()
                                                                                .equals(
                                                                                        row
                                                                                                .targetObjectId())
                                                                        && t.targetFieldId()
                                                                                .equals(
                                                                                        row
                                                                                                .targetFieldId()))
                                                .findFirst()
                                                .orElse(null);
                                if (theirs == null) reason = LinkageSync.REASON_NO_RULE;
                                else if (!theirs.signature().equals(row.signature()))
                                    reason = LinkageSync.REASON_DIFFERENT_RULE;
                            }
                            if (reason != null)
                                divergent.add(
                                        new LinkageSync.Divergent(
                                                other.applicationId(), other.name(), reason));
                        }
                        fields.add(
                                new LinkageSync.Field(
                                        row.targetObjectId(),
                                        target.objectName(),
                                        row.targetObjectVersion(),
                                        row.targetFieldId(),
                                        fieldName(target, row.targetFieldId()),
                                        row.sourceObjectId(),
                                        source.objectName(),
                                        row.anchor(),
                                        row.anchorFieldId(),
                                        fieldName(onSource ? source : target, row.anchorFieldId()),
                                        row.signature(),
                                        before == null
                                                ? LinkageSync.CHANGE_NEW
                                                : before.signature().equals(row.signature())
                                                        ? LinkageSync.CHANGE_UNCHANGED
                                                        : LinkageSync.CHANGE_CHANGED,
                                        divergent));
                    }
                    List<LinkageSync.Removed> removed = new ArrayList<>();
                    for (var row : baseline.rows())
                        if (current.row(row.targetObjectId(), row.targetFieldId()) == null) {
                            var target = baseline.definitions().get(row.targetObjectId());
                            removed.add(
                                    new LinkageSync.Removed(
                                            row.targetObjectId(),
                                            target.objectName(),
                                            row.targetFieldId(),
                                            fieldName(target, row.targetFieldId())));
                        }
                    return new LinkageSync.Overview(
                            applicationId, mode, current.version(), fields, removed);
                });
    }

    @Override
    public LinkageSync.Preview preview(LinkageSync.PreviewRequest request, long actor) {
        if (request == null || request.applicationId() == null) throw invalid("预告请求缺少应用");
        String mode = basis(request.basis());
        int limit = limit(request.limit(), PREVIEW_DEFAULT, PREVIEW_MAX);
        applications.requireDesigner(request.applicationId(), actor);
        // 不写任何数据、不取执行锁。没有标成数据库只读事务：读发布快照会对应用头加共享行锁，只读事务不允许。
        // 第一段（短事务）：核对规则、取齐固定版本的对象定义；应用头与目录的共享锁随这一段提交释放。
        record Plan(Basis basis, LinkageTriggerPlan.Row rule) {}
        Plan plan =
                transaction.execute(
                        status -> {
                            // 这一段读应用头、再读各对象头；对象发布是「目录独占锁 → 对象头 → 应用头」，先取目录共享锁才不会与它交叉。
                            automations.lock(false);
                            var basis = resolve(request.applicationId(), mode);
                            return new Plan(
                                    basis,
                                    rule(basis, request.targetObjectId(), request.targetFieldId()));
                        });
        // 第二段：读这一页记录并逐条求值。只用第一段取好的定义（都是不可变的固定版本），不再碰应用头。
        return transaction.execute(
                status -> {
                    var basis = plan.basis();
                    var rule = plan.rule();
                    var target = basis.definitions().get(rule.targetObjectId());
                    var page = linkageSync.scan(target, request.cursor(), limit, actor);
                    boolean done = page.size() <= limit;
                    var rows = done ? page : page.subList(0, limit);
                    int unchanged = 0, fill = 0, clear = 0, change = 0, failedCount = 0;
                    List<LinkageSync.Failure> failed = new ArrayList<>();
                    for (var row : rows) {
                        Object expected;
                        try {
                            expected =
                                    expected(
                                            request.applicationId(),
                                            basis,
                                            target,
                                            rule.targetFieldId(),
                                            row,
                                            actor);
                        } catch (ServiceException e) {
                            failedCount++;
                            if (failed.size() < FAILURE_SAMPLES)
                                failed.add(new LinkageSync.Failure(row.id(), e.getMessage()));
                            continue;
                        }
                        Object stored = row.values().get(rule.targetFieldId());
                        if (FieldRuleEnforcer.same(stored, expected)) unchanged++;
                        else if (FieldRuleEnforcer.same(stored, null)) fill++;
                        else if (FieldRuleEnforcer.same(expected, null)) clear++;
                        else change++;
                    }
                    return new LinkageSync.Preview(
                            rule.signature(),
                            request.cursor() == null ? linkageSync.count(target, actor) : null,
                            rows.size(),
                            unchanged,
                            fill,
                            clear,
                            change,
                            failedCount,
                            failed,
                            rows.isEmpty() ? request.cursor() : rows.getLast().id(),
                            done);
                });
    }

    @Override
    public LinkageSync.Backfill backfill(LinkageSync.BackfillRequest request, long actor) {
        if (request == null || request.applicationId() == null) throw invalid("回填请求缺少应用");
        int limit = limit(request.limit(), BACKFILL_DEFAULT, BACKFILL_MAX);
        applications.requireDesigner(request.applicationId(), actor);
        // 先在一个短事务里核对规则并取这一页的记录 ID；随后每条记录各自一个事务。
        record Batch(int version, String object, String field, List<String> ids, boolean done) {}
        Batch batch =
                transaction.execute(
                        status -> {
                            var basis =
                                    resolve(request.applicationId(), LinkageSync.BASIS_PUBLISHED);
                            var rule =
                                    rule(basis, request.targetObjectId(), request.targetFieldId());
                            if (request.signature() == null
                                    || !rule.signature().equals(request.signature()))
                                throw new ServiceException(CONFLICT, "规则已变化，请重新预告");
                            var target = basis.definitions().get(rule.targetObjectId());
                            var page = linkageSync.scan(target, request.cursor(), limit, actor);
                            boolean done = page.size() <= limit;
                            var rows = done ? page : page.subList(0, limit);
                            return new Batch(
                                    basis.version(),
                                    rule.targetObjectId(),
                                    rule.targetFieldId(),
                                    rows.stream().map(ApplicationRecords.Row::id).toList(),
                                    done);
                        });
        int updated = 0, unchanged = 0, failedCount = 0;
        List<LinkageSync.Failure> failed = new ArrayList<>();
        for (String id : batch.ids()) {
            try {
                // 回填没有外层保存：挑取值等选择校验需要的应用上下文在这里套上。
                boolean written =
                        selectionCatalog.inApplication(
                                request.applicationId(),
                                null,
                                () ->
                                        linkageSync.backfill(
                                                request.applicationId(),
                                                batch.version(),
                                                batch.object(),
                                                id,
                                                List.of(batch.field()),
                                                actor));
                if (written) updated++;
                else unchanged++;
            } catch (ServiceException e) {
                // 单条失败保持旧值、记入失败清单，不中止本页。
                failedCount++;
                if (failed.size() < FAILURE_SAMPLES)
                    failed.add(new LinkageSync.Failure(id, e.getMessage()));
            } catch (RuntimeException e) {
                // 非业务异常（数据库瞬时错误等）同样只影响这一条：这条的事务已回滚，原因留日志，页内其余记录继续。
                LOG.warn(
                        "linkage backfill failed: application={} object={} field={} record={}",
                        request.applicationId(),
                        batch.object(),
                        batch.field(),
                        id,
                        e);
                failedCount++;
                if (failed.size() < FAILURE_SAMPLES)
                    failed.add(new LinkageSync.Failure(id, "回填这条记录时出错，已保持旧值；请稍后重试"));
            }
        }
        return new LinkageSync.Backfill(
                batch.ids().size(),
                updated,
                unchanged,
                failedCount,
                failed,
                batch.ids().isEmpty() ? request.cursor() : batch.ids().getLast(),
                batch.done());
    }

    /** 一条目标记录上这个字段的应有值：与保存、来源触发同一个求值入口，来源按全部数据读、版本取基准固定的对象定义。 */
    private Object expected(
            String applicationId,
            Basis basis,
            DataCenter.Definition target,
            String fieldId,
            ApplicationRecords.Row row,
            long actor) {
        var result =
                evaluator
                        .evaluate(
                                new RuleContext(
                                        applicationId,
                                        target,
                                        actor,
                                        basis.definitions(),
                                        row.id()),
                                row.values(),
                                Set.of(fieldId),
                                Set.of(),
                                false)
                        .stream()
                        .filter(
                                r ->
                                        fieldId.equals(r.fieldId())
                                                && FieldRuleKindEnum.LINKAGE.matches(r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("数据联动无法求值"));
        if (FieldRuleStateEnum.APPLIED.matches(result.state())) return result.value();
        if (FieldRuleStateEnum.NO_MATCH.matches(result.state())
                || FieldRuleStateEnum.PENDING_ROW_VALUE.matches(result.state())) return null;
        throw invalid(result.message() == null ? result.state() : result.message());
    }

    private Basis resolve(String applicationId, String mode) {
        if (LinkageSync.BASIS_PUBLISHED.equals(mode)) {
            var published = applications.published(applicationId);
            List<LinkageTriggerPlan.Row> rows = new ArrayList<>();
            for (var t : triggers.ofApplication(applicationId, published.versionNo()))
                rows.add(
                        new LinkageTriggerPlan.Row(
                                t.sourceObjectId(),
                                t.targetObjectId(),
                                t.targetObjectVersion(),
                                t.targetFieldId(),
                                t.anchor(),
                                t.anchorFieldId(),
                                t.signature()));
            return new Basis(
                    published.versionNo(), rows, definitions(published.definition().objects()));
        }
        var draft =
                applications.normalize(
                        new ApplicationCenter.Definition(
                                applications.get(applicationId).draft().objects(), List.of()));
        return new Basis(null, triggers.derive(draft), definitions(draft.objects()));
    }

    /**
     * 比较基准：PUBLISHED 相对上一个发布版；DRAFT 相对当前发布版（应用未发布或已停用时为空）。
     *
     * <p>「有没有发布版」先看应用头再决定，不靠「取发布版失败了再接住」：取发布版与这里是同一个事务，它一抛错整个事务就被标成只能回滚，
     * 接住也没用，提交时会变成一个与业务无关的回滚异常（首次发布前的预告正是这种情形）。
     */
    private Basis baseline(String applicationId, String mode, Basis current) {
        if (LinkageSync.BASIS_DRAFT.equals(mode)) {
            var head = applications.get(applicationId).application();
            if (head.publishedVersion() == null
                    || !ApplicationStatusEnum.ACTIVE.matches(head.status()))
                return new Basis(null, List.of(), Map.of());
            return resolve(applicationId, LinkageSync.BASIS_PUBLISHED);
        }
        if (current.version() == null || current.version() <= 1)
            return new Basis(null, List.of(), Map.of());
        var previous = applications.published(applicationId, current.version() - 1);
        List<LinkageTriggerPlan.Row> rows = new ArrayList<>();
        for (var t : triggers.ofApplication(applicationId, previous.versionNo()))
            rows.add(
                    new LinkageTriggerPlan.Row(
                            t.sourceObjectId(),
                            t.targetObjectId(),
                            t.targetObjectVersion(),
                            t.targetFieldId(),
                            t.anchor(),
                            t.anchorFieldId(),
                            t.signature()));
        return new Basis(previous.versionNo(), rows, definitions(previous.definition().objects()));
    }

    private Map<String, DataCenter.Definition> definitions(
            List<ApplicationCenter.ObjectReference> refs) {
        Map<String, DataCenter.Definition> result = new LinkedHashMap<>();
        for (var ref : refs)
            result.put(
                    ref.objectId(),
                    objects.getVersion(ref.objectId(), ref.versionNo()).definition());
        return result;
    }

    private static LinkageTriggerPlan.Row rule(Basis basis, String object, String field) {
        if (object == null || field == null) throw invalid("请指定目标对象和字段");
        var rule = basis.row(object, field);
        if (rule == null) throw invalid("该字段没有开启「来源变化时自动更新」，或应用还没有同步到带这条规则的对象版本");
        return rule;
    }

    private static String basis(String basis) {
        if (LinkageSync.BASIS_PUBLISHED.equals(basis) || LinkageSync.BASIS_DRAFT.equals(basis))
            return basis;
        throw invalid("基准只能是 PUBLISHED 或 DRAFT");
    }

    private static int limit(Integer requested, int fallback, int maximum) {
        if (requested == null) return fallback;
        if (requested < 1 || requested > maximum) throw invalid("每页条数须在 1 到 " + maximum + " 之间");
        return requested;
    }

    private static String fieldName(DataCenter.Definition d, String fieldId) {
        return d.fields().stream()
                .filter(f -> f.id().equals(fieldId))
                .map(FieldDefinition::name)
                .findFirst()
                .orElse(fieldId);
    }
}
