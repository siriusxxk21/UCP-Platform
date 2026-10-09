package com.lingan.ucp.module.bpm.service.task;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.lingan.ucp.module.bpm.enums.ErrorCodeConstants.*;

import com.lingan.ucp.module.bpm.api.task.BpmBusinessTaskBinding;
import com.lingan.ucp.module.bpm.api.task.BpmTaskCompletionGuard;
import com.lingan.ucp.module.bpm.api.task.dto.BpmBusinessTaskCompleteReqDTO;
import com.lingan.ucp.module.bpm.api.task.dto.BpmBusinessTaskDTO;
import com.lingan.ucp.module.bpm.controller.admin.task.vo.task.BpmTaskApproveReqVO;

import jakarta.annotation.Resource;

import org.flowable.bpmn.model.UserTask;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.TaskService;
import org.flowable.task.api.DelegationState;
import org.flowable.task.api.Task;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

import javax.sql.DataSource;

/** 托管状态来自不可变部署模型，完成证明只存在于当前同步调用及事务，不能通过流程变量伪造。 */
@Service
public class BpmBusinessTaskServiceImpl implements BpmBusinessTaskService {
    @Resource @Lazy private RepositoryService repositoryService;
    @Resource @Lazy private TaskService taskService;
    @Resource @Lazy private BpmTaskService bpmTaskService;
    @Resource private ObjectProvider<BpmTaskCompletionGuard> guards;
    @Resource private DataSource dataSource;

    private final ThreadLocal<Completion> completion = new ThreadLocal<>();

    @Override
    public BpmBusinessTaskDTO getAssignedTask(long actor, String taskId) {
        return snapshot(requireAssigned(actor, taskId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void complete(long actor, BpmBusinessTaskCompleteReqDTO command) {
        if (command == null || command.submissionId() == null || command.submissionId().isBlank())
            throw exception(TASK_BUSINESS_COMPLETION_REQUIRED);
        var task = requireAssigned(actor, command.taskId());
        var view = snapshot(task);
        if (view.handler() == null) throw exception(TASK_BUSINESS_BINDING_INVALID);
        // 首批只开放明确分配的直接人工任务；委派、加签等动作另行接入其材料和责任语义。
        if (task.getDelegationState() == DelegationState.PENDING
                || task.getParentTaskId() != null
                || task.getScopeType() != null) throw exception(TASK_BUSINESS_ACTION_UNSUPPORTED);
        var transactionResource = TransactionSynchronizationManager.getResource(dataSource);
        if (completion.get() != null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || transactionResource == null) throw exception(TASK_BUSINESS_COMPLETION_REQUIRED);
        completion.set(new Completion(actor, view, command, transactionResource));
        try {
            validateCompletion(task);
            var request = new BpmTaskApproveReqVO();
            request.setId(task.getId());
            request.setReason(command.reason());
            request.setVariables(command.variables());
            // 复用底座审批规则；跨模块调用者无需依赖该 Web VO。
            bpmTaskService.approveTask(actor, request);
        } finally {
            completion.remove();
        }
    }

    @Override
    public void validateCompletion(Task task) {
        var view = snapshot(task);
        if (view.handler() == null) return; // 没有托管标记的历史独立流程保持原行为。
        var current = completion.get();
        if (current == null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || current.transactionResource()
                        != TransactionSynchronizationManager.getResource(dataSource)
                || !current.task().equals(view)
                || !Objects.equals(view.assignee(), Long.toString(current.actor())))
            throw exception(TASK_BUSINESS_COMPLETION_REQUIRED);
        var matching = guards.stream().filter(g -> view.handler().equals(g.handler())).toList();
        if (matching.size() != 1) throw exception(TASK_BUSINESS_GUARD_UNAVAILABLE);
        matching.getFirst().validate(view, current.command(), current.actor());
    }

    private Task requireAssigned(long actor, String taskId) {
        if (actor <= 0 || taskId == null || taskId.isBlank())
            throw exception(TASK_OPERATE_FAIL_ASSIGN_NOT_SELF);
        var task = taskService.createTaskQuery().taskId(taskId).singleResult();
        if (task == null) throw exception(TASK_NOT_EXISTS);
        if (!Objects.equals(task.getAssignee(), Long.toString(actor)))
            throw exception(TASK_OPERATE_FAIL_ASSIGN_NOT_SELF);
        if (task.isSuspended()) throw exception(TASK_IS_PENDING);
        return task;
    }

    private BpmBusinessTaskDTO snapshot(Task task) {
        String handler = null;
        String configuration = null;
        if (task.getProcessDefinitionId() != null) {
            var model = repositoryService.getBpmnModel(task.getProcessDefinitionId());
            if (model == null || task.getTaskDefinitionKey() == null)
                throw exception(TASK_BUSINESS_BINDING_INVALID);
            var element = model.getMainProcess().getFlowElement(task.getTaskDefinitionKey(), true);
            if (!(element instanceof UserTask)) throw exception(TASK_BUSINESS_BINDING_INVALID);
            var values = element.getAttributes().get(BpmBusinessTaskBinding.HANDLER_ATTRIBUTE);
            if (values != null) {
                var owned =
                        values.stream()
                                .filter(
                                        a ->
                                                BpmBusinessTaskBinding.NAMESPACE.equals(
                                                        a.getNamespace()))
                                .toList();
                if (!owned.isEmpty()) {
                    if (owned.size() != 1
                            || owned.getFirst().getValue() == null
                            || !owned.getFirst().getValue().matches("[a-z][a-z0-9_]{0,63}"))
                        throw exception(TASK_BUSINESS_BINDING_INVALID);
                    handler = owned.getFirst().getValue();
                }
            }
            var configs =
                    element.getAttributes().get(BpmBusinessTaskBinding.CONFIGURATION_ATTRIBUTE);
            if (handler != null && configs != null) {
                var ownedConfigs =
                        configs.stream()
                                .filter(
                                        a ->
                                                BpmBusinessTaskBinding.NAMESPACE.equals(
                                                        a.getNamespace()))
                                .toList();
                if (ownedConfigs.size() > 1) throw exception(TASK_BUSINESS_BINDING_INVALID);
                if (!ownedConfigs.isEmpty()) configuration = ownedConfigs.getFirst().getValue();
            }
        }
        return new BpmBusinessTaskDTO(
                task.getId(),
                task.getProcessInstanceId(),
                task.getProcessDefinitionId(),
                task.getExecutionId(),
                task.getTaskDefinitionKey(),
                task.getAssignee(),
                handler,
                configuration);
    }

    private record Completion(
            long actor,
            BpmBusinessTaskDTO task,
            BpmBusinessTaskCompleteReqDTO command,
            Object transactionResource) {}
}
