package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.mockito.Mockito.*;

import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.module.system.api.user.dto.AdminUserRespDTO;
import com.lingan.ucp.nocode.report.dal.mapper.*;
import com.lingan.ucp.nocode.report.service.authorization.*;
import com.lingan.ucp.nocode.report.service.dataset.*;

import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** 报表专项测试使用真实 Mapper 和事务；只模拟底座身份 API，不绕过报表授权规则。 */
final class ReportIntegrationSupport {
    private ReportIntegrationSupport() {}

    static AnnotationConfigApplicationContext context() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.setParent(servicesContext);
        context.registerBean(
                ReportDatasetMapper.class, () -> session.getMapper(ReportDatasetMapper.class));
        context.registerBean(
                ReportFolderMapper.class, () -> session.getMapper(ReportFolderMapper.class));
        context.register(com.lingan.ucp.nocode.report.service.folder.ReportFolderServiceImpl.class);
        context.registerBean(
                ReportAuthorizationMapper.class,
                () -> session.getMapper(ReportAuthorizationMapper.class));
        context.register(
                com.lingan.ucp.nocode.runtime.service.report.ReportDatasetQueryServiceImpl.class,
                ReportDatasetUsage.class,
                ReportSourcePermissions.class,
                ReportDatasetAnalysis.class,
                ReportDatasetCatalogServiceImpl.class,
                ReportDatasetSourceServiceImpl.class,
                ReportDatasetServiceImpl.class,
                ReportDatasetAuthorizationServiceImpl.class,
                ReportResourceAccess.class,
                ReportAuthorizationDependencies.class,
                ReportPrincipals.class,
                ReportJson.class);
        context.registerBean(
                ReportDashboardMapper.class, () -> session.getMapper(ReportDashboardMapper.class));
        context.register(
                com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardServiceImpl.class,
                com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardNavigation.class,
                com.lingan.ucp.nocode.runtime.service.report.ReportDashboardQueryServiceImpl.class);
        context.refresh();
        return context;
    }

    static void activeUsers(long... ids) {
        AdminUserApi users = servicesContext.getBean(AdminUserApi.class);
        for (long id : ids) {
            AdminUserRespDTO user = new AdminUserRespDTO();
            user.setId(id);
            user.setStatus(0);
            when(users.getUser(id)).thenReturn(user);
        }
    }

    static void clearAuthorization(String id) {
        long value = Long.parseLong(id);
        jdbc.update("DELETE FROM public.nocode_report_object_grant WHERE dataset_id=?", value);
        jdbc.update("DELETE FROM public.nocode_report_dataset_policy WHERE dataset_id=?", value);
        jdbc.update(
                "DELETE FROM public.nocode_report_resource_acl WHERE resource_kind='DATASET' AND"
                        + " resource_id=?",
                value);
    }
}
