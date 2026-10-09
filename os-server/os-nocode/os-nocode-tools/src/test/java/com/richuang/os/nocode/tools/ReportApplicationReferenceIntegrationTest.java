package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.richuang.os.module.system.api.permission.PermissionApi;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.report.dal.mapper.ReportCatalogMapper;
import com.richuang.os.nocode.report.service.authorization.ReportDatasetAuthorizationService;
import com.richuang.os.nocode.report.service.catalog.ReportCatalogServiceImpl;
import com.richuang.os.nocode.report.service.dashboard.ReportDashboardService;
import com.richuang.os.nocode.report.service.dataset.ReportDatasetService;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 正式应用保存/发布链路验证固定看板引用；当前开发库每例回滚，仅清理随机前缀对象。 */
class ReportApplicationReferenceIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private AnnotationConfigApplicationContext reportContext;
    private AnnotationConfigApplicationContext catalogContext;
    private ReportCatalogApi catalog;
    private ReportDashboardService boards;
    private ReportDatasetService datasets;
    private ReportDatasetAuthorizationService authorization;
    private ApplicationService applications;
    private DataObjectApi objects;
    private final ObjectMapper json = new ObjectMapper();

    private record Seed(
            ApplicationCenter.ObjectReference object,
            String field,
            ReportDatasets.Release dataset) {}

    @BeforeAll
    static void open() throws Exception {
        connect();
    }

    @AfterAll
    static void shutdown() {
        close();
    }

    @BeforeEach
    void setup() {
        fixture = new NocodeIntegrationSupport();
        fixture.name();
        reportContext = ReportIntegrationSupport.context();
        catalogContext = new AnnotationConfigApplicationContext();
        catalogContext.setParent(reportContext);
        catalogContext.registerBean(
                ReportCatalogMapper.class, () -> session.getMapper(ReportCatalogMapper.class));
        catalogContext.register(ReportCatalogServiceImpl.class);
        catalogContext.refresh();
        catalog = catalogContext.getBean(ReportCatalogApi.class);
        // 应用容器的可选 Provider 看到同一个真实目录，不使用应用测试替代实现。
        servicesContext
                .getBeanFactory()
                .registerSingleton("reportApplicationCatalogForTest", catalog);
        boards = reportContext.getBean(ReportDashboardService.class);
        datasets = reportContext.getBean(ReportDatasetService.class);
        authorization = reportContext.getBean(ReportDatasetAuthorizationService.class);
        applications = servicesContext.getBean(ApplicationService.class);
        objects = servicesContext.getBean(DataObjectApi.class);
        PermissionApi permissions = servicesContext.getBean(PermissionApi.class);
        reset(permissions, servicesContext.getBean(AdminUserApi.class));
        ReportIntegrationSupport.activeUsers(10001, 10002, 10003);
        for (long actor : List.of(10001L, 10002L, 10003L)) {
            for (String action :
                    List.of("query", "create", "update", "publish", "manage", "authorize"))
                when(permissions.hasAnyPermissions(actor, "nocode:report:" + action))
                        .thenReturn(true);
            when(permissions.hasAnyPermissions(actor, "nocode:object:query")).thenReturn(true);
        }
        when(permissions.hasAnyPermissions(10003L, "nocode:object:share")).thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        servicesContext
                .getDefaultListableBeanFactory()
                .destroySingleton("reportApplicationCatalogForTest");
        catalogContext.close();
        reportContext.close();
        writeFailure.clear();
        fixture.clean();
    }

    @Test
    void keepsPinnedHistoryAndAllApplicationVersionsAcrossDraftRemovalRestoreAndRecycle() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Release first = board(seed, "第一版");
                    ApplicationDashboards.Reference pin = reference(first);
                    ApplicationCenter.Detail saved = save(null, "app", definition(seed, pin));
                    String appId = saved.application().id();
                    assertThat(dependencies(appId, "DRAFT")).containsExactly(0);
                    ApplicationCenter.Detail published =
                            applications.publish(
                                    new ApplicationCenter.Revision(
                                            appId, saved.application().revision(), "应用第一版"),
                                    10001);
                    assertThat(dependencies(appId, "VERSION")).containsExactly(1);
                    ReportDashboards.Detail head = boards.get(first.id(), 10001);
                    ReportDashboards.Content changed =
                            new ReportDashboards.Content(
                                    1,
                                    fixture.prefix + "第二版",
                                    "",
                                    head.draft().charts(),
                                    head.draft().filters());
                    ReportDashboards.Detail edited =
                            boards.save(
                                    new ReportDashboards.Save(first.id(), head.revision(), changed),
                                    10001);
                    ReportDashboards.Release second =
                            boards.publish(
                                    new ReportDashboards.Publish(
                                            first.id(), edited.revision(), "第二版"),
                                    10001);
                    assertThat(second.versionNo()).isEqualTo(2);
                    assertThat(catalog.fixed(pin, List.of(seed.object()), 10001).content().name())
                            .endsWith("第一版");
                    ApplicationCenter.Detail removed =
                            save(
                                    published,
                                    "app",
                                    new ApplicationCenter.Definition(
                                            List.of(seed.object()), List.of()));
                    assertThat(dependencies(appId, "DRAFT")).isEmpty();
                    ApplicationCenter.Detail empty =
                            applications.publish(
                                    new ApplicationCenter.Revision(
                                            appId, removed.application().revision(), "移除当前引用"),
                                    10001);
                    assertThat(dependencies(appId, "VERSION")).containsExactly(1);
                    ApplicationCenter.Detail restored =
                            applications.restore(
                                    new ApplicationCenter.Restore(
                                            appId, empty.application().revision(), 1, "恢复固定引用"),
                                    10001);
                    assertThat(restored.application().publishedVersion()).isEqualTo(3);
                    assertThat(dependencies(appId, "VERSION")).containsExactly(1, 3);
                    applications.delete(
                            new ApplicationCenter.Revision(
                                    appId, restored.application().revision(), "回收应用"),
                            10001);
                    assertThat(dependencies(appId, "VERSION")).containsExactly(1, 3);
                    assertThat(boards.deletePreview(first.id(), 10001).canDelete()).isFalse();
                    ApplicationCenter.Detail copied = save(null, "copy", definition(seed, pin));
                    assertThat(copied.application().id()).isNotEqualTo(appId);
                    assertThat(dependencies(copied.application().id(), "DRAFT")).containsExactly(0);
                });
    }

    @Test
    void requiresViewAndExactChecksumWithoutAddingMissingObjectsOrReportDataGrants() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Release release = board(seed, "只读目录");
                    ApplicationDashboards.Reference pin = reference(release);
                    assertThatThrownBy(() -> catalog.fixed(pin, List.of(seed.object()), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    boards.saveResource(
                            new ReportAuthorization.SaveDashboardResource(
                                    release.id(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("VIEW"))),
                                    "只授予查看"),
                            10001);
                    assertThat(catalog.fixed(pin, List.of(seed.object()), 10002).reference())
                            .isEqualTo(pin);
                    assertThatThrownBy(
                                    () ->
                                            catalog.fixed(
                                                    new ApplicationDashboards.Reference(
                                                            pin.id(),
                                                            pin.versionNo(),
                                                            "0".repeat(64)),
                                                    List.of(seed.object()),
                                                    10001))
                            .hasMessageContaining("校验和");
                    long grants =
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.nocode_report_object_grant WHERE"
                                            + " dataset_id=?",
                                    Long.class,
                                    Long.parseLong(seed.dataset().datasetId()));
                    ApplicationCenter.Definition missing =
                            new ApplicationCenter.Definition(
                                    List.of(), definition(seed, pin).resources());
                    assertThatThrownBy(() -> save(null, "missing", missing))
                            .hasMessageContaining("尚未加入应用");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.nocode_application WHERE"
                                                    + " app_code=?",
                                            Long.class,
                                            fixture.prefix + "missing"))
                            .isZero();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.nocode_report_object_grant"
                                                    + " WHERE dataset_id=?",
                                            Long.class,
                                            Long.parseLong(seed.dataset().datasetId())))
                            .isEqualTo(grants);
                });
    }

    @Test
    void rejectsDependencyRegistrationOutsideTheApplicationTransaction() {
        assertThatThrownBy(() -> catalog.registerApplicationDraft("1", List.of(), List.of(), 10001))
                .hasMessageContaining("同一事务");
    }

    @Test
    void discoversPublishedPinsUsingViewWithoutReportMenuPermissionAndFiltersRevocation() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Release published = board(seed, "候选发布名称");
                    ReportDashboards.Detail head = boards.get(published.id(), 10001);
                    boards.save(
                            new ReportDashboards.Save(
                                    head.id(),
                                    head.revision(),
                                    new ReportDashboards.Content(
                                            1,
                                            fixture.prefix + "秘密草稿名称",
                                            "",
                                            head.draft().charts(),
                                            head.draft().filters())),
                            10001);
                    when(servicesContext
                                    .getBean(PermissionApi.class)
                                    .hasAnyPermissions(10002L, "nocode:report:query"))
                            .thenReturn(false);
                    assertThat(catalog.page(1, 100, fixture.prefix, 10002).getList()).isEmpty();
                    ReportAuthorization.ResourcePolicy acl =
                            boards.saveResource(
                                    new ReportAuthorization.SaveDashboardResource(
                                            published.id(),
                                            0,
                                            List.of(
                                                    new ReportAuthorization.ResourceMember(
                                                            "USER", "10002", Set.of("VIEW"))),
                                            "候选只读"),
                                    10001);
                    assertThat(catalog.page(1, 100, fixture.prefix, 10002).getList())
                            .containsExactly(
                                    new ApplicationDashboards.Candidate(
                                            published.id(),
                                            published.content().name(),
                                            published.versionNo(),
                                            published.checksum(),
                                            published.content().charts().size()));
                    assertThat(catalog.page(1, 100, "秘密草稿名称", 10002).getList()).isEmpty();
                    assertThat(
                                    catalog.fixed(
                                                    reference(published),
                                                    List.of(seed.object()),
                                                    10002)
                                            .reference())
                            .isEqualTo(reference(published));
                    head = boards.get(published.id(), 10001);
                    head =
                            boards.status(
                                    new ReportDashboards.ChangeStatus(
                                            head.id(), head.revision(), "INACTIVE", "候选停用"),
                                    10001);
                    assertThat(catalog.page(1, 100, fixture.prefix, 10002).getList()).isEmpty();
                    boards.status(
                            new ReportDashboards.ChangeStatus(
                                    head.id(), head.revision(), "ACTIVE", "候选启用"),
                            10001);
                    boards.saveResource(
                            new ReportAuthorization.SaveDashboardResource(
                                    published.id(), acl.revision(), List.of(), "撤销候选查看"),
                            10001);
                    assertThat(catalog.page(1, 100, fixture.prefix, 10002).getList()).isEmpty();
                });
    }

    private Seed seed() {
        ObjectDraft draft = fixture.create("catalog");
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(draft.id(), draft.lockVersion(), null), 10001);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "应用看板来源"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi.PublishedObject fixed = objects.getVersion(draft.id(), null);
        String field = fixed.definition().fields().getFirst().id();
        ReportDatasets.Source source =
                new ReportDatasets.Source(
                        1,
                        new ReportDatasets.ObjectReference(
                                draft.id(), fixed.versionNo(), fixed.checksum()),
                        List.of(),
                        List.of(
                                new ReportDatasets.Field(
                                        "name", List.of(), field, "名称", "DIMENSION")));
        ReportDatasets.Detail data =
                datasets.save(
                        new ReportDatasets.Save(
                                null,
                                0,
                                fixture.prefix + "数据集",
                                "",
                                source,
                                new ReportDatasets.Analysis(
                                        1,
                                        List.of(
                                                new ReportDatasetQueries.Metric(
                                                        "count", "记录数", "COUNT", null)),
                                        null,
                                        Map.of(),
                                        "Asia/Shanghai")),
                        10001);
        ApplicationAuthorization.ObjectGrant grant =
                new ApplicationAuthorization.ObjectGrant(
                        draft.id(),
                        Set.of("READ", "EXPORT"),
                        "ALL",
                        Set.of(field),
                        Set.of(),
                        Set.of(),
                        Set.of());
        authorization.saveCeiling(
                new ReportAuthorization.SaveCeiling(data.id(), draft.id(), 0, grant, "明确来源上限"),
                10003);
        authorization.saveDataPolicy(
                new ReportAuthorization.SaveDataPolicy(
                        data.id(),
                        0,
                        List.of(
                                new ApplicationAuthorization.Member(
                                        "USER", "10001", List.of(grant))),
                        "来源成员"),
                10001);
        ReportDatasets.Release released =
                datasets.publish(
                        new ReportDatasets.Publish(
                                data.id(), data.revision(), "application_catalog", "固定数据版本"),
                        10001);
        return new Seed(
                new ApplicationCenter.ObjectReference(
                        draft.id(), fixed.versionNo(), fixed.checksum()),
                field,
                released);
    }

    private ReportDashboards.Release board(Seed seed, String name) {
        ReportDashboards.Chart chart =
                new ReportDashboards.Chart(
                        "table",
                        "记录统计",
                        "TABLE",
                        new ReportDashboards.Dataset(
                                seed.dataset().datasetId(),
                                seed.dataset().versionNo(),
                                seed.dataset().checksum()),
                        List.of(new ReportDatasetQueries.Dimension("name", "VALUE")),
                        List.of("count"),
                        0,
                        0,
                        12,
                        4);
        ReportDashboards.Content content =
                new ReportDashboards.Content(
                        1,
                        fixture.prefix + name,
                        "",
                        List.of(chart),
                        List.of(
                                new ReportDashboards.Filter(
                                        "names",
                                        "名称",
                                        "SELECT",
                                        List.of(new ReportDashboards.Mapping("table", "name")))));
        ReportDashboards.Detail saved =
                boards.save(new ReportDashboards.Save(null, 0, content), 10001);
        return boards.publish(
                new ReportDashboards.Publish(saved.id(), saved.revision(), "固定看板"), 10001);
    }

    private ApplicationDashboards.Reference reference(ReportDashboards.Release release) {
        return new ApplicationDashboards.Reference(
                release.id(), release.versionNo(), release.checksum());
    }

    private ApplicationCenter.Definition definition(
            Seed seed, ApplicationDashboards.Reference pin) {
        ApplicationDashboards.Config config =
                new ApplicationDashboards.Config(pin, null, List.of(), List.of());
        Map<String, Object> value =
                json.convertValue(config, new TypeReference<Map<String, Object>>() {});
        return new ApplicationCenter.Definition(
                List.of(seed.object()),
                List.of(
                        new ApplicationCenter.Resource(
                                "board", "REPORT_DASHBOARD", "board", "经营看板", value),
                        new ApplicationCenter.Resource(
                                "menu", "MENU", "menu", "看板入口", Map.of("targetId", "board"))));
    }

    private ApplicationCenter.Detail save(
            ApplicationCenter.Detail previous,
            String code,
            ApplicationCenter.Definition definition) {
        return applications.save(
                new ApplicationCenter.Save(
                        previous == null ? null : previous.application().id(),
                        previous == null ? null : previous.application().revision(),
                        fixture.prefix + code,
                        fixture.prefix + code,
                        "",
                        null,
                        definition),
                10001);
    }

    private List<Integer> dependencies(String app, String stage) {
        return jdbc.queryForList(
                "SELECT source_version FROM public.nocode_report_dependency WHERE"
                    + " source_kind='APPLICATION' AND source_id=? AND source_stage=? AND deleted=0"
                    + " ORDER BY source_version",
                Integer.class,
                Long.parseLong(app),
                stage);
    }

    private void rollback(Runnable action) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            try {
                                action.run();
                            } finally {
                                tx.setRollbackOnly();
                            }
                        });
    }
}
