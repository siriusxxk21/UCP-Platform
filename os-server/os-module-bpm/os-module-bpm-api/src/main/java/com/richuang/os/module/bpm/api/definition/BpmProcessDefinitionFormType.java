package com.richuang.os.module.bpm.api.definition;

/**
 * 对外暴露的流程表单类型，避免业务模块依赖 BPM 实现枚举。
 */
public final class BpmProcessDefinitionFormType {

    public static final int NONE = 0;
    public static final int NORMAL = 10;
    public static final int CUSTOM = 20;

    private BpmProcessDefinitionFormType() {
    }
}
