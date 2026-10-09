package com.richuang.os.nocode.enums;

/** HTTP 提交结果：立即生效或形成审批申请。 */
public enum HandlingOutcomeEnum implements NocodeCodeEnum {
    EFFECTIVE,
    SUBMITTED;

    @Override
    public String getCode() {
        return name();
    }
}
