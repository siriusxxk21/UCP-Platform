package com.richuang.os.nocode.runtime.service.taskcenter;

import com.richuang.os.nocode.api.*;

import java.util.*;

/** 历史材料以保存时值为准，同时受当前字段、明细、关系可见性限制；不返回写操作能力。 */
public final class TaskMaterials {
    private TaskMaterials() {}

    public static ApplicationRecords.Aggregate project(
            ApplicationRecords.Aggregate frozen,
            ApplicationRecords.Aggregate current,
            DataCenter.Definition definition) {
        ApplicationAuthorization.Capabilities rights = current.record().permissions();
        Set<String> fields = new HashSet<>(rights.readFields());
        fields.retainAll(definition.fields().stream().map(FieldDefinition::id).toList());
        Set<String> detailIds = new HashSet<>(rights.readDetails());
        detailIds.retainAll(definition.details().stream().map(DataCenter.Detail::id).toList());
        Set<String> relationIds = new HashSet<>(rights.readRelations());
        relationIds.retainAll(
                definition.relations().stream().map(DataCenter.Relation::id).toList());
        ApplicationAuthorization.Capabilities readOnly =
                new ApplicationAuthorization.Capabilities(
                        Set.of("READ"),
                        fields,
                        Set.of(),
                        detailIds,
                        Set.of(),
                        relationIds,
                        Set.of());
        ApplicationRecords.Row record = row(frozen.record(), fields, readOnly);
        Map<String, List<ApplicationRecords.Row>> details = new LinkedHashMap<>();
        for (DataCenter.Detail detail : definition.details()) {
            if (!detailIds.contains(detail.id())) continue;
            Map<String, ApplicationRecords.Row> visibleRows = new HashMap<>();
            current.details()
                    .getOrDefault(detail.id(), List.of())
                    .forEach(r -> visibleRows.put(r.id(), r));
            List<ApplicationRecords.Row> projected = new ArrayList<>();
            for (ApplicationRecords.Row saved :
                    frozen.details().getOrDefault(detail.id(), List.of())) {
                ApplicationRecords.Row now = visibleRows.get(saved.id());
                if (now == null) continue;
                Set<String> allowed =
                        new HashSet<>(detail.fields().stream().map(FieldDefinition::id).toList());
                if (now.permissions() != null) allowed.retainAll(now.permissions().readFields());
                projected.add(
                        row(
                                saved,
                                allowed,
                                new ApplicationAuthorization.Capabilities(
                                        Set.of("READ"), allowed, Set.of(), Set.of(), Set.of())));
            }
            details.put(detail.id(), projected);
        }
        Map<String, List<String>> relations = new LinkedHashMap<>();
        for (String id : relationIds) {
            Set<String> visibleTargets =
                    new HashSet<>(current.relations().getOrDefault(id, List.of()));
            relations.put(
                    id,
                    frozen.relations().getOrDefault(id, List.of()).stream()
                            .filter(visibleTargets::contains)
                            .toList());
        }
        return new ApplicationRecords.Aggregate(record, details, List.of(), relations);
    }

    private static ApplicationRecords.Row row(
            ApplicationRecords.Row row,
            Set<String> fields,
            ApplicationAuthorization.Capabilities rights) {
        Map<String, Object> values = new LinkedHashMap<>();
        row.values()
                .forEach(
                        (field, value) -> {
                            if (fields.contains(field)) values.put(field, value);
                        });
        Map<String, String> displays = new LinkedHashMap<>();
        if (row.displayValues() != null)
            row.displayValues()
                    .forEach(
                            (field, value) -> {
                                if (fields.contains(field)) displays.put(field, value);
                            });
        return new ApplicationRecords.Row(row.id(), row.revision(), values, rights, displays);
    }
}
