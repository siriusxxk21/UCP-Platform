package com.lingan.ucp.nocode.enums;

import java.util.Set;

/** 字段逻辑类型，物理映射由 schema 模块编译。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum FieldTypeEnum implements NocodeCodeEnum {
    TEXT("TEXT"),
    TEXTAREA("TEXTAREA"),
    INTEGER("INTEGER"),
    DECIMAL("DECIMAL"),
    BOOLEAN("BOOLEAN"),
    DATE("DATE"),
    DATETIME("DATETIME"),
    RICH_TEXT("RICH_TEXT"),
    URL("URL"),
    MONEY("MONEY"),
    PERCENT("PERCENT"),
    TIME("TIME"),
    SELECT("SELECT"),
    MULTI_SELECT("MULTI_SELECT"),
    ORGANIZATION("ORGANIZATION"),
    USER("USER"),
    DEPARTMENT("DEPARTMENT"),
    POST("POST"),
    USER_GROUP("USER_GROUP"),
    IMAGE("IMAGE"),
    ATTACHMENT("ATTACHMENT"),
    REGION("REGION"),
    CASCADE("CASCADE"),
    AUTO_NUMBER("AUTO_NUMBER"),
    FORMULA("FORMULA"),
    SUMMARY("SUMMARY"),
    REFERENCE("REFERENCE"),
    UUID("UUID");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(FieldTypeEnum.class);

    FieldTypeEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static FieldTypeEnum fromCode(String code) {
        return NocodeCodeEnum.require(FieldTypeEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }

    /** 导入模板不携带关系、选项或表达式配置，这些类型必须在设计器中配置。 */
    public boolean supportsStructureImport() {
        return switch (this) {
            case TEXT,
                            TEXTAREA,
                            INTEGER,
                            DECIMAL,
                            BOOLEAN,
                            DATE,
                            DATETIME,
                            RICH_TEXT,
                            URL,
                            MONEY,
                            PERCENT,
                            TIME,
                            AUTO_NUMBER,
                            UUID ->
                    true;
            default -> false;
        };
    }

    /** 应用统计和独立数据集共用分组类型白名单，避免新入口扩大隐式分析类型。 */
    public boolean supportsReportGrouping() {
        return switch (this) {
            case TEXT,
                            INTEGER,
                            DECIMAL,
                            MONEY,
                            PERCENT,
                            BOOLEAN,
                            DATE,
                            DATETIME,
                            TIME,
                            SELECT,
                            AUTO_NUMBER,
                            REFERENCE,
                            UUID,
                            ORGANIZATION,
                            DEPARTMENT,
                            USER,
                            POST,
                            USER_GROUP ->
                    true;
            default -> false;
        };
    }

    public boolean isDecimal() {
        return this == DECIMAL || this == MONEY || this == PERCENT;
    }

    public boolean isNumeric() {
        return this == INTEGER || isDecimal();
    }

    public boolean isComputed() {
        return this == FORMULA || this == SUMMARY;
    }
}
