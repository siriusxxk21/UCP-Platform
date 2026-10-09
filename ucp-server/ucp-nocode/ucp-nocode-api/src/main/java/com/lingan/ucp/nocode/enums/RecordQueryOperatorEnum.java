package com.lingan.ucp.nocode.enums;

/** 运行端沿用底座动态查询运算符编码；按字段类型收紧可用运算。 */
public enum RecordQueryOperatorEnum implements NocodeCodeEnum {
    EQ("eq"),
    NEQ("neq"),
    LIKE("like"),
    NOT_LIKE("notLike"),
    START_WITH("startWith"),
    END_WITH("endWith"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    IN("in"),
    CONTAINS_ANY("containsAny"),
    CONTAINS_ALL("containsAll"),
    BETWEEN("between"),
    IS_NULL("isNull"),
    NOT_NULL("notNull");

    private final String code;

    RecordQueryOperatorEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static RecordQueryOperatorEnum fromCode(String code) {
        return NocodeCodeEnum.require(RecordQueryOperatorEnum.class, code);
    }

    public static boolean supports(FieldTypeEnum type) {
        return switch (type) {
            case SUMMARY, MULTI_SELECT, IMAGE, ATTACHMENT, REGION, CASCADE, URL -> false;
            default -> true;
        };
    }

    public boolean supportsType(FieldTypeEnum type) {
        if (this == CONTAINS_ANY || this == CONTAINS_ALL) return type == FieldTypeEnum.MULTI_SELECT;
        if (!supports(type)) return false;
        return switch (this) {
            case CONTAINS_ANY, CONTAINS_ALL -> false;
            case EQ, NEQ, IN, IS_NULL, NOT_NULL -> true;
            case LIKE, NOT_LIKE, START_WITH, END_WITH ->
                    switch (type) {
                        case TEXT, TEXTAREA, RICH_TEXT, AUTO_NUMBER -> true;
                        default -> false;
                    };
            case GT, GTE, LT, LTE, BETWEEN ->
                    type.isNumeric()
                            || type == FieldTypeEnum.DATE
                            || type == FieldTypeEnum.DATETIME
                            || type == FieldTypeEnum.TIME;
        };
    }
}
