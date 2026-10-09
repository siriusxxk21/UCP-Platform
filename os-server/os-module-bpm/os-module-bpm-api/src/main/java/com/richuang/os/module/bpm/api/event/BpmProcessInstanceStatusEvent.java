package com.richuang.os.module.bpm.api.event;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.context.ApplicationEvent;

import java.util.Map;

/**
 * 流程实例结束后发送给关联业务模块的状态事件。
 */
@Data
public class BpmProcessInstanceStatusEvent extends ApplicationEvent {

    @NotNull(message = "流程实例的编号不能为空")
    private String id;
    @NotNull(message = "流程实例的 key 不能为空")
    private String processDefinitionKey;
    @NotNull(message = "流程实例的状态不能为空")
    private Integer status;
    private String reason;
    private String businessKey;
    /**
     * 流程结束时的最终变量，业务监听器可用于读取审批表单结果。
     */
    private Map<String, Object> variables;

    public BpmProcessInstanceStatusEvent(Object source) {
        super(source);
    }
}
