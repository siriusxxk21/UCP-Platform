package com.lingan.ucp.module.system.feedback.entity;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 反馈跟进审计记录，保存分派、状态变化、验证及备注的操作现场。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("system_feedback_follow_up")
@KeySequence("system_feedback_follow_up_seq")
public class SystemFeedbackFollowUp extends TenantBaseDO {
    @TableId
    private Long id;
    private Long feedbackId;
    private String actionType;
    private String fromStatus;
    private String toStatus;
    private Long fromAssigneeId;
    private Long toAssigneeId;
    private Long operatorId;
    private String operatorName;
    private String content;
}
