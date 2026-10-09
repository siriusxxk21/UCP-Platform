package com.lingan.ucp.nocode.enums;

/** BPM 通过后先登记待生效；业务事务失败不能冒充已完成。 */
public enum HandlingStateEnum implements NocodeCodeEnum {
    PENDING,
    APPLY_PENDING,
    APPROVED,
    REJECTED,
    CANCELED,
    APPLY_FAILED;

    @Override
    public String getCode() {
        return name();
    }

    public static HandlingStateEnum fromCode(String code) {
        return NocodeCodeEnum.require(HandlingStateEnum.class, code);
    }
}
