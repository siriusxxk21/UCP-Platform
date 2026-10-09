package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.Row;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.api.ObjectDataMaintenance.*;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.dal.dataobject.ObjectDraftHeadDO;
import com.richuang.os.nocode.metadata.dal.mapper.DataCenterMapper;
import com.richuang.os.nocode.metadata.service.form.BusinessHandlingPolicies;
import com.richuang.os.nocode.metadata.service.form.DocumentPolicies;
import com.richuang.os.nocode.runtime.dal.mapper.ObjectMaintenanceMapper;
import com.richuang.os.nocode.runtime.dal.mapper.RecordHistoryMapper;
import com.richuang.os.nocode.runtime.dal.mapper.RecordMapper;
import com.richuang.os.nocode.runtime.dal.query.RecordStatement;
import com.richuang.os.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.richuang.os.nocode.runtime.service.maintenance.ObjectMaintenanceScope;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 管理员删除前只读遍历真实影响；调用方持有管理授权、定义锁和事务，最终删除仍复查。 */
@Component
public class RecordMaintenanceChecks {
    @Resource private DataObjectApi objects;
    @Resource private DataCenterMapper metadata;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordPersistence persistence;
    @Resource private RecordMapper records;
    @Resource private RecordRelations relations;
    @Resource private RecordProcessService processes;
    @Resource private RecordLinkageSync linkageSync;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private RecordValues values;
    @Resource private RecordDocumentValidation documents;
    @Resource private RecordCalculations calculations;
    @Resource private RecordAutomations automations;
    @Resource private RecordCaptures captures;
    @Resource private RecordHistoryMapper history;
    @Resource private ObjectMaintenanceMapper maintenance;

    /** 确认指纹包括发布契约、所有影响行修订和关系动作；预览不执行删除或保存。 */
    public DeletePreview preview(Delete command, long actor) {
        if (command == null) throw invalid("缺少删除记录");
        if (!ObjectMaintenanceScope.permits(null, actor)) throw invalid("缺少对象数据维护授权上下文");
        DataObjectApi.PublishedObject published = objects.getVersion(command.objectId(), null);
        if (published.versionNo() != command.versionNo()
                || !Objects.equals(published.checksum(), command.checksum()))
            throw invalid("对象版本已变化，请刷新后重新检查");
        State state = new State(actor);
        state.definitions.put(published.objectId(), published);
        Definition definition = published.definition();
        // 与真正删除、普通保存保持自动更新锁 → 历史锁 → 行锁的顺序；预检只取锁，不建立历史基线。
        automations.lock(null);
        linkageSync.lock(null);
        lockHistory(definition.objectId(), state);
        Row root = persistence.read(schemas.main(definition), command.id(), null, actor, true);
        persistence.checkRevision(root, command.expectedRevision());
        visit(definition, root, null, state);
        boolean allowed =
                state.impacts.stream()
                        .noneMatch(i -> ObjectDataImpactActionEnum.BLOCK.matches(i.action()));
        String message =
                allowed
                        ? "删除将立即生效；下列记录和引用按策略处理，相关自动更新继续按已发布规则执行"
                        : state.impacts.stream()
                                .filter(i -> ObjectDataImpactActionEnum.BLOCK.matches(i.action()))
                                .map(
                                        i ->
                                                i.objectName()
                                                        + " · "
                                                        + i.recordTitle()
                                                        + "："
                                                        + i.message())
                                .findFirst()
                                .orElse("请先处理删除影响");
        state.fingerprints.add(persistence.write(state.impacts));
        return new DeletePreview(
                allowed,
                title(definition, root),
                List.copyOf(state.impacts),
                message,
                digest(persistence.write(state.fingerprints)));
    }

    private void visit(Definition definition, Row row, String incomingName, State state) {
        String key = definition.objectId() + ":" + row.id();
        if (!state.deleting.add(key)) return;
        remember(definition, row, null, state);
        if (state.deleting.size() > 200) {
            impact(
                    definition,
                    row,
                    incomingName,
                    ObjectDataImpactActionEnum.BLOCK,
                    "级联删除超过 200 条，请先分批处理关联记录",
                    state);
            return;
        }
        try {
            RuntimeSchema.Table table = schemas.main(definition);
            persistence.writable(table);
            persistence.authorizedRead(
                    table,
                    row.id(),
                    state.actor,
                    true,
                    policy.access(null, definition, state.actor),
                    ApplicationActionEnum.DELETE);
            processes.requireIdle(definition.objectId(), row.id());
            DocumentStates.requireDelete(definition, row.values());
        } catch (ServiceException error) {
            impact(
                    definition,
                    row,
                    incomingName,
                    ObjectDataImpactActionEnum.BLOCK,
                    error.getMessage(),
                    state);
            return;
        }
        impact(
                definition,
                row,
                incomingName,
                ObjectDataImpactActionEnum.DELETE,
                incomingName == null ? "删除当前记录（逻辑删除）" : "按关系删除策略级联删除这条记录（逻辑删除）",
                state);
        incoming(definition, row, state);
        details(definition, row, state);
        outgoing(definition, row, state);
    }

    private void incoming(Definition target, Row targetRow, State state) {
        for (ObjectDraftHeadDO head : metadata.allHeads()) {
            if (head.getCurrentPublishedVersionNo() == null
                    || !ObjectStatusEnum.ACTIVE.matches(head.getStatus())) continue;
            Definition source = published(head.getId().toString(), state).definition();
            for (Relation relation : source.relations()) {
                if (!Objects.equals(relation.targetObjectId(), target.objectId())) continue;
                lockHistory(source.objectId(), state);
                if (RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())) {
                    incomingMany(source, relation, targetRow.id(), state);
                    continue;
                }
                RelationSources.Source origin = RelationSources.source(source, relation);
                RuntimeSchema.Table table =
                        schemas.table(
                                origin.tableName(),
                                origin.binding(),
                                origin.fields(),
                                origin.options());
                String column = table.columns().get(relation.fieldId());
                if (column == null)
                    throw invalid("引用字段结构已经变化：" + source.objectName() + " · " + relation.name());
                RecordStatement query = referencing(table, column, targetRow.id(), state.actor);
                List<Row> linked =
                        records.rows(query).stream()
                                .map(persistence::row)
                                .filter(
                                        row ->
                                                relation.sourceDetailId() != null
                                                        || !state.deleting.contains(
                                                                source.objectId() + ":" + row.id()))
                                .toList();
                if (linked.size() > 200) {
                    // 超限项是整组引用的汇总，不以首条明细 ID 冒充可导航的主记录 ID。
                    state.impacts.add(
                            new Impact(
                                    source.objectId(),
                                    source.objectName(),
                                    null,
                                    "关联记录",
                                    relation.name(),
                                    ObjectDataImpactActionEnum.BLOCK.getCode(),
                                    "入向引用超过 200 条，请先分批处理；当前关系共有 " + records.count(query) + " 条引用"));
                    continue;
                }
                for (Row row : linked) {
                    remember(source, row, relation.sourceDetailId(), state);
                    if (relation.sourceDetailId() != null) {
                        Detail detail =
                                source.details().stream()
                                        .filter(d -> d.id().equals(relation.sourceDetailId()))
                                        .findFirst()
                                        .orElseThrow();
                        String parentId =
                                maintenance.parentRecordId(
                                        table.statement(
                                                row.id(), null, Long.toString(state.actor), false));
                        List<Row> parents =
                                parentId == null
                                        ? List.of()
                                        : records
                                                .rows(
                                                        schemas.main(source)
                                                                .statement(
                                                                        parentId,
                                                                        null,
                                                                        Long.toString(state.actor),
                                                                        true))
                                                .stream()
                                                .map(persistence::row)
                                                .toList();
                        Row parent = parents.isEmpty() ? null : parents.getFirst();
                        if (parent != null) remember(source, parent, null, state);
                        state.impacts.add(
                                new Impact(
                                        source.objectId(),
                                        source.objectName(),
                                        parent == null ? null : parent.id(),
                                        (parent == null
                                                        ? "主记录 #" + Objects.toString(parentId, "未知")
                                                        : title(source, parent))
                                                + " · 明细 #"
                                                + row.id(),
                                        detail.name() + " · " + relation.name(),
                                        ObjectDataImpactActionEnum.BLOCK.getCode(),
                                        "内部明细“"
                                                + detail.name()
                                                + "”的明细行 #"
                                                + row.id()
                                                + " 仍引用目标，请先在所属主单据解除该明细引用"));
                    } else if (DeletePolicyEnum.CASCADE.matches(relation.onDelete())) {
                        visit(source, row, relation.name(), state);
                    } else if (DeletePolicyEnum.SET_NULL.matches(relation.onDelete())) {
                        clearReference(source, relation, row, state);
                    } else {
                        impact(
                                source,
                                row,
                                relation.name(),
                                ObjectDataImpactActionEnum.BLOCK,
                                "这条记录通过“" + relation.name() + "”引用删除目标；关系设置为仍被使用时禁止删除，请先调整该引用",
                                state);
                    }
                }
            }
        }
    }

    private void clearReference(Definition source, Relation relation, Row row, State state) {
        try {
            RuntimeSchema.Table table = schemas.main(source);
            persistence.writable(table);
            Row authorized =
                    persistence.authorizedRead(
                            table,
                            row.id(),
                            state.actor,
                            true,
                            policy.access(null, source, state.actor),
                            ApplicationActionEnum.UPDATE);
            if (!authorized.permissions().writeFields().contains(relation.fieldId()))
                throw invalid("该引用字段不可修改");
            processes.requireIdle(source.objectId(), row.id());
            String recordKey = source.objectId() + ":" + row.id();
            // 同一记录可能有多个引用同时指向删除目标，按执行次序检查累计后的候选，不能分别放行互相依赖的字段。
            Map<String, Object> changed =
                    new LinkedHashMap<>(state.clearedFields.getOrDefault(recordKey, Map.of()));
            changed.put(relation.fieldId(), null);
            Map<String, Object> prepared =
                    DocumentStates.prepare(
                            source,
                            row.values(),
                            changed,
                            null,
                            authorized.permissions().actions(),
                            false);
            prepared =
                    automations.prepare(
                            source.objectId(), row.id(), prepared, changed, state.actor);
            prepared = captures.prepare(source, row.id(), prepared, changed, state.actor);
            Map<String, Object> payload = values.normalize(table, prepared, false, row.values());
            Map<String, Object> candidate =
                    RecordPersistence.mergeValues(table, row.values(), payload);
            if (DocumentPolicies.policy(source) != null) {
                DocumentPolicies.Input before =
                        documents.documentInput(source, row.id(), state.actor, Map.of());
                DocumentPolicies.Input after =
                        DocumentCalculations.calculate(
                                source,
                                RecordDocumentValidation.typedDocument(
                                        source,
                                        new DocumentPolicies.Input(candidate, before.details())),
                                input ->
                                        calculations.preview(
                                                null, source, row.id(), input, null, state.actor));
                RecordDocumentValidation.requireDocument(
                        source, before, after, authorized.permissions());
                candidate = after.values();
            }
            if (BusinessHandlingPolicies.required(source, false, candidate))
                throw invalid("解除引用需要审批，请先通过业务办理入口调整该引用");
            state.clearedFields.put(recordKey, changed);
            impact(
                    source,
                    row,
                    relation.name(),
                    ObjectDataImpactActionEnum.CLEAR_REFERENCE,
                    "清空“" + relation.name() + "”引用；这条记录及其他字段保留",
                    state);
        } catch (ServiceException error) {
            impact(
                    source,
                    row,
                    relation.name(),
                    ObjectDataImpactActionEnum.BLOCK,
                    "无法解除“" + relation.name() + "”引用：" + error.getMessage(),
                    state);
        }
    }

    private void incomingMany(Definition source, Relation relation, String targetId, State state) {
        List<String> ids;
        try {
            ids =
                    relations.sources(source, relation, targetId, state.actor).stream()
                            .filter(id -> !state.deleting.contains(source.objectId() + ":" + id))
                            .toList();
        } catch (ServiceException error) {
            state.impacts.add(
                    new Impact(
                            source.objectId(),
                            source.objectName(),
                            null,
                            "关联记录",
                            relation.name(),
                            ObjectDataImpactActionEnum.BLOCK.getCode(),
                            error.getMessage()));
            return;
        }
        for (String id : ids) {
            RuntimeSchema.Table table = schemas.main(source);
            Row row = persistence.read(table, id, null, state.actor, true);
            remember(source, row, null, state);
            state.fingerprints.add(
                    persistence.write(List.of(source.objectId(), relation.id(), id, targetId)));
            if (!DeletePolicyEnum.SET_NULL.matches(relation.onDelete())) {
                impact(
                        source,
                        row,
                        relation.name(),
                        ObjectDataImpactActionEnum.BLOCK,
                        "这条记录仍通过多对多关系“" + relation.name() + "”使用目标，请先解除关联",
                        state);
                continue;
            }
            try {
                persistence.writable(table);
                Row authorized =
                        persistence.authorizedRead(
                                table,
                                id,
                                state.actor,
                                true,
                                policy.access(null, source, state.actor),
                                ApplicationActionEnum.UPDATE);
                if (!authorized.permissions().writeRelations().contains(relation.id()))
                    throw invalid("没有解除入向关系的权限");
                processes.requireIdle(source.objectId(), id);
                impact(
                        source,
                        row,
                        relation.name(),
                        ObjectDataImpactActionEnum.CLEAR_REFERENCE,
                        "解除与删除目标的多对多关联；这条记录及其他关联保留",
                        state);
            } catch (ServiceException error) {
                impact(
                        source,
                        row,
                        relation.name(),
                        ObjectDataImpactActionEnum.BLOCK,
                        error.getMessage(),
                        state);
            }
        }
    }

    private void details(Definition definition, Row parent, State state) {
        for (Detail detail : definition.details()) {
            if (!MemberStateEnum.ACTIVE.matches(detail.state())) continue;
            RuntimeSchema.Table table = schemas.detail(definition, detail);
            List<Row> rows =
                    records
                            .rows(
                                    table.statement(
                                            null, parent.id(), Long.toString(state.actor), true))
                            .stream()
                            .map(persistence::row)
                            .toList();
            if (rows.size() > 500) {
                impact(
                        definition,
                        parent,
                        detail.name(),
                        ObjectDataImpactActionEnum.BLOCK,
                        "内部明细超过 500 行，无法完整封存删除历史，请先分批处理",
                        state);
                continue;
            }
            try {
                persistence.writable(table);
            } catch (ServiceException error) {
                impact(
                        definition,
                        parent,
                        detail.name(),
                        ObjectDataImpactActionEnum.BLOCK,
                        error.getMessage(),
                        state);
                continue;
            }
            for (Row row : rows) {
                remember(definition, row, detail.id(), state);
                state.impacts.add(
                        new Impact(
                                definition.objectId(),
                                definition.objectName(),
                                parent.id(),
                                title(definition, parent) + " · 明细 #" + row.id(),
                                detail.name(),
                                ObjectDataImpactActionEnum.DELETE.getCode(),
                                "随主记录“" + title(definition, parent) + "”删除内部明细（逻辑删除）"));
            }
        }
    }

    private void outgoing(Definition source, Row row, State state) {
        for (Relation relation : source.relations()) {
            if (!RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())) continue;
            List<String> targets;
            try {
                targets = relations.targets(source, relation, row.id(), state.actor);
            } catch (ServiceException error) {
                impact(
                        source,
                        row,
                        relation.name(),
                        ObjectDataImpactActionEnum.BLOCK,
                        error.getMessage(),
                        state);
                continue;
            }
            for (String target : targets) {
                state.fingerprints.add(
                        persistence.write(
                                List.of(source.objectId(), relation.id(), row.id(), target)));
                impact(
                        source,
                        row,
                        relation.name(),
                        ObjectDataImpactActionEnum.CLEAR_REFERENCE,
                        "解除与目标记录 #" + target + " 的多对多关联，目标记录保留",
                        state);
            }
        }
    }

    private DataObjectApi.PublishedObject published(String id, State state) {
        return state.definitions.computeIfAbsent(id, value -> objects.getVersion(value, null));
    }

    private void lockHistory(String object, State state) {
        if (state.historyLocked.add(object)) history.lock(object);
    }

    private void remember(Definition definition, Row row, String detail, State state) {
        DataObjectApi.PublishedObject version = published(definition.objectId(), state);
        state.fingerprints.add(
                persistence.write(
                        Arrays.asList(
                                definition.objectId(),
                                version.versionNo(),
                                version.checksum(),
                                detail,
                                row.id(),
                                row.revision())));
    }

    private static void impact(
            Definition d,
            Row row,
            String relation,
            ObjectDataImpactActionEnum action,
            String message,
            State state) {
        state.impacts.add(
                new Impact(
                        d.objectId(),
                        d.objectName(),
                        row.id(),
                        title(d, row),
                        relation,
                        action.getCode(),
                        message));
    }

    private static String title(Definition definition, Row row) {
        String result = Objects.toString(row.values().get(definition.titleFieldId()), "");
        return result.isBlank() ? "记录 #" + row.id() : result;
    }

    private RecordStatement referencing(
            RuntimeSchema.Table table, String column, String target, long actor) {
        RecordStatement base = table.statement(null, null, Long.toString(actor), true);
        return new RecordStatement(
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
                persistence.write(Map.of(column, target)),
                null,
                false,
                // 200 条已访问级联记录之外，仍读取 201 条以检查未处理引用是否超限。
                401,
                0,
                List.of(),
                "{}",
                base.actor(),
                true);
    }

    private static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法生成删除影响确认", error);
        }
    }

    private static final class State {
        private final long actor;
        private final Set<String> deleting = new LinkedHashSet<>();
        private final Set<String> historyLocked = new LinkedHashSet<>();
        private final Map<String, Map<String, Object>> clearedFields = new LinkedHashMap<>();
        private final Map<String, DataObjectApi.PublishedObject> definitions =
                new LinkedHashMap<>();
        private final List<Impact> impacts = new ArrayList<>();
        private final List<String> fingerprints = new ArrayList<>();

        State(long actor) {
            this.actor = actor;
        }
    }
}
