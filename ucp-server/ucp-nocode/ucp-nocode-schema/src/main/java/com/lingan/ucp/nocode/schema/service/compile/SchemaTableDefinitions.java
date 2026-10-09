package com.lingan.ucp.nocode.schema.service.compile;

import static com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands.*;

import com.lingan.ucp.framework.mybatis.core.metadata.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.formula.FieldExpressions;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaDates;
import com.lingan.ucp.nocode.metadata.service.table.TableBindingService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 对象版本中的物理表、稳定列名和类型参数；复用底座类型白名单及公式表达式校验。 */
@Component
public class SchemaTableDefinitions {
    @Resource private DatabaseMetadataReader database;

    record FieldTable(TableDesign design, FieldDefinition field, FieldOptions options) {
        String table() {
            return design.name();
        }

        String schema() {
            return design.binding().schemaName();
        }
    }

    record TableDesign(
            String name,
            String title,
            List<FieldDefinition> fields,
            Map<String, FieldOptions> options,
            String detailId,
            TableBinding binding) {
        String schema() {
            return binding.schemaName();
        }

        ObjectTables.Ref ref() {
            return new ObjectTables.Ref(schema(), name);
        }
    }

    String mainKeyType(Definition d) {
        var binding = ObjectTables.main(d);
        if (!binding.adopted()) return "bigint";
        return TableBindingService.key(
                        database.readTable(binding.schemaName(), d.tableName()).orElseThrow())
                .nativeType();
    }

    static String canonicalType(String type) {
        return type.replace("character varying", "varchar");
    }

    List<TableDesign> tables(Definition d) {
        return tables(d, false);
    }

    List<TableDesign> tables(Definition d, boolean includeRetained) {
        var result = new ArrayList<TableDesign>();
        result.add(
                new TableDesign(
                        d.tableName(),
                        d.objectName(),
                        d.fields(),
                        d.fieldOptions(),
                        null,
                        ObjectTables.main(d)));
        d.details().stream()
                .filter(
                        t ->
                                MemberStateEnum.ACTIVE.matches(t.state())
                                        || includeRetained
                                                && database.relationExists(
                                                        ObjectTables.detail(d, t).schemaName(),
                                                        t.tableName()))
                .forEach(
                        t ->
                                result.add(
                                        new TableDesign(
                                                t.tableName(),
                                                t.name(),
                                                t.fields(),
                                                t.fieldOptions(),
                                                t.id(),
                                                ObjectTables.detail(d, t))));
        return result;
    }

    public static FieldOptions options(Map<String, FieldOptions> values, FieldDefinition field) {
        return values.getOrDefault(field.id(), FieldOptions.defaults());
    }

    FieldOptions options(TableDesign table, FieldDefinition field) {
        return options(table.options(), field);
    }

    public static String columnName(FieldDefinition f, FieldOptions o) {
        return o.columnName() == null ? f.code() : o.columnName();
    }

    public static String sqlType(FieldDefinition f, FieldOptions o) {
        return type(FieldStorage.sqlType(f, o));
    }

    Column column(TableDesign table, FieldDefinition f) {
        var o = options(table, f);
        Expression expression = null;
        if (FieldTypeEnum.FORMULA.matches(f.type()) && o.calculation() == null) {
            Map<String, String> available = new HashMap<>();
            table.fields().stream()
                    .filter(v -> !FieldTypeEnum.fromCode(v.type()).isComputed())
                    .forEach(v -> available.put(v.code(), columnName(v, options(table, v))));
            expression = FieldExpressions.parse(o.expression(), available).expression();
            // 日期加减改写成显式的日期函数再渲染（日期时间按日期部分算，bigint / numeric 的天数先取整）。
            expression =
                    FormulaDates.lower(
                            expression, FormulaDates.generated(table.fields(), table.options()));
        }
        if ((expression != null || FieldTypeEnum.AUTO_NUMBER.matches(f.type()))
                && o.defaultValue() != null)
            throw new IllegalArgumentException("计算或自动编号字段不能设置常量默认值");
        return new Column(
                columnName(f, o),
                sqlType(f, o),
                Boolean.TRUE.equals(f.required()),
                o.defaultValue(),
                FieldTypeEnum.AUTO_NUMBER.matches(f.type()) && o.autoNumber() == null,
                false,
                expression);
    }
}
