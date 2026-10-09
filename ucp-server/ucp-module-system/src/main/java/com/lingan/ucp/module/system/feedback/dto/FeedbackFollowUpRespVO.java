package com.lingan.ucp.module.system.feedback.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 反馈跟进展示对象；将审计记录中的编码转换为可直接展示的中文文案。
 */
@Data
public class FeedbackFollowUpRespVO {
    private Long id;
    private Long feedbackId;
    private String actionType;
    private String actionLabel;
    private String fromStatus;
    private String fromStatusLabel;
    private String toStatus;
    private String toStatusLabel;
    private Long fromAssigneeId;
    private Long toAssigneeId;
    private Long operatorId;
    private String operatorName;
    private String content;
    private LocalDateTime createTime;
}
