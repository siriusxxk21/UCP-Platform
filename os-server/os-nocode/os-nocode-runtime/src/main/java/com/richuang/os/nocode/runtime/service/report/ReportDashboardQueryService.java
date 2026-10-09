package com.richuang.os.nocode.runtime.service.report;

import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.report.service.authorization.ReportDatasetAuthorizationService.ApplicationGate;
import com.richuang.os.nocode.runtime.dal.query.ReportStatement;

import java.util.List;
import java.util.function.Function;

/** 仪表板图表的固定版本查询入口。 */
public interface ReportDashboardQueryService {
    /** 记录绑定按原键等值，参数绑定按发布筛选类型；两者都不能由候选排除自身解除。 */
    record BoundInput(ReportDashboards.FilterValue value, boolean exact) {}

    ApplicationReports.Result fixed(
            ReportDashboards.Resolved resolved,
            List<BoundInput> inputs,
            ApplicationGate gate,
            boolean exporting,
            long actor);

    ReportDashboards.DetailPage fixedDetails(
            ReportDashboards.Resolved resolved,
            ReportDashboards.Details request,
            List<BoundInput> inputs,
            ApplicationGate gate,
            long actor);

    /** 业务明细复用完整交互编译、数据授权和交付前复核，不接收自由看板入口。 */
    <T> T withFixedBusinessDetails(
            ReportDashboards.Resolved resolved,
            ReportDashboards.Details request,
            List<BoundInput> inputs,
            ApplicationGate gate,
            String objectId,
            long actor,
            Function<ReportStatement, T> action);

    ReportDatasetQueries.OptionPage fixedOptions(
            ReportDashboards.Resolved resolved,
            ReportDashboards.Options request,
            List<BoundInput> inputs,
            ApplicationGate gate,
            long actor);

    ApplicationReports.Result export(ReportDashboards.Query request, long actor);

    ReportDashboards.DetailPage details(ReportDashboards.Details request, long actor);

    ReportDatasetQueries.OptionPage options(ReportDashboards.Options request, long actor);

    /** 单图即时预览沿用数据集制作授权，不修改任何草稿或发布版本。 */
    ApplicationReports.Result previewChart(ReportDashboards.Chart chart, long actor);

    ApplicationReports.Result query(ReportDashboards.Query request, long actor);
}
