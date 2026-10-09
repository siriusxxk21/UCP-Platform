package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.Row;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.runtime.dal.mapper.*;
import com.richuang.os.nocode.runtime.dal.query.*;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/** 一级内部明细的实时汇总。必须同时拥有汇总字段与来源明细的查看权，避免推算隐藏业务数据。 */
@Service
public class RecordSummaries {
    @Resource private RuntimeSchema schemas;
    @Resource private RecordMapper records;
    @Resource private ObjectMapper json;
    private static final Pattern EXPRESSION =
            Pattern.compile(
                    "(count|sum|avg|min|max)\\(([a-z][a-z0-9_]*)(?:\\.([a-z][a-z0-9_]*))?\\)");

    public String detailId(Definition d, FieldDefinition field) {
        var match =
                EXPRESSION.matcher(
                        Objects.toString(
                                d.fieldOptions()
                                        .getOrDefault(field.id(), FieldOptions.defaults())
                                        .expression(),
                                ""));
        if (!match.matches()) throw invalid("汇总表达式无效：" + field.name());
        return d.details().stream()
                .filter(
                        t ->
                                t.code().equals(match.group(2))
                                        && MemberStateEnum.ACTIVE.matches(t.state()))
                .map(Detail::id)
                .findFirst()
                .orElseThrow(() -> invalid("汇总来源明细不存在"));
    }

    public List<Row> enrich(Definition d, List<Row> rows) {
        if (rows.isEmpty()) return rows;
        Map<String, Map<String, Object>> output = new LinkedHashMap<>();
        rows.forEach(row -> output.put(row.id(), new LinkedHashMap<>(row.values())));
        for (var field : d.fields()) {
            if (!FieldTypeEnum.SUMMARY.matches(field.type())
                    || MemberStateEnum.INACTIVE.matches(
                            d.fieldOptions()
                                    .getOrDefault(field.id(), FieldOptions.defaults())
                                    .state())) continue;
            String detailId = detailId(d, field);
            var allowed =
                    rows.stream()
                            .filter(
                                    r ->
                                            r.permissions().readFields().contains(field.id())
                                                    && r.permissions()
                                                            .readDetails()
                                                            .contains(detailId))
                            .map(Row::id)
                            .toList();
            if (allowed.isEmpty()) continue;
            var match = EXPRESSION.matcher(d.fieldOptions().get(field.id()).expression());
            if (!match.matches()) throw invalid("汇总表达式无效");
            var operation = SummaryOperationEnum.fromCode(match.group(1));
            var detail =
                    d.details().stream()
                            .filter(t -> t.id().equals(detailId))
                            .findFirst()
                            .orElseThrow();
            var table = schemas.detail(d, detail);
            String column =
                    operation == SummaryOperationEnum.COUNT
                            ? null
                            : detail.fields().stream()
                                    .filter(f -> f.code().equals(match.group(3)))
                                    .map(f -> table.columns().get(f.id()))
                                    .filter(Objects::nonNull)
                                    .findFirst()
                                    .orElseThrow(() -> invalid("汇总数值字段不可用"));
            allowed.forEach(
                    id -> output.get(id).put(field.id(), operation.zeroWhenEmpty() ? "0" : null));
            try {
                var statement =
                        new SummaryStatement(
                                table.schema(),
                                table.name(),
                                table.binding().parentColumn(),
                                column,
                                operation,
                                json.writeValueAsString(allowed),
                                table.statement(null, null, null, false).deletedColumn());
                for (String raw : records.summaries(statement)) {
                    var value = json.readTree(raw);
                    String parent = value.path("parent").asText();
                    if (allowed.contains(parent))
                        output.get(parent)
                                .put(
                                        field.id(),
                                        value.path("value").isNull()
                                                ? operation.zeroWhenEmpty() ? "0" : null
                                                : value.path("value").asText());
                }
            } catch (java.io.IOException e) {
                throw invalid("汇总结果无法读取");
            }
        }
        return rows.stream()
                .map(r -> new Row(r.id(), r.revision(), output.get(r.id()), r.permissions()))
                .toList();
    }
}
