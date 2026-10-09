package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.mybatis.core.metadata.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.math.*;
import java.time.*;
import java.util.*;

/** 业务输入校验独立于页面引擎；绕过前端也不能写审计列、公式列、未知字段或非法选项。 */
@Component
public class RecordValues {
    @Resource private ObjectMapper json;

    public Map<String, Object> normalize(
            RuntimeSchema.Table table, Map<String, Object> values, boolean insert) {
        return normalize(table, values, insert, Map.of());
    }

    public Map<String, Object> normalize(
            RuntimeSchema.Table table,
            Map<String, Object> values,
            boolean insert,
            Map<String, Object> previous) {
        if (values == null) throw invalid("记录字段不能为空");
        if (values.size() > 500) throw invalid("记录字段过多");
        Map<String, Object> out = new LinkedHashMap<>();
        for (var e : values.entrySet()) {
            var f =
                    table.fields().stream()
                            .filter(v -> v.id().equals(e.getKey()))
                            .findFirst()
                            .orElseThrow(() -> invalid("包含未定义或已停用字段"));
            var type = FieldTypeEnum.fromCode(f.type());
            var options = table.options().getOrDefault(f.id(), DataCenter.FieldOptions.defaults());
            String column = table.column(f);
            var actual =
                    table.physical().columns().stream()
                            .filter(c -> c.name().equals(column))
                            .findFirst()
                            .orElse(null);
            if (BaseDOColumns.NAMES.contains(column)
                    || type.isComputed()
                    || type == FieldTypeEnum.AUTO_NUMBER
                    || actual != null
                            && actual.generatedKind() != null
                            && !actual.generatedKind().isBlank()
                    || column.equals(table.key().name()) && (!insert || table.generatedKey()))
                throw invalid("字段由系统维护，不能手动提交：" + f.name());
            // 未改动的历史选择允许保留；仍校验字段写权限、类型和记录版本。
            boolean unchangedSelection =
                    !insert
                            && SelectionFields.source(f, options) != null
                            && Objects.equals(e.getValue(), previous.get(f.id()));
            Object value =
                    unchangedSelection
                            ? e.getValue()
                            : convert(
                                    f,
                                    options,
                                    e.getValue(),
                                    new HashSet<>(SelectionCatalog.ids(previous.get(f.id()))));
            if (Boolean.TRUE.equals(f.required())
                    && (value == null || value instanceof Collection<?> items && items.isEmpty()))
                throw invalid("必填字段不能为空：" + f.name());
            out.put(column, value);
        }
        if (insert && !table.generatedKey() && !out.containsKey(table.key().name()))
            throw invalid("已有表没有主键生成器，请填写主键");
        if (insert)
            for (var f : table.fields()) {
                String col = table.column(f);
                if (out.containsKey(col)
                        || BaseDOColumns.NAMES.contains(col)
                        || FieldTypeEnum.fromCode(f.type()).isComputed()
                        || FieldTypeEnum.AUTO_NUMBER.matches(f.type())) continue;
                var actual =
                        table.physical().columns().stream()
                                .filter(c -> c.name().equals(col))
                                .findFirst()
                                .orElse(null);
                if (actual == null
                        || actual.defaultExpression() != null
                        || actual.identityKind() != null && !actual.identityKind().isBlank()
                        || actual.generatedKind() != null && !actual.generatedKind().isBlank())
                    continue;
                if (Boolean.TRUE.equals(f.required())) throw invalid("缺少必填字段：" + f.name());
            }
        return out;
    }

    public Object convert(FieldDefinition f, DataCenter.FieldOptions o, Object raw) {
        return convert(f, o, raw, Set.of());
    }

    private Object convert(
            FieldDefinition f, DataCenter.FieldOptions o, Object raw, Set<String> previous) {
        if (raw == null || raw instanceof String s && s.isEmpty()) return null;
        try {
            var type = FieldTypeEnum.fromCode(f.type());
            String s = raw.toString();
            Object value = s;
            switch (type) {
                case URL -> value = com.lingan.ucp.nocode.api.HyperlinkValue.normalize(raw);
                case INTEGER -> {
                    var n = new BigInteger(s);
                    if (n.bitLength() > 63) throw new IllegalArgumentException();
                    value = n;
                }
                case DECIMAL, MONEY, PERCENT -> {
                    var n = new BigDecimal(s);
                    if (f.scale() != null) n = n.setScale(f.scale(), RoundingMode.UNNECESSARY);
                    if (f.precision() != null && n.precision() > f.precision())
                        throw new IllegalArgumentException();
                    value = n;
                }
                case BOOLEAN -> {
                    if (!(raw instanceof Boolean)) throw new IllegalArgumentException();
                    value = raw;
                }
                case DATE -> value = LocalDate.parse(s).toString();
                case DATETIME -> {
                    if (o.nativeType() != null && o.nativeType().contains("with time zone"))
                        value = OffsetDateTime.parse(s).toString();
                    else value = LocalDateTime.parse(s.replace(" ", "T")).toString();
                }
                case TIME -> value = LocalTime.parse(s).toString();
                case UUID -> value = UUID.fromString(s).toString();
                case SELECT -> {
                    if (raw instanceof Collection<?> || raw instanceof Map<?, ?>)
                        throw invalid("单选字段只能填写一个选择值：" + f.name());
                    if (o.selection() != null
                            && !SelectionSourceEnum.LOCAL_OPTIONS.matches(o.selection().kind()))
                        break;
                    if (o.options() == null
                            || o.options().stream()
                                    .noneMatch(
                                            v ->
                                                    v.code().equals(s)
                                                            && !Boolean.TRUE.equals(v.disabled())))
                        throw invalid("选项不存在或已停用：" + f.name());
                }
                case MULTI_SELECT, IMAGE, ATTACHMENT, REGION, CASCADE -> {
                    if (!(raw instanceof List<?> list) || list.size() > 100)
                        throw invalid("多值字段格式无效：" + f.name());
                    if (type == FieldTypeEnum.MULTI_SELECT
                            && (o.selection() == null
                                    || SelectionSourceEnum.LOCAL_OPTIONS.matches(
                                            o.selection().kind())))
                        for (Object option : list)
                            if (!previous.contains(Objects.toString(option))
                                    && (o.options() == null
                                            || o.options().stream()
                                                    .noneMatch(
                                                            v ->
                                                                    v.code().equals(option)
                                                                            && !Boolean.TRUE.equals(
                                                                                    v.disabled()))))
                                throw invalid("选项不存在或已停用：" + f.name());
                    value = list;
                }
                default -> {
                    if (!(raw instanceof String || raw instanceof Number))
                        throw new IllegalArgumentException();
                }
            }
            if (value instanceof String str
                    && (str.length() > 100000 || f.length() != null && str.length() > f.length()))
                throw invalid("字段内容超过长度限制：" + f.name());
            if (type.isNumeric()) {
                var n = new BigDecimal(value.toString());
                if (o.minimum() != null
                                && !o.minimum().isBlank()
                                && n.compareTo(new BigDecimal(o.minimum())) < 0
                        || o.maximum() != null
                                && !o.maximum().isBlank()
                                && n.compareTo(new BigDecimal(o.maximum())) > 0)
                    throw invalid("字段数值超出允许范围：" + f.name());
            }
            return value;
        } catch (com.lingan.ucp.framework.common.exception.ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw invalid("字段值格式无效：" + f.name());
        }
    }
}
