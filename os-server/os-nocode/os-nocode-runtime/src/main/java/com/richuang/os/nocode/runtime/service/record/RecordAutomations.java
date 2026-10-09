package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.application.service.resource.ApplicationAutomationCatalog;
import com.richuang.os.nocode.application.service.resource.ApplicationAutomationValidator;
import com.richuang.os.nocode.application.service.sharing.ObjectGrantValidator;
import com.richuang.os.nocode.application.service.sharing.ObjectSharingService;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.richuang.os.nocode.runtime.dal.mapper.RecordMapper;
import com.richuang.os.nocode.runtime.dal.query.RecordStatement;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.richuang.os.nocode.runtime.service.task.TaskEntryRuntimeScope;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/** 所有正式写入入口共用的同步自动更新。发布目录读锁和执行锁先于源记录写入， 来源变更、目标计算、历史留痕在原事务中共同提交；权限失败、超限和规则错误均回滚。 */
@Component
public class RecordAutomations {
    @Resource private ApplicationAutomationCatalog catalog;
    @Resource private ObjectDraftMapper locks;
    @Resource private DataObjectApi objects;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordPersistence persistence;
    @Resource private RecordMapper records;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private TaskEntryRuntimeScope taskScope;
    @Resource private ObjectSharingService sharing;
    @Resource private ObjectGrantValidator grantValidator;
    @Resource private org.springframework.beans.factory.ObjectProvider<RecordWriteService> writer;

    public record Rule(
            String app,
            int version,
            String id,
            String name,
            ApplicationAutomations.Config config,
            DataCenter.Definition source,
            DataCenter.Definition target,
            DataCenter.Relation relation) {}

    public record Before(Rule rule, Row previous, Set<String> targets) {}

    private List<Rule> rules() {
        var out = new ArrayList<Rule>();
        for (var p : catalog.published()) {
            Map<String, DataCenter.Definition> definitions = new HashMap<>();
            for (var resource : p.definition().resources()) {
                if (!ApplicationResourceKindEnum.AUTOMATION.matches(resource.kind())) continue;
                var c = catalog.config(resource);
                // 按日期自动执行不由数据变化触发，由 RecordDateTriggers 每天执行；这里的锁、维护保护与事件钩子都不涉及它。
                if (Boolean.FALSE.equals(c.enabled()) || AutomationModeEnum.DATE.matches(c.mode()))
                    continue;
                for (String id : List.of(c.objectId(), c.targetObjectId()))
                    definitions.computeIfAbsent(
                            id,
                            key -> {
                                var ref =
                                        p.definition().objects().stream()
                                                .filter(r -> r.objectId().equals(key))
                                                .findFirst()
                                                .orElseThrow(() -> invalid("自动更新引用对象不存在"));
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
                                ApplicationAutomationValidator.relation(c, source, target)));
            }
        }
        return out;
    }

    /** 必须在业务/历史行锁之前调用。仅参与规则的写入串行，普通无规则对象不占执行锁。 */
    public void lock(String object) {
        catalog.lock(false);
        if (!catalog.captures(object).isEmpty()
                || rules().stream()
                        .anyMatch(
                                r ->
                                        object == null
                                                || r.source().objectId().equals(object)
                                                || r.target().objectId().equals(object)))
            locks.lockTableName("nocode-automation-write");
    }

    /** 仅供纯导入批末计算资格检查，任何源/目标自动更新与确认留存入口均保守退出。 */
    public boolean canDeferOrderedImport(String object) {
        return catalog.captures(object).isEmpty()
                && rules().stream()
                        .noneMatch(
                                rule ->
                                        rule.source().objectId().equals(object)
                                                || rule.target().objectId().equals(object));
    }

    public List<String> managedFields(String object) {
        List<String> fields =
                new ArrayList<>(
                        rules().stream()
                                .filter(
                                        r ->
                                                r.target().objectId().equals(object)
                                                        && AutomationModeEnum.MAINTAIN.matches(
                                                                r.config().mode()))
                                .flatMap(r -> r.config().assignments().stream())
                                .map(ApplicationAutomations.Assignment::fieldId)
                                .distinct()
                                .toList());
        catalog.captures(object).forEach(c -> fields.addAll(c.action().captures().keySet()));
        return fields.stream().distinct().toList();
    }

    /** 保留每条发布规则自己版本下的旧值；跨应用写入不能遗漏当前表单没有展示的字段。 */
    public List<Before> before(String object, String id, long actor) {
        var out = new ArrayList<Before>();
        for (var r : rules())
            if (r.source().objectId().equals(object)) {
                var row =
                        id == null
                                ? null
                                : persistence.read(
                                        schemas.main(r.source()), id, null, actor, false);
                out.add(new Before(r, row, targets(r, row, actor)));
            }
        return out;
    }

    /** 校验前补齐维护值，保证新建目标的必填状态有确定初值；用户不能伪造维护字段。 */
    public Map<String, Object> prepare(
            String object,
            String id,
            Map<String, Object> input,
            Map<String, Object> requested,
            long actor) {
        var result = new LinkedHashMap<>(input);
        for (var r : rules()) {
            if (!r.target().objectId().equals(object)
                    || !AutomationModeEnum.MAINTAIN.matches(r.config().mode())) continue;
            var scope = AutomationWriteScope.current();
            if (scope != null
                    && scope.applicationId().equals(r.app())
                    && scope.ruleId().equals(r.id())) continue;
            // 调用应用可能仍固定旧版；不能把旧版未投影的关联字段误当成已经解除。
            var stored =
                    id == null
                            ? Map.<String, Object>of()
                            : persistence
                                    .read(schemas.main(r.target()), id, null, actor, false)
                                    .values();
            var candidate = new LinkedHashMap<>(stored);
            candidate.putAll(result);
            var maintained = calculate(r, id, candidate, actor);
            for (var a : r.config().assignments()) {
                // 表单默认值和服务端预填会被真实维护值覆盖；保护只判断调用者实际提交的值。
                if (requested != null
                        && requested.containsKey(a.fieldId())
                        && !same(
                                r.target(),
                                a.fieldId(),
                                requested.get(a.fieldId()),
                                stored.get(a.fieldId()))
                        && !same(
                                r.target(),
                                a.fieldId(),
                                requested.get(a.fieldId()),
                                maintained.get(a.fieldId())))
                    throw invalid("字段由自动更新“" + r.name() + "”维护，不能手工修改");
            }
            candidate.putAll(maintained);
            requireTarget(r, id, candidate, actor);
            result.putAll(maintained);
        }
        return result;
    }

    public void after(List<Before> before, String id, String event, long actor) {
        after(before, id, event, actor, Set.of());
    }

    /** 级联已标记删除的目标不再回写，不能将无目标误报为整个删除失败。 */
    public void after(
            List<Before> before, String id, String event, long actor, Set<String> deletedTargets) {
        for (var b : before) {
            var r = b.rule();
            boolean deleting = RecordChangeOperationEnum.DELETE.matches(event);
            var now =
                    deleting
                            ? null
                            : persistence.read(schemas.main(r.source()), id, null, actor, false);
            boolean maintain = AutomationModeEnum.MAINTAIN.matches(r.config().mode());
            if (!maintain && !r.config().events().contains(event)) continue;
            var source = deleting ? b.previous() : now;
            if (!maintain && (source == null || !matches(r, source))) continue;
            requireSource(r, actor);
            var ids = new TreeSet<String>();
            if (maintain || deleting) ids.addAll(b.targets());
            if (!deleting) ids.addAll(targets(r, now, actor));
            if (ids.size() > 200) throw invalid("自动更新单次目标超过 200 条，请缩小关联范围");
            for (String targetId : ids) {
                if (deletedTargets.contains(r.target().objectId() + ":" + targetId)) continue;
                var row = persistence.read(schemas.main(r.target()), targetId, null, actor, false);
                var data =
                        maintain
                                ? calculate(r, targetId, row.values(), actor)
                                : eventValues(r, source);
                data.entrySet()
                        .removeIf(
                                e ->
                                        same(
                                                r.target(),
                                                e.getKey(),
                                                e.getValue(),
                                                row.values().get(e.getKey())));
                if (data.isEmpty()) continue;
                requireTarget(r, targetId, row.values(), actor);
                var context =
                        new AutomationWriteScope.Context(
                                r.app(),
                                r.version(),
                                r.id(),
                                r.name(),
                                r.source().objectId(),
                                id,
                                r.target().objectId(),
                                Set.copyOf(data.keySet()),
                                targetId,
                                taskRule(r, actor));
                try {
                    AutomationWriteScope.run(
                            context,
                            () ->
                                    writer.getObject()
                                            .save(
                                                    new Save(
                                                            r.app(),
                                                            r.target().objectId(),
                                                            targetId,
                                                            row.revision(),
                                                            data,
                                                            null),
                                                    actor));
                } catch (com.richuang.os.framework.common.exception.ServiceException e) {
                    throw invalid("自动更新“" + r.name() + "”失败：" + e.getMessage() + "；本次数据变更未保存");
                }
            }
        }
    }

    private Map<String, Object> eventValues(Rule r, Row source) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (var a : r.config().assignments())
            values.put(
                    a.fieldId(),
                    AutomationAssignmentEnum.VALUE.matches(a.kind())
                            ? a.value()
                            : source.values().get(a.sourceFieldId()));
        return values;
    }

    private boolean matches(Rule r, Row row) {
        return r.config().conditions() == null
                || r.config().conditions().matches(r.source(), row.values(), Map.of());
    }

    private Set<String> targets(Rule r, Row row, long actor) {
        if (row == null) return Set.of();
        if (RelationDirectionEnum.OUTGOING.matches(r.config().binding().direction())) {
            var id = row.values().get(r.relation().fieldId());
            return id == null ? Set.of() : Set.of(id.toString());
        }
        return query(r.target(), r.relation().fieldId(), row.id(), null, actor, 200).stream()
                .map(Row::id)
                .collect(java.util.stream.Collectors.toSet());
    }

    private Map<String, Object> calculate(
            Rule r, String id, Map<String, Object> target, long actor) {
        requireSource(r, actor);
        List<Row> source;
        if (RelationDirectionEnum.OUTGOING.matches(r.config().binding().direction()))
            source =
                    id == null
                            ? List.of()
                            : query(r.source(), r.relation().fieldId(), id, null, actor, 10000);
        else {
            var sourceId = target.get(r.relation().fieldId());
            source =
                    sourceId == null
                            ? List.of()
                            : query(r.source(), null, null, sourceId.toString(), actor, 10000);
        }
        source = source.stream().filter(row -> matches(r, row)).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        for (var a : r.config().assignments()) {
            var kind = AutomationAssignmentEnum.fromCode(a.kind());
            Object value;
            if (kind == AutomationAssignmentEnum.EXISTS)
                value = source.isEmpty() ? a.emptyValue() : a.value();
            else if (kind == AutomationAssignmentEnum.COUNT)
                value = Integer.toString(source.size());
            else {
                var values =
                        source.stream()
                                .map(row -> row.values().get(a.sourceFieldId()))
                                .filter(Objects::nonNull)
                                .toList();
                if (values.isEmpty())
                    value =
                            kind == AutomationAssignmentEnum.SUM && a.emptyValue() == null
                                    ? "0"
                                    : a.emptyValue();
                else if (kind == AutomationAssignmentEnum.SUM)
                    value =
                            values.stream()
                                    .map(v -> new BigDecimal(v.toString()))
                                    .reduce(BigDecimal.ZERO, BigDecimal::add)
                                    .toPlainString();
                else {
                    boolean numeric =
                            FieldTypeEnum.fromCode(
                                            DataScope.field(r.source(), a.sourceFieldId()).type())
                                    .isNumeric();
                    Comparator<Object> comparator =
                            numeric
                                    ? Comparator.comparing(v -> new BigDecimal(v.toString()))
                                    : Comparator.comparing(Object::toString);
                    value =
                            (kind == AutomationAssignmentEnum.MIN
                                            ? values.stream().min(comparator)
                                            : values.stream().max(comparator))
                                    .orElse(null);
                }
            }
            result.put(a.fieldId(), value);
        }
        return result;
    }

    private List<Row> query(
            DataCenter.Definition d,
            String fieldId,
            String value,
            String id,
            long actor,
            int maximum) {
        var t = schemas.main(d);
        var b = t.statement(id, null, Long.toString(actor), false);
        String column = fieldId == null ? null : t.columns().get(fieldId);
        if (fieldId != null && column == null) throw invalid("自动更新的关联字段结构已变化");
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
                        column == null ? List.of() : List.of(column),
                        column == null ? "{}" : persistence.write(Map.of(column, value)),
                        null,
                        false,
                        maximum + 1,
                        0,
                        List.of(),
                        "{}",
                        b.actor(),
                        false);
        var rows = records.rows(q).stream().map(persistence::row).toList();
        if (rows.size() > maximum) throw invalid("自动更新关联记录超过 " + maximum + " 条，不能保存部分计算结果");
        return rows;
    }

    private void requireSource(Rule r, long actor) {
        Set<String> fields = new HashSet<>();
        r.config().assignments().stream()
                .map(ApplicationAutomations.Assignment::sourceFieldId)
                .filter(Objects::nonNull)
                .forEach(fields::add);
        conditionFields(r.config().conditions(), fields);
        if (RelationDirectionEnum.OUTGOING.matches(r.config().binding().direction()))
            fields.add(r.relation().fieldId());
        // 可信任务触发的发布规则使用应用自身的共享能力，完整汇总关联来源；
        // 不把这些来源的明细读取权限加入员工授权，也不能用部分可见记录计算总数。
        List<ApplicationAuthorization.ObjectGrant> grants;
        if (taskRule(r, actor)) {
            ApplicationAuthorization.ObjectGrant ceiling =
                    sharing.storedPermission(r.source().objectId(), r.app());
            grants =
                    ceiling == null
                            ? List.of()
                            : List.of(grantValidator.resolve(ceiling, r.source()));
        } else {
            grants = policy.effectiveGrants(r.app(), r.source(), actor);
        }
        Set<String> readable = new HashSet<>();
        for (var g : grants)
            if (ApplicationScopeEnum.ALL.matches(g.scope())
                    && g.actions().contains(ApplicationActionEnum.READ.getCode())
                    && !g.actionScopes().containsKey(ApplicationActionEnum.READ.getCode()))
                readable.addAll(g.readFields());
        if (!readable.containsAll(fields)
                || grants.stream()
                        .noneMatch(
                                g ->
                                        ApplicationScopeEnum.ALL.matches(g.scope())
                                                && g.actions()
                                                        .contains(
                                                                ApplicationActionEnum.READ
                                                                        .getCode())
                                                && !g.actionScopes()
                                                        .containsKey(
                                                                ApplicationActionEnum.READ
                                                                        .getCode())))
            throw invalid("自动更新“" + r.name() + "”需要您在规则所属应用中完整读取来源记录及规则使用字段的权限");
    }

    private void requireTarget(Rule r, String id, Map<String, Object> candidate, long actor) {
        if (taskRule(r, actor) && !taskScope.current().grants().containsKey(r.target().objectId()))
            throw invalid("自动更新目标不在当前任务的应用业务范围内");
        AutomationWriteScope.Context active =
                AutomationWriteScope.taskTarget(r.app(), r.target().objectId());
        if (taskRule(r, actor)
                && (active == null
                        || !active.ruleId().equals(r.id())
                        || !Objects.equals(active.targetRecordId(), id))) {
            Set<String> fields =
                    r.config().assignments().stream()
                            .map(ApplicationAutomations.Assignment::fieldId)
                            .collect(java.util.stream.Collectors.toSet());
            AutomationWriteScope.Context context =
                    new AutomationWriteScope.Context(
                            r.app(),
                            r.version(),
                            r.id(),
                            r.name(),
                            r.source().objectId(),
                            id,
                            r.target().objectId(),
                            fields,
                            id,
                            true);
            AutomationWriteScope.run(
                    context,
                    () -> {
                        requireTarget(r, id, candidate, actor);
                        return null;
                    });
            return;
        }
        var current =
                com.richuang.os.nocode.metadata.service.form.DocumentPolicies.policy(
                        objects.getPublished(r.target().objectId()));
        if (current != null
                && current.lifecycle() != null
                && r.config().assignments().stream()
                        .anyMatch(a -> a.fieldId().equals(current.lifecycle().fieldId())))
            throw invalid("自动更新目标已成为受业务动作控制的状态字段，请调整规则后再保存");
        var access = policy.access(r.app(), r.target(), actor);
        var caps =
                id == null
                        ? access.require(
                                Long.toString(actor), candidate, ApplicationActionEnum.UPDATE)
                        : persistence
                                .authorizedRead(
                                        schemas.main(r.target()),
                                        id,
                                        actor,
                                        false,
                                        access,
                                        ApplicationActionEnum.UPDATE)
                                .permissions();
        if (!caps.writeFields()
                .containsAll(
                        r.config().assignments().stream()
                                .map(ApplicationAutomations.Assignment::fieldId)
                                .toList())) throw invalid("自动更新“" + r.name() + "”缺少目标字段修改权限");
        var after =
                persistence.requireCandidate(
                        schemas.main(r.target()), id, candidate, actor, access);
        if (!after.writeFields()
                .containsAll(
                        r.config().assignments().stream()
                                .map(ApplicationAutomations.Assignment::fieldId)
                                .toList())) throw invalid("自动更新“" + r.name() + "”的结果超出目标字段修改权限");
        if (RelationDirectionEnum.INCOMING.matches(r.config().binding().direction())
                && !caps.readFields().contains(r.relation().fieldId()))
            throw invalid("自动更新缺少目标关联字段读取权限");
    }

    /** 只能由任务编排器授予本次办理上下文，不能借其他应用的规则跨应用扩权。 */
    private boolean taskRule(Rule rule, long actor) {
        TaskEntryRuntimeScope.Invocation invocation = taskScope.current();
        return invocation != null
                && invocation.data() != null
                && invocation.data().writable()
                && invocation.actor() == actor
                && invocation.applicationId().equals(rule.app());
    }

    private void conditionFields(DataScope scope, Set<String> fields) {
        if (scope == null) return;
        scope.conditions().forEach(c -> fields.add(c.fieldId()));
        scope.groups().forEach(group -> conditionFields(group, fields));
    }

    private boolean same(DataCenter.Definition target, String field, Object left, Object right) {
        if (Objects.equals(left, right)) return true;
        if (left == null || right == null) return false;
        // 文本与单选编码保留精确身份，例如“001”与“1”不能被当成同一个值。
        if (!FieldTypeEnum.fromCode(DataScope.field(target, field).type()).isNumeric())
            return left.toString().equals(right.toString());
        try {
            return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
        } catch (NumberFormatException ignored) {
            return left.toString().equals(right.toString());
        }
    }
}
