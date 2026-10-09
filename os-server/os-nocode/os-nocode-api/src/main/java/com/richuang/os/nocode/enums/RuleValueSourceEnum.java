package com.richuang.os.nocode.enums;

import java.util.Set;

/**
 * 对象规则条件的取值来源：固定值、当前记录字段，或当前记录本身（CURRENT_RECORD，只用于开启自动更新的数据联动）；不进入授权范围的动态值来源。
 * 编码显式固定，不依赖枚举名称或序号持久化。
 */
public enum RuleValueSourceEnum implements NocodeCodeEnum {
    CONSTANT("CONSTANT"),
    FORM_FIELD("FORM_FIELD"),
    CURRENT_RECORD("CURRENT_RECORD");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(RuleValueSourceEnum.class);

    RuleValueSourceEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static RuleValueSourceEnum fromCode(String code) {
        return NocodeCodeEnum.require(RuleValueSourceEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
