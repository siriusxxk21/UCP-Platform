package com.lingan.ucp.nocode.report.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 报表持久化记录；审计与逻辑删除沿用底座字段。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_report_dataset_version", schema = "public")
public class ReportDatasetVersionDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long datasetId;
    private Integer versionNo;
    private String definitionJson;
    private String checksum;
    private String reason;
    private String requestId;
    private String requestHash;
}
