package com.lingan.ucp.module.system.feedback.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 反馈可关联的研发对象类型；仅保存引用，不承担研发对象生命周期管理。
 */
@Getter
@AllArgsConstructor
public enum FeedbackReferenceType {
    WORKITEM("工作项"), DEFECT("缺陷");

    private final String label;
}
