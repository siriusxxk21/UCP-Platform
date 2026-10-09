package com.richuang.os.nocode.enums;

/** 办理入口的展示方式，不代表任务实例状态或额外的数据权限。 */
public enum TaskEntryModeEnum implements NocodeCodeEnum {
    LIST,
    FORM;

    @Override
    public String getCode() {
        return name();
    }

    public static TaskEntryModeEnum fromCode(String value) {
        return NocodeCodeEnum.require(TaskEntryModeEnum.class, value);
    }
}
