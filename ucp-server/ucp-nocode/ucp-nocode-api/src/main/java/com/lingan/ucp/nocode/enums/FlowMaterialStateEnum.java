package com.lingan.ucp.nocode.enums;

/** 材料有效状态与不可用提示。 */
public enum FlowMaterialStateEnum implements NocodeCodeEnum {
    CURRENT("CURRENT"),
    HISTORY("HISTORY"),
    UNAVAILABLE("UNAVAILABLE");

    private final String code;

    FlowMaterialStateEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
