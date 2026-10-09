package com.richuang.os.nocode.work.service.draft;

import com.richuang.os.nocode.api.work.WorkDrafts;
import com.richuang.os.nocode.api.work.WorkSourceRef;

/** 草稿存储服务；业务授权由来源服务校验，本层始终再校验个人归属和修订。 */
public interface WorkDraftService {
    /** 来源服务先核验当前任务资格；只返回该办理人尚未提交的草稿标识。 */
    String currentSourceDraft(WorkSourceRef source, long actor);

    java.util.List<com.richuang.os.nocode.api.work.WorkDraftViews.SourceCandidate>
            taskEntryCandidates(
                    com.richuang.os.nocode.api.work.WorkDraftViews.SourceQuery query, long actor);

    java.util.List<com.richuang.os.nocode.api.work.WorkDraftViews.Candidate> candidates(
            com.richuang.os.nocode.api.work.WorkDraftViews.Query query, long actor);

    /** 仅扫描本人流程来源，调用方必须重新验证任务与固定资源；不会创建草稿。 */
    java.util.List<com.richuang.os.nocode.api.work.WorkDraftViews.SourceCandidate>
            flowTaskCandidates(
                    com.richuang.os.nocode.api.workflow.FlowTaskWorkViews.Query query, long actor);

    default WorkDrafts.Draft save(WorkDrafts.Save command, long actor) {
        return save(command, actor, WorkSourceRef.PERSONAL);
    }

    WorkDrafts.Draft save(WorkDrafts.Save command, long actor, WorkSourceRef source);

    default WorkDrafts.Draft get(String id, long actor) {
        return get(id, actor, WorkSourceRef.PERSONAL);
    }

    WorkDrafts.Draft get(String id, long actor, WorkSourceRef source);

    default void markSubmitted(String id, int expectedRevision, long actor) {
        markSubmitted(id, expectedRevision, actor, WorkSourceRef.PERSONAL);
    }

    void markSubmitted(String id, int expectedRevision, long actor, WorkSourceRef source);
}
