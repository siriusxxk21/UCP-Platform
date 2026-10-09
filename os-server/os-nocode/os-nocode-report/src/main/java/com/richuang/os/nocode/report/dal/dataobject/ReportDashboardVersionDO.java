package com.richuang.os.nocode.report.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 仪表板持久化记录，复用底座审计与逻辑删除。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_report_dashboard_version", schema = "public")
public class ReportDashboardVersionDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long dashboardId;
    private Integer versionNo;
    private String definitionJson;
    private String checksum;
    private String requestId;
}
