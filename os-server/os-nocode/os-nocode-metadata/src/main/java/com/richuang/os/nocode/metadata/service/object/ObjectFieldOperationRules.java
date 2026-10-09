package com.richuang.os.nocode.metadata.service.object;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.ObjectOperationPreview.Impact;
import com.richuang.os.nocode.enums.*;

import java.util.*;

/** 字段停用和恢复的纯规则；供只读模拟及正式草稿保存共同调用。 */
final class ObjectFieldOperationRules {
    private ObjectFieldOperationRules() {}

    static List<Impact> references(Definition definition, List<Dependency> dependencies) {
        List<Impact> impacts = new ArrayList<>();
        Set<String> active = new HashSet<>();
        definition.fields().forEach(field -> active.add(field.id()));
        if (!active.contains(definition.titleFieldId()))
            impacts.add(
                    ObjectOperationImpacts.own(
                            ObjectOperationCheckEnum.TITLE_FIELD,
                            definition,
                            definition.titleFieldId(),
                            "基本信息 / 记录标题",
                            "不能停用记录标题字段",
                            "先在基本信息中选择其他有效记录标题字段，再重新检查"));
        definition.details().stream()
                .filter(detail -> MemberStateEnum.ACTIVE.matches(detail.state()))
                .flatMap(detail -> detail.fields().stream())
                .forEach(field -> active.add(field.id()));
        for (Relation relation : definition.relations()) {
            if (RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())) continue;
            List<FieldDefinition> fields = definition.fields();
            if (relation.sourceDetailId() != null) {
                Detail detail =
                        definition.details().stream()
                                .filter(
                                        item ->
                                                relation.sourceDetailId().equals(item.id())
                                                        && MemberStateEnum.ACTIVE.matches(
                                                                item.state()))
                                .findFirst()
                                .orElse(null);
                if (detail == null) {
                    impacts.add(
                            ObjectOperationImpacts.own(
                                    ObjectOperationCheckEnum.RELATION_FIELD,
                                    definition,
                                    relation.fieldId(),
                                    "对象关系 / " + relation.name(),
                                    "关系仍引用已停用明细",
                                    "先删除或调整此关系的来源明细，再重新检查"));
                    continue;
                }
                fields = detail.fields();
            }
            if (fields.stream().noneMatch(field -> Objects.equals(field.id(), relation.fieldId())))
                impacts.add(
                        ObjectOperationImpacts.own(
                                ObjectOperationCheckEnum.RELATION_FIELD,
                                definition,
                                relation.fieldId(),
                                "对象关系 / " + relation.name(),
                                "关系仍引用已停用或不属于来源表的字段：" + relation.name(),
                                "先删除或改绑此关系，再重新检查；不会自动删除关系"));
        }
        List<Index> indexes = new ArrayList<>(definition.indexes());
        definition.details().forEach(detail -> indexes.addAll(detail.indexes()));
        for (Index index : indexes)
            for (String fieldId : index.fieldIds())
                if (!active.contains(fieldId))
                    impacts.add(
                            ObjectOperationImpacts.own(
                                    ObjectOperationCheckEnum.INDEX_FIELD,
                                    definition,
                                    fieldId,
                                    "索引 / " + index.name(),
                                    "索引仍引用已停用字段：" + index.name(),
                                    "先删除或调整此索引的字段，再重新检查"));
        for (Dependency dependency : dependencies)
            for (String fieldId : dependency.fieldIds())
                if (!active.contains(fieldId)) {
                    Impact impact =
                            ObjectOperationImpacts.dependency(
                                    dependency,
                                    fieldId,
                                    !DependencyKindEnum.APP.matches(dependency.sourceKind()));
                    impacts.add(
                            new Impact(
                                    impact.code(),
                                    impact.blocking(),
                                    impact.sourceKind(),
                                    impact.sourceId(),
                                    impact.sourceName(),
                                    impact.fieldId(),
                                    impact.location(),
                                    impact.blocking()
                                            ? "字段仍被引用：" + dependency.sourceName()
                                            : impact.message(),
                                    impact.resolution(),
                                    impact.route()));
                }
        return List.copyOf(new LinkedHashSet<>(impacts));
    }

    static boolean restorationIdentityMatches(FieldDefinition original, FieldDefinition changed) {
        return Objects.equals(original.id(), changed.key())
                && Objects.equals(original.code(), changed.code())
                && Objects.equals(original.type(), changed.type())
                && Objects.equals(original.length(), changed.length())
                && Objects.equals(original.precision(), changed.precision())
                && Objects.equals(original.scale(), changed.scale());
    }
}
