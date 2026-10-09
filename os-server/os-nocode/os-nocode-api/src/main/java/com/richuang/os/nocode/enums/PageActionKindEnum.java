package com.richuang.os.nocode.enums;

/** 页面配置的稳定编码；不接受任意脚本或样式表达式。 */
public enum PageActionKindEnum implements NocodeCodeEnum {
    CREATE,
    EDIT,
    VIEW,
    REFRESH,
    OPEN_PAGE,
    NAVIGATE,
    EXECUTE_ACTION;

    public String getCode() {
        return name();
    }

    public static PageActionKindEnum fromCode(String code) {
        return NocodeCodeEnum.require(PageActionKindEnum.class, code);
    }
}
