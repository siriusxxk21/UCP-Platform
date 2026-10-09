package com.richuang.os.module.bpm.service.message;

import com.richuang.os.framework.web.config.WebProperties;
import com.richuang.os.module.bpm.enums.message.BpmMessageEnum;
import com.richuang.os.module.bpm.service.message.dto.BpmMessageSendWhenProcessInstanceApproveReqDTO;
import com.richuang.os.module.bpm.service.message.dto.BpmMessageSendWhenProcessInstanceRejectReqDTO;
import com.richuang.os.module.bpm.service.message.dto.BpmMessageSendWhenTaskCreatedReqDTO;
import com.richuang.os.module.bpm.service.message.dto.BpmMessageSendWhenTaskTimeoutReqDTO;
import com.richuang.os.module.msg.api.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.validation.annotation.Validated;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * BPM 消息 Service 实现类，负责注册流程消息模板并在主事务提交后异步投递。
 *
 * @author 芋道源码
 */
@Service
@Validated
@Slf4j
public class BpmMessageServiceImpl implements BpmMessageService, ApplicationRunner {

    private static final String SOURCE_TYPE = "BPM_PROCESS_INSTANCE";
    private static final String NOTICE_CONFIG = "[{\"channel\":\"default\",\"open\":1}]";

    private final IMsgSendService msgSendService;
    private final WebProperties webProperties;
    private final Executor messageTaskExecutor;

    public BpmMessageServiceImpl(IMsgSendService msgSendService, WebProperties webProperties,
                                 @Qualifier("messageTaskExecutor") Executor messageTaskExecutor) {
        this.msgSendService = msgSendService;
        this.webProperties = webProperties;
        this.messageTaskExecutor = messageTaskExecutor;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (BpmMessageEnum message : BpmMessageEnum.values()) {
            msgSendService.registMsgType(new MsgTypeRegistParam()
                    .setCode(message.getMsgCode())
                    .setName(message.getName())
                    .setPriority(5)
                    .setSubscribeAble(0)
                    .setMetaData("test")
                    .setTemplateTitle(message.getTitleTemplate())
                    .setTemplateContent(message.getContentTemplate())
                    .setTemplateUrl("${detailUrl}")
                    .setNoticeConfig(NOTICE_CONFIG));
        }
        log.info("[BpmMessageService] BPM 消息类型注册完成");
    }

    @Override
    public void sendMessageWhenProcessInstanceApprove(BpmMessageSendWhenProcessInstanceApproveReqDTO reqDTO) {
        Map<String, Object> templateParams = new HashMap<>();
        templateParams.put("processInstanceName", reqDTO.getProcessInstanceName());
        templateParams.put("detailUrl", getProcessInstanceDetailUrl(reqDTO.getProcessInstanceId()));
        sendAfterCommit(BpmMessageEnum.PROCESS_INSTANCE_APPROVE, reqDTO.getStartUserId(),
                reqDTO.getProcessInstanceId(), templateParams);
    }

    @Override
    public void sendMessageWhenProcessInstanceReject(BpmMessageSendWhenProcessInstanceRejectReqDTO reqDTO) {
        Map<String, Object> templateParams = new HashMap<>();
        templateParams.put("processInstanceName", reqDTO.getProcessInstanceName());
        templateParams.put("reason", reqDTO.getReason());
        templateParams.put("detailUrl", getProcessInstanceDetailUrl(reqDTO.getProcessInstanceId()));
        sendAfterCommit(BpmMessageEnum.PROCESS_INSTANCE_REJECT, reqDTO.getStartUserId(),
                reqDTO.getProcessInstanceId(), templateParams);
    }

    @Override
    public void sendMessageWhenTaskAssigned(BpmMessageSendWhenTaskCreatedReqDTO reqDTO) {
        Map<String, Object> templateParams = new HashMap<>();
        templateParams.put("processInstanceName", reqDTO.getProcessInstanceName());
        templateParams.put("taskName", reqDTO.getTaskName());
        templateParams.put("startUserNickname", reqDTO.getStartUserNickname());
        templateParams.put("detailUrl", getProcessInstanceDetailUrl(reqDTO.getProcessInstanceId()));
        sendAfterCommit(BpmMessageEnum.TASK_ASSIGNED, reqDTO.getAssigneeUserId(),
                reqDTO.getProcessInstanceId(), templateParams);
    }

    @Override
    public void sendMessageWhenTaskTimeout(BpmMessageSendWhenTaskTimeoutReqDTO reqDTO) {
        Map<String, Object> templateParams = new HashMap<>();
        templateParams.put("processInstanceName", reqDTO.getProcessInstanceName());
        templateParams.put("taskName", reqDTO.getTaskName());
        templateParams.put("detailUrl", getProcessInstanceDetailUrl(reqDTO.getProcessInstanceId()));
        sendAfterCommit(BpmMessageEnum.TASK_TIMEOUT, reqDTO.getAssigneeUserId(),
                reqDTO.getProcessInstanceId(), templateParams);
    }

    /**
     * 审批事务提交后再异步发送消息，避免通知失败导致 Flowable 主事务回滚。
     */
    private void sendAfterCommit(BpmMessageEnum message, Long receiverUserId, String processInstanceId,
                                 Map<String, Object> msgData) {
        Runnable dispatchTask = () -> submitMessageTask(message, receiverUserId, processInstanceId, msgData);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatchTask.run();
                }
            });
            return;
        }
        dispatchTask.run();
    }

    private void submitMessageTask(BpmMessageEnum message, Long receiverUserId, String processInstanceId,
                                   Map<String, Object> msgData) {
        try {
            messageTaskExecutor.execute(() -> doSendMessage(message, receiverUserId, processInstanceId, msgData));
        } catch (Exception ex) {
            log.error("[submitMessageTask][消息任务提交失败，messageCode({}), processInstanceId({}), receiverUserId({})]",
                    message.getMsgCode(), processInstanceId, receiverUserId, ex);
        }
    }

    private void doSendMessage(BpmMessageEnum message, Long receiverUserId, String processInstanceId,
                               Map<String, Object> msgData) {
        try {
            MsgTarget target = new MsgTarget()
                    .setTargetType(MsgTargetType.USER)
                    .setTargetId(String.valueOf(receiverUserId));
            msgSendService.send(new MsgSendParam()
                    .setMsgCode(message.getMsgCode())
                    .setSourceType(SOURCE_TYPE)
                    .setSourceId(processInstanceId)
                    .setMsgData(msgData)
                    .setTargets(Collections.singletonList(target)));
        } catch (Exception ex) {
            // 消息是审批的附加能力，失败只记录业务标识，不向流程主链路传播。
            log.error("[doSendMessage][BPM 消息发送失败，messageCode({}), processInstanceId({}), receiverUserId({})]",
                    message.getMsgCode(), processInstanceId, receiverUserId, ex);
        }
    }

    private String getProcessInstanceDetailUrl(String processInstanceId) {
        return "/task/instance/detail?id=" + processInstanceId;
    }

}
