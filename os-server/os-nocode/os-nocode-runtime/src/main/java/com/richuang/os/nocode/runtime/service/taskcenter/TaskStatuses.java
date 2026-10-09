package com.richuang.os.nocode.runtime.service.taskcenter;

import com.richuang.os.nocode.api.TaskCenter.State;
import com.richuang.os.nocode.runtime.dal.dataobject.TaskInstanceDO;

import java.util.List;

/** 列表共用的任务组状态投影；展示聚合不改写根任务或推进任务流转。 */
final class TaskStatuses {
    private TaskStatuses() {}

    /** 未开始的总任务已有下级开工时展示进行中，其他状态保留总任务本身的事实。 */
    static String groupStatus(TaskInstanceDO root, List<TaskInstanceDO> nodes) {
        if (State.PENDING.name().equals(root.getStatus())
                && nodes.stream().anyMatch(node -> node.getActualStart() != null))
            return State.RUNNING.name();
        return root.getStatus();
    }
}
