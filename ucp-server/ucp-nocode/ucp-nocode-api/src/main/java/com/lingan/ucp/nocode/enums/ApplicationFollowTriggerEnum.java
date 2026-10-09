package com.lingan.ucp.nocode.enums;

/** 跟随由什么触发。 */
public enum ApplicationFollowTriggerEnum implements NocodeCodeEnum {
    /** 对象发布。 */
    OBJECT_PUBLISH("OBJECT_PUBLISH"),
    /** 把开关从关拨到开。 */
    SWITCH_ON("SWITCH_ON"),
    /** 在工作台点「立即跟随 / 重试」。 */
    MANUAL("MANUAL"),
    /** 定时重试。 */
    RETRY_JOB("RETRY_JOB"),
    /** 应用人工发布时自动提版。 */
    APPLICATION_PUBLISH("APPLICATION_PUBLISH"),
    /** 迁移工具。 */
    MIGRATION("MIGRATION");

    private final String code;

    ApplicationFollowTriggerEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static ApplicationFollowTriggerEnum fromCode(String code) {
        return NocodeCodeEnum.require(ApplicationFollowTriggerEnum.class, code);
    }
}
