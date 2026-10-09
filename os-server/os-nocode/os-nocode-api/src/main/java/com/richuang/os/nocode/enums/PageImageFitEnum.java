package com.richuang.os.nocode.enums;

/** 页面配置的稳定编码；不接受任意脚本或样式表达式。 */
public enum PageImageFitEnum implements NocodeCodeEnum {
    CONTAIN,
    COVER;

    public String getCode() {
        return name();
    }

    public static PageImageFitEnum fromCode(String code) {
        return NocodeCodeEnum.require(PageImageFitEnum.class, code);
    }
}
