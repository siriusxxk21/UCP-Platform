package com.richuang.os.nocode.api;

import java.util.List;

/** 应用通过领域 API 校验固定引用并登记依赖，不反向依赖报表实现或 Mapper。 */
public interface ReportCatalogApi {
    /** 应用设计器使用当前 VIEW 权限发现固定发布 pin，不要求数据集制作 USE。 */
    com.richuang.os.framework.common.pojo.PageResult<ApplicationDashboards.Candidate> page(
            int page, int size, String search, long actor);

    /** 必须通过 VIEW；逐份固定对象定义验证兼容，不授予业务读取权或生成默认上限。 */
    ApplicationDashboards.Catalog fixed(
            ApplicationDashboards.Reference reference,
            List<ApplicationCenter.ObjectReference> objects,
            long actor);

    /** 调用方须处于应用写事务并已持设计锁；只替换草稿依赖，保留所有历史版本。 */
    void registerApplicationDraft(
            String applicationId,
            List<ApplicationDashboards.Reference> references,
            List<ApplicationCenter.ObjectReference> objects,
            long actor);

    /** 每次发布、恢复发布单独登记；应用回收和停用也不能释放可恢复历史引用。 */
    void registerApplicationVersion(
            String applicationId,
            int versionNo,
            List<ApplicationDashboards.Reference> references,
            List<ApplicationCenter.ObjectReference> objects,
            long actor);
}
