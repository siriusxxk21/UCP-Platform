package com.lingan.ucp.module.bpm.api.task;

/** 审批命令在写入意见和推进引擎前重新校验本次查阅材料，不反向依赖具体业务来源。 */
public interface BpmTaskMaterialReviewGuard {
    void validate(long actor, String taskId, String materialReviewToken);

    /** 同人、连续与超时自动通过遇到需查阅材料时保留人工待办，不在提交后抛错。 */
    boolean requiresReview(long actor, String taskId);
}
