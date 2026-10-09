package com.richuang.os.nocode.runtime.service.report;

import com.richuang.os.nocode.api.ApplicationDashboards;
import com.richuang.os.nocode.api.ApplicationRecords;
import com.richuang.os.nocode.api.ApplicationReports;
import com.richuang.os.nocode.api.ReportDashboards;
import com.richuang.os.nocode.api.ReportDatasetQueries;
import com.richuang.os.nocode.runtime.dal.query.ReportStatement;

import java.util.function.Function;

/** 应用固定看板运行入口；所有资源、版本和绑定都从应用当前发布快照还原。 */
public interface ApplicationDashboardRuntimeService {
    ApplicationDashboards.Model model(String applicationId, String resourceId, long actor);

    boolean available(String applicationId, String resourceId, long actor);

    ApplicationReports.Result query(ApplicationDashboards.Query request, long actor);

    ApplicationReports.Result export(ApplicationDashboards.Query request, long actor);

    ReportDatasetQueries.OptionPage options(ApplicationDashboards.Options request, long actor);

    ReportDashboards.DetailPage details(ApplicationDashboards.Details request, long actor);

    /** 在完整看板授权事务内调用业务视图查询，范围 SQL 不得带出事务后执行。 */
    <T> T withBusinessDetails(
            ApplicationRecords.Query request, long actor, Function<ReportStatement, T> action);
}
