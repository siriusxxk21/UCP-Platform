package com.richuang.os.nocode.enums;

import java.util.Set;

/** 数据中心操作审计编码，不包含业务数据内容。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum AuditOperationEnum implements NocodeCodeEnum {
    OBJECT_ADOPTION_DRAFT("OBJECT_ADOPTION_DRAFT"),
    OBJECT_COPY("OBJECT_COPY"),
    OBJECT_CREATE("OBJECT_CREATE"),
    OBJECT_DELETE("OBJECT_DELETE"),
    OBJECT_DEPENDENCY_REGISTER("OBJECT_DEPENDENCY_REGISTER"),
    OBJECT_DISABLE("OBJECT_DISABLE"),
    OBJECT_DRAFT_OPEN("OBJECT_DRAFT_OPEN"),
    OBJECT_DRAFT_SAVE("OBJECT_DRAFT_SAVE"),
    OBJECT_ENABLE("OBJECT_ENABLE"),
    OBJECT_PUBLISH("OBJECT_PUBLISH"),
    OBJECT_PUBLISH_FAILED("OBJECT_PUBLISH_FAILED"),
    OBJECT_PUBLISH_PLAN("OBJECT_PUBLISH_PLAN"),
    OBJECT_RECONCILIATION_DRAFT("OBJECT_RECONCILIATION_DRAFT"),
    OBJECT_STRUCTURE_VERIFY("OBJECT_STRUCTURE_VERIFY");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(AuditOperationEnum.class);

    AuditOperationEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static AuditOperationEnum fromCode(String code) {
        return NocodeCodeEnum.require(AuditOperationEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
