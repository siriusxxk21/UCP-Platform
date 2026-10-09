package com.richuang.os.nocode.enums;

import java.util.Set;

/** 对象规则求值状态；编码沿用旧系统联动运行态，新增类型不符、不适用及取整方式未知。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum FieldRuleStateEnum implements NocodeCodeEnum {
    APPLIED("APPLIED"),
    INCOMPLETE_CONFIG("INCOMPLETE_CONFIG"),
    SOURCE_TABLE_MISSING("SOURCE_TABLE_MISSING"),
    SOURCE_NOT_READABLE("SOURCE_NOT_READABLE"),
    SOURCE_FIELD_MISSING("SOURCE_FIELD_MISSING"),
    CONDITION_UNPARSEABLE("CONDITION_UNPARSEABLE"),
    CONDITION_MULTI_GROUP("CONDITION_MULTI_GROUP"),
    CONDITION_TOO_DEEP("CONDITION_TOO_DEEP"),
    CONDITION_FIELD_MISSING("CONDITION_FIELD_MISSING"),
    CONDITION_FIELD_NOT_FILTERABLE("CONDITION_FIELD_NOT_FILTERABLE"),
    CONDITION_UNSUPPORTED("CONDITION_UNSUPPORTED"),
    PENDING_ROW_VALUE("PENDING_ROW_VALUE"),
    CURRENT_FIELD_MULTI_VALUE("CURRENT_FIELD_MULTI_VALUE"),
    NO_MATCH("NO_MATCH"),
    TOO_MANY_ROWS("TOO_MANY_ROWS"),
    MULTI_ROW_ERROR("MULTI_ROW_ERROR"),
    MULTI_ROW_MODE_REJECTED("MULTI_ROW_MODE_REJECTED"),
    MULTI_ROW_MODE_UNKNOWN("MULTI_ROW_MODE_UNKNOWN"),
    SUM_NOT_NUMERIC("SUM_NOT_NUMERIC"),
    UNSUPPORTED_VALUE_KIND("UNSUPPORTED_VALUE_KIND"),
    OPTION_SOURCE_MODE_UNKNOWN("OPTION_SOURCE_MODE_UNKNOWN"),
    SOURCE_FIELD_HAS_NO_OPTIONS("SOURCE_FIELD_HAS_NO_OPTIONS"),
    VALUE_TYPE_MISMATCH("VALUE_TYPE_MISMATCH"),
    NOT_APPLICABLE("NOT_APPLICABLE"),
    ROUNDING_MODE_UNKNOWN("ROUNDING_MODE_UNKNOWN");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(FieldRuleStateEnum.class);

    FieldRuleStateEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static FieldRuleStateEnum fromCode(String code) {
        return NocodeCodeEnum.require(FieldRuleStateEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
