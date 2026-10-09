package com.lingan.ucp.nocode.report.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 报表持久化记录；审计与逻辑删除沿用底座字段。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_report_operation_log", schema = "public")
public class ReportOperationLogDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String resourceKind;
    private Long resourceId;
    private String action;
    private Integer revision;
    private String beforeJson;
    private String afterJson;
    private String reason;
}
