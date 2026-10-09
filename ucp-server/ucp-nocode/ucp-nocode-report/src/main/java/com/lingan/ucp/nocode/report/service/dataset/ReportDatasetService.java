package com.lingan.ucp.nocode.report.service.dataset;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.nocode.api.ReportDatasets;

/** 数据集设计生命周期；不执行记录查询，资源管理权限不授予业务读取权限。 */
public interface ReportDatasetService {
    ReportDatasets.Detail save(ReportDatasets.Save command, long actor);

    ReportDatasets.Detail move(ReportDatasets.Move command, long actor);

    PageResult<ReportDatasets.Detail> page(
            int pageNo, int pageSize, String search, String folderId, long actor);

    ReportDatasets.Detail copy(ReportDatasets.Copy command, long actor);

    ReportDatasets.DeletePreview deletePreview(String id, long actor);

    ReportDatasets.Deleted delete(ReportDatasets.Delete command, long actor);

    ReportDatasets.Detail get(String id, long actor);

    PageResult<ReportDatasets.Detail> page(int pageNo, int pageSize, String search, long actor);

    ReportDatasets.Release publish(ReportDatasets.Publish command, long actor);

    PageResult<ReportDatasets.Release> releases(String id, int pageNo, int pageSize, long actor);

    ReportDatasets.Detail restore(ReportDatasets.Restore command, long actor);

    ReportDatasets.Detail status(ReportDatasets.ChangeStatus command, long actor);
}
