package com.lingan.ucp.common.exception;

import lombok.Getter;

import java.util.Map;

/**
 * 模板渲染异常
 * 用于封装 FreeMarker 模板渲染错误的详细信息
 */
@Getter
public class TemplateRenderException extends RuntimeException {

    private final String templateName;
    private final String templateType;
    private final String errorLocation;
    private final String errorDetail;
    private final Map<String, Object> dataModel;

    public TemplateRenderException(String templateName, String templateType,
                                   String errorLocation, String errorDetail,
                                   String message, Throwable cause) {
        super(message, cause);
        this.templateName = templateName;
        this.templateType = templateType;
        this.errorLocation = errorLocation;
        this.errorDetail = errorDetail;
        this.dataModel = null;
    }

    public TemplateRenderException(String templateName, String templateType,
                                   String errorLocation, String errorDetail,
                                   String message, Map<String, Object> dataModel,
                                   Throwable cause) {
        super(message, cause);
        this.templateName = templateName;
        this.templateType = templateType;
        this.errorLocation = errorLocation;
        this.errorDetail = errorDetail;
        this.dataModel = dataModel;
    }
}
