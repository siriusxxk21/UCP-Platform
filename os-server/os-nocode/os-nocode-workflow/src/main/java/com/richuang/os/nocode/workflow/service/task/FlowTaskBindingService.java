package com.richuang.os.nocode.workflow.service.task;

import com.richuang.os.module.bpm.api.task.dto.BpmBusinessTaskDTO;
import com.richuang.os.nocode.api.workflow.FlowTasks;
import com.richuang.os.nocode.workflow.dal.dataobject.task.FlowTaskBindingDO;

/** 来源内部绑定服务，调用方须处于同一事务并先核验引擎任务资格。 */
public interface FlowTaskBindingService {
    /** 只读材料入口；调用前必须取得 BPM 服务端已核验的前序任务事实。 */
    FlowTaskBindingDO read(String taskId);

    FlowTaskBindingDO lock(String taskId);

    BpmBusinessTaskDTO snapshot(FlowTaskBindingDO binding);

    default FlowTasks.Configuration configuration(BpmBusinessTaskDTO task) {
        return configuration(task.handler(), task.configuration());
    }

    FlowTasks.Configuration configuration(String handler, String configuration);

    FlowTaskBindingDO bind(BpmBusinessTaskDTO task, long actor);

    void match(FlowTaskBindingDO binding, BpmBusinessTaskDTO task);

    void submitted(String taskId, String material, String digest, long actor);

    String digest(FlowTasks.Submit command);
}
