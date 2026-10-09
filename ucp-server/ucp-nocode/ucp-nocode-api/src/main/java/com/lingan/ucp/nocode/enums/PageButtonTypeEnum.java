package com.lingan.ucp.nocode.enums;

/** 页面配置的稳定编码；不接受任意脚本或样式表达式。 */
public enum PageButtonTypeEnum implements NocodeCodeEnum {
    PRIMARY,
    DEFAULT,
    TEXT;

    public String getCode() {
        return name();
    }

    public static PageButtonTypeEnum fromCode(String code) {
        return NocodeCodeEnum.require(PageButtonTypeEnum.class, code);
    }
}
