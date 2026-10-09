package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.module.bpm.api.definition.BpmProcessDefinitionApi;
import com.richuang.os.module.bpm.api.definition.BpmProcessDefinitionFormType;
import com.richuang.os.module.bpm.api.definition.dto.BpmProcessDefinitionDTO;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;

/** 复用底座已部署流程；业务表单路径必须指向受控的无代码记录入口。 */
@Component
public class ApplicationProcessDefinition {
    public static final String RECORD_VIEW_PATH = "/nocode-app/process-record";
    @Resource private BpmProcessDefinitionApi definitions;

    public BpmProcessDefinitionDTO require(String id) {
        if (id == null || id.isBlank() || id.length() > 128) throw invalid("请选择已发布流程版本");
        var definition = definitions.getProcessDefinition(id);
        if (definition == null) throw invalid("流程版本不存在或已挂起，请重新选择");
        if (!Integer.valueOf(BpmProcessDefinitionFormType.CUSTOM).equals(definition.getFormType())
                || !RECORD_VIEW_PATH.equals(definition.getFormCustomViewPath()))
            throw invalid("流程必须使用业务表单，查看路径配置为 " + RECORD_VIEW_PATH);
        return definition;
    }
}
