package com.richuang.os.nocode.application.service.application;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.mybatis.core.metadata.*;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.*;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.*;

/** 发布/恢复前核对真实目录，防止只验证历史 JSON 却发布到已漂移的数据表。 */
@Service
public class ApplicationObjectContract {
    @Resource private DatabaseMetadataReader database;

    public void validate(Definition definition) {
        validate(
                definition.tableName(),
                ObjectTables.main(definition),
                definition.fields(),
                definition.fieldOptions());
        for (var detail : definition.details())
            if (!MemberStateEnum.INACTIVE.matches(detail.state()))
                validate(
                        detail.tableName(),
                        ObjectTables.detail(definition, detail),
                        detail.fields(),
                        detail.fieldOptions());
        for (var relation : definition.relations())
            if (RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())) {
                var table =
                        database.readTable(
                                        definition.schemaName(),
                                        com.richuang.os.nocode.metadata.service.table
                                                .DataTableService.relationTable(
                                                definition.objectId(), relation.id()))
                                .orElseThrow(() -> invalid("应用关联表不存在：" + relation.name()));
                if (!BaseDOColumns.differences(table).isEmpty()
                        || table.columns().stream().noneMatch(c -> "source_id".equals(c.name()))
                        || table.columns().stream().noneMatch(c -> "target_id".equals(c.name()))
                        || table.indexes().stream()
                                .noneMatch(
                                        i ->
                                                ("nocode_i_r_" + relation.id()).equals(i.name())
                                                        && Boolean.TRUE.equals(i.unique())
                                                        && Boolean.TRUE.equals(i.valid())))
                    throw invalid("应用关联表结构或唯一约束已变化：" + relation.name());
            }
    }

    private void validate(
            String name,
            TableBinding binding,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> options) {
        var table =
                database.readTable(binding.schemaName(), name)
                        .orElseThrow(() -> invalid("应用绑定表已不存在：" + name));
        var keys = table.columns().stream().filter(c -> c.primaryKeyPosition() > 0).toList();
        if (keys.size() != 1 || !keys.getFirst().name().equals(binding.keyColumn()))
            throw invalid("应用绑定表主键已变化：" + name);
        if (!Boolean.TRUE.equals(binding.readOnly()) && !BaseDOColumns.differences(table).isEmpty())
            throw invalid("应用可写表缺少底座公共字段规范：" + name);
        Set<String> mapped = new HashSet<>();
        for (var field : fields) {
            var option = options.getOrDefault(field.id(), FieldOptions.defaults());
            if (MemberStateEnum.INACTIVE.matches(option.state())
                    || FieldTypeEnum.SUMMARY.matches(field.type())) continue;
            String column = option.columnName() == null ? field.code() : option.columnName();
            var physical =
                    table.columns().stream()
                            .filter(c -> c.name().equals(column))
                            .findFirst()
                            .orElseThrow(() -> invalid("应用字段对应列已不存在：" + field.name()));
            if (!FieldStorage.accepts(FieldStorage.sqlType(field, option), physical.nativeType()))
                throw invalid("应用字段物理类型不兼容：" + field.name());
            if (!Boolean.TRUE.equals(field.required())
                    && !Boolean.TRUE.equals(physical.nullable())
                    && physical.primaryKeyPosition() == 0
                    && (physical.generatedKind() == null || physical.generatedKind().isBlank())
                    && (physical.identityKind() == null || physical.identityKind().isBlank()))
                throw invalid("应用字段的非空约束已变化：" + field.name());
            mapped.add(column);
        }
        if (binding.parentColumn() != null) {
            if (table.columns().stream().noneMatch(c -> c.name().equals(binding.parentColumn())))
                throw invalid("内部明细的归属列已不存在：" + name);
            mapped.add(binding.parentColumn());
        }
        if (!Boolean.TRUE.equals(binding.readOnly()))
            for (var column : table.columns())
                if (!mapped.contains(column.name())
                        && !BaseDOColumns.NAMES.contains(column.name())
                        && !Boolean.TRUE.equals(column.nullable())
                        && column.defaultExpression() == null
                        && (column.generatedKind() == null || column.generatedKind().isBlank())
                        && (column.identityKind() == null || column.identityKind().isBlank()))
                    throw invalid("数据表有旧应用无法填写的必填列：" + name + "." + column.name());
    }
}
