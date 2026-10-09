package com.lingan.ucp.nocode.runtime.service.taskcenter;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.TaskWorkEntries.*;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskWorkRecordDO;
import com.lingan.ucp.nocode.runtime.dal.mapper.TaskWorkEntryMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.*;

/** 从不可变办理事实核算标准工时，不根据客户端计数或当前记录反推历史劳动。 */
@Component
public class TaskStandardWork {
    @Resource private ObjectMapper json;
    @Resource private TaskWorkEntryMapper store;

    public void validate(WorkRule rule) {
        if (rule == null) return;
        TaskWorkBudgets.validate(rule);
        if (rule.minutes() < 0 || rule.minutes() > 599999) throw invalid("标准工时须为0分钟至9999小时59分钟");
        if (rule.effectiveMinutes() < 0 || rule.effectiveMinutes() > 599999)
            throw invalid("调整后的标准工时须为0分钟至9999小时59分钟");
        // 兼容旧编辑器的零时长占位；工时未设置不能成为业务办理的前置条件。
        if (rule.minutes() == 0 && rule.effectiveMinutes() == 0) return;
        if (rule.mode() == null) throw invalid("标准工时请选择计算规则");
        if (rule.mode() == WorkRuleMode.QUANTITY
                && (rule.quantityFieldId() == null || rule.quantityFieldId().isBlank()))
            throw invalid("按业务数量计时需要选择数量字段");
        if (rule.mode() == WorkRuleMode.CONDITION
                && (rule.conditionFieldId() == null
                        || rule.conditionFieldId().isBlank()
                        || rule.conditionValue() == null
                        || rule.conditionValue() instanceof Collection<?>
                        || rule.conditionValue() instanceof Map<?, ?>))
            throw invalid("按条件计时需要选择条件字段和一个有效值");
    }

    public void validate(WorkRule rule, ApplicationRecords.Model model, Config config) {
        validate(rule);
        if (rule == null
                || rule.minutes() == 0 && rule.effectiveMinutes() == 0
                || rule.mode() == WorkRuleMode.RECORD_ONCE) return;
        String id =
                rule.mode() == WorkRuleMode.QUANTITY
                        ? rule.quantityFieldId()
                        : rule.conditionFieldId();
        FieldDefinition field =
                model.object().fields().stream()
                        .filter(f -> f.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> invalid("工时规则引用的字段不存在"));
        if (!model.permissions().readFields().contains(id)
                || config.readableFieldIds() != null && !config.readableFieldIds().contains(id))
            throw invalid("工时规则只能使用当前办理项可读取的字段");
        if (rule.mode() == WorkRuleMode.QUANTITY) {
            if (!Set.of("INTEGER", "DECIMAL").contains(field.type()))
                throw invalid("工时数量字段只支持整数或小数");
        } else {
            new DataScope(
                            "AND",
                            List.of(new DataScope.Condition(id, "eq", rule.conditionValue())),
                            List.of())
                    .validate(model.object(), false);
        }
    }

    /** 节点、办理项、人员、记录组成计量身份；更新数量只覆盖本人旧数量，不重复累计保存次数。 */
    public WorkSummary summarize(
            String taskId,
            Config config,
            List<TaskWorkRecordDO> facts,
            long actor,
            boolean totals) {
        if (config.workRule() == null) return null;
        Map<String, BigDecimal> measured = new LinkedHashMap<>();
        Map<String, List<WorkSegment>> segments = new HashMap<>();
        Set<String> deleted = new HashSet<>();
        List<TaskWorkRecordDO> ordered = new ArrayList<>(facts);
        Collections.reverse(ordered);
        for (TaskWorkRecordDO fact : ordered) {
            if (fact.getSupersededBy() != null) continue;
            TaskCenter.BusinessRef ref = read(fact.getBusinessJson(), TaskCenter.BusinessRef.class);
            String recordId =
                    ref.requestId() == null ? ref.recordId() : store.approvedRecord(fact.getId());
            if (recordId == null) continue;
            Operation operation = Operation.valueOf(fact.getOperation());
            if (operation == Operation.DELETED) {
                deleted.add(recordId);
                continue;
            }
            if (!taskId.equals(fact.getTaskId())
                    || !config.key().equals(fact.getEntryKey())
                    || operation != Operation.CREATED && operation != Operation.UPDATED
                    || fact.getSnapshotJson() == null) continue;
            if (!totals && !Long.toString(actor).equals(fact.getCreator())) continue;
            ApplicationRecords.Aggregate snapshot =
                    read(fact.getSnapshotJson(), ApplicationRecords.Aggregate.class);
            String identity = fact.getCreator() + ":" + recordId;
            // 旧夹具或升级期间遗留事实以原实例规则兼容；显式 JSON null 表示当时不计时。
            WorkRule rule =
                    fact.getWorkRuleJson() == null
                            ? config.workRule()
                            : read(fact.getWorkRuleJson(), WorkRule.class);
            // 停计仅影响之后的办理事实，历史事实仍使用当时已冻结的计时规则。
            if (rule == null
                    || rule.effectiveMinutes() == 0 && rule.mode() != WorkRuleMode.QUANTITY)
                continue;
            BigDecimal units = quantity(rule, snapshot.record().values());
            if (rule.mode() == WorkRuleMode.QUANTITY) {
                List<WorkSegment> stack =
                        segments.computeIfAbsent(identity, ignored -> new ArrayList<>());
                BigDecimal previous =
                        stack.stream()
                                .map(WorkSegment::quantity)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal delta = units.subtract(previous);
                // 停计期间新增数量仍记录为零单价段，恢复后不能把这部分补算为新工时。
                if (delta.signum() > 0)
                    stack.add(new WorkSegment(delta, BigDecimal.valueOf(rule.effectiveMinutes())));
                else if (delta.signum() < 0) {
                    BigDecimal remove = delta.negate();
                    // 数量更正优先冲回最近新增部分，以该部分原标准冲减，不能用新单价倒扣历史。
                    while (remove.signum() > 0 && !stack.isEmpty()) {
                        WorkSegment last = stack.removeLast();
                        BigDecimal cut = last.quantity().min(remove);
                        if (cut.compareTo(last.quantity()) < 0)
                            stack.add(
                                    new WorkSegment(
                                            last.quantity().subtract(cut), last.unitMinutes()));
                        remove = remove.subtract(cut);
                    }
                }
                measured.put(
                        identity,
                        stack.stream()
                                .map(segment -> segment.quantity().multiply(segment.unitMinutes()))
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
            } else if (units.signum() > 0) {
                // 固定计时和首次达标只认第一次有效标准，后续保存或调价不重复计入。
                measured.putIfAbsent(identity, BigDecimal.valueOf(rule.effectiveMinutes()));
            }
        }
        BigDecimal mine = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        int myCount = 0;
        int totalCount = 0;
        for (Map.Entry<String, BigDecimal> measure : measured.entrySet()) {
            int separator = measure.getKey().indexOf(':');
            String owner = measure.getKey().substring(0, separator);
            String record = measure.getKey().substring(separator + 1);
            if (deleted.contains(record) || measure.getValue().signum() <= 0) continue;
            total = total.add(measure.getValue());
            totalCount++;
            if (Long.toString(actor).equals(owner)) {
                mine = mine.add(measure.getValue());
                myCount++;
            }
        }
        return new WorkSummary(mine, myCount, totals ? total : null, totals ? totalCount : null);
    }

    private record WorkSegment(BigDecimal quantity, BigDecimal unitMinutes) {}

    private BigDecimal quantity(WorkRule rule, Map<String, Object> values) {
        if (rule.mode() == WorkRuleMode.RECORD_ONCE) return BigDecimal.ONE;
        if (rule.mode() == WorkRuleMode.QUANTITY) {
            Object value = values.get(rule.quantityFieldId());
            if (value == null) return BigDecimal.ZERO;
            try {
                return new BigDecimal(value.toString()).max(BigDecimal.ZERO);
            } catch (NumberFormatException failure) {
                throw invalid("已保存的工时数量不是有效数值");
            }
        }
        Object actual = values.get(rule.conditionFieldId());
        if (actual == null) return BigDecimal.ZERO;
        boolean same = Objects.equals(actual.toString(), rule.conditionValue().toString());
        if (actual instanceof Number || rule.conditionValue() instanceof Number) {
            try {
                same =
                        new BigDecimal(actual.toString())
                                        .compareTo(new BigDecimal(rule.conditionValue().toString()))
                                == 0;
            } catch (NumberFormatException failure) {
                same = false;
            }
        }
        return same ? BigDecimal.ONE : BigDecimal.ZERO;
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception failure) {
            throw invalid("标准工时的办理事实无法读取");
        }
    }
}
