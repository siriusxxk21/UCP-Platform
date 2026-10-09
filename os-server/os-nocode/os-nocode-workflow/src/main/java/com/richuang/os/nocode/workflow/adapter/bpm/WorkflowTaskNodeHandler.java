package com.richuang.os.nocode.workflow.adapter.bpm;

import com.richuang.os.module.bpm.api.task.BpmTaskCenterNodeHandler;
import com.richuang.os.module.bpm.api.task.dto.BpmTaskCenterNodeArrivalDTO;
import com.richuang.os.nocode.api.workflow.WorkflowTaskGuard;
import com.richuang.os.nocode.workflow.service.task.WorkflowTaskNodeService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** 引擎只调用任务交接，任务中心经可选契约检查来源有效性。 */
@Component
public class WorkflowTaskNodeHandler implements BpmTaskCenterNodeHandler, WorkflowTaskGuard {
    @Resource private WorkflowTaskNodeService service;

    @Override
    public String prepare(String nodeId, String configuration, long actor) {
        return service.prepare(nodeId, configuration, actor);
    }

    @Override
    public Set<String> neededFields(String configuration) {
        return service.neededFields(configuration);
    }

    @Override
    public String arrive(BpmTaskCenterNodeArrivalDTO arrival) {
        return service.arrive(arrival);
    }

    @Override
    public void requireActive(String rootId) {
        service.requireActive(rootId);
    }

    @Override
    public Map<String, String> readOnlyReasons(Collection<String> rootIds) {
        return service.readOnlyReasons(rootIds);
    }
}
