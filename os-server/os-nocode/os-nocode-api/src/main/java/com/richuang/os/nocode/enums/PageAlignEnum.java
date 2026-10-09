package com.richuang.os.nocode.enums;

/** 页面配置的稳定编码；不接受任意脚本或样式表达式。 */
public enum PageAlignEnum implements NocodeCodeEnum {
    START,
    CENTER,
    END,
    SPACE_BETWEEN;

    public String getCode() {
        return name();
    }

    public static PageAlignEnum fromCode(String code) {
        return NocodeCodeEnum.require(PageAlignEnum.class, code);
    }
}
