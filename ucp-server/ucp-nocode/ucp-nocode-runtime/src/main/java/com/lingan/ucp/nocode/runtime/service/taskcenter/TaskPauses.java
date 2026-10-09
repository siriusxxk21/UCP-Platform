package com.lingan.ucp.nocode.runtime.service.taskcenter;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import com.lingan.ucp.nocode.api.TaskCenter.State;
import com.lingan.ucp.nocode.runtime.dal.dataobject.TaskInstanceDO;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 暂停只存于被操作节点；祖先门控不覆盖下级状态，因此恢复不会误恢复独立暂停的任务。 */
public final class TaskPauses {
    private TaskPauses() {}

    /** 返回最外层暂停节点；已结束节点保留完成或取消事实，不显示继承暂停。 */
    public static String pausedBy(TaskInstanceDO task, List<TaskInstanceDO> nodes) {
        if (State.COMPLETED.name().equals(task.getStatus())
                || State.CANCELLED.name().equals(task.getStatus())) return null;
        Map<String, TaskInstanceDO> index = new HashMap<>();
        nodes.stream()
                .filter(n -> task.getRootId().equals(n.getRootId()))
                .forEach(n -> index.put(n.getId(), n));
        String paused = null;
        TaskInstanceDO current = task;
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current.getId())) {
            if (State.PAUSED.name().equals(current.getStatus())) paused = current.getId();
            current = index.get(current.getParentId());
        }
        return paused;
    }

    /** 提示不泄露不可见上级的任务名称或人员资料。 */
    public static String reason(TaskInstanceDO task, List<TaskInstanceDO> nodes) {
        String paused = pausedBy(task, nodes);
        return paused == null
                ? null
                : task.getId().equals(paused) ? "任务已暂停，请恢复后继续" : "上级任务已暂停，请先恢复上级任务";
    }

    public static void requireActive(TaskInstanceDO task, List<TaskInstanceDO> nodes) {
        String reason = reason(task, nodes);
        if (reason != null) throw invalid(reason);
    }
}
