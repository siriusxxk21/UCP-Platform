package com.lingan.ucp.module.bpm.api.definition.dto;

import lombok.Data;

/** 业务模块启动流程前所需的流程定义摘要。 */
@Data
public class BpmProcessDefinitionDTO {

    private String id;
    private String key;
    private String name;
    private Integer version;
    private Integer formType;

    /** 业务模块核对表单路由，避免绑定到其他业务的查看入口。 */
    private String formCustomViewPath;
}
