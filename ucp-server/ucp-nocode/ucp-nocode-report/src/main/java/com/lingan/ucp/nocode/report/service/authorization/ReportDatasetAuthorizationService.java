package com.lingan.ucp.nocode.report.service.authorization;

import com.lingan.ucp.nocode.api.*;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 独立数据集授权入口；资源 ACL 始终生效，旧来源上限与成员策略保留兼容。 */
public interface ReportDatasetAuthorizationService {
    ReportAuthorization.ResourcePolicy resourcePolicy(String id, long actor);

    ReportAuthorization.ResourcePolicy saveResource(
            ReportAuthorization.SaveResource request, long actor);

    List<ReportAuthorization.ObjectCeiling> ceilings(String id, long actor);

    ReportAuthorization.ObjectCeiling saveCeiling(
            ReportAuthorization.SaveCeiling request, long actor);

    ReportAuthorization.DataPolicy dataPolicy(String id, long actor);

    ReportAuthorization.DataPolicy saveDataPolicy(
            ReportAuthorization.SaveDataPolicy request, long actor);

    /** 调用方已持设计锁和数据集写锁；默认仅验证来源，显式兼容模式追加对象上限校验。 */
    void requireCeilings(long datasetId, ReportDatasets.Source source);

    record Context(
            String datasetId,
            Integer versionNo,
            ReportDatasets.Content content,
            ReportDatasets.ResolvedSource source,
            Map<String, List<ApplicationAuthorization.ObjectGrant>> grants,
            Map<String, Object> scopeContext,
            boolean resourceCanExport,
            Map<String, List<ApplicationAuthorization.ObjectGrant>> applicationGrants) {
        public Context(
                String datasetId,
                Integer versionNo,
                ReportDatasets.Content content,
                ReportDatasets.ResolvedSource source,
                Map<String, List<ApplicationAuthorization.ObjectGrant>> grants,
                Map<String, Object> scopeContext,
                boolean resourceCanExport) {
            this(
                    datasetId,
                    versionNo,
                    content,
                    source,
                    grants,
                    scopeContext,
                    resourceCanExport,
                    null);
        }
    }

    /** 可信应用门卫由运行服务构造；不能经 HTTP 反序列化或用另一应用的授权代替。 */
    interface ApplicationGate {
        void lockCatalog();

        /** 设计共享锁之后、看板锁之前取得应用共享锁，并核验服务器固定 pin。 */
        void lockApplication(ReportDashboards.Resolved board, long actor);

        /** 来源对象锁之后读取指定应用有效授权，并核验记录上下文。 */
        Map<String, List<ApplicationAuthorization.ObjectGrant>> grants(
                ReportDatasets.ResolvedSource source, long actor);

        /** 主事务结束后以新事务复核发布、成员、共享上限和上下文，不提前交付结果。 */
        void recheck(long actor);
    }

    <T> T withExportAccess(
            String id,
            Integer versionNo,
            String checksum,
            boolean preview,
            long actor,
            Function<Context, T> action);

    /** 仪表板固定版本受众入口，不授予数据集设计 USE；来源上限与成员数据策略仍完整执行。 */
    <T> T withDashboardDataAccess(
            ReportDashboards.Resolved board, long actor, Function<Context, T> action);

    <T> T withDashboardExportAccess(
            ReportDashboards.Resolved board, long actor, Function<Context, T> action);

    <T> T withApplicationDashboardAccess(
            ReportDashboards.Resolved board,
            ApplicationGate gate,
            long actor,
            Function<Context, T> action,
            boolean exporting);

    /** 同步查询回调，自行管理取数事务；调用方不得另包外层事务。共享锁保持至取数结束，返回前重验身份；回调不可提前写响应或启动异步任务。 */
    <T> T withDataAccess(
            String id,
            Integer versionNo,
            String checksum,
            boolean preview,
            long actor,
            Function<Context, T> action);
}
