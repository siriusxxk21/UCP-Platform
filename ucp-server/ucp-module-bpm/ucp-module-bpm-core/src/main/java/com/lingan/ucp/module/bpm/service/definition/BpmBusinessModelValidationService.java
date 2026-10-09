package com.lingan.ucp.module.bpm.service.definition;

/** 与模型发布共用事务的业务节点校验入口。 */
public interface BpmBusinessModelValidationService {
    void validate(byte[] bpmn, Integer autoApprovalType, long actor);
}
