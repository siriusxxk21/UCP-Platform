package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.Row;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.sharing.ObjectSharingService;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.formula.Calculations;
import com.lingan.ucp.nocode.metadata.service.formula.FieldExpressions;
import com.lingan.ucp.nocode.metadata.service.formula.FormulaEvaluator;
import com.lingan.ucp.nocode.runtime.dal.mapper.*;
import com.lingan.ucp.nocode.runtime.dal.query.*;
import com.lingan.ucp.nocode.runtime.dal.support.*;
import com.lingan.ucp.nocode.runtime.dal.support.RuntimeConditionSql;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.access.ScopeConditions;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.math.*;
import java.util.*;

/** 在记录事务中计算。LIVE 不保存演员相关结果，ON_SAVE 保存稳定快照；来源授权按应用检查。 */
@Service
public class RecordCalculations {
    @Resource
    private com.lingan.ucp.nocode.metadata.service.formula.OrderedCalculationStateService
            orderedStates;

    @Resource private RuntimeConditionSql sqlFragments;
    @Resource private ApplicationService applications;
    @Resource private DataObjectApi objects;
    @Resource private ObjectSharingService sharing;

    @Resource private com.lingan.ucp.nocode.application.service.sharing.SystemReadAccess systemRead;

    @Resource private com.lingan.ucp.nocode.application.service.sharing.ImpliedObjects implied;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordMapper records;
    @Resource private RecordRelations relations;
    @Resource private ScopeConditions scopes;
    @Resource private ObjectMapper json;
    @Resource private RecordSummaries summaries;
    @Resource private ApplicationRuntimePolicy policy;

    /** 单组完整求值，原值用于差异写入；不会写业务数据。 */
    public record OrderedValue(String id, Map<String, Object> stored, Object expected) {}

    public record OrderedGroup(List<Object> values, long rows, long nullRows) {}

    /** 分组包括不参与累计筛选的记录，确保退出筛选后能清除残留结果。 */
    public List<OrderedGroup> orderedGroups(DataCenter.Definition d, String fieldId, long actor) {
        FieldDefinition field = orderedField(d, fieldId);
        CalculationOptions c = d.fieldOptions().get(field.id()).calculation();
        RuntimeSchema.Table table = schemas.main(d);
        OrderedGroupStatement statement =
                new OrderedGroupStatement(
                        table.statement(null, null, Long.toString(actor), false),
                        c.groupFields().stream()
                                .map(code -> table.column(Calculations.field(d, code)))
                                .toList(),
                        table.column(field));
        List<OrderedGroup> groups = new ArrayList<>();
        try {
            for (String raw : records.orderedGroups(statement)) {
                // 分组键参与后续等值查询，第一步解析即保留 decimal 精度。
                com.fasterxml.jackson.databind.JsonNode value =
                        json.reader()
                                .with(
                                        com.fasterxml.jackson.databind.DeserializationFeature
                                                .USE_BIG_DECIMAL_FOR_FLOATS)
                                .readTree(raw);
                List<Object> keys =
                        json.readerFor(
                                        new com.fasterxml.jackson.core.type.TypeReference<
                                                List<Object>>() {})
                                .with(
                                        com.fasterxml.jackson.databind.DeserializationFeature
                                                .USE_BIG_DECIMAL_FOR_FLOATS)
                                .readValue(value.path("values"));
                groups.add(
                        new OrderedGroup(
                                keys,
                                value.path("rows").asLong(),
                                value.path("nullRows").asLong()));
            }
        } catch (java.io.IOException error) {
            throw invalid("有序计算分组无法读取");
        }
        return groups;
    }

    public List<OrderedValue> orderedGroup(
            String app, DataCenter.Definition d, String fieldId, List<Object> group, long actor) {
        FieldDefinition field = orderedField(d, fieldId);
        CalculationOptions c = d.fieldOptions().get(fieldId).calculation();
        if (group.size() != c.groupFields().size()) throw invalid("有序计算分组与规则不一致");
        RuntimeSchema.Table table = schemas.main(d);
        QueryWrapper<Object> where = new QueryWrapper<>();
        where.setParamAlias("dynamicQuery");
        for (int index = 0; index < group.size(); index++) {
            String column = table.column(Calculations.field(d, c.groupFields().get(index)));
            String reference = sqlFragments.column("t", column, false);
            Object operand = group.get(index);
            if (operand == null) where.isNull(reference);
            else
                where.apply(
                        sqlFragments.scopeScalar(
                                reference, table.schema(), table.name(), column, "="),
                        write(Map.of(column, operand)));
        }
        RecordStatement statement =
                table.statement(null, null, Long.toString(actor), false).conditions(where);
        long count = records.count(statement);
        int maximum = Calculations.sequence(c) ? 10000 : 100000;
        if (count > maximum) throw invalid("有序落库单组超过 " + maximum + " 行写入预算；未写入部分结果");
        if (count == 0) return List.of();
        Evaluation run = new Evaluation(app, actor, null);
        run.orderedField = fieldId;
        run.definitions.put(d.objectId(), d);
        Calculations.validate(d, run::definition);
        List<Stored> rows = new ArrayList<>();
        for (String raw : records.rows(statement.page(maximum + 1, 0))) {
            Stored row = run.decode(raw);
            rows.add(row);
            run.loaded.put(d.objectId() + ":" + row.id(), row);
        }
        // 首行触发完整分组窗口/表达式求值；随后只读同一轮缓存，不按结果行重复扫描。
        run.value(d, rows.getFirst(), field);
        List<OrderedValue> result = new ArrayList<>();
        for (Stored row : rows)
            result.add(
                    new OrderedValue(
                            row.id(),
                            row.values(),
                            coerce(
                                    run.results.get(d.objectId() + ":" + row.id() + ":" + fieldId),
                                    d.fieldOptions().get(fieldId).resultType())));
        return result;
    }

    private FieldDefinition orderedField(DataCenter.Definition d, String id) {
        FieldDefinition field =
                d.fields().stream()
                        .filter(item -> item.id().equals(id))
                        .findFirst()
                        .orElseThrow(() -> invalid("有序计算字段不存在"));
        if (!Calculations.orderedStored(d.fieldOptions().get(id))) throw invalid("只能校准有序落库字段");
        return field;
    }

    private record Stored(
            String id, String revision, Map<String, Object> values, String recordCreator) {}

    /** 调用方在既有业务动作事务中校验记录及字段读取权限；本方法仍检查跨记录计算取数授权。 */
    public Map<String, Object> freshValues(
            String app, DataCenter.Definition d, String id, Set<String> fields, long actor) {
        orderedStates.requireReady(d, fields);
        Evaluation run = new Evaluation(app, actor, d.objectId() + ":" + id);
        run.definitions.put(d.objectId(), d);
        Calculations.validate(d, run::definition);
        Stored row = run.record(d, id);
        Map<String, Object> values = new LinkedHashMap<>();
        for (String fieldId : fields) {
            FieldDefinition field =
                    d.fields().stream()
                            .filter(item -> item.id().equals(fieldId))
                            .findFirst()
                            .orElseThrow(() -> invalid("留存来源公式字段不存在"));
            Calculations.field(d, field.code());
            if (!FieldTypeEnum.FORMULA.matches(field.type())) throw invalid("留存来源须为公式字段");
            values.put(fieldId, run.value(d, row, field));
        }
        return values;
    }

    public List<Row> enrich(String app, DataCenter.Definition d, List<Row> rows, long actor) {
        return enrich(app, d, rows, actor, Map.of());
    }

    /** 设计预览按当前草稿固定的对象版本求实时计算，不回退到已发布应用引用。 */
    public List<Row> enrich(
            String app,
            DataCenter.Definition d,
            List<Row> rows,
            long actor,
            Map<String, DataCenter.Definition> previewDefinitions) {
        Set<String> used = new HashSet<>();
        rows.forEach(row -> used.addAll(row.values().keySet()));
        orderedStates.requireReady(d, used);
        if (d.fieldOptions().values().stream().noneMatch(Calculations::live)) return rows;
        var run = new Evaluation(app, actor, null);
        if (previewDefinitions != null) run.definitions.putAll(previewDefinitions);
        run.definitions.put(d.objectId(), d);
        Calculations.validate(d, run::definition);
        var output = new ArrayList<Row>();
        for (var row : rows) {
            // 读取入口已经批量完成明细汇总；沿用该次授权结果，避免每个本行公式重复聚合明细。
            run.rememberSummaries(d, row);
            var visible = new LinkedHashMap<>(row.values());
            Stored full = null;
            for (var f : d.fields())
                if (visible.containsKey(f.id())
                        && Calculations.live(d.fieldOptions().get(f.id()))) {
                    if (full == null) full = run.record(d, row.id());
                    visible.put(f.id(), run.value(d, full, f));
                }
            output.add(
                    new Row(
                            row.id(),
                            row.revision(),
                            visible,
                            row.permissions(),
                            row.displayValues()));
        }
        return output;
    }

    /** 在同一规则求值中用候选主值及本次关系替代旧库值，保留已有计算取数授权。 */
    public Map<String, Object> preview(
            String app,
            DataCenter.Definition d,
            String id,
            Map<String, Object> candidate,
            Map<String, List<String>> pendingRelations,
            long actor) {
        return candidateValues(app, d, id, candidate, pendingRelations, actor, null);
    }

    /** 仅供同事务有序写入后的整单核验：当前对象有序值已写入，其他公式仍完整求值。 */
    Map<String, Object> finalDocumentValues(
            String app,
            DataCenter.Definition d,
            String id,
            Map<String, Object> candidate,
            Set<String> completedFields,
            long actor) {
        return candidateValues(app, d, id, candidate, null, actor, completedFields);
    }

    private Map<String, Object> candidateValues(
            String app,
            DataCenter.Definition d,
            String id,
            Map<String, Object> candidate,
            Map<String, List<String>> pendingRelations,
            long actor,
            Set<String> completedFields) {
        String candidateId = id == null ? "new-document" : id;
        Evaluation run = new Evaluation(app, actor, d.objectId() + ":" + candidateId);
        run.preview = true;
        run.finalOrderedObject = completedFields != null ? d.objectId() : null;
        run.completedOrderedFields = completedFields == null ? Set.of() : completedFields;
        run.pendingRelations = pendingRelations == null ? Map.of() : pendingRelations;
        run.definitions.put(d.objectId(), d);
        Calculations.validate(d, run::definition);
        String creator = id == null ? Long.toString(actor) : run.record(d, id).recordCreator();
        Stored full = new Stored(candidateId, null, candidate, creator);
        run.loaded.put(d.objectId() + ":" + candidateId, full);
        var result = new LinkedHashMap<>(candidate);
        for (FieldDefinition field : d.fields()) {
            CalculationOptions calculation =
                    d.fieldOptions()
                            .getOrDefault(field.id(), DataCenter.FieldOptions.defaults())
                            .calculation();
            // 派生行的普通 ON_SAVE 仍是原快照，不在最终核验中改变既有语义。
            if (completedFields != null
                    && calculation != null
                    && CalculationUpdateEnum.ON_SAVE.matches(calculation.updateMode())) continue;
            if (FieldTypeEnum.FORMULA.matches(field.type()))
                result.put(field.id(), run.value(d, full, field));
        }
        return result;
    }

    /** 主表、明细和关系写入完成后执行；同一事务内任何计算错误使整笔保存回滚。 */
    public void save(String app, DataCenter.Definition d, String id, long actor) {
        if (d.fieldOptions().values().stream().noneMatch(o -> o.calculation() != null)) return;
        var run = new Evaluation(app, actor, d.objectId() + ":" + id);
        run.definitions.put(d.objectId(), d);
        Calculations.validate(d, run::definition);
        var full = run.record(d, id);
        var table = schemas.main(d);
        Map<String, Object> payload = new LinkedHashMap<>();
        for (var f : d.fields()) {
            var o = d.fieldOptions().getOrDefault(f.id(), DataCenter.FieldOptions.defaults());
            if (o.calculation() == null) continue;
            if (Calculations.orderedStored(o)) continue;
            Object result = run.value(d, full, f);
            if (CalculationUpdateEnum.ON_SAVE.matches(o.calculation().updateMode()))
                payload.put(table.column(f), result);
        }
        if (!payload.isEmpty()) {
            schemas.requireWriteCompatible(table, payload.keySet());
            var b = table.statement(id, null, Long.toString(actor), false);
            var sql =
                    new RecordStatement(
                            b.schema(),
                            b.table(),
                            b.keyColumn(),
                            b.fields(),
                            b.textFields(),
                            b.numericFields(),
                            b.deletedColumn(),
                            id,
                            null,
                            null,
                            null,
                            null,
                            List.of(),
                            "{}",
                            null,
                            false,
                            1,
                            0,
                            new ArrayList<>(payload.keySet()),
                            write(payload),
                            b.actor(),
                            false);
            if (records.update(sql) != 1) throw invalid("计算结果保存失败");
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (java.io.IOException e) {
            throw invalid("计算条件格式无效");
        }
    }

    private final class Evaluation {
        private final String app;
        private final long actor;
        private final String saving;
        private final Map<String, DataCenter.Definition> definitions = new HashMap<>();
        private final Map<String, Stored> loaded = new HashMap<>();
        private final Map<String, Object> results = new HashMap<>();
        private final Set<String> visiting = new HashSet<>();
        private final Set<String> sequenceGroups = new HashSet<>();
        private int evaluated;
        private boolean preview;
        private String finalOrderedObject;
        private Set<String> completedOrderedFields = Set.of();
        private String orderedField;
        private Map<String, List<String>> pendingRelations = Map.of();

        Evaluation(String app, long actor, String saving) {
            this.app = app;
            this.actor = actor;
            this.saving = saving;
        }

        DataCenter.Definition definition(String id) {
            return definitions.computeIfAbsent(
                    id,
                    key -> {
                        if (com.lingan.ucp.nocode.runtime.service.maintenance.ObjectMaintenanceScope
                                .permits(app, actor)) return objects.getPublished(key);
                        var ref =
                                applications.published(app).definition().objects().stream()
                                        .filter(v -> v.objectId().equals(key))
                                        .findFirst()
                                        .orElse(null);
                        // 应用没有引用、但因关联而隐式可读的来源对象：没有固定版本，用最新发布版。
                        if (ref == null) {
                            if (!implied.implied(app, key)) throw invalid("计算来源对象未加入应用发布版本");
                            DataCenter.Definition latest = implied.latest(key);
                            if (latest == null) throw invalid("计算来源对象未加入应用发布版本");
                            return latest;
                        }
                        var version = objects.getVersion(key, ref.versionNo());
                        if (!ref.checksum().equals(version.checksum()))
                            throw invalid("计算来源对象版本校验失败");
                        return version.definition();
                    });
        }

        Stored decode(String raw) {
            try {
                var result = json.readValue(raw, Stored.class);
                return result;
            } catch (java.io.IOException e) {
                throw invalid("计算来源记录读取失败");
            }
        }

        Stored record(DataCenter.Definition d, String id) {
            return loaded.computeIfAbsent(
                    d.objectId() + ":" + id,
                    key -> {
                        var raw =
                                records.rows(
                                        schemas.main(d)
                                                .statement(id, null, Long.toString(actor), false));
                        if (raw.size() != 1) throw invalid("计算来源记录已不存在");
                        return decode(raw.getFirst());
                    });
        }

        Object value(DataCenter.Definition d, Stored row, FieldDefinition f) {
            if (Objects.equals(finalOrderedObject, d.objectId())) {
                DataCenter.FieldOptions option = d.fieldOptions().get(f.id());
                if (option != null
                        && option.calculation() != null
                        && CalculationUpdateEnum.ON_SAVE.matches(
                                option.calculation().updateMode())) {
                    if (Calculations.orderedStored(option)
                            && !completedOrderedFields.contains(f.id()))
                        orderedStates.requireReady(d, List.of(f.id()));
                    return row.values().get(f.id());
                }
            }
            if (!Objects.equals(orderedField, f.id())
                    && Calculations.orderedStored(d.fieldOptions().get(f.id())))
                orderedStates.requireReady(d, List.of(f.id()));
            if (FieldTypeEnum.SUMMARY.matches(f.type())) return summary(d, row, f);
            var o = d.fieldOptions().getOrDefault(f.id(), DataCenter.FieldOptions.defaults());
            var c = o.calculation();
            boolean candidateLocal =
                    preview
                            && Objects.equals(saving, d.objectId() + ":" + row.id())
                            && FieldTypeEnum.FORMULA.matches(f.type())
                            && c == null;
            if (c == null && !candidateLocal
                    || c != null
                            && CalculationUpdateEnum.ON_SAVE.matches(c.updateMode())
                            && !Objects.equals(orderedField, f.id())
                            && !Objects.equals(saving, d.objectId() + ":" + row.id()))
                return row.values().get(f.id());
            String key = d.objectId() + ":" + row.id() + ":" + f.id();
            if (results.containsKey(key)) return results.get(key);
            if (!visiting.add(key) || visiting.size() > 16) throw invalid("计算字段存在循环依赖或层级过深");
            if (++evaluated > 10000) throw invalid("本次计算超过 10000 个单元格，请缩小查询范围");
            Object result;
            if (candidateLocal || CalculationModeEnum.LOCAL.matches(c.mode())) {
                var names = new HashMap<String, String>();
                d.fields().forEach(v -> names.put(v.code(), v.code()));
                result =
                        FormulaEvaluator.evaluate(
                                FieldExpressions.parse(o.expression(), names).expression(),
                                code -> typedValue(d, row, Calculations.field(d, code)));
            } else result = lookup(d, row, f, c);
            result = coerce(result, o.resultType());
            visiting.remove(key);
            results.put(key, result);
            return result;
        }

        /** SUMMARY 的物理列不保存汇总结果，只能来自已授权聚合或本次完整单据候选。 */
        Object summary(DataCenter.Definition d, Stored row, FieldDefinition field) {
            String key = d.objectId() + ":" + row.id() + ":" + field.id();
            if (results.containsKey(key)) return results.get(key);
            ApplicationAuthorization.Capabilities capabilities =
                    policy.access(app, d, actor)
                            .require(row.recordCreator(), row.values(), ApplicationActionEnum.READ);
            if (!capabilities.readFields().contains(field.id())
                    || !capabilities.readDetails().contains(summaries.detailId(d, field)))
                throw invalid("公式引用的明细汇总缺少字段或来源明细查看权限：" + field.name());
            if (preview && Objects.equals(saving, d.objectId() + ":" + row.id())) {
                if (!row.values().containsKey(field.id())) throw invalid("明细汇总试算需要完整单据候选值");
                results.put(key, row.values().get(field.id()));
            } else {
                Row enriched =
                        summaries
                                .enrich(
                                        d,
                                        List.of(
                                                new Row(
                                                        row.id(),
                                                        row.revision(),
                                                        row.values(),
                                                        capabilities)))
                                .getFirst();
                rememberSummaries(d, enriched);
            }
            return results.get(key);
        }

        private void rememberSummaries(DataCenter.Definition d, Row row) {
            for (FieldDefinition field : d.fields()) {
                if (FieldTypeEnum.SUMMARY.matches(field.type())
                        && !MemberStateEnum.INACTIVE.matches(
                                d.fieldOptions()
                                        .getOrDefault(
                                                field.id(), DataCenter.FieldOptions.defaults())
                                        .state())
                        && row.values().containsKey(field.id())
                        && row.permissions().readFields().contains(field.id())
                        && row.permissions().readDetails().contains(summaries.detailId(d, field)))
                    results.put(
                            d.objectId() + ":" + row.id() + ":" + field.id(),
                            row.values().get(field.id()));
            }
        }

        /** 数据库投影保留大数为字符串；比较前按元数据恢复数值，文本编码仍按原文比较。 */
        Object typedValue(DataCenter.Definition d, Stored row, FieldDefinition field) {
            Object result = value(d, row, field);
            if (result == null) return null;
            String type =
                    FieldTypeEnum.fromCode(field.type()).isComputed()
                            ? d.fieldOptions().get(field.id()).resultType()
                            : field.type();
            return FieldTypeEnum.fromCode(type).isNumeric()
                    ? FormulaEvaluator.number(result)
                    : result;
        }

        Object lookup(
                DataCenter.Definition d, Stored row, FieldDefinition output, CalculationOptions c) {
            var target = definition(Calculations.target(d, c));
            var requiredFields = Calculations.sourceFields(target, c);
            if (Calculations.sequence(c))
                requiredFields.addAll(Calculations.sequenceSourceFields(d, output, c));
            if (com.lingan.ucp.nocode.runtime.service.maintenance.ObjectMaintenanceScope.permits(
                    app, actor)) {
                // 对象数据维护入口：沿用维护授权本身带的取数范围，口径不变。
                ApplicationAuthorization.ObjectGrant permission =
                        com.lingan.ucp.nocode.runtime.service.maintenance.ObjectMaintenanceScope
                                .grant(target);
                if (!permission.computeFields().containsAll(requiredFields)
                        || permission.computeFields().isEmpty())
                    throw invalid("计算来源未授予应用所需的计算取数权限");
            } else
                // 不再让人勾计算取数：由应用对来源对象的授权现场推出，取不了时说清对象、字段和去哪改。
                systemRead.require(
                        app,
                        target,
                        requiredFields,
                        "「" + d.objectName() + "」的「" + output.name() + "」");
            var table = schemas.main(target);
            var where = new QueryWrapper<Object>();
            where.setParamAlias("dynamicQuery");
            var rules = new ArrayList<DataScope.Condition>();
            boolean nullMatch = false;
            for (var match : c.conditions()) {
                Object operand = match.value();
                if (match.localField() != null && !match.localField().isBlank()) {
                    operand = value(d, row, Calculations.field(d, match.localField()));
                    if (operand == null
                            && !Set.of(ScopeOperatorEnum.IS_NULL, ScopeOperatorEnum.NOT_NULL)
                                    .contains(ScopeOperatorEnum.fromCode(match.operator()))) {
                        if ("AND".equals(c.logic())) {
                            nullMatch = true;
                            break;
                        } else continue;
                    }
                    if (ScopeOperatorEnum.IN.matches(match.operator())
                            && !(operand instanceof List<?>)) operand = List.of(operand);
                }
                rules.add(
                        new DataScope.Condition(
                                Calculations.field(target, match.targetField()).id(),
                                match.operator(),
                                operand));
            }
            if (nullMatch || !c.conditions().isEmpty() && rules.isEmpty())
                where.apply(sqlFragments.alwaysFalse());
            else if (!rules.isEmpty()) {
                var scope = new DataScope(c.logic(), rules, List.of());
                scope.validate(target, false);
                scopes.append(where, scope, target, table, Map.of(), "t");
            }
            if (Boolean.TRUE.equals(c.excludeCurrent()) && target.objectId().equals(d.objectId()))
                where.ne(sqlFragments.column("t", table.key().name(), true), row.id());
            if (CalculationModeEnum.RELATION.matches(c.mode())) {
                var relation = Calculations.relation(d, c);
                if (RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())) {
                    if (preview
                            && Objects.equals(saving, d.objectId() + ":" + row.id())
                            && pendingRelations.containsKey(relation.id())) {
                        var ids = pendingRelations.get(relation.id());
                        if (ids == null || ids.isEmpty()) where.apply(sqlFragments.alwaysFalse());
                        else where.in(sqlFragments.column("t", table.key().name(), true), ids);
                    } else {
                        var link = relations.statement(d, relation, row.id(), null, actor);
                        where.apply(
                                sqlFragments.calculationRelation(
                                        link.schema(), link.table(), table.key().name()),
                                row.id());
                    }
                } else {
                    Object ref = row.values().get(relation.fieldId());
                    if (ref == null) where.apply(sqlFragments.alwaysFalse());
                    else
                        where.eq(
                                sqlFragments.column("t", table.key().name(), true), ref.toString());
                }
            }
            if (Calculations.sequence(c)) return sequence(d, row, output, c, table, where);
            if (Calculations.tableWide(c)) return tableAggregate(d, row, c, table, where);
            var matches =
                    records.rows(
                            table.statement(null, null, Long.toString(actor), false)
                                    .conditions(where));
            if (matches.size() > 500) throw invalid("计算匹配超过 500 条记录，请收紧条件；未返回部分汇总");
            var aggregate = CalculationAggregateEnum.fromCode(c.aggregate());
            if (aggregate == CalculationAggregateEnum.COUNT)
                return BigDecimal.valueOf(matches.size());
            if (aggregate == CalculationAggregateEnum.SINGLE && matches.size() > 1)
                throw invalid("唯一取值匹配到多条记录，请选择汇总方式或收紧条件");
            var source = Calculations.field(target, c.targetField());
            var operands = new ArrayList<Object>();
            for (String raw : matches) {
                var item = decode(raw);
                loaded.put(target.objectId() + ":" + item.id(), item);
                Object v = value(target, item, source);
                if (v != null) operands.add(v);
            }
            if (aggregate == CalculationAggregateEnum.SINGLE)
                return operands.isEmpty() ? null : operands.getFirst();
            if (operands.isEmpty())
                return aggregate == CalculationAggregateEnum.SUM ? BigDecimal.ZERO : null;
            var numbers = operands.stream().map(FormulaEvaluator::number).toList();
            var sum = numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            return switch (aggregate) {
                case SUM -> sum;
                case AVG ->
                        sum.divide(BigDecimal.valueOf(numbers.size()), 16, RoundingMode.HALF_UP);
                case MIN -> numbers.stream().min(BigDecimal::compareTo).orElseThrow();
                case MAX -> numbers.stream().max(BigDecimal::compareTo).orElseThrow();
                default -> throw invalid("汇总方式无效");
            };
        }

        Object sequence(
                DataCenter.Definition d,
                Stored row,
                FieldDefinition output,
                CalculationOptions c,
                RuntimeSchema.Table table,
                QueryWrapper<Object> where) {
            List<Object> groupValues = new ArrayList<>();
            for (String code : c.groupFields()) {
                FieldDefinition field = Calculations.field(d, code);
                Object operand = row.values().get(field.id());
                groupValues.add(operand);
                String name = table.column(field);
                String column = sqlFragments.column("t", name, false);
                if (operand == null) where.isNull(column);
                else
                    where.apply(
                            sqlFragments.scopeScalar(
                                    column, table.schema(), table.name(), name, "="),
                            write(Map.of(name, operand)));
            }
            String groupKey = d.objectId() + ":" + output.id() + ":" + write(groupValues);
            String resultKey = d.objectId() + ":" + row.id() + ":" + output.id();
            if (sequenceGroups.contains(groupKey)) return results.get(resultKey);
            CalculationOptions.Sequence rule = c.sequence();
            LinkedHashSet<String> orderColumns = new LinkedHashSet<>();
            orderColumns.add(table.column(Calculations.field(d, rule.orderField())));
            if (rule.tieBreakerField() != null)
                orderColumns.add(table.column(Calculations.field(d, rule.tieBreakerField())));
            orderColumns.add(table.key().name());
            String candidateId = null;
            String candidatePayload = null;
            if (preview && saving.startsWith(d.objectId() + ":")) {
                Stored candidate = loaded.get(saving);
                candidateId = candidate.id();
                Map<String, Object> payload = new LinkedHashMap<>();
                for (FieldDefinition field : d.fields()) {
                    if (!FieldTypeEnum.fromCode(field.type()).isComputed())
                        payload.put(table.column(field), candidate.values().get(field.id()));
                }
                payload.put(
                        table.key().name(),
                        "new-document".equals(candidateId) ? null : candidateId);
                if (table.physical().columns().stream()
                        .anyMatch(column -> column.name().equals("deleted")))
                    payload.put(
                            "deleted",
                            table.physical().columns().stream()
                                            .anyMatch(
                                                    column ->
                                                            column.name().equals("deleted")
                                                                    && column.nativeType()
                                                                            .equals("boolean"))
                                    ? false
                                    : 0);
                candidatePayload = write(payload);
            }
            TableCalculationStatement statement =
                    new TableCalculationStatement(
                            table.statement(null, null, Long.toString(actor), false)
                                    .conditions(where),
                            null,
                            null,
                            null,
                            null,
                            BigDecimal.ZERO,
                            List.copyOf(orderColumns),
                            row.id(),
                            candidateId,
                            candidatePayload,
                            "NEXT".equals(rule.direction()));
            List<String> rawRows = records.sequenceRows(statement);
            if (rawRows.size() > 10000) throw invalid("顺序计算单组超过 10000 条记录，请调整分组；未返回部分结果");
            List<Stored> ordered = new ArrayList<>();
            for (String raw : rawRows) {
                Stored item = decode(raw);
                if (item.id() == null)
                    item =
                            new Stored(
                                    candidateId,
                                    item.revision(),
                                    item.values(),
                                    Long.toString(actor));
                if (preview && Objects.equals(saving, d.objectId() + ":" + item.id()))
                    item = loaded.get(saving);
                else loaded.put(d.objectId() + ":" + item.id(), item);
                ordered.add(item);
            }
            FieldExpressions.Parsed parsed =
                    FieldExpressions.parse(
                            d.fieldOptions().get(output.id()).expression(),
                            Calculations.sequenceNames(d, output));
            Map<String, FieldDefinition> fields = new HashMap<>();
            d.fields().forEach(field -> fields.put(field.id(), field));
            BigDecimal total = Calculations.sequenceInitial(rule);
            for (int index = 0; index < ordered.size(); index++) {
                Stored current = ordered.get(index);
                int adjacentIndex = index + ("NEXT".equals(rule.direction()) ? 1 : -1);
                Stored adjacent =
                        adjacentIndex < 0 || adjacentIndex >= ordered.size()
                                ? null
                                : ordered.get(adjacentIndex);
                Object contribution =
                        FormulaEvaluator.evaluate(
                                parsed.expression(),
                                id -> {
                                    if (id.startsWith("previous:"))
                                        return adjacent == null
                                                ? null
                                                : typedValue(
                                                        d,
                                                        adjacent,
                                                        fields.get(
                                                                id.substring(
                                                                        "previous:".length())));
                                    return typedValue(d, current, fields.get(id));
                                });
                Object result = contribution;
                if (Calculations.cumulative(c)) {
                    if (contribution != null)
                        total = total.add(FormulaEvaluator.number(contribution));
                    result = total;
                }
                if (!current.id().equals(row.id()) && ++evaluated > 10000)
                    throw invalid("本次计算超过 10000 个单元格，请缩小查询范围");
                results.put(
                        d.objectId() + ":" + current.id() + ":" + output.id(),
                        coerce(result, d.fieldOptions().get(output.id()).resultType()));
            }
            sequenceGroups.add(groupKey);
            return results.get(resultKey);
        }

        Object tableAggregate(
                DataCenter.Definition d,
                Stored row,
                CalculationOptions c,
                RuntimeSchema.Table table,
                QueryWrapper<Object> where) {
            var groupValues = new ArrayList<Object>();
            for (String code : c.groupFields()) {
                var field = Calculations.field(d, code);
                Object operand = row.values().get(field.id());
                groupValues.add(operand);
                String name = table.column(field);
                String column = sqlFragments.column("t", name, false);
                if (operand == null) where.isNull(column);
                // 分组使用完整记录值，不套用面向查询输入的空白和长度限制。
                else
                    where.apply(
                            sqlFragments.scopeScalar(
                                    column, table.schema(), table.name(), name, "="),
                            write(Map.of(name, operand)));
            }
            var running = c.runningTotal();
            String cacheKey = null;
            if (running == null) {
                var operands =
                        c.conditions().stream()
                                .map(
                                        m ->
                                                m.localField() == null || m.localField().isBlank()
                                                        ? m.value()
                                                        : row.values()
                                                                .get(
                                                                        Calculations.field(
                                                                                        d,
                                                                                        m
                                                                                                .localField())
                                                                                .id()))
                                .toList();
                cacheKey =
                        "statistics:"
                                + d.objectId()
                                + ":"
                                + write(c)
                                + ":"
                                + write(groupValues)
                                + ":"
                                + write(operands)
                                + ":"
                                + (Boolean.TRUE.equals(c.excludeCurrent()) ? row.id() : "");
                if (results.containsKey(cacheKey)) return results.get(cacheKey);
            }
            String candidatePayload = null;
            String candidateId = null;
            if (preview && saving.startsWith(d.objectId() + ":")) {
                var candidate = loaded.get(saving);
                candidateId = candidate.id();
                var payload = new LinkedHashMap<String, Object>();
                for (var field : d.fields())
                    if (!FieldTypeEnum.fromCode(field.type()).isComputed())
                        payload.put(table.column(field), candidate.values().get(field.id()));
                payload.put(
                        table.key().name(),
                        "new-document".equals(candidateId) ? null : candidateId);
                if (table.physical().columns().stream()
                        .anyMatch(column -> column.name().equals("deleted")))
                    payload.put(
                            "deleted",
                            table.physical().columns().stream()
                                            .anyMatch(
                                                    column ->
                                                            column.name().equals("deleted")
                                                                    && column.nativeType()
                                                                            .equals("boolean"))
                                    ? false
                                    : 0);
                candidatePayload = write(payload);
            }
            var orderColumns = new LinkedHashSet<String>();
            if (running != null) {
                orderColumns.add(table.column(Calculations.field(d, running.orderField())));
                if (running.tieBreakerField() != null)
                    orderColumns.add(
                            table.column(Calculations.field(d, running.tieBreakerField())));
                orderColumns.add(table.key().name());
            }
            var statement =
                    new TableCalculationStatement(
                            table.statement(null, null, Long.toString(actor), false)
                                    .conditions(where),
                            CalculationAggregateEnum.fromCode(c.aggregate()),
                            CalculationAggregateEnum.COUNT.matches(c.aggregate())
                                    ? null
                                    : table.column(Calculations.field(d, c.targetField())),
                            running == null || running.subtractField() == null
                                    ? null
                                    : table.column(Calculations.field(d, running.subtractField())),
                            running == null || running.initialField() == null
                                    ? null
                                    : table.column(Calculations.field(d, running.initialField())),
                            running == null || running.initialValue() == null
                                    ? BigDecimal.ZERO
                                    : new BigDecimal(running.initialValue()),
                            List.copyOf(orderColumns),
                            row.id(),
                            candidateId,
                            candidatePayload,
                            false);
            if (running != null && orderedField != null) {
                try {
                    for (String raw : records.runningTotals(statement)) {
                        com.fasterxml.jackson.databind.JsonNode value = json.readTree(raw);
                        results.put(
                                d.objectId() + ":" + value.path("id").asText() + ":" + orderedField,
                                value.path("value").isNull() ? null : value.path("value").asText());
                    }
                } catch (java.io.IOException error) {
                    throw invalid("累计计算结果无法读取");
                }
                return results.get(d.objectId() + ":" + row.id() + ":" + orderedField);
            }
            Object result =
                    running == null
                            ? records.statistics(statement)
                            : records.runningTotal(statement);
            if (cacheKey != null) results.put(cacheKey, result);
            return result;
        }
    }

    private static Object coerce(Object value, String type) {
        if (value == null) return null;
        if (FieldTypeEnum.TEXT.matches(type)) {
            var text = value.toString();
            if (text.length() > 10000) throw invalid("计算文本结果超过 10000 字符");
            return text;
        }
        var number = FormulaEvaluator.number(value);
        if (FieldTypeEnum.INTEGER.matches(type)) {
            try {
                return Long.toString(number.longValueExact());
            } catch (ArithmeticException e) {
                throw invalid("计算结果不是有效整数");
            }
        }
        number = number.setScale(10, RoundingMode.HALF_UP);
        if (number.precision() > 38) throw invalid("计算结果超过小数精度");
        return number.toPlainString();
    }
}
