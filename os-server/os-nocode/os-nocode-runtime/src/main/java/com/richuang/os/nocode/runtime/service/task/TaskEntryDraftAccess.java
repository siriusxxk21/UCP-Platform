package com.richuang.os.nocode.runtime.service.task;

import static com.richuang.os.nocode.api.NocodeErrorCodes.*;

import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.work.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.enums.*;
import com.richuang.os.nocode.runtime.service.task.TaskEntryRuntimeInvocation.Resolved;
import com.richuang.os.nocode.runtime.service.work.WorkFormService;
import com.richuang.os.nocode.work.service.draft.WorkDraftService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 入口工作草稿的来源标识、表单引用及读取保存，沿用工作域的状态约束。 */
@Component
public class TaskEntryDraftAccess {
    @Resource private TaskEntryRuntimeInvocation invocations;
    @Resource private ApplicationService applications;
    @Resource private WorkFormService workForms;
    @Resource private WorkDraftService drafts;
    @Resource private DataObjectApi objects;

    String requestKey(String entryId, String key) {
        if (key == null || key.isBlank() || key.length() > 120) throw invalid("缺少有效的保存请求标识");
        return UUID.nameUUIDFromBytes((entryId + ":" + key).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    WorkSourceRef source(Resolved r) {
        return new WorkSourceRef(
                WorkSourceEnum.TASK_ENTRY,
                sourceId(
                        r.invocation().applicationId(),
                        r.resource().id(),
                        r.release().versionNo()));
    }

    String sourceId(String app, String entry, int version) {
        return UUID.nameUUIDFromBytes(
                        (app + ":" + entry + ":" + version).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    public TaskEntries.DraftPage drafts(TaskEntries.DraftQuery query, long actor) {
        if (query == null) throw invalid("缺少草稿分页参数");
        var candidates =
                drafts.taskEntryCandidates(
                        new WorkDraftViews.SourceQuery(
                                WorkDraftStateEnum.DRAFT.getCode(), query.before(), query.limit()),
                        actor);
        List<TaskEntries.DraftItem> items = new ArrayList<>();
        for (var candidate : candidates.stream().limit(query.limit()).toList()) {
            var source = new WorkSourceRef(WorkSourceEnum.TASK_ENTRY, candidate.sourceId());
            var draft = drafts.get(candidate.id(), actor, source);
            // 列表与提交并发时不把已提交草稿重新显示为待继续。
            if (!WorkDraftStateEnum.DRAFT.matches(draft.state())) continue;
            TaskEntries.Locator locator = null;
            String name = "已不可用的事项", appName = "原应用", blocked = null;
            try {
                var ref = draft.resource();
                var release = applications.published(ref.applicationId(), ref.applicationVersion());
                var resource =
                        release.definition().resources().stream()
                                .filter(
                                        r ->
                                                ApplicationResourceKindEnum.TASK_ENTRY.matches(
                                                                r.kind())
                                                        && candidate
                                                                .sourceId()
                                                                .equals(
                                                                        sourceId(
                                                                                ref.applicationId(),
                                                                                r.id(),
                                                                                ref
                                                                                        .applicationVersion())))
                                .findFirst()
                                .orElseThrow(() -> invalid("原任务入口已不可用，草稿仍被保留"));
                locator =
                        new TaskEntries.Locator(
                                ref.applicationId(), resource.id(), ref.applicationVersion());
                name = resource.name();
                appName = release.application().name();
                var current = draft(locator, actor);
                if (current == null
                        || !current.id().equals(candidate.id())
                        || !current.resource().equals(ref)) throw invalid("草稿状态或原表单配置已变化，请刷新列表");
            } catch (ServiceException unavailable) {
                blocked = unavailable.getMessage();
            }
            items.add(
                    new TaskEntries.DraftItem(
                            candidate.id(),
                            locator,
                            name,
                            appName,
                            draft.updatedAt(),
                            blocked == null,
                            blocked));
        }
        var last = candidates.size() > query.limit() ? candidates.get(query.limit() - 1) : null;
        return new TaskEntries.DraftPage(
                items,
                last == null
                        ? null
                        : new WorkDraftViews.Cursor(last.createdAt().toString(), last.id()));
    }

    PublishedResourceRef formRef(Resolved r) {
        if (r.config().formId() == null) throw invalid("当前入口没有可暂存的表单");
        return new PublishedResourceRef(
                r.invocation().applicationId(),
                r.release().versionNo(),
                r.release().checksum(),
                r.config().formId(),
                ApplicationResourceKindEnum.FORM.getCode());
    }

    public WorkDrafts.Draft draft(TaskEntries.Locator entry, long actor) {
        return invocations.execute(
                entry,
                actor,
                true,
                r -> {
                    var id = drafts.currentSourceDraft(source(r), actor);
                    return id == null ? null : workForms.getDraft(id, actor, source(r));
                });
    }

    public WorkDrafts.Draft saveDraft(TaskEntries.Save command, long actor) {
        return invocations.execute(
                command.entry(),
                actor,
                true,
                r -> {
                    var input = command.record();
                    if (input == null
                            || input.id() != null
                            || input.expectedRevision() != null
                            || input.context() != null
                            || input.actionCode() != null
                            || input.relations() != null && !input.relations().isEmpty())
                        throw invalid("入口暂存支持新增单据；已有记录、页面上下文和多对多写入请直接保存");
                    invocations.requireTarget(r, input.applicationId(), input.objectId());
                    var definition = objects.getPublished(r.config().objectId());
                    if (definition.relations().stream()
                            .anyMatch(
                                    relation ->
                                            RelationTypeEnum.MANY_TO_MANY.matches(relation.kind())))
                        throw invalid("含多对多关系的入口请直接保存，暂不支持草稿");
                    var current = drafts.currentSourceDraft(source(r), actor);
                    if (current != null
                            && (command.draft() == null || !current.equals(command.draft().id())))
                        throw invalid("此入口已有未提交草稿，请先恢复后继续暂存，避免覆盖另一个窗口的输入");
                    return workForms.saveDraft(
                            new WorkDrafts.Save(
                                    command.draft() == null ? null : command.draft().id(),
                                    command.draft() == null ? null : command.draft().revision(),
                                    formRef(r),
                                    r.config().objectId(),
                                    null,
                                    null,
                                    input.values(),
                                    input.details(),
                                    input.relatedRecords()),
                            actor,
                            source(r));
                });
    }

    void requireRelatedDraft(ApplicationRecords.Save input, WorkDrafts.Draft draft) {
        if (!draft.relatedRecords().isEmpty()
                && (input.relatedRecords() == null
                        || !input.relatedRecords()
                                .keySet()
                                .containsAll(draft.relatedRecords().keySet())))
            throw invalid("请先恢复完整关联草稿再提交，不能遗漏已暂存的关联区域");
    }
}
