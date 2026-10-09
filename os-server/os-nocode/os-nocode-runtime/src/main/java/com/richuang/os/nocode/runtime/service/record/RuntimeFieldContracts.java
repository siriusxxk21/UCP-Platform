package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.FieldConversionCompatibility.Field;
import com.richuang.os.nocode.enums.MemberStateEnum;

import java.util.*;

/** 固定版本应用写入当前业务表时，按本次字段集合检查值语义及存储归属。 */
public final class RuntimeFieldContracts {
    private RuntimeFieldContracts() {}

    public static void requireCompatible(
            Definition requested, Definition current, Collection<String> fieldIds) {
        Map<String, Field> before = FieldConversionCompatibility.fields(requested);
        Map<String, Field> after = FieldConversionCompatibility.fields(current);
        for (String id : fieldIds) {
            Field old = before.get(id);
            Field next = after.get(id);
            if (!FieldConversionCompatibility.writeCompatible(old, next)
                    || !Objects.equals(address(requested, id), address(current, id))
                    || !sameRelation(
                            BusinessFields.relation(requested, id),
                            BusinessFields.relation(current, id))
                    || Boolean.TRUE.equals(next.options().generated())
                            && !Boolean.TRUE.equals(old.options().generated())) {
                String name = old == null ? id : old.definition().name();
                throw invalid("字段“" + name + "”的类型、选择来源或引用关系已变化，请先在应用中同步对象版本后再提交");
            }
        }
    }

    private static boolean sameRelation(Relation before, Relation after) {
        if (before == null || after == null) return before == after;
        return Objects.equals(before.id(), after.id())
                && Objects.equals(before.kind(), after.kind())
                && Objects.equals(before.fieldId(), after.fieldId())
                && Objects.equals(before.sourceDetailId(), after.sourceDetailId())
                && Objects.equals(before.targetObjectId(), after.targetObjectId())
                && Objects.equals(before.targetFieldId(), after.targetFieldId());
    }

    private record Address(String schema, String table, String detailId, String column) {}

    private static Address address(Definition definition, String id) {
        FieldDefinition main =
                definition.fields().stream()
                        .filter(f -> f.id().equals(id))
                        .findFirst()
                        .orElse(null);
        if (main != null)
            return address(
                    ObjectTables.main(definition),
                    definition.tableName(),
                    null,
                    main,
                    definition.fieldOptions());
        for (Detail detail : definition.details()) {
            if (MemberStateEnum.INACTIVE.matches(detail.state())) continue;
            FieldDefinition field =
                    detail.fields().stream()
                            .filter(f -> f.id().equals(id))
                            .findFirst()
                            .orElse(null);
            if (field != null)
                return address(
                        ObjectTables.detail(definition, detail),
                        detail.tableName(),
                        detail.id(),
                        field,
                        detail.fieldOptions());
        }
        return null;
    }

    private static Address address(
            TableBinding binding,
            String table,
            String detail,
            FieldDefinition field,
            Map<String, FieldOptions> options) {
        FieldOptions option = options.getOrDefault(field.id(), FieldOptions.defaults());
        return new Address(
                binding.schemaName(),
                table,
                detail,
                option.columnName() == null ? field.code() : option.columnName());
    }
}
