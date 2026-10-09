package com.lingan.ucp.nocode.runtime.service.work;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.framework.common.exception.ServiceException;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.work.*;
import com.lingan.ucp.nocode.application.service.published.ApplicationPublishedService;
import com.lingan.ucp.nocode.application.service.resource.ApplicationResourceValidator;
import com.lingan.ucp.nocode.enums.*;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.application.ApplicationRuntimeService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.work.service.draft.WorkDraftService;
import com.lingan.ucp.nocode.work.service.submission.WorkSubmissionService;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.*;

/** 固定表单版本只决定配置；人员、记录和字段授权仍实时计算。 */
@Service
public class WorkDraftQueryServiceImpl implements WorkDraftQueryService {
    @Resource private WorkDraftService drafts;
    @Resource private WorkSubmissionService submissions;
    @Resource private WorkFormService workForms;
    @Resource private ApplicationPublishedService published;
    @Resource private ApplicationRuntimeService runtime;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private ApplicationResourceValidator validator;

    @Resource(name = "nocodeRecordService")
    private RecordService records;

    @Override
    public WorkDraftViews.Page page(WorkDraftViews.Query query, long actor) {
        if (query == null) throw invalid("缺少草稿列表参数");
        policy.requireEntry(query.applicationId(), actor);
        published.getCurrent(query.applicationId());
        var candidates = drafts.candidates(query, actor);
        var items = new ArrayList<WorkDraftViews.Item>();
        int count = Math.min(query.limit(), candidates.size());
        for (int i = 0; i < count; i++) {
            try {
                var context = context(candidates.get(i).id(), actor);
                var draft = context.draft();
                if (!query.state().equals(draft.state())) continue;
                items.add(
                        new WorkDraftViews.Item(
                                draft.id(),
                                draft.state(),
                                context.formName(),
                                context.model().object().objectName(),
                                draft.resource().applicationVersion(),
                                draft.recordId(),
                                draft.updatedAt()));
            } catch (ServiceException | AccessDeniedException unavailable) {
                // 已撤权或资源不可用的条目不输出名称／值；基础设施异常仍向上抛出。
            }
        }
        var last = count == 0 ? null : candidates.get(count - 1);
        var next =
                candidates.size() > count && last != null
                        ? new WorkDraftViews.Cursor(last.createdAt().toString(), last.id())
                        : null;
        return new WorkDraftViews.Page(items, next);
    }

    @Override
    public WorkDraftViews.Context context(String draftId, long actor, WorkSourceRef source) {
        // 读工作区不组合写事务。可写权限检查失败可以降级只读，不污染后续读取事务。
        var draft = workForms.getDraft(draftId, actor, source);
        var material =
                WorkDraftStateEnum.SUBMITTED.matches(draft.state())
                        ? submissions.forDraft(draftId, actor)
                        : null;
        if (WorkDraftStateEnum.SUBMITTED.matches(draft.state()) && material == null)
            throw invalid("已提交草稿缺少材料，请联系管理员核查");
        var submission =
                material == null ? null : workForms.getSubmission(material.id(), actor, source);
        return published.withVersion(
                draft.resource(),
                () -> {
                    var release = runtime.application(draft.resource().applicationId(), actor);
                    var resource =
                            release.definition().resources().stream()
                                    .filter(r -> r.id().equals(draft.resource().resourceId()))
                                    .findFirst()
                                    .orElseThrow(() -> invalid("当前无权读取草稿表单"));
                    var form = validator.decode(resource.config(), ApplicationUi.Form.class);
                    var model =
                            records.model(
                                    draft.resource().applicationId(), draft.objectId(), actor);
                    var authorizedRecordId =
                            submission == null ? draft.recordId() : submission.recordId();
                    var aggregate =
                            authorizedRecordId == null
                                    ? null
                                    : records.get(
                                            draft.resource().applicationId(),
                                            draft.objectId(),
                                            authorizedRecordId,
                                            actor);
                    var row = aggregate == null ? null : aggregate.record();
                    var caps = row == null ? model.permissions() : row.permissions();
                    boolean writable = false;
                    String reason = submission == null ? null : "本次材料已提交，不能修改";
                    if (submission == null) {
                        try {
                            caps =
                                    records.workWriteCapabilities(
                                            draft.resource().applicationId(),
                                            draft.objectId(),
                                            draft.recordId(),
                                            actor);
                            writable =
                                    model.writable()
                                            && (form.options() == null
                                                    || !Boolean.TRUE.equals(
                                                            form.options().readOnly()));
                            if (!writable) reason = "当前表单或业务对象为只读";
                        } catch (ServiceException | AccessDeniedException denied) {
                            reason = denied.getMessage();
                        }
                    }
                    var visible =
                            row == null
                                    ? model.permissions().readFields()
                                    : row.permissions().readFields();
                    var projected =
                            new ApplicationUi.Form(
                                    form.objectId(),
                                    readableNodes(form.nodes(), visible, caps.readDetails()),
                                    form.detailIds().stream()
                                            .filter(caps.readDetails()::contains)
                                            .toList(),
                                    form.options(),
                                    com.lingan.ucp.nocode.metadata.service.form.DetailForms.visible(
                                            form, caps.readDetails()));
                    var restricted =
                            new ApplicationAuthorization.Capabilities(
                                    caps.actions(),
                                    caps.readFields(),
                                    writable ? caps.writeFields() : Set.of(),
                                    caps.readDetails(),
                                    caps.writeDetails(),
                                    caps.readRelations(),
                                    caps.writeRelations());
                    var renderModel =
                            new ApplicationRecords.Model(
                                    model.object(),
                                    writable,
                                    model.generatedKey(),
                                    model.keyFieldId(),
                                    model.keyType(),
                                    model.details(),
                                    restricted);
                    return new WorkDraftViews.Context(
                            draft,
                            submission,
                            resource.name(),
                            projected,
                            renderModel,
                            submission == null ? row : null,
                            writable,
                            reason,
                            submission == null
                                    && row != null
                                    && !Objects.equals(draft.baseRecordRevision(), row.revision()),
                            submission == null && aggregate != null
                                    ? aggregate.details()
                                    : Map.of());
                });
    }

    @Override
    public SelectionFields.Result selection(
            WorkDraftViews.Selection request, long actor, WorkSourceRef source) {
        if (request == null || request.query() == null) throw invalid("缺少草稿候选查询");
        var context = context(request.draftId(), actor, source);
        var draft = context.draft();
        var q = request.query();
        if (!Objects.equals(q.applicationId(), draft.resource().applicationId())
                || !Objects.equals(q.objectId(), draft.objectId())
                || q.detailId() == null
                        && (q.detailRecordId() != null
                                || !SelectionFields.presentations(context.form().nodes())
                                        .containsKey(q.fieldId()))
                || q.detailId() != null
                        && (!context.form().detailIds().contains(q.detailId())
                                || context.model().object().details().stream()
                                        .noneMatch(
                                                d ->
                                                        d.id().equals(q.detailId())
                                                                && d.fields().stream()
                                                                        .anyMatch(
                                                                                f ->
                                                                                        f.id().equals(
                                                                                                        q
                                                                                                                .fieldId())))))
            throw invalid("候选字段不属于当前工作草稿");
        var result =
                published.withVersion(
                        draft.resource(),
                        () ->
                                records.selection(
                                        new SelectionFields.Query(
                                                draft.resource().applicationId(),
                                                draft.objectId(),
                                                q.detailId(),
                                                q.fieldId(),
                                                q.search(),
                                                q.pageNo(),
                                                q.pageSize(),
                                                q.selected(),
                                                context.submission() == null
                                                        ? draft.recordId()
                                                        : context.submission().recordId(),
                                                draft.resource().resourceId(),
                                                q.formValues(),
                                                context.submission() == null
                                                        && draft.recordId() == null
                                                        && context.writable(),
                                                q.detailRecordId()),
                                        actor));
        // 恢复的是已保存输入，不再重新应用新建默认值（尤其是动态组织默认值）。
        return new SelectionFields.Result(
                result.options(), result.total(), result.selected(), result.tree(), null, null);
    }

    private List<ApplicationUi.Node> readableNodes(
            List<ApplicationUi.Node> nodes, Set<String> fields, Set<String> details) {
        if (nodes == null) return List.of();
        return nodes.stream()
                .filter(n -> n.fieldId() == null || fields.contains(n.fieldId()))
                .filter(n -> n.detail() == null || details.contains(n.detail().detailId()))
                .map(
                        n ->
                                new ApplicationUi.Node(
                                        n.id(),
                                        n.type(),
                                        n.fieldId(),
                                        n.resourceId(),
                                        n.text(),
                                        n.span(),
                                        readableNodes(n.children(), fields, details),
                                        n.binding(),
                                        n.presentation(),
                                        n.style(),
                                        n.display(),
                                        n.action(),
                                        n.detail()))
                .toList();
    }
}
