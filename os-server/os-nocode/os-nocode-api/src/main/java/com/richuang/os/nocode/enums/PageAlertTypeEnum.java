package com.richuang.os.nocode.enums;

/** 页面配置的稳定编码；不接受任意脚本或样式表达式。 */
public enum PageAlertTypeEnum implements NocodeCodeEnum {
    INFO,
    SUCCESS,
    WARNING,
    ERROR;

    public String getCode() {
        return name();
    }

    public static PageAlertTypeEnum fromCode(String code) {
        return NocodeCodeEnum.require(PageAlertTypeEnum.class, code);
    }
}
