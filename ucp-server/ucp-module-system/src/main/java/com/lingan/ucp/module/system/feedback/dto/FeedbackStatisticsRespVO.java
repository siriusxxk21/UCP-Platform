package com.lingan.ucp.module.system.feedback.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 后台反馈清单顶部统计。
 */
@Data
@AllArgsConstructor
public class FeedbackStatisticsRespVO {
    private long total;
    private long pending;
    private long processing;
    private long referenced;
}
