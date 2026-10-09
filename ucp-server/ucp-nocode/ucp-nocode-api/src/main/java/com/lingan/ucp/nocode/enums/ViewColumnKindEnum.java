package com.lingan.ucp.nocode.enums;

/** 关联列的受控取值方式；聚合逐来源求值，避免多个子表相乘。 */
public enum ViewColumnKindEnum implements NocodeCodeEnum {
    LOOKUP("LOOKUP"),
    DETAIL("DETAIL"),
    COUNT("COUNT"),
    SUM("SUM"),
    MIN("MIN"),
    MAX("MAX");
    private final String code;

    ViewColumnKindEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static ViewColumnKindEnum fromCode(String code) {
        return NocodeCodeEnum.require(ViewColumnKindEnum.class, code);
    }
}
