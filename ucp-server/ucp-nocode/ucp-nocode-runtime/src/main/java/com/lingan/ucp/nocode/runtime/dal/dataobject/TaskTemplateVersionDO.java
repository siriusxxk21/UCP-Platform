package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 不可变模板版本，同时冻结业务表单与对象引用。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_task_template_version", schema = "public")
public class TaskTemplateVersionDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private String templateId;
    private Integer versionNo;
    private String name;
    private String description;
    private String kind;
    private String nodesJson;
    private String bindingsJson;
    private String rootJson;

    /** 发布时重新批准的根任务数据能力；发起实例不得由客户端修改快照。 */
    private String authorizationJson;
}
