package com.lingan.ucp.nocode.report.service.dashboard;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.ReportAuthorization;
import com.lingan.ucp.nocode.api.ReportDashboards;

/** 设计、发布和运行配置入口；资源权限扩展集中于此，数据权限由数据集执行器保留。 */
public interface ReportDashboardService {
    PageResult<ReportDashboards.Detail> page(int page, int size, String search, long actor);

    PageResult<ReportDashboards.AvailableItem> availablePage(
            int page, int size, String search, long actor);

    PageResult<ReportDashboards.AvailableItem> availablePage(
            int page, int size, String search, String folderId, String status, long actor);

    /** 工作台统一读取当前发布摘要，即使设计者也不暴露未发布草稿差异。 */
    ReportDashboards.AvailableItem publishedSummary(String id, long actor);

    ReportAuthorization.ResourcePolicy resourcePolicy(String id, long actor);

    ReportAuthorization.ResourcePolicy saveResource(
            ReportAuthorization.SaveDashboardResource request, long actor);

    ReportDashboards.Detail get(String id, long actor);

    /** 只校验单图配置，不持久化；始终要求数据集 USE，不借用看板查看权限。 */
    ReportDashboards.Chart previewChart(ReportDashboards.Chart chart, long actor);

    ReportDashboards.Detail save(ReportDashboards.Save request, long actor);

    ReportDashboards.Detail copy(ReportDashboards.Copy request, long actor);

    ReportDashboards.Detail move(ReportDashboards.Move request, long actor);

    ReportDashboards.Detail status(ReportDashboards.ChangeStatus request, long actor);

    ReportDashboards.Detail restore(ReportDashboards.Restore request, long actor);

    PageResult<ReportDashboards.Release> releases(String id, int page, int size, long actor);

    ReportDashboards.DeletePreview deletePreview(String id, long actor);

    ReportDashboards.Deleted delete(ReportDashboards.Delete request, long actor);

    ReportDashboards.Release publish(ReportDashboards.Publish request, long actor);

    ReportDashboards.Release published(String id, Integer version, String checksum, long actor);

    /** 应用内部固定引用入口，调用方已从可信应用配置取得精确版本；HTTP 不映射此方法。 */
    ReportDashboards.Release publishedFixed(String id, int version, String checksum, long actor);

    ReportDashboards.Resolved resolve(ReportDashboards.Query request, long actor);

    ReportDashboards.Resolved resolve(
            ReportDashboards.Query request, long actor, boolean exporting);

    /** 内部固定历史版本入口，仍检查资源状态、ACL 和数据权限，不接受草稿预览。 */
    ReportDashboards.Resolved resolveFixed(
            ReportDashboards.Query request, long actor, boolean exporting);

    /** 受控二次授权保持服务端已解析入口；HTTP 不接收 Resolved。 */
    ReportDashboards.Resolved refresh(
            ReportDashboards.Resolved resolved, long actor, boolean exporting);

    void recheck(ReportDashboards.Resolved resolved, long actor);

    void recheck(ReportDashboards.Resolved resolved, long actor, boolean exporting);
}
