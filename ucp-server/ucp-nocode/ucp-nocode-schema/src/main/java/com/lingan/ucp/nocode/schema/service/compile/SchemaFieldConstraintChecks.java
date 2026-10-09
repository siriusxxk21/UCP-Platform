package com.lingan.ucp.nocode.schema.service.compile;

import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadata;
import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadataReader;
import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.FieldStorage;
import com.lingan.ucp.nocode.api.ObjectTables;
import com.lingan.ucp.nocode.api.TableBinding;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.enums.MemberStateEnum;
import com.lingan.ucp.nocode.enums.PublishCheckEnum;
import com.lingan.ucp.nocode.metadata.dal.mapper.FieldConstraintCheckMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/** 新增或收紧值约束时先检查存量冲突，不执行清空或 DDL；调用方继续负责发布锁和事务。 */
@Component
public class SchemaFieldConstraintChecks {
    @Resource private DatabaseMetadataReader database;
    @Resource private FieldConstraintCheckMapper mapper;

    /** 转换字段由清空确认协议检查；其目标空值不参与本检查，避免把待清空旧值误判为新约束冲突。 */
    public List<Check> inspect(
            Definition current, Definition previous, Set<String> conversionFields) {
        Set<String> conversions = conversionFields == null ? Set.of() : conversionFields;
        List<Check> checks = new ArrayList<>();
        inspectTable(
                current.objectName(),
                current.tableName(),
                ObjectTables.main(current),
                current.fields(),
                current.fieldOptions(),
                previous == null ? List.of() : previous.fields(),
                previous == null ? Map.of() : previous.fieldOptions(),
                conversions,
                checks);
        for (Detail detail : current.details()) {
            if (!MemberStateEnum.ACTIVE.matches(detail.state())) continue;
            Detail old =
                    previous == null
                            ? null
                            : previous.details().stream()
                                    .filter(d -> Objects.equals(d.id(), detail.id()))
                                    .findFirst()
                                    .orElse(null);
            inspectTable(
                    current.objectName() + " / " + detail.name(),
                    detail.tableName(),
                    ObjectTables.detail(current, detail),
                    detail.fields(),
                    detail.fieldOptions(),
                    old == null ? List.of() : old.fields(),
                    old == null ? Map.of() : old.fieldOptions(),
                    conversions,
                    checks);
        }
        return List.copyOf(checks);
    }

    private void inspectTable(
            String sourceName,
            String tableName,
            TableBinding binding,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> options,
            List<FieldDefinition> previousFields,
            Map<String, FieldOptions> previousOptions,
            Set<String> conversions,
            List<Check> checks) {
        PostgreSqlCommands.identifier(binding.schemaName());
        PostgreSqlCommands.identifier(tableName);
        PostgreSqlCommands.identifier(binding.keyColumn());
        DatabaseMetadata.Table table =
                database.readTable(binding.schemaName(), tableName).orElse(null);
        if (table == null
                || table.columns().stream().noneMatch(c -> c.name().equals(binding.keyColumn())))
            return;
        Map<String, FieldDefinition> previousById =
                previousFields.stream()
                        .collect(Collectors.toMap(FieldDefinition::id, field -> field));
        Map<String, DatabaseMetadata.Column> columns =
                table.columns().stream()
                        .collect(Collectors.toMap(DatabaseMetadata.Column::name, column -> column));
        for (FieldDefinition field : fields) {
            if (conversions.contains(field.id())) continue;
            FieldOptions now = options.getOrDefault(field.id(), FieldOptions.defaults());
            if (MemberStateEnum.INACTIVE.matches(now.state())) continue;
            FieldOptions old = previousOptions.getOrDefault(field.id(), FieldOptions.defaults());
            String columnName = now.columnName() == null ? field.code() : now.columnName();
            PostgreSqlCommands.identifier(columnName);
            DatabaseMetadata.Column actual = columns.get(columnName);
            if (actual == null
                    || !canonical(FieldStorage.sqlType(field, now))
                            .equals(canonical(actual.nativeType()))) continue;
            FieldDefinition before = previousById.get(field.id());
            if (before != null
                    && !canonical(FieldStorage.sqlType(before, old))
                            .equals(canonical(FieldStorage.sqlType(field, now)))) continue;
            if (FieldTypeEnum.fromCode(field.type()).isNumeric()) {
                if (tightened(now.minimum(), old.minimum(), true))
                    addConflict(
                            checks,
                            sourceName,
                            field,
                            "最小值 " + now.minimum(),
                            mapper.belowMinimum(
                                    statement(binding, tableName, columnName, now.minimum())));
                if (tightened(now.maximum(), old.maximum(), false))
                    addConflict(
                            checks,
                            sourceName,
                            field,
                            "最大值 " + now.maximum(),
                            mapper.aboveMaximum(
                                    statement(binding, tableName, columnName, now.maximum())));
            }
            if (Set.of(FieldTypeEnum.TEXT.getCode(), FieldTypeEnum.TEXTAREA.getCode())
                            .contains(field.type())
                    && now.pattern() != null
                    && !Objects.equals(now.pattern(), old.pattern()))
                addConflict(
                        checks,
                        sourceName,
                        field,
                        "正则规则 " + now.pattern(),
                        mapper.patternMismatch(
                                statement(binding, tableName, columnName, now.pattern())));
        }
    }

    private static FieldConstraintCheckMapper.Statement statement(
            TableBinding binding, String table, String column, String value) {
        return new FieldConstraintCheckMapper.Statement(
                binding.schemaName(), table, column, binding.keyColumn(), value);
    }

    static boolean tightened(String current, String previous, boolean minimum) {
        if (current == null || Objects.equals(current, previous)) return false;
        if (previous == null) return true;
        int change = new BigDecimal(current).compareTo(new BigDecimal(previous));
        return minimum ? change > 0 : change < 0;
    }

    private static String canonical(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replace("character varying", "varchar")
                .replaceAll("\\s+", "");
    }

    private static void addConflict(
            List<Check> checks,
            String sourceName,
            FieldDefinition field,
            String rule,
            List<FieldConstraintCheckMapper.Conflict> conflicts) {
        if (conflicts.isEmpty()) return;
        long count = conflicts.getFirst().total();
        String ids =
                conflicts.stream()
                        .map(FieldConstraintCheckMapper.Conflict::recordId)
                        .collect(Collectors.joining("、"));
        checks.add(
                new Check(
                        PublishCheckEnum.INVALID_FIELD.getCode(),
                        "“"
                                + sourceName
                                + "”字段“"
                                + field.name()
                                + "”的"
                                + rule
                                + "与 "
                                + count
                                + " 条历史数据冲突（包含逻辑删除记录）；记录 ID："
                                + ids
                                + (count > conflicts.size() ? "（仅列出前 10 条）" : "")
                                + "。请先修正数据或调整规则。",
                        true));
    }
}
