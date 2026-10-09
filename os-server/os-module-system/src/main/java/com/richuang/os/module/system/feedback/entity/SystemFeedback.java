package com.richuang.os.module.system.feedback.entity;

import com.baomidou.mybatisplus.annotation.KeySequence;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.richuang.os.framework.tenant.core.db.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户问题反馈记录，保存提交内容、页面现场及关联的站内消息。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("system_feedback")
@KeySequence("system_feedback_seq")
public class SystemFeedback extends TenantBaseDO {

    @TableId
    private Long id;

    private String feedbackNo;

    private String feedbackType;

    private String title;

    private String description;

    private Long submitterId;

    private String submitterName;

    private String pagePath;

    private String pageTitle;

    private String buildCommit;

    private String userAgent;

    private String imageFileIds;

    private Long messageId;

    private String status;

    private Long assigneeId;

    private String assigneeName;

    private String submitterRoleNames;

    private Long projectId;

    private String projectName;

    @Version
    private Integer version;
}
