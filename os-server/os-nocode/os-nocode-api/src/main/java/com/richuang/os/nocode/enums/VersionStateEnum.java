package com.richuang.os.nocode.enums;

import java.util.Set;

/** 对象版本状态；已发布版本不可修改。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum VersionStateEnum implements NocodeCodeEnum {
    DRAFT("DRAFT"),
    PUBLISHED("PUBLISHED");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(VersionStateEnum.class);

    VersionStateEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static VersionStateEnum fromCode(String code) {
        return NocodeCodeEnum.require(VersionStateEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
