package com.lingan.ucp.nocode.schema.service.compile;

import static com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands.*;

import com.lingan.ucp.framework.mybatis.core.metadata.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.schema.service.compile.SchemaTableDefinitions.FieldTable;
import com.lingan.ucp.nocode.schema.service.compile.SchemaTableDefinitions.TableDesign;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 索引按稳定身份和父记录范围演进；停用规则及命令顺序保持不变。 */
@Component
public class SchemaIndexChanges {
    @Resource private SchemaTableDefinitions tableDefinitions;
    @Resource private DatabaseMetadataReader database;
    @Resource private PostgreSqlCommandMapper mapper;

    TableDesign indexTable(Definition definition, Index index) {
        return tableDefinitions.tables(definition, true).stream()
                .filter(t -> t.fields().stream().anyMatch(f -> index.fieldIds().contains(f.id())))
                .findFirst()
                .orElseThrow();
    }

    void indexes(
            Definition d,
            Definition old,
            Map<String, FieldTable> fields,
            List<Command> commands,
            List<Step> changes,
            List<Check> checks,
            Set<String> conversionFields) {
        var previous = new HashMap<String, Index>();
        if (old != null) old.indexes().forEach(i -> previous.put(i.id(), i));
        for (var i : d.indexes()) {
            var before = previous.remove(i.id());
            if (before != null
                    && before.fieldIds().equals(i.fieldIds())
                    && Objects.equals(before.unique(), i.unique())
                    && Boolean.TRUE.equals(before.parentScoped())
                            == Boolean.TRUE.equals(i.parentScoped())) continue;
            var selected = i.fieldIds().stream().map(fields::get).toList();
            if (selected.stream().anyMatch(Objects::isNull)
                    || selected.stream().map(f -> f.design().ref()).distinct().count() != 1
                    || selected.stream()
                            .anyMatch(f -> FieldTypeEnum.SUMMARY.matches(f.field().type()))) {
                checks.add(
                        new Check(
                                PublishCheckEnum.INDEX_FIELDS.getCode(),
                                "索引必须选择同一张表的存储字段：" + i.name(),
                                true));
                continue;
            }
            var table = selected.getFirst().design();
            if (!table.binding().managed()) {
                checks.add(
                        new Check(
                                PublishCheckEnum.STRUCTURE_RETAINED.getCode(),
                                "保留结构的表不能创建或修改索引：" + i.name(),
                                true));
                continue;
            }
            var columns =
                    new ArrayList<>(
                            selected.stream()
                                    .map(
                                            f ->
                                                    SchemaTableDefinitions.columnName(
                                                            f.field(), f.options()))
                                    .toList());
            if (Boolean.TRUE.equals(i.parentScoped())) {
                if (table.detailId() == null) {
                    checks.add(
                            new Check(
                                    PublishCheckEnum.INDEX_FIELDS.getCode(),
                                    "父记录范围仅适用于内部明细：" + i.name(),
                                    true));
                    continue;
                }
                columns.remove(table.binding().parentColumn());
                columns.addFirst(table.binding().parentColumn());
            }
            var physical = database.readTable(table.schema(), table.name()).orElse(null);
            if (i.fieldIds().stream().noneMatch(conversionFields::contains)
                    && Boolean.TRUE.equals(i.unique())
                    && physical != null
                    && physical.columns().stream()
                            .map(DatabaseMetadata.Column::name)
                            .toList()
                            .containsAll(columns)
                    && Boolean.TRUE.equals(
                            mapper.check(duplicates(table.schema(), table.name(), columns))))
                checks.add(
                        new Check(
                                PublishCheckEnum.DUPLICATES.getCode(),
                                "组合唯一索引已有重复值：" + i.name(),
                                true));
            if (before != null)
                commands.add(dropIndex(indexTable(old, before).schema(), "nocode_i_" + i.id()));
            commands.add(
                    index(
                            table.schema(),
                            table.name(),
                            "nocode_i_" + i.id(),
                            columns,
                            Boolean.TRUE.equals(i.unique())));
            changes.add(
                    new Step(
                            SchemaChangeEnum.INDEX.getCode(),
                            "建立索引："
                                    + i.name()
                                    + (Boolean.TRUE.equals(i.parentScoped()) ? "（同一主记录范围）" : "")));
        }
        for (var i : previous.values()) {
            var table = indexTable(old, i);
            var now =
                    tableDefinitions.tables(d, true).stream()
                            .filter(t -> t.ref().equals(table.ref()))
                            .findFirst()
                            .orElse(table);
            if (!now.binding().managed()) {
                checks.add(
                        new Check(
                                PublishCheckEnum.STRUCTURE_RETAINED.getCode(),
                                "保留结构的表不能删除索引：" + i.name(),
                                true));
                continue;
            }
            commands.add(dropIndex(table.schema(), "nocode_i_" + i.id()));
            changes.add(new Step(SchemaChangeEnum.DROP_INDEX.getCode(), "移除索引：" + i.name()));
        }
    }
}
