package com.richuang.os.nocode.enums;

/** 公共办理存储来源；业务表单草稿不等同于流程或常规任务实例。 */
public enum WorkSourceEnum implements NocodeCodeEnum {
    BUSINESS_FORM,
    FLOW_TASK,
    ROUTINE_TASK,
    TASK_ENTRY,
    BUSINESS_APPROVAL;

    @Override
    public String getCode() {
        return name();
    }
}
