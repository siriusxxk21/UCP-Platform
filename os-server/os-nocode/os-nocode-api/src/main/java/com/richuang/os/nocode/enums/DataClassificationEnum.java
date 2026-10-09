package com.richuang.os.nocode.enums;

import java.util.Set;

/** 字段数据分类；受控预览按分类隐藏敏感值。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum DataClassificationEnum implements NocodeCodeEnum {
    NORMAL("NORMAL"),
    INTERNAL("INTERNAL"),
    SENSITIVE("SENSITIVE"),
    SECRET("SECRET");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(DataClassificationEnum.class);

    DataClassificationEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static DataClassificationEnum fromCode(String code) {
        return NocodeCodeEnum.require(DataClassificationEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
