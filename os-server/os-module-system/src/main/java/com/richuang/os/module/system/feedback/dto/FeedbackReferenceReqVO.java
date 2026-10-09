package com.richuang.os.module.system.feedback.dto;

import lombok.Data;

/**
 * 研发项引用快照请求，研发对象权限仍由项目模块在选择或创建时校验。
 */
@Data
public class FeedbackReferenceReqVO {
    private Long projectId;
    private String projectName;
    private String referenceType;
    private Long targetId;
    private String targetCode;
    private String targetTitle;
}
