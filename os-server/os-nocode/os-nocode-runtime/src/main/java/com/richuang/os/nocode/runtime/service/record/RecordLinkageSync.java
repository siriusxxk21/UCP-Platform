package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.resource.ApplicationLinkageTriggers;
import com.richuang.os.nocode.application.service.resource.ApplicationLinkageTriggers.Trigger;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.richuang.os.nocode.runtime.dal.mapper.RecordMapper;
import com.richuang.os.nocode.runtime.dal.query.RecordStatement;
import com.richuang.os.nocode.runtime.service.rules.FieldRuleEnforcer;
import com.richuang.os.nocode.runtime.service.rules.FieldRuleEvaluator;
import com.richuang.os.nocode.runtime.service.rules.RuleContext;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/**
 * 数据联动「来源变化时自动更新」：来源记录经某个应用新增、修改、删除时，在同一事务里按该应用固定的对象版本找出受影响的目标记录， 用现有的同一个求值入口重算，值变了才经嵌套公共保存写回并留历史。
 *
 * <p>生效口径：只按触发这次保存的那个应用的发布版本登记的规则触发（索引表 nocode_linkage_trigger，应用发布时登记）；
 * 没有应用上下文的写入不触发；应用没引用目标对象或固定的目标版本里没有这条规则也不触发。
 *
 * <p>三个钩子与 {@link RecordAutomations} 的 lock / before / after 并排：lock 必须在业务行锁之前；before 记下改前锚定到的目标；
 * after 对「改前 ∪ 改后」锚定到的目标逐条重算。回写走嵌套公共保存，目标上的下游规则、落库计算、历史照常执行。
 */
@Component
public class RecordLinkageSync {
    /** 一次来源保存最多牵动的目标记录数；超过即报错回滚，不截断。 */
    public static final int MAX_TARGETS = 200;

    private static final String DELETING = RecordLinkageSync.class.getName() + ".deleting";

    @Resource private ApplicationLinkageTriggers triggers;
    @Resource private ApplicationService applications;
    @Resource private ObjectDraftMapper locks;
    @Resource private RecordContextResolver contexts;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordPersistence persistence;
    @Resource private RecordMapper records;
    @Resource private FieldRuleEvaluator evaluator;
    @Resource private RecordAutomations automations;
    @Resource private RecordTransactions transactions;

    @Resource private com.richuang.os.nocode.runtime.dal.support.RuntimeConditionSql sqlFragments;

    @Resource private org.springframework.beans.factory.ObjectProvider<RecordWriteService> writer;

    /**
     * 一个目标对象上、来源是被写对象的全部自动更新字段，以及来源改前那一行锚定到的目标记录。
     *
     * @param previousTargets 改前行上「来源对象的关联字段」的值（CURRENT_RECORD 锚点）；新增时为空
     */
    public record Before(
            String applicationId,
            int applicationVersion,
            String targetObjectId,
            List<Trigger> triggers,
            Set<String> previousTargets) {}

    /**
     * 必须在业务/历史行锁之前调用，紧跟 {@link RecordAutomations#lock}、参数相同。被写对象是任一条索引行（任何应用、任何版本）
     * 的来源或目标时取全局执行锁（与自动更新规则同一把，事务级、可重入）；参数为 null（删除、联合保存、对象维护）时只要存在任何索引行就取——
     * 这些入口会在已经持有行锁之后才碰到别的对象，到那时再取执行锁就违反了「执行锁先于行锁」的顺序。
     *
     * <p>为什么要串行：「值没变就不写」的预判是在没有行锁的情况下读的；两个事务各自看不到对方未提交的数据，都判「没变」或写了过期值， 提交后结果与任何串行顺序都不相符。
     */
    public void lock(String object) {
        if (triggers.participates(object)) locks.lockTableName("nocode-automation-write");
    }

    /** 对象是否参与任何自动更新（是任一条索引行的来源或目标）。 */
    public boolean participates(String object) {
        return triggers.participates(object);
    }

    /** 来源写入前：按触发应用的发布版本取规则，并记下改前行锚定到的目标。没有应用上下文不触发。 */
    public List<Before> before(String application, String object, String id, long actor) {
        if (application == null || application.isBlank()) return List.of();
        // 不参与任何自动更新的对象到此为止：这个判断在 lock 里已经问过库，同一事务内不再产生语句。
        if (!triggers.participates(object)) return List.of();
        int version = applications.published(application).versionNo();
        var rules = triggers.bySource(application, version, object);
        if (rules.isEmpty()) return List.of();
        Row previous = id == null ? null : source(application, object, id, actor);
        Map<String, List<Trigger>> byTarget = new LinkedHashMap<>();
        for (var rule : rules)
            byTarget.computeIfAbsent(rule.targetObjectId(), key -> new ArrayList<>()).add(rule);
        List<Before> out = new ArrayList<>();
        for (var entry : byTarget.entrySet())
            out.add(
                    new Before(
                            application,
                            version,
                            entry.getKey(),
                            List.copyOf(entry.getValue()),
                            anchored(entry.getValue(), previous)));
        out.sort(Comparator.comparing(Before::targetObjectId, RecordLinkageSync::compareIds));
        return out;
    }

    /** 来源写入后：对改前 ∪ 改后锚定到的目标逐条重算；本事务正在被删除的记录不回写。 */
    public void after(List<Before> before, String object, String id, String event, long actor) {
        if (before.isEmpty()) return;
        boolean deleting = RecordChangeOperationEnum.DELETE.matches(event);
        var first = before.getFirst();
        Row now = deleting ? null : source(first.applicationId(), object, id, actor);
        for (var b : before) {
            Set<String> targets = new TreeSet<>(RecordLinkageSync::compareIds);
            targets.addAll(b.previousTargets());
            targets.addAll(anchored(b.triggers(), now));
            var target = contexts.definition(b.applicationId(), b.targetObjectId(), actor);
            for (var rule : b.triggers())
                if (!rule.currentRecordAnchor())
                    targets.addAll(
                            referencing(target, rule.anchorFieldId(), id, actor, MAX_TARGETS + 1));
            targets.removeIf(targetId -> deletingNow(b.targetObjectId(), targetId));
            if (targets.size() > MAX_TARGETS)
                throw invalid(
                        "数据联动自动更新「"
                                + label(target, fieldIds(b.triggers()))
                                + "」一次牵动的记录超过 "
                                + MAX_TARGETS
                                + " 条，本次数据变更未保存；请缩小关联范围");
            for (String targetId : targets)
                refresh(
                        b.applicationId(),
                        b.applicationVersion(),
                        b.targetObjectId(),
                        targetId,
                        fieldIds(b.triggers()),
                        object,
                        id,
                        false,
                        actor);
        }
    }

    /**
     * 重算一条目标记录上的自动更新字段，值变了才发起嵌套保存。
     *
     * <p>整个过程都在 {@link LinkageWriteScope} 内（从读目标定义起）：回填的发起人是应用设计者，不一定是运行成员，不在 Scope 里读定义与预求值
     * 就会被当成没有应用运行权限。预求值只用来决定要不要发起保存；嵌套保存里对象规则会再求值一次，那一次的结果才是落库值。
     *
     * @param fields 要重算的字段，必须都是 (应用, 应用版本, 目标对象) 登记过的自动更新字段
     * @param sourceObject 触发的来源对象；回填时为 null
     * @param sourceRecord 触发的来源记录；回填时为 null
     * @return 是否实际写入（目标不存在、已删除或值没变时为 false）
     */
    public boolean refresh(
            String application,
            int applicationVersion,
            String targetObject,
            String targetId,
            Collection<String> fields,
            String sourceObject,
            String sourceRecord,
            boolean backfill,
            long actor) {
        int depth = LinkageWriteScope.depth() + 1;
        if (depth > LinkageWriteScope.MAX_DEPTH)
            throw invalid("数据联动自动更新连锁超过 " + LinkageWriteScope.MAX_DEPTH + " 层，本次数据变更未保存");
        Set<String> registered = new HashSet<>();
        for (var rule : triggers.ofApplication(application, applicationVersion))
            if (rule.targetObjectId().equals(targetObject)) registered.add(rule.targetFieldId());
        if (fields.isEmpty() || !registered.containsAll(fields))
            throw new IllegalStateException(
                    "linkage write scope fields are not registered auto-update fields: " + fields);
        // 先用占位上下文读到目标定义，才知道字段顺序与名称；占位上下文同样只放行这个应用与目标对象。
        var probe =
                new LinkageWriteScope.Context(
                        application,
                        applicationVersion,
                        targetObject,
                        targetId,
                        List.copyOf(fields),
                        List.copyOf(fields),
                        Set.of(),
                        sourceObject,
                        sourceRecord,
                        backfill,
                        depth);
        var target =
                LinkageWriteScope.run(
                        probe, () -> contexts.definition(application, targetObject, actor));
        List<String> ordered = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (var field : target.fields())
            if (fields.contains(field.id())) {
                ordered.add(field.id());
                names.add(field.name());
            }
        if (ordered.size() != new HashSet<>(fields).size())
            throw new IllegalStateException(
                    "linkage write scope fields are not on the pinned target version: " + fields);
        // 嵌套保存会顺带重算目标上其它由服务端控制的字段：自动更新规则与留存动作维护的字段、只读联动与公式默认值
        // （下游规则依赖刚写入的联动值）。这些字段客户端本来就提交不了，窄授权里算可写，否则它们会保持旧值或被判无权。
        Set<String> managed = new HashSet<>(automations.managedFields(targetObject));
        for (var field : target.fields()) {
            var options = target.fieldOptions().get(field.id());
            if (options != null
                    && !MemberStateEnum.INACTIVE.matches(options.state())
                    && options.rules() != null
                    && options.rules().effectiveReadOnly()) managed.add(field.id());
        }
        managed.removeAll(ordered);
        var context =
                new LinkageWriteScope.Context(
                        application,
                        applicationVersion,
                        targetObject,
                        targetId,
                        ordered,
                        names,
                        managed,
                        sourceObject,
                        sourceRecord,
                        backfill,
                        depth);
        return LinkageWriteScope.run(context, () -> write(context, target, backfill, actor));
    }

    /**
     * 回填一条目标记录：独立事务，目录共享锁 → 执行锁 → 重算并写回。别人的保存可以穿插在两条记录之间，列表照常可用。
     *
     * @return 是否实际写入
     */
    public boolean backfill(
            String application,
            int applicationVersion,
            String targetObject,
            String targetId,
            Collection<String> fields,
            long actor) {
        return transactions.tx(
                () -> {
                    lock(targetObject);
                    return refresh(
                            application,
                            applicationVersion,
                            targetObject,
                            targetId,
                            fields,
                            null,
                            null,
                            true,
                            actor);
                });
    }

    /** 预告或回填用的一页目标记录：按主键升序，取游标之后的 limit + 1 条（多取一条判断是否还有下一页）。系统读，不带操作者范围。 */
    public List<Row> scan(DataCenter.Definition target, String cursor, int limit, long actor) {
        var t = schemas.main(target);
        var b = t.statement(null, null, Long.toString(actor), false);
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Object> where = null;
        if (cursor != null) {
            where = new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            where.setParamAlias("dynamicQuery");
            // 游标就是上一页最后一条的主键。数值主键按数值比较（与 ORDER BY 主键 的顺序一致），其余按文本比较。
            Long number = numericKey(t) ? parse(cursor) : null;
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
                                limit + 1,
                                0,
                                List.of(),
                                "{}",
                                b.actor(),
                                false)
                        .conditions(where);
        return records.rows(q).stream().map(persistence::row).toList();
    }

    /** 目标对象未删除的记录总数（系统读）。 */
    public long count(DataCenter.Definition target, long actor) {
        return records.count(
                schemas.main(target).statement(null, null, Long.toString(actor), false));
    }

    private static boolean numericKey(RuntimeSchema.Table t) {
        String type = Objects.toString(t.key().nativeType(), "").toLowerCase(Locale.ROOT);
        return type.startsWith("bigint")
                || type.startsWith("int")
                || type.startsWith("smallint")
                || type.startsWith("numeric")
                || type.startsWith("decimal");
    }

    private static Long parse(String cursor) {
        try {
            return Long.valueOf(cursor);
        } catch (NumberFormatException e) {
            throw invalid("游标无效，请重新开始");
        }
    }

    private boolean write(
            LinkageWriteScope.Context context,
            DataCenter.Definition target,
            boolean backfill,
            long actor) {
        Row row;
        try {
            row =
                    persistence.read(
                            schemas.main(target), context.targetRecordId(), null, actor, false);
        } catch (ServiceException missing) {
            if (Objects.equals(missing.getCode(), NOT_FOUND)) return false;
            throw missing;
        }
        var results =
                evaluator.evaluate(
                        new RuleContext(
                                context.applicationId(),
                                target,
                                actor,
                                null,
                                context.targetRecordId()),
                        row.values(),
                        new LinkedHashSet<>(context.fields()),
                        Set.of(),
                        false);
        Map<String, Object> changed = new LinkedHashMap<>();
        for (int i = 0; i < context.fields().size(); i++) {
            String fieldId = context.fields().get(i);
            String name = context.fieldNames().get(i);
            var result =
                    results.stream()
                            .filter(
                                    r ->
                                            fieldId.equals(r.fieldId())
                                                    && FieldRuleKindEnum.LINKAGE.matches(r.kind()))
                            .findFirst()
                            .orElse(null);
            if (result == null) throw failure(target, List.of(name), "数据联动无法求值", backfill);
            Object value;
            if (FieldRuleStateEnum.APPLIED.matches(result.state())) value = result.value();
            else if (FieldRuleStateEnum.NO_MATCH.matches(result.state())
                    || FieldRuleStateEnum.PENDING_ROW_VALUE.matches(result.state())) value = null;
            else
                throw failure(
                        target,
                        List.of(name),
                        result.message() == null ? result.state() : result.message(),
                        backfill);
            // 值没变不写：不发起保存、不留历史、不改更新时间。
            if (!FieldRuleEnforcer.same(row.values().get(fieldId), value))
                changed.put(fieldId, value);
        }
        if (changed.isEmpty()) return false;
        List<String> names = new ArrayList<>();
        for (int i = 0; i < context.fields().size(); i++)
            if (changed.containsKey(context.fields().get(i)))
                names.add(context.fieldNames().get(i));
        try {
            writer.getObject()
                    .save(
                            new Save(
                                    context.applicationId(),
                                    context.targetObjectId(),
                                    context.targetRecordId(),
                                    row.revision(),
                                    changed,
                                    null),
                            actor);
        } catch (ServiceException e) {
            throw failure(target, names, e.getMessage(), backfill);
        }
        return true;
    }

    /** 删除入口登记「本事务正在被删除的记录」：级联与置空会连带保存引用它的来源记录，那些保存不应回头改写马上就要删掉的目标。 */
    public void deleting(String object, String id) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        @SuppressWarnings("unchecked")
        Set<String> current = (Set<String>) TransactionSynchronizationManager.getResource(DELETING);
        if (current == null) {
            Set<String> created = new HashSet<>();
            current = created;
            TransactionSynchronizationManager.bindResource(DELETING, created);
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void suspend() {
                            if (TransactionSynchronizationManager.getResource(DELETING) == created)
                                TransactionSynchronizationManager.unbindResource(DELETING);
                        }

                        @Override
                        public void resume() {
                            if (TransactionSynchronizationManager.getResource(DELETING) == null)
                                TransactionSynchronizationManager.bindResource(DELETING, created);
                        }

                        @Override
                        public void afterCompletion(int status) {
                            if (TransactionSynchronizationManager.getResource(DELETING) == created)
                                TransactionSynchronizationManager.unbindResource(DELETING);
                        }
                    });
        }
        current.add(object + ":" + id);
    }

    private static boolean deletingNow(String object, String id) {
        return TransactionSynchronizationManager.getResource(DELETING) instanceof Set<?> current
                && current.contains(object + ":" + id);
    }

    /** 来源记录按全部字段读（系统读，不带操作者范围）；定义取触发应用固定的版本。 */
    private Row source(String application, String object, String id, long actor) {
        var d = contexts.definition(application, object, actor);
        return persistence.read(schemas.main(d), id, null, actor, false);
    }

    /** CURRENT_RECORD 锚点：来源行上那个关联字段的值就是被牵动的目标记录。 */
    private static Set<String> anchored(List<Trigger> rules, Row source) {
        Set<String> result = new LinkedHashSet<>();
        if (source == null) return result;
        for (var rule : rules) {
            if (!rule.currentRecordAnchor()) continue;
            Object value = source.values().get(rule.anchorFieldId());
            if (value != null && !value.toString().isEmpty()) result.add(value.toString());
        }
        return result;
    }

    /** RECORD_KEY 锚点：目标表里「关联字段 = 来源记录」且未删除的记录（系统读，不带操作者范围）。 */
    private List<String> referencing(
            DataCenter.Definition target, String fieldId, String value, long actor, int limit) {
        var t = schemas.main(target);
        var b = t.statement(null, null, Long.toString(actor), false);
        String column = t.columns().get(fieldId);
        if (column == null) throw invalid("数据联动自动更新的关联字段结构已变化");
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
                        persistence.write(Map.of(column, value)),
                        null,
                        false,
                        limit,
                        0,
                        List.of(),
                        "{}",
                        b.actor(),
                        false);
        return records.rows(q).stream().map(persistence::row).map(Row::id).toList();
    }

    private static List<String> fieldIds(List<Trigger> rules) {
        return rules.stream().map(Trigger::targetFieldId).distinct().toList();
    }

    private static String label(DataCenter.Definition target, Collection<String> fieldIds) {
        List<String> names = new ArrayList<>();
        for (var field : target.fields())
            if (fieldIds.contains(field.id())) names.add(field.name());
        return target.objectName() + " · " + String.join("、", names);
    }

    private static ServiceException failure(
            DataCenter.Definition target, List<String> names, String reason, boolean backfill) {
        return invalid(
                "数据联动自动更新「"
                        + target.objectName()
                        + " · "
                        + String.join("、", names)
                        + "」失败："
                        + reason
                        + (backfill ? "" : "；本次数据变更未保存"));
    }

    /** 记录 ID 多为十进制数字串：先比长度再比字面，等价于按数值升序；非数字 ID 仍有确定顺序。 */
    private static int compareIds(String a, String b) {
        return a.length() != b.length() ? Integer.compare(a.length(), b.length()) : a.compareTo(b);
    }
}
