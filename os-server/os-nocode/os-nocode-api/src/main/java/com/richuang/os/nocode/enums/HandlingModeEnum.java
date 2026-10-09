package com.richuang.os.nocode.enums;

/** 办理策略与界面呈现模式相互独立。 */
public enum HandlingModeEnum implements NocodeCodeEnum {
    DIRECT,
    APPROVAL,
    CONDITIONAL;

    @Override
    public String getCode() {
        return name();
    }

    public static HandlingModeEnum fromCode(String code) {
        return NocodeCodeEnum.require(HandlingModeEnum.class, code);
    }
}
