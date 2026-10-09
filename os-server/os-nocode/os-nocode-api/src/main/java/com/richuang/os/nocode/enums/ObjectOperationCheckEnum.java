package com.richuang.os.nocode.enums;

/** 生命周期诊断代码，用于界面定位；业务异常继续使用统一错误码。 */
public enum ObjectOperationCheckEnum implements NocodeCodeEnum {
    DEPENDENCY("DEPENDENCY"),
    BUSINESS_ROWS("BUSINESS_ROWS"),
    UNKNOWN_DATA("UNKNOWN_DATA"),
    TITLE_FIELD("TITLE_FIELD"),
    RELATION_FIELD("RELATION_FIELD"),
    INDEX_FIELD("INDEX_FIELD"),
    PROTECTED_FIELD("PROTECTED_FIELD"),
    FIELD_IDENTITY("FIELD_IDENTITY"),
    DETAIL_INACTIVE("DETAIL_INACTIVE"),
    EDITABLE_DRAFT("EDITABLE_DRAFT"),
    MISSING_COLUMN("MISSING_COLUMN"),
    COLUMN_TYPE("COLUMN_TYPE"),
    FIELD_CONSTRAINT("FIELD_CONSTRAINT"),
    CONFIGURATION("CONFIGURATION");

    private final String code;

    ObjectOperationCheckEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }
}
