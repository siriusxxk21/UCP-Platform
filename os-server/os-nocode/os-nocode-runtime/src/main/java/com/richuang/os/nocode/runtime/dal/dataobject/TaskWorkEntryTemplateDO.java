package com.richuang.os.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 模板发布时固定各办理入口的应用和对象版本。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_task_entry_template_version", schema = "public")
public class TaskWorkEntryTemplateDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private String templateId;
    private Integer versionNo;
    private String bindingsJson;
}
