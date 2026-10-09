package com.lingan.ucp.module.bpm.api.definition;

import com.lingan.ucp.module.bpm.api.definition.dto.BpmProcessDefinitionDTO;

/** 提供给业务模块的流程定义查询接口。 */
public interface BpmProcessDefinitionApi {

    BpmProcessDefinitionDTO getActiveProcessDefinition(String key);

    BpmProcessDefinitionDTO getProcessDefinition(String id);

    /** 包含启用的所有部署版本和仍有运行实例的停用版本，不枚举草稿模型。 */
    java.util.List<com.lingan.ucp.module.bpm.api.definition.dto.BpmBusinessBindingDTO>
            getEffectiveBusinessBindings(String handler);
}
