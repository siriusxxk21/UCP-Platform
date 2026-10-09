package com.richuang.os.nocode.api;

import com.richuang.os.nocode.api.DataCenter.*;

import java.util.*;

/** 单一关系集合按来源表解释，避免把明细引用误用为主表关系。 */
public final class RelationSources {
    private RelationSources() {}

    public record Source(
            String detailId,
            String tableName,
            TableBinding binding,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> options) {}

    public static Source source(Definition definition, Relation relation) {
        if (relation.sourceDetailId() == null)
            return new Source(
                    null,
                    definition.tableName(),
                    ObjectTables.main(definition),
                    definition.fields(),
                    definition.fieldOptions());
        var detail =
                definition.details().stream()
                        .filter(d -> Objects.equals(d.id(), relation.sourceDetailId()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("关系来源明细不存在"));
        return new Source(
                detail.id(),
                detail.tableName(),
                ObjectTables.detail(definition, detail),
                detail.fields(),
                detail.fieldOptions());
    }

    public static List<Relation> main(Definition definition) {
        return in(definition, null);
    }

    public static List<Relation> in(Definition definition, String detailId) {
        return definition.relations().stream()
                .filter(r -> Objects.equals(r.sourceDetailId(), detailId))
                .toList();
    }
}
