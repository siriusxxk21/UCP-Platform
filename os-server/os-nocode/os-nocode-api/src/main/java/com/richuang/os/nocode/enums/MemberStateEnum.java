package com.richuang.os.nocode.enums;

import java.util.Set;

/** 字段和内部明细的启停状态；停用保留物理数据。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum MemberStateEnum implements NocodeCodeEnum {
    ACTIVE("ACTIVE"),
    INACTIVE("INACTIVE");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(MemberStateEnum.class);

    MemberStateEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static MemberStateEnum fromCode(String code) {
        return NocodeCodeEnum.require(MemberStateEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
