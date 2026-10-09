package com.lingan.ucp.nocode.enums;

import java.util.Set;

/** 物理表在对象中的职责。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum TableRoleEnum implements NocodeCodeEnum {
    UNMANAGED("UNMANAGED"),
    MAIN("MAIN"),
    DETAIL("DETAIL"),
    RELATION("RELATION");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(TableRoleEnum.class);

    TableRoleEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static TableRoleEnum fromCode(String code) {
        return NocodeCodeEnum.require(TableRoleEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
