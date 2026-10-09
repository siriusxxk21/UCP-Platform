package com.lingan.ucp.nocode.enums;

import java.util.Set;

/** 物理表纳管状态，与对象生命周期分别表示。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum TableManagementEnum implements NocodeCodeEnum {
    UNMANAGED("UNMANAGED"),
    PENDING("PENDING"),
    GENERATED("GENERATED"),
    ADOPTED("ADOPTED"),
    DISABLED("DISABLED");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(TableManagementEnum.class);

    TableManagementEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static TableManagementEnum fromCode(String code) {
        return NocodeCodeEnum.require(TableManagementEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
