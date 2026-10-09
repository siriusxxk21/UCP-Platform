package com.richuang.os.nocode.enums;

/** 一次跟随的结果。 */
public enum ApplicationFollowOutcomeEnum implements NocodeCodeEnum {
    /** 已替应用同步并发布到新版本。 */
    FOLLOWED("FOLLOWED"),
    /** 已在目标版本或更高，不需要跟。 */
    UP_TO_DATE("UP_TO_DATE"),
    /** 没跟上，已记为待处理。 */
    PENDING("PENDING"),
    /** 应用从没发布过，只同步了草稿。 */
    DRAFT_ONLY("DRAFT_ONLY");

    private final String code;

    ApplicationFollowOutcomeEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static ApplicationFollowOutcomeEnum fromCode(String code) {
        return NocodeCodeEnum.require(ApplicationFollowOutcomeEnum.class, code);
    }
}
