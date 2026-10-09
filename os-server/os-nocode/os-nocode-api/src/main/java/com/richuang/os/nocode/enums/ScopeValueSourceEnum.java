package com.richuang.os.nocode.enums;

/** 动态权限值只从底座身份解析，不接受客户端指定当前用户或部门。 */
public enum ScopeValueSourceEnum implements NocodeCodeEnum {
    CONSTANT("CONSTANT"),
    CURRENT_USER("CURRENT_USER"),
    CURRENT_DEPARTMENT("CURRENT_DEPARTMENT"),
    CURRENT_DEPARTMENT_TREE("CURRENT_DEPARTMENT_TREE");
    private final String code;

    ScopeValueSourceEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ScopeValueSourceEnum fromCode(String code) {
        return NocodeCodeEnum.require(
                ScopeValueSourceEnum.class, code == null ? CONSTANT.code : code);
    }
}
