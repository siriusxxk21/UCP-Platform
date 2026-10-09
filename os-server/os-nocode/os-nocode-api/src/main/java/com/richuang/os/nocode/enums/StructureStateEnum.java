package com.richuang.os.nocode.enums;

import java.util.Set;

/** 已登记结构与数据库实际结构的核验状态。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum StructureStateEnum implements NocodeCodeEnum {
    UNMANAGED("UNMANAGED"),
    PENDING("PENDING"),
    MATCHED("MATCHED"),
    DRIFTED("DRIFTED");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(StructureStateEnum.class);

    StructureStateEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static StructureStateEnum fromCode(String code) {
        return NocodeCodeEnum.require(StructureStateEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
