package com.lingan.ucp.nocode.enums;

/** 工作草稿状态，正式提交后不允许覆盖原输入。 */
public enum WorkDraftStateEnum implements NocodeCodeEnum {
    DRAFT,
    SUBMITTED;

    @Override
    public String getCode() {
        return name();
    }
}
