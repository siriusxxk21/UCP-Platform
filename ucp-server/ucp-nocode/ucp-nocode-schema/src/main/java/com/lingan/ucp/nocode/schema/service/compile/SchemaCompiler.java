package com.lingan.ucp.nocode.schema.service.compile;

import static com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands.*;

import com.lingan.ucp.framework.mybatis.core.metadata.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDesignService;
import com.lingan.ucp.nocode.schema.service.compile.SchemaTableDefinitions.FieldTable;
import com.lingan.ucp.nocode.schema.service.compile.SchemaTableDefinitions.TableDesign;
import com.lingan.ucp.nocode.schema.service.selection.SelectionMigrationService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** 将对象定义编译为受控 PostgreSQL 命令。比较稳定字段身份，停用不删列； 每次执行均重新编译和检查实际数据，不信任客户端或旧计划中的 SQL。 */
@Component
public class SchemaCompiler {
    @Resource private SchemaColumnChanges columnChanges;
    @Resource private SchemaIndexChanges indexChanges;
    @Resource private SchemaRelationChanges relationChanges;
    @Resource private SchemaTableDefinitions tableDefinitions;
    @Resource private SelectionMigrationService selectionMigrations;
    @Resource private SchemaFieldConstraintChecks fieldConstraintChecks;
    @Resource private DatabaseMetadataReader database;
    @Resource private PostgreSqlCommandMapper mapper;
    @Resource private ObjectDesignService designs;

    public record Compilation(List<Command> commands, List<Step> changes, List<Check> checks) {}

    /** 该过程只读目录和数据；DDL 由发布事务逐条执行。 */
    public Compilation compile(Definition current, Definition previous) {
        return compile(current, previous, Set.of());
    }

    /** 已由发布计划预检的列按清空后的目标结构编译，执行前必须再次核对并应用转换。 */
    public Compilation compile(
            Definition current, Definition previous, Set<String> conversionFields) {
        var commands = new ArrayList<Command>();
        var changes = new ArrayList<Step>();
        var checks = new ArrayList<Check>();
        for (SelectionMigrationService.Change change :
                selectionMigrations.preview(current, previous, conversionFields)) {
            changes.add(
                    new Step(
                            SchemaChangeEnum.VALIDATION.getCode(),
                            "转换选择来源："
                                    + change.fieldName()
                                    + "，涉及 "
                                    + change.existingValues().size()
                                    + " 种旧值"));
            if (change.error() != null)
                checks.add(
                        new Check(
                                PublishCheckEnum.STORAGE_CHANGE.getCode(),
                                change.fieldName() + "：" + change.error(),
                                true));
        }
        // 编译器保留基础引用提示；发布服务另行复核运行应用并执行已确认的暂停方案。
        // 物理数据安全由本编译流程独立检查，应用适配仍由应用中心负责。
        if (previous != null) {
            var apps =
                    designs.get(current.objectId()).dependencies().stream()
                            .filter(d -> DependencyKindEnum.APP.matches(d.sourceKind()))
                            .map(DataCenter.Dependency::sourceName)
                            .filter(name -> name != null && !name.isBlank())
                            .distinct()
                            .sorted()
                            .toList();
            if (!apps.isEmpty()) {
                String names =
                        apps.size() <= 3
                                ? String.join("、", apps)
                                : String.join("、", apps.subList(0, 3))
                                        + " 等 "
                                        + apps.size()
                                        + " 个应用";
                for (String change : ObjectContracts.breakingChanges(previous, current))
                    checks.add(
                            new Check(
                                    PublishCheckEnum.APPLICATION_CONTRACT.getCode(),
                                    "对象已被应用使用（" + names + "）：" + change + "；发布后请在相关应用中同步对象版本并调整配置",
                                    false));
            }
        }
        var oldTables =
                previous == null
                        ? Map.<ObjectTables.Ref, TableDesign>of()
                        : tableDefinitions.tables(previous, true).stream()
                                .collect(Collectors.toMap(TableDesign::ref, t -> t));
        var allFields = new HashMap<String, FieldTable>();
        for (var table : tableDefinitions.tables(current)) {
            for (var f : table.fields())
                allFields.put(f.id(), new FieldTable(table, f, tableDefinitions.options(table, f)));
            var actual = database.readTable(table.schema(), table.name()).orElse(null);
            var old = oldTables.get(table.ref());
            if (table.binding().adopted()) {
                columnChanges.adopted(current, table, old, actual, commands, changes, checks);
                continue;
            }
            if (actual != null && old == null) {
                checks.add(
                        new Check(
                                PublishCheckEnum.TABLE_OCCUPIED.getCode(),
                                "物理表名已存在：" + table.name(),
                                true));
                continue;
            }
            if (actual == null && old != null) {
                checks.add(
                        new Check(
                                PublishCheckEnum.TABLE_MISSING.getCode(),
                                "已发布表丢失：" + table.name(),
                                true));
                continue;
            }
            try {
                if (actual == null) {
                    var columns = new ArrayList<Column>();
                    if (table.detailId() != null)
                        columns.add(
                                new Column(
                                        table.binding().parentColumn(),
                                        tableDefinitions.mainKeyType(current),
                                        true,
                                        null,
                                        false,
                                        false));
                    for (var f : table.fields())
                        if (!FieldTypeEnum.SUMMARY.matches(f.type()))
                            columns.add(tableDefinitions.column(table, f));
                    commands.add(createBusinessTable(table.schema(), table.name(), columns));
                    for (var base : BaseDOColumns.FIELDS)
                        commands.add(
                                comment(table.schema(), table.name(), base.name(), base.comment()));
                    changes.add(
                            new Step(
                                    SchemaChangeEnum.CREATE_TABLE.getCode(),
                                    "创建"
                                            + (table.detailId() == null ? "主表" : "内部明细表")
                                            + " "
                                            + table.name()
                                            + "（包含底座公共字段）"));
                } else {
                    var missing = BaseDOColumns.differences(actual);
                    if (!missing.isEmpty())
                        checks.add(
                                new Check(
                                        PublishCheckEnum.BASE_FIELDS.getCode(),
                                        "生成表的底座公共字段不符合规范：" + table.name() + " " + missing,
                                        true));
                    columnChanges.evolve(
                            current,
                            table,
                            old,
                            actual,
                            commands,
                            changes,
                            checks,
                            conversionFields);
                }
                if (table.detailId() != null)
                    columnChanges.parent(current, table, old, actual, commands, changes, checks);
                commands.add(comment(table.schema(), table.name(), null, table.title()));
                for (var f : table.fields()) {
                    var o = tableDefinitions.options(table, f);
                    if (FieldTypeEnum.SUMMARY.matches(f.type())) {
                        changes.add(
                                new Step(
                                        SchemaChangeEnum.SUMMARY.getCode(),
                                        "保存汇总定义：" + f.name() + "；按内部明细关系在业务查询时计算"));
                        continue;
                    }
                    commands.add(
                            comment(
                                    table.schema(),
                                    table.name(),
                                    SchemaTableDefinitions.columnName(f, o),
                                    o.description() == null ? f.name() : o.description()));
                    FieldDefinition before =
                            old == null
                                    ? null
                                    : old.fields().stream()
                                            .filter(v -> v.id().equals(f.id()))
                                            .findFirst()
                                            .orElse(null);
                    FieldOptions beforeOptions =
                            before == null
                                    ? FieldOptions.defaults()
                                    : tableDefinitions.options(old, before);
                    var oldUnique = before != null && Boolean.TRUE.equals(before.unique());
                    var newUnique = Boolean.TRUE.equals(f.unique());
                    if (oldUnique && !newUnique)
                        commands.add(dropIndex(table.schema(), "nocode_u_" + f.id()));
                    if (newUnique && !oldUnique) {
                        if (!conversionFields.contains(f.id())
                                && actual != null
                                && actual.columns().stream()
                                        .anyMatch(
                                                c ->
                                                        c.name()
                                                                .equals(
                                                                        SchemaTableDefinitions
                                                                                .columnName(f, o)))
                                && Boolean.TRUE.equals(
                                        mapper.check(
                                                duplicates(
                                                        table.schema(),
                                                        table.name(),
                                                        List.of(
                                                                SchemaTableDefinitions.columnName(
                                                                        f, o))))))
                            checks.add(
                                    new Check(
                                            PublishCheckEnum.DUPLICATES.getCode(),
                                            "唯一字段已有重复值：" + f.name(),
                                            true));
                        commands.add(
                                index(
                                        table.schema(),
                                        table.name(),
                                        "nocode_u_" + f.id(),
                                        List.of(SchemaTableDefinitions.columnName(f, o)),
                                        true));
                        changes.add(
                                new Step(SchemaChangeEnum.UNIQUE.getCode(), "建立唯一约束：" + f.name()));
                    }
                    columnChanges.checks(
                            table.schema(),
                            table,
                            f,
                            o,
                            conversionFields.contains(f.id())
                                    ? FieldOptions.defaults()
                                    : beforeOptions,
                            before != null,
                            commands,
                            changes);
                }
            } catch (IllegalArgumentException ex) {
                checks.add(
                        new Check(
                                PublishCheckEnum.INVALID_FIELD.getCode(),
                                table.title() + "：" + ex.getMessage(),
                                true));
            }
        }
        indexChanges.indexes(
                current, previous, allFields, commands, changes, checks, conversionFields);
        relationChanges.relations(current, previous, commands, changes, checks, conversionFields);
        checks.addAll(fieldConstraintChecks.inspect(current, previous, conversionFields));
        if (changes.isEmpty())
            changes.add(
                    new Step(SchemaChangeEnum.METADATA.getCode(), "更新名称、标题、显示规则或其他管理配置，保持物理结构"));
        return new Compilation(List.copyOf(commands), List.copyOf(changes), List.copyOf(checks));
    }

    public String physicalSignature(Definition d) {
        // 兼容旧快照缺少表级绑定和 parentScoped；显示名、分类及选表指纹不属于结构修改。
        var signature = new ArrayList<Object>();
        for (var table : tableDefinitions.tables(d, true)) {
            var b = table.binding();
            signature.add(
                    Arrays.asList(
                            table.ref(),
                            b.source(),
                            b.keyColumn(),
                            b.parentColumn(),
                            b.structureMode(),
                            Boolean.TRUE.equals(b.readOnly()),
                            Boolean.TRUE.equals(b.repairBaseFields())));
            for (var f : table.fields()) {
                var o = tableDefinitions.options(table, f);
                signature.add(
                        Arrays.asList(
                                f.id(),
                                SchemaTableDefinitions.columnName(f, o),
                                f.type(),
                                f.length(),
                                f.precision(),
                                f.scale(),
                                f.required(),
                                f.unique(),
                                o.defaultValue(),
                                o.expression(),
                                o.nativeType()));
            }
        }
        signature.add(d.relations());
        d.indexes()
                .forEach(
                        i ->
                                signature.add(
                                        Arrays.asList(
                                                i.id(),
                                                i.code(),
                                                i.fieldIds(),
                                                i.unique(),
                                                Boolean.TRUE.equals(i.parentScoped()))));
        d.details().forEach(t -> signature.add(Arrays.asList(t.id(), t.state())));
        return designs.write(signature);
    }

    public static FieldOptions options(Map<String, FieldOptions> values, FieldDefinition field) {
        return SchemaTableDefinitions.options(values, field);
    }

    public static String columnName(FieldDefinition f, FieldOptions o) {
        return SchemaTableDefinitions.columnName(f, o);
    }

    public static String sqlType(FieldDefinition f, FieldOptions o) {
        return SchemaTableDefinitions.sqlType(f, o);
    }

    /** 编译和结构核对共用同一类型放宽规则，避免对现有物理列得出不同结论。 */
    public static boolean widening(String from, String to) {
        return SchemaColumnChanges.widening(from, to);
    }
}
