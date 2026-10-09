package com.lingan.ucp.nocode.report.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 个人看板偏好；唯一(user_id,dashboard_id)，沿用底座审计和逻辑删除。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_report_preference", schema = "public")
public class ReportDashboardPreferenceDO extends BaseDO {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;
    private Long dashboardId;
    private Boolean favorite;
    private LocalDateTime lastVisitedAt;
}
