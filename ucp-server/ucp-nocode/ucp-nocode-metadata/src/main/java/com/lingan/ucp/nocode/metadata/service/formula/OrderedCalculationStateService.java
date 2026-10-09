package com.lingan.ucp.nocode.metadata.service.formula;

import static com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommands.hasRows;
import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.lingan.ucp.framework.mybatis.core.metadata.PostgreSqlCommandMapper;
import com.lingan.ucp.nocode.api.CalculationOptions;
import com.lingan.ucp.nocode.api.DataCenter;
import com.lingan.ucp.nocode.api.FieldDefinition;
import com.lingan.ucp.nocode.api.OrderedCalculations;
import com.lingan.ucp.nocode.api.OrderedCalculations.Readiness;
import com.lingan.ucp.nocode.enums.MemberStateEnum;
import com.lingan.ucp.nocode.metadata.dal.dataobject.OrderedCalculationStateDO;
import com.lingan.ucp.nocode.metadata.dal.mapper.OrderedCalculationStateMapper;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDesignService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/** 有序落库的发布门禁和轻量进度；复用调用方事务及对象锁，不调度后台任务。 */
@Service
public class OrderedCalculationStateService {
    private static final ObjectMapper SIGNATURE_JSON =
            new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    @Resource private OrderedCalculationStateMapper store;
    @Resource private ObjectMapper json;
    @Resource private ObjectDesignService designs;
    @Resource private PostgreSqlCommandMapper commands;

    public List<OrderedCalculations.State> forObject(String objectId) {
        return store.forObject(id(objectId)).stream().map(this::view).toList();
    }

    public OrderedCalculations.State get(String objectId, String fieldId) {
        OrderedCalculationStateDO row = store.find(id(objectId), id(fieldId));
        return row == null ? null : view(row);
    }

    /** 只哈希目标与真正来源口径，添加无关字段或改显示名称不会使进度失效。 */
    public static String signature(DataCenter.Definition definition, String fieldId) {
        try {
            return DigestUtil.sha256Hex(
                    SIGNATURE_JSON.writeValueAsString(rule(definition, fieldId)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw invalid("有序计算规则摘要无法生成");
        }
    }

    /** 旧 LIVE 写新落库和旧落库写回 LIVE 都阻断，调用方必须显式同步固定版本。 */
    public static void requireCompatible(
            DataCenter.Definition requested, DataCenter.Definition current) {
        Set<String> stored = new TreeSet<>();
        requested.fields().stream()
                .filter(f -> Calculations.orderedStored(requested.fieldOptions().get(f.id())))
                .forEach(f -> stored.add(f.id()));
        current.fields().stream()
                .filter(f -> Calculations.orderedStored(current.fieldOptions().get(f.id())))
                .forEach(f -> stored.add(f.id()));
        for (String fieldId : stored) {
            if (!Calculations.orderedStored(requested.fieldOptions().get(fieldId))
                    || !Calculations.orderedStored(current.fieldOptions().get(fieldId))
                    || !signature(requested, fieldId).equals(signature(current, fieldId)))
                throw invalid("有序计算的固定版本与当前对象不一致，请同步应用或任务引用后再写入");
        }
    }

    /** 只对本次实际使用的有序落库字段检查，旧 LIVE 版本仍按原算法读取。 */
    public void requireReady(DataCenter.Definition definition, Collection<String> fieldIds) {
        for (String fieldId : fieldIds) {
            if (!Calculations.orderedStored(definition.fieldOptions().get(fieldId))) continue;
            DataCenter.Definition current = designs.published(definition.objectId());
            if (current == null || !Calculations.orderedStored(current.fieldOptions().get(fieldId)))
                throw invalid("有序落库字段的当前模式已变化，请同步固定版本后查询");
            OrderedCalculations.State state = get(definition.objectId(), fieldId);
            if (state == null
                    || !Readiness.READY.matches(state.state())
                    || !signature(definition, fieldId).equals(state.signature()))
                throw invalid("有序计算尚未完成校准，暂不能用于查询、统计或确认留存");
        }
    }

    /** 维护窗口按当前已发布对象阻写；不能用旧应用的 LIVE 定义绕过。 */
    public void requireWritable(DataCenter.Definition definition) {
        DataCenter.Definition current = designs.published(definition.objectId());
        if (current == null) throw invalid("数据对象尚未发布");
        requireCompatible(definition, current);
        requireReady(current, current.fields().stream().map(FieldDefinition::id).toList());
    }

    /** 保留曾落库规则，禁止经 LIVE 中转替换；校准开始后不允许同时发布新对象版本。 */
    public void validatePublish(DataCenter.Definition proposed) {
        for (OrderedCalculations.State saved : forObject(proposed.objectId())) {
            DataCenter.FieldOptions options = proposed.fieldOptions().get(saved.fieldId());
            if (options == null
                    || !Calculations.ordered(options.calculation())
                    || !MemberStateEnum.ACTIVE.matches(options.state()))
                throw invalid("已有有序落库字段不能删除、停用或更换计算类型，请保留字段并解除引用后处理");
            if (!signature(proposed, saved.fieldId()).equals(saved.signature()))
                throw invalid("曾落库的有序规则及来源口径不能原地替换，请新增字段");
            if (Readiness.BACKFILLING.matches(saved.state()))
                throw invalid("有序计算正在校准，请先完成或暂停失败批次再发布");
        }
    }

    /** 在物理发布事务完成 DDL 后登记，保证定义与校准门禁原子可见。 */
    public void publish(DataCenter.Definition definition, long actor) {
        transaction();
        validatePublish(definition);
        DataCenter.Definition previous = designs.published(definition.objectId());
        if (previous != null)
            for (FieldDefinition field : previous.fields()) {
                if (!Calculations.orderedStored(previous.fieldOptions().get(field.id()))
                        || Calculations.orderedStored(definition.fieldOptions().get(field.id())))
                    continue;
                OrderedCalculationStateDO row =
                        store.find(id(definition.objectId()), id(field.id()));
                if (row != null) {
                    // LIVE 写入不维护物理结果；原落库版本与游标不能再声称已就绪。
                    row.setState(Readiness.PENDING.getCode());
                    row.setCursorJson("{}");
                    row.setCompletedGroups(0L);
                    row.setUpdatedRows(0L);
                    row.setTotalRows(0L);
                    row.setErrorMessage(null);
                    update(row, actor);
                }
            }
        if (definition.fields().stream()
                .noneMatch(
                        field ->
                                Calculations.orderedStored(
                                        definition.fieldOptions().get(field.id())))) return;
        boolean empty =
                !Boolean.TRUE.equals(
                        commands.check(hasRows(definition.schemaName(), definition.tableName())));
        for (FieldDefinition field : definition.fields()) {
            if (!Calculations.orderedStored(definition.fieldOptions().get(field.id()))) continue;
            OrderedCalculationStateDO row = store.find(id(definition.objectId()), id(field.id()));
            if (row == null) {
                row = new OrderedCalculationStateDO();
                row.setObjectId(id(definition.objectId()));
                row.setFieldId(id(field.id()));
                row.setSignature(signature(definition, field.id()));
                row.setState((empty ? Readiness.READY : Readiness.PENDING).getCode());
                row.setTotalRows(0L);
                row.setCreator(Long.toString(actor));
                row.setUpdater(Long.toString(actor));
                store.insert(row);
            } else if (previous == null
                    || !Calculations.orderedStored(previous.fieldOptions().get(field.id()))) {
                // LIVE 期间来源可能变化，即使规则未变，重新启用也必须重新回填。
                row.setState((empty ? Readiness.READY : Readiness.PENDING).getCode());
                row.setCursorJson("{}");
                row.setCompletedGroups(0L);
                row.setUpdatedRows(0L);
                row.setTotalRows(0L);
                row.setErrorMessage(null);
                update(row, actor);
            }
        }
    }

    public OrderedCalculations.State begin(
            String objectId,
            String fieldId,
            String expectedSignature,
            Map<String, Object> cursor,
            long totalRows,
            long actor) {
        OrderedCalculationStateDO row = checked(objectId, fieldId, expectedSignature);
        if (totalRows < 0) throw invalid("校准总行数无效");
        if (Readiness.BACKFILLING.matches(row.getState())) throw invalid("该字段已有校准批次，请继续原批次");
        row.setState(Readiness.BACKFILLING.getCode());
        row.setCursorJson(write(cursor));
        row.setTotalRows(totalRows);
        row.setUpdatedRows(0L);
        row.setCompletedGroups(0L);
        row.setErrorMessage(null);
        return update(row, actor);
    }

    /** 传入累计进度，每组结果和位置必须处于同一事务；乐观锁拒绝重复并发推进。 */
    public OrderedCalculations.State progress(
            String objectId,
            String fieldId,
            String expectedSignature,
            Map<String, Object> cursor,
            long completedGroups,
            long updatedRows,
            long actor) {
        OrderedCalculationStateDO row = checked(objectId, fieldId, expectedSignature);
        if (!Readiness.BACKFILLING.matches(row.getState())
                && !Readiness.FAILED.matches(row.getState())) throw invalid("该字段尚未开始校准");
        if (completedGroups < row.getCompletedGroups() || updatedRows < row.getUpdatedRows())
            throw invalid("校准进度不能回退");
        row.setState(Readiness.BACKFILLING.getCode());
        Map<String, Object> resumed = new LinkedHashMap<>(cursor);
        resumed.remove("failedGroupIndex");
        resumed.remove("failedGroupValues");
        row.setCursorJson(write(resumed));
        row.setCompletedGroups(completedGroups);
        row.setUpdatedRows(updatedRows);
        row.setErrorMessage(null);
        return update(row, actor);
    }

    public OrderedCalculations.State fail(
            String objectId, String fieldId, String expectedSignature, String error, long actor) {
        return fail(objectId, fieldId, expectedSignature, error, null, actor);
    }

    /** 管理员状态保留失败组定位；普通查询状态由维护服务脱敏。 */
    public OrderedCalculations.State fail(
            String objectId,
            String fieldId,
            String expectedSignature,
            String error,
            Map<String, Object> cursor,
            long actor) {
        OrderedCalculationStateDO row = checked(objectId, fieldId, expectedSignature);
        row.setState(Readiness.FAILED.getCode());
        if (cursor != null) row.setCursorJson(write(cursor));
        row.setErrorMessage(
                error == null ? "校准失败" : error.substring(0, Math.min(error.length(), 2000)));
        return update(row, actor);
    }

    /** 调用方已完成完整组核验后才能结束，状态和最后一批结果在同一事务提交。 */
    public OrderedCalculations.State finish(
            String objectId, String fieldId, String expectedSignature, long actor) {
        OrderedCalculationStateDO row = checked(objectId, fieldId, expectedSignature);
        if (!Readiness.BACKFILLING.matches(row.getState())) throw invalid("校准尚未完成有效批次");
        row.setState(Readiness.READY.getCode());
        row.setErrorMessage(null);
        return update(row, actor);
    }

    private OrderedCalculationStateDO checked(
            String objectId, String fieldId, String expectedSignature) {
        transaction();
        OrderedCalculationStateDO row = store.find(id(objectId), id(fieldId));
        if (row == null || !Objects.equals(row.getSignature(), expectedSignature))
            throw invalid("校准规则已变化，请重新预检");
        DataCenter.Definition current = designs.published(objectId);
        if (current == null
                || !Calculations.orderedStored(current.fieldOptions().get(fieldId))
                || !signature(current, fieldId).equals(expectedSignature))
            throw invalid("有序落库配置已变化，请重新预检");
        return row;
    }

    private OrderedCalculations.State update(OrderedCalculationStateDO row, long actor) {
        row.setUpdater(Long.toString(actor));
        if (store.update(row, row.getLockVersion()) != 1) throw invalid("校准进度已变化，请刷新后重试");
        return get(row.getObjectId().toString(), row.getFieldId().toString());
    }

    private OrderedCalculations.State view(OrderedCalculationStateDO row) {
        try {
            Map<String, Object> cursor =
                    json.readerFor(new TypeReference<Map<String, Object>>() {})
                            .with(
                                    com.fasterxml.jackson.databind.DeserializationFeature
                                            .USE_BIG_DECIMAL_FOR_FLOATS)
                            .readValue(row.getCursorJson());
            return new OrderedCalculations.State(
                    row.getObjectId().toString(),
                    row.getFieldId().toString(),
                    row.getSignature(),
                    row.getState(),
                    cursor,
                    row.getTotalRows(),
                    row.getUpdatedRows(),
                    row.getCompletedGroups(),
                    row.getErrorMessage(),
                    row.getLockVersion());
        } catch (java.io.IOException error) {
            throw invalid("校准进度无法读取");
        }
    }

    private String write(Map<String, Object> cursor) {
        try {
            return json.writeValueAsString(cursor == null ? Map.of() : cursor);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw invalid("校准位置无法保存");
        }
    }

    private static Map<String, Object> rule(DataCenter.Definition definition, String fieldId) {
        FieldDefinition output =
                definition.fields().stream()
                        .filter(f -> f.id().equals(fieldId))
                        .findFirst()
                        .orElseThrow(() -> invalid("有序计算字段已不存在"));
        DataCenter.FieldOptions options = definition.fieldOptions().get(fieldId);
        if (options == null || !Calculations.ordered(options.calculation()))
            throw invalid("字段不是有序计算");
        CalculationOptions calculation = options.calculation();
        Set<String> sources =
                Calculations.sequence(calculation)
                        ? Calculations.sequenceSourceFields(definition, output, calculation)
                        : Calculations.sourceFields(definition, calculation);
        Map<String, Object> fields = new TreeMap<>();
        for (String source : sources) {
            FieldDefinition field =
                    definition.fields().stream()
                            .filter(f -> f.id().equals(source))
                            .findFirst()
                            .orElseThrow(() -> invalid("有序计算来源字段已不存在"));
            fields.put(
                    source,
                    fieldRule(
                            field,
                            definition
                                    .fieldOptions()
                                    .getOrDefault(source, DataCenter.FieldOptions.defaults()),
                            false));
        }
        Map<String, Object> result = new TreeMap<>();
        result.put("schema", definition.schemaName());
        result.put("table", definition.tableName());
        result.put("binding", definition.mainBinding());
        result.put("output", fieldRule(output, options, true));
        result.put("sources", fields);
        return result;
    }

    private static Map<String, Object> fieldRule(
            FieldDefinition field, DataCenter.FieldOptions options, boolean output) {
        Map<String, Object> value = new TreeMap<>();
        value.put("id", field.id());
        value.put("code", field.code());
        value.put("type", field.type());
        value.put("length", field.length());
        value.put("precision", field.precision());
        value.put("scale", field.scale());
        value.put("column", options.columnName() == null ? field.code() : options.columnName());
        value.put("classification", options.classification());
        value.put("state", options.state());
        value.put("expression", options.expression());
        value.put("resultType", options.resultType());
        value.put(
                "calculation",
                output
                        ? Calculations.normalizedOrdered(options.calculation())
                        : options.calculation());
        return value;
    }

    private static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw invalid("对象或字段 ID 无效");
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw invalid("对象或字段 ID 超出范围");
        }
    }

    private static void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw invalid("校准状态更新必须在对象写入事务中执行");
    }
}
