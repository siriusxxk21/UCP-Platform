package com.richuang.os.nocode.schema.service.compile;

import static com.richuang.os.framework.mybatis.core.metadata.PostgreSqlCommands.*;

import com.richuang.os.framework.mybatis.core.metadata.*;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.dal.mapper.DataCenterMapper;
import com.richuang.os.nocode.metadata.service.object.ObjectDesignCodec;
import com.richuang.os.nocode.metadata.service.table.TableBindingService;
import com.richuang.os.nocode.schema.service.compile.SchemaTableDefinitions.TableDesign;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/** 新增、纳管及演进列的结构规则，按原顺序生成命令和检查项。 */
@Component
public class SchemaColumnChanges {
    private static final Set<FieldTypeEnum> EMPTY_TABLE_REPLACEABLE_TYPES =
            EnumSet.of(
                    FieldTypeEnum.TEXT,
                    FieldTypeEnum.TEXTAREA,
                    FieldTypeEnum.RICH_TEXT,
                    FieldTypeEnum.INTEGER,
                    FieldTypeEnum.DECIMAL,
                    FieldTypeEnum.MONEY,
                    FieldTypeEnum.PERCENT,
                    FieldTypeEnum.BOOLEAN,
                    FieldTypeEnum.DATE,
                    FieldTypeEnum.DATETIME,
                    FieldTypeEnum.TIME,
                    FieldTypeEnum.UUID);

    @Resource private SchemaTableDefinitions tableDefinitions;
    @Resource private DatabaseMetadataReader database;
    @Resource private PostgreSqlCommandMapper mapper;
    @Resource private TableBindingService bindings;
    @Resource private DataCenterMapper store;
    @Resource private ObjectDesignCodec designCodec;

    /** 纳管保持原业务列；结构授权只覆盖明示的公共列修复、父键和新增关系约束。 */
    void adopted(
            Definition d,
            TableDesign table,
            TableDesign old,
            DatabaseMetadata.Table actual,
            List<Command> commands,
            List<Step> changes,
            List<Check> checks) {
        var b = table.binding();
        if (actual == null) {
            checks.add(
                    new Check(
                            PublishCheckEnum.TABLE_MISSING.getCode(),
                            "纳管表不存在：" + table.name(),
                            true));
            return;
        }
        if (old == null
                && b.fingerprint() != null
                && !b.fingerprint().equals(bindings.fingerprint(actual)))
            checks.add(
                    new Check(
                            PublishCheckEnum.BINDING_CHANGED.getCode(),
                            "纳管表结构自选取后发生变化，请重新核验：" + table.name(),
                            true));
        try {
            if (!TableBindingService.key(actual).name().equals(b.keyColumn()))
                throw new IllegalArgumentException("真实主键与绑定不一致");
            var missing = BaseDOColumns.differences(actual);
            if (Boolean.TRUE.equals(b.repairBaseFields()) && b.managed()) {
                for (String name : missing) {
                    if (actual.columns().stream().anyMatch(c -> c.name().equals(name)))
                        throw new IllegalArgumentException("已有公共列不符合规范，不能覆盖：" + name);
                    commands.add(addBaseDOColumn(table.schema(), table.name(), name));
                    commands.add(
                            comment(
                                    table.schema(),
                                    table.name(),
                                    name,
                                    BaseDOColumns.FIELDS.stream()
                                            .filter(f -> f.name().equals(name))
                                            .findFirst()
                                            .orElseThrow()
                                            .comment()));
                }
                if (!missing.isEmpty())
                    changes.add(
                            new Step(
                                    SchemaChangeEnum.REPAIR_BASE_FIELDS.getCode(),
                                    "补齐 "
                                            + table.name()
                                            + " 公共列 "
                                            + missing
                                            + "；历史操作者留空，缺失时间记为本次补齐时间"));
            } else if (!missing.isEmpty() && !Boolean.TRUE.equals(b.readOnly()))
                checks.add(
                        new Check(
                                PublishCheckEnum.BASE_FIELDS.getCode(),
                                "纳管表未满足底座公共字段规范，只能只读或显式补齐：" + table.name(),
                                true));
            for (var field : table.fields()) {
                var option = tableDefinitions.options(table, field);
                if (FieldTypeEnum.SUMMARY.matches(field.type())) continue;
                var column =
                        actual.columns().stream()
                                .filter(
                                        c ->
                                                c.name()
                                                        .equals(
                                                                SchemaTableDefinitions.columnName(
                                                                        field, option)))
                                .findFirst()
                                .orElse(null);
                if (column == null) {
                    boolean relationGenerated =
                            d.relations().stream()
                                    .anyMatch(r -> Objects.equals(r.fieldId(), field.id()));
                    if (!b.managed() || !relationGenerated)
                        throw new IllegalArgumentException(
                                "映射列不存在：" + SchemaTableDefinitions.columnName(field, option));
                    if (Boolean.TRUE.equals(field.required())
                            && Boolean.TRUE.equals(
                                    mapper.check(hasRows(table.schema(), table.name()))))
                        checks.add(
                                new Check(
                                        PublishCheckEnum.REQUIRED_EXISTING.getCode(),
                                        "已有数据不能直接新增必填引用列：" + field.name(),
                                        true));
                    commands.add(
                            addColumn(
                                    table.schema(),
                                    table.name(),
                                    tableDefinitions.column(table, field)));
                    changes.add(
                            new Step(
                                    SchemaChangeEnum.ADD_COLUMN.getCode(),
                                    "新增引用列：" + field.name()));
                } else if (!SchemaTableDefinitions.canonicalType(column.nativeType())
                        .equals(
                                SchemaTableDefinitions.canonicalType(
                                        option.nativeType() == null
                                                ? SchemaTableDefinitions.sqlType(field, option)
                                                : option.nativeType())))
                    throw new IllegalArgumentException("映射字段与实际列类型不同：" + field.name());
            }
            if (table.detailId() != null) parent(d, table, old, actual, commands, changes, checks);
            changes.add(
                    new Step(
                            SchemaChangeEnum.BIND_TABLE.getCode(),
                            "绑定已有表 "
                                    + table.schema()
                                    + "."
                                    + table.name()
                                    + (b.managed() ? "，按计划管理结构" : "，保留原结构")
                                    + (Boolean.TRUE.equals(b.readOnly()) ? "（只读）" : "")));
        } catch (IllegalArgumentException e) {
            checks.add(
                    new Check(
                            PublishCheckEnum.BINDING_INVALID.getCode(),
                            table.name() + "：" + e.getMessage(),
                            true));
        }
    }

    /** 内部主从始终有明确的父键；只读可保留未归属历史行，但不会把它们纳入父对象明细。 */
    void parent(
            Definition d,
            TableDesign table,
            TableDesign old,
            DatabaseMetadata.Table actual,
            List<Command> commands,
            List<Step> changes,
            List<Check> checks) {
        var b = table.binding();
        var main = ObjectTables.main(d);
        String nativeType = tableDefinitions.mainKeyType(d);
        var parent =
                actual == null
                        ? null
                        : actual.columns().stream()
                                .filter(c -> c.name().equals(b.parentColumn()))
                                .findFirst()
                                .orElse(null);
        boolean rows =
                actual != null
                        && Boolean.TRUE.equals(mapper.check(hasRows(table.schema(), table.name())));
        boolean parentExists = database.relationExists(main.schemaName(), d.tableName());
        if (parent != null
                && !SchemaTableDefinitions.canonicalType(parent.nativeType())
                        .equals(SchemaTableDefinitions.canonicalType(nativeType)))
            throw new IllegalArgumentException("父键列类型必须与主表真实主键一致：" + b.parentColumn());
        boolean unlinked =
                rows
                        && (parent == null
                                || !parentExists
                                || Boolean.TRUE.equals(
                                        mapper.check(
                                                unlinkedRows(
                                                        table.schema(),
                                                        table.name(),
                                                        b.parentColumn(),
                                                        main.schemaName(),
                                                        d.tableName(),
                                                        main.keyColumn(),
                                                        true))));
        if (unlinked)
            checks.add(
                    new Check(
                            PublishCheckEnum.UNLINKED_ROWS.getCode(),
                            "明细 " + table.name() + " 存在空父键或找不到主记录的历史行；需先修复归属。只读绑定会排除这些行",
                            !Boolean.TRUE.equals(b.readOnly())));
        if (actual != null && parent == null) {
            if (!b.managed()) {
                checks.add(
                        new Check(
                                PublishCheckEnum.STRUCTURE_RETAINED.getCode(),
                                "保留结构的明细必须选择已有父键列：" + table.name(),
                                true));
                return;
            }
            commands.add(
                    addColumn(
                            table.schema(),
                            table.name(),
                            new Column(
                                    b.parentColumn(),
                                    nativeType,
                                    !Boolean.TRUE.equals(b.readOnly()),
                                    null,
                                    false,
                                    false)));
            changes.add(
                    new Step(
                            SchemaChangeEnum.LINK_PARENT.getCode(),
                            "新增父键列：" + table.name() + "." + b.parentColumn()));
        }
        if (!b.managed() || Boolean.TRUE.equals(b.readOnly())) return;
        String name = "nocode_fk_d_" + table.detailId();
        boolean constrained =
                actual != null
                        && actual.constraints().stream().anyMatch(c -> c.name().equals(name));
        if (!constrained) {
            if (parent != null && parent.nullable())
                commands.add(required(table.schema(), table.name(), b.parentColumn(), true));
            commands.add(
                    foreignKey(
                            table.schema(),
                            table.name(),
                            name,
                            b.parentColumn(),
                            main.schemaName(),
                            d.tableName(),
                            main.keyColumn(),
                            DeletePolicyEnum.CASCADE.getCode()));
            if (actual == null
                    || actual.indexes().stream()
                            .noneMatch(i -> i.name().equals("nocode_i_d_" + table.detailId())))
                commands.add(
                        index(
                                table.schema(),
                                table.name(),
                                "nocode_i_d_" + table.detailId(),
                                List.of(b.parentColumn()),
                                false));
            changes.add(
                    new Step(
                            SchemaChangeEnum.LINK_PARENT.getCode(),
                            "约束明细归属："
                                    + table.name()
                                    + "."
                                    + b.parentColumn()
                                    + " → "
                                    + d.tableName()
                                    + "."
                                    + main.keyColumn()));
        }
    }

    void evolve(
            Definition d,
            TableDesign table,
            TableDesign old,
            DatabaseMetadata.Table actual,
            List<Command> commands,
            List<Step> changes,
            List<Check> checks,
            Set<String> conversionFields) {
        Map<String, FieldDefinition> previous =
                old.fields().stream().collect(Collectors.toMap(FieldDefinition::id, f -> f));
        Set<String> present =
                actual.columns().stream()
                        .map(DatabaseMetadata.Column::name)
                        .collect(Collectors.toSet());
        for (var f : table.fields()) {
            var o = tableDefinitions.options(table, f);
            var before = previous.remove(f.id());
            if (FieldTypeEnum.SUMMARY.matches(f.type())) {
                if (before != null && !FieldTypeEnum.SUMMARY.matches(before.type()))
                    checks.add(
                            new Check(
                                    PublishCheckEnum.STORAGE_CHANGE.getCode(),
                                    "已存储字段不能改为虚拟汇总：" + f.name(),
                                    true));
                continue;
            }
            if (before == null) {
                if (present.contains(SchemaTableDefinitions.columnName(f, o))) {
                    if (restoreRetained(d, table, f, o, actual, commands, changes, checks))
                        continue;
                    checks.add(
                            new Check(
                                    PublishCheckEnum.COLUMN_OCCUPIED.getCode(),
                                    "列名已存在或为停用字段保留：" + SchemaTableDefinitions.columnName(f, o),
                                    true));
                    continue;
                }
                if (store.lastPublishedFieldSchema(
                                Long.parseLong(d.objectId()), Long.parseLong(f.id()))
                        != null) {
                    checks.add(
                            new Check(
                                    PublishCheckEnum.COLUMN_IDENTITY.getCode(),
                                    "恢复字段的原物理列已丢失，不能重新建列代替历史数据：" + f.name(),
                                    true));
                    continue;
                }
                if (Boolean.TRUE.equals(f.required())
                        && o.defaultValue() == null
                        && !FieldTypeEnum.FORMULA.matches(f.type())
                        && !(FieldTypeEnum.AUTO_NUMBER.matches(f.type()) && o.autoNumber() == null)
                        && Boolean.TRUE.equals(mapper.check(hasRows(table.schema(), table.name()))))
                    checks.add(
                            new Check(
                                    PublishCheckEnum.REQUIRED_EXISTING.getCode(),
                                    "已有数据的表新增必填字段需要默认值：" + f.name(),
                                    true));
                commands.add(
                        addColumn(table.schema(), table.name(), tableDefinitions.column(table, f)));
                changes.add(
                        new Step(
                                SchemaChangeEnum.ADD_COLUMN.getCode(),
                                "新增字段 " + table.title() + "." + f.name()));
                continue;
            }
            var oldOption = tableDefinitions.options(old, before);
            if (!SchemaTableDefinitions.columnName(f, o)
                    .equals(SchemaTableDefinitions.columnName(before, oldOption))) {
                checks.add(
                        new Check(
                                PublishCheckEnum.COLUMN_IDENTITY.getCode(), "已发布字段不可更换物理列", true));
                continue;
            }
            if (FieldTypeEnum.SUMMARY.matches(before.type())
                    || !Objects.equals(o.expression(), oldOption.expression())
                    || !liveCalculationChange(before, oldOption, f, o)
                    || (o.autoNumber() == null) != (oldOption.autoNumber() == null)
                    || FieldTypeEnum.AUTO_NUMBER.matches(f.type())
                            != FieldTypeEnum.AUTO_NUMBER.matches(before.type()))
                checks.add(
                        new Check(
                                PublishCheckEnum.GENERATED_CHANGE.getCode(),
                                "已发布计算或自动编号定义不能原地替换，请新增字段：" + f.name(),
                                true));
            String from = SchemaTableDefinitions.sqlType(before, oldOption),
                    to = SchemaTableDefinitions.sqlType(f, o);
            boolean selectionChanged =
                    !com.richuang.os.nocode.api.SelectionFields.identity(f, o)
                            .equals(
                                    com.richuang.os.nocode.api.SelectionFields.identity(
                                            before, oldOption));
            if (!from.equals(to) && !selectionChanged && !conversionFields.contains(f.id())) {
                if (widening(from, to)) {
                    commands.add(
                            alterType(
                                    table.schema(),
                                    table.name(),
                                    SchemaTableDefinitions.columnName(f, o),
                                    to));
                    changes.add(
                            new Step(
                                    SchemaChangeEnum.WIDEN_COLUMN.getCode(), "扩大字段容量：" + f.name()));
                } else if (emptyTableTypeChange(before, oldOption, f, o)
                        && !Boolean.TRUE.equals(
                                mapper.check(hasRows(table.schema(), table.name())))) {
                    commands.add(
                            alterTypeUsingCast(
                                    table.schema(),
                                    table.name(),
                                    SchemaTableDefinitions.columnName(f, o),
                                    to));
                    changes.add(
                            new Step(
                                    SchemaChangeEnum.ALTER_COLUMN_TYPE.getCode(),
                                    "空表调整字段类型：" + f.name() + " " + from + " → " + to));
                } else
                    checks.add(
                            new Check(
                                    PublishCheckEnum.TYPE_NARROWING.getCode(),
                                    "不兼容的字段类型变化：" + f.name() + " " + from + " → " + to,
                                    true));
            }
            if (!Objects.equals(f.required(), before.required())
                    || conversionFields.contains(f.id())) {
                if (Boolean.TRUE.equals(f.required())
                        && Boolean.TRUE.equals(
                                mapper.check(
                                        hasNull(
                                                table.schema(),
                                                table.name(),
                                                SchemaTableDefinitions.columnName(f, o)))))
                    checks.add(
                            new Check(
                                    PublishCheckEnum.NULL_VALUES.getCode(),
                                    "设置必填前需要处理空值：" + f.name(),
                                    true));
                commands.add(
                        required(
                                table.schema(),
                                table.name(),
                                SchemaTableDefinitions.columnName(f, o),
                                Boolean.TRUE.equals(f.required())));
                changes.add(new Step(SchemaChangeEnum.REQUIRED.getCode(), "调整必填规则：" + f.name()));
            }
            if (!Objects.equals(o.defaultValue(), oldOption.defaultValue())
                    || selectionChanged
                    || conversionFields.contains(f.id())) {
                commands.add(
                        defaultValue(
                                table.schema(),
                                table.name(),
                                SchemaTableDefinitions.columnName(f, o),
                                to,
                                o.defaultValue()));
                changes.add(new Step(SchemaChangeEnum.DEFAULT.getCode(), "调整默认值：" + f.name()));
            }
        }
        for (var f : previous.values()) {
            var o = tableDefinitions.options(old, f);
            // 停用列仍保存历史值，但不再要求未来记录提交该字段。
            if (!FieldTypeEnum.SUMMARY.matches(f.type())) {
                if (Boolean.TRUE.equals(f.required()))
                    commands.add(
                            required(
                                    table.schema(),
                                    table.name(),
                                    SchemaTableDefinitions.columnName(f, o),
                                    false));
                if (Boolean.TRUE.equals(f.unique()))
                    commands.add(dropIndex(table.schema(), "nocode_u_" + f.id()));
                if (o.minimum() != null)
                    commands.add(
                            dropConstraint(
                                    table.schema(), table.name(), "nocode_c_" + f.id() + "_0"));
                if (o.maximum() != null)
                    commands.add(
                            dropConstraint(
                                    table.schema(), table.name(), "nocode_c_" + f.id() + "_1"));
                if (o.pattern() != null)
                    commands.add(
                            dropConstraint(
                                    table.schema(), table.name(), "nocode_c_" + f.id() + "_2"));
            }
            changes.add(
                    new Step(
                            SchemaChangeEnum.DEACTIVATE_FIELD.getCode(),
                            "停用字段并保留原列与数据：" + table.title() + "." + f.name()));
        }
    }

    /** 实时计算只在读取时按发布版本求值，调整其定义不改写已有列或历史记录。 */
    private static boolean liveCalculationChange(
            FieldDefinition before,
            FieldOptions oldOptions,
            FieldDefinition current,
            FieldOptions currentOptions) {
        if (Objects.equals(oldOptions.calculation(), currentOptions.calculation())) return true;
        if (FieldTypeEnum.FORMULA.matches(before.type())
                && FieldTypeEnum.FORMULA.matches(current.type())
                && before.id().equals(current.id())
                && Objects.equals(oldOptions.expression(), currentOptions.expression())
                && Objects.equals(oldOptions.resultType(), currentOptions.resultType())
                && SchemaTableDefinitions.columnName(before, oldOptions)
                        .equals(SchemaTableDefinitions.columnName(current, currentOptions))
                && com.richuang.os.nocode.metadata.service.formula.Calculations.sameOrderedRule(
                        oldOptions.calculation(), currentOptions.calculation())) return true;
        return FieldTypeEnum.FORMULA.matches(before.type())
                && FieldTypeEnum.FORMULA.matches(current.type())
                && oldOptions.calculation() != null
                && currentOptions.calculation() != null
                && CalculationUpdateEnum.LIVE.matches(oldOptions.calculation().updateMode())
                && CalculationUpdateEnum.LIVE.matches(currentOptions.calculation().updateMode());
    }

    /** 原发布快照证明稳定身份和物理列归属；恢复不重新建列，也不覆盖历史值。 */
    private boolean restoreRetained(
            Definition definition,
            TableDesign table,
            FieldDefinition field,
            FieldOptions options,
            DatabaseMetadata.Table actual,
            List<Command> commands,
            List<Step> changes,
            List<Check> checks) {
        String schema =
                store.lastPublishedFieldSchema(
                        Long.parseLong(definition.objectId()), Long.parseLong(field.id()));
        if (schema == null) return false;
        var historical = designCodec.read(schema, Definition.class);
        var originalTable =
                tableDefinitions.tables(historical, true).stream()
                        .filter(
                                t ->
                                        t.ref().equals(table.ref())
                                                && Objects.equals(t.detailId(), table.detailId()))
                        .findFirst()
                        .orElse(null);
        if (originalTable == null) return false;
        var original =
                originalTable.fields().stream()
                        .filter(f -> field.id().equals(f.id()))
                        .findFirst()
                        .orElse(null);
        if (original == null) return false;
        var oldOptions = tableDefinitions.options(originalTable, original);
        String columnName = SchemaTableDefinitions.columnName(field, options);
        if (!columnName.equals(SchemaTableDefinitions.columnName(original, oldOptions)))
            return false;
        var column =
                actual.columns().stream()
                        .filter(c -> columnName.equals(c.name()))
                        .findFirst()
                        .orElseThrow();
        if (!SchemaTableDefinitions.canonicalType(column.nativeType())
                .equals(
                        SchemaTableDefinitions.canonicalType(
                                SchemaTableDefinitions.sqlType(field, options)))) {
            checks.add(
                    new Check(
                            PublishCheckEnum.TYPE_NARROWING.getCode(),
                            "恢复字段与保留列类型不一致，请先恢复原字段定义：" + field.name(),
                            true));
            return true;
        }
        if (!Objects.equals(field.type(), original.type())
                || !Objects.equals(options.expression(), oldOptions.expression())
                || !Objects.equals(options.calculation(), oldOptions.calculation())
                || (options.autoNumber() == null) != (oldOptions.autoNumber() == null)
                || !SelectionFields.identity(field, options)
                        .equals(SelectionFields.identity(original, oldOptions))) {
            checks.add(
                    new Check(
                            PublishCheckEnum.STORAGE_CHANGE.getCode(),
                            "恢复字段必须保留原计算、编号和选择来源，请恢复后另行调整：" + field.name(),
                            true));
            return true;
        }
        if (Boolean.TRUE.equals(field.required())) {
            if (Boolean.TRUE.equals(
                    mapper.check(hasNull(table.schema(), table.name(), columnName))))
                checks.add(
                        new Check(
                                PublishCheckEnum.NULL_VALUES.getCode(),
                                "恢复必填字段前需要处理停用期间产生的空值：" + field.name(),
                                true));
            commands.add(required(table.schema(), table.name(), columnName, true));
        }
        if (!Objects.equals(options.defaultValue(), oldOptions.defaultValue()))
            commands.add(
                    defaultValue(
                            table.schema(),
                            table.name(),
                            columnName,
                            SchemaTableDefinitions.sqlType(field, options),
                            options.defaultValue()));
        changes.add(
                new Step(
                        SchemaChangeEnum.RESTORE_FIELD.getCode(),
                        "恢复字段并沿用原列与历史数据：" + table.title() + "." + field.name()));
        return true;
    }

    static boolean widening(String from, String to) {
        if (from.matches("varchar\\([0-9]+\\)"))
            return to.equals("text")
                    || to.matches("varchar\\([0-9]+\\)") && number(to) >= number(from);
        if (from.startsWith("numeric(") && to.startsWith("numeric(")) {
            int[] a =
                    Arrays.stream(from.substring(8, from.length() - 1).split(","))
                            .mapToInt(Integer::parseInt)
                            .toArray();
            int[] b =
                    Arrays.stream(to.substring(8, to.length() - 1).split(","))
                            .mapToInt(Integer::parseInt)
                            .toArray();
            return b[1] >= a[1] && b[0] - b[1] >= a[0] - a[1];
        }
        return false;
    }

    /** 空表不承载历史值时才允许跨存储类型；选择、关系、生成字段和校验/默认值由各自迁移协议维护，不能借此绕过。 */
    private static boolean emptyTableTypeChange(
            FieldDefinition before,
            FieldOptions oldOptions,
            FieldDefinition current,
            FieldOptions currentOptions) {
        return EMPTY_TABLE_REPLACEABLE_TYPES.contains(FieldTypeEnum.fromCode(before.type()))
                && EMPTY_TABLE_REPLACEABLE_TYPES.contains(FieldTypeEnum.fromCode(current.type()))
                && oldOptions.defaultValue() == null
                && oldOptions.minimum() == null
                && oldOptions.maximum() == null
                && oldOptions.pattern() == null
                && currentOptions.defaultValue() == null
                && currentOptions.minimum() == null
                && currentOptions.maximum() == null
                && currentOptions.pattern() == null;
    }

    static int number(String type) {
        return Integer.parseInt(type.replaceAll("[^0-9]", ""));
    }

    void checks(
            String schema,
            TableDesign table,
            FieldDefinition field,
            FieldOptions now,
            FieldOptions old,
            boolean existed,
            List<Command> commands,
            List<Step> changes) {
        String[] newValues = {now.minimum(), now.maximum(), now.pattern()},
                oldValues = {old.minimum(), old.maximum(), old.pattern()},
                operators = {">=", "<=", "~"};
        for (int i = 0; i < 3; i++)
            if (!Objects.equals(newValues[i], oldValues[i])) {
                String name = "nocode_c_" + field.id() + "_" + i;
                if (existed && oldValues[i] != null)
                    commands.add(dropConstraint(schema, table.name(), name));
                if (newValues[i] != null)
                    commands.add(
                            check(
                                    schema,
                                    table.name(),
                                    name,
                                    SchemaTableDefinitions.columnName(field, now),
                                    operators[i],
                                    newValues[i]));
                changes.add(
                        new Step(SchemaChangeEnum.VALIDATION.getCode(), "调整字段校验：" + field.name()));
            }
    }
}
