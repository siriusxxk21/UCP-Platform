package com.richuang.os.module.bpm.service.task;

import static com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnModelConstants.TASK_CENTER_BINDING_ID;
import static com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnVariableConstants.PROCESS_INSTANCE_VARIABLE_STATUS;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richuang.os.module.bpm.api.task.BpmTaskCenterNodeApi.State;
import com.richuang.os.module.bpm.api.task.dto.BpmTaskCenterNodeExecutionDTO;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import com.richuang.os.module.bpm.dal.dataobject.task.BpmProcessInstanceCopyDO;
import com.richuang.os.module.bpm.dal.mysql.task.BpmProcessInstanceCopyMapper;
import com.richuang.os.module.bpm.enums.definition.BpmSimpleModelNodeTypeEnum;
import com.richuang.os.module.bpm.enums.task.BpmProcessInstanceStatusEnum;
import com.richuang.os.module.bpm.framework.flowable.core.util.BpmnModelUtils;
import com.richuang.os.module.bpm.framework.flowable.core.util.FlowableUtils;
import com.richuang.os.module.bpm.service.definition.BpmProcessDefinitionService;
import com.richuang.os.module.system.api.permission.PermissionApi;

import jakarta.annotation.Resource;

import org.flowable.bpmn.model.FlowElement;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.runtime.Execution;
import org.flowable.engine.runtime.ProcessInstance;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 精确匹配流程执行和本轮绑定，沿用引擎事务与乐观锁，重复通知不会误推进后续节点。 */
@Service
public class BpmTaskCenterNodeServiceImpl implements BpmTaskCenterNodeService {
    @Resource private RuntimeService runtimeService;
    @Resource private RepositoryService repositoryService;
    @Resource private HistoryService historyService;
    @Resource private PermissionApi permissionApi;
    @Resource private BpmProcessDefinitionService definitions;
    @Resource private BpmProcessInstanceCopyMapper copies;

    @Override
    public State inspect(BpmTaskCenterNodeExecutionDTO command) {
        if (command == null
                || blank(command.executionId())
                || blank(command.processInstanceId())
                || blank(command.nodeId())
                || blank(command.bindingId())) return State.INACTIVE;
        Execution execution =
                runtimeService
                        .createExecutionQuery()
                        .executionId(command.executionId())
                        .singleResult();
        if (execution == null
                || !sameTenant(execution.getTenantId())
                || !Objects.equals(command.processInstanceId(), execution.getProcessInstanceId())
                || !Objects.equals(command.nodeId(), execution.getActivityId()))
            return State.INACTIVE;
        ProcessInstance process =
                runtimeService
                        .createProcessInstanceQuery()
                        .processInstanceId(command.processInstanceId())
                        .singleResult();
        if (process == null || process.isEnded() || !sameTenant(process.getTenantId()))
            return State.INACTIVE;
        Object binding =
                runtimeService.getVariableLocal(command.executionId(), TASK_CENTER_BINDING_ID);
        if (!Objects.equals(command.bindingId(), binding)) return State.INACTIVE;
        FlowElement node =
                BpmnModelUtils.getFlowElementById(
                        repositoryService.getBpmnModel(process.getProcessDefinitionId()),
                        command.nodeId());
        if (!(node instanceof ReceiveTask)
                || !Objects.equals(
                        BpmnModelUtils.parseNodeType(node),
                        BpmSimpleModelNodeTypeEnum.TASK_CENTER_NODE.getType()))
            return State.INACTIVE;
        if (process.isSuspended() || execution.isSuspended()) return State.SUSPENDED;
        Object status =
                runtimeService.getVariable(
                        command.processInstanceId(), PROCESS_INSTANCE_VARIABLE_STATUS);
        if (!Objects.equals(BpmProcessInstanceStatusEnum.RUNNING.getStatus(), status))
            return State.INACTIVE;
        return State.WAITING;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean advance(BpmTaskCenterNodeExecutionDTO command) {
        if (inspect(command) != State.WAITING) return false;
        // 与业务方更新绑定记录处于同一事务。并发完成／取消由 Flowable 版本锁决出，失败由桥接重试。
        runtimeService.trigger(command.executionId());
        return true;
    }

    @Override
    public boolean canViewProcess(long actor, String processInstanceId) {
        if (actor <= 0 || blank(processInstanceId)) return false;
        HistoricProcessInstance process =
                historyService
                        .createHistoricProcessInstanceQuery()
                        .processInstanceId(processInstanceId)
                        .singleResult();
        if (process == null || !sameTenant(process.getTenantId())) return false;
        if (permissionApi.hasAnyPermissions(actor, "bpm:process-instance:manager-query"))
            return true;
        // 仅有通用查询菜单权限并不足以查看别人的流程。
        if (!permissionApi.hasAnyPermissions(actor, "bpm:process-instance:query")) return false;
        if (Objects.equals(Long.toString(actor), process.getStartUserId())) return true;
        BpmProcessDefinitionInfoDO definition =
                definitions.getProcessDefinitionInfo(process.getProcessDefinitionId());
        if (definition != null
                && definition.getManagerUserIds() != null
                && definition.getManagerUserIds().contains(actor)) return true;
        if (historyService
                        .createHistoricTaskInstanceQuery()
                        .processInstanceId(processInstanceId)
                        .taskAssignee(Long.toString(actor))
                        .count()
                > 0) return true;
        return copies.selectCount(
                        new LambdaQueryWrapper<BpmProcessInstanceCopyDO>()
                                .eq(
                                        BpmProcessInstanceCopyDO::getProcessInstanceId,
                                        processInstanceId)
                                .eq(BpmProcessInstanceCopyDO::getUserId, actor))
                > 0;
    }

    /** 无租户引擎使用空值，任务交接表使用 0；只在本适配层统一默认范围，不混淆正式租户。 */
    private boolean sameTenant(String engineTenantId) {
        String contextTenantId = FlowableUtils.getTenantId();
        return Objects.equals(
                blank(contextTenantId) ? "0" : contextTenantId,
                blank(engineTenantId) ? "0" : engineTenantId);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
