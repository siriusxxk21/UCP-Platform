package com.richuang.os.nocode.workflow.service.task;

import com.richuang.os.module.bpm.api.task.dto.BpmTaskCenterNodeArrivalDTO;
import com.richuang.os.nocode.api.workflow.WorkflowTaskNodes.View;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 流程节点与真实任务组的可靠交接，不处理任务内部执行及验收规则。 */
public interface WorkflowTaskNodeService {
    String prepare(String nodeId, String configuration, long actor);

    Set<String> neededFields(String configuration);

    String arrive(BpmTaskCenterNodeArrivalDTO arrival);

    void reconcile();

    View retry(String executionId, long actor);

    List<View> process(String processInstanceId, long actor);

    View source(String taskId, long actor);

    void requireActive(String rootId);

    Map<String, String> readOnlyReasons(Collection<String> rootIds);
}
