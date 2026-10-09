package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.form.DocumentPolicies;
import com.lingan.ucp.nocode.metadata.service.formula.Calculations;
import com.lingan.ucp.nocode.metadata.service.formula.OrderedCalculationStateService;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordHistoryMapper;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordMapper;
import com.lingan.ucp.nocode.runtime.service.history.RecordHistoryTracker;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/** 仅维护当前对象有序 ON_SAVE。每次显式完成后丢弃求值状态，保存点回滚不残留内存脏组。 */
@Component
public class OrderedRecordCalculations {
    @Resource private DataObjectApi objects;
    @Resource private OrderedCalculationStateService states;
    @Resource private RecordHistoryMapper historyLocks;
    @Resource private RecordHistoryTracker history;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordCalculations calculations;
    @Resource private RecordPersistence persistence;
    @Resource private RecordMapper records;
    @Resource private RecordProcessService processes;
    @Resource private RecordDocumentValidation documents;
    @Resource private ObjectMapper json;
    @Resource private com.lingan.ucp.nocode.runtime.service.live.RecordChangeCollector changes;

    /** 沿用原有对象锁，必须在业务行锁之前，且在 catalog/automation 锁之后调用。 */
    public void lock(String object) {
        DataCenter.Definition current = objects.getPublished(object);
        if (fields(current).isEmpty()) return;
        historyLocks.lock(object);
        states.requireWritable(current);
    }

    public void requireCompatible(DataCenter.Definition requested) {
        OrderedCalculationStateService.requireCompatible(
                requested, objects.getPublished(requested.objectId()));
    }

    public Map<String, Object> before(DataCenter.Definition d, String id, long actor) {
        return id == null || fields(d).isEmpty()
                ? Map.of()
                : persistence.read(schemas.main(d), id, null, actor, false).values();
    }

    public List<FieldDefinition> fields(DataCenter.Definition d) {
        return d.fields().stream()
                .filter(
                        field ->
                                Calculations.orderedStored(d.fieldOptions().get(field.id()))
                                        && !MemberStateEnum.INACTIVE.matches(
                                                d.fieldOptions().get(field.id()).state()))
                .toList();
    }

    /** 写入结束即完成，最终校验、历史和返回结果在调用方随后执行；只写实际变化的公式列。 */
    public long complete(
            String app,
            DataCenter.Definition d,
            String id,
            Map<String, Object> before,
            boolean deleted,
            long actor) {
        List<FieldDefinition> fields = fields(d);
        if (fields.isEmpty()) return 0;
        Map<String, Object> after = deleted ? Map.of() : before(d, id, actor);
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        for (FieldDefinition field : fields) {
            CalculationOptions c = d.fieldOptions().get(field.id()).calculation();
            Set<String> dependencies =
                    Calculations.sequence(c)
                            ? Calculations.sequenceSourceFields(d, field, c)
                            : Calculations.sourceFields(d, c);
            boolean affected =
                    before.isEmpty()
                            || deleted
                            || dependencies.stream()
                                    .anyMatch(
                                            key -> !same(d, key, before.get(key), after.get(key)));
            if (!affected) continue;
            Map<String, List<Object>> groups = new LinkedHashMap<>();
            if (!before.isEmpty()) addGroup(d, c, before, groups);
            if (!deleted) addGroup(d, c, after, groups);
            for (List<Object> group : groups.values()) {
                List<RecordCalculations.OrderedValue> values =
                        calculations.orderedGroup(app, d, field.id(), group, actor);
                for (RecordCalculations.OrderedValue value : values) {
                    if (!same(d, field.id(), value.stored().get(field.id()), value.expected()))
                        changes.computeIfAbsent(value.id(), key -> new LinkedHashMap<>())
                                .put(field.id(), value.expected());
                }
            }
        }
        return apply(app, d, changes, deleted ? Set.of() : Set.of(id), actor, id);
    }

    /** 纯新增导入按字段与组去重；新行由导入调用方在全部完成后各封存一次最终 CREATE。 */
    public long completeImported(
            String app, DataCenter.Definition d, Collection<String> insertedIds, long actor) {
        List<Map<String, Object>> inserted =
                insertedIds.stream().map(id -> before(d, id, actor)).toList();
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        for (FieldDefinition field : fields(d)) {
            CalculationOptions calculation = d.fieldOptions().get(field.id()).calculation();
            Map<String, List<Object>> groups = new LinkedHashMap<>();
            inserted.forEach(row -> addGroup(d, calculation, row, groups));
            for (List<Object> group : groups.values()) {
                for (RecordCalculations.OrderedValue value :
                        calculations.orderedGroup(app, d, field.id(), group, actor)) {
                    if (!same(d, field.id(), value.stored().get(field.id()), value.expected()))
                        changes.computeIfAbsent(value.id(), key -> new LinkedHashMap<>())
                                .put(field.id(), value.expected());
                }
            }
        }
        return apply(app, d, changes, new HashSet<>(insertedIds), actor, null);
    }

    public long applyGroup(
            String app,
            DataCenter.Definition d,
            String fieldId,
            List<RecordCalculations.OrderedValue> values,
            long actor) {
        Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
        for (RecordCalculations.OrderedValue value : values) {
            if (same(d, fieldId, value.stored().get(fieldId), value.expected())) continue;
            Map<String, Object> changed = new LinkedHashMap<>();
            changed.put(fieldId, value.expected());
            changes.put(value.id(), changed);
        }
        return apply(app, d, changes, Set.of(), actor, null);
    }

    private long apply(
            String app,
            DataCenter.Definition d,
            Map<String, Map<String, Object>> changes,
            Set<String> excludedHistory,
            long actor,
            String sourceId) {
        if (changes.isEmpty()) return 0;
        if (changes.size() > 100000) throw invalid("本次有序联动更新超过 100000 行预算；保存已回滚");
        DataCenter.Definition current = history.begin(d, actor);
        RecordHistoryTracker.CaptureContext historyContext = history.context(current);
        RuntimeSchema.Table table = schemas.main(d);
        for (Map.Entry<String, Map<String, Object>> entry : changes.entrySet()) {
            String id = entry.getKey();
            String previous =
                    excludedHistory.contains(id)
                            ? null
                            : history.capture(historyContext, id, actor);
            DocumentPolicies.Input beforeDocument = null;
            if (!excludedHistory.contains(id)) {
                processes.requireIdle(d.objectId(), id);
                if (DocumentPolicies.policy(current) != null)
                    beforeDocument = documents.documentInput(current, id, actor, Map.of());
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            entry.getValue()
                    .forEach((field, value) -> payload.put(table.columns().get(field), value));
            if (records.orderedUpdate(persistence.writeStatement(table, id, null, payload, actor))
                    != 1) throw invalid("有序计算联动保存失败，整笔操作已回滚");
            // 本方法的参数也叫 changes，登记簿字段须用 this 限定。
            this.changes.changed(d.objectId(), id, RecordChangeOperationEnum.UPDATE);
            if (beforeDocument != null) {
                Set<String> policyFields = new HashSet<>();
                for (DocumentPolicy.Rule rule : DocumentPolicies.policy(current).rules()) {
                    policyFields(rule.when(), policyFields);
                    policyFields(rule.assertion(), policyFields);
                }
                policyFields.removeAll(entry.getValue().keySet());
                try {
                    states.requireReady(current, policyFields);
                } catch (com.lingan.ucp.framework.common.exception.ServiceException error) {
                    throw invalid("整单规则依赖其他尚未就绪的有序字段，请先校准依赖字段后重试");
                }
                DocumentPolicies.Input afterDocument =
                        DocumentCalculations.calculate(
                                current,
                                documents.documentInput(current, id, actor, Map.of()),
                                values ->
                                        calculations.finalDocumentValues(
                                                app,
                                                current,
                                                id,
                                                values,
                                                entry.getValue().keySet(),
                                                actor));
                try {
                    DocumentStates.requireWrite(current, beforeDocument, afterDocument);
                    if (!DocumentPolicies.evaluate(DocumentPolicies.policy(current), afterDocument)
                            .isEmpty()) throw invalid("有序计算结果不满足关联记录的整单规则");
                } catch (com.lingan.ucp.framework.common.exception.ServiceException error) {
                    throw invalid("有序计算会改变受保护记录或违反整单规则，整笔操作已回滚");
                }
            }
            if (!excludedHistory.contains(id))
                history.finishCalculated(historyContext, app, id, previous, actor, sourceId);
        }
        return changes.size();
    }

    private void policyFields(DocumentPolicy.Expression expression, Set<String> fields) {
        if (expression == null) return;
        if (expression.fieldId() != null) fields.add(expression.fieldId());
        expression.args().forEach(argument -> policyFields(argument, fields));
    }

    private void addGroup(
            DataCenter.Definition d,
            CalculationOptions c,
            Map<String, Object> row,
            Map<String, List<Object>> groups) {
        List<Object> values = new ArrayList<>();
        for (String code : c.groupFields()) values.add(row.get(Calculations.field(d, code).id()));
        try {
            groups.putIfAbsent(json.writeValueAsString(values), values);
        } catch (java.io.IOException error) {
            throw invalid("有序计算分组无法编码");
        }
    }

    public static boolean same(DataCenter.Definition d, String id, Object left, Object right) {
        if (left == null || right == null) return left == right;
        FieldDefinition field =
                d.fields().stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
        String type =
                FieldTypeEnum.fromCode(field.type()).isComputed()
                        ? d.fieldOptions().get(id).resultType()
                        : field.type();
        if (FieldTypeEnum.fromCode(type).isNumeric()) {
            try {
                return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString()))
                        == 0;
            } catch (NumberFormatException error) {
                throw invalid("有序计算输入数值无效");
            }
        }
        return Objects.equals(left, right);
    }
}
