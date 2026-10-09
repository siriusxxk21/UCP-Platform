package com.richuang.os.module.bpm.service.definition;

import com.richuang.os.module.bpm.controller.admin.definition.vo.model.BpmModelMetaInfoVO;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmFormDO;

import org.flowable.bpmn.model.FlowElement;

/** 在发布边界解析节点继承并固定表单；办理读取部署快照，旧定义沿用既有逻辑。 */
public interface BpmNodeFormService {
    byte[] resolveForDeployment(byte[] bpmn, BpmModelMetaInfoVO model);

    BpmFormDO getDeployedForm(FlowElement node);
}
