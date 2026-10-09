package com.lingan.ucp.nocode.enums;

import java.util.Set;

/** 数据联动命中多行时的归约档位；缺省为 CONCAT，没有“去重取值”档。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum LinkageMultiRowEnum implements NocodeCodeEnum {
    CONCAT("CONCAT"),
    FIRST("FIRST"),
    SUM("SUM"),
    ERROR("ERROR");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(LinkageMultiRowEnum.class);

    LinkageMultiRowEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static LinkageMultiRowEnum fromCode(String code) {
        return NocodeCodeEnum.require(LinkageMultiRowEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
