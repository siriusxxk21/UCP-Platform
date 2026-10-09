package com.richuang.os.nocode.enums;

/** 事务内登记的工作事件，投递器后续按事件 ID 去重。 */
public enum WorkEventTypeEnum implements NocodeCodeEnum {
    SUBMITTED;

    @Override
    public String getCode() {
        return name();
    }
}
