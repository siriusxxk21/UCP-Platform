package com.lingan.ucp.nocode.schema.service.compile;

import static com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands.*;

import com.lingan.ucp.framework.mybatis.core.metadata.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDesignService;
import com.lingan.ucp.nocode.metadata.service.table.DataTableService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 对象关系、外键和关联表的结构演进，保留删除策略与存量物理名称。 */
@Component
public class SchemaRelationChanges {
    @Resource private SchemaTableDefinitions tableDefinitions;
    @Resource private DatabaseMetadataReader database;
    @Resource private PostgreSqlCommandMapper mapper;
    @Resource private ObjectDesignService designs;

    void relations(
            Definition d,
            Definition old,
            List<Command> commands,
            List<Step> changes,
            List<Check> checks,
            Set<String> conversionFields) {
        var previous = new HashMap<String, Relation>();
        if (old != null) old.relations().forEach(r -> previous.put(r.id(), r));
        for (var r : d.relations()) {
            var before = previous.remove(r.id());
            var target = designs.published(r.targetObjectId());
            if (target == null) {
                checks.add(
                        new Check(
                                PublishCheckEnum.TARGET_UNPUBLISHED.getCode(),
                                "关系目标未发布：" + r.name(),
                                true));
                continue;
            }
            var physical =
                    database.readTable(target.schemaName(), target.tableName()).orElseThrow();
            var keys = physical.columns().stream().filter(c -> c.primaryKeyPosition() > 0).toList();
            if (keys.size() != 1) {
                checks.add(
                        new Check(
                                PublishCheckEnum.TARGET_KEY.getCode(),
                                "关系目标主键已变化：" + r.name(),
                                true));
                continue;
            }
            var key = keys.getFirst();
            if (RelationTypeEnum.MANY_TO_MANY.matches(r.kind())) {
                if (before == null) {
                    String table =
                            DataTableService.relationTable(
                                    database, d.schemaName(), d.objectId(), r.id());
                    if (database.relationExists(d.schemaName(), table)) {
                        checks.add(
                                new Check(
                                        PublishCheckEnum.RELATION_TABLE.getCode(),
                                        "关联表名已被占用",
                                        true));
                        continue;
                    }
                    commands.add(
                            createBusinessTable(
                                    d.schemaName(),
                                    table,
                                    List.of(
                                            new Column(
                                                    "source_id",
                                                    tableDefinitions.mainKeyType(d),
                                                    true,
                                                    null,
                                                    false,
                                                    false),
                                            new Column(
                                                    "target_id",
                                                    key.nativeType(),
                                                    true,
                                                    null,
                                                    false,
                                                    false))));
                    commands.add(
                            foreignKey(
                                    d.schemaName(),
                                    table,
                                    "nocode_fk_rs_" + r.id(),
                                    "source_id",
                                    d.schemaName(),
                                    d.tableName(),
                                    ObjectTables.main(d).keyColumn(),
                                    DeletePolicyEnum.CASCADE.getCode()));
                    commands.add(
                            foreignKey(
                                    d.schemaName(),
                                    table,
                                    "nocode_fk_rt_" + r.id(),
                                    "target_id",
                                    target.schemaName(),
                                    target.tableName(),
                                    key.name(),
                                    DeletePolicyEnum.RESTRICT.getCode()));
                    commands.add(
                            index(
                                    d.schemaName(),
                                    table,
                                    "nocode_i_r_" + r.id(),
                                    List.of("source_id", "target_id"),
                                    true));
                    for (var base : BaseDOColumns.FIELDS)
                        commands.add(comment(d.schemaName(), table, base.name(), base.comment()));
                    changes.add(
                            new Step(SchemaChangeEnum.MANY_TO_MANY.getCode(), "建立关联表：" + r.name()));
                }
            } else {
                var origin = RelationSources.source(d, r);
                // 保留结构与只读升级后仍复核数据；物理约束只在缺失或规则变更时创建。
                boolean constraintExists =
                        database.readTable(origin.binding().schemaName(), origin.tableName())
                                .map(
                                        t ->
                                                t.constraints().stream()
                                                        .anyMatch(
                                                                c ->
                                                                        c.name()
                                                                                .equals(
                                                                                        "nocode_fk_r_"
                                                                                                + r
                                                                                                        .id())))
                                .orElse(false);
                boolean constraintChanged =
                        before != null
                                && (!Objects.equals(before.onDelete(), r.onDelete())
                                        || !Objects.equals(before.required(), r.required())
                                        || !Objects.equals(
                                                before.targetObjectId(), r.targetObjectId()));
                String fk = "nocode_fk_r_" + r.id();
                if (constraintChanged
                        && !conversionFields.contains(r.fieldId())
                        && origin.binding().managed()
                        && !Boolean.TRUE.equals(origin.binding().readOnly())
                        && database.readTable(origin.binding().schemaName(), origin.tableName())
                                .map(
                                        t ->
                                                t.constraints().stream()
                                                        .anyMatch(c -> c.name().equals(fk)))
                                .orElse(false))
                    commands.add(
                            dropConstraint(origin.binding().schemaName(), origin.tableName(), fk));
                var f =
                        origin.fields().stream()
                                .filter(v -> v.id().equals(r.fieldId()))
                                .findFirst()
                                .orElseThrow();
                var option = tableDefinitions.options(origin.options(), f);
                String column = SchemaTableDefinitions.columnName(f, option);
                var source =
                        database.readTable(origin.binding().schemaName(), origin.tableName())
                                .orElse(null);
                if (!Boolean.TRUE.equals(r.required()) && Boolean.TRUE.equals(f.required())) {
                    checks.add(
                            new Check(
                                    PublishCheckEnum.BINDING_INVALID.getCode(),
                                    "引用列本身为必填，不能发布为可选引用：" + r.name(),
                                    true));
                }
                if (!conversionFields.contains(f.id())
                        && source != null
                        && source.columns().stream().anyMatch(c -> c.name().equals(column))) {
                    if (Boolean.TRUE.equals(
                            mapper.check(
                                    unlinkedRows(
                                            origin.binding().schemaName(),
                                            origin.tableName(),
                                            column,
                                            target.schemaName(),
                                            target.tableName(),
                                            key.name(),
                                            Boolean.TRUE.equals(r.required())))))
                        checks.add(
                                new Check(
                                        PublishCheckEnum.UNLINKED_ROWS.getCode(),
                                        "引用列存在未匹配记录或必填空值：" + r.name(),
                                        !Boolean.TRUE.equals(origin.binding().readOnly())));
                    if (RelationTypeEnum.ONE_TO_ONE.matches(r.kind())
                            && Boolean.TRUE.equals(
                                    mapper.check(
                                            duplicates(
                                                    origin.binding().schemaName(),
                                                    origin.tableName(),
                                                    List.of(column)))))
                        checks.add(
                                new Check(
                                        PublishCheckEnum.DUPLICATES.getCode(),
                                        "一对一引用存在重复目标：" + r.name(),
                                        true));
                }
                if (!origin.binding().managed()
                        || Boolean.TRUE.equals(origin.binding().readOnly())) {
                    changes.add(
                            new Step(
                                    SchemaChangeEnum.RELATION.getCode(),
                                    "保存引用映射并保留原约束：" + r.name()));
                    continue;
                }
                // 复用的普通列保留原字段必填属性；只有关系追加的非空约束才随关系放宽。
                // 新主表在计划执行时才存在，也必须把复用列的关系必填要求加入 DDL。
                if (Boolean.TRUE.equals(r.required())) {
                    commands.add(
                            required(
                                    origin.binding().schemaName(),
                                    origin.tableName(),
                                    column,
                                    true));
                } else if (before != null
                        && Boolean.TRUE.equals(before.required())
                        && !Boolean.TRUE.equals(f.required())) {
                    commands.add(
                            required(
                                    origin.binding().schemaName(),
                                    origin.tableName(),
                                    column,
                                    false));
                    changes.add(
                            new Step(
                                    SchemaChangeEnum.RELATION.getCode(),
                                    "解除关系添加的引用必填约束：" + r.name()));
                }
                if (constraintExists && !constraintChanged) continue;
                if (source != null
                        && source.columns().stream().anyMatch(c -> c.name().equals(column))) {
                    if (RelationTypeEnum.ONE_TO_ONE.matches(r.kind())
                            && !Boolean.TRUE.equals(f.unique())
                            && source.indexes().stream()
                                    .noneMatch(i -> i.name().equals("nocode_ur_" + r.id())))
                        commands.add(
                                index(
                                        origin.binding().schemaName(),
                                        origin.tableName(),
                                        "nocode_ur_" + r.id(),
                                        List.of(column),
                                        true));
                }
                commands.add(
                        foreignKey(
                                origin.binding().schemaName(),
                                origin.tableName(),
                                fk,
                                SchemaTableDefinitions.columnName(
                                        f, tableDefinitions.options(origin.options(), f)),
                                target.schemaName(),
                                target.tableName(),
                                key.name(),
                                r.onDelete()));
                changes.add(new Step(SchemaChangeEnum.RELATION.getCode(), "建立关系约束：" + r.name()));
            }
        }
        for (var r : previous.values()) {
            var origin = RelationSources.source(old == null ? d : old, r);
            if (!RelationTypeEnum.MANY_TO_MANY.matches(r.kind())
                    && !conversionFields.contains(r.fieldId())
                    && origin.binding().managed()
                    && database.readTable(origin.binding().schemaName(), origin.tableName())
                            .map(
                                    t ->
                                            t.constraints().stream()
                                                    .anyMatch(
                                                            c ->
                                                                    c.name()
                                                                            .equals(
                                                                                    "nocode_fk_r_"
                                                                                            + r
                                                                                                    .id())))
                            .orElse(false))
                commands.add(
                        dropConstraint(
                                origin.binding().schemaName(),
                                origin.tableName(),
                                "nocode_fk_r_" + r.id()));
            changes.add(
                    new Step(
                            SchemaChangeEnum.DEACTIVATE_RELATION.getCode(),
                            (r.fieldId() != null && conversionFields.contains(r.fieldId())
                                            ? "解除对象关系并按字段转换计划处理本列："
                                            : "停用关系并保留原关联数据：")
                                    + r.name()));
        }
    }
}
