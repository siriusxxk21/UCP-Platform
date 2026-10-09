package com.richuang.os.nocode.workflow.adapter.bpm;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.module.bpm.api.task.BpmTaskCompletionGuard;
import com.richuang.os.module.bpm.api.task.dto.*;
import com.richuang.os.nocode.api.work.WorkSourceRef;
import com.richuang.os.nocode.api.workflow.FlowTasks;
import com.richuang.os.nocode.enums.WorkSourceEnum;
import com.richuang.os.nocode.runtime.service.work.WorkFormService;
import com.richuang.os.nocode.workflow.service.task.FlowTaskBindingService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

import java.util.Objects;

/** 只核验绑定与材料，不调用完成编排，防止 Bean 循环及递归完成。 */
@Component
public class FlowTaskCompletionGuard implements BpmTaskCompletionGuard {
    @Resource private FlowTaskBindingService bindings;
    @Resource private WorkFormService forms;

    @Override
    public String handler() {
        return FlowTasks.HANDLER;
    }

    @Override
    public void validate(
            BpmBusinessTaskDTO task, BpmBusinessTaskCompleteReqDTO command, long actor) {
        var binding = bindings.lock(task.taskId());
        bindings.match(binding, task);
        if (!Objects.equals(binding.getSubmissionId(), command.submissionId())
                || !Long.toString(actor).equals(binding.getSubmitter())
                || !command.variables().isEmpty()) throw invalid("本次流程提交没有匹配的业务材料");
        var config = bindings.configuration(task);
        var source = new WorkSourceRef(WorkSourceEnum.FLOW_TASK, task.taskId());
        var material = forms.getSubmission(command.submissionId(), actor, source);
        if (!Objects.equals(config.resource(), material.resource())
                || !Objects.equals(config.objectId(), material.objectId()))
            throw invalid("材料与当前流程节点的固定资源不一致");
        forms.validateSourceSubmission(command.submissionId(), actor, source);
    }
}
