package com.richuang.os.module.bpm.service.definition;

import com.richuang.os.module.bpm.dal.dataobject.definition.BpmFormDO;

/** 发布任务节点时固定业务配置，并同步部署后的图展示快照。 */
public interface BpmTaskCenterNodeModelService {
    record Deployment(byte[] bpmn, String simpleJson) {}

    Deployment prepare(byte[] bpmn, String simpleJson, BpmFormDO flowForm, long actor);
}
