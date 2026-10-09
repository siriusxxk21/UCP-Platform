package com.lingan.ucp.nocode.runtime.service.history;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.enums.FieldTypeEnum;
import com.lingan.ucp.nocode.enums.RecordChangeOperationEnum;
import com.lingan.ucp.nocode.runtime.dal.dataobject.BizFileRetentionDO;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordHistoryMapper;
import com.lingan.ucp.nocode.runtime.dal.mapper.RecordMapper;
import com.lingan.ucp.nocode.runtime.service.bizfile.BizFileRetentionService;
import com.lingan.ucp.nocode.runtime.service.record.DetailPositions;
import com.lingan.ucp.nocode.runtime.service.record.RuntimeSchema;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/** 与实际业务写入同事务；失败保存不留事件，不将重复保存和读取时计算作为修改。 */
@Service
public class RecordHistoryTracker {
    @Resource private RecordHistoryMapper history;
    @Resource private RecordMapper records;
    @Resource private RuntimeSchema schemas;
    @Resource private DataObjectApi objects;
    @Resource private ObjectMapper json;
    @Resource private DetailPositions detailPositions;
    @Resource private com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeScope taskScope;
    @Resource private BizFileRetentionService fileRetentions;

    /** 仅在持有对象锁的本次联动内复用表结构，不跨事务缓存已发布定义。 */
    public record CaptureContext(
            DataCenter.Definition definition,
            RuntimeSchema.Table main,
            Map<String, RuntimeSchema.Table> details,
            String definitionJson) {}

    public CaptureContext context(DataCenter.Definition definition) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw invalid("历史快照上下文必须处于业务事务中");
        Map<String, RuntimeSchema.Table> details = new LinkedHashMap<>();
        for (DataCenter.Detail detail : definition.details()) {
            if (com.lingan.ucp.nocode.enums.MemberStateEnum.ACTIVE.matches(detail.state()))
                details.put(detail.id(), schemas.detail(definition, detail));
        }
        return new CaptureContext(
                definition, schemas.main(definition), Map.copyOf(details), encode(definition));
    }

    public DataCenter.Definition begin(DataCenter.Definition requested, long actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw invalid("历史留痕必须处于业务事务中");
        history.lock(requested.objectId());
        // 同一对象在多应用复用时只建一份历史，按平台已发布结构封存全部实际字段。
        var d = objects.getPublished(requested.objectId());
        if (history.coverage(d.objectId()) == null) {
            history.start(d.objectId(), Long.toString(actor));
            history.baseline(
                    schemas.main(d).statement(null, null, Long.toString(actor), false),
                    d.objectId(),
                    encode(d),
                    Long.toString(actor));
        }
        return d;
    }

    public String capture(DataCenter.Definition d, String id, long actor) {
        return capture(d, id, actor, null);
    }

    public String capture(CaptureContext context, String id, long actor) {
        return capture(context.definition(), id, actor, context);
    }

    private String capture(DataCenter.Definition d, String id, long actor, CaptureContext context) {
        if (id == null) return null;
        RuntimeSchema.Table main = context == null ? schemas.main(d) : context.main();
        List<String> rows =
                context == null
                        ? records.rows(main.statement(id, null, Long.toString(actor), false))
                        : records.orderedRows(
                                main.statement(id, null, Long.toString(actor), false));
        if (rows.isEmpty()) return null;
        try {
            var snapshot =
                    (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(rows.getFirst());
            var details = snapshot.putObject("details");
            var order = snapshot.putObject("detailOrder");
            for (var detail : d.details()) {
                if (!com.lingan.ucp.nocode.enums.MemberStateEnum.ACTIVE.matches(detail.state()))
                    continue;
                var group = details.putObject(detail.id());
                RuntimeSchema.Table detailTable =
                        context == null
                                ? schemas.detail(d, detail)
                                : context.details().get(detail.id());
                var lines =
                        records.rows(detailTable.statement(null, id, Long.toString(actor), false));
                if (lines.size() > 500) throw invalid("明细超过历史快照容量，不能保存部分历史");
                var parsed = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                for (String raw : lines) parsed.add(json.readTree(raw));
                var ordered =
                        detailPositions.order(
                                d.objectId(),
                                detail.id(),
                                id,
                                parsed,
                                line -> line.path("id").asText());
                var ids = order.putArray(detail.id());
                for (var line : ordered) {
                    ids.add(line.path("id").asText());
                    group.set(line.path("id").asText(), line.path("values"));
                }
            }
            return json.writeValueAsString(snapshot);
        } catch (java.io.IOException e) {
            throw invalid("整单历史快照无法读取");
        }
    }

    public void finish(DataCenter.Definition d, String app, String id, String before, long actor) {
        finish(d, app, id, before, actor, UUID.randomUUID().toString(), null);
    }

    public void finish(
            DataCenter.Definition d,
            String app,
            String id,
            String before,
            long actor,
            String operationId,
            String policyVersion) {
        finish(
                d,
                app,
                id,
                before,
                actor,
                operationId,
                policyVersion,
                source(app, d.objectId(), id));
    }

    /** 派生更新与源操作同事务，单独记录来源，避免把联动误认为人工编辑。 */
    public void finishCalculated(
            CaptureContext context,
            String app,
            String id,
            String before,
            long actor,
            String sourceId) {
        DataCenter.Definition d = context.definition();
        Map<String, Object> origin = new LinkedHashMap<>();
        origin.put("kind", "ORDERED_CALCULATION");
        origin.put("objectId", d.objectId());
        if (sourceId != null) origin.put("sourceRecordId", sourceId);
        append(
                d,
                app,
                id,
                before,
                capture(context, id, actor),
                actor,
                UUID.randomUUID().toString(),
                null,
                encode(origin),
                context.definitionJson());
    }

    private void finish(
            DataCenter.Definition d,
            String app,
            String id,
            String before,
            long actor,
            String operationId,
            String policyVersion,
            String origin) {
        String after = capture(d, id, actor);
        append(d, app, id, before, after, actor, operationId, policyVersion, origin, null);
    }

    private void append(
            DataCenter.Definition d,
            String app,
            String id,
            String before,
            String after,
            long actor,
            String operationId,
            String policyVersion,
            String origin,
            String definitionJson) {
        try {
            if (before == null && after == null) return;
            if (before != null
                    && after != null
                    && json.readTree(before)
                            .get("values")
                            .equals(json.readTree(after).get("values"))
                    && Objects.equals(
                            json.readTree(before).get("details"),
                            json.readTree(after).get("details"))
                    && Objects.equals(
                            json.readTree(before).get("detailOrder"),
                            json.readTree(after).get("detailOrder"))) return;
        } catch (Exception e) {
            throw invalid("变更内容无法校验");
        }
        var operation =
                before == null
                        ? RecordChangeOperationEnum.CREATE
                        : after == null
                                ? RecordChangeOperationEnum.DELETE
                                : RecordChangeOperationEnum.UPDATE;
        history.append(
                d.objectId(),
                id,
                app,
                operation.getCode(),
                before,
                after,
                definitionJson == null ? encode(d) : definitionJson,
                Long.toString(actor),
                operationId,
                policyVersion,
                origin);
        // 历史修订引用受保护文件内容：同事务登记保留引用，物理清理不得越过历史快照
        fileRetentions.register(
                BizFileRetentionDO.HOLDER_RECORD_HISTORY,
                operationId,
                d.objectId(),
                id,
                attachmentFileIds(d, before, after),
                actor);
    }

    /**
     * 前后快照中出现过的受保护文件编号并集
     *
     * <p>只认对象业务文件规则内的附件/图片字段：普通附件不进入物理清理，无需保留引用， 也避免把数量等普通数值字段误判为文件编号。
     */
    private List<Long> attachmentFileIds(DataCenter.Definition d, String before, String after) {
        DataCenter.BusinessFilePolicy policy =
                d.settings() == null ? null : d.settings().businessFilePolicy();
        if (!DataCenter.BusinessFilePolicy.enabled(policy)) return List.of();
        Set<String> fields = new HashSet<>(policy.fieldIds());
        Set<Long> result = new LinkedHashSet<>();
        collectAttachmentFileIds(d, fields, before, result);
        collectAttachmentFileIds(d, fields, after, result);
        return new ArrayList<>(result);
    }

    private void collectAttachmentFileIds(
            DataCenter.Definition d, Set<String> fields, String snapshotJson, Set<Long> out) {
        if (snapshotJson == null) return;
        JsonNode snapshot;
        try {
            snapshot = json.readTree(snapshotJson);
        } catch (java.io.IOException e) {
            throw invalid("历史快照附件引用无法读取");
        }
        for (FieldDefinition field : d.fields()) {
            if (protectedFile(field, fields))
                collectFileIds(snapshot.path("values").get(field.id()), out);
        }
        for (DataCenter.Detail detail : d.details()) {
            JsonNode rows = snapshot.path("details").path(detail.id());
            for (FieldDefinition field : detail.fields()) {
                if (!protectedFile(field, fields)) continue;
                for (JsonNode row : rows) {
                    collectFileIds(row.get(field.id()), out);
                }
            }
        }
    }

    private boolean protectedFile(FieldDefinition field, Set<String> fields) {
        return fields.contains(field.id())
                && (FieldTypeEnum.ATTACHMENT.matches(field.type())
                        || FieldTypeEnum.IMAGE.matches(field.type()));
    }

    private void collectFileIds(JsonNode value, Set<Long> out) {
        if (value == null || value.isNull() || value.isMissingNode()) return;
        if (value.isArray()) {
            for (JsonNode item : value) collectFileId(item, out);
            return;
        }
        collectFileId(value, out);
    }

    private void collectFileId(JsonNode value, Set<Long> out) {
        String text = value.asText("");
        if (text != null && text.matches("[1-9][0-9]{0,18}")) out.add(Long.valueOf(text));
    }

    /** 变更来源。被记历史的记录正是数据联动自动更新这次系统写入的目标时记为 LINKAGE，优先于其它上下文。 */
    private String source(String app, String objectId, String recordId) {
        var linkage =
                com.lingan.ucp.nocode.runtime.service.record.LinkageWriteScope.historySource(
                        app, objectId, recordId);
        if (linkage != null) return encode(linkage);
        var dated =
                com.lingan.ucp.nocode.runtime.service.record.DateTriggerWriteScope.historySource(
                        app, objectId, recordId);
        if (dated != null) return encode(dated);
        return source(app);
    }

    private String source(String app) {
        var automation =
                com.lingan.ucp.nocode.runtime.service.record.AutomationWriteScope.historySource();
        if (automation != null) return encode(automation);
        Map<String, Object> capture =
                com.lingan.ucp.nocode.runtime.service.record.CaptureWriteScope.historySource();
        if (capture != null) return encode(capture);
        Map<String, Object> maintenance =
                com.lingan.ucp.nocode.runtime.service.maintenance.ObjectMaintenanceScope
                        .historySource(app);
        if (maintenance != null) return encode(maintenance);
        var entry = taskScope.current();
        if (entry == null) return null;
        if (entry.data() != null)
            return encode(
                    Map.of(
                            "kind",
                            "TASK_GROUP",
                            "applicationId",
                            entry.applicationId(),
                            "rootId",
                            entry.data().rootId(),
                            "taskId",
                            entry.data().taskId(),
                            "entryKey",
                            entry.data().entryKey(),
                            "grantorId",
                            Long.toString(entry.data().grantorId()),
                            "version",
                            entry.version()));
        return encode(
                Map.of(
                        "kind",
                        "TASK_ENTRY",
                        "applicationId",
                        entry.applicationId(),
                        "entryId",
                        entry.entryId(),
                        "version",
                        entry.version(),
                        "name",
                        entry.name()));
    }

    public long checkpoint() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw invalid("联合保存必须处于事务中");
        return history.transactionCheckpoint();
    }

    /** 只有独立关联数据实际变化且主记录尚无事件时，才补一条联合更新事实。 */
    public String finishRelated(
            DataCenter.Definition definition, String app, String id, long checkpoint, long actor) {
        var changes = new ArrayList<Map<String, String>>();
        try {
            for (var raw : history.transactionEvents(checkpoint, app, Long.toString(actor))) {
                var event = json.readTree(raw);
                if (definition.objectId().equals(event.path("objectId").asText())
                        && id.equals(event.path("recordId").asText()))
                    return event.path("operationId").asText();
                changes.add(
                        Map.of(
                                "eventId",
                                event.path("eventId").asText(),
                                "objectId",
                                event.path("objectId").asText(),
                                "recordId",
                                event.path("recordId").asText()));
            }
            var operationId = UUID.randomUUID().toString();
            if (changes.isEmpty()) return operationId;
            var d = begin(definition, actor);
            var snapshot = capture(d, id, actor);
            if (snapshot == null) throw invalid("联合更新的主记录已不存在");
            var source =
                    source(app) == null
                            ? json.createObjectNode()
                            : (com.fasterxml.jackson.databind.node.ObjectNode)
                                    json.readTree(source(app));
            source.set("relatedEvents", json.valueToTree(changes));
            // 主字段没有改动，前后快照保持真实。源事件引用只在服务端存储，不直接向无子对象权限者输出。
            history.append(
                    d.objectId(),
                    id,
                    app,
                    RecordChangeOperationEnum.UPDATE.getCode(),
                    snapshot,
                    snapshot,
                    encode(d),
                    Long.toString(actor),
                    operationId,
                    null,
                    encode(source));
            return operationId;
        } catch (java.io.IOException e) {
            throw invalid("联合更新来源无法编码");
        }
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw invalid("历史快照无法编码");
        }
    }
}
