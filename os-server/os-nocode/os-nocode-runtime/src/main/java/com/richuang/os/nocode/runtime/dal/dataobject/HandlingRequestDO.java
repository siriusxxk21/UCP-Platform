package com.richuang.os.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 状态登记不重复保存审批材料，材料关联公共不可变提交。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nocode_handling_request")
public class HandlingRequestDO extends BaseDO {
    private String id;
    private Long applicationId;
    private String applicationName;
    private Integer applicationVersion;
    private Long objectId;
    private String objectName;
    private String entryId;
    private String recordId;
    private String operation;
    private String name;
    private String requestKey;
    private String requestDigest;
    private String definitionChecksum;
    private String definitionJson;
    private String submissionId;
    private String processInstanceId;
    private String processDefinitionId;
    private String processDefinitionKey;
    private String status;
    private Integer lockVersion;
    private String error;
}
