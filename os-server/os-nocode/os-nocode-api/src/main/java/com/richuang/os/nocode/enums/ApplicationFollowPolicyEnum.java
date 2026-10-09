package com.richuang.os.nocode.enums;

/** 有审批在途的应用怎么跟随（配置项 nocode.follow.in-flight-policy）。 */
public enum ApplicationFollowPolicyEnum implements NocodeCodeEnum {
    /** 过渡做法：有在途审批就不跟，记为待处理，之后重试。两道在途保护放宽之前只能用这个。 */
    STRICT("strict"),
    /** 放宽后的做法：不做在途预检，直接跟；跟不上（校验不过）记为待处理。 */
    RELAXED("relaxed");

    private final String code;

    ApplicationFollowPolicyEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static ApplicationFollowPolicyEnum fromCode(String code) {
        return NocodeCodeEnum.require(ApplicationFollowPolicyEnum.class, code);
    }
}
