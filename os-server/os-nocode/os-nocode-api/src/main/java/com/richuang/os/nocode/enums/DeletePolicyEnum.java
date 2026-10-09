package com.richuang.os.nocode.enums;

import java.util.Set;

/** 关系删除策略；编译层仍校验可用策略组合。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum DeletePolicyEnum implements NocodeCodeEnum {
    RESTRICT("RESTRICT"),
    CASCADE("CASCADE"),
    SET_NULL("SET_NULL");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(DeletePolicyEnum.class);

    DeletePolicyEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static DeletePolicyEnum fromCode(String code) {
        return NocodeCodeEnum.require(DeletePolicyEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
