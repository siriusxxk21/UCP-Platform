package com.richuang.os.nocode.enums;

/** 受控业务页面节点，不包含任意 HTML、脚本、外部请求或组件源码。 */
public enum ApplicationNodeKindEnum implements NocodeCodeEnum {
    ROW("ROW"),
    COLUMN("COLUMN"),
    CARD("CARD"),
    TABS("TABS"),
    TAB("TAB"),
    DIVIDER("DIVIDER"),
    DETAIL("DETAIL"),
    INTERNAL_DETAIL("INTERNAL_DETAIL"),
    RELATED("RELATED"),
    ATTACHMENTS("ATTACHMENTS"),
    PROCESSES("PROCESSES"),
    TASKS("TASKS"),
    TEXT("TEXT"),
    HEADING("HEADING"),
    IMAGE("IMAGE"),
    ALERT("ALERT"),
    BUTTON("BUTTON"),
    FLEX("FLEX"),
    SPACER("SPACER"),
    VIEW("VIEW"),
    REPORT("REPORT"),
    REPORT_DASHBOARD("REPORT_DASHBOARD"),
    FORM("FORM"),
    FIELD("FIELD"),
    METRIC("METRIC"),
    /** 设计引擎区块：iframe 嵌入外部设计服务，固定绑定页面当前记录（laneEG）。 */
    ENGINE("ENGINE");
    private final String code;

    ApplicationNodeKindEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ApplicationNodeKindEnum fromCode(String code) {
        return NocodeCodeEnum.require(ApplicationNodeKindEnum.class, code);
    }
}
