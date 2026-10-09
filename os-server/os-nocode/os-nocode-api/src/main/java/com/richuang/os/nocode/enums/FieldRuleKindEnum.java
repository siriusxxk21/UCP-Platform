package com.richuang.os.nocode.enums;

import java.util.Set;

/** 对象规则的求值类别，对应求值结果中的 kind。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum FieldRuleKindEnum implements NocodeCodeEnum {
    LINKAGE("LINKAGE"),
    DEFAULT_FORMULA("DEFAULT_FORMULA"),
    REFERENCE("REFERENCE");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(FieldRuleKindEnum.class);

    FieldRuleKindEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static FieldRuleKindEnum fromCode(String code) {
        return NocodeCodeEnum.require(FieldRuleKindEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
