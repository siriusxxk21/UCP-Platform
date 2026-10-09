package com.richuang.os.nocode.application.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 保存业务记录与底座流程实例的关联，不复制流程任务或业务表数据。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nocode_record_process")
public class NocodeRecordProcessDO extends BaseDO {
    @TableId private Long id;
    private Long applicationId;
    private Integer applicationVersion;
    private Long objectId;
    private Integer objectVersion;
    private String recordId;
    private String actionId;
    private String name;
    private String businessKey;
    private String processDefinitionId;
    private String processDefinitionKey;
    private String processInstanceId;
    private String status;
    private LocalDateTime endTime;
}
