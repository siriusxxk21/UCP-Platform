package com.richuang.os.nocode.runtime.service.taskcenter;

import com.richuang.os.nocode.api.workflow.WorkflowTaskGuard;

import jakarta.annotation.Resource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** 延迟发现来源适配器，避免 runtime 反向依赖 workflow 或形成初始化循环。 */
@Component
public class TaskWorkflowProtection {
    @Resource private ObjectProvider<WorkflowTaskGuard> guards;

    public void requireActive(String rootId) {
        guards.orderedStream().forEach(guard -> guard.requireActive(rootId));
    }

    public Map<String, String> readOnlyReasons(Collection<String> rootIds) {
        if (rootIds.isEmpty()) return Map.of();
        Map<String, String> reasons = new LinkedHashMap<>();
        guards.orderedStream()
                .forEach(guard -> guard.readOnlyReasons(rootIds).forEach(reasons::putIfAbsent));
        return reasons;
    }
}
