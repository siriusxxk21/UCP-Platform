package com.richuang.os.nocode.enums;

/** 没跟上的原因分类。 */
public enum ApplicationFollowPendingEnum implements NocodeCodeEnum {
    /** 应用里有在途审批，暂不切换对象版本。 */
    IN_FLIGHT("IN_FLIGHT"),
    /** 按新版本做应用发布校验没通过。 */
    VALIDATION("VALIDATION"),
    /** 系统错误，或跟随时应用状态已变。 */
    ERROR("ERROR");

    private final String code;

    ApplicationFollowPendingEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static ApplicationFollowPendingEnum fromCode(String code) {
        return NocodeCodeEnum.require(ApplicationFollowPendingEnum.class, code);
    }
}
