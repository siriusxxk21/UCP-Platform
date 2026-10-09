package com.lingan.ucp.module.system.feedback.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 系统支持的用户反馈类型。
 */
@Getter
@AllArgsConstructor
public enum FeedbackType {
    REQUIREMENT("需求反馈", "需求"),
    ISSUE("问题反馈", "问题"),
    BUG("Bug反馈", "Bug");

    private final String label;
    private final String value;
}
