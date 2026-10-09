package com.lingan.ucp.nocode.enums;

/** 业务名称的展示状态；空值、配置失效与字段权限分别表达，不改变附件授权。 */
public enum BusinessFileLabelStatusEnum implements NocodeCodeEnum {
    NORMAL("NORMAL"),
    EMPTY("EMPTY"),
    RESTRICTED("RESTRICTED"),
    INVALID("INVALID");
    private final String code;

    BusinessFileLabelStatusEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }
}
