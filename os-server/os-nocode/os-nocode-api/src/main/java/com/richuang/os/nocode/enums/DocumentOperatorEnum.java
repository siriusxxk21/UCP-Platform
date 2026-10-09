package com.richuang.os.nocode.enums;

/** 整单规则白名单算子，持久化稳定编码而非枚举序号。 */
public enum DocumentOperatorEnum implements NocodeCodeEnum {
    VALUE("VALUE"),
    FIELD("FIELD"),
    SUM("SUM"),
    COUNT("COUNT"),
    UNIQUE("UNIQUE"),
    EQ("EQ"),
    NE("NE"),
    GT("GT"),
    GE("GE"),
    LT("LT"),
    LE("LE"),
    AND("AND"),
    OR("OR"),
    NOT("NOT"),
    EMPTY("EMPTY");
    private final String code;

    DocumentOperatorEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static DocumentOperatorEnum fromCode(String code) {
        return NocodeCodeEnum.require(DocumentOperatorEnum.class, code);
    }
}
