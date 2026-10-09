package com.lingan.ucp.nocode.enums;

/** 工作台个人分类使用固定编码，不把收藏或访问记录当作授权。 */
public enum ReportDashboardPreferenceViewEnum implements NocodeCodeEnum {
    ALL("ALL"),
    FAVORITE("FAVORITE"),
    RECENT("RECENT");

    private final String code;

    ReportDashboardPreferenceViewEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ReportDashboardPreferenceViewEnum fromCode(String code) {
        return NocodeCodeEnum.require(ReportDashboardPreferenceViewEnum.class, code);
    }
}
