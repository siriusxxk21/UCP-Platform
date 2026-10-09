package com.lingan.ucp.module.bpm.api.task;

import com.lingan.ucp.module.bpm.api.task.dto.BpmTaskCenterNodeExecutionDTO;

/** 仅供可信任务中心桥接服务使用，不开放通用 HTTP 唤醒接口。 */
public interface BpmTaskCenterNodeApi {

    enum State {
        WAITING,
        SUSPENDED,
        INACTIVE
    }

    /** 同时检查租户、流程、节点和本轮绑定；历史或已结束执行统一返回 INACTIVE。 */
    State inspect(BpmTaskCenterNodeExecutionDTO execution);

    /** 仅唤醒仍在等待的同一绑定；重复、结束和暂停不推进，返回 false。 */
    boolean advance(BpmTaskCenterNodeExecutionDTO execution);

    /** 关联入口复用流程发起、办理、抄送和管理资格，不因任务可见扩大流程查看范围。 */
    boolean canViewProcess(long actor, String processInstanceId);
}
