package com.lingan.ucp.nocode.report.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 报表实时授权记录，独立修订且保留撤权历史。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_report_resource_acl", schema = "public")
public class ReportResourceAclDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String resourceKind;
    private Long resourceId;
    private String policyJson;
    private Integer lockVersion;
}
