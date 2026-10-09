package com.richuang.os.nocode.enums;

import java.util.Set;

/** 对象来源；纳管既有表不自动修改其结构。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum ObjectSourceEnum implements NocodeCodeEnum {
    GENERATED("GENERATED"),
    ADOPTED("ADOPTED");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(ObjectSourceEnum.class);

    ObjectSourceEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static ObjectSourceEnum fromCode(String code) {
        return NocodeCodeEnum.require(ObjectSourceEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
