package com.richuang.os.module.bpm.enums.message;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Bpm 消息的枚举
 *
 * @author 芋道源码
 */
@AllArgsConstructor
@Getter
public enum BpmMessageEnum {

    PROCESS_INSTANCE_APPROVE("bpm_process_instance_approve", "流程审批通过",
            "流程审批通过：${processInstanceName}", "您发起的流程【${processInstanceName}】已审批通过。"),
    PROCESS_INSTANCE_REJECT("bpm_process_instance_reject", "流程审批不通过",
            "流程审批不通过：${processInstanceName}", "您发起的流程【${processInstanceName}】审批不通过，原因：${reason}。"),
    TASK_ASSIGNED("bpm_task_assigned", "审批任务待办",
            "待办审批：${taskName}", "${startUserNickname}发起的流程【${processInstanceName}】有待办任务【${taskName}】，请及时处理。"),
    TASK_TIMEOUT("bpm_task_timeout", "审批任务超时",
            "审批任务超时：${taskName}", "流程【${processInstanceName}】的任务【${taskName}】已超时，请及时处理。");

    /**
     * msg-core 消息类型编码。
     */
    private final String msgCode;

    private final String name;

    private final String titleTemplate;

    private final String contentTemplate;

}
