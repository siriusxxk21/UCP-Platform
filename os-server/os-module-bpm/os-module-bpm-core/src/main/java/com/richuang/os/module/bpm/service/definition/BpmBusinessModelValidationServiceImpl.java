package com.richuang.os.module.bpm.service.definition;

import static com.richuang.os.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.richuang.os.module.bpm.enums.ErrorCodeConstants.*;
import static com.richuang.os.module.bpm.framework.flowable.core.util.BpmnModelUtils.*;

import com.richuang.os.module.bpm.api.task.BpmBusinessModelGuard;
import com.richuang.os.module.bpm.api.task.BpmBusinessTaskBinding;
import com.richuang.os.module.bpm.enums.definition.*;
import com.richuang.os.module.bpm.framework.flowable.core.enums.BpmnModelConstants;

import jakarta.annotation.Resource;

import org.flowable.bpmn.model.UserTask;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** 来源未接入或配置自动跳过时拒绝发布，避免生成无法提交业务材料的任务。 */
@Service
public class BpmBusinessModelValidationServiceImpl implements BpmBusinessModelValidationService {
    @Resource private ObjectProvider<BpmBusinessModelGuard> guards;

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void validate(byte[] bpmn, Integer autoApprovalType, long actor) {
        for (var node : getBpmnModelElements(getBpmnModel(bpmn), UserTask.class)) {
            var handler =
                    node.getAttributeValue(
                            BpmBusinessTaskBinding.NAMESPACE,
                            BpmBusinessTaskBinding.HANDLER_ATTRIBUTE);
            var configuration =
                    node.getAttributeValue(
                            BpmBusinessTaskBinding.NAMESPACE,
                            BpmBusinessTaskBinding.CONFIGURATION_ATTRIBUTE);
            if (handler == null && configuration == null) continue;
            if (handler == null || handler.isBlank() || configuration == null)
                throw exception(TASK_BUSINESS_BINDING_INVALID);
            if (node.getSkipExpression() != null && !node.getSkipExpression().isEmpty()) {
                throw exception(TASK_BUSINESS_SKIP_NOT_ALLOWED, node.getName());
            }
            var validators = guards.stream().filter(g -> handler.equals(g.handler())).toList();
            if (validators.size() != 1) throw exception(TASK_BUSINESS_GUARD_UNAVAILABLE);
            if (autoApprovalType != null
                            && !BpmAutoApproveTypeEnum.NONE.getType().equals(autoApprovalType)
                    || !Objects.equals(
                            parseApproveType(node), BpmUserTaskApproveTypeEnum.USER.getType())
                    || node.getLoopCharacteristics() != null
                    || BpmnModelConstants.START_USER_NODE_ID.equals(node.getId())
                    || !Objects.equals(
                            parseAssignStartUserHandlerType(node),
                            BpmUserTaskAssignStartUserHandlerTypeEnum.START_USER_AUDIT.getType())
                    || parseAssignEmptyHandlerType(node) != null)
                throw exception(MODEL_BUSINESS_NODE_MANUAL_REQUIRED, node.getName());
            validators.get(0).validate(node.getId(), configuration, actor);
        }
    }
}
