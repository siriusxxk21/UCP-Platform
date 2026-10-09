package com.lingan.ucp.nocode.enums;

/** 生命周期预检的数据保留范围；历史部署表不能因当前草稿停用而遗漏。 */
public enum ObjectOperationScopeEnum implements NocodeCodeEnum {
    MAIN("MAIN"),
    DETAIL("DETAIL"),
    RELATION("RELATION"),
    RETAINED("RETAINED"),
    FIELD("FIELD");

    private final String code;

    ObjectOperationScopeEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }
}
