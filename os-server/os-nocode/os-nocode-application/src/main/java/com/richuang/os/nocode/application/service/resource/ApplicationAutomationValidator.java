package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;

import org.springframework.stereotype.Component;

import java.util.*;

/** 自动更新只允许单跳主表引用和普通标量字段；配置不得绕过字段身份与引用目标校验。 */
@Component
public class ApplicationAutomationValidator {
    /** 按日期自动执行的偏移天数上下限。 */
    public static final int MAX_OFFSET_DAYS = 365;

    public ApplicationAutomations.Config validate(
            ApplicationAutomations.Config c, Map<String, DataCenter.Definition> definitions) {
        if (c == null
                || c.binding() == null
                || c.assignments() == null
                || c.assignments().isEmpty()
                || c.assignments().size() > 20) throw invalid("自动更新需要关联关系及 1 到 20 条字段赋值");
        var mode = AutomationModeEnum.fromCode(c.mode());
        if (mode == AutomationModeEnum.DATE) return validateDate(c, definitions);
        var source = definitions.get(c.objectId());
        var target = definitions.get(c.targetObjectId());
        if (source == null || target == null || source.objectId().equals(target.objectId()))
            throw invalid("自动更新需要两个不同的已引用对象");
        if (c.events() == null
                || c.events().isEmpty()
                || !Set.of(
                                RecordChangeOperationEnum.CREATE.getCode(),
                                RecordChangeOperationEnum.UPDATE.getCode(),
                                RecordChangeOperationEnum.DELETE.getCode())
                        .containsAll(c.events())) throw invalid("自动更新触发事件无效");
        if (mode == AutomationModeEnum.MAINTAIN && c.events().size() != 3)
            throw invalid("持续维护必须覆盖新增、修改和删除");
        var relation = relation(c, source, target);
        if (c.conditions() != null) c.conditions().validate(source, false);
        // 持续维护的结果写进目标记录，只在来源记录增删改时重算；「今天」「本月」这类条件过了零点不会自己变，所以不允许。
        if (mode == AutomationModeEnum.MAINTAIN && DataScope.usesRelativeDate(c.conditions()))
            throw invalid(
                    "持续维护不支持相对日期（今天、本月等）：维护结果只在来源记录变化时重算，过了零点不会自己变。"
                            + "请改用具体日期；按当天看数请在视图或统计视图的条件里使用相对日期");
        assignments(c, mode, source, target, relation);
        return new ApplicationAutomations.Config(
                c.objectId(),
                c.targetObjectId(),
                !Boolean.FALSE.equals(c.enabled()),
                c.mode(),
                Set.copyOf(c.events()),
                c.conditions(),
                c.binding(),
                List.copyOf(c.assignments()));
    }

    /**
     * 按日期自动执行：来源上的日期 / 日期时间字段到了「日期 + 偏移天数」那天，对目标记录执行一次赋值（固定值 / 来源字段值）。
     *
     * <p>目标可以是来源记录本身（binding.direction = SELF，目标对象必须就是来源对象），也可以经单跳主表引用找到； 日期字段必须能参与记录范围（SQL
     * 按日期筛选来源），所以读取时计算的字段不能用。不触发事件，events 归一为空集合。
     */
    private ApplicationAutomations.Config validateDate(
            ApplicationAutomations.Config c, Map<String, DataCenter.Definition> definitions) {
        var source = definitions.get(c.objectId());
        var target = definitions.get(c.targetObjectId());
        if (source == null || target == null) throw invalid("按日期自动执行需要已引用的来源与目标对象");
        boolean self = c.binding().self();
        if (self != source.objectId().equals(target.objectId()))
            throw invalid(self ? "更新本条记录时，目标对象必须就是来源对象" : "通过关系更新时，来源与目标需要两个不同的对象");
        int offset = c.offsetDays() == null ? 0 : c.offsetDays();
        if (Math.abs(offset) > MAX_OFFSET_DAYS)
            throw invalid("按日期自动执行的偏移天数需在 -" + MAX_OFFSET_DAYS + " 到 " + MAX_OFFSET_DAYS + " 之间");
        if (c.dateFieldId() == null || c.dateFieldId().isBlank()) throw invalid("请选择来源记录上的日期字段");
        var date = DataScope.field(source, c.dateFieldId());
        if (!Set.of(FieldTypeEnum.DATE, FieldTypeEnum.DATETIME)
                .contains(FieldTypeEnum.fromCode(date.type())))
            throw invalid("按日期自动执行只能选择日期或日期时间字段：" + date.name());
        // 与运行时筛选来源用的是同一条范围条件：能通过记录范围校验的字段才能按日期筛选。
        dateScope(source, c.dateFieldId(), java.time.LocalDate.of(2000, 1, 1))
                .validate(source, false);
        if (c.conditions() != null) c.conditions().validate(source, false);
        assignments(
                c,
                AutomationModeEnum.DATE,
                source,
                target,
                self ? null : relation(c, source, target));
        return new ApplicationAutomations.Config(
                c.objectId(),
                c.targetObjectId(),
                !Boolean.FALSE.equals(c.enabled()),
                c.mode(),
                Set.of(),
                c.conditions(),
                self
                        ? new ApplicationAutomations.Binding(null, ApplicationAutomations.SELF)
                        : c.binding(),
                List.copyOf(c.assignments()),
                c.dateFieldId(),
                offset);
    }

    /**
     * 来源记录「日期字段落在 day 这一天」的记录范围：日期字段等于 day；日期时间字段在 [day 00:00, day+1 00:00)， 带时区的按系统「今天」的时区 （与
     * TODAY() 相同）取这一天的起止。
     */
    public static DataScope dateScope(
            DataCenter.Definition source, String fieldId, java.time.LocalDate day) {
        var field = DataScope.field(source, fieldId);
        if (FieldTypeEnum.DATE.matches(field.type()))
            return new DataScope(
                    "AND",
                    List.of(new DataScope.Condition(fieldId, "eq", day.toString())),
                    List.of());
        var options =
                source.fieldOptions().getOrDefault(fieldId, DataCenter.FieldOptions.defaults());
        boolean zoned =
                options.nativeType() != null && options.nativeType().contains("with time zone");
        java.util.function.Function<java.time.LocalDate, String> start =
                d ->
                        zoned
                                ? d.atStartOfDay(
                                                com.richuang.os.nocode.metadata.service.formula
                                                        .FormulaDates.ZONE)
                                        .toOffsetDateTime()
                                        .toString()
                                : d.atStartOfDay().toString();
        return new DataScope(
                "AND",
                List.of(
                        new DataScope.Condition(fieldId, "gte", start.apply(day)),
                        new DataScope.Condition(fieldId, "lt", start.apply(day.plusDays(1)))),
                List.of());
    }

    private void assignments(
            ApplicationAutomations.Config c,
            AutomationModeEnum mode,
            DataCenter.Definition source,
            DataCenter.Definition target,
            DataCenter.Relation relation) {
        Set<String> used = new HashSet<>();
        for (var a : c.assignments()) {
            if (a == null || !used.add(a.fieldId())) throw invalid("自动更新目标字段重复");
            var field = DataScope.field(target, a.fieldId());
            var type = FieldTypeEnum.fromCode(field.type());
            var document =
                    com.richuang.os.nocode.metadata.service.form.DocumentPolicies.policy(target);
            if (document != null
                    && document.lifecycle() != null
                    && field.id().equals(document.lifecycle().fieldId()))
                throw invalid("自动更新不能修改受业务动作控制的生命周期状态字段");
            if (!scalar(type)
                    || Boolean.TRUE.equals(
                                    target.fieldOptions()
                                            .getOrDefault(
                                                    field.id(), DataCenter.FieldOptions.defaults())
                                            .generated())
                            && !reference(target, field.id())
                    || Objects.equals(ObjectTables.main(target).keyColumn(), column(target, field)))
                throw invalid("自动更新只能写入普通可写标量字段：" + field.name());
            if (relation != null
                    && RelationDirectionEnum.INCOMING.matches(c.binding().direction())
                    && field.id().equals(relation.fieldId())) throw invalid("自动更新不能修改用于定位目标的引用字段");
            var kind = AutomationAssignmentEnum.fromCode(a.kind());
            boolean eventKind =
                    kind == AutomationAssignmentEnum.VALUE
                            || kind == AutomationAssignmentEnum.FIELD;
            if (mode.oneShot() != eventKind) throw invalid("事件赋值与按日期执行使用固定值/来源字段，持续维护使用统计/存在性结果");
            if (kind == AutomationAssignmentEnum.VALUE || kind == AutomationAssignmentEnum.EXISTS) {
                literal(target, field, a.value());
                if (kind == AutomationAssignmentEnum.EXISTS) literal(target, field, a.emptyValue());
            }
            if (kind == AutomationAssignmentEnum.COUNT
                    && (!type.isNumeric() || reference(target, field.id())))
                throw invalid("条数只能写入普通数值字段");
            if (Set.of(
                            AutomationAssignmentEnum.FIELD,
                            AutomationAssignmentEnum.SUM,
                            AutomationAssignmentEnum.MIN,
                            AutomationAssignmentEnum.MAX)
                    .contains(kind)) {
                var from = DataScope.field(source, a.sourceFieldId());
                var fromType = FieldTypeEnum.fromCode(from.type());
                if (!scalar(fromType)
                        || !SelectionFields.linkCompatible(source, from, target, field)
                        || fromType.isDecimal() && type == FieldTypeEnum.INTEGER)
                    throw invalid("来源与目标字段的类型、选项或引用对象不兼容");
                if (kind != AutomationAssignmentEnum.FIELD) {
                    if (reference(source, from.id()) || reference(target, field.id()))
                        throw invalid("不能对对象引用 ID 求和或取极值");
                    if (kind == AutomationAssignmentEnum.SUM && !fromType.isNumeric()
                            || kind != AutomationAssignmentEnum.SUM
                                    && !fromType.isNumeric()
                                    && !Set.of(
                                                    FieldTypeEnum.DATE,
                                                    FieldTypeEnum.DATETIME,
                                                    FieldTypeEnum.TIME)
                                            .contains(fromType)) throw invalid("统计来源需要数值或日期时间字段");
                    literal(
                            target,
                            field,
                            kind == AutomationAssignmentEnum.SUM && a.emptyValue() == null
                                    ? "0"
                                    : a.emptyValue());
                }
            }
        }
    }

    private boolean scalar(FieldTypeEnum type) {
        return Set.of(
                        FieldTypeEnum.TEXT,
                        FieldTypeEnum.TEXTAREA,
                        FieldTypeEnum.INTEGER,
                        FieldTypeEnum.DECIMAL,
                        FieldTypeEnum.MONEY,
                        FieldTypeEnum.PERCENT,
                        FieldTypeEnum.BOOLEAN,
                        FieldTypeEnum.DATE,
                        FieldTypeEnum.DATETIME,
                        FieldTypeEnum.TIME,
                        FieldTypeEnum.SELECT,
                        FieldTypeEnum.REFERENCE,
                        FieldTypeEnum.UUID,
                        FieldTypeEnum.ORGANIZATION,
                        FieldTypeEnum.USER,
                        FieldTypeEnum.DEPARTMENT,
                        FieldTypeEnum.POST,
                        FieldTypeEnum.USER_GROUP)
                .contains(type);
    }

    private void literal(DataCenter.Definition d, FieldDefinition f, Object value) {
        if (value == null) {
            if (Boolean.TRUE.equals(f.required())) throw invalid("自动更新必填目标需要空集合默认值：" + f.name());
        } else DataScope.scalar(d, f, value);
    }

    private String column(DataCenter.Definition d, FieldDefinition field) {
        return Objects.toString(
                d.fieldOptions()
                        .getOrDefault(field.id(), DataCenter.FieldOptions.defaults())
                        .columnName(),
                field.code());
    }

    private boolean reference(DataCenter.Definition d, String field) {
        return d.relations().stream()
                .anyMatch(r -> r.sourceDetailId() == null && Objects.equals(r.fieldId(), field));
    }

    public static DataCenter.Relation relation(
            ApplicationAutomations.Config c,
            DataCenter.Definition source,
            DataCenter.Definition target) {
        var direction = RelationDirectionEnum.fromCode(c.binding().direction());
        var owner = direction == RelationDirectionEnum.OUTGOING ? source : target;
        var other = direction == RelationDirectionEnum.OUTGOING ? target : source;
        return owner.relations().stream()
                .filter(
                        r ->
                                r.id().equals(c.binding().relationId())
                                        && r.sourceDetailId() == null
                                        && RelationTypeEnum.REFERENCE.matches(r.kind())
                                        && r.targetObjectId().equals(other.objectId()))
                .findFirst()
                .orElseThrow(() -> invalid("自动更新需要来源与目标间的单跳主表引用关系"));
    }

    /**
     * 同一字段的写入者：一次性赋值（事件赋值、按日期自动执行）可以多条写同一字段，按执行先后后写覆盖；持续维护独占目标字段，
     * 不能与任何其他规则共写（它随来源变化重算，会与一次性赋值互相覆盖）。
     *
     * <p>对象依赖也必须无环，防止跨应用反复回写。按日期自动执行不由数据变化触发，不会形成回写环，不计入依赖。
     */
    public void graph(List<ApplicationAutomations.Config> configs) {
        Map<String, List<ApplicationAutomations.Config>> writers = new HashMap<>();
        Map<String, Set<String>> edges = new HashMap<>();
        for (var c : configs) {
            if (Boolean.FALSE.equals(c.enabled())) continue;
            for (var a : c.assignments()) {
                String field = c.targetObjectId() + ":" + a.fieldId();
                List<ApplicationAutomations.Config> fieldWriters =
                        writers.computeIfAbsent(field, key -> new ArrayList<>());
                if (fieldWriters.stream().anyMatch(existing -> !allowsSharedWrite(existing, c)))
                    throw invalid("持续维护的字段由该规则独占：多个自动更新规则不能写入同一目标字段");
                fieldWriters.add(c);
            }
            if (!AutomationModeEnum.DATE.matches(c.mode()))
                edges.computeIfAbsent(c.objectId(), k -> new HashSet<>()).add(c.targetObjectId());
        }
        for (String node : edges.keySet()) visit(node, edges, new HashSet<>(), new HashSet<>());
    }

    /** 两条规则能否写同一目标字段：两条都是一次性赋值（事件赋值或按日期）才可以；只要有一条是持续维护就不行。 */
    public boolean allowsSharedWrite(
            ApplicationAutomations.Config existing, ApplicationAutomations.Config candidate) {
        return AutomationModeEnum.fromCode(existing.mode()).oneShot()
                && AutomationModeEnum.fromCode(candidate.mode()).oneShot();
    }

    private void visit(
            String node, Map<String, Set<String>> edges, Set<String> path, Set<String> done) {
        if (done.contains(node)) return;
        if (!path.add(node)) throw invalid("自动更新对象依赖存在循环，请移除反向回写链路");
        for (String next : edges.getOrDefault(node, Set.of())) visit(next, edges, path, done);
        path.remove(node);
        done.add(node);
    }
}
