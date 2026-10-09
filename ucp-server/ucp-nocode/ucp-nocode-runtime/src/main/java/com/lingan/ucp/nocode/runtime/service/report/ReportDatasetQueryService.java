package com.lingan.ucp.nocode.runtime.service.report;

import com.lingan.ucp.nocode.api.ApplicationReports;
import com.lingan.ucp.nocode.api.ReportDashboards;
import com.lingan.ucp.nocode.api.ReportDatasetQueries;
import com.lingan.ucp.nocode.report.service.authorization.ReportDatasetAuthorizationService.ApplicationGate;
import com.lingan.ucp.nocode.runtime.dal.query.ReportStatement;

import java.util.function.Function;

/** 数据集取数由授权服务掌管事务和身份复核；不得在外层增加事务或提前交付结果。 */
public interface ReportDatasetQueryService {
    ReportDatasetQueries.OptionPage options(ReportDatasetQueries.Options request, long actor);

    ApplicationReports.Result chart(ReportDashboards.Chart chart, boolean exporting, long actor);

    ReportDashboards.DetailPage details(
            ReportDashboards.Chart chart, ReportDashboards.Details request, long actor);

    ApplicationReports.Result chart(
            ReportDashboards.Execution execution, boolean exporting, long actor);

    ApplicationReports.Result chart(
            ReportDashboards.Execution execution,
            ApplicationGate gate,
            boolean exporting,
            long actor);

    /** 回调必须在指定应用及数据集授权事务内完成实际记录 SQL，再沿原路径复核身份。 */
    <T> T withBusinessDetails(
            ReportDashboards.Execution execution,
            ReportDashboards.Details request,
            ApplicationGate gate,
            String objectId,
            long actor,
            Function<ReportStatement, T> action);

    ReportDashboards.DetailPage details(
            ReportDashboards.Execution execution, ReportDashboards.Details request, long actor);

    ReportDashboards.DetailPage details(
            ReportDashboards.Execution execution,
            ReportDashboards.Details request,
            ApplicationGate gate,
            long actor);

    ReportDatasetQueries.OptionPage options(
            ReportDashboards.Execution execution,
            String fieldId,
            int pageNo,
            int pageSize,
            String search,
            long actor);

    ReportDatasetQueries.OptionPage options(
            ReportDashboards.Execution execution,
            String fieldId,
            int pageNo,
            int pageSize,
            String search,
            ApplicationGate gate,
            long actor);

    void checkChartAccess(ReportDashboards.Chart chart, long actor);

    void checkChartAccess(ReportDashboards.Resolved resolved, long actor);

    void checkChartAccess(ReportDashboards.Resolved resolved, ApplicationGate gate, long actor);

    ApplicationReports.Result query(ReportDatasetQueries.Query request, long actor);
}
