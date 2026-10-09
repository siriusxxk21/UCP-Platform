package com.lingan.ucp.nocode.enums;

/** 规则定位范围；字段规则及整单规则均在候选最终数据上执行。 */
public enum DocumentRuleScopeEnum implements NocodeCodeEnum {
    FIELD("FIELD"),
    ROW("ROW"),
    DETAIL("DETAIL"),
    DOCUMENT("DOCUMENT");
    private final String code;

    DocumentRuleScopeEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static DocumentRuleScopeEnum fromCode(String code) {
        return NocodeCodeEnum.require(DocumentRuleScopeEnum.class, code);
    }
}
