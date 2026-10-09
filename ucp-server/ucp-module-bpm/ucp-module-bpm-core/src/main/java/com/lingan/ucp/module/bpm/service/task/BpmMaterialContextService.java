package com.lingan.ucp.module.bpm.service.task;

import com.lingan.ucp.module.bpm.api.task.dto.BpmMaterialReviewContextDTO;

import org.flowable.task.api.Task;

import java.util.Map;

/** BPM 所有的审阅资格、已执行路径与流程表单材料事实。 */
public interface BpmMaterialContextService {
    BpmMaterialReviewContextDTO context(long actor, String processInstanceId, String taskId);

    void sealForm(Task task, Map<String, Object> variables);
}
