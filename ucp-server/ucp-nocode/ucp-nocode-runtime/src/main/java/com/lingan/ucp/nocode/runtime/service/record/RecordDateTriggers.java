package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.application.dal.dataobject.DateTriggerStateDO;
import com.lingan.ucp.nocode.application.service.resource.ApplicationAutomationCatalog;
import com.lingan.ucp.nocode.application.service.resource.ApplicationAutomationValidator;
import com.lingan.ucp.nocode.application.service.resource.ApplicationDateTriggers;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordMapper;
import com.lingan.ucp.nocode.runtime.dal.query.RecordStatement;
import com.lingan.ucp.nocode.runtime.dal.support.RuntimeConditionSql;
import com.lingan.ucp.nocode.runtime.service.access.ScopeConditions;
import com.lingan.ucp.nocode.runtime.service.rules.FieldRuleEnforcer;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

/**
 * 按日期自动执行（自动更新 mode=DATE）的运行：找出「日期字段 + 偏移天数 = 业务日」的来源记录，以系统身份对目标记录执行一次赋值。
 *
 * <p>账本见 {@link ApplicationDateTriggers}。补跑时日期在外层、规则在内层（按目录顺序），后执行的覆盖前面的；每条来源记录一个事务， 认领（done
 * 行）与写目标同一事务提交，所以停机后精确续跑、多实例下同一条记录只处理一次、单条失败只回滚它自己。 调度与多实例互斥锁见 {@code DateTriggerScheduler}。
 */
@Component
@Slf4j
public class RecordDateTriggers {
    /** 一次扫描每页的来源记录数。 */
    static final int PAGE = 200;

    /** 一条来源记录最多牵动的目标记录数；超过即这一条失败。 */
    public static final int MAX_TARGETS = 200;

    /** done 行保留天数。 */
    static final int RETENTION_DAYS = 90;

    /** 目标记录被并发修改（修订号不符）时，这一条最多再试的次数。 */
    private static final int CONFLICT_RETRIES = 2;

    @Resource private ApplicationAutomationCatalog catalog;
    @Resource private ApplicationDateTriggers ledger;
    @Resource private DataObjectApi objects;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordPersistence persistence;
    @Resource private RecordMapper records;
    @Resource private RecordTransactions transactions;
    @Resource private ObjectDraftMapper locks;
    @Resource private RecordAutomations automations;
    @Resource private ScopeConditions scopes;
    @Resource private RuntimeConditionSql sqlFragments;
    @Resource private ObjectProvider<RecordWriteService> writer;

    /** 一条生效的按日期规则，定义取规则所属应用版本固定的对象版本；SELF 时 relation 为 null。 */
    public record Rule(
            String app,
            int version,
            String id,
            String name,
            ApplicationAutomations.Config config,
            DataCenter.Definition source,
            DataCenter.Definition target,
            DataCenter.Relation relation) {
        String key() {
            return app + ":" + id;
        }

        int offset() {
            return config.offsetDays() == null ? 0 : config.offsetDays();
        }
    }

    /** 一次运行的设置：每天的执行时刻与当天重扫间隔（分钟，0 = 不重扫）。 */
    public record Settings(LocalTime runAt, int rescanMinutes) {}

    enum Outcome {
        SUCCESS,
        UNCHANGED,
        FAILED,
        SKIPPED
    }

    /** 规则在处理过程中被重新发布 / 停用：这一轮停止处理它，不记账。 */
    private static final class RuleChanged extends RuntimeException {
        RuleChanged() {
            super("rule changed", null, false, false);
        }
    }

    /** 来源记录在扫描之后已不再满足日期或条件：回滚认领，不记账。 */
    private static final class NotQualified extends RuntimeException {
        NotQualified() {
            super("not qualified", null, false, false);
        }
    }

    /** 当前业务日：今天的执行时刻已过为今天，否则为昨天。 */
    public static LocalDate businessDay(LocalDateTime now, LocalTime runAt) {
        return now.toLocalTime().isBefore(runAt)
                ? now.toLocalDate().minusDays(1)
                : now.toLocalDate();
    }

    /**
     * 跑一轮。
     *
     * @param now 系统时区（与 TODAY() 相同）的当前时刻
     * @param onlyApp / onlyRule 只处理这一条规则（立即执行）；都为 null 时处理全部
     * @param manual 立即按今天执行：业务日取日历今天，不受执行时刻与重扫间隔限制，并先清掉今天的失败行（重试）
     * @return 本轮各业务日的结果合计（立即执行时只含今天）
     */
    public DateTriggerRuns.Result run(
            LocalDateTime now, Settings settings, String onlyApp, String onlyRule, boolean manual) {
        LocalDate today = now.toLocalDate();
        LocalDate current = manual ? today : businessDay(now, settings.runAt());
        List<Rule> rules = transactions.tx(this::rules);
        if (onlyApp != null)
            rules =
                    rules.stream()
                            .filter(r -> r.app().equals(onlyApp) && r.id().equals(onlyRule))
                            .toList();
        if (manual && rules.isEmpty()) throw invalid("这条业务动作未在已发布版本中按日期执行，请先发布");
        Map<String, DateTriggerStateDO> states = new HashMap<>();
        for (var state : ledger.states())
            states.put(state.getApplicationId() + ":" + state.getResourceId(), state);
        Map<String, LocalDate> closed = new HashMap<>();
        Map<String, Long> actors = new HashMap<>();
        for (var rule : rules) {
            var state = states.get(rule.key());
            if (state == null || !Boolean.TRUE.equals(state.getArmed())) {
                // 兜底：正常由发布 / 启用在同一事务里建账；没有账本的从今天起算，不补发布前的日期。
                transactions.tx(
                        () -> {
                            ledger.armLate(rule.app(), rule.id(), actor(rule, actors));
                            return null;
                        });
                closed.put(rule.key(), today.minusDays(1));
            } else closed.put(rule.key(), state.getClosedDate());
        }
        TreeSet<LocalDate> days = new TreeSet<>();
        for (var rule : rules)
            for (LocalDate d = closed.get(rule.key()).plusDays(1);
                    !d.isAfter(current);
                    d = d.plusDays(1)) days.add(d);
        int success = 0, unchanged = 0, failed = 0, skipped = 0;
        Set<String> halted = new HashSet<>();
        for (LocalDate day : days) {
            for (var rule : rules) {
                if (halted.contains(rule.key()) || !day.isAfter(closed.get(rule.key()))) continue;
                var state = states.get(rule.key());
                if (!manual && day.equals(current) && recentlyScanned(state, day, now, settings))
                    continue;
                int[] counts;
                String error = null;
                try {
                    counts =
                            scan(
                                    rule,
                                    day,
                                    manual,
                                    manual && day.equals(today),
                                    actor(rule, actors));
                } catch (RuleChanged changed) {
                    halted.add(rule.key());
                    continue;
                } catch (RuntimeException failure) {
                    // 整条规则跑不下去（例如对象结构变了）：记在账本上，下一轮再试，不封账。
                    log.warn("[date-trigger][规则「{}」业务日 {} 扫描失败]", rule.name(), day, failure);
                    error = message(failure);
                    halted.add(rule.key());
                    counts = new int[4];
                }
                String scanError = error;
                transactions.tx(
                        () -> {
                            ledger.scanned(rule.app(), rule.id(), day, manual, scanError);
                            return null;
                        });
                if (scanError == null && day.isBefore(current)) {
                    transactions.tx(
                            () -> {
                                ledger.close(rule.app(), rule.id(), day);
                                return null;
                            });
                    closed.put(rule.key(), day);
                }
                if (!manual || day.equals(today)) {
                    success += counts[0];
                    unchanged += counts[1];
                    failed += counts[2];
                    skipped += counts[3];
                }
            }
        }
        if (!manual && onlyApp == null)
            transactions.tx(
                    () -> {
                        ledger.purge(today.minusDays(RETENTION_DAYS));
                        return null;
                    });
        return new DateTriggerRuns.Result(
                (manual ? today : current).toString(), success, unchanged, failed, skipped);
    }

    private static boolean recentlyScanned(
            DateTriggerStateDO state, LocalDate day, LocalDateTime now, Settings settings) {
        if (state == null || state.getLastScanAt() == null || !day.equals(state.getLastScanDate()))
            return false;
        if (settings.rescanMinutes() <= 0) return true;
        // last_scan_at 是数据库会话时区的时刻；只用于节流，按「距上次多少分钟」比较即可，偏差不影响正确性（done 去重）。
        return java.time.Duration.between(state.getLastScanAt(), LocalDateTime.now()).toMinutes()
                < settings.rescanMinutes();
    }

    /** 目录里所有启用的按日期规则：应用 ID 升序，同一应用内按资源在版本里的顺序。 */
    List<Rule> rules() {
        List<Rule> out = new ArrayList<>();
        for (var p : catalog.published()) {
            Map<String, DataCenter.Definition> definitions = new HashMap<>();
            for (var resource : p.definition().resources()) {
                if (!ApplicationResourceKindEnum.AUTOMATION.matches(resource.kind())) continue;
                var c = catalog.config(resource);
                if (Boolean.FALSE.equals(c.enabled()) || !AutomationModeEnum.DATE.matches(c.mode()))
                    continue;
                for (String id : List.of(c.objectId(), c.targetObjectId()))
                    definitions.computeIfAbsent(
                            id,
                            key -> {
                                var ref =
                                        p.definition().objects().stream()
                                                .filter(r -> r.objectId().equals(key))
                                                .findFirst()
                                                .orElseThrow(() -> invalid("按日期自动执行引用的对象不存在"));
                                return objects.getVersion(key, ref.versionNo()).definition();
                            });
                var source = definitions.get(c.objectId());
                var target = definitions.get(c.targetObjectId());
                out.add(
                        new Rule(
                                p.applicationId(),
                                p.version(),
                                resource.id(),
                                resource.name(),
                                c,
                                source,
                                target,
                                c.binding().self()
                                        ? null
                                        : ApplicationAutomationValidator.relation(
                                                c, source, target)));
            }
        }
        return out;
    }

    /** 扫描一条规则的一个业务日；返回 {成功, 无变化, 失败, 跳过}。 */
    private int[] scan(Rule rule, LocalDate day, boolean manual, boolean retryFailed, long actor) {
        if (retryFailed)
            transactions.tx(
                    () -> {
                        ledger.clearFailed(rule.app(), rule.id(), day);
                        return null;
                    });
        Set<String> done = transactions.tx(() -> ledger.done(rule.app(), rule.id(), day));
        var scope = scope(rule, day);
        int[] counts = new int[4];
        String cursor = null;
        while (true) {
            String after = cursor;
            List<Row> page =
                    transactions.tx(
                            () -> {
                                current(rule);
                                return candidates(rule.source(), scope, day, after, actor);
                            });
            for (int i = 0; i < Math.min(page.size(), PAGE); i++) {
                String id = page.get(i).id();
                if (done.contains(id)) continue;
                switch (process(rule, day, id, manual, actor)) {
                    case SUCCESS -> counts[0]++;
                    case UNCHANGED -> counts[1]++;
                    case FAILED -> counts[2]++;
                    case SKIPPED -> counts[3]++;
                }
            }
            if (page.size() <= PAGE) break;
            cursor = page.get(PAGE - 1).id();
        }
        return counts;
    }

    /**
     * 来源记录「日期字段落在 业务日 − 偏移 那天」且满足来源条件。来源条件里的相对日期（今天、本周……）在拼条件时按业务日 day 换算（见 candidates /
     * qualifying），补跑历史业务日时也按那一天，而不是按机器当前日期。
     */
    private static DataScope scope(Rule rule, LocalDate day) {
        return DataScope.and(
                ApplicationAutomationValidator.dateScope(
                        rule.source(), rule.config().dateFieldId(), day.minusDays(rule.offset())),
                rule.config().conditions());
    }

    /** 处理一条来源记录（独立事务）；目标被并发修改时整条重试。 */
    private Outcome process(Rule rule, LocalDate day, String sourceId, boolean manual, long actor) {
        for (int attempt = 0; ; attempt++) {
            try {
                return transactions.tx(() -> apply(rule, day, sourceId, manual, actor));
            } catch (NotQualified ignored) {
                return Outcome.SKIPPED;
            } catch (RuleChanged changed) {
                throw changed;
            } catch (RuntimeException failure) {
                if (failure instanceof ServiceException e
                        && Objects.equals(e.getCode(), CONFLICT)
                        && attempt < CONFLICT_RETRIES) continue;
                log.warn(
                        "[date-trigger][规则「{}」业务日 {} 来源记录 {} 执行失败：{}]",
                        rule.name(),
                        day,
                        sourceId,
                        message(failure));
                transactions.tx(
                        () -> {
                            ledger.failed(
                                    rule.app(), rule.id(), day, sourceId, message(failure), actor);
                            return null;
                        });
                return Outcome.FAILED;
            }
        }
    }

    private Outcome apply(Rule rule, LocalDate day, String sourceId, boolean manual, long actor) {
        current(rule);
        // 认领在前：同一条记录已被别的实例或别的轮次处理（或正在处理，提交后冲突）时跳过。
        if (!ledger.claim(rule.app(), rule.id(), day, sourceId, actor)) return Outcome.SKIPPED;
        var source = qualifying(rule, day, sourceId, actor);
        // 与所有自动更新同一顺序：目录锁 → 执行锁 → 业务行。读目标现值与「值没变不写」的判断在执行锁内。
        locks.lockTableName("nocode-automation-write");
        var targets = targets(rule, source, actor);
        if (targets.size() > MAX_TARGETS)
            throw invalid("一条来源记录牵动的目标记录超过 " + MAX_TARGETS + " 条，请缩小关联范围");
        boolean wrote = false;
        for (String targetId : targets) wrote |= write(rule, day, source, targetId, manual, actor);
        ledger.finish(rule.app(), rule.id(), day, sourceId, wrote, targets.size());
        return wrote ? Outcome.SUCCESS : Outcome.UNCHANGED;
    }

    /** 规则仍是目录里同一应用版本的同一条生效规则；否则这一轮停止处理它。 */
    private void current(Rule rule) {
        for (var p : catalog.published()) {
            if (!p.applicationId().equals(rule.app())) continue;
            if (p.version() != rule.version()) throw new RuleChanged();
            for (var resource : p.definition().resources())
                if (resource.id().equals(rule.id())
                        && ApplicationResourceKindEnum.AUTOMATION.matches(resource.kind())) {
                    var c = catalog.config(resource);
                    if (Boolean.FALSE.equals(c.enabled())
                            || !AutomationModeEnum.DATE.matches(c.mode())) throw new RuleChanged();
                    return;
                }
        }
        throw new RuleChanged();
    }

    /** 在事务里按与扫描相同的条件复核这一条来源记录（系统读，不带操作者范围）。 */
    private Row qualifying(Rule rule, LocalDate day, String sourceId, long actor) {
        var t = schemas.main(rule.source());
        var where = new QueryWrapper<Object>();
        where.setParamAlias("dynamicQuery");
        scopes.append(where, scope(rule, day), rule.source(), t, Map.of(), "t", day);
        var found =
                records.rows(
                        t.statement(sourceId, null, Long.toString(actor), false).conditions(where));
        if (found.size() != 1) throw new NotQualified();
        return persistence.row(found.getFirst());
    }

    private Set<String> targets(Rule rule, Row source, long actor) {
        if (rule.relation() == null) return Set.of(source.id());
        if (RelationDirectionEnum.OUTGOING.matches(rule.config().binding().direction())) {
            var id = source.values().get(rule.relation().fieldId());
            return id == null || id.toString().isEmpty() ? Set.of() : Set.of(id.toString());
        }
        // 引用来源的目标记录（系统读，不带操作者范围），多取一条用于判断超限。
        var t = schemas.main(rule.target());
        var b = t.statement(null, null, Long.toString(actor), false);
        String column = t.columns().get(rule.relation().fieldId());
        if (column == null) throw invalid("按日期自动执行的关联字段结构已变化");
        var q =
                new RecordStatement(
                        b.schema(),
                        b.table(),
                        b.keyColumn(),
                        b.fields(),
                        b.textFields(),
                        b.numericFields(),
                        b.deletedColumn(),
                        b.id(),
                        b.parentColumn(),
                        b.parentId(),
                        null,
                        null,
                        List.of(column),
                        persistence.write(Map.of(column, source.id())),
                        null,
                        false,
                        MAX_TARGETS + 1,
                        0,
                        List.of(),
                        "{}",
                        b.actor(),
                        false);
        Set<String> ids = new TreeSet<>(RecordDateTriggers::compareIds);
        for (String raw : records.rows(q)) ids.add(persistence.row(raw).id());
        return ids;
    }

    /** 对一条目标记录执行赋值：值没变不写（不留历史、不改更新时间）。返回是否实际写入。 */
    private boolean write(
            Rule rule, LocalDate day, Row source, String targetId, boolean manual, long actor) {
        var t = schemas.main(rule.target());
        Row row;
        try {
            row = persistence.read(t, targetId, null, actor, false);
        } catch (ServiceException missing) {
            if (Objects.equals(missing.getCode(), NOT_FOUND)) return false;
            throw missing;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        for (var a : rule.config().assignments())
            data.put(
                    a.fieldId(),
                    AutomationAssignmentEnum.VALUE.matches(a.kind())
                            ? a.value()
                            : source.values().get(a.sourceFieldId()));
        data.entrySet()
                .removeIf(e -> FieldRuleEnforcer.same(e.getValue(), row.values().get(e.getKey())));
        if (data.isEmpty()) return false;
        var context =
                new DateTriggerWriteScope.Context(
                        rule.app(),
                        rule.version(),
                        rule.id(),
                        rule.name(),
                        rule.target().objectId(),
                        targetId,
                        writable(rule),
                        rule.source().objectId(),
                        source.id(),
                        day,
                        manual);
        DateTriggerWriteScope.run(
                context,
                () ->
                        writer.getObject()
                                .save(
                                        new Save(
                                                rule.app(),
                                                rule.target().objectId(),
                                                targetId,
                                                row.revision(),
                                                data,
                                                null),
                                        actor));
        return true;
    }

    /**
     * 窄授权的可写字段：本规则赋值的字段，加上目标对象上另由服务端维护的字段（持续维护 / 留存动作的目标、只读联动与公式默认值）—— 嵌套保存会顺带重算它们，
     * 不算可写的话会被判无权（同数据联动自动更新）。客户端提交不了这些字段，这里只是不让系统写入被自己的授权挡住。
     */
    private Set<String> writable(Rule rule) {
        Set<String> result = new HashSet<>();
        rule.config().assignments().forEach(a -> result.add(a.fieldId()));
        result.addAll(automations.managedFields(rule.target().objectId()));
        for (var field : rule.target().fields()) {
            var options = rule.target().fieldOptions().get(field.id());
            if (options != null
                    && !MemberStateEnum.INACTIVE.matches(options.state())
                    && options.rules() != null
                    && options.rules().effectiveReadOnly()) result.add(field.id());
        }
        return result;
    }

    /** 一页候选来源记录：按主键升序，取游标之后的 PAGE + 1 条（多取一条判断是否还有下一页）。系统读，不带操作者范围。条件里的相对日期按业务日 day 换算。 */
    private List<Row> candidates(
            DataCenter.Definition d, DataScope scope, LocalDate day, String cursor, long actor) {
        var t = schemas.main(d);
        var b = t.statement(null, null, Long.toString(actor), false);
        var where = new QueryWrapper<Object>();
        where.setParamAlias("dynamicQuery");
        scopes.append(where, scope, d, t, Map.of(), "t", day);
        if (cursor != null) {
            Long number = numericKey(t) ? Long.valueOf(cursor) : null;
            if (number != null) where.gt(sqlFragments.column("t", t.key().name(), false), number);
            else where.gt(sqlFragments.column("t", t.key().name(), true), cursor);
        }
        var q =
                new RecordStatement(
                                b.schema(),
                                b.table(),
                                b.keyColumn(),
                                b.fields(),
                                b.textFields(),
                                b.numericFields(),
                                b.deletedColumn(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                List.of(),
                                "{}",
                                null,
                                false,
                                PAGE + 1,
                                0,
                                List.of(),
                                "{}",
                                b.actor(),
                                false)
                        .conditions(where);
        return records.rows(q).stream().map(persistence::row).toList();
    }

    private static boolean numericKey(RuntimeSchema.Table t) {
        String type = Objects.toString(t.key().nativeType(), "").toLowerCase(Locale.ROOT);
        return type.startsWith("bigint")
                || type.startsWith("int")
                || type.startsWith("smallint")
                || type.startsWith("numeric")
                || type.startsWith("decimal");
    }

    /** 系统执行时留痕用的操作者：应用创建人（不参与权限判断）。 */
    private long actor(Rule rule, Map<String, Long> cache) {
        long actor =
                cache.computeIfAbsent(
                        rule.app(), app -> transactions.tx(() -> ledger.creator(app)));
        if (actor <= 0) throw invalid("应用创建人无效，无法以系统身份执行按日期规则");
        return actor;
    }

    private static String message(Throwable failure) {
        String text = failure.getMessage();
        return text == null || text.isBlank() ? failure.getClass().getSimpleName() : text;
    }

    /** 记录 ID 多为十进制数字串：先比长度再比字面，等价于按数值升序；非数字 ID 仍有确定顺序。 */
    private static int compareIds(String a, String b) {
        return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
    }
}
