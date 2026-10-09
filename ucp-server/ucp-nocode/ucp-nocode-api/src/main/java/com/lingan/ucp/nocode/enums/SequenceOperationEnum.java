package com.lingan.ucp.nocode.enums;

/** 顺序公式的求值方式；旧版本未保存 operation 时仍按相邻记录计算。 */
public enum SequenceOperationEnum implements NocodeCodeEnum {
    ADJACENT("ADJACENT"),
    CUMULATIVE("CUMULATIVE");
    private final String code;

    SequenceOperationEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static SequenceOperationEnum fromCode(String code) {
        return code == null ? ADJACENT : NocodeCodeEnum.require(SequenceOperationEnum.class, code);
    }
}
