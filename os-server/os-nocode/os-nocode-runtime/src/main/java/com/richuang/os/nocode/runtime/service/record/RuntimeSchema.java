package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.mybatis.core.metadata.*;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.request.ReadRequestMemo;
import com.richuang.os.nocode.runtime.dal.query.RecordStatement;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.*;

/** 每笔请求重新核对固定对象版本和实际目录；草稿变动不会偷偷扩大可访问列。 */
@Service
public class RuntimeSchema {
    @Resource private DatabaseMetadataReader database;
    @Resource private PostgreSqlCommandMapper commands;
    @Resource private DataObjectApi objects;

    public record Table(
            String schema,
            String name,
            TableBinding binding,
            DatabaseMetadata.Table physical,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> options,
            Map<String, String> columns,
            DatabaseMetadata.Column key,
            boolean writable,
            Definition definition) {
        /** 兼容只读查询的临时投影；业务写入必须由 main/detail 携带固定对象契约。 */
        public Table(
                String schema,
                String name,
                TableBinding binding,
                DatabaseMetadata.Table physical,
                List<FieldDefinition> fields,
                Map<String, FieldOptions> options,
                Map<String, String> columns,
                DatabaseMetadata.Column key,
                boolean writable) {
            this(schema, name, binding, physical, fields, options, columns, key, writable, null);
        }

        public String column(FieldDefinition f) {
            var o = options.getOrDefault(f.id(), FieldOptions.defaults());
            return o.columnName() == null ? f.code() : o.columnName();
        }

        public boolean generatedKey() {
            return (key.identityKind() != null && !key.identityKind().isBlank())
                    || key.defaultExpression() != null;
        }

        public String keyField() {
            return fields.stream()
                    .filter(f -> column(f).equals(key.name()))
                    .map(FieldDefinition::id)
                    .findFirst()
                    .orElse(null);
        }

        public RecordStatement statement(String id, String parent, String actor, boolean lock) {
            return new RecordStatement(
                    schema,
                    name,
                    key.name(),
                    columns,
                    fields.stream()
                            .filter(
                                    f ->
                                            Set.of(
                                                            FieldTypeEnum.TEXT,
                                                            FieldTypeEnum.TEXTAREA,
                                                            FieldTypeEnum.SELECT,
                                                            FieldTypeEnum.AUTO_NUMBER)
                                                    .contains(FieldTypeEnum.fromCode(f.type())))
                            .filter(f -> columns.containsKey(f.id()))
                            .map(this::column)
                            .toList(),
                    physical.columns().stream()
                            .filter(
                                    c ->
                                            c.nativeType()
                                                    .matches("(smallint|integer|bigint|numeric.*)"))
                            .map(DatabaseMetadata.Column::name)
                            .toList(),
                    physical.columns().stream().anyMatch(c -> c.name().equals("deleted")),
                    id,
                    binding.parentColumn(),
                    parent,
                    null,
                    null,
                    List.of(),
                    "{}",
                    null,
                    false,
                    501,
                    0,
                    List.of(),
                    "{}",
                    actor,
                    lock);
        }
    }

    public Table main(Definition d) {
        return table(d.tableName(), ObjectTables.main(d), d.fields(), d.fieldOptions(), d);
    }

    public Table detail(Definition d, Detail t) {
        return table(t.tableName(), ObjectTables.detail(d, t), t.fields(), t.fieldOptions(), d);
    }

    public Table table(
            String name,
            TableBinding binding,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> options) {
        return table(name, binding, fields, options, null);
    }

    private Table table(
            String name,
            TableBinding binding,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> options,
            Definition definition) {
        // 只读作用域内同一事务（或同一请求的无事务段）对同一张表只自省一次；作用域外仍每次读取当前目录。
        var actual =
                ReadRequestMemo.once(
                                ReadRequestMemo.key("physical.table", binding.schemaName(), name),
                                () -> database.readTable(binding.schemaName(), name))
                        .orElseThrow(() -> invalid("绑定表不存在：" + name));
        var keys = actual.columns().stream().filter(c -> c.primaryKeyPosition() > 0).toList();
        if (keys.size() != 1 || !keys.getFirst().name().equals(binding.keyColumn()))
            throw invalid("表主键已经变化，请重新确认对象绑定");
        var active =
                fields.stream()
                        .filter(
                                f -> {
                                    var o = options.getOrDefault(f.id(), FieldOptions.defaults());
                                    return !MemberStateEnum.INACTIVE.matches(o.state());
                                })
                        .toList();
        Map<String, String> columns = new LinkedHashMap<>();
        for (var f : active) {
            if (FieldTypeEnum.SUMMARY.matches(f.type())) continue;
            var o = options.getOrDefault(f.id(), FieldOptions.defaults());
            String column = o.columnName() == null ? f.code() : o.columnName();
            var real =
                    actual.columns().stream()
                            .filter(c -> c.name().equals(column))
                            .findFirst()
                            .orElseThrow(() -> invalid("字段对应列不存在：" + f.name()));
            if (!FieldStorage.accepts(FieldStorage.sqlType(f, o), real.nativeType()))
                throw invalid("字段物理类型发生变化：" + f.name());
            columns.put(f.id(), column);
        }
        if (binding.parentColumn() != null
                && actual.columns().stream()
                        .noneMatch(c -> c.name().equals(binding.parentColumn())))
            throw invalid("明细表的归属列已变化");
        boolean writable =
                !Boolean.TRUE.equals(binding.readOnly())
                        && BaseDOColumns.differences(actual).isEmpty()
                        && actual.statistics() != null
                        && Boolean.TRUE.equals(actual.statistics().canWrite())
                        && !Boolean.TRUE.equals(actual.statistics().rowSecurity());
        return new Table(
                binding.schemaName(),
                name,
                binding,
                actual,
                active,
                options,
                columns,
                keys.getFirst(),
                writable,
                definition);
    }

    /**
     * 仅校验真正写入的列，兼容字段不因对象版本不同被连带禁写。表锁沿记录写入顺序获取， 与发布的结构锁互斥；锁语句刷新 MyBatis 本地缓存后再取最新版本，避免等待发布后沿用旧契约。
     */
    public void requireWriteCompatible(Table table, Collection<String> columns) {
        Set<String> fields = new LinkedHashSet<>();
        table.columns()
                .forEach(
                        (field, column) -> {
                            if (columns.contains(column)) fields.add(field);
                        });
        if (fields.isEmpty()) return;
        if (table.definition() == null) throw invalid("业务写入缺少对象字段契约");
        commands.execute(PostgreSqlCommands.writeLock(table.schema(), table.name()));
        RuntimeFieldContracts.requireCompatible(
                table.definition(), objects.getPublished(table.definition().objectId()), fields);
    }
}
