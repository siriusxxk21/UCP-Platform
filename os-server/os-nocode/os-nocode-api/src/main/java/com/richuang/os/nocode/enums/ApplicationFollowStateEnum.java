package com.richuang.os.nocode.enums;

/** 应用对一个对象的跟随状态。没有状态行等同于 FOLLOWING。 */
public enum ApplicationFollowStateEnum implements NocodeCodeEnum {
    /** 正常跟随。 */
    FOLLOWING("FOLLOWING"),
    /** 有一个对象版本没跟上，等待重试。 */
    PENDING("PENDING");

    private final String code;

    ApplicationFollowStateEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static ApplicationFollowStateEnum fromCode(String code) {
        return NocodeCodeEnum.require(ApplicationFollowStateEnum.class, code);
    }
}
