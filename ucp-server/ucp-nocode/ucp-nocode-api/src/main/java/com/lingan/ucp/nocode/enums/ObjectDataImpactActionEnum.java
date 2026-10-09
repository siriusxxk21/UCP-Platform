package com.lingan.ucp.nocode.enums;

/** 对象数据维护删除检查中的实际影响，供前后端统一展示。 */
public enum ObjectDataImpactActionEnum implements NocodeCodeEnum {
    BLOCK("BLOCK"),
    CLEAR_REFERENCE("CLEAR_REFERENCE"),
    DELETE("DELETE");

    private final String code;

    ObjectDataImpactActionEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }
}
