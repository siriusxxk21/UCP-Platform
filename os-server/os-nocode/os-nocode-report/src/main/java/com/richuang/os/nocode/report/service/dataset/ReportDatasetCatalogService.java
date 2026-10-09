package com.richuang.os.nocode.report.service.dataset;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.nocode.api.ReportDatasetCatalog;

/** 报表结构候选沿用数据中心发现权限；数据管理员使用独立摘要入口定位授权目标。 */
public interface ReportDatasetCatalogService {
    PageResult<ReportDatasetCatalog.ObjectItem> objects(
            int pageNo, int pageSize, String search, long actor);

    ReportDatasetCatalog.ObjectVersion object(String id, Integer versionNo, long actor);

    PageResult<ReportDatasetCatalog.AuthorizationTarget> authorizationTargets(
            int pageNo, int pageSize, String search, long actor);

    /** 对象共享管理员独立发现草稿、历史版本及遗留上限的对象，不需要报表制作权限。 */
    java.util.List<ReportDatasetCatalog.AuthorizationObject> authorizationObjects(
            String id, long actor);
}
