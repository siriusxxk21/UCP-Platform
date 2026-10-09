package com.richuang.os.module.bpm.api.task;

/** SIMPLE 与 BPMN 共用的节点表单协议。配置进入部署定义，运行变量不能替换它。 */
public final class BpmNodeFormBinding {
    public static final String NAMESPACE = "https://richuang.com/schema/bpmn/node-form";
    public static final String CONFIGURATION = "configuration";
    public static final String RESOLVED = "resolved";

    public enum Mode {
        INHERIT,
        OVERRIDE
    }

    public enum Source {
        NONE,
        FLOW_FORM,
        SYSTEM_ROUTE,
        APPLICATION_RESOURCE
    }

    public enum TaskMode {
        APPROVAL,
        BUSINESS
    }

    private BpmNodeFormBinding() {}
}
