package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.runtime.dal.support.RuntimeConditionSql;
import com.richuang.os.nocode.runtime.service.access.ScopeConditions;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 旧等值与新固定范围都独立追加，客户端不能覆盖或移除。 */
@Component
public class FixedViewConditions {
    @Resource private RuntimeConditionSql sqlFragments;
    @Resource private ObjectMapper json;
    @Resource private RecordValues values;
    @Resource private RecordRelations relations;
    @Resource private ScopeConditions scopes;

    public QueryWrapper<Object> append(
            ApplicationUi.View view,
            DataCenter.Definition d,
            RuntimeSchema.Table table,
            QueryWrapper<Object> where,
            long actor) {
        if (view == null) return where;
        if (where == null) {
            where = new QueryWrapper<>();
            where.setParamAlias("dynamicQuery");
        }
        for (var entry : view.equal().entrySet()) {
            var relation = BusinessFields.relation(d, entry.getKey());
            if (BusinessFields.multiple(relation)) {
                var link = relations.statement(d, relation, null, null, actor);
                where.apply(
                        sqlFragments.fixedRelation(link.schema(), link.table(), table.key().name()),
                        encode(entry.getValue()));
            } else {
                var f =
                        table.fields().stream()
                                .filter(v -> v.id().equals(entry.getKey()))
                                .findFirst()
                                .orElseThrow(() -> invalid("固定范围字段不存在"));
                var column = table.columns().get(f.id());
                if (column == null) throw invalid("固定范围字段未映射");
                var payload = new LinkedHashMap<String, Object>();
                payload.put(
                        column,
                        values.convert(
                                f,
                                table.options()
                                        .getOrDefault(f.id(), DataCenter.FieldOptions.defaults()),
                                entry.getValue()));
                where.apply(
                        sqlFragments.fixedScalar(
                                sqlFragments.column("t", column, false),
                                table.schema(),
                                table.name(),
                                column),
                        encode(payload));
            }
        }
        if (view.query() != null) {
            view.query().validate(d);
            scopes.append(where, view.query().scope(), d, table, Map.of(), "t");
        }
        return where;
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw invalid("固定范围格式无效");
        }
    }
}
