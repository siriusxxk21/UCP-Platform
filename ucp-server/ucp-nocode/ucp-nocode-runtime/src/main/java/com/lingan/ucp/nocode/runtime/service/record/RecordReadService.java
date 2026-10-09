package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.dal.dataobject.*;
import com.lingan.ucp.nocode.runtime.dal.mapper.*;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 已授权记录、历史收据及流程关联读取，统一装配字段、明细和展示标签。 */
@Component
public class RecordReadService {
    @Resource private RecordContextResolver contexts;
    @Resource private RecordModelProjection projection;
    @Resource private RecordPersistence persistence;
    @Resource private RecordRelationAccess relationAccess;
    @Resource private RecordSelectionSupport selections;
    @Resource private RecordTransactions transactions;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private RecordProcessService processes;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordValues values;
    @Resource private RecordSummaries summaries;
    @Resource private RecordCalculations calculations;
    @Resource private RecordMapper records;
    @Resource private com.lingan.ucp.nocode.runtime.service.record.DocumentReceipts receipts;
    @Resource private com.lingan.ucp.nocode.runtime.service.record.DetailPositions detailPositions;

    /** 读取主记录及当前可见明细，返回实际可执行能力与记录修订。 */
    public Aggregate get(String app, String object, String id, long actor) {
        return get(app, object, id, actor, false);
    }

    /** 内部材料装配使用：不存在的记录返回空，不以捕获异常污染调用方已有事务。 */
    public Aggregate getIfPresent(String app, String object, String id, long actor) {
        return get(app, object, id, actor, true);
    }

    private Aggregate get(String app, String object, String id, long actor, boolean allowMissing) {
        return transactions.tx(
                () -> {
                    var d = contexts.definition(app, object, actor);
                    var access = policy.access(app, d, actor);
                    var main =
                            allowMissing
                                    ? persistence.authorizedReadIfPresent(
                                            schemas.main(d), id, actor, access)
                                    : persistence.authorizedRead(
                                            schemas.main(d),
                                            id,
                                            actor,
                                            false,
                                            access,
                                            ApplicationActionEnum.READ);
                    if (main == null) return null;
                    var visible =
                            projection.visibleAggregate(
                                    aggregate(d, id, actor, main.permissions().readDetails()),
                                    main);
                    return new Aggregate(
                            processPermissions(
                                    object,
                                    selections
                                            .selectionLabels(
                                                    app,
                                                    d,
                                                    calculations.enrich(
                                                            app,
                                                            d,
                                                            summaries.enrich(
                                                                    d, List.of(visible.record())),
                                                            actor),
                                                    actor)
                                            .getFirst()),
                            selections.selectionDetailLabels(app, d, visible.details(), actor),
                            processes.history(app, object, id),
                            relationAccess.readRelations(
                                    d, id, actor, main.permissions().readRelations()));
                });
    }

    Aggregate aggregate(DataCenter.Definition d, String id, long actor, Set<String> readDetails) {
        var main = persistence.read(schemas.main(d), id, null, actor, false);
        Map<String, List<Row>> details = new LinkedHashMap<>();
        for (var detail : d.details())
            if (MemberStateEnum.ACTIVE.matches(detail.state())
                    && readDetails.contains(detail.id())) {
                var table = schemas.detail(d, detail);
                var rows =
                        records
                                .rows(table.statement(null, id, Long.toString(actor), false))
                                .stream()
                                .map(persistence::row)
                                .toList();
                if (rows.size() > 500) throw invalid("明细超过 500 行，请使用明细查询视图处理");
                details.put(
                        detail.id(),
                        detailPositions.order(d.objectId(), detail.id(), id, rows, Row::id));
            }
        return new Aggregate(main, details);
    }

    /** 结果查询不会再次执行写入；不存在收据时客户端应保留原键重试或继续查询。 */
    /** 按当前操作者和原请求键读取保存收据，结果再次经过当前权限裁剪。 */
    public SaveReceipt receipt(String app, String object, String key, long actor) {
        return transactions.tx(
                () -> {
                    var d = contexts.definition(app, object, actor);
                    var row = receipts.find(app, object, key, actor);
                    return row == null
                            ? new SaveReceipt(
                                    key,
                                    DocumentReceiptStatusEnum.NOT_FOUND.getCode(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null)
                            : receiptResult(app, d, row, actor);
                });
    }

    SaveReceipt receiptResult(
            String app,
            DataCenter.Definition d,
            com.lingan.ucp.nocode.runtime.dal.dataobject.DocumentReceiptDO row,
            long actor) {
        Aggregate result = null;
        Row current = null;
        try {
            current =
                    persistence.authorizedRead(
                            schemas.main(d),
                            row.getRecordId(),
                            actor,
                            false,
                            policy.access(app, d, actor),
                            ApplicationActionEnum.READ);
        } catch (ServiceException denied) {
            // 请求归当前操作者，可确认成功事实；已删记录或撤权后的快照不再向客户端返回。
        }
        if (current != null) {
            var original = receipts.decode(row);
            var caps = current.permissions();
            var fields = new LinkedHashMap<>(original.record().values());
            fields.keySet().retainAll(caps.readFields());
            var main = new Row(original.record().id(), original.record().revision(), fields, caps);
            Map<String, List<Row>> groups = new LinkedHashMap<>();
            original.details()
                    .forEach(
                            (id, lines) -> {
                                if (caps.readDetails().contains(id)) {
                                    var detail =
                                            d.details().stream()
                                                    .filter(t -> t.id().equals(id))
                                                    .findFirst()
                                                    .orElse(null);
                                    if (detail == null) return;
                                    var visibleFields =
                                            detail.fields().stream()
                                                    .filter(
                                                            f ->
                                                                    !MemberStateEnum.INACTIVE
                                                                            .matches(
                                                                                    detail.fieldOptions()
                                                                                            .getOrDefault(
                                                                                                    f
                                                                                                            .id(),
                                                                                                    DataCenter
                                                                                                            .FieldOptions
                                                                                                            .defaults())
                                                                                            .state()))
                                                    .map(FieldDefinition::id)
                                                    .collect(java.util.stream.Collectors.toSet());
                                    groups.put(
                                            id,
                                            lines.stream()
                                                    .map(
                                                            line -> {
                                                                var values =
                                                                        new LinkedHashMap<>(
                                                                                line.values());
                                                                values.keySet()
                                                                        .retainAll(visibleFields);
                                                                return new Row(
                                                                        line.id(),
                                                                        line.revision(),
                                                                        values,
                                                                        null,
                                                                        Map.of(),
                                                                        line.clientRowKey());
                                                            })
                                                    .toList());
                                }
                            });
            Map<String, List<String>> links = new LinkedHashMap<>(original.relations());
            links.keySet().retainAll(caps.readRelations());
            result =
                    new Aggregate(
                            selections.selectionLabels(app, d, List.of(main), actor).getFirst(),
                            selections.selectionDetailLabels(app, d, groups, actor),
                            List.of(),
                            links);
        }
        return new SaveReceipt(
                row.getRequestKey(),
                DocumentReceiptStatusEnum.SUCCEEDED.getCode(),
                row.getOperationId(),
                row.getRecordId(),
                row.getRecordRevision(),
                row.getPolicyVersion(),
                result);
    }

    List<Row> reportRows(
            String app,
            DataCenter.Definition d,
            List<String> raw,
            ApplicationRuntimePolicy.Access access,
            long actor) {
        return selections.selectionLabels(
                app,
                d,
                calculations.enrich(
                        app,
                        d,
                        summaries.enrich(
                                d,
                                raw.stream()
                                        .map(
                                                r ->
                                                        processPermissions(
                                                                d.objectId(),
                                                                persistence.visible(r, access)))
                                        .toList()),
                        actor),
                actor);
    }

    public ProcessRecord processRecord(String businessKey, long actor) {
        return transactions.tx(
                () -> {
                    var link = processes.locate(businessKey);
                    String app = link.getApplicationId().toString(),
                            object = link.getObjectId().toString();
                    get(app, object, link.getRecordId(), actor);
                    return new ProcessRecord(app, object, link.getRecordId());
                });
    }

    Row processPermissions(String object, Row row) {
        if (!processes.running(object, row.id())) return row;
        var caps = row.permissions();
        Set<String> allowed = new HashSet<>(caps.actions());
        allowed.removeAll(
                Set.of(
                        ApplicationActionEnum.UPDATE.getCode(),
                        ApplicationActionEnum.DELETE.getCode(),
                        ApplicationActionEnum.START_PROCESS.getCode()));
        return new Row(
                row.id(),
                row.revision(),
                row.values(),
                new ApplicationAuthorization.Capabilities(
                        allowed,
                        caps.readFields(),
                        Set.of(),
                        caps.readDetails(),
                        Set.of(),
                        caps.readRelations(),
                        Set.of()),
                row.displayValues());
    }
}
