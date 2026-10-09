package com.lingan.ucp.nocode.report.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 报表实时授权记录，独立修订且保留撤权历史。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_report_dataset_policy", schema = "public")
public class ReportDatasetPolicyDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasetId;
    private String membersJson;
    private Integer lockVersion;
}
