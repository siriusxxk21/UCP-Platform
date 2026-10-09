package com.richuang.os.nocode.report.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 仪表板持久化记录，复用底座审计与逻辑删除。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_report_dashboard", schema = "public")
public class ReportDashboardDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String name;
    private Long ownerId;
    private String status;
    private Long folderId;
    private String draftJson;
    private String draftChecksum;
    private Integer lockVersion;
    private Integer publishedVersion;
}
