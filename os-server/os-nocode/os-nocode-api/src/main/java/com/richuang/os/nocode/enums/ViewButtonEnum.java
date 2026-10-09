package com.richuang.os.nocode.enums;

/** 视图界面按钮；不代替运行服务中的业务操作授权。 */
public enum ViewButtonEnum implements NocodeCodeEnum {
    CREATE,
    IMPORT,
    EXPORT,
    VIEW,
    UPDATE,
    DELETE;

    public String getCode() {
        return name();
    }

    public static ViewButtonEnum fromCode(String code) {
        return NocodeCodeEnum.require(ViewButtonEnum.class, code);
    }
}
