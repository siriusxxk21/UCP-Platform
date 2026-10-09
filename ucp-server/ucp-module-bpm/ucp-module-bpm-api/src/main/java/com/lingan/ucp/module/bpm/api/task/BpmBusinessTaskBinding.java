package com.lingan.ucp.module.bpm.api.task;

/** 已部署 BPMN UserTask 的固定扩展属性；运行变量不能开启、关闭或替换托管归属。 */
public final class BpmBusinessTaskBinding {
    public static final String NAMESPACE = "urn:ucp-platform:bpmn:business-task";
    public static final String HANDLER_ATTRIBUTE = "handler";

    /** 来源私有配置的原文，由已部署模型提供，不能取运行变量替代。 */
    public static final String CONFIGURATION_ATTRIBUTE = "configuration";

    private BpmBusinessTaskBinding() {}
}
