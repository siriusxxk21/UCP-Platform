package com.richuang.os.nocode.api;

import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 新旧版本统一的表绑定读取；旧快照按原规则解释，不回写不可变历史版本。 */
public final class ObjectTables {
    private ObjectTables() {}

    public record Ref(String schema, String name) {
        public String key(String defaultSchema) {
            if (schema.equals(defaultSchema) && !name.startsWith("@table:")) return name;
            return "@table:"
                    + Base64.getUrlEncoder()
                            .withoutPadding()
                            .encodeToString(
                                    (schema + "\0" + name).getBytes(StandardCharsets.UTF_8));
        }
    }

    public static Ref fromKey(String key, String defaultSchema) {
        if (key.startsWith("@table:")) {
            String[] pair =
                    new String(
                                    Base64.getUrlDecoder().decode(key.substring(7)),
                                    StandardCharsets.UTF_8)
                            .split("\0", -1);
            if (pair.length != 2) throw new IllegalArgumentException("表绑定键无效");
            return new Ref(pair[0], pair[1]);
        }
        return new Ref(defaultSchema, key);
    }

    public static TableBinding main(Definition d) {
        if (d.mainBinding() != null) return d.mainBinding();
        if (ObjectSourceEnum.GENERATED.matches(d.source()))
            return TableBinding.generated(d.schemaName(), false);
        String key =
                d.fieldOptions().values().stream()
                        .filter(o -> Boolean.TRUE.equals(o.primaryKey()))
                        .map(FieldOptions::columnName)
                        .findFirst()
                        .orElse("id");
        return new TableBinding(
                d.source(),
                d.schemaName(),
                key,
                null,
                StructureModeEnum.RETAIN.getCode(),
                d.readOnly(),
                false,
                null);
    }

    public static TableBinding detail(Definition d, Detail t) {
        return t.binding() == null ? TableBinding.generated(d.schemaName(), true) : t.binding();
    }

    /** 应用侧始终获得完整表级契约；归一化仅发生在返回值，不改写历史版本。 */
    public static Definition normalize(Definition d) {
        var details =
                d.details().stream()
                        .map(
                                t ->
                                        new Detail(
                                                t.id(),
                                                t.code(),
                                                t.name(),
                                                t.tableName(),
                                                t.state(),
                                                t.fields(),
                                                t.fieldOptions(),
                                                t.indexes(),
                                                detail(d, t)))
                        .toList();
        var indexes =
                d.indexes().stream()
                        .map(
                                i ->
                                        new Index(
                                                i.id(),
                                                i.code(),
                                                i.name(),
                                                i.unique(),
                                                i.fieldIds(),
                                                Boolean.TRUE.equals(i.parentScoped())))
                        .toList();
        var main = main(d);
        return new Definition(
                d.objectId(),
                d.objectCode(),
                d.objectName(),
                d.description(),
                d.schemaName(),
                d.tableName(),
                d.source(),
                Boolean.TRUE.equals(main.readOnly()),
                d.titleFieldId(),
                d.settings(),
                d.fields(),
                d.fieldOptions(),
                d.relations(),
                indexes,
                details,
                main);
    }

    public static Map<Ref, TableBinding> bindings(Definition d) {
        Map<Ref, TableBinding> result = new LinkedHashMap<>();
        var main = main(d);
        result.put(new Ref(main.schemaName(), d.tableName()), main);
        for (var t : d.details()) {
            var b = detail(d, t);
            result.put(new Ref(b.schemaName(), t.tableName()), b);
        }
        return result;
    }
}
