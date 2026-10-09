package com.richuang.os.nocode.runtime.service.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.nocode.api.DataCenter;
import com.richuang.os.nocode.api.RecordHistory;
import com.richuang.os.nocode.enums.ApplicationActionEnum;
import com.richuang.os.nocode.enums.MemberStateEnum;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.*;

/** 公共历史的可见明细投影；每个应用的字段必须来自其实际授权的对象版本。 */
@Service
public class HistoryDetailProjection {
    @Resource private ObjectMapper json;

    /** 允许只授予明细读取的场景；不能把没有主表字段误判成整条记录不可读。 */
    public boolean readable(JsonNode row, List<ApplicationRuntimePolicy.Access> access) {
        return fields(row, access).values().stream().anyMatch(fields -> !fields.isEmpty());
    }

    public List<RecordHistory.DetailChange> changes(
            JsonNode event, List<ApplicationRuntimePolicy.Access> access) {
        var before = event.path("before");
        var after = event.path("after");
        var b = fields(before, access);
        var a = fields(after, access);
        var visible = absent(before) ? a : b;
        if (!absent(after) && !absent(before)) {
            visible.keySet().retainAll(a.keySet());
            visible.forEach((id, fields) -> fields.keySet().retainAll(a.get(id).keySet()));
        }
        List<RecordHistory.DetailChange> result = new ArrayList<>();
        for (var entry : visible.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            var id = entry.getKey();
            boolean beforeKnown = absent(before) || before.path("details").has(id);
            boolean afterKnown = absent(after) || after.path("details").has(id);
            if (!beforeKnown && !afterKnown) continue;
            var oldRows = rows(before, id, entry.getValue().keySet());
            var newRows = rows(after, id, entry.getValue().keySet());
            var oldOrder = order(before, id, oldRows.keySet());
            var newOrder = order(after, id, newRows.keySet());
            if (beforeKnown && afterKnown && oldRows.equals(newRows) && oldOrder.equals(newOrder))
                continue;
            String name = id;
            for (var item : access) {
                var detail =
                        item.definition().details().stream()
                                .filter(d -> id.equals(d.id()))
                                .findFirst();
                if (detail.isPresent()) {
                    name = detail.get().name();
                    break;
                }
            }
            // 历史标签只覆盖当前允许的字段；不返回无权限的明细组或已移除的字段。
            for (var historical : event.path("detailFields")) {
                if (!id.equals(historical.path("id").asText())) continue;
                name = historical.path("name").asText(name);
                for (var f : historical.path("fields")) {
                    String field = f.path("id").asText();
                    if (entry.getValue().containsKey(field))
                        entry.getValue().put(field, f.path("name").asText());
                }
            }
            var labels =
                    entry.getValue().entrySet().stream()
                            .map(f -> new RecordHistory.Field(f.getKey(), f.getValue()))
                            .toList();
            result.add(
                    new RecordHistory.DetailChange(
                            id,
                            name,
                            labels,
                            oldRows,
                            newRows,
                            oldOrder,
                            newOrder,
                            beforeKnown,
                            afterKnown));
        }
        return result;
    }

    private Map<String, Map<String, String>> fields(
            JsonNode row, List<ApplicationRuntimePolicy.Access> access) {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        if (absent(row)) return result;
        Map<String, Object> values = json.convertValue(row.path("values"), Map.class);
        for (var item : access) {
            var caps = item.forRow(row.path("recordCreator").asText(), values);
            if (!caps.actions().contains(ApplicationActionEnum.READ.getCode())) continue;
            for (var d : item.definition().details()) {
                if (!caps.readDetails().contains(d.id())
                        || !MemberStateEnum.ACTIVE.matches(d.state())) continue;
                var fields = result.computeIfAbsent(d.id(), ignored -> new LinkedHashMap<>());
                d.fields().stream()
                        .filter(
                                f ->
                                        !MemberStateEnum.INACTIVE.matches(
                                                d.fieldOptions()
                                                        .getOrDefault(
                                                                f.id(),
                                                                DataCenter.FieldOptions.defaults())
                                                        .state()))
                        .forEach(f -> fields.put(f.id(), f.name()));
            }
        }
        return result;
    }

    private Map<String, Map<String, Object>> rows(
            JsonNode snapshot, String detail, Set<String> fields) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        snapshot.path("details")
                .path(detail)
                .fields()
                .forEachRemaining(
                        row -> {
                            Map<String, Object> values = new LinkedHashMap<>();
                            row.getValue()
                                    .fields()
                                    .forEachRemaining(
                                            f -> {
                                                if (fields.contains(f.getKey()))
                                                    values.put(
                                                            f.getKey(),
                                                            json.convertValue(
                                                                    f.getValue(), Object.class));
                                            });
                            result.put(row.getKey(), values);
                        });
        return result;
    }

    private List<String> order(JsonNode snapshot, String detail, Set<String> rows) {
        List<String> result = new ArrayList<>();
        for (var id : snapshot.path("detailOrder").path(detail))
            if (rows.contains(id.asText()) && !result.contains(id.asText()))
                result.add(id.asText());
        rows.stream().filter(id -> !result.contains(id)).sorted().forEach(result::add);
        return result;
    }

    private boolean absent(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode();
    }
}
