package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.dal.mapper.DataCenterMapper;
import com.lingan.ucp.nocode.runtime.dal.mapper.*;
import com.lingan.ucp.nocode.runtime.dal.query.*;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.*;

/** 主记录删除与传入引用处理；级联继续使用同一应用授权、事务和已访问集合。 */
@Component
public class RecordDeletionService {
    @Resource private com.lingan.ucp.nocode.runtime.service.task.TaskEntryRuntimeScope taskScope;
    @Resource private RecordContextResolver contexts;
    @Resource private RecordPersistence persistence;
    @Resource private RecordTransactions transactions;
    @Resource private RecordAutomations automations;
    @Resource private RecordLinkageSync linkageSync;
    @Resource private RecordWriteService writer;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private RecordProcessService processes;
    @Resource private DataObjectApi objects;
    @Resource private DataCenterMapper metadata;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordRelations relations;
    @Resource private RecordCalculations calculations;
    @Resource private OrderedRecordCalculations ordered;
    @Resource private RecordMapper records;
    @Resource private com.lingan.ucp.nocode.runtime.service.history.RecordHistoryTracker history;
    @Resource private com.lingan.ucp.nocode.runtime.service.record.DetailPositions detailPositions;
    @Resource private com.lingan.ucp.nocode.runtime.service.bizfile.BizFileBindingService bizFiles;
    @Resource private com.lingan.ucp.nocode.runtime.service.live.RecordChangeCollector changes;

    /** 删除当前可操作记录，复用原事务中的流程保护、引用策略、修订和历史记录。 */
    public void delete(Delete command, long actor) {
        taskScope.requireWrite();
        delete(command, actor, new LinkedHashSet<>());
    }

    /** 级联限定同一事务、同一应用授权及 200 条记录，循环关系由已访问集合终止。 */
    void delete(Delete command, long actor, Set<String> deleting) {
        transactions.tx(
                () -> {
                    if (command == null) throw invalid("缺少删除记录");
                    automations.lock(null);
                    linkageSync.lock(null);
                    if (!deleting.add(command.objectId() + ":" + command.id())) return true;
                    // 登记「本事务正在被删除」：级联与置空连带保存的来源记录不再回头改写这条记录。
                    linkageSync.deleting(command.objectId(), command.id());
                    if (deleting.size() > 200) throw invalid("级联删除超过 200 条，请先分批处理明细对象");
                    var d = contexts.definition(command.applicationId(), command.objectId(), actor);
                    ordered.lock(d.objectId());
                    ordered.requireCompatible(d);
                    Map<String, Object> orderedBefore = ordered.before(d, command.id(), actor);
                    var automationBefore =
                            automations.before(command.objectId(), command.id(), actor);
                    var linkageBefore =
                            linkageSync.before(
                                    command.applicationId(),
                                    command.objectId(),
                                    command.id(),
                                    actor);
                    var historyDefinition = history.begin(d, actor);
                    var historyBefore = history.capture(historyDefinition, command.id(), actor);
                    var access = policy.access(command.applicationId(), d, actor);
                    var t = schemas.main(d);
                    persistence.writable(t);
                    persistence.authorizedRead(
                            t, command.id(), actor, true, access, ApplicationActionEnum.DELETE);
                    persistence.checkRevision(
                            persistence.read(t, command.id(), null, actor, true),
                            command.expectedRevision());
                    processes.requireIdle(d.objectId(), command.id());
                    DocumentStates.requireDelete(
                            historyDefinition,
                            persistence
                                    .read(
                                            schemas.main(historyDefinition),
                                            command.id(),
                                            null,
                                            actor,
                                            false)
                                    .values());
                    // 逻辑删除不会触发物理外键，按已发布规则显式执行并重新检查每条记录权限。
                    resolveIncoming(command.applicationId(), d, command.id(), actor, deleting);
                    for (var relation : d.relations())
                        if (RelationTypeEnum.MANY_TO_MANY.matches(relation.kind()))
                            relations.detach(d, relation, command.id(), null, actor);
                    for (var detail : d.details())
                        if (MemberStateEnum.ACTIVE.matches(detail.state())) {
                            var dt = schemas.detail(d, detail);
                            persistence.writable(dt);
                            records.delete(
                                    dt.statement(null, command.id(), Long.toString(actor), false));
                            detailPositions.save(
                                    d.objectId(), detail.id(), command.id(), List.of(), actor);
                        }
                    if (records.delete(t.statement(command.id(), null, Long.toString(actor), false))
                            != 1) throw persistence.conflict();
                    changes.changed(d.objectId(), command.id(), RecordChangeOperationEnum.DELETE);
                    // 业务文件绑定与记录删除同事务：附件绑定转 HISTORY、移除网盘节点与空目录子树
                    bizFiles.unbindRecord(d.objectId(), command.id(), actor);
                    ordered.complete(
                            command.applicationId(), d, command.id(), orderedBefore, true, actor);
                    history.finish(
                            historyDefinition,
                            command.applicationId(),
                            command.id(),
                            historyBefore,
                            actor);
                    automations.after(
                            automationBefore,
                            command.id(),
                            RecordChangeOperationEnum.DELETE.getCode(),
                            actor,
                            deleting);
                    linkageSync.after(
                            linkageBefore,
                            command.objectId(),
                            command.id(),
                            RecordChangeOperationEnum.DELETE.getCode(),
                            actor);
                    return true;
                });
    }

    void resolveIncoming(
            String app, DataCenter.Definition target, String id, long actor, Set<String> deleting) {
        for (var head : metadata.allHeads()) {
            if (head.getCurrentPublishedVersionNo() == null
                    || !ObjectStatusEnum.ACTIVE.matches(head.getStatus())) continue;
            var source = objects.getPublished(head.getId().toString());
            for (var r : source.relations())
                if (r.targetObjectId().equals(target.objectId())) {
                    if (RelationTypeEnum.MANY_TO_MANY.matches(r.kind())) {
                        var linked =
                                relations.sources(source, r, id, actor).stream()
                                        .filter(
                                                sourceId ->
                                                        !deleting.contains(
                                                                source.objectId() + ":" + sourceId))
                                        .toList();
                        if (linked.isEmpty()) continue;
                        if (!DeletePolicyEnum.SET_NULL.matches(r.onDelete()))
                            throw invalid("记录仍有多对多关联，请先解除关系后删除");
                        var live = contexts.definition(app, source.objectId(), actor);
                        ordered.lock(live.objectId());
                        ordered.requireCompatible(live);
                        var table = schemas.main(live);
                        persistence.writable(table);
                        for (var sourceId : linked) {
                            Map<String, Object> orderedBefore =
                                    ordered.before(live, sourceId, actor);
                            var record =
                                    persistence.authorizedRead(
                                            table,
                                            sourceId,
                                            actor,
                                            true,
                                            policy.access(app, live, actor),
                                            ApplicationActionEnum.UPDATE);
                            if (!record.permissions().writeRelations().contains(r.id()))
                                throw invalid("没有解除入向关系的权限");
                            processes.requireIdle(source.objectId(), sourceId);
                            relations.detach(source, r, sourceId, id, actor);
                            if (records.update(
                                            persistence.writeStatement(
                                                    table, sourceId, null, Map.of(), actor))
                                    != 1) throw persistence.conflict();
                            changes.changed(
                                    source.objectId(), sourceId, RecordChangeOperationEnum.UPDATE);
                            calculations.save(app, live, sourceId, actor);
                            ordered.complete(app, live, sourceId, orderedBefore, false, actor);
                        }
                        continue;
                    }
                    var origin = RelationSources.source(source, r);
                    var table =
                            schemas.table(
                                    origin.tableName(),
                                    origin.binding(),
                                    origin.fields(),
                                    origin.options());
                    String column = table.columns().get(r.fieldId());
                    if (column == null) throw invalid("引用字段结构已经变化");
                    var base = table.statement(null, null, Long.toString(actor), false);
                    var query =
                            new RecordStatement(
                                    base.schema(),
                                    base.table(),
                                    base.keyColumn(),
                                    base.fields(),
                                    base.textFields(),
                                    base.numericFields(),
                                    base.deletedColumn(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    List.of(column),
                                    persistence.write(Map.of(column, id)),
                                    null,
                                    false,
                                    // 至多 200 条已访问记录会在下方过滤，仍须保留第 201 条未处理引用来识别超限。
                                    401,
                                    0,
                                    List.of(),
                                    "{}",
                                    base.actor(),
                                    false);
                    var linked =
                            records.rows(query).stream()
                                    .map(persistence::row)
                                    .filter(
                                            row ->
                                                    r.sourceDetailId() != null
                                                            || !deleting.contains(
                                                                    source.objectId()
                                                                            + ":"
                                                                            + row.id()))
                                    .toList();
                    if (linked.size() > 200) throw invalid("入向引用超过 200 条，请先分批处理");
                    if (r.sourceDetailId() != null && !linked.isEmpty())
                        throw invalid("记录仍被“" + source.objectName() + "”的内部明细引用，请先在主单据解除引用");
                    for (var record : linked) {
                        if (DeletePolicyEnum.CASCADE.matches(r.onDelete())) {
                            delete(
                                    new Delete(
                                            app, source.objectId(), record.id(), record.revision()),
                                    actor,
                                    deleting);
                        } else if (DeletePolicyEnum.SET_NULL.matches(r.onDelete())) {
                            Map<String, Object> changed = new LinkedHashMap<>();
                            changed.put(r.fieldId(), null);
                            writer.save(
                                    new Save(
                                            app,
                                            source.objectId(),
                                            record.id(),
                                            record.revision(),
                                            changed,
                                            null),
                                    actor);
                        } else throw invalid("记录仍被“" + source.objectName() + "”引用，请先解除引用");
                    }
                }
        }
    }
}
