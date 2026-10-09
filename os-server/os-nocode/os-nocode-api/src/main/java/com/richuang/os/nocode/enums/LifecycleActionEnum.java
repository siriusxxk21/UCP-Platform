package com.richuang.os.nocode.enums;

import java.util.Set;

/** 对象治理操作，保留 HTTP 接口的小写编码。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum LifecycleActionEnum implements NocodeCodeEnum {
    ENABLE("enable"),
    DISABLE("disable"),
    DELETE("delete");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(LifecycleActionEnum.class);

    LifecycleActionEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static LifecycleActionEnum fromCode(String code) {
        return NocodeCodeEnum.require(LifecycleActionEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
