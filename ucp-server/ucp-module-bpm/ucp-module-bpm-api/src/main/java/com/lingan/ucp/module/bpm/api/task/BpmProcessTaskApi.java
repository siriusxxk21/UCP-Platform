package com.lingan.ucp.module.bpm.api.task;

import com.lingan.ucp.module.bpm.api.task.dto.BpmBusinessTaskCompleteReqDTO;
import com.lingan.ucp.module.bpm.api.task.dto.BpmBusinessTaskDTO;

import jakarta.validation.constraints.NotEmpty;

/** 提供给业务模块的流程任务操作接口。 */
public interface BpmProcessTaskApi {

    /** 只返回当前操作者已分配且可操作的任务，不接受客户端自报来源或资源。 */
    BpmBusinessTaskDTO getAssignedTask(long actor, String taskId);

    /** 材料阅读资格与实际前序任务事实，不创建草稿、不绑定来源、不推进流程。 */
    com.lingan.ucp.module.bpm.api.task.dto.BpmMaterialReviewContextDTO materialReviewContext(
            long actor, String processInstanceId, String taskId);

    /** 与调用者现有事务合并；业务来源须先写入材料，任何校验／引擎失败均使整次提交回滚。 */
    void completeBusinessTask(long actor, BpmBusinessTaskCompleteReqDTO command);

    void triggerTask(
            @NotEmpty(message = "流程实例的编号不能为空") String processInstanceId,
            @NotEmpty(message = "任务 Key 不能为空") String taskDefineKey);
}
