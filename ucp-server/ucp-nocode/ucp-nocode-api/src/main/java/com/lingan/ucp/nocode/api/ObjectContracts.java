package com.lingan.ucp.nocode.api;

import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.*;

import java.util.*;
import java.util.stream.Collectors;

/** 对象版本的业务兼容性。允许展示调整和可选字段扩展，保护已发布应用依赖的读写语义。 */
public final class ObjectContracts {
    private ObjectContracts() {}

    /** previous 为调用方固定的旧版本，current 为将要使用的实际对象契约。 */
    public static List<String> breakingChanges(Definition previous, Definition current) {
        Set<String> errors = new LinkedHashSet<>();
        if (!Objects.equals(previous.objectId(), current.objectId())) errors.add("对象身份已变化");
        binding(
                previous.tableName(),
                ObjectTables.main(previous),
                current.tableName(),
                ObjectTables.main(current),
                errors);
        fields(
                previous.fields(),
                previous.fieldOptions(),
                current.fields(),
                current.fieldOptions(),
                current.relations(),
                errors);
        uniqueIndexes(previous.indexes(), current.indexes(), errors);
        Map<String, Relation> relations =
                current.relations().stream().collect(Collectors.toMap(Relation::id, r -> r));
        for (var old : previous.relations()) {
            var next = relations.get(old.id());
            if (next == null
                    || !Objects.equals(old.kind(), next.kind())
                    || !Objects.equals(old.targetObjectId(), next.targetObjectId())
                    || !Objects.equals(old.fieldId(), next.fieldId())
                    || !Objects.equals(old.sourceDetailId(), next.sourceDetailId())
                    || !Objects.equals(old.targetFieldId(), next.targetFieldId())
                    || !Objects.equals(old.onDelete(), next.onDelete())
                    || !Objects.equals(
                            Boolean.TRUE.equals(old.required()),
                            Boolean.TRUE.equals(next.required())))
                errors.add("关联“" + old.name() + "”的目标、归属或删除规则已变化");
        }
        Set<String> oldRelationIds =
                previous.relations().stream().map(Relation::id).collect(Collectors.toSet());
        Set<String> oldFields =
                previous.fields().stream().map(FieldDefinition::id).collect(Collectors.toSet());
        previous.details().forEach(d -> d.fields().forEach(f -> oldFields.add(f.id())));
        for (var relation : current.relations())
            if (!oldRelationIds.contains(relation.id()) && oldFields.contains(relation.fieldId()))
                errors.add("既有字段新增关联约束：“" + relation.name() + "”");
        for (var old : previous.details()) {
            if (MemberStateEnum.INACTIVE.matches(old.state())) continue;
            var next =
                    current.details().stream()
                            .filter(
                                    d ->
                                            Objects.equals(d.id(), old.id())
                                                    && !MemberStateEnum.INACTIVE.matches(d.state()))
                            .findFirst()
                            .orElse(null);
            if (next == null) {
                errors.add("内部明细“" + old.name() + "”已移除或停用");
                continue;
            }
            binding(
                    old.tableName(),
                    ObjectTables.detail(previous, old),
                    next.tableName(),
                    ObjectTables.detail(current, next),
                    errors);
            fields(
                    old.fields(),
                    old.fieldOptions(),
                    next.fields(),
                    next.fieldOptions(),
                    RelationSources.in(current, next.id()),
                    errors);
            uniqueIndexes(old.indexes(), next.indexes(), errors);
        }
        return List.copyOf(errors);
    }

    private static void binding(
            String oldName,
            TableBinding old,
            String nextName,
            TableBinding next,
            Set<String> errors) {
        if (!Objects.equals(oldName, nextName)
                || !Objects.equals(old.schemaName(), next.schemaName())
                || !Objects.equals(old.keyColumn(), next.keyColumn())
                || !Objects.equals(old.parentColumn(), next.parentColumn())
                || !Objects.equals(old.source(), next.source())
                || Boolean.TRUE.equals(old.readOnly()) != Boolean.TRUE.equals(next.readOnly()))
            errors.add("数据表“" + oldName + "”的绑定、主键、归属或读写模式已变化");
    }

    private static boolean active(FieldDefinition f, Map<String, FieldOptions> options) {
        return !MemberStateEnum.INACTIVE.matches(
                options.getOrDefault(f.id(), FieldOptions.defaults()).state());
    }

    private static void fields(
            List<FieldDefinition> before,
            Map<String, FieldOptions> beforeOptions,
            List<FieldDefinition> after,
            Map<String, FieldOptions> afterOptions,
            List<Relation> relations,
            Set<String> errors) {
        Map<String, FieldDefinition> current =
                after.stream()
                        .filter(f -> active(f, afterOptions))
                        .collect(Collectors.toMap(FieldDefinition::id, f -> f));
        Set<String> existing = new HashSet<>();
        for (var old : before) {
            if (!active(old, beforeOptions)) continue;
            existing.add(old.id());
            var next = current.get(old.id());
            if (next == null) {
                errors.add("字段“" + old.name() + "”已移除或停用");
                continue;
            }
            var o = beforeOptions.getOrDefault(old.id(), FieldOptions.defaults());
            var n = afterOptions.getOrDefault(next.id(), FieldOptions.defaults());
            boolean changed =
                    !OrderedCalculations.storageModeCompatible(o, n)
                            || !SelectionFields.identity(old, o)
                                    .equals(SelectionFields.identity(next, n))
                            || !Objects.equals(old.type(), next.type())
                            || !Objects.equals(
                                    o.columnName() == null ? old.code() : o.columnName(),
                                    n.columnName() == null ? next.code() : n.columnName())
                            || old.length() != null
                                    && next.length() != null
                                    && next.length() < old.length()
                            || !Objects.equals(old.precision(), next.precision())
                            || !Objects.equals(old.scale(), next.scale())
                            || Boolean.TRUE.equals(old.required())
                                    != Boolean.TRUE.equals(next.required())
                            || Boolean.TRUE.equals(old.unique())
                                    != Boolean.TRUE.equals(next.unique())
                            || !Objects.equals(o.defaultValue(), n.defaultValue())
                            || !Objects.equals(o.pattern(), n.pattern())
                            || !Objects.equals(o.minimum(), n.minimum())
                            || !Objects.equals(o.maximum(), n.maximum())
                            || !Objects.equals(o.expression(), n.expression())
                            || !Objects.equals(o.resultType(), n.resultType())
                            || !Objects.equals(o.nativeType(), n.nativeType())
                            || Boolean.TRUE.equals(o.primaryKey())
                                    != Boolean.TRUE.equals(n.primaryKey())
                            || Boolean.TRUE.equals(o.generated())
                                    != Boolean.TRUE.equals(n.generated());
            Set<String> values =
                    (n.options() == null ? List.<Option>of() : n.options())
                            .stream()
                                    .filter(v -> !Boolean.TRUE.equals(v.disabled()))
                                    .map(Option::code)
                                    .collect(Collectors.toSet());
            if (o.options() != null
                    && o.options().stream()
                            .filter(v -> !Boolean.TRUE.equals(v.disabled()))
                            .anyMatch(v -> !values.contains(v.code()))) changed = true;
            if (changed) errors.add("字段“" + old.name() + "”的类型、约束或计算规则不兼容");
        }
        for (var next : current.values()) {
            var options = afterOptions.getOrDefault(next.id(), FieldOptions.defaults());
            if (!existing.contains(next.id()) && OrderedCalculations.stored(options))
                errors.add("新增有序落库字段“" + next.name() + "”，旧应用需同步对象版本后再写入");
            boolean automatic =
                    FieldTypeEnum.fromCode(next.type()).isComputed()
                            || FieldTypeEnum.AUTO_NUMBER.matches(next.type())
                            || Boolean.TRUE.equals(options.generated())
                                    && relations.stream()
                                            .noneMatch(r -> Objects.equals(r.fieldId(), next.id()));
            if (!existing.contains(next.id())
                    && Boolean.TRUE.equals(next.required())
                    && options.defaultValue() == null
                    && !automatic) errors.add("新增必填字段“" + next.name() + "”没有默认值，旧应用无法填写");
        }
    }

    private static void uniqueIndexes(List<Index> before, List<Index> after, Set<String> errors) {
        var old =
                before.stream()
                        .filter(i -> Boolean.TRUE.equals(i.unique()))
                        .map(ObjectContracts::indexKey)
                        .collect(Collectors.toSet());
        var next =
                after.stream()
                        .filter(i -> Boolean.TRUE.equals(i.unique()))
                        .map(ObjectContracts::indexKey)
                        .collect(Collectors.toSet());
        if (!old.equals(next)) errors.add("唯一索引约束已变化");
    }

    private static String indexKey(Index index) {
        return new TreeSet<>(index.fieldIds()) + ":" + Boolean.TRUE.equals(index.parentScoped());
    }
}
