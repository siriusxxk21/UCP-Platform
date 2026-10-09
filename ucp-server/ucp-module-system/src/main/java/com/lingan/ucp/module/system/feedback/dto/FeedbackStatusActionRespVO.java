package com.lingan.ucp.module.system.feedback.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 反馈详情中可执行的处理动作；由后端状态流转策略生成，前端只负责展示和提交。
 */
@Data
@AllArgsConstructor
public class FeedbackStatusActionRespVO {
    private String action;
    private String label;
    private String targetStatus;
    private String targetStatusLabel;
    private boolean requiresAssignee;
    private boolean requiresContent;
}
