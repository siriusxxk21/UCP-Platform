package com.richuang.os.nocode.enums;

import java.util.Set;

/** 发布计划状态，成功执行具备幂等性。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum PublishStateEnum implements NocodeCodeEnum {
    PENDING("PENDING"),
    BLOCKED("BLOCKED"),
    SUCCEEDED("SUCCEEDED"),
    FAILED("FAILED");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(PublishStateEnum.class);

    PublishStateEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static PublishStateEnum fromCode(String code) {
        return NocodeCodeEnum.require(PublishStateEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
