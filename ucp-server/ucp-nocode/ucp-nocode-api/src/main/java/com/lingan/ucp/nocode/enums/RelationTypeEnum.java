package com.lingan.ucp.nocode.enums;

import java.util.Set;

/** 全局对象关系类型，内部明细由对象版本统一管理。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum RelationTypeEnum implements NocodeCodeEnum {
    REFERENCE("REFERENCE"),
    MASTER_DETAIL("MASTER_DETAIL"),
    ONE_TO_ONE("ONE_TO_ONE"),
    MANY_TO_MANY("MANY_TO_MANY");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(RelationTypeEnum.class);

    RelationTypeEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static RelationTypeEnum fromCode(String code) {
        return NocodeCodeEnum.require(RelationTypeEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
