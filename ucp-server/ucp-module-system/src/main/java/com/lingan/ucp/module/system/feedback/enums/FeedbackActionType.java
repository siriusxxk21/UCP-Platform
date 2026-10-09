package com.lingan.ucp.module.system.feedback.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 反馈跟进记录的操作类型，用于形成不可覆盖的审计轨迹。
 */
@Getter
@AllArgsConstructor
public enum FeedbackActionType {
    CREATED("提交反馈"),
    ASSIGNED("分派负责人"),
    COMMENTED("添加跟进"),
    STATUS_CHANGED("变更状态"),
    VERIFIED("确认完成"),
    REJECTED("退回处理中"),
    REFERENCE_ADDED("新增关联"),
    REFERENCE_REMOVED("取消关联");

    private final String label;
}
