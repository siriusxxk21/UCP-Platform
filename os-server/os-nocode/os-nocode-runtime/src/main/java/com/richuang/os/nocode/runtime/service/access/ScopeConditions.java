package com.richuang.os.nocode.runtime.service.access;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.runtime.dal.support.RuntimeConditionSql;
import com.richuang.os.nocode.runtime.service.record.RuntimeSchema;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.time.temporal.TemporalAccessor;
import java.util.*;

/** 已发布字段范围转成参数化 MyBatis 条件，不接收 SQL 或物理标识符。 */
@Component
public class ScopeConditions {
    @Resource private RuntimeConditionSql sqlFragments;
    @Resource private ObjectMapper json;

    public void append(
            QueryWrapper<Object> where,
            DataScope scope,
            DataCenter.Definition d,
            RuntimeSchema.Table table,
            Map<String, Object> context,
            String alias) {
        append(where, scope, d, table, context, alias, null);
    }

    /**
     * 同上，但条件里的相对日期（今天、本周……）按 today 换算，而不是按机器当前日期。today 为 null 时取 {@link
     * RelativeDates#today()}。按日期自动执行用它：「今天」= 正在处理的那个业务日（补跑历史业务日时也按那一天）。
     */
    public void append(
            QueryWrapper<Object> where,
            DataScope scope,
            DataCenter.Definition d,
            RuntimeSchema.Table table,
            Map<String, Object> context,
            String alias,
            java.time.LocalDate today) {
        if (scope == null) return;
        where.nested(
                group -> {
                    boolean first = true;
                    for (DataScope.Condition c : scope.conditions()) {
                        if (!first && "OR".equals(scope.logic())) group.or();
                        first = false;
                        condition(group, c, d, table, context, alias, today);
                    }
                    for (DataScope nested : scope.groups()) {
                        if (!first && "OR".equals(scope.logic())) group.or();
                        first = false;
                        append(group, nested, d, table, context, alias, today);
                    }
                });
    }

    private void condition(
            QueryWrapper<Object> where,
            DataScope.Condition c,
            DataCenter.Definition d,
            RuntimeSchema.Table table,
            Map<String, Object> context,
            String alias,
            java.time.LocalDate today) {
        FieldDefinition f = DataScope.field(d, c.fieldId());
        ScopeOperatorEnum op = ScopeOperatorEnum.fromCode(c.operator());
        String name = table.columns().get(c.fieldId());
        if (name == null) throw invalid("范围字段未映射到业务列");
        String column = sqlFragments.column(alias, name, false);
        if (op == ScopeOperatorEnum.IS_NULL) {
            where.isNull(column);
            return;
        }
        if (op == ScopeOperatorEnum.NOT_NULL) {
            where.isNotNull(column);
            return;
        }
        Object raw = DataScope.operand(c, context);
        if (raw == null) {
            where.apply(sqlFragments.alwaysFalse());
            return;
        }
        if (RelativeDates.isRelative(raw)) {
            relative(where, c, f, d, table, column, name, raw, today);
            return;
        }
        if (op == ScopeOperatorEnum.IN) {
            if (!(raw instanceof List<?> list) || list.isEmpty()) {
                where.apply(sqlFragments.alwaysFalse());
                return;
            }
            where.nested(
                    group -> {
                        boolean first = true;
                        for (Object value : (List<?>) raw) {
                            if (!first) group.or();
                            first = false;
                            scalar(group, column, name, table, DataScope.scalar(d, f, value), "=");
                        }
                    });
        } else if (FieldTypeEnum.MULTI_SELECT.matches(f.type())) {
            List<?> list = raw instanceof List<?> entries ? entries : List.of(raw);
            if (op == ScopeOperatorEnum.CONTAINS_ANY)
                where.apply(sqlFragments.containsAny(column, false), encode(list));
            else if (op == ScopeOperatorEnum.CONTAINS_ALL)
                where.apply(sqlFragments.containsAll(column), encode(list));
            else
                where.apply(
                        sqlFragments.multiEquals(column, op != ScopeOperatorEnum.EQ), encode(list));
        } else {
            String operator =
                    switch (op) {
                        case EQ -> "=";
                        case NEQ -> "<>";
                        case GT -> ">";
                        case GTE -> ">=";
                        case LT -> "<";
                        case LTE -> "<=";
                        default -> throw invalid("无效范围算子");
                    };
            scalar(where, column, name, table, DataScope.scalar(d, f, raw), operator);
        }
    }

    /** 相对日期：按执行当天换算成区间边界，拼成参数化的 (列 >= 起 AND 列 < 止) 等条件；不存换算结果。 */
    private void relative(
            QueryWrapper<Object> where,
            DataScope.Condition c,
            FieldDefinition f,
            DataCenter.Definition d,
            RuntimeSchema.Table table,
            String column,
            String name,
            Object raw,
            java.time.LocalDate today) {
        var options = d.fieldOptions().getOrDefault(f.id(), DataCenter.FieldOptions.defaults());
        var spec = RelativeDates.check(f, c.operator(), raw);
        var bounds =
                RelativeDates.bounds(
                        c.operator(),
                        RelativeDates.range(spec, today == null ? RelativeDates.today() : today));
        where.nested(
                group -> {
                    boolean first = true;
                    for (var bound : bounds.parts()) {
                        if (!first && bounds.any()) group.or();
                        first = false;
                        scalar(
                                group,
                                column,
                                name,
                                table,
                                RelativeDates.boundValue(f, options, bound.date()),
                                "lt".equals(bound.operator()) ? "<" : ">=");
                    }
                });
    }

    private void scalar(
            QueryWrapper<Object> where,
            String column,
            String name,
            RuntimeSchema.Table table,
            Object value,
            String op) {
        // 底座 HTTP 日期序列化可能输出毫秒数；业务列 JSON 转型须使用 ISO 文本并保留偏移。
        Object operand = value instanceof TemporalAccessor ? value.toString() : value;
        where.apply(
                sqlFragments.scopeScalar(column, table.schema(), table.name(), name, op),
                encode(Map.of(name, operand)));
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw invalid("范围条件无法编码");
        }
    }
}
