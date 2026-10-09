package com.lingan.ucp.nocode.enums;

/** 审批材料来源，不使用枚举序号持久化或传输。 */
public enum FlowMaterialKindEnum implements NocodeCodeEnum {
    FLOW_FORM("FLOW_FORM"),
    APPLICATION_FORM("APPLICATION_FORM");

    private final String code;

    FlowMaterialKindEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
