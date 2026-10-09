package com.lingan.ucp.nocode.workflow.service.task;

import static com.lingan.ucp.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.crypto.digest.DigestUtil;

import com.fasterxml.jackson.databind.*;
import com.lingan.ucp.module.bpm.api.task.dto.BpmBusinessTaskDTO;
import com.lingan.ucp.nocode.api.workflow.FlowTasks;
import com.lingan.ucp.nocode.application.service.published.ApplicationPublishedService;
import com.lingan.ucp.nocode.enums.ApplicationActionEnum;
import com.lingan.ucp.nocode.enums.ApplicationResourceKindEnum;
import com.lingan.ucp.nocode.workflow.dal.dataobject.task.FlowTaskBindingDO;
import com.lingan.ucp.nocode.workflow.dal.mapper.task.FlowTaskBindingMapper;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/** 已部署节点是资源的唯一来源；不从流程变量、个人草稿或 HTTP 参数回填绑定。 */
@Service
public class FlowTaskBindingServiceImpl implements FlowTaskBindingService {
    @Override
    public FlowTaskBindingDO read(String taskId) {
        return mapper.read(taskId);
    }

    @Resource private FlowTaskBindingMapper mapper;
    @Resource private ObjectMapper json;
    @Resource private ApplicationPublishedService published;

    @Override
    public FlowTaskBindingDO lock(String taskId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("任务绑定必须处于事务中");
        if (taskId == null || taskId.isBlank() || taskId.length() > 128) throw invalid("流程任务标识无效");
        mapper.lock("nocode-flow-task:" + taskId);
        return mapper.get(taskId);
    }

    @Override
    public BpmBusinessTaskDTO snapshot(FlowTaskBindingDO binding) {
        if (binding == null) throw invalid("流程任务尚未绑定业务资源");
        try {
            return json.readValue(binding.getTaskJson(), BpmBusinessTaskDTO.class);
        } catch (java.io.IOException ex) {
            throw invalid("流程任务绑定无法读取");
        }
    }

    @Override
    public FlowTasks.Configuration configuration(String handler, String configuration) {
        if (!FlowTasks.HANDLER.equals(handler)
                || configuration == null
                || configuration.length() > 16384) throw invalid("流程节点尚未配置业务资源");
        try {
            var config =
                    json.readerFor(FlowTasks.Configuration.class)
                            .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                            .readValue(configuration);
            FlowTasks.Configuration value = (FlowTasks.Configuration) config;
            if (value == null
                    || value.resource() == null
                    || value.objectId() == null
                    || !value.objectId().matches("[1-9][0-9]{0,18}")
                    || !ApplicationActionEnum.CREATE.matches(value.operation())
                    || !ApplicationResourceKindEnum.FORM.matches(value.resource().resourceKind()))
                throw invalid("当前流程办理仅支持绑定已发布表单的 CREATE 节点");
            published.resolve(value.resource());
            return value;
        } catch (java.io.IOException ex) {
            throw invalid("流程节点业务配置无效");
        }
    }

    @Override
    public FlowTaskBindingDO bind(BpmBusinessTaskDTO task, long actor) {
        configuration(task);
        var existing = lock(task.taskId());
        if (existing == null) {
            try {
                mapper.create(task.taskId(), json.writeValueAsString(task), Long.toString(actor));
            } catch (java.io.IOException ex) {
                throw invalid("流程任务绑定无法保存");
            }
            existing = mapper.get(task.taskId());
        }
        match(existing, task);
        return existing;
    }

    @Override
    public void match(FlowTaskBindingDO row, BpmBusinessTaskDTO task) {
        if (row == null) throw invalid("流程任务尚未绑定业务资源");
        try {
            var original = json.readValue(row.getTaskJson(), BpmBusinessTaskDTO.class);
            if (!Objects.equals(original.taskId(), task.taskId())
                    || !Objects.equals(original.processInstanceId(), task.processInstanceId())
                    || !Objects.equals(original.processDefinitionId(), task.processDefinitionId())
                    || !Objects.equals(original.executionId(), task.executionId())
                    || !Objects.equals(original.taskDefinitionKey(), task.taskDefinitionKey())
                    || !Objects.equals(original.handler(), task.handler())
                    || !Objects.equals(original.configuration(), task.configuration()))
                throw invalid("流程任务身份或固定资源不一致");
        } catch (java.io.IOException ex) {
            throw invalid("流程任务绑定无法读取");
        }
    }

    @Override
    public void submitted(String taskId, String material, String digest, long actor) {
        if (mapper.submitted(taskId, material, Long.toString(actor), digest) != 1)
            throw invalid("流程任务已提交");
    }

    @Override
    public String digest(FlowTasks.Submit command) {
        if (command == null || command.reason() != null && command.reason().length() > 2000)
            throw invalid("流程提交参数无效");
        try {
            return DigestUtil.sha256Hex(json.writeValueAsString(command));
        } catch (java.io.IOException ex) {
            throw invalid("流程提交参数无效");
        }
    }
}
