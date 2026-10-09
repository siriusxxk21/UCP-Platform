package com.richuang.os.nocode.report.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 报表持久化记录；审计与逻辑删除沿用底座字段。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_report_dataset", schema = "public")
public class ReportDatasetDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private String description;
    private Long ownerId;
    private Long folderId;
    private String status;
    private String draftJson;
    private String draftChecksum;
    private Integer lockVersion;
    private Integer publishedVersion;
}
