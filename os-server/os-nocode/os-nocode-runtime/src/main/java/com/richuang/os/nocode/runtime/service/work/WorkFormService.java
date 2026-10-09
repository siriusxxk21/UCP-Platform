package com.richuang.os.nocode.runtime.service.work;

import com.richuang.os.nocode.api.work.WorkDrafts;
import com.richuang.os.nocode.api.work.WorkSourceRef;

/**
 * 业务表单的工作草稿与提交契约。
 *
 * <p>个人、任务入口和流程来源复用本服务，来源标识由可信服务提供。草稿允许未完成输入； 提交重新校验当前授权及完整性，将业务写入、提交材料和草稿状态放在同一事务中。
 */
public interface WorkFormService {
    /** 暂存当前用户的个人来源草稿。 */
    default WorkDrafts.Draft saveDraft(WorkDrafts.Save command, long actor) {
        return saveDraft(command, actor, WorkSourceRef.PERSONAL);
    }

    /** 在指定可信来源下暂存草稿；保留原修订及来源隔离校验。 */
    WorkDrafts.Draft saveDraft(WorkDrafts.Save command, long actor, WorkSourceRef source);

    /** 读取个人来源草稿，并校验归属。 */
    default WorkDrafts.Draft getDraft(String id, long actor) {
        return getDraft(id, actor, WorkSourceRef.PERSONAL);
    }

    /** 读取指定来源的草稿，恢复时仍重新计算资源与记录权限。 */
    WorkDrafts.Draft getDraft(String id, long actor, WorkSourceRef source);

    /** 提交个人来源草稿，返回不可变的本次提交材料。 */
    default WorkDrafts.Submission submit(WorkDrafts.Submit command, long actor) {
        return submit(command, actor, WorkSourceRef.PERSONAL);
    }

    /** 提交指定来源草稿；原请求的重试沿用幂等结果，不重复写入业务。 */
    WorkDrafts.Submission submit(WorkDrafts.Submit command, long actor, WorkSourceRef source);

    /** 读取个人来源提交材料。历史配置不代表历史权限仍有效。 */
    default WorkDrafts.Submission getSubmission(String id, long actor) {
        return getSubmission(id, actor, WorkSourceRef.PERSONAL);
    }

    /** 读取指定来源材料，核对原草稿归属及当前可读范围。 */
    WorkDrafts.Submission getSubmission(String id, long actor, WorkSourceRef source);

    /** 来源完成监听再次核验原动作的实时写权、字段权限和材料归属。 */
    void validateSourceSubmission(String id, long actor, WorkSourceRef source);

    /** 任务上下文也须实时具备新增及资源访问权。 */
    void validateCreateResource(
            com.richuang.os.nocode.api.work.PublishedResourceRef resource,
            String objectId,
            long actor);
}
