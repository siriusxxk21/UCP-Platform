package com.richuang.os.module.system.feedback.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 工单只有未解决和已解决两个取值，允许直接切换。保留旧编码以兼容历史记录。 */
@Getter
@AllArgsConstructor
public enum FeedbackStatus {
    PENDING("未解决"),
    CLOSED("已解决");

    private final String label;
}
