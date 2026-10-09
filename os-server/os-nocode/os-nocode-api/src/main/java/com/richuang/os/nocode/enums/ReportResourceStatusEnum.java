package com.richuang.os.nocode.enums;

/** 报表资源生效状态独立于草稿和发布版本。 */
public enum ReportResourceStatusEnum implements NocodeCodeEnum {
    ACTIVE("ACTIVE"),
    INACTIVE("INACTIVE");
    private final String code;

    ReportResourceStatusEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ReportResourceStatusEnum fromCode(String code) {
        return NocodeCodeEnum.require(ReportResourceStatusEnum.class, code);
    }
}
