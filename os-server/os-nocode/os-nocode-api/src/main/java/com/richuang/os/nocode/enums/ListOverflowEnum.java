package com.richuang.os.nocode.enums;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

/** 列表单元格内容超出列宽时的显示方式。视图没有配置时沿用按字段类型的既有显示，不补默认值。 */
public enum ListOverflowEnum implements NocodeCodeEnum {
    /** 自动截断：列宽严格按配置，单行显示，超出部分省略。 */
    ELLIPSIS;

    public String getCode() {
        return name();
    }

    /** 没有配置（null）是合法的历史形态，原样返回；配置了就只接受现有的取值。 */
    public static String optional(String code) {
        if (code == null) return null;
        for (var value : values()) if (value.matches(code)) return code;
        throw invalid("“内容超出列宽时”只支持“自动截断”");
    }
}
