package com.richuang.os.module.bpm.api.definition;

import com.richuang.os.module.bpm.api.definition.dto.BpmProcessDefinitionDTO;
import com.richuang.os.module.bpm.dal.dataobject.definition.BpmProcessDefinitionInfoDO;
import com.richuang.os.module.bpm.service.definition.BpmProcessDefinitionService;

import jakarta.annotation.Resource;

import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;

/** 提供 Flowable 流程定义摘要，屏蔽业务模块对流程引擎的依赖。 */
@Service
public class BpmProcessDefinitionApiImpl implements BpmProcessDefinitionApi {

    @Resource private BpmProcessDefinitionService processDefinitionService;

    @Override
    public BpmProcessDefinitionDTO getActiveProcessDefinition(String key) {
        ProcessDefinition definition = processDefinitionService.getActiveProcessDefinition(key);
        return buildProcessDefinition(definition);
    }

    @Override
    public BpmProcessDefinitionDTO getProcessDefinition(String id) {
        ProcessDefinition definition = processDefinitionService.getProcessDefinition(id);
        return buildProcessDefinition(definition);
    }

    @Override
    public java.util.List<com.richuang.os.module.bpm.api.definition.dto.BpmBusinessBindingDTO>
            getEffectiveBusinessBindings(String handler) {
        return processDefinitionService.getEffectiveBusinessBindings(handler);
    }

    private BpmProcessDefinitionDTO buildProcessDefinition(ProcessDefinition definition) {
        if (definition == null || definition.isSuspended()) {
            return null;
        }
        BpmProcessDefinitionInfoDO info =
                processDefinitionService.getProcessDefinitionInfo(definition.getId());
        if (info == null) {
            return null;
        }
        BpmProcessDefinitionDTO result = new BpmProcessDefinitionDTO();
        result.setId(definition.getId());
        result.setKey(definition.getKey());
        result.setName(definition.getName());
        result.setVersion(definition.getVersion());
        result.setFormType(info.getFormType());
        result.setFormCustomViewPath(info.getFormCustomViewPath());
        return result;
    }
}
