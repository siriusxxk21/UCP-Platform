package com.richuang.os.nocode.work.service.submission;

import com.richuang.os.nocode.api.work.WorkDrafts;

/** 不可变提交存储；调用方在同一事务内完成业务保存、草稿关闭与本次封存。 */
public interface WorkSubmissionService {
    /** 内部材料共享入口；必须由已授权前序事实给出源任务和实际提交人，不能供个人草稿接口调用。 */
    WorkDrafts.Submission flowMaterial(String id, String sourceTaskId, long submitter);

    /** 当前已提交的流程成果持续保护，流程终态不自动解锁。 */
    boolean protectsFlowRecord(String objectId, String recordId);

    WorkDrafts.Submission forDraft(String draftId, long actor);

    WorkDrafts.Submission findSuccessful(WorkDrafts.Submit command, long actor);

    WorkDrafts.Submission create(
            WorkDrafts.Submit command, WorkDrafts.Submission material, long actor);

    WorkDrafts.Submission get(String id, long actor);
}
