package com.richuang.os.nocode.enums;

/** 固定范围和记录权限共用的受控算子。 */
public enum ScopeOperatorEnum implements NocodeCodeEnum {
    EQ("eq"),
    NEQ("neq"),
    IN("in"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    CONTAINS_ANY("containsAny"),
    CONTAINS_ALL("containsAll"),
    IS_NULL("isNull"),
    NOT_NULL("notNull");
    private final String code;

    ScopeOperatorEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ScopeOperatorEnum fromCode(String code) {
        return NocodeCodeEnum.require(ScopeOperatorEnum.class, code);
    }
}
