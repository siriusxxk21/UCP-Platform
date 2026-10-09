package com.lingan.ucp.nocode.api;

import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.enums.RelationTypeEnum;

import java.util.*;

/** 应用逻辑字段投影；不用于物理建表、字段授权或直接写入主表。 */
public final class BusinessFields {
    private BusinessFields() {}

    public static String key(DataCenter.Relation relation) {
        return "relation_" + relation.id();
    }

    public static DataCenter.Relation relation(DataCenter.Definition d, String id) {
        if (id == null) return null;
        return d.relations().stream()
                .filter(
                        r ->
                                Objects.equals(r.fieldId(), id)
                                        || RelationTypeEnum.MANY_TO_MANY.matches(r.kind())
                                                && key(r).equals(id))
                .findFirst()
                .orElse(null);
    }

    public static boolean multiple(DataCenter.Relation relation) {
        return relation != null && RelationTypeEnum.MANY_TO_MANY.matches(relation.kind());
    }

    public static List<FieldDefinition> fields(DataCenter.Definition d) {
        var fields = new ArrayList<>(d.fields());
        for (var relation : d.relations())
            if (multiple(relation))
                fields.add(
                        new FieldDefinition(
                                key(relation),
                                key(relation),
                                relation.code(),
                                relation.name(),
                                FieldTypeEnum.MULTI_SELECT.getCode(),
                                null,
                                null,
                                null,
                                relation.required(),
                                false,
                                fields.size()));
        return fields;
    }

    public static boolean linkable(FieldDefinition field) {
        return !Set.of(
                        FieldTypeEnum.MULTI_SELECT,
                        FieldTypeEnum.IMAGE,
                        FieldTypeEnum.ATTACHMENT,
                        FieldTypeEnum.REGION,
                        FieldTypeEnum.CASCADE,
                        FieldTypeEnum.SUMMARY,
                        FieldTypeEnum.RICH_TEXT)
                .contains(FieldTypeEnum.fromCode(field.type()));
    }
}
