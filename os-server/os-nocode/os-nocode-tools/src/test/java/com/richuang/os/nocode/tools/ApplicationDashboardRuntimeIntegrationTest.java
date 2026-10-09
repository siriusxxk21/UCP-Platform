package com.richuang.os.nocode.tools;

import static com.richuang.os.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.richuang.os.framework.common.exception.ServiceException;
import com.richuang.os.module.system.api.permission.PermissionApi;
import com.richuang.os.module.system.api.permission.RoleApi;
import com.richuang.os.module.system.api.permission.dto.RoleRespDTO;
import com.richuang.os.module.system.api.user.AdminUserApi;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.api.ApplicationAuthorization.Member;
import com.richuang.os.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService;
import com.richuang.os.nocode.application.service.sharing.ObjectSharingService;
import com.richuang.os.nocode.report.dal.mapper.ReportCatalogMapper;
import com.richuang.os.nocode.report.service.authorization.ReportDatasetAuthorizationService;
import com.richuang.os.nocode.report.service.catalog.ReportCatalogServiceImpl;
import com.richuang.os.nocode.report.service.dashboard.ReportDashboardService;
import com.richuang.os.nocode.report.service.dataset.ReportDatasetService;
import com.richuang.os.nocode.runtime.dal.mapper.ReportMapper;
import com.richuang.os.nocode.runtime.service.record.RecordService;
import com.richuang.os.nocode.runtime.service.record.RuntimeSchema;
import com.richuang.os.nocode.runtime.service.report.ApplicationDashboardRuntimeService;
import com.richuang.os.nocode.runtime.service.report.ApplicationDashboardRuntimeServiceImpl;
import com.richuang.os.nocode.runtime.service.report.ReportAggregateReader;
import com.richuang.os.nocode.runtime.service.report.ReportDashboardQueryService;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** 应用固定看板走真实发布、授权和 SQL 取数；每例回滚，仅操作随机前缀夹具。 */
class ApplicationDashboardRuntimeIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private AnnotationConfigApplicationContext reportContext;
    private AnnotationConfigApplicationContext catalogContext;
    private AnnotationConfigApplicationContext runtimeContext;
    private ReportDashboardService boards;
    private ReportDatasetService datasets;
    private ReportDatasetAuthorizationService dataAuthorization;
    private ApplicationService applications;
    private ApplicationAuthorizationService appAuthorization;
    private ObjectSharingService sharing;
    private ApplicationDashboardRuntimeService runtime;
    private PermissionApi permissions;

    private record Seed(
            ApplicationCenter.ObjectReference object,
            String name,
            String amount,
            ReportDatasets.Release dataset,
            ReportDashboards.Release board,
            String ownRecord,
            String otherRecord) {}

    private record RaceOutcome<T>(T result, String rejection) {}

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
        servicesContext
                .getBeanFactory()
                .registerSingleton(
                        "applicationDashboardCatalogForTest",
                        catalogContext.getBean(ReportCatalogApi.class));
        runtimeContext = new AnnotationConfigApplicationContext();
        runtimeContext.setParent(catalogContext);
        runtimeContext.register(ApplicationDashboardRuntimeServiceImpl.class);
        runtimeContext.refresh();
        runtime = runtimeContext.getBean(ApplicationDashboardRuntimeService.class);
        servicesContext
                .getBeanFactory()
                .registerSingleton("applicationDashboardRuntimeForTest", runtime);
        boards = reportContext.getBean(ReportDashboardService.class);
        datasets = reportContext.getBean(ReportDatasetService.class);
        dataAuthorization = reportContext.getBean(ReportDatasetAuthorizationService.class);
        applications = servicesContext.getBean(ApplicationService.class);
        appAuthorization = servicesContext.getBean(ApplicationAuthorizationService.class);
        sharing = servicesContext.getBean(ObjectSharingService.class);
        permissions = servicesContext.getBean(PermissionApi.class);
        reset(
                permissions,
                servicesContext.getBean(AdminUserApi.class),
                servicesContext.getBean(RoleApi.class));
        ReportIntegrationSupport.activeUsers(10001, 10002, 10003);
        for (String action : List.of("query", "create", "update", "publish", "manage", "authorize"))
            when(permissions.hasAnyPermissions(10001L, "nocode:report:" + action)).thenReturn(true);
        when(permissions.hasAnyPermissions(10001L, "nocode:object:query")).thenReturn(true);
        when(permissions.hasAnyPermissions(10003L, "nocode:object:share")).thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        servicesContext
                .getDefaultListableBeanFactory()
                .destroySingleton("applicationDashboardRuntimeForTest");
        runtimeContext.close();
        servicesContext
                .getDefaultListableBeanFactory()
                .destroySingleton("applicationDashboardCatalogForTest");
        catalogContext.close();
        reportContext.close();
        writeFailure.clear();
        fixture.clean();
    }

    @Test
    void ordinaryMemberNeedsApplicationAndDashboardAccessButNoDatasetDataPolicy() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ApplicationCenter.Detail app = application(seed, "entry", List.of());
                    String appId = app.application().id();
                    ApplicationDashboards.Model model = runtime.model(appId, "board", 10002);
                    ApplicationDashboards.Query query = query(model, "table", Map.of(), null);
                    assertThat(runtime.available(appId, "board", 10002)).isTrue();
                    assertThat(runtime.query(query, 10002).recordCount()).isEqualTo(3);
                    assertThat(runtime.export(query, 10002).recordCount()).isEqualTo(3);
                    assertThatThrownBy(
                                    () ->
                                            reportContext
                                                    .getBean(ReportDashboardQueryService.class)
                                                    .query(
                                                            new ReportDashboards.Query(
                                                                    seed.board().id(),
                                                                    "table",
                                                                    false,
                                                                    null,
                                                                    null),
                                                            10002))
                            .isInstanceOf(AccessDeniedException.class);

                    appAuthorization.save(
                            new ApplicationAuthorization.Save(appId, 1, List.of()), 10001);
                    assertThat(runtime.available(appId, "board", 10002)).isFalse();
                    assertThatThrownBy(() -> runtime.query(query, 10002))
                            .isInstanceOf(ServiceException.class)
                            .hasMessageContaining("应用");
                    appPolicy(appId, 2, List.of(member("USER", "10002", all(seed))));

                    boards.saveResource(
                            new ReportAuthorization.SaveDashboardResource(
                                    seed.board().id(), 1, List.of(), "撤销看板查看"),
                            10001);
                    assertThat(runtime.available(appId, "board", 10002)).isFalse();
                    assertThatThrownBy(() -> runtime.query(query, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    boardPolicy(seed.board().id(), 2);

                    datasetPolicy(
                            seed.dataset().datasetId(),
                            1,
                            List.of(member("USER", "10001", all(seed))));
                    assertThat(runtime.query(query, 10002).recordCount()).isEqualTo(3);
                });
    }

    @Test
    void keepsApplicationRowFieldBindingsWithoutBorrowingAnotherApplicationGrant() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ApplicationCenter.Detail narrow = application(seed, "narrow", List.of());
                    application(seed, "other_full", List.of());
                    String appId = narrow.application().id();
                    appPolicy(
                            appId,
                            1,
                            List.of(
                                    member(
                                            "USER",
                                            "10002",
                                            scoped(
                                                    seed,
                                                    "B",
                                                    Set.of(seed.name(), seed.amount())))));
                    ApplicationDashboards.Model model = runtime.model(appId, "board", 10002);
                    assertThat(
                                    runtime.query(query(model, "table", Map.of(), null), 10002)
                                            .recordCount())
                            .isEqualTo(2);
                    role();
                    datasetPolicy(
                            seed.dataset().datasetId(),
                            1,
                            List.of(
                                    member("USER", "10001", all(seed)),
                                    member("USER", "10002", scoped(seed, "A", Set.of(seed.name()))),
                                    member(
                                            "ROLE",
                                            "200",
                                            scoped(seed, "B", Set.of(seed.amount())))));
                    ApplicationReports.Result summary =
                            runtime.query(query(model, "summary", Map.of(), null), 10002);
                    assertThat(summary.recordCount()).isEqualTo(2);
                    assertThat(new BigDecimal(summary.totals().get("sum")))
                            .isEqualByComparingTo("25.75");
                    assertThat(
                                    runtime.query(query(model, "table", Map.of(), null), 10002)
                                            .recordCount())
                            .isEqualTo(2);

                    datasetPolicy(
                            seed.dataset().datasetId(),
                            2,
                            List.of(
                                    member("USER", "10001", all(seed)),
                                    member("USER", "10002", all(seed))));
                    appPolicy(
                            appId,
                            2,
                            List.of(
                                    member("USER", "10002", scoped(seed, "A", Set.of(seed.name()))),
                                    member(
                                            "ROLE",
                                            "200",
                                            scoped(seed, "B", Set.of(seed.amount())))));
                    assertThat(
                                    runtime.query(query(model, "summary", Map.of(), null), 10002)
                                            .recordCount())
                            .isEqualTo(2);
                    assertThat(
                                    runtime.query(query(model, "table", Map.of(), null), 10002)
                                            .recordCount())
                            .isZero();
                });
    }

    @Test
    void appliesTheSpecifiedApplicationSharedCeilingToCountWithoutAUsedField() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ApplicationCenter.Detail app = application(seed, "ceiling", List.of());
                    String appId = app.application().id();
                    application(seed, "unrelated_ceiling", List.of());
                    ApplicationDashboards.Model model = runtime.model(appId, "board", 10002);
                    ObjectSharing.Grant previous =
                            sharing.forApplication(appId).stream()
                                    .filter(g -> g.objectId().equals(seed.object().objectId()))
                                    .findFirst()
                                    .orElseThrow();
                    sharing.save(
                            new ObjectSharing.Save(
                                    seed.object().objectId(),
                                    appId,
                                    previous.revision(),
                                    scoped(seed, "A", Set.of(seed.name(), seed.amount())),
                                    "当前应用仅允许 A"),
                            10003);
                    assertThat(
                                    runtime.query(query(model, "count", Map.of(), null), 10002)
                                            .recordCount())
                            .isEqualTo(1);
                    assertThat(
                                    runtime.query(query(model, "table", Map.of(), null), 10002)
                                            .recordCount())
                            .isEqualTo(1);
                });
    }

    @Test
    void rejectsThePreviousModelAcrossAllOperationsAfterTheApplicationIsRepublished() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ApplicationCenter.Detail app = application(seed, "stamp", List.of());
                    String appId = app.application().id();
                    ApplicationDashboards.Model model = runtime.model(appId, "board", 10002);
                    ApplicationDashboards.Query query = query(model, "table", Map.of(), null);
                    ApplicationCenter.Definition changed =
                            new ApplicationCenter.Definition(
                                    app.draft().objects(),
                                    app.draft().resources().stream()
                                            .map(
                                                    resource ->
                                                            new ApplicationCenter.Resource(
                                                                    resource.id(),
                                                                    resource.kind(),
                                                                    resource.code(),
                                                                    resource.name() + "更新",
                                                                    resource.config()))
                                            .toList());
                    ApplicationCenter.Detail saved = save(app, "stamp", changed);
                    applications.publish(
                            new ApplicationCenter.Revision(
                                    appId, saved.application().revision(), "更新应用看板入口"),
                            10001);
                    assertVersionChanged(() -> runtime.query(query, 10002));
                    assertVersionChanged(
                            () ->
                                    runtime.options(
                                            new ApplicationDashboards.Options(
                                                    query, "names", 1, 20, null),
                                            10002));
                    assertVersionChanged(
                            () ->
                                    runtime.details(
                                            new ApplicationDashboards.Details(
                                                    query, List.of(), List.of(), null, 1, 20),
                                            10002));
                    assertVersionChanged(() -> runtime.export(query, 10002));
                    ApplicationDashboards.Model refreshed = runtime.model(appId, "board", 10002);
                    assertThat(refreshed.stamp()).isNotEqualTo(model.stamp());
                    assertThat(
                                    runtime.query(query(refreshed, "table", Map.of(), null), 10002)
                                            .recordCount())
                            .isEqualTo(3);
                });
    }

    @Test
    void requiresNonemptyDeclaredParametersAndKeepsBindingsWhenOptionsExcludeTheirOwnFilter() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ApplicationCenter.Detail app =
                            application(
                                    seed,
                                    "parameter",
                                    List.of(
                                            new ApplicationDashboards.InputBinding(
                                                    "names", "PARAMETER", "selectedName", null)));
                    ApplicationDashboards.Model model =
                            runtime.model(app.application().id(), "board", 10002);
                    assertThat(model.boundFilterIds()).containsExactly("names");
                    assertThatThrownBy(
                                    () ->
                                            runtime.query(
                                                    query(model, "table", Map.of(), null), 10002))
                            .isInstanceOf(ServiceException.class);
                    assertThatThrownBy(
                                    () ->
                                            runtime.query(
                                                    query(
                                                            model,
                                                            "table",
                                                            Map.of(
                                                                    "selectedName",
                                                                    new ApplicationDashboards
                                                                            .InputValue(
                                                                            List.of(), null, null)),
                                                            null),
                                                    10002))
                            .isInstanceOf(ServiceException.class);
                    Map<String, ApplicationDashboards.InputValue> bound =
                            Map.of(
                                    "selectedName",
                                    new ApplicationDashboards.InputValue(List.of("B"), null, null));
                    ApplicationDashboards.Query query = query(model, "table", bound, null);
                    assertThat(runtime.query(query, 10002).recordCount()).isEqualTo(2);
                    assertThat(
                                    runtime.options(
                                                    new ApplicationDashboards.Options(
                                                            query, "names", 1, 20, null),
                                                    10002)
                                            .list())
                            .extracting(ReportDatasetQueries.Option::value)
                            .containsExactly("B");
                    assertThatThrownBy(
                                    () ->
                                            runtime.query(
                                                    withFilters(
                                                            query,
                                                            List.of(
                                                                    new ReportDashboards
                                                                            .FilterValue(
                                                                            "names",
                                                                            List.of("A"),
                                                                            null,
                                                                            null))),
                                                    10002))
                            .isInstanceOf(ServiceException.class);
                    ApplicationDashboards.Query otherFilter =
                            withFilters(
                                    query,
                                    List.of(
                                            new ReportDashboards.FilterValue(
                                                    "userNames", List.of("A"), null, null)));
                    assertThat(
                                    runtime.options(
                                                    new ApplicationDashboards.Options(
                                                            otherFilter, "names", 1, 20, null),
                                                    10002)
                                            .list())
                            .isEmpty();
                    assertThatThrownBy(
                                    () ->
                                            runtime.query(
                                                    query(
                                                            model,
                                                            "table",
                                                            Map.of(
                                                                    "undeclared",
                                                                    new ApplicationDashboards
                                                                            .InputValue(
                                                                            List.of("B"),
                                                                            null,
                                                                            null)),
                                                            null),
                                                    10002))
                            .isInstanceOf(ServiceException.class);
                });
    }

    @Test
    void readsRecordBindingsFromAnAuthorizedRowAndRejectsHiddenContextFields() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ApplicationCenter.Detail app =
                            application(
                                    seed,
                                    "record",
                                    List.of(
                                            new ApplicationDashboards.InputBinding(
                                                    "names", "RECORD_FIELD", null, seed.name())));
                    String appId = app.application().id();
                    ApplicationDashboards.Model model = runtime.model(appId, "board", 10002);
                    assertThatThrownBy(
                                    () ->
                                            runtime.query(
                                                    query(model, "table", Map.of(), null), 10002))
                            .isInstanceOf(ServiceException.class);
                    assertThat(
                                    runtime.query(
                                                    query(
                                                            model,
                                                            "table",
                                                            Map.of(),
                                                            seed.ownRecord()),
                                                    10002)
                                            .recordCount())
                            .isEqualTo(2);
                    ObjectGrant own =
                            new ObjectGrant(
                                    seed.object().objectId(),
                                    Set.of("READ"),
                                    "OWN",
                                    Set.of(seed.name(), seed.amount()),
                                    Set.of(),
                                    Set.of(),
                                    Set.of());
                    appPolicy(appId, 1, List.of(member("USER", "10002", own)));
                    assertThatThrownBy(
                                    () ->
                                            runtime.query(
                                                    query(
                                                            model,
                                                            "table",
                                                            Map.of(),
                                                            seed.otherRecord()),
                                                    10002))
                            .isInstanceOf(ServiceException.class);
                    assertThat(
                                    runtime.query(
                                                    query(
                                                            model,
                                                            "table",
                                                            Map.of(),
                                                            seed.ownRecord()),
                                                    10002)
                                            .recordCount())
                            .isEqualTo(1);
                    appPolicy(
                            appId,
                            2,
                            List.of(
                                    member(
                                            "USER",
                                            "10002",
                                            new ObjectGrant(
                                                    seed.object().objectId(),
                                                    Set.of("READ"),
                                                    "ALL",
                                                    Set.of(seed.amount()),
                                                    Set.of(),
                                                    Set.of(),
                                                    Set.of()))));
                    assertThatThrownBy(
                                    () ->
                                            runtime.query(
                                                    query(
                                                            model,
                                                            "summary",
                                                            Map.of(),
                                                            seed.ownRecord()),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class)
                            .hasMessageContaining("字段");
                });
    }

    @Test
    void businessDetailsKeepFullSqlRangeAndOriginalCapabilitiesAcrossPaginationAndViewFilters() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ApplicationCenter.Detail app =
                            businessApplication(seed, "business_range", Map.of(), false, List.of());
                    ApplicationDashboards.Model model =
                            runtime.model(app.application().id(), "board", 10002);
                    ApplicationDashboards.Drill drill =
                            new ApplicationDashboards.Drill(
                                    query(model, "table", Map.of(), null),
                                    List.of("B"),
                                    List.of(),
                                    "sum");
                    RecordService records = servicesContext.getBean(RecordService.class);
                    ApplicationRecords.Query first =
                            businessQuery(seed, model, "business_view", drill, 1, 1, Map.of());
                    ApplicationRecords.Query second =
                            businessQuery(seed, model, "business_view", drill, 2, 1, Map.of());
                    assertThat(records.page(first, 10002).getTotal()).isEqualTo(2);
                    assertThat(records.page(first, 10002).getList()).hasSize(1);
                    assertThat(records.page(second, 10002).getList()).hasSize(1);
                    assertThat(records.page(first, 10002).getList().getFirst().id())
                            .isNotEqualTo(records.page(second, 10002).getList().getFirst().id());
                    assertThat(
                                    records.page(first, 10002)
                                            .getList()
                                            .getFirst()
                                            .permissions()
                                            .actions())
                            .doesNotContain("UPDATE", "CREATE", "DELETE");
                    assertThat(
                                    records.page(
                                                    businessQuery(
                                                            seed,
                                                            model,
                                                            "business_view",
                                                            drill,
                                                            1,
                                                            20,
                                                            Map.of(seed.amount(), "20.75")),
                                                    10002)
                                            .getList())
                            .extracting(ApplicationRecords.Row::id)
                            .containsExactly(seed.ownRecord());
                    assertThatThrownBy(() -> records.export(first, 10002))
                            .isInstanceOf(ServiceException.class)
                            .hasMessageContaining("业务明细");
                    assertThatThrownBy(
                                    () ->
                                            records.save(
                                                    new ApplicationRecords.Save(
                                                            model.applicationId(),
                                                            seed.object().objectId(),
                                                            seed.ownRecord(),
                                                            records.get(
                                                                            model.applicationId(),
                                                                            seed.object()
                                                                                    .objectId(),
                                                                            seed.ownRecord(),
                                                                            10002)
                                                                    .record()
                                                                    .revision(),
                                                            Map.of(seed.amount(), "99"),
                                                            Map.of(),
                                                            Map.of(),
                                                            null),
                                                    10002))
                            .isInstanceOf(ServiceException.class);
                });
    }

    @Test
    void businessDetailsKeepApplicationAndComposedViewScopesWithoutDatasetPolicy() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ApplicationCenter.Detail app =
                            businessApplication(
                                    seed,
                                    "business_composed",
                                    Map.of(seed.name(), "B"),
                                    true,
                                    List.of());
                    appPolicy(
                            app.application().id(),
                            1,
                            List.of(
                                    member(
                                            "USER",
                                            "10002",
                                            scoped(
                                                    seed,
                                                    "B",
                                                    Set.of(seed.name(), seed.amount())))));
                    datasetPolicy(
                            seed.dataset().datasetId(),
                            1,
                            List.of(
                                    member("USER", "10001", all(seed)),
                                    member(
                                            "USER",
                                            "10002",
                                            scoped(
                                                    seed,
                                                    "A",
                                                    Set.of(seed.name(), seed.amount())))));
                    ApplicationDashboards.Model model =
                            runtime.model(app.application().id(), "board", 10002);
                    ApplicationDashboards.Drill drill =
                            new ApplicationDashboards.Drill(
                                    query(model, "table", Map.of(), null),
                                    List.of(),
                                    List.of(),
                                    null);
                    RecordService records = servicesContext.getBean(RecordService.class);
                    ApplicationRecords.Query request =
                            businessQuery(seed, model, "business_view", drill, 1, 1, Map.of());
                    assertThat(records.page(request, 10002).getTotal()).isEqualTo(2);
                    assertThat(records.dashboardDrillPage(request, 10002).drillTotal())
                            .isEqualTo(2);
                    datasetPolicy(
                            seed.dataset().datasetId(),
                            2,
                            List.of(
                                    member("USER", "10001", all(seed)),
                                    member("USER", "10002", all(seed))));
                    assertThat(records.page(request, 10002).getTotal()).isEqualTo(2);
                    assertThat(records.page(request, 10002).getList()).hasSize(1);
                    ApplicationRecords.DashboardDrillPage counted =
                            records.dashboardDrillPage(request, 10002);
                    assertThat(counted.drillTotal()).isEqualTo(2);
                    assertThat(counted.drillDifferentGrain()).isFalse();
                });
    }

    @Test
    void businessCountExplainsOrdinaryAndRootViewConditionsWithoutChangingTheDashboardScope() {
        rollback(
                () -> {
                    Seed seed = seed();
                    RecordService records = servicesContext.getBean(RecordService.class);
                    for (boolean composed : List.of(false, true)) {
                        ApplicationCenter.Detail app =
                                businessApplication(
                                        seed,
                                        "count_" + composed,
                                        Map.of(seed.name(), "B"),
                                        composed,
                                        List.of());
                        ApplicationDashboards.Model model =
                                runtime.model(app.application().id(), "board", 10002);
                        ApplicationDashboards.Drill drill =
                                new ApplicationDashboards.Drill(
                                        query(model, "table", Map.of(), null),
                                        List.of(),
                                        List.of(),
                                        null);
                        ApplicationRecords.Query request =
                                businessQuery(seed, model, "business_view", drill, 1, 1, Map.of());
                        ApplicationRecords.DashboardDrillPage counted =
                                records.dashboardDrillPage(request, 10002);
                        assertThat(counted.list()).hasSize(1);
                        assertThat(counted.total()).isEqualTo(2);
                        assertThat(counted.drillTotal()).isEqualTo(3);
                        assertThat(counted.drillDifferentGrain()).isFalse();
                        ApplicationRecords.Query searched =
                                new ApplicationRecords.Query(
                                        request.applicationId(),
                                        request.objectId(),
                                        1,
                                        20,
                                        "A",
                                        Map.of(),
                                        null,
                                        false,
                                        request.viewId(),
                                        null,
                                        null,
                                        List.of(),
                                        null,
                                        drill);
                        ApplicationRecords.DashboardDrillPage empty =
                                records.dashboardDrillPage(searched, 10002);
                        assertThat(empty.list()).isEmpty();
                        assertThat(empty.total()).isZero();
                        assertThat(empty.drillTotal()).isEqualTo(3);
                        ApplicationRecords.DashboardDrillPage filtered =
                                records.dashboardDrillPage(
                                        businessQuery(
                                                seed,
                                                model,
                                                "business_view",
                                                drill,
                                                1,
                                                20,
                                                Map.of(seed.amount(), "20.75")),
                                        10002);
                        assertThat(filtered.total()).isEqualTo(1);
                        assertThat(filtered.drillTotal()).isEqualTo(3);
                    }
                });
    }

    @Test
    void businessDetailGrainKeepsRootCountDistinctFromActualDetailRows() {
        rollback(
                () -> {
                    Seed seed = seed(true);
                    DataCenter.Definition definition =
                            servicesContext
                                    .getBean(DataObjectApi.class)
                                    .getVersion(seed.object().objectId(), seed.object().versionNo())
                                    .definition();
                    DataCenter.Detail detail = definition.details().getFirst();
                    String label = detail.fields().getFirst().id();
                    DataViews.Composition composition =
                            new DataViews.Composition(
                                    "DETAIL",
                                    detail.id(),
                                    List.of(
                                            new DataViews.Section(
                                                    "items",
                                                    "明细",
                                                    detail.id(),
                                                    null,
                                                    null,
                                                    null,
                                                    List.of(label),
                                                    null,
                                                    20,
                                                    true)),
                                    List.of(
                                            new DataViews.Column(
                                                    "view_detail_label",
                                                    "明细名称",
                                                    "items",
                                                    label,
                                                    "DETAIL",
                                                    null)));
                    ApplicationCenter.Detail app =
                            businessApplication(
                                    seed,
                                    "detail_count",
                                    Map.of(seed.name(), "B"),
                                    composition,
                                    List.of());
                    RuntimeSchema.Table table =
                            servicesContext.getBean(RuntimeSchema.class).detail(definition, detail);
                    for (String text : List.of("B1", "B2", "B3")) {
                        jdbc.update(
                                "INSERT INTO public.\""
                                        + table.name()
                                        + "\"(\""
                                        + table.binding().parentColumn()
                                        + "\",label,creator,deleted) VALUES (?::bigint,?,?,0)",
                                seed.ownRecord(),
                                text,
                                "10002");
                    }
                    ApplicationDashboards.Model model =
                            runtime.model(app.application().id(), "board", 10002);
                    ApplicationDashboards.Drill drill =
                            new ApplicationDashboards.Drill(
                                    query(model, "table", Map.of(), null),
                                    List.of("B"),
                                    List.of(),
                                    null);
                    ApplicationRecords.DashboardDrillPage counted =
                            servicesContext
                                    .getBean(RecordService.class)
                                    .dashboardDrillPage(
                                            businessQuery(
                                                    seed,
                                                    model,
                                                    "business_view",
                                                    drill,
                                                    1,
                                                    2,
                                                    Map.of()),
                                            10002);
                    assertThat(counted.list()).hasSize(2);
                    assertThat(counted.list())
                            .extracting(ApplicationRecords.Row::parentId)
                            .containsOnly(seed.ownRecord());
                    assertThat(counted.list())
                            .extracting(ApplicationRecords.Row::id)
                            .doesNotHaveDuplicates()
                            .doesNotContain(seed.ownRecord());
                    assertThat(counted.total()).isEqualTo(3);
                    assertThat(counted.drillTotal()).isEqualTo(2);
                    assertThat(counted.drillDifferentGrain()).isTrue();
                });
    }

    @Test
    void fixedQueryAndExportConvergeWithApplicationPublishDataRevocationAndDashboardStop()
            throws Exception {
        // 夹具先真实提交，线程各取独立连接；不能用外层回滚事务隐藏并发写入。
        Seed seed = new TransactionTemplate(manager).execute(tx -> seed());
        ApplicationCenter.Detail app = null;
        ReportAggregateReader reader = servicesContext.getBean(ReportAggregateReader.class);
        ReportMapper original = (ReportMapper) ReflectionTestUtils.getField(reader, "mapper");
        ReportMapper controlled =
                mock(ReportMapper.class, org.mockito.AdditionalAnswers.delegatesTo(original));
        List<Map<String, Object>> timeline = new CopyOnWriteArrayList<>();
        Map<String, Integer> pids = new ConcurrentHashMap<>();
        ThreadLocal<String> operation = new ThreadLocal<>();
        CountDownLatch sqlFinished = new CountDownLatch(2);
        CountDownLatch releaseReaders = new CountDownLatch(1);
        CountDownLatch writersStarted = new CountDownLatch(3);
        Path evidence =
                Path.of(
                        System.getProperty(
                                "report.concurrency.output", "../.work/report-p8/concurrency"),
                        fixture.prefix + ".json");
        String status = "FAILED";
        try (ExecutorService pool = Executors.newFixedThreadPool(5)) {
            app =
                    new TransactionTemplate(manager)
                            .execute(
                                    tx ->
                                            businessApplication(
                                                    seed,
                                                    "concurrent",
                                                    Map.of(),
                                                    false,
                                                    List.of()));
            String appId = app.application().id();
            int applicationRevision = app.application().revision();
            int applicationVersion = applications.published(appId).versionNo();
            ApplicationDashboards.Model model = runtime.model(appId, "board", 10002);
            ApplicationDashboards.Query query = query(model, "table", Map.of(), null);
            int boardRevision = boards.get(seed.board().id(), 10001).revision();
            doAnswer(
                            invocation -> {
                                String result = original.result(invocation.getArgument(0));
                                String name = operation.get();
                                if (name != null) {
                                    int pid =
                                            jdbc.queryForObject(
                                                    "SELECT pg_backend_pid()", Integer.class);
                                    pids.put(name, pid);
                                    raceEvent(
                                            timeline,
                                            name,
                                            "SQL_FINISHED_LOCKS_HELD",
                                            pid,
                                            Map.of());
                                    sqlFinished.countDown();
                                    if (!releaseReaders.await(10, TimeUnit.SECONDS))
                                        throw new AssertionError("并发读取未及时释放");
                                    raceEvent(timeline, name, "READ_RELEASED", pid, Map.of());
                                }
                                return result;
                            })
                    .when(controlled)
                    .result(any());
            ReflectionTestUtils.setField(reader, "mapper", controlled);
            Future<RaceOutcome<ApplicationReports.Result>> querying =
                    pool.submit(
                            () -> {
                                operation.set("query");
                                try {
                                    return raceRead(
                                            "query", () -> runtime.query(query, 10002), timeline);
                                } finally {
                                    operation.remove();
                                }
                            });
            Future<RaceOutcome<ApplicationReports.Result>> exporting =
                    pool.submit(
                            () -> {
                                operation.set("export");
                                try {
                                    return raceRead(
                                            "export", () -> runtime.export(query, 10002), timeline);
                                } finally {
                                    operation.remove();
                                }
                            });
            assertThat(sqlFinished.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(pids.values()).doesNotHaveDuplicates();
            Future<RaceOutcome<ApplicationCenter.Detail>> publishing =
                    pool.submit(
                            () ->
                                    raceWrite(
                                            "application_publish",
                                            () ->
                                                    applications.publish(
                                                            new ApplicationCenter.Revision(
                                                                    appId,
                                                                    applicationRevision,
                                                                    "并发固定应用发布"),
                                                            10001),
                                            writersStarted,
                                            pids,
                                            timeline));
            Future<RaceOutcome<ReportAuthorization.DataPolicy>> revoking =
                    pool.submit(
                            () ->
                                    raceWrite(
                                            "dataset_revoke",
                                            () ->
                                                    dataAuthorization.saveDataPolicy(
                                                            new ReportAuthorization.SaveDataPolicy(
                                                                    seed.dataset().datasetId(),
                                                                    1,
                                                                    List.of(
                                                                            member(
                                                                                    "USER", "10001",
                                                                                    all(seed))),
                                                                    "并发撤销查看者数据成员"),
                                                            10001),
                                            writersStarted,
                                            pids,
                                            timeline));
            Future<RaceOutcome<ReportDashboards.Detail>> stopping =
                    pool.submit(
                            () ->
                                    raceWrite(
                                            "dashboard_stop",
                                            () ->
                                                    boards.status(
                                                            new ReportDashboards.ChangeStatus(
                                                                    seed.board().id(),
                                                                    boardRevision,
                                                                    "INACTIVE",
                                                                    "并发停用看板"),
                                                            10001),
                                            writersStarted,
                                            pids,
                                            timeline));
            assertThat(writersStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(pids).hasSize(5);
            assertThat(pids.values()).doesNotHaveDuplicates();
            try {
                for (Future<?> writer : List.of(publishing, revoking, stopping))
                    assertThatThrownBy(() -> writer.get(200, TimeUnit.MILLISECONDS))
                            .isInstanceOf(TimeoutException.class);
                List<Map<String, Object>> locks =
                        jdbc.queryForList(
                                "SELECT pid,wait_event_type,wait_event,pg_blocking_pids(pid)::text"
                                    + " AS blockers FROM pg_stat_activity WHERE pid IN (?,?,?,?,?)",
                                pids.get("query"),
                                pids.get("export"),
                                pids.get("application_publish"),
                                pids.get("dataset_revoke"),
                                pids.get("dashboard_stop"));
                raceEvent(
                        timeline,
                        "observer",
                        "CONCURRENT_LOCK_SNAPSHOT",
                        null,
                        Map.of("connections", locks));
                for (String writer :
                        List.of("application_publish", "dataset_revoke", "dashboard_stop"))
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT cardinality(pg_blocking_pids(?))",
                                            Integer.class,
                                            pids.get(writer)))
                            .isPositive();
            } finally {
                raceEvent(timeline, "observer", "RELEASE_READERS", null, Map.of());
                releaseReaders.countDown();
            }
            RaceOutcome<ApplicationReports.Result> queried = querying.get(10, TimeUnit.SECONDS);
            RaceOutcome<ApplicationReports.Result> exported = exporting.get(10, TimeUnit.SECONDS);
            RaceOutcome<ApplicationCenter.Detail> published = publishing.get(10, TimeUnit.SECONDS);
            RaceOutcome<ReportAuthorization.DataPolicy> revoked =
                    revoking.get(10, TimeUnit.SECONDS);
            RaceOutcome<ReportDashboards.Detail> stopped = stopping.get(10, TimeUnit.SECONDS);
            for (RaceOutcome<ApplicationReports.Result> result : List.of(queried, exported))
                if (result.result() != null) assertThat(result.result().recordCount()).isEqualTo(3);
            assertThat(revoked.rejection()).isNull();
            assertThat(revoked.result().revision()).isEqualTo(2);
            assertThat(stopped.rejection()).isNull();
            assertThat(stopped.result().status()).isEqualTo("INACTIVE");
            int versionAfterRace = applications.published(appId).versionNo();
            assertThat(versionAfterRace)
                    .isEqualTo(applicationVersion + (published.result() == null ? 0 : 1));
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM public.nocode_application_version"
                                            + " WHERE application_id=? AND deleted=0",
                                    Integer.class,
                                    Long.parseLong(appId)))
                    .isEqualTo(versionAfterRace);
            RaceOutcome<ApplicationReports.Result> stoppedQuery =
                    raceRead("query_after_stop", () -> runtime.query(query, 10002), timeline);
            RaceOutcome<ApplicationReports.Result> stoppedExport =
                    raceRead("export_after_stop", () -> runtime.export(query, 10002), timeline);
            assertThat(stoppedQuery.result()).isNull();
            assertThat(stoppedExport.result()).isNull();
            ReportDashboards.Detail active =
                    boards.status(
                            new ReportDashboards.ChangeStatus(
                                    seed.board().id(),
                                    stopped.result().revision(),
                                    "ACTIVE",
                                    "并发后恢复本测试看板"),
                            10001);
            assertThat(active.status()).isEqualTo("ACTIVE");
            if (published.result() == null) {
                ApplicationCenter.Detail current = applications.get(appId);
                applications.publish(
                        new ApplicationCenter.Revision(
                                appId, current.application().revision(), "依赖恢复后重试发布"),
                        10001);
                raceEvent(timeline, "application_publish_retry", "COMMITTED", null, Map.of());
            }
            assertThatThrownBy(() -> runtime.query(query, 10002))
                    .isInstanceOfSatisfying(
                            ServiceException.class,
                            error ->
                                    assertThat(error.getCode())
                                            .isEqualTo(NocodeErrorCodes.VERSION_CHANGED));
            assertThatThrownBy(() -> runtime.export(query, 10002))
                    .isInstanceOfSatisfying(
                            ServiceException.class,
                            error ->
                                    assertThat(error.getCode())
                                            .isEqualTo(NocodeErrorCodes.VERSION_CHANGED));
            ApplicationDashboards.Model current = runtime.model(appId, "board", 10002);
            ApplicationDashboards.Query newStamp = query(current, "table", Map.of(), null);
            // 默认模式忽略旧数据集成员撤权，应用新版与看板恢复后仍按应用权限取数。
            assertThat(runtime.query(newStamp, 10002).recordCount()).isEqualTo(3);
            assertThat(runtime.export(newStamp, 10002).recordCount()).isEqualTo(3);
            raceEvent(
                    timeline,
                    "observer",
                    "POST_COMMIT_BOUNDARIES_VERIFIED",
                    null,
                    Map.of(
                            "oldStamp",
                            "VERSION_CHANGED",
                            "newStamp",
                            "APPLICATION_ACCESS_PRESERVED"));
            status = "PASSED";
        } finally {
            releaseReaders.countDown();
            ReflectionTestUtils.setField(reader, "mapper", original);
            try {
                cleanCommittedRace(seed, app == null ? null : app.application().id());
                raceEvent(timeline, "cleanup", "OWNED_METADATA_REMOVED", null, Map.of());
            } catch (RuntimeException | AssertionError failure) {
                status = "FAILED";
                raceEvent(
                        timeline,
                        "cleanup",
                        "CLEANUP_FAILED",
                        null,
                        Map.of("message", String.valueOf(failure.getMessage())));
                throw failure;
            } finally {
                Files.createDirectories(evidence.getParent());
                mapper.writerWithDefaultPrettyPrinter()
                        .writeValue(
                                evidence.toFile(),
                                Map.of(
                                        "prefix",
                                        fixture.prefix,
                                        "status",
                                        status,
                                        "fixtures",
                                        Map.of(
                                                "applicationId",
                                                app == null ? "" : app.application().id(),
                                                "datasetId",
                                                seed.dataset().datasetId(),
                                                "dashboardId",
                                                seed.board().id()),
                                        "timeline",
                                        timeline,
                                        "identityBoundary",
                                        "底座用户/角色/部门API为测试mock；业务SQL、连接、锁和提交真实执行"));
                System.out.println("A23并发证据: " + evidence.toAbsolutePath());
            }
        }
    }

    private RaceOutcome<ApplicationReports.Result> raceRead(
            String name,
            Supplier<ApplicationReports.Result> action,
            List<Map<String, Object>> timeline) {
        raceEvent(timeline, name, "REQUEST_STARTED", null, Map.of());
        try {
            ApplicationReports.Result result = action.get();
            raceEvent(
                    timeline,
                    name,
                    "REQUEST_SUCCEEDED",
                    null,
                    Map.of("recordCount", result.recordCount()));
            return new RaceOutcome<>(result, null);
        } catch (AccessDeniedException denied) {
            raceEvent(
                    timeline,
                    name,
                    "REQUEST_REJECTED",
                    null,
                    Map.of("message", denied.getMessage()));
            return new RaceOutcome<>(null, denied.getMessage());
        } catch (ServiceException rejected) {
            requireRaceRejection(rejected);
            raceEvent(
                    timeline,
                    name,
                    "REQUEST_REJECTED",
                    null,
                    Map.of("code", rejected.getCode(), "message", rejected.getMessage()));
            return new RaceOutcome<>(null, rejected.getMessage());
        }
    }

    private <T> RaceOutcome<T> raceWrite(
            String name,
            Supplier<T> action,
            CountDownLatch started,
            Map<String, Integer> pids,
            List<Map<String, Object>> timeline) {
        raceEvent(timeline, name, "WRITE_STARTED", null, Map.of());
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setTimeout(15);
        try {
            T result =
                    tx.execute(
                            state -> {
                                int pid =
                                        jdbc.queryForObject(
                                                "SELECT pg_backend_pid()", Integer.class);
                                pids.put(name, pid);
                                raceEvent(timeline, name, "TRANSACTION_STARTED", pid, Map.of());
                                started.countDown();
                                return action.get();
                            });
            raceEvent(timeline, name, "COMMITTED", pids.get(name), Map.of());
            return new RaceOutcome<>(result, null);
        } catch (ServiceException rejected) {
            requireRaceRejection(rejected);
            raceEvent(
                    timeline,
                    name,
                    "ROLLED_BACK_BUSINESS_REJECTION",
                    pids.get(name),
                    Map.of("code", rejected.getCode(), "message", rejected.getMessage()));
            return new RaceOutcome<>(null, rejected.getMessage());
        }
    }

    private void requireRaceRejection(ServiceException error) {
        assertThat(error.getCode())
                .isIn(NocodeErrorCodes.INVALID, NocodeErrorCodes.VERSION_CHANGED);
        assertThat(error.getMessage()).matches(".*(停用|版本|授权).*");
    }

    private void raceEvent(
            List<Map<String, Object>> timeline,
            String operation,
            String stage,
            Integer pid,
            Map<String, Object> details) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("at", Instant.now().toString());
        event.put("nanoTime", System.nanoTime());
        event.put("operation", operation);
        event.put("stage", stage);
        if (pid != null) event.put("pid", pid);
        event.putAll(details);
        timeline.add(event);
    }

    /** 仅拆除本例精确随机ID，先清应用引用，再清看板和数据集；物理对象由原fixture.clean处理。 */
    private void cleanCommittedRace(Seed seed, String application) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        tx -> {
                            if (application != null) {
                                long app = Long.parseLong(application);
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM"
                                                                + " public.nocode_application WHERE"
                                                                + " id=? AND app_code LIKE ?",
                                                        Integer.class,
                                                        app,
                                                        fixture.prefix + "%"))
                                        .isEqualTo(1);
                                jdbc.update(
                                        "DELETE FROM public.nocode_report_dependency WHERE"
                                                + " source_kind='APPLICATION' AND source_id=?",
                                        app);
                                jdbc.update(
                                        "DELETE FROM public.nocode_resource_dependency WHERE"
                                                + " source_kind='APP' AND source_key LIKE ?",
                                        "application:" + app + ":%");
                                jdbc.update(
                                        "DELETE FROM public.nocode_object_application_grant_log"
                                                + " WHERE application_id=?",
                                        app);
                                jdbc.update(
                                        "DELETE FROM public.nocode_object_application_grant WHERE"
                                                + " application_id=?",
                                        app);
                                jdbc.update(
                                        "DELETE FROM public.nocode_application_access WHERE"
                                                + " application_id=?",
                                        app);
                                jdbc.update(
                                        "DELETE FROM public.nocode_application_version WHERE"
                                                + " application_id=?",
                                        app);
                                assertThat(
                                                jdbc.update(
                                                        "DELETE FROM public.nocode_application"
                                                                + " WHERE id=? AND app_code LIKE ?",
                                                        app,
                                                        fixture.prefix + "%"))
                                        .isEqualTo(1);
                            }
                            long board = Long.parseLong(seed.board().id());
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM"
                                                        + " public.nocode_report_dashboard WHERE"
                                                        + " id=? AND owner_id=10001 AND name LIKE"
                                                        + " ?",
                                                    Integer.class,
                                                    board,
                                                    fixture.prefix + "%"))
                                    .isEqualTo(1);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM"
                                                        + " public.nocode_report_dependency WHERE"
                                                        + " target_kind='DASHBOARD' AND"
                                                        + " target_id=?",
                                                    Integer.class,
                                                    board))
                                    .isZero();
                            jdbc.update(
                                    "DELETE FROM public.nocode_report_dependency WHERE"
                                            + " source_kind='DASHBOARD' AND source_id=?",
                                    board);
                            jdbc.update(
                                    "DELETE FROM public.nocode_report_preference WHERE"
                                            + " dashboard_id=?",
                                    board);
                            jdbc.update(
                                    "DELETE FROM public.nocode_report_resource_acl WHERE"
                                            + " resource_kind='DASHBOARD' AND resource_id=?",
                                    board);
                            jdbc.update(
                                    "DELETE FROM public.nocode_report_operation_log WHERE"
                                            + " resource_kind='DASHBOARD' AND resource_id=?",
                                    board);
                            jdbc.update(
                                    "DELETE FROM public.nocode_report_dashboard_version WHERE"
                                            + " dashboard_id=?",
                                    board);
                            jdbc.update(
                                    "DELETE FROM public.nocode_report_dashboard WHERE id=? AND"
                                            + " owner_id=10001",
                                    board);
                            long dataset = Long.parseLong(seed.dataset().datasetId());
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM"
                                                        + " public.nocode_report_dataset WHERE id=?"
                                                        + " AND owner_id=10001 AND name LIKE ?",
                                                    Integer.class,
                                                    dataset,
                                                    fixture.prefix + "%"))
                                    .isEqualTo(1);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM"
                                                        + " public.nocode_report_dependency WHERE"
                                                        + " target_kind='DATASET' AND target_id=?",
                                                    Integer.class,
                                                    dataset))
                                    .isZero();
                            ReportIntegrationSupport.clearAuthorization(seed.dataset().datasetId());
                            jdbc.update(
                                    "DELETE FROM public.nocode_resource_dependency WHERE"
                                            + " source_kind='DATASET' AND source_key LIKE ?",
                                    dataset + ":%");
                            jdbc.update(
                                    "DELETE FROM public.nocode_report_operation_log WHERE"
                                            + " resource_kind='DATASET' AND resource_id=?",
                                    dataset);
                            jdbc.update(
                                    "DELETE FROM public.nocode_report_dataset_version WHERE"
                                            + " dataset_id=?",
                                    dataset);
                            jdbc.update(
                                    "DELETE FROM public.nocode_report_dataset WHERE id=? AND"
                                            + " owner_id=10001",
                                    dataset);
                        });
    }

    @Test
    void businessDetailsRejectUnboundTargetsForeignApplicationAndStaleModel() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ApplicationCenter.Detail app =
                            businessApplication(
                                    seed, "business_target", Map.of(), false, List.of());
                    ApplicationCenter.Detail other =
                            businessApplication(seed, "business_other", Map.of(), false, List.of());
                    ApplicationDashboards.Model model =
                            runtime.model(app.application().id(), "board", 10002);
                    ApplicationDashboards.Drill drill =
                            new ApplicationDashboards.Drill(
                                    query(model, "table", Map.of(), null),
                                    List.of("B"),
                                    List.of(),
                                    null);
                    RecordService records = servicesContext.getBean(RecordService.class);
                    assertThatThrownBy(
                                    () ->
                                            records.page(
                                                    businessQuery(
                                                            seed,
                                                            model,
                                                            "other_view",
                                                            drill,
                                                            1,
                                                            20,
                                                            Map.of()),
                                                    10002))
                            .isInstanceOf(ServiceException.class)
                            .hasMessageContaining("发布绑定");
                    ApplicationDashboards.Model otherModel =
                            runtime.model(other.application().id(), "board", 10002);
                    assertThatThrownBy(
                                    () ->
                                            records.page(
                                                    businessQuery(
                                                            seed,
                                                            otherModel,
                                                            "business_view",
                                                            drill,
                                                            1,
                                                            20,
                                                            Map.of()),
                                                    10002))
                            .isInstanceOf(ServiceException.class)
                            .hasMessageContaining("同一应用");
                    ApplicationDashboards.Drill unbound =
                            new ApplicationDashboards.Drill(
                                    query(model, "count", Map.of(), null),
                                    List.of(),
                                    List.of(),
                                    null);
                    assertThatThrownBy(
                                    () ->
                                            records.page(
                                                    businessQuery(
                                                            seed,
                                                            model,
                                                            "business_view",
                                                            unbound,
                                                            1,
                                                            20,
                                                            Map.of()),
                                                    10002))
                            .isInstanceOf(ServiceException.class)
                            .hasMessageContaining("未绑定");
                    ApplicationDashboards.Query stale = drill.query();
                    ApplicationDashboards.Drill wrongStamp =
                            new ApplicationDashboards.Drill(
                                    new ApplicationDashboards.Query(
                                            stale.applicationId(),
                                            stale.resourceId(),
                                            stale.chartId(),
                                            "0".repeat(64),
                                            stale.parameters(),
                                            null,
                                            List.of(),
                                            List.of(),
                                            List.of()),
                                    List.of(),
                                    List.of(),
                                    null);
                    assertVersionChanged(
                            () ->
                                    records.page(
                                            businessQuery(
                                                    seed,
                                                    model,
                                                    "business_view",
                                                    wrongStamp,
                                                    1,
                                                    20,
                                                    Map.of()),
                                            10002));
                });
    }

    @Test
    void businessDetailsShareMetricAndLinkedFilterConditionsWithReadonlyDetails() {
        rollback(
                () -> {
                    Seed seed = businessAnalysisSeed();
                    ApplicationCenter.Detail app =
                            businessApplication(
                                    seed, "business_metric", Map.of(), false, List.of());
                    ApplicationDashboards.Model model =
                            runtime.model(app.application().id(), "board", 10002);
                    ApplicationDashboards.Query selected =
                            new ApplicationDashboards.Query(
                                    model.applicationId(),
                                    model.resourceId(),
                                    "summary",
                                    model.stamp(),
                                    Map.of(),
                                    null,
                                    List.of(),
                                    List.of(new ReportDashboards.Selection("table", List.of("B"))),
                                    List.of());
                    ApplicationDashboards.Drill drill =
                            new ApplicationDashboards.Drill(
                                    selected, List.of(), List.of(), "eligible");
                    RecordService records = servicesContext.getBean(RecordService.class);
                    assertThat(
                                    runtime.details(
                                                    new ApplicationDashboards.Details(
                                                            selected,
                                                            List.of(),
                                                            List.of(),
                                                            "eligible",
                                                            1,
                                                            20),
                                                    10002)
                                            .list())
                            .extracting(ReportDashboards.DetailRow::id)
                            .containsExactly(seed.ownRecord());
                    assertThat(
                                    records.page(
                                                    businessQuery(
                                                            seed,
                                                            model,
                                                            "business_view",
                                                            drill,
                                                            1,
                                                            20,
                                                            Map.of()),
                                                    10002)
                                            .getList())
                            .extracting(ApplicationRecords.Row::id)
                            .containsExactly(seed.ownRecord());
                    ApplicationDashboards.Query conflicting =
                            withFilters(
                                    selected,
                                    List.of(
                                            new ReportDashboards.FilterValue(
                                                    "userNames", List.of("A"), null, null)));
                    assertThat(
                                    records.page(
                                                    businessQuery(
                                                            seed,
                                                            model,
                                                            "business_view",
                                                            new ApplicationDashboards.Drill(
                                                                    conflicting,
                                                                    List.of(),
                                                                    List.of(),
                                                                    "eligible"),
                                                            1,
                                                            20,
                                                            Map.of()),
                                                    10002)
                                            .getTotal())
                            .isZero();
                });
    }

    private ApplicationRecords.Query businessQuery(
            Seed seed,
            ApplicationDashboards.Model model,
            String view,
            ApplicationDashboards.Drill drill,
            int page,
            int size,
            Map<String, Object> equal) {
        return new ApplicationRecords.Query(
                model.applicationId(),
                seed.object().objectId(),
                page,
                size,
                null,
                equal,
                null,
                false,
                view,
                null,
                null,
                List.of(),
                null,
                drill);
    }

    /** B2 独立夹具，不改 B1 的页面/记录绑定入口。 */
    private ApplicationCenter.Detail businessApplication(
            Seed seed,
            String code,
            Map<String, Object> fixed,
            boolean composed,
            List<ApplicationDashboards.InputBinding> inputs) {
        return businessApplication(
                seed,
                code,
                fixed,
                composed ? new DataViews.Composition("ROOT", null, List.of(), List.of()) : null,
                inputs);
    }

    private ApplicationCenter.Detail businessApplication(
            Seed seed,
            String code,
            Map<String, Object> fixed,
            DataViews.Composition composition,
            List<ApplicationDashboards.InputBinding> inputs) {
        ApplicationDashboards.Config config =
                new ApplicationDashboards.Config(
                        new ApplicationDashboards.Reference(
                                seed.board().id(),
                                seed.board().versionNo(),
                                seed.board().checksum()),
                        null,
                        inputs,
                        List.of(
                                new ApplicationDashboards.DetailView("table", "business_view"),
                                new ApplicationDashboards.DetailView("summary", "business_view")));
        ApplicationUi.View view =
                new ApplicationUi.View(
                        seed.object().objectId(),
                        List.of(seed.name(), seed.amount()),
                        fixed,
                        null,
                        false,
                        20,
                        null,
                        Map.of(),
                        null,
                        null,
                        null,
                        null,
                        composition);
        List<ApplicationCenter.Resource> resources =
                List.of(
                        new ApplicationCenter.Resource(
                                "board",
                                "REPORT_DASHBOARD",
                                "board",
                                "经营看板",
                                mapper.convertValue(
                                        config, new TypeReference<Map<String, Object>>() {})),
                        new ApplicationCenter.Resource(
                                "business_view",
                                "VIEW",
                                "business_view",
                                "业务明细",
                                mapper.convertValue(
                                        view, new TypeReference<Map<String, Object>>() {})),
                        new ApplicationCenter.Resource(
                                "other_view",
                                "VIEW",
                                "other_view",
                                "其他视图",
                                mapper.convertValue(
                                        view, new TypeReference<Map<String, Object>>() {})),
                        new ApplicationCenter.Resource(
                                "menu",
                                "MENU",
                                "menu",
                                "业务入口",
                                Map.of("targetId", "business_view")));
        ApplicationCenter.Detail saved =
                save(
                        null,
                        code,
                        new ApplicationCenter.Definition(List.of(seed.object()), resources));
        ObjectGrant grant = all(seed);
        if (composition != null && composition.detailId() != null) {
            grant =
                    new ObjectGrant(
                            grant.objectId(),
                            grant.actions(),
                            grant.scope(),
                            grant.readFields(),
                            grant.writeFields(),
                            Set.of(composition.detailId()),
                            Set.of());
        }
        appPolicy(saved.application().id(), 0, List.of(member("USER", "10002", grant)));
        return applications.publish(
                new ApplicationCenter.Revision(
                        saved.application().id(), saved.application().revision(), "业务明细发布"),
                10001);
    }

    private Seed businessAnalysisSeed() {
        Seed seed = seed();
        ReportDatasets.Detail current = datasets.get(seed.dataset().datasetId(), 10001);
        ReportDatasets.Detail changed =
                datasets.save(
                        new ReportDatasets.Save(
                                current.id(),
                                current.revision(),
                                current.draft().name(),
                                current.draft().description(),
                                current.draft().source(),
                                new ReportDatasets.Analysis(
                                        1,
                                        List.of(
                                                new ReportDatasetQueries.Metric(
                                                        "count", "记录数", "COUNT", null),
                                                new ReportDatasetQueries.Metric(
                                                        "sum", "金额", "SUM", "amount"),
                                                new ReportDatasetQueries.Metric(
                                                        "eligible",
                                                        "有效金额",
                                                        "SUM",
                                                        "amount",
                                                        null,
                                                        new DataScope(
                                                                "AND",
                                                                List.of(
                                                                        new DataScope.Condition(
                                                                                "amount", "gt",
                                                                                10)),
                                                                List.of()),
                                                        null)),
                                        null,
                                        Map.of(),
                                        "Asia/Shanghai")),
                        10001);
        ReportDatasets.Release released =
                datasets.publish(
                        new ReportDatasets.Publish(
                                changed.id(), changed.revision(), "business_metric", "业务指标发布"),
                        10001);
        ReportDashboards.Dataset ref =
                new ReportDashboards.Dataset(
                        released.datasetId(), released.versionNo(), released.checksum());
        ReportDashboards.Detail board =
                boards.save(
                        new ReportDashboards.Save(
                                null,
                                0,
                                new ReportDashboards.Content(
                                        1,
                                        fixture.prefix + "业务指标",
                                        "",
                                        List.of(
                                                new ReportDashboards.Chart(
                                                        "table",
                                                        "名称",
                                                        "TABLE",
                                                        ref,
                                                        List.of(
                                                                new ReportDatasetQueries.Dimension(
                                                                        "name", "VALUE")),
                                                        List.of("count", "sum", "eligible"),
                                                        0,
                                                        0,
                                                        12,
                                                        4,
                                                        null,
                                                        null,
                                                        null,
                                                        List.of(
                                                                new ReportDashboards.Link(
                                                                        "summary", "name",
                                                                        "name"))),
                                                new ReportDashboards.Chart(
                                                        "summary",
                                                        "有效金额",
                                                        "METRIC",
                                                        ref,
                                                        List.of(),
                                                        List.of("eligible"),
                                                        0,
                                                        4,
                                                        6,
                                                        4),
                                                new ReportDashboards.Chart(
                                                        "count",
                                                        "记录数",
                                                        "METRIC",
                                                        ref,
                                                        List.of(),
                                                        List.of("count"),
                                                        6,
                                                        4,
                                                        6,
                                                        4)),
                                        List.of(filter("names"), filter("userNames")))),
                        10001);
        ReportDashboards.Release result =
                boards.publish(
                        new ReportDashboards.Publish(board.id(), board.revision(), "业务看板发布"),
                        10001);
        boardPolicy(result.id(), 0);
        return new Seed(
                seed.object(),
                seed.name(),
                seed.amount(),
                released,
                result,
                seed.ownRecord(),
                seed.otherRecord());
    }

    private Seed seed() {
        return seed(false);
    }

    private Seed seed(boolean withDetails) {
        String code = fixture.prefix + "fixed_runtime";
        SaveObjectDraft request =
                new SaveObjectDraft(
                        null,
                        null,
                        code,
                        fixture.prefix + "固定入口来源",
                        null,
                        "biz_" + code,
                        "name",
                        List.of(
                                new FieldDefinition(
                                        "name", null, "name", "名称", "TEXT", 100, null, null, false,
                                        false, 0),
                                new FieldDefinition(
                                        "amount", null, "amount", "金额", "DECIMAL", null, 20, 2,
                                        false, false, 1)),
                        List.of());
        ObjectDraft draft =
                withDetails
                        ? designs.save(
                                        new DataCenter.SaveDesign(
                                                request,
                                                DataCenter.Settings.defaults(),
                                                null,
                                                List.of(),
                                                List.of(),
                                                List.of(
                                                        new DataCenter.Detail(
                                                                null,
                                                                "items",
                                                                "明细",
                                                                "biz_" + code + "_items",
                                                                "ACTIVE",
                                                                List.of(
                                                                        new FieldDefinition(
                                                                                "label", null,
                                                                                "label", "名称",
                                                                                "TEXT", 100, null,
                                                                                null, false, false,
                                                                                0)),
                                                                Map.of(),
                                                                List.of()))),
                                        10001)
                                .draft()
                        : service.create(request, 10001, UUID.randomUUID());
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(draft.id(), draft.lockVersion(), null), 10001);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "固定入口来源"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi.PublishedObject object =
                servicesContext.getBean(DataObjectApi.class).getVersion(draft.id(), null);
        String name =
                object.definition().fields().stream()
                        .filter(field -> field.code().equals("name"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        String amount =
                object.definition().fields().stream()
                        .filter(field -> field.code().equals("amount"))
                        .findFirst()
                        .orElseThrow()
                        .id();
        ReportDatasets.Detail data =
                datasets.save(
                        new ReportDatasets.Save(
                                null,
                                0,
                                code,
                                "",
                                new ReportDatasets.Source(
                                        1,
                                        new ReportDatasets.ObjectReference(
                                                draft.id(), object.versionNo(), object.checksum()),
                                        List.of(),
                                        List.of(
                                                new ReportDatasets.Field(
                                                        "name", List.of(), name, "名称", "DIMENSION"),
                                                new ReportDatasets.Field(
                                                        "amount", List.of(), amount, "金额",
                                                        "MEASURE"))),
                                new ReportDatasets.Analysis(
                                        1,
                                        List.of(
                                                new ReportDatasetQueries.Metric(
                                                        "count", "记录数", "COUNT", null),
                                                new ReportDatasetQueries.Metric(
                                                        "sum", "金额", "SUM", "amount")),
                                        null,
                                        Map.of(),
                                        "Asia/Shanghai")),
                        10001);
        ObjectGrant all =
                new ObjectGrant(
                        draft.id(),
                        Set.of("READ", "EXPORT"),
                        "ALL",
                        Set.of(name, amount),
                        Set.of(),
                        Set.of(),
                        Set.of());
        dataAuthorization.saveCeiling(
                new ReportAuthorization.SaveCeiling(data.id(), draft.id(), 0, all, "固定入口数据上限"),
                10003);
        datasetPolicy(
                data.id(), 0, List.of(member("USER", "10001", all), member("USER", "10002", all)));
        ReportDatasets.Release dataset =
                datasets.publish(
                        new ReportDatasets.Publish(
                                data.id(), data.revision(), "fixed_runtime", "固定入口数据版本"),
                        10001);
        ReportDashboards.Dataset reference =
                new ReportDashboards.Dataset(
                        dataset.datasetId(), dataset.versionNo(), dataset.checksum());
        ReportDashboards.Detail board =
                boards.save(
                        new ReportDashboards.Save(
                                null,
                                0,
                                new ReportDashboards.Content(
                                        1,
                                        fixture.prefix + "应用固定看板",
                                        "",
                                        List.of(
                                                new ReportDashboards.Chart(
                                                        "table",
                                                        "名称与金额",
                                                        "TABLE",
                                                        reference,
                                                        List.of(
                                                                new ReportDatasetQueries.Dimension(
                                                                        "name", "VALUE")),
                                                        List.of("count", "sum"),
                                                        0,
                                                        0,
                                                        12,
                                                        4),
                                                new ReportDashboards.Chart(
                                                        "summary",
                                                        "金额统计",
                                                        "METRIC",
                                                        reference,
                                                        List.of(),
                                                        List.of("sum"),
                                                        0,
                                                        4,
                                                        6,
                                                        4),
                                                new ReportDashboards.Chart(
                                                        "count",
                                                        "记录计数",
                                                        "METRIC",
                                                        reference,
                                                        List.of(),
                                                        List.of("count"),
                                                        6,
                                                        4,
                                                        6,
                                                        4)),
                                        List.of(filter("names"), filter("userNames")))),
                        10001);
        ReportDashboards.Release released =
                boards.publish(
                        new ReportDashboards.Publish(board.id(), board.revision(), "固定入口看板"),
                        10001);
        boardPolicy(released.id(), 0);
        String table = object.definition().tableName();
        insert(table, "A", "10.25", "10001");
        String own = insert(table, "B", "20.75", "10002");
        String other = insert(table, "B", "5", "10001");
        return new Seed(
                new ApplicationCenter.ObjectReference(
                        draft.id(), object.versionNo(), object.checksum()),
                name,
                amount,
                dataset,
                released,
                own,
                other);
    }

    private ReportDashboards.Filter filter(String id) {
        return new ReportDashboards.Filter(
                id,
                "名称",
                "SELECT",
                List.of(
                        new ReportDashboards.Mapping("table", "name"),
                        new ReportDashboards.Mapping("summary", "name"),
                        new ReportDashboards.Mapping("count", "name")));
    }

    private String insert(String table, String name, String amount, String creator) {
        return jdbc.queryForObject(
                "INSERT INTO public.\""
                        + table
                        + "\"(name,amount,creator,deleted) VALUES (?,?,?,0) RETURNING id::text",
                String.class,
                name,
                new BigDecimal(amount),
                creator);
    }

    private ApplicationCenter.Detail application(
            Seed seed, String code, List<ApplicationDashboards.InputBinding> inputs) {
        ApplicationDashboards.Config config =
                new ApplicationDashboards.Config(
                        new ApplicationDashboards.Reference(
                                seed.board().id(),
                                seed.board().versionNo(),
                                seed.board().checksum()),
                        inputs.stream().anyMatch(input -> !input.source().equals("PARAMETER"))
                                ? seed.object().objectId()
                                : null,
                        inputs,
                        List.of());
        ApplicationCenter.Detail saved =
                save(
                        null,
                        code,
                        new ApplicationCenter.Definition(
                                List.of(seed.object()),
                                List.of(
                                        new ApplicationCenter.Resource(
                                                "board",
                                                "REPORT_DASHBOARD",
                                                "board",
                                                "经营看板",
                                                mapper.convertValue(
                                                        config,
                                                        new TypeReference<
                                                                Map<String, Object>>() {})),
                                        new ApplicationCenter.Resource(
                                                "dashboard_page",
                                                "PAGE",
                                                "dashboard_page",
                                                "看板页面",
                                                config.contextObjectId() == null
                                                        ? Map.of(
                                                                "nodes",
                                                                List.of(
                                                                        Map.of(
                                                                                "id",
                                                                                "dashboard_node",
                                                                                "type",
                                                                                "REPORT_DASHBOARD",
                                                                                "resourceId",
                                                                                "board")))
                                                        : Map.of(
                                                                "contextObjectId",
                                                                config.contextObjectId(),
                                                                "nodes",
                                                                List.of(
                                                                        Map.of(
                                                                                "id",
                                                                                "dashboard_node",
                                                                                "type",
                                                                                "REPORT_DASHBOARD",
                                                                                "resourceId",
                                                                                "board")))),
                                        new ApplicationCenter.Resource(
                                                "menu",
                                                "MENU",
                                                "menu",
                                                "看板入口",
                                                Map.of("targetId", "dashboard_page")))));
        String appId = saved.application().id();
        appPolicy(appId, 0, List.of(member("USER", "10002", all(seed))));
        return applications.publish(
                new ApplicationCenter.Revision(appId, saved.application().revision(), "固定看板发布"),
                10001);
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

    private ApplicationDashboards.Query query(
            ApplicationDashboards.Model model,
            String chart,
            Map<String, ApplicationDashboards.InputValue> parameters,
            String record) {
        return new ApplicationDashboards.Query(
                model.applicationId(),
                model.resourceId(),
                chart,
                model.stamp(),
                parameters,
                record,
                List.of(),
                List.of(),
                List.of());
    }

    private ApplicationDashboards.Query withFilters(
            ApplicationDashboards.Query query, List<ReportDashboards.FilterValue> filters) {
        return new ApplicationDashboards.Query(
                query.applicationId(),
                query.resourceId(),
                query.chartId(),
                query.stamp(),
                query.parameters(),
                query.recordId(),
                filters,
                query.selections(),
                query.drillPath());
    }

    private ObjectGrant all(Seed seed) {
        return new ObjectGrant(
                seed.object().objectId(),
                Set.of("READ", "EXPORT"),
                "ALL",
                Set.of(seed.name(), seed.amount()),
                Set.of(),
                Set.of(),
                Set.of());
    }

    private ObjectGrant scoped(Seed seed, String name, Set<String> fields) {
        return new ObjectGrant(
                seed.object().objectId(),
                Set.of("READ"),
                "ALL",
                fields,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Map.of(
                        "READ",
                        new DataScope(
                                "AND",
                                List.of(new DataScope.Condition(seed.name(), "eq", name)),
                                List.of())),
                Set.of());
    }

    private Member member(String kind, String id, ObjectGrant grant) {
        return new Member(kind, id, List.of(grant));
    }

    private void appPolicy(String app, int revision, List<Member> members) {
        appAuthorization.save(new ApplicationAuthorization.Save(app, revision, members), 10001);
    }

    private void datasetPolicy(String dataset, int revision, List<Member> members) {
        dataAuthorization.saveDataPolicy(
                new ReportAuthorization.SaveDataPolicy(dataset, revision, members, "固定入口数据成员"),
                10001);
    }

    private void boardPolicy(String board, int revision) {
        boards.saveResource(
                new ReportAuthorization.SaveDashboardResource(
                        board,
                        revision,
                        List.of(
                                new ReportAuthorization.ResourceMember(
                                        "USER", "10002", Set.of("VIEW", "EXPORT"))),
                        "固定入口看板成员"),
                10001);
    }

    private void role() {
        RoleRespDTO role = new RoleRespDTO();
        role.setId(200L);
        role.setCode("APPLICATION_DASHBOARD_TEST");
        role.setStatus(0);
        RoleApi roles = servicesContext.getBean(RoleApi.class);
        when(permissions.getUserRoleIds(10002L)).thenReturn(Set.of(200L));
        when(permissions.hasAnyRoles(10002L, role.getCode())).thenReturn(true);
        when(roles.getRole(200L)).thenReturn(role);
        when(roles.getRoleList(Set.of(200L))).thenReturn(List.of(role));
    }

    private void assertVersionChanged(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        ServiceException.class,
                        error ->
                                assertThat(error.getCode())
                                        .isEqualTo(NocodeErrorCodes.VERSION_CHANGED));
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
