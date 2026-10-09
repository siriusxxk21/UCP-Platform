package com.richuang.os.nocode.enums;

/** 页面配置的稳定编码；不接受任意脚本或样式表达式。 */
public enum PageDirectionEnum implements NocodeCodeEnum {
    ROW,
    COLUMN;

    public String getCode() {
        return name();
    }

    public static PageDirectionEnum fromCode(String code) {
        return NocodeCodeEnum.require(PageDirectionEnum.class, code);
    }
}
