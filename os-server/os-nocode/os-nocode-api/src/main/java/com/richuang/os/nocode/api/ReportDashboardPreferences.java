package com.richuang.os.nocode.api;

/** 个人分类只附加在安全发布摘要上，不授予资源或数据访问权；身份由当前会话决定。 */
public final class ReportDashboardPreferences {
    private ReportDashboardPreferences() {}

    public record State(boolean favorite, String lastVisitedAt) {}

    public record Item(
            ReportDashboards.AvailableItem dashboard, boolean favorite, String lastVisitedAt) {}

    public record SetFavorite(String id, Boolean favorite) {}

    public record Visit(String id) {}
}
