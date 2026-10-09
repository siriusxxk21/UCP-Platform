package com.richuang.os.module.system.feedback.entity;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.richuang.os.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 反馈与研发项的弱引用快照，避免系统模块反向依赖项目模块。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("system_feedback_reference")
@KeySequence("system_feedback_reference_seq")
public class SystemFeedbackReference extends TenantBaseDO {
    @TableId
    private Long id;
    private Long feedbackId;
    private Long projectId;
    private String projectName;
    private String referenceType;
    private Long targetId;
    private String targetCode;
    private String targetTitle;
}
