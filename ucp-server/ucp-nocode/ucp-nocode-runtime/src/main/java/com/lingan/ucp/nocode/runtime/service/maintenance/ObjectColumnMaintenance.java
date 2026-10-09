package com.lingan.ucp.nocode.runtime.service.maintenance;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.framework.mybatis.core.metadata.DatabaseMetadata;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.DataCenter.*;
import com.lingan.ucp.nocode.application.service.resource.ApplicationFieldConversionDependencies;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper;
import com.lingan.ucp.nocode.metadata.dal.mapper.ObjectDraftMapper;
import com.lingan.ucp.nocode.metadata.service.form.BusinessHandlingPolicies;
import com.lingan.ucp.nocode.metadata.service.form.DocumentPolicies;
import com.lingan.ucp.nocode.metadata.service.object.ObjectFieldConversionDependencies;
import com.lingan.ucp.nocode.runtime.dal.mapper.ObjectMaintenanceMapper;
import com.lingan.ucp.nocode.runtime.dal.mapper.ObjectMaintenanceMapper.ColumnScope;
import com.lingan.ucp.nocode.runtime.dal.mapper.ObjectMaintenanceMapper.ColumnStats;
import com.lingan.ucp.nocode.runtime.service.record.DocumentStates;
import com.lingan.ucp.nocode.runtime.service.record.RecordAutomations;
import com.lingan.ucp.nocode.runtime.service.record.RecordCalculations;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.record.RuntimeSchema;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 已发布对象的即时单列维护；调用方负责管理授权、目录锁、设计锁及事务。 */
@Component
public class ObjectColumnMaintenance {
    @Resource private DataObjectApi objects;
    @Resource private RuntimeSchema schemas;
    @Resource private ObjectMaintenanceMapper mapper;
    @Resource private ObjectDraftMapper audits;
    @Resource private ObjectMapper json;
    @Resource private RecordService records;
    @Resource private RecordAutomations automations;

    @Resource private com.lingan.ucp.nocode.runtime.service.record.RecordLinkageSync linkageSync;

    @Resource private RecordCalculations calculations;
    @Resource private ObjectFieldConversionDependencies objectDependencies;
    @Resource private ApplicationFieldConversionDependencies applicationDependencies;
    @Resource private DataCenterMapper metadata;
    @Resource private com.lingan.ucp.nocode.runtime.service.live.RecordChangeCollector changes;

    private record Target(
            Definition definition,
            Detail detail,
            FieldDefinition field,
            FieldOptions option,
            RuntimeSchema.Table table,
            DatabaseMetadata.Column physical,
            ColumnScope scope) {}

    /** 预检始终读取发布结构及实际列，草稿属性不能放宽清空条件。 */
    public ObjectDataMaintenance.ClearColumnPreview preview(
            ObjectDataMaintenance.ClearColumn request, long actor) {
        Target target = locate(request, actor);
        return inspect(request, target, actor);
    }

    /** 表锁后重跑版本、指纹和依赖检查；同一事务中仅修改目标列及公共更新审计列。 */
    public ObjectDataMaintenance.ClearColumnResult clear(
            ObjectDataMaintenance.ClearColumn request, long actor) {
        if (request.impactToken() == null || request.impactToken().isBlank())
            throw invalid("请先检查清空影响并确认");
        automations.lock(null);
        linkageSync.lock(null);
        Target target = locate(request, actor);
        if (target.scope() == null || target.physical() == null) throw invalid("字段没有可维护的真实物理列");
        if (target.detail() != null) {
            RuntimeSchema.Table main = schemas.main(target.definition());
            mapper.lockColumnTable(
                    new ColumnScope(
                            main.schema(),
                            main.name(),
                            main.key().name(),
                            null,
                            main.key().name(),
                            target.definition().objectId(),
                            Long.toString(actor)));
        }
        mapper.lockColumnTable(target.scope());
        DataObjectApi.PublishedObject current = objects.getVersion(request.objectId(), null);
        if (current.versionNo() != request.versionNo()
                || !Objects.equals(current.checksum(), request.checksum()))
            throw conflict("对象已发布新版本，请重新检查清空影响");
        ObjectDataMaintenance.ClearColumnPreview preview = inspect(request, target, actor);
        if (!preview.allowed()) throw invalid(preview.message());
        if (!Objects.equals(request.impactToken(), preview.impactToken()))
            throw conflict("列数据或依赖已变化，请重新检查并确认清空影响");
        int expected = Math.toIntExact(preview.activeRows() + preview.deletedRows());
        int changed = mapper.clearColumn(target.scope());
        if (changed != expected) throw conflict("列数据已变化，请重新检查并确认清空影响");
        changes.bulk(request.objectId());
        audit(target, preview, actor);
        return new ObjectDataMaintenance.ClearColumnResult(
                preview.activeRows(), preview.deletedRows());
    }

    private Target locate(ObjectDataMaintenance.ClearColumn request, long actor) {
        if (request == null
                || request.fieldId() == null
                || !request.fieldId().matches("[1-9][0-9]*")) throw invalid("字段标识无效");
        DataObjectApi.PublishedObject published = objects.getVersion(request.objectId(), null);
        if (published.versionNo() != request.versionNo()
                || !Objects.equals(published.checksum(), request.checksum()))
            throw conflict("对象已发布新版本，请刷新对象数据后重新检查");
        Definition definition = published.definition();
        Detail detail = null;
        if (request.detailId() != null && !request.detailId().isBlank()) {
            detail =
                    definition.details().stream()
                            .filter(value -> Objects.equals(value.id(), request.detailId()))
                            .filter(value -> MemberStateEnum.ACTIVE.matches(value.state()))
                            .findFirst()
                            .orElseThrow(() -> invalid("内部明细不存在或已停用"));
        }
        RuntimeSchema.Table table =
                detail == null ? schemas.main(definition) : schemas.detail(definition, detail);
        FieldDefinition field =
                table.fields().stream()
                        .filter(value -> Objects.equals(value.id(), request.fieldId()))
                        .findFirst()
                        .orElseThrow(() -> invalid("字段不存在或已停用"));
        String column = table.columns().get(field.id());
        FieldOptions option = table.options().getOrDefault(field.id(), FieldOptions.defaults());
        DatabaseMetadata.Column physical =
                column == null
                        ? null
                        : table.physical().columns().stream()
                                .filter(value -> Objects.equals(value.name(), column))
                                .findFirst()
                                .orElse(null);
        ColumnScope scope =
                column == null
                        ? null
                        : new ColumnScope(
                                table.schema(),
                                table.name(),
                                table.key().name(),
                                table.binding().parentColumn(),
                                column,
                                definition.objectId(),
                                Long.toString(actor));
        return new Target(definition, detail, field, option, table, physical, scope);
    }

    private ObjectDataMaintenance.ClearColumnPreview inspect(
            ObjectDataMaintenance.ClearColumn request, Target target, long actor) {
        List<String> blockers = new ArrayList<>();
        structuralBlockers(target, blockers);
        ColumnStats stats =
                target.scope() == null || target.physical() == null
                        ? new ColumnStats(0, 0, "")
                        : mapper.columnStats(target.scope());
        if (stats.activeRows() + stats.deletedRows() > 0) {
            dependencyBlockers(target, blockers);
            if (target.scope() != null && mapper.hasProtectedRows(target.scope()))
                blockers.add("受影响的有效记录存在运行中流程、待办理申请或流程任务材料，请先完成相关业务");
            if (target.scope() != null
                    && !blockers.stream().anyMatch(value -> value.startsWith("整单规则")))
                documentBlockers(target, actor, blockers);
        }
        List<String> unique = blockers.stream().distinct().toList();
        String token = unique.isEmpty() ? token(request, target, stats) : null;
        String message =
                unique.isEmpty()
                        ? stats.activeRows() + stats.deletedRows() == 0
                                ? "该列当前没有非空值，无需清空"
                                : "确认后只清空该列在有效及逻辑删除记录中的值；记录、其他业务列和历史日志保留"
                        : String.join("；", unique);
        return new ObjectDataMaintenance.ClearColumnPreview(
                unique.isEmpty(),
                target.definition().objectName(),
                target.field().name(),
                target.detail() == null ? null : target.detail().name(),
                target.physical() == null ? "物理列未找到" : target.physical().nativeType(),
                request.versionNo(),
                request.checksum(),
                stats.activeRows(),
                stats.deletedRows(),
                unique,
                message,
                token);
    }

    private void structuralBlockers(Target target, List<String> blockers) {
        FieldDefinition field = target.field();
        RuntimeSchema.Table table = target.table();
        if (!ObjectSourceEnum.GENERATED.matches(target.definition().source())
                || !table.binding().managed()
                || !table.writable()) blockers.add("当前表为只读、纳管或物理写入能力不受平台管理，不能清空此列");
        // 接入业务文件的字段值即网盘归属，批量清空会造成绑定与网盘目录失配，须先取消接入
        BusinessFilePolicy policy =
                target.definition().settings() == null
                        ? null
                        : target.definition().settings().businessFilePolicy();
        if (DataCenter.BusinessFilePolicy.enabled(policy) && policy.fieldIds().contains(field.id()))
            blockers.add("字段已接入业务文件，请先在对象设置中取消接入后再清空此列");
        if (target.scope() == null || target.physical() == null) {
            blockers.add("字段没有可维护的真实物理列");
            return;
        }
        if (Objects.equals(field.id(), target.definition().titleFieldId())
                && target.detail() == null) blockers.add("记录标题字段不能清空");
        if (Objects.equals(target.scope().column(), table.key().name())
                || target.physical().primaryKeyPosition() != null
                        && target.physical().primaryKeyPosition() > 0) blockers.add("主键字段不能清空");
        if (Objects.equals(target.scope().column(), table.binding().parentColumn()))
            blockers.add("内部明细归属字段不能清空");
        if (Set.of("deleted", "creator", "create_time", "updater", "update_time")
                .contains(target.scope().column())) blockers.add("系统审计字段不能清空");
        if (Boolean.TRUE.equals(field.required())
                || !Boolean.TRUE.equals(target.physical().nullable()))
            blockers.add("字段为必填或数据库不允许 NULL，请先调整并发布结构");
        boolean relationColumn =
                target.definition().relations().stream()
                        .anyMatch(
                                relation ->
                                        Objects.equals(relation.fieldId(), field.id())
                                                && Objects.equals(
                                                        relation.sourceDetailId(),
                                                        target.detail() == null
                                                                ? null
                                                                : target.detail().id()));
        if (FieldTypeEnum.fromCode(field.type()).isComputed()
                || FieldTypeEnum.AUTO_NUMBER.matches(field.type())
                || target.option().autoNumber() != null
                || Boolean.TRUE.equals(target.option().generated()) && !relationColumn
                || target.physical().identityKind() != null
                        && !target.physical().identityKind().isBlank()
                || target.physical().generatedKind() != null
                        && !target.physical().generatedKind().isBlank())
            blockers.add("计算、汇总、自动编号或数据库生成字段不能直接清空");
        if (automations.managedFields(target.definition().objectId()).contains(field.id()))
            blockers.add("该字段由已发布自动更新或留存规则维护，请先在应用中心调整规则");
        if (target.definition().relations().stream()
                .anyMatch(
                        relation ->
                                Objects.equals(relation.fieldId(), field.id())
                                        && Objects.equals(
                                                relation.sourceDetailId(),
                                                target.detail() == null
                                                        ? null
                                                        : target.detail().id())
                                        && Boolean.TRUE.equals(relation.required())))
            blockers.add("该字段是必选对象关系的引用列，请先调整并发布关系");
        if (table.physical().columns().stream()
                .anyMatch(
                        column ->
                                !Objects.equals(column.name(), target.scope().column())
                                        && column.generatedKind() != null
                                        && !column.generatedKind().isBlank()))
            blockers.add("同表存在数据库生成列，更新本列可能同时改变其他业务列");
        if (table.physical().triggers().stream()
                .anyMatch(trigger -> !"D".equals(trigger.enabled())))
            blockers.add("表上存在启用的数据库触发器，无法保证只改变目标列");
    }

    private void dependencyBlockers(Target target, List<String> blockers) {
        String fieldId = target.field().id();
        Definition definition = target.definition();
        for (FieldConversionDependencyInspector.Impact impact :
                objectDependencies.inspect(
                        definition, definition, Set.of(fieldId), Set.of(fieldId)))
            if (impact.blocking())
                blockers.add(
                        impact.sourceName() + " / " + impact.location() + "：" + impact.message());
        for (FieldConversionDependencyInspector.Impact impact :
                applicationDependencies.inspectRaw(
                        definition, definition, Set.of(fieldId), Set.of(fieldId)))
            if (impact.location()
                    .startsWith(ApplicationResourceKindEnum.AUTOMATION.getCode() + " / "))
                blockers.add(
                        impact.sourceName()
                                + " / "
                                + impact.location()
                                + "：自动更新依赖该列，直接清空不会触发重算；请先调整或停用规则");
        for (ObjectDraftHeadDO head : metadata.allHeads()) {
            if (head.getCurrentPublishedVersionNo() == null
                    || !ObjectStatusEnum.ACTIVE.matches(head.getStatus())) continue;
            Definition owner = objects.getVersion(head.getId().toString(), null).definition();
            for (Relation relation : owner.relations())
                if (Objects.equals(relation.targetObjectId(), definition.objectId())
                        && Objects.equals(relation.targetFieldId(), fieldId))
                    blockers.add(
                            owner.objectName()
                                    + " / 对象关系 / "
                                    + relation.name()
                                    + "：目标键仍被关联，请先解除关系");
        }
    }

    private void documentBlockers(Target target, long actor, List<String> blockers) {
        DocumentPolicy policy = DocumentPolicies.policy(target.definition());
        if (policy == null) return;
        String fieldId = target.field().id();
        if (policy.lifecycle() != null && Objects.equals(policy.lifecycle().fieldId(), fieldId)) {
            blockers.add("状态字段由业务动作维护，不能直接清空");
            return;
        }
        for (String id : mapper.affectedMainIds(target.scope())) {
            if (id == null) continue;
            ApplicationRecords.Aggregate aggregate;
            try {
                aggregate = records.get(null, target.definition().objectId(), id, actor);
            } catch (com.lingan.ucp.framework.common.exception.ServiceException exception) {
                if (exception.getCode() == NOT_FOUND) continue;
                throw exception;
            }
            DocumentPolicies.Input before = document(aggregate, null, null, null);
            DocumentPolicies.Input after =
                    document(
                            aggregate,
                            target.detail() == null ? fieldId : null,
                            target.detail() == null ? null : target.detail().id(),
                            target.detail() == null ? null : fieldId);
            String unsupportedRule = unsupportedComputedRule(target, policy);
            if (unsupportedRule != null) {
                blockers.add(unsupportedRule);
                break;
            }
            if (target.detail() == null) {
                after =
                        new DocumentPolicies.Input(
                                calculations.preview(
                                        null,
                                        target.definition(),
                                        id,
                                        after.values(),
                                        aggregate.relations(),
                                        actor),
                                after.details());
            }
            if (BusinessHandlingPolicies.required(target.definition(), false, after.values())) {
                blockers.add("业务办理 / 记录 #" + id + "：清空后的记录仍需审批办理，请从业务入口发起，不能直接清空");
                break;
            }
            try {
                DocumentStates.requireWrite(target.definition(), before, after);
            } catch (com.lingan.ucp.framework.common.exception.ServiceException exception) {
                blockers.add("整单规则 / 记录 #" + id + "：" + exception.getMessage());
                break;
            }
            Set<DocumentPolicy.Problem> existing =
                    new HashSet<>(DocumentPolicies.evaluate(policy, before));
            List<DocumentPolicy.Problem> introduced =
                    DocumentPolicies.evaluate(policy, after).stream()
                            .filter(problem -> !existing.contains(problem))
                            .toList();
            if (!introduced.isEmpty()) {
                blockers.add("整单规则 / 记录 #" + id + "：" + introduced.getFirst().message());
                break;
            }
        }
    }

    /** 单条候选试算无法代表整列计算；明确保留此组合限制，不伪造依赖破坏结论。 */
    private String unsupportedComputedRule(Target target, DocumentPolicy policy) {
        Map<String, String> computed = new LinkedHashMap<>();
        for (FieldDefinition field : target.definition().fields()) {
            FieldOptions option =
                    target.definition()
                            .fieldOptions()
                            .getOrDefault(field.id(), FieldOptions.defaults());
            boolean crossRecord =
                    FieldTypeEnum.FORMULA.matches(field.type())
                            && option.calculation() != null
                            && (!CalculationModeEnum.LOCAL.matches(option.calculation().mode())
                                    || option.calculation().runningTotal() != null
                                    || option.calculation().sequence() != null);
            if (crossRecord
                    || target.detail() != null && FieldTypeEnum.fromCode(field.type()).isComputed())
                computed.put(field.id(), field.name());
        }
        for (DocumentPolicy.Rule rule : policy.rules()) {
            List<String> names =
                    computed.entrySet().stream()
                            .filter(
                                    entry ->
                                            references(rule.when(), Set.of(entry.getKey()))
                                                    || references(
                                                            rule.assertion(),
                                                            Set.of(entry.getKey())))
                            .map(Map.Entry::getValue)
                            .toList();
            if (!names.isEmpty())
                return "整单规则 / "
                        + rule.name()
                        + " / 计算字段 "
                        + String.join("、", names)
                        + "：暂不支持在此计算规则下试算整列清空，请先调整规则";
        }
        BusinessHandling.Rule handling = BusinessHandlingPolicies.rule(target.definition(), false);
        if (handling != null) {
            List<String> names =
                    computed.entrySet().stream()
                            .filter(
                                    entry ->
                                            references(
                                                    handling.condition(), Set.of(entry.getKey())))
                            .map(Map.Entry::getValue)
                            .toList();
            if (!names.isEmpty())
                return "办理策略 / 修改办理 / 计算字段 "
                        + String.join("、", names)
                        + "：暂不支持在此计算条件下试算整列清空，请先调整办理条件";
        }
        return null;
    }

    private boolean references(DocumentPolicy.Expression expression, Set<String> fields) {
        if (expression == null) return false;
        if (fields.contains(expression.fieldId()) && expression.detailId() == null) return true;
        for (DocumentPolicy.Expression child : expression.args())
            if (references(child, fields)) return true;
        return false;
    }

    private DocumentPolicies.Input document(
            ApplicationRecords.Aggregate aggregate,
            String mainFieldId,
            String changedDetailId,
            String changedDetailFieldId) {
        Map<String, Object> values = new LinkedHashMap<>(aggregate.record().values());
        if (mainFieldId != null) values.put(mainFieldId, null);
        Map<String, List<DocumentPolicies.InputRow>> details = new LinkedHashMap<>();
        aggregate
                .details()
                .forEach(
                        (detailId, rows) -> {
                            List<DocumentPolicies.InputRow> input = new ArrayList<>();
                            for (ApplicationRecords.Row row : rows) {
                                Map<String, Object> item = new LinkedHashMap<>(row.values());
                                if (Objects.equals(changedDetailId, detailId))
                                    item.put(changedDetailFieldId, null);
                                input.add(
                                        new DocumentPolicies.InputRow(
                                                row.id(), "row-" + row.id(), item));
                            }
                            details.put(detailId, input);
                        });
        return new DocumentPolicies.Input(values, details);
    }

    private String token(
            ObjectDataMaintenance.ClearColumn request, Target target, ColumnStats stats) {
        String source =
                String.join(
                        "|",
                        request.objectId(),
                        Integer.toString(request.versionNo()),
                        request.checksum(),
                        Objects.toString(request.detailId(), ""),
                        request.fieldId(),
                        target.table().schema(),
                        target.table().name(),
                        target.scope().column(),
                        Long.toString(stats.activeRows()),
                        Long.toString(stats.deletedRows()),
                        stats.fingerprint());
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private void audit(
            Target target, ObjectDataMaintenance.ClearColumnPreview preview, long actor) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("objectId", target.definition().objectId());
        detail.put("versionNo", preview.versionNo());
        detail.put("fieldId", target.field().id());
        detail.put("detailId", target.detail() == null ? null : target.detail().id());
        detail.put("clearedActiveRows", preview.activeRows());
        detail.put("clearedDeletedRows", preview.deletedRows());
        try {
            audits.insertAudit(
                    UUID.randomUUID().toString(),
                    actor,
                    "CLEAR_COLUMN",
                    Long.parseLong(target.definition().objectId()),
                    json.writeValueAsString(detail));
        } catch (JsonProcessingException exception) {
            throw invalid("清空操作审计无法保存，已回滚本次清空");
        }
    }

    private com.lingan.ucp.framework.common.exception.ServiceException conflict(String message) {
        return new com.lingan.ucp.framework.common.exception.ServiceException(CONFLICT, message);
    }
}
