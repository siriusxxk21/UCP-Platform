package com.lingan.ucp.nocode.enums;

/** 报表资源审计操作，固定编码持久化。 */
public enum ReportAuditOperationEnum implements NocodeCodeEnum {
    CREATE("CREATE"),
    COPY("COPY"),
    DELETE("DELETE"),
    SAVE("SAVE"),
    MOVE("MOVE"),
    PUBLISH("PUBLISH"),
    RESTORE("RESTORE"),
    STATUS("STATUS"),
    AUTHORIZE("AUTHORIZE");
    private final String code;

    ReportAuditOperationEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
