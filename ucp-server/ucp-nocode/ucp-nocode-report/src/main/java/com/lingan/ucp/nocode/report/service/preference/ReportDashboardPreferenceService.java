package com.lingan.ucp.nocode.report.service.preference;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.ReportDashboardPreferences;

/** 收藏和最近访问属于当前用户；每次读写仍验证看板VIEW及启用发布状态。 */
public interface ReportDashboardPreferenceService {
    PageResult<ReportDashboardPreferences.Item> page(
            int page, int size, String search, String view, long actor);

    ReportDashboardPreferences.State get(String id, long actor);

    ReportDashboardPreferences.State favorite(
            ReportDashboardPreferences.SetFavorite request, long actor);

    ReportDashboardPreferences.State visit(ReportDashboardPreferences.Visit request, long actor);
}
