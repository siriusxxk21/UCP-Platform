package com.lingan.ucp.module.system.feedback.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 反馈固定流程步骤，由后端根据负责人和业务状态计算展示状态。
 */
@Data
@AllArgsConstructor
public class FeedbackProgressStepRespVO {
    private String key;
    private String label;
    private String state;
}
