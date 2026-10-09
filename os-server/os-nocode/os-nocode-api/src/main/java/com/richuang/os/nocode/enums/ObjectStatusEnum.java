package com.richuang.os.nocode.enums;

import java.util.Set;

/** 对象生命周期，与版本状态分开管理。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum ObjectStatusEnum implements NocodeCodeEnum {
    DRAFT("DRAFT"),
    ACTIVE("ACTIVE"),
    DISABLED("DISABLED"),
    DELETED("DELETED");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(ObjectStatusEnum.class);

    ObjectStatusEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static ObjectStatusEnum fromCode(String code) {
        return NocodeCodeEnum.require(ObjectStatusEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
