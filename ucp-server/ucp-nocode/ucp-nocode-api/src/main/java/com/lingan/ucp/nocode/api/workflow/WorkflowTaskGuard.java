package com.lingan.ucp.nocode.api.workflow;

import com.lingan.ucp.framework.common.exception.ServiceException;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** 可选来源保护：任务中心不依赖流程引擎，工作流模块提供真实执行有效性校验。 */
public interface WorkflowTaskGuard {
    void requireActive(String rootId);

    /** 读取能力与写入保护同源；适配器可批量实现，兼容已有单项来源保护。 */
    default Map<String, String> readOnlyReasons(Collection<String> rootIds) {
        Map<String, String> reasons = new LinkedHashMap<>();
        for (String rootId : rootIds.stream().distinct().toList()) {
            try {
                requireActive(rootId);
            } catch (ServiceException denied) {
                reasons.put(rootId, denied.getMessage());
            }
        }
        return reasons;
    }
}
