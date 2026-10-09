package com.richuang.os.nocode.runtime.service.handling;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.MemberStateEnum;

import java.util.*;

/** 恢复填写意图时按当前可见模型裁剪，封存材料不可变且不能恢复已撤回的字段权限。 */
public final class HandlingMaterials {
    private HandlingMaterials() {}

    public static ApplicationRecords.Aggregate restore(
            ApplicationRecords.Aggregate initial,
            DataCenter.Definition visible,
            DataCenter.Definition current) {
        ApplicationAuthorization.Capabilities rights = initial.record().permissions();
        Set<String> fields = active(visible.fields(), visible.fieldOptions());
        fields.retainAll(active(current.fields(), current.fieldOptions()));
        fields.retainAll(rights.readFields());
        Set<String> writable = new HashSet<>(rights.writeFields());
        writable.retainAll(fields);
        Set<String> detailIds = new HashSet<>();
        Map<String, List<ApplicationRecords.Row>> details = new LinkedHashMap<>();
        for (DataCenter.Detail detail : visible.details()) {
            DataCenter.Detail now =
                    current.details().stream()
                            .filter(d -> d.id().equals(detail.id()))
                            .findFirst()
                            .orElse(null);
            if (now == null
                    || MemberStateEnum.INACTIVE.matches(now.state())
                    || !rights.readDetails().contains(detail.id())) continue;
            detailIds.add(detail.id());
            Set<String> columns = active(detail.fields(), detail.fieldOptions());
            columns.retainAll(active(now.fields(), now.fieldOptions()));
            ApplicationAuthorization.Capabilities rowRights =
                    new ApplicationAuthorization.Capabilities(
                            rights.actions(),
                            columns,
                            rights.writeDetails().contains(detail.id()) ? columns : Set.of(),
                            Set.of(),
                            Set.of(),
                            Set.of(),
                            Set.of());
            details.put(
                    detail.id(),
                    initial.details().getOrDefault(detail.id(), List.of()).stream()
                            .map(r -> row(r, columns, rowRights))
                            .toList());
        }
        Set<String> relationIds = new HashSet<>();
        visible.relations().forEach(r -> relationIds.add(r.id()));
        relationIds.retainAll(current.relations().stream().map(DataCenter.Relation::id).toList());
        relationIds.retainAll(rights.readRelations());
        Map<String, List<String>> relations = new LinkedHashMap<>();
        initial.relations()
                .forEach(
                        (id, rows) -> {
                            if (relationIds.contains(id)) relations.put(id, rows);
                        });
        Set<String> writeDetails = new HashSet<>(rights.writeDetails());
        writeDetails.retainAll(detailIds);
        Set<String> writeRelations = new HashSet<>(rights.writeRelations());
        writeRelations.retainAll(relationIds);
        ApplicationAuthorization.Capabilities narrowed =
                new ApplicationAuthorization.Capabilities(
                        rights.actions(),
                        fields,
                        writable,
                        detailIds,
                        writeDetails,
                        relationIds,
                        writeRelations);
        return new ApplicationRecords.Aggregate(
                row(initial.record(), fields, narrowed), details, List.of(), relations);
    }

    private static Set<String> active(
            List<FieldDefinition> fields, Map<String, DataCenter.FieldOptions> options) {
        Set<String> result = new HashSet<>();
        for (FieldDefinition field : fields) {
            DataCenter.FieldOptions option = options == null ? null : options.get(field.id());
            if (option == null || !MemberStateEnum.INACTIVE.matches(option.state()))
                result.add(field.id());
        }
        return result;
    }

    private static ApplicationRecords.Row row(
            ApplicationRecords.Row source,
            Set<String> fields,
            ApplicationAuthorization.Capabilities rights) {
        Map<String, Object> values = new LinkedHashMap<>();
        source.values()
                .forEach(
                        (id, value) -> {
                            if (fields.contains(id)) values.put(id, value);
                        });
        Map<String, String> labels = new LinkedHashMap<>();
        if (source.displayValues() != null)
            source.displayValues()
                    .forEach(
                            (id, value) -> {
                                if (fields.contains(id)) labels.put(id, value);
                            });
        return new ApplicationRecords.Row(source.id(), source.revision(), values, rights, labels);
    }
}
