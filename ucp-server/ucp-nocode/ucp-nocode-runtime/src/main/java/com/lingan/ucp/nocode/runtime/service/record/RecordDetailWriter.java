package com.lingan.ucp.nocode.runtime.service.record;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.*;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.metadata.service.form.DetailForms;
import com.lingan.ucp.nocode.metadata.service.form.FormBehaviors;
import com.lingan.ucp.nocode.runtime.dal.mapper.*;
import com.lingan.ucp.nocode.runtime.service.rules.FieldRuleEnforcer;
import com.lingan.ucp.nocode.runtime.service.rules.RuleContext;
import com.lingan.ucp.nocode.runtime.service.selection.RecordDirectoryValues;
import com.lingan.ucp.nocode.runtime.service.selection.SelectionCatalog;

import jakarta.annotation.Resource;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.*;

/** 内部明细完整替换的准备与持久化；父记录锁及整个事务由主写入编排持有。 */
@Component
public class RecordDetailWriter {
    @Resource private RecordAutoNumbers autoNumbers;
    @Resource private RecordPersistence persistence;
    @Resource private RecordRelationAccess relationAccess;
    @Resource private RecordSelectionSupport selections;
    @Resource private RuntimeSchema schemas;
    @Resource private RecordValues values;
    @Resource private RecordDirectoryValues directoryValues;
    @Resource private SelectionCatalog selectionCatalog;
    @Resource private RecordMapper records;
    @Resource private FieldRuleEnforcer ruleEnforcer;
    @Resource private com.lingan.ucp.nocode.runtime.service.record.DetailPositions detailPositions;

    record PreparedLine(
            Row row, String key, Map<String, Object> payload, Map<String, Object> values) {}

    /** 第一轮逐行准备后的中间值；只读联动与公式默认值按整组一次求值后再完成第二轮校验。 */
    private record RowDraft(
            Row item,
            String key,
            boolean create,
            Map<String, Object> previousValues,
            ApplicationUi.Form detailForm,
            Set<String> fieldIds,
            Map<String, Object> input) {}

    record PreparedDetail(
            String objectId,
            DataCenter.Detail detail,
            RuntimeSchema.Table table,
            List<Row> previous,
            List<PreparedLine> lines) {}

    /** 先准备完整替换集合，统一检查归属、版本和临时行键，再开始任何主从持久化。 */
    List<PreparedDetail> prepareDetails(
            Save command,
            DataCenter.Definition object,
            long actor,
            Map<String, Object> mainPrevious,
            Map<String, Object> mainCandidate,
            Set<String> mainReadable) {
        if (command.details() == null) return List.of();
        if (command.details().size() > 20) throw invalid("明细分组过多");
        var ruleContext = new RuleContext(command.applicationId(), object, actor, null, null);
        List<PreparedDetail> result = new ArrayList<>();
        for (Map.Entry<String, List<ApplicationRecords.Row>> group : command.details().entrySet()) {
            DataCenter.Detail detail =
                    object.details().stream()
                            .filter(
                                    x ->
                                            x.id().equals(group.getKey())
                                                    && MemberStateEnum.ACTIVE.matches(x.state()))
                            .findFirst()
                            .orElseThrow(() -> invalid("明细分组不存在或已停用"));
            RuntimeSchema.Table t = schemas.detail(object, detail);
            persistence.writable(t);
            List<ApplicationRecords.Row> submitted = group.getValue();
            if (submitted == null || submitted.size() > 500) throw invalid("每组明细最多 500 行");
            List<ApplicationRecords.Row> previous =
                    command.id() == null
                            ? List.<Row>of()
                            : records
                                    .rows(
                                            t.statement(
                                                    null, command.id(), Long.toString(actor), true))
                                    .stream()
                                    .map(persistence::row)
                                    .toList();
            if (previous.size() > 500) throw invalid("明细超过 500 行，不能整组替换");
            Map<String, Row> old = new LinkedHashMap<>();
            previous.forEach(r -> old.put(r.id(), r));
            Set<String> retained = new HashSet<>(), keys = new HashSet<>();
            List<PreparedLine> lines = new ArrayList<>();
            List<RowDraft> drafts = new ArrayList<>();
            for (int index = 0; index < submitted.size(); index++) {
                ApplicationRecords.Row item = submitted.get(index);
                if (item == null) throw invalid("明细不能为空");
                String key =
                        item.clientRowKey() == null
                                ? (item.id() == null ? "new-" + index : "row-" + item.id())
                                : item.clientRowKey();
                if (!key.matches("[A-Za-z0-9_-]{1,100}") || !keys.add(key))
                    throw invalid("明细临时行键无效或重复");
                try {
                    boolean create = item.id() == null;
                    if (!create) {
                        if (!retained.add(item.id()) || !old.containsKey(item.id()))
                            throw invalid("明细重复或不属于当前主记录");
                        persistence.checkRevision(old.get(item.id()), item.revision());
                    } else if (item.revision() != null) throw invalid("新增明细不能指定版本");
                    Map<String, Object> previousValues =
                            create ? Map.<String, Object>of() : old.get(item.id()).values();
                    ApplicationUi.Form detailForm =
                            DetailForms.form(
                                    selections.selectionForm(
                                            command.applicationId(),
                                            command.objectId(),
                                            command.formId()),
                                    detail.id());
                    Set<String> fieldIds =
                            t.fields().stream()
                                    .map(FieldDefinition::id)
                                    .collect(java.util.stream.Collectors.toSet());
                    HashSet<String> writableIds = new HashSet<>(fieldIds);
                    if (detailForm != null) {
                        Map<String, ApplicationUi.FieldPresentation> presentations =
                                SelectionFields.presentations(detailForm.nodes());
                        writableIds.retainAll(presentations.keySet());
                        presentations.forEach(
                                (id, p) -> {
                                    if (p != null && Boolean.TRUE.equals(p.readOnly()))
                                        writableIds.remove(id);
                                });
                        if (!writableIds.containsAll(item.values().keySet()))
                            throw invalid("包含明细表单外或只读字段");
                    }
                    Map<String, Object> input =
                            FormBehaviors.apply(
                                    detailForm,
                                    com.lingan.ucp.nocode.metadata.service.form.FormFillBindings
                                            .clearEmptySources(
                                                    detailForm,
                                                    item.values(),
                                                    previousValues,
                                                    writableIds,
                                                    t.options()),
                                    previousValues,
                                    fieldIds,
                                    t.fields());
                    LinkedHashMap<String, Object> initialized = new LinkedHashMap<>(mainCandidate);
                    initialized.putAll(input);
                    input =
                            selections.selectionDefaults(
                                    t,
                                    initialized,
                                    create,
                                    actor,
                                    writableIds,
                                    detailForm == null
                                            ? Map.of()
                                            : SelectionFields.presentations(detailForm.nodes()));
                    input.keySet().retainAll(fieldIds);
                    drafts.add(
                            new RowDraft(
                                    item,
                                    key,
                                    create,
                                    previousValues,
                                    detailForm,
                                    fieldIds,
                                    input));
                } catch (ServiceException e) {
                    throw located(e, detail.id(), key, item.id());
                }
            }
            // 对象规则按整组一次求值：只读联动按本行与主表服务端值重算强制，新增行补公式默认值（15.4.5）。
            var ruled =
                    ruleEnforcer.prepareRows(
                            ruleContext,
                            detail,
                            drafts.stream()
                                    .map(
                                            x ->
                                                    new FieldRuleEnforcer.RowState(
                                                            x.key(),
                                                            x.item().id(),
                                                            x.create(),
                                                            x.previousValues(),
                                                            x.input(),
                                                            x.item().values() == null
                                                                    ? Set.<String>of()
                                                                    : x.item().values().keySet()))
                                    .toList(),
                            mainPrevious,
                            mainCandidate);
            for (int index = 0; index < drafts.size(); index++) {
                var draft = drafts.get(index);
                var item = draft.item();
                var key = draft.key();
                try {
                    boolean create = draft.create();
                    var previousValues = draft.previousValues();
                    var detailForm = draft.detailForm();
                    var fieldIds = draft.fieldIds();
                    if (ruled.get(index).error() != null) throw ruled.get(index).error();
                    var input = ruled.get(index).input();
                    var candidate = new LinkedHashMap<String, Object>(previousValues);
                    candidate.putAll(input);
                    FormBehaviors.require(detailForm, candidate, t.fields());
                    Map<String, Object> payload =
                            values.normalize(t, input, create, previousValues);
                    relationAccess.validateReferences(
                            command.applicationId(), object, t, payload, actor);
                    LinkedHashMap<String, Object> scenePrevious = new LinkedHashMap<>(mainPrevious);
                    scenePrevious.putAll(previousValues);
                    HashSet<String> sceneReadable = new HashSet<>(mainReadable);
                    sceneReadable.addAll(fieldIds);
                    selections.validateSelectionScene(
                            command.applicationId(),
                            DetailForms.selectionDefinition(object, detail),
                            t,
                            detailForm,
                            payload,
                            scenePrevious,
                            actor,
                            sceneReadable,
                            Map.of(),
                            item.id(),
                            mainCandidate);
                    ruleEnforcer.validateRowReferences(
                            ruleContext,
                            detail,
                            RecordPersistence.mergeValues(t, previousValues, payload),
                            previousValues,
                            mainPrevious,
                            mainCandidate,
                            create);
                    directoryValues.validate(t, payload, previousValues);
                    selectionCatalog.validate(t, payload, previousValues);
                    if (payload.containsKey(t.binding().parentColumn())) throw invalid("明细归属由平台维护");
                    lines.add(
                            new PreparedLine(
                                    item,
                                    key,
                                    payload,
                                    RecordPersistence.mergeValues(t, previousValues, payload)));
                } catch (ServiceException e) {
                    throw located(e, detail.id(), key, item.id());
                }
            }
            result.add(new PreparedDetail(object.objectId(), detail, t, previous, lines));
        }
        return result;
    }

    /** 行内错误包装成行级问题；冲突保留原错误码，前端不得把并发覆盖提示误当作普通字段问题。规则错误另带 fieldId 定位单元格。 */
    private static ServiceException located(
            ServiceException e, String detailId, String key, String recordId) {
        return e.setDetails(
                Map.of(
                        "problems",
                        List.of(
                                new DocumentPolicy.Problem(
                                        null,
                                        DocumentRuleScopeEnum.ROW.getCode(),
                                        detailId,
                                        key,
                                        recordId,
                                        FieldRuleEnforcer.fieldId(e),
                                        e.getMessage()))));
    }

    Map<String, String> writeDetails(PreparedDetail group, String parent, long actor) {
        Set<String> retained = new HashSet<>();
        Map<String, String> keys = new LinkedHashMap<>();
        RuntimeSchema.Table t = group.table();
        for (RecordDetailWriter.PreparedLine line : group.lines()) {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>(line.payload());
            payload.put(t.binding().parentColumn(), parent);
            if (line.row().id() == null) autoNumbers.generate(group.objectId(), t, payload, actor);
            com.lingan.ucp.nocode.runtime.dal.query.RecordStatement sql =
                    persistence.writeStatement(t, line.row().id(), parent, payload, actor);
            try {
                String id = line.row().id();
                if (id == null) id = records.insert(sql);
                else if (records.update(sql) != 1) throw persistence.conflict();
                retained.add(id);
                keys.put(id, line.key());
            } catch (DataIntegrityViolationException error) {
                throw DocumentValidation.error(
                        List.of(
                                new DocumentPolicy.Problem(
                                        null,
                                        DocumentRuleScopeEnum.ROW.getCode(),
                                        group.detail().id(),
                                        line.key(),
                                        line.row().id(),
                                        null,
                                        RecordConstraintErrors.translate(error, t).getMessage())));
            }
        }
        for (ApplicationRecords.Row row : group.previous())
            if (!retained.contains(row.id()))
                records.delete(t.statement(row.id(), parent, Long.toString(actor), false));
        detailPositions.save(
                group.objectId(),
                group.detail().id(),
                parent,
                new ArrayList<>(keys.keySet()),
                actor);
        return keys;
    }
}
