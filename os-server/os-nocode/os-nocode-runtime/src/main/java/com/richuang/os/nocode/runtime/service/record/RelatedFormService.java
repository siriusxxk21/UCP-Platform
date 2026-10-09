package com.richuang.os.nocode.runtime.service.record;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationRecords.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.resource.ApplicationResourceValidator;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.metadata.service.form.BusinessHandlingPolicies;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** 联合保存仅支持直接生效。每个独立对象仍经过原记录服务的权限、版本和规则检查。 */
@Service
public class RelatedFormService {
    @Resource(name = "nocodeRecordService")
    private RecordService records;

    @Resource private ApplicationService applications;
    @Resource private RecordAutomations automations;
    @Resource private RecordLinkageSync linkageSync;
    @Resource private ApplicationResourceValidator resources;
    @Resource private DataObjectApi objects;
    @Resource private DocumentReceipts receipts;
    @Resource private com.richuang.os.nocode.runtime.service.history.RecordHistoryTracker history;

    private record Resolved(
            RelatedForms.Binding binding,
            DataCenter.Relation relation,
            String targetId,
            ApplicationUi.Form form,
            boolean incoming) {}

    private ApplicationUi.Form form(String app, String formId, String objectId) {
        var resource =
                applications.published(app).definition().resources().stream()
                        .filter(
                                r ->
                                        Objects.equals(r.id(), formId)
                                                && ApplicationResourceKindEnum.FORM.matches(
                                                        r.kind()))
                        .findFirst()
                        .orElseThrow(() -> invalid("关联录入表单未发布或不存在"));
        var form = resources.decode(resource.config(), ApplicationUi.Form.class);
        if (!Objects.equals(form.objectId(), objectId)) throw invalid("表单与业务对象不匹配");
        return form;
    }

    private Resolved resolve(
            String app, String objectId, String formId, String bindingId, long actor) {
        var parent = records.model(app, objectId, actor);
        var form = form(app, formId, objectId);
        var binding =
                Optional.ofNullable(form.relatedForms()).orElse(List.of()).stream()
                        .filter(b -> Objects.equals(b.id(), bindingId))
                        .findFirst()
                        .orElseThrow(() -> invalid("关联录入区域未配置或已变更"));
        boolean incoming = RelationDirectionEnum.INCOMING.matches(binding.direction());
        var source = records.definition(app, binding.sourceObjectId(), actor);
        var relation =
                source.relations().stream()
                        .filter(r -> Objects.equals(r.id(), binding.relationId()))
                        .findFirst()
                        .orElseThrow(() -> invalid("关联关系不存在"));
        if (relation.sourceDetailId() != null
                || RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())
                || (incoming
                        ? !Objects.equals(relation.targetObjectId(), objectId)
                        : !Objects.equals(source.objectId(), objectId)))
            throw invalid("关联录入只支持主记录上的单条引用或反向一对多关系");
        var target = incoming ? source.objectId() : relation.targetObjectId();
        if (Objects.equals(target, objectId)) throw invalid("关联录入暂不支持对象自身递归");
        var targetForm = form(app, binding.formId(), target);
        if (targetForm.relatedForms() != null && !targetForm.relatedForms().isEmpty())
            throw invalid("关联录入暂支持一层，请选择不含关联录入区的目标表单");
        return new Resolved(binding, relation, target, targetForm, incoming);
    }

    /** 读取范围沿用当前应用或任务作用域，不以关联关系扩展读取权。 */
    public RelatedForms.Result query(RelatedForms.Query query, long actor) {
        var r =
                resolve(
                        query.applicationId(),
                        query.objectId(),
                        query.formId(),
                        query.bindingId(),
                        actor);
        var model = records.model(query.applicationId(), r.targetId(), actor);
        var rows = new ArrayList<Aggregate>();
        boolean truncated = false;
        if (query.selectedId() != null && !query.selectedId().isBlank()) {
            rows.add(records.get(query.applicationId(), r.targetId(), query.selectedId(), actor));
        } else if (query.search() != null) {
            var page =
                    records.page(
                            new Query(
                                    query.applicationId(),
                                    r.targetId(),
                                    1,
                                    30,
                                    query.search(),
                                    Map.of(),
                                    null,
                                    false),
                            actor);
            for (var row : page.getList())
                rows.add(records.get(query.applicationId(), r.targetId(), row.id(), actor));
            truncated = page.getTotal() > rows.size();
        } else if (query.recordId() != null) {
            var parent =
                    records.get(query.applicationId(), query.objectId(), query.recordId(), actor);
            if (r.incoming()) {
                var page =
                        records.page(
                                new Query(
                                        query.applicationId(),
                                        r.targetId(),
                                        1,
                                        100,
                                        null,
                                        Map.of(r.relation().fieldId(), query.recordId()),
                                        null,
                                        false),
                                actor);
                for (var row : page.getList())
                    rows.add(records.get(query.applicationId(), r.targetId(), row.id(), actor));
                truncated = page.getTotal() > rows.size();
            } else {
                if (!parent.record().permissions().readFields().contains(r.relation().fieldId()))
                    throw invalid("没有关联字段的查看权限");
                var id = parent.record().values().get(r.relation().fieldId());
                if (id != null)
                    rows.add(
                            records.get(query.applicationId(), r.targetId(), id.toString(), actor));
            }
        }
        return new RelatedForms.Result(
                model,
                r.form(),
                rows,
                r.incoming() && !RelationTypeEnum.ONE_TO_ONE.matches(r.relation().kind()),
                r.relation().fieldId(),
                Boolean.TRUE.equals(r.relation().required()),
                truncated);
    }

    private void direct(String object, boolean creating) {
        var rule = BusinessHandlingPolicies.rule(objects.getPublished(object), creating);
        if (rule != null && !HandlingModeEnum.DIRECT.matches(rule.mode()))
            throw invalid("关联联合保存目前仅支持直接生效，请使用不需要审批的对象");
    }

    public SelectionFields.Result selection(RelatedForms.Selection request, long actor) {
        var q = request.context();
        var r = resolve(q.applicationId(), q.objectId(), q.formId(), q.bindingId(), actor);
        if (!Objects.equals(q.applicationId(), request.query().applicationId())
                || !Objects.equals(r.targetId(), request.query().objectId())
                || !Objects.equals(r.binding().formId(), request.query().formId()))
            throw invalid("关联候选字段不属于此录入区域");
        return records.selection(request.query(), actor);
    }

    public Map<String, Object> fill(RelatedForms.Fill request, long actor) {
        var q = request.context();
        var r = resolve(q.applicationId(), q.objectId(), q.formId(), q.bindingId(), actor);
        if (!Objects.equals(q.applicationId(), request.query().applicationId())
                || !Objects.equals(r.targetId(), request.query().objectId())
                || !Objects.equals(r.binding().formId(), request.query().formId()))
            throw invalid("关联带入字段不属于此录入区域");
        return records.formFill(request.query(), actor);
    }

    /** 求值必须来自发布表单声明的关联区域；实际记录仍由公共规则引擎校验读取范围。 */
    public FieldRules.Evaluation fieldRules(RelatedForms.FieldRules request, long actor) {
        if (request == null || request.context() == null || request.query() == null)
            throw invalid("缺少关联表单求值上下文");
        RelatedForms.Query context = request.context();
        Resolved resolved =
                resolve(
                        context.applicationId(),
                        context.objectId(),
                        context.formId(),
                        context.bindingId(),
                        actor);
        FieldRules.EvaluateQuery query = request.query();
        if (!Objects.equals(context.applicationId(), query.applicationId())
                || !Objects.equals(resolved.targetId(), query.objectId())
                || !Objects.equals(resolved.binding().formId(), query.formId()))
            throw invalid("求值字段不属于此关联录入区域");
        if (context.recordId() != null)
            records.get(context.applicationId(), context.objectId(), context.recordId(), actor);
        return records.evaluateRules(query, actor);
    }

    private Save rowCommand(
            Save parent, Resolved r, RelatedForms.Row row, Map<String, Object> values) {
        return new Save(
                parent.applicationId(),
                r.targetId(),
                row.id(),
                row.expectedRevision(),
                values,
                row.details(),
                null,
                null,
                r.binding().formId(),
                null,
                null);
    }

    /** 同一数据库事务内保存全部独立记录与关联，任何权限、修订或校验失败均整体回滚。 */
    @Transactional(rollbackFor = Exception.class)
    public Aggregate save(Save command, long actor) {
        automations.lock(null);
        linkageSync.lock(null);
        if (command.requestKey() == null
                || command.formId() == null
                || command.context() != null
                || command.actionCode() != null) throw invalid("关联联合保存需要发布表单和请求标识，暂不支持页面上下文或状态动作");
        records.model(command.applicationId(), command.objectId(), actor);
        var previous = receipts.successful(command, actor);
        if (previous != null)
            return records.receipt(
                            command.applicationId(),
                            command.objectId(),
                            command.requestKey(),
                            actor)
                    .result();
        long checkpoint = history.checkpoint();
        direct(command.objectId(), command.id() == null);
        if (command.relatedRecords().size() > 10) throw invalid("每张表单最多联合保存 10 个关联区");
        var resolved = new LinkedHashMap<String, Resolved>();
        int count = 0;
        for (var entry : command.relatedRecords().entrySet()) {
            var r =
                    resolve(
                            command.applicationId(),
                            command.objectId(),
                            command.formId(),
                            entry.getKey(),
                            actor);
            if (entry.getValue() == null) throw invalid("缺少关联记录列表");
            if (entry.getValue().stream().anyMatch(Objects::isNull)) throw invalid("关联记录不能为 null");
            count += entry.getValue().size();
            if (count > 100) throw invalid("一次最多联合保存 100 条关联记录");
            if ((!r.incoming() || RelationTypeEnum.ONE_TO_ONE.matches(r.relation().kind()))
                    && entry.getValue().stream().filter(v -> !v.unlink()).count() > 1)
                throw invalid("此关系只能关联一条记录");
            var ids = new HashSet<String>();
            for (var row : entry.getValue()) {
                if (row == null || row.values() == null) throw invalid("关联记录缺少字段内容");
                if (row.id() != null && !ids.add(row.id())) throw invalid("同一关联记录不能重复提交");
                boolean writes =
                        r.incoming()
                                || row.id() == null
                                || !row.values().isEmpty()
                                || row.details() != null && !row.details().isEmpty();
                if (writes) direct(r.targetId(), row.id() == null);
            }
            resolved.put(entry.getKey(), r);
        }
        var values = new LinkedHashMap<>(command.values());
        for (var entry : command.relatedRecords().entrySet()) {
            var r = resolved.get(entry.getKey());
            if (r.incoming()) continue;
            String targetId = null;
            for (var row : entry.getValue()) {
                if (row.unlink()) continue;
                // 只选择已有记录而不修改字段不要求 UPDATE 权限。
                targetId =
                        row.id() != null
                                        && row.values().isEmpty()
                                        && (row.details() == null || row.details().isEmpty())
                                ? records.get(
                                                command.applicationId(),
                                                r.targetId(),
                                                row.id(),
                                                actor)
                                        .record()
                                        .id()
                                : records.save(rowCommand(command, r, row, row.values()), actor)
                                        .record()
                                        .id();
            }
            values.put(r.relation().fieldId(), targetId);
        }
        var mainCommand =
                new Save(
                        command.applicationId(),
                        command.objectId(),
                        command.id(),
                        command.expectedRevision(),
                        values,
                        command.details(),
                        command.relations(),
                        null,
                        command.formId(),
                        null,
                        null);
        var generatedFields = new HashSet<String>();
        resolved.values().stream()
                .filter(r -> !r.incoming())
                .forEach(r -> generatedFields.add(r.relation().fieldId()));
        var main =
                RelatedWriteScope.execute(
                        mainCommand, generatedFields, () -> records.save(mainCommand, actor));
        for (var entry : command.relatedRecords().entrySet()) {
            var r = resolved.get(entry.getKey());
            if (!r.incoming()) continue;
            for (var row : entry.getValue()) {
                if (row.id() == null && row.unlink()) continue;
                if (row.id() != null) {
                    var existing =
                            records.get(command.applicationId(), r.targetId(), row.id(), actor);
                    if (!existing.record()
                            .permissions()
                            .readFields()
                            .contains(r.relation().fieldId())) throw invalid("没有关联字段的查看权限");
                    var owner = existing.record().values().get(r.relation().fieldId());
                    if (owner != null && !Objects.equals(owner.toString(), main.record().id()))
                        throw invalid("关联记录已属于其他主记录，不能在此改变归属");
                    if (row.unlink()
                            && !Objects.equals(Objects.toString(owner, null), main.record().id()))
                        throw invalid("只能解除当前主记录的关联");
                }
                if (row.unlink() && Boolean.TRUE.equals(r.relation().required()))
                    throw invalid("此关联必填，不能解除");
                var childValues =
                        new LinkedHashMap<>(row.unlink() ? Map.<String, Object>of() : row.values());
                childValues.put(r.relation().fieldId(), row.unlink() ? null : main.record().id());
                var childCommand = rowCommand(command, r, row, childValues);
                RelatedWriteScope.execute(
                        childCommand,
                        Set.of(r.relation().fieldId()),
                        () -> records.save(childCommand, actor));
            }
        }
        var result =
                records.get(command.applicationId(), command.objectId(), main.record().id(), actor);
        var definition = objects.getPublished(command.objectId());
        var operationId =
                history.finishRelated(
                        definition,
                        command.applicationId(),
                        result.record().id(),
                        checkpoint,
                        actor);
        receipts.append(command, result, definition, operationId, actor);
        return result;
    }
}
