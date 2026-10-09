package com.richuang.os.module.bpm.framework.flowable.core.candidate.strategy.user;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import com.richuang.os.module.bpm.framework.flowable.core.candidate.BpmTaskCandidateStrategy;
import com.richuang.os.module.bpm.framework.flowable.core.enums.BpmTaskCandidateStrategyEnum;
import com.richuang.os.module.bpm.framework.flowable.core.util.FlowableUtils;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.engine.delegate.DelegateExecution;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 项目负责人审批策略。
 *
 * <p>该策略不在 BPMN 模型中保存具体用户。业务模块发起流程时先解析项目负责人并写入审批人快照，
 * 任务创建阶段只读取快照，从而保证项目负责人变更不会影响已发起流程。</p>
 */
@Component
public class BpmTaskCandidateProjectOwnerStrategy implements BpmTaskCandidateStrategy {

    private static final String PROJECT_OWNER_KEY = "PROJECT_OWNER";
    /**
     * 仅用于审批预览，正式流程必须使用服务端生成的审批人快照。
     */
    private static final String PREVIEW_PROJECT_OWNER_ID = "projectOwnerId";

    @Override
    public BpmTaskCandidateStrategyEnum getStrategy() {
        return BpmTaskCandidateStrategyEnum.PROJECT_OWNER;
    }

    @Override
    public void validateParam(String param) {
        // 项目负责人由业务上下文解析，节点不需要额外参数。
    }

    @Override
    public boolean isParamRequired() {
        return false;
    }

    @Override
    public Set<Long> calculateUsersByTask(DelegateExecution execution, String param) {
        Map<String, List<Long>> snapshot = readSnapshot(execution.getVariables());
        return toUserIds(snapshot == null ? null : snapshot.get(PROJECT_OWNER_KEY),
                "流程实例(" + execution.getProcessInstanceId() + ")的项目负责人审批人快照");
    }

    @Override
    public Set<Long> calculateUsersByActivity(BpmnModel bpmnModel, String activityId, String param,
                                              Long startUserId, String processDefinitionId,
                                              Map<String, Object> processVariables) {
        Map<String, List<Long>> snapshot = readSnapshot(processVariables);
        String description = "流程定义(" + processDefinitionId + ")节点(" + activityId + ")的项目负责人审批人";
        if (snapshot != null && snapshot.get(PROJECT_OWNER_KEY) != null) {
            return toUserIds(snapshot.get(PROJECT_OWNER_KEY), description + "快照");
        }
        Object previewOwnerId = processVariables == null ? null : processVariables.get(PREVIEW_PROJECT_OWNER_ID);
        // 通用流程预览可能尚未加载业务上下文，此时不应阻断页面；正式任务创建仍由 calculateUsersByTask 强制校验快照。
        if (previewOwnerId == null || previewOwnerId.toString().isBlank()) {
            return Set.of();
        }
        return toUserIds(List.of(previewOwnerId), description);
    }

    private Map<String, List<Long>> readSnapshot(Map<String, Object> processVariables) {
        return FlowableUtils.getApproverSnapshot(processVariables);
    }

    private Set<Long> toUserIds(Collection<?> values, String description) {
        Assert.notEmpty(values, description + "不能为空");
        LinkedHashSet<Long> userIds = new LinkedHashSet<>();
        for (Object value : values) {
            if (value instanceof Number number) {
                userIds.add(number.longValue());
            } else if (value != null && !value.toString().isBlank()) {
                userIds.add(Long.valueOf(value.toString()));
            }
        }
        Assert.isTrue(CollUtil.isNotEmpty(userIds), description + "不能为空");
        return userIds;
    }
}
