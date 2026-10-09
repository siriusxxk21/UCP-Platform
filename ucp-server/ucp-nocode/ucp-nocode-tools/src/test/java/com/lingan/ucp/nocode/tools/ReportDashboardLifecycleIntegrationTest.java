package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.module.system.api.permission.MenuApi;
import com.lingan.ucp.module.system.api.permission.PermissionApi;
import com.lingan.ucp.module.system.api.permission.RoleApi;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.*;
import com.lingan.ucp.nocode.report.service.authorization.ReportDatasetAuthorizationService;
import com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardService;
import com.lingan.ucp.nocode.report.service.dataset.ReportDatasetService;
import com.lingan.ucp.nocode.runtime.service.report.ReportDashboardQueryService;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 当前开发库验证资源头生命周期与真实取数；每项事务回滚，只清理本测试对象夹具。 */
class ReportDashboardLifecycleIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private AnnotationConfigApplicationContext context;
    private ReportDashboardService boards;
    private ReportDashboardQueryService query;
    private ReportDatasetService datasets;
    private ReportDatasetAuthorizationService auth;
    private PermissionApi permissions;

    private record Seed(ReportDatasets.Release data, String object, Set<String> fields) {}

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
        context = ReportIntegrationSupport.context();
        boards = context.getBean(ReportDashboardService.class);
        query = context.getBean(ReportDashboardQueryService.class);
        datasets = context.getBean(ReportDatasetService.class);
        auth = context.getBean(ReportDatasetAuthorizationService.class);
        permissions = servicesContext.getBean(PermissionApi.class);
        reset(
                permissions,
                servicesContext.getBean(RoleApi.class),
                servicesContext.getBean(AdminUserApi.class));
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
        writeFailure.clear();
        context.close();
        fixture.clean();
    }

    @Test
    void previewsUnsavedChartsWithoutMutatingDashboardOrDataset() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Detail board = create(seed);
                    ReportDatasets.Detail before = datasets.get(seed.data().datasetId(), 10001);
                    ReportDashboards.Chart source = board.draft().charts().getFirst();
                    ApplicationReports.Result result = query.previewChart(source, 10001);
                    assertThat(result.recordCount()).isEqualTo(2);
                    assertThat(new java.math.BigDecimal(result.totals().get("sum")))
                            .isEqualByComparingTo("31.00");
                    ReportDashboards.Chart pivot =
                            new ReportDashboards.Chart(
                                    source.id(),
                                    "临时透视",
                                    "PIVOT",
                                    source.dataset(),
                                    source.dimensions(),
                                    source.metricIds(),
                                    0,
                                    0,
                                    12,
                                    6,
                                    List.of(),
                                    ApplicationReports.Pivot.defaults());
                    assertThat(query.previewChart(pivot, 10001).pivot()).isNotNull();
                    assertThat(boards.get(board.id(), 10001)).isEqualTo(board);
                    assertThat(datasets.get(seed.data().datasetId(), 10001)).isEqualTo(before);
                });
    }

    @Test
    void unsavedPreviewRejectsMissingUseAndForgedFixedVersion() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Chart source = content(seed, "预览").charts().getFirst();
                    assertThatThrownBy(() -> query.previewChart(source, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    ReportDashboards.Chart forged =
                            new ReportDashboards.Chart(
                                    source.id(),
                                    source.title(),
                                    source.display(),
                                    new ReportDashboards.Dataset(
                                            source.dataset().id(),
                                            source.dataset().versionNo(),
                                            "forged"),
                                    source.dimensions(),
                                    source.metricIds(),
                                    0,
                                    0,
                                    12,
                                    6);
                    assertThatThrownBy(() -> query.previewChart(forged, 10001))
                            .hasMessageContaining("固定版本失效");
                });
    }

    @Test
    void defaultsActiveButRequiresPublishingAndRespectsExplicitInactiveOnFirstPublish() {
        rollback(
                () -> {
                    ReportDashboards.Detail blank =
                            boards.save(
                                    new ReportDashboards.Save(
                                            null,
                                            0,
                                            new ReportDashboards.Content(
                                                    1, fixture.prefix + "空看板", "", List.of())),
                                    10001);
                    assertThat(blank.status()).isEqualTo("ACTIVE");
                    assertThat(blank.folderId()).isNull();
                    assertThatThrownBy(() -> boards.published(blank.id(), null, null, 10001))
                            .hasMessageContaining("发布版本");
                    assertThatThrownBy(
                                    () ->
                                            boards.status(
                                                    new ReportDashboards.ChangeStatus(
                                                            blank.id(),
                                                            blank.revision(),
                                                            "ACTIVE",
                                                            "未发布启用"),
                                                    10001))
                            .hasMessageContaining("尚未发布");
                    Seed seed = seed();
                    ReportDashboards.Detail inactive =
                            boards.status(
                                    new ReportDashboards.ChangeStatus(
                                            blank.id(), blank.revision(), "INACTIVE", "提前停用"),
                                    10001);
                    ReportDashboards.Detail configured =
                            boards.save(
                                    new ReportDashboards.Save(
                                            blank.id(), inactive.revision(), content(seed, "待发布")),
                                    10001);
                    ReportDashboards.Release release = publish(configured, "inactive_first");
                    assertThat(boards.get(blank.id(), 10001).status()).isEqualTo("INACTIVE");
                    assertThatThrownBy(() -> query.query(request(release), 10001))
                            .hasMessageContaining("已停用");
                });
    }

    @Test
    void restoresIntoNewDraftWithoutRewindingPublishedVersionOrChangingHistoricalBytes() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Detail first = create(seed);
                    ReportDashboards.Release v1 = publish(first, "v1");
                    ReportDashboards.Detail current = boards.get(first.id(), 10001);
                    ReportDashboards.Detail second =
                            boards.save(
                                    new ReportDashboards.Save(
                                            first.id(),
                                            current.revision(),
                                            renamed(current.draft(), "第二版")),
                                    10001);
                    ReportDashboards.Release v2 = publish(second, "v2");
                    current = boards.get(first.id(), 10001);
                    int revision = current.revision();
                    ReportDashboards.Detail restored =
                            boards.restore(
                                    new ReportDashboards.Restore(first.id(), revision, 1, "恢复第一版"),
                                    10001);
                    assertThat(restored.revision()).isEqualTo(revision + 1);
                    assertThat(restored.publishedVersion()).isEqualTo(2);
                    assertThat(restored.checksum()).isEqualTo(v1.checksum());
                    assertThat(restored.modified()).isTrue();
                    assertThat(boards.published(first.id(), 2, v2.checksum(), 10001)).isEqualTo(v2);
                    assertThat(boards.releases(first.id(), 1, 1, 10001).getList())
                            .containsExactly(v2);
                    assertThat(boards.releases(first.id(), 2, 1, 10001).getList())
                            .containsExactly(v1);
                    assertThatThrownBy(
                                    () ->
                                            boards.restore(
                                                    new ReportDashboards.Restore(
                                                            first.id(), revision, 1, "旧修订"),
                                                    10001))
                            .hasMessageContaining("已被修改");
                    assertThatThrownBy(
                                    () ->
                                            boards.restore(
                                                    new ReportDashboards.Restore(
                                                            first.id(),
                                                            restored.revision(),
                                                            999,
                                                            "不存在历史"),
                                                    10001))
                            .hasMessageContaining("历史版本");
                    ReportDashboards.Release v3 = publish(restored, "v3");
                    assertThat(v3.versionNo()).isEqualTo(3);
                    assertThat(boards.releases(first.id(), 1, 10, 10001).getTotal()).isEqualTo(3);
                });
    }

    @Test
    void
            copyingRequiresDatasetUseAndRemapsAllChartFilterAndLinkIdentitiesWithoutAclOrPreferences() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Detail source = create(seed);
                    publish(source, "copy_source");
                    ReportDashboards.Detail current = boards.get(source.id(), 10001);
                    boards.saveResource(
                            new ReportAuthorization.SaveDashboardResource(
                                    source.id(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER",
                                                    "10002",
                                                    Set.of("VIEW", "EDIT", "EXPORT"))),
                                    "复制协作"),
                            10001);
                    jdbc.update(
                            "INSERT INTO"
                                + " public.nocode_report_preference(user_id,dashboard_id,favorite,last_visited_at)"
                                + " VALUES(10001,?,true,now())",
                            Long.parseLong(source.id()));
                    ReportDashboards.Copy command =
                            new ReportDashboards.Copy(
                                    source.id(), current.revision(), fixture.prefix + "副本", "复制测试");
                    assertThatThrownBy(() -> boards.copy(command, 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    auth.saveResource(
                            new ReportAuthorization.SaveResource(
                                    seed.data().datasetId(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("USE"))),
                                    "允许使用固定数据集"),
                            10001);
                    ReportDashboards.Detail copied = boards.copy(command, 10002);
                    assertThat(copied.id()).isNotEqualTo(source.id());
                    assertThat(copied.ownerId()).isEqualTo("10002");
                    assertThat(copied.revision()).isEqualTo(1);
                    assertThat(copied.publishedVersion()).isNull();
                    assertThat(copied.status()).isEqualTo("ACTIVE");
                    assertThat(copied.draft().charts())
                            .extracting(ReportDashboards.Chart::id)
                            .doesNotContain("source", "target");
                    ReportDashboards.Chart from = copied.draft().charts().get(0);
                    ReportDashboards.Chart to = copied.draft().charts().get(1);
                    assertThat(from.links())
                            .containsExactly(new ReportDashboards.Link(to.id(), "name", "name"));
                    assertThat(from.dataset()).isEqualTo(source.draft().charts().get(0).dataset());
                    ReportDashboards.Filter filter = copied.draft().filters().getFirst();
                    assertThat(filter.id()).isNotEqualTo("names");
                    assertThat(filter.mappings())
                            .containsExactly(new ReportDashboards.Mapping(to.id(), "name"));
                    assertThat(boards.resourcePolicy(copied.id(), 10002).members()).isEmpty();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.nocode_report_preference"
                                                    + " WHERE dashboard_id=?",
                                            Long.class,
                                            Long.parseLong(copied.id())))
                            .isZero();
                    assertThatThrownBy(() -> boards.get(copied.id(), 10001))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            boards.copy(
                                                    new ReportDashboards.Copy(
                                                            source.id(),
                                                            current.revision() - 1,
                                                            "旧修订副本",
                                                            "旧修订"),
                                                    10002))
                            .hasMessageContaining("已被修改");
                });
    }

    @Test
    void movesOnlyToSameKindFoldersAndKeepsPublishedContentAndChecksumsUnchanged() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Detail board = create(seed);
                    ReportDashboards.Release released = publish(board, "move_source");
                    ReportDashboards.Detail current = boards.get(board.id(), 10001);
                    Long destination = folder("DASHBOARD", "仪表板分类");
                    Long wrong = folder("DATASET", "数据集分类");
                    ReportDashboards.Detail moved =
                            boards.move(
                                    new ReportDashboards.Move(
                                            board.id(),
                                            current.revision(),
                                            destination.toString(),
                                            "分类测试"),
                                    10001);
                    assertThat(moved.folderId()).isEqualTo(destination.toString());
                    assertThat(moved.revision()).isEqualTo(current.revision() + 1);
                    assertThat(moved.checksum()).isEqualTo(current.checksum());
                    assertThat(moved.modified()).isFalse();
                    assertThat(boards.published(board.id(), 1, released.checksum(), 10001))
                            .isEqualTo(released);
                    assertThatThrownBy(
                                    () ->
                                            boards.move(
                                                    new ReportDashboards.Move(
                                                            board.id(),
                                                            current.revision(),
                                                            null,
                                                            "旧修订"),
                                                    10001))
                            .hasMessageContaining("已被修改");
                    assertThatThrownBy(
                                    () ->
                                            boards.move(
                                                    new ReportDashboards.Move(
                                                            board.id(),
                                                            moved.revision(),
                                                            wrong.toString(),
                                                            "跨类型"),
                                                    10001))
                            .isInstanceOfAny(
                                    AccessDeniedException.class,
                                    com.lingan.ucp.framework.common.exception.ServiceException
                                            .class);
                    assertThat(
                                    boards.move(
                                                    new ReportDashboards.Move(
                                                            board.id(),
                                                            moved.revision(),
                                                            null,
                                                            "取消分类"),
                                                    10001)
                                            .folderId())
                            .isNull();
                    assertThat(boards.availablePage(1, 100, fixture.prefix, 10001).getList())
                            .anySatisfy(
                                    item -> {
                                        assertThat(item.id()).isEqualTo(board.id());
                                        assertThat(item.status()).isEqualTo("ACTIVE");
                                    });
                });
    }

    @Test
    void
            creationClassifiesAtomicallyAndListFiltersIncludeDescendantsWithoutChangingDraftChecksum() {
        rollback(
                () -> {
                    Seed seed = seed();
                    Long parent = folder("DASHBOARD", "父分类");
                    Long child =
                            jdbc.queryForObject(
                                    "INSERT INTO"
                                        + " public.nocode_report_folder(resource_kind,parent_id,name,creator,updater)"
                                        + " VALUES('DASHBOARD',?,?,'10001','10001') RETURNING id",
                                    Long.class,
                                    parent,
                                    fixture.prefix + "子分类");
                    ReportDashboards.Detail created =
                            boards.save(
                                    new ReportDashboards.Save(
                                            null, 0, content(seed, "子分类看板"), child.toString()),
                                    10001);
                    assertThat(created.folderId()).isEqualTo(child.toString());
                    assertThat(created.revision()).isEqualTo(1);
                    assertThat(
                                    boards.availablePage(
                                                    1,
                                                    10,
                                                    fixture.prefix,
                                                    parent.toString(),
                                                    "ACTIVE",
                                                    10001)
                                            .getList())
                            .extracting(ReportDashboards.AvailableItem::id)
                            .containsExactly(created.id());
                    assertThat(
                                    boards.availablePage(1, 10, fixture.prefix, "0", null, 10001)
                                            .getTotal())
                            .isZero();
                    ReportDashboards.Detail saved =
                            boards.save(
                                    new ReportDashboards.Save(
                                            created.id(), created.revision(), created.draft()),
                                    10001);
                    assertThat(saved.folderId()).isEqualTo(child.toString());
                    assertThat(saved.checksum()).isEqualTo(created.checksum());
                    assertThatThrownBy(
                                    () ->
                                            boards.save(
                                                    new ReportDashboards.Save(
                                                            created.id(),
                                                            saved.revision(),
                                                            created.draft(),
                                                            parent.toString()),
                                                    10001))
                            .hasMessageContaining("移动操作");
                    boards.status(
                            new ReportDashboards.ChangeStatus(
                                    created.id(), saved.revision(), "INACTIVE", "分类筛选状态"),
                            10001);
                    assertThat(
                                    boards.availablePage(
                                                    1,
                                                    10,
                                                    fixture.prefix,
                                                    parent.toString(),
                                                    "ACTIVE",
                                                    10001)
                                            .getTotal())
                            .isZero();
                    assertThat(
                                    boards.availablePage(
                                                    1,
                                                    10,
                                                    fixture.prefix,
                                                    parent.toString(),
                                                    "INACTIVE",
                                                    10001)
                                            .getList())
                            .extracting(ReportDashboards.AvailableItem::id)
                            .containsExactly(created.id());
                });
    }

    @Test
    void inactiveStateIsDisclosedOnlyAfterTheRequiredResourceAclCheck() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Detail board = create(seed);
                    ReportDashboards.Release released = publish(board, "inactive_acl");
                    ReportDashboards.Detail current = boards.get(board.id(), 10001);
                    boards.status(
                            new ReportDashboards.ChangeStatus(
                                    board.id(), current.revision(), "INACTIVE", "停用状态可见性"),
                            10001);
                    ReportDashboards.Query request = request(released);
                    ReportDashboards.Query preview =
                            new ReportDashboards.Query(board.id(), "target", true, null, null);
                    assertThatThrownBy(() -> query.query(request, 10002))
                            .isInstanceOf(AccessDeniedException.class)
                            .hasMessageNotContaining("停用");
                    assertThatThrownBy(() -> query.query(preview, 10002))
                            .isInstanceOf(AccessDeniedException.class)
                            .hasMessageNotContaining("停用");
                    assertThatThrownBy(() -> query.export(request, 10002))
                            .isInstanceOf(AccessDeniedException.class)
                            .hasMessageNotContaining("停用");
                    boards.saveResource(
                            new ReportAuthorization.SaveDashboardResource(
                                    board.id(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("VIEW"))),
                                    "只读资源受众"),
                            10001);
                    assertThatThrownBy(() -> query.query(request, 10002))
                            .hasMessageContaining("已停用");
                    assertThatThrownBy(() -> query.query(preview, 10002))
                            .isInstanceOf(AccessDeniedException.class)
                            .hasMessageNotContaining("停用");
                    assertThatThrownBy(() -> query.export(request, 10002))
                            .isInstanceOf(AccessDeniedException.class)
                            .hasMessageNotContaining("停用");
                });
    }

    @Test
    void inactiveBlocksHistoricalRuntimeOptionsDetailsExportAndRecheckWhileAllowingDraftEditing() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Detail board = create(seed);
                    ReportDashboards.Release v1 = publish(board, "status_v1");
                    ReportDashboards.Query request = request(v1);
                    assertThat(query.query(request, 10001).recordCount()).isEqualTo(2);
                    ReportDashboards.Resolved resolved = boards.resolve(request, 10001);
                    ReportDashboards.Detail current = boards.get(board.id(), 10001);
                    ReportDashboards.Detail inactive =
                            boards.status(
                                    new ReportDashboards.ChangeStatus(
                                            board.id(), current.revision(), "INACTIVE", "停用测试"),
                                    10001);
                    assertThat(inactive.checksum()).isEqualTo(current.checksum());
                    assertThatThrownBy(() -> boards.published(board.id(), 1, v1.checksum(), 10001))
                            .hasMessageContaining("已停用");
                    assertThatThrownBy(() -> query.query(request, 10001))
                            .hasMessageContaining("已停用");
                    assertThatThrownBy(
                                    () ->
                                            query.options(
                                                    new ReportDashboards.Options(
                                                            request, "names", 1, 20, null),
                                                    10001))
                            .hasMessageContaining("已停用");
                    assertThatThrownBy(
                                    () ->
                                            query.details(
                                                    new ReportDashboards.Details(
                                                            request,
                                                            List.of("A"),
                                                            List.of(),
                                                            "sum",
                                                            1,
                                                            20),
                                                    10001))
                            .hasMessageContaining("已停用");
                    assertThatThrownBy(() -> query.export(request, 10001))
                            .hasMessageContaining("已停用");
                    assertThatThrownBy(() -> boards.recheck(resolved, 10001))
                            .hasMessageContaining("已停用");
                    assertThatThrownBy(
                                    () ->
                                            query.query(
                                                    new ReportDashboards.Query(
                                                            board.id(), "source", true, null, null),
                                                    10001))
                            .hasMessageContaining("已停用");
                    ReportDashboards.Detail edited =
                            boards.save(
                                    new ReportDashboards.Save(
                                            board.id(),
                                            inactive.revision(),
                                            renamed(inactive.draft(), "停用中编辑")),
                                    10001);
                    ReportDashboards.Release v2 = publish(edited, "status_v2");
                    current = boards.get(board.id(), 10001);
                    assertThat(current.status()).isEqualTo("INACTIVE");
                    ReportDashboards.Detail active =
                            boards.status(
                                    new ReportDashboards.ChangeStatus(
                                            board.id(), current.revision(), "ACTIVE", "恢复运行"),
                                    10001);
                    assertThat(active.status()).isEqualTo("ACTIVE");
                    assertThatThrownBy(() -> query.query(request, 10001))
                            .isInstanceOf(
                                    com.lingan.ucp.framework.common.exception.ServiceException
                                            .class)
                            .extracting(
                                    error ->
                                            ((com.lingan.ucp.framework.common.exception
                                                                    .ServiceException)
                                                            error)
                                                    .getCode())
                            .isEqualTo(com.lingan.ucp.nocode.api.NocodeErrorCodes.VERSION_CHANGED);
                    assertThat(query.query(request(v2), 10001).recordCount()).isEqualTo(2);
                });
    }

    @Test
    void newPublicationInvalidatesAllIndependentRequestsEvenWhenTheChecksumIsUnchanged() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Detail board = create(seed);
                    ReportDashboards.Release v1 = publish(board, "guard_v1");
                    ReportDashboards.Query old = request(v1);
                    ReportDashboards.Resolved resolved = boards.resolve(old, 10001);
                    ReportDashboards.Detail current = boards.get(board.id(), 10001);
                    ReportDashboards.Release v2 = publish(current, "guard_v2_same_content");
                    assertThat(v2.checksum()).isEqualTo(v1.checksum());
                    assertThat(v2.versionNo()).isEqualTo(2);
                    assertThat(boards.published(board.id(), null, null, 10001)).isEqualTo(v2);
                    changed(() -> boards.published(board.id(), 1, v1.checksum(), 10001));
                    changed(() -> query.query(old, 10001));
                    changed(
                            () ->
                                    query.options(
                                            new ReportDashboards.Options(old, "names", 1, 20, null),
                                            10001));
                    changed(
                            () ->
                                    query.details(
                                            new ReportDashboards.Details(
                                                    old, List.of("A"), List.of(), "sum", 1, 20),
                                            10001));
                    changed(() -> query.export(old, 10001));
                    changed(() -> boards.recheck(resolved, 10001));
                    changed(
                            () ->
                                    auth.withDashboardDataAccess(
                                            resolved, 10001, access -> "must_not_deliver"));
                    changed(
                            () ->
                                    auth.withDashboardExportAccess(
                                            resolved, 10001, access -> "must_not_export"));
                    assertThat(query.query(request(v2), 10001).recordCount()).isEqualTo(2);
                    assertThat(query.export(request(v2), 10001).recordCount()).isEqualTo(2);
                });
    }

    @Test
    void internalFixedHistoricalEntryKeepsItsSemanticsDuringDatasetAuthorizationAndRecheck() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Detail board = create(seed);
                    ReportDashboards.Release v1 = publish(board, "fixed_v1");
                    ReportDashboards.Resolved fixed =
                            boards.resolveFixed(request(v1), 10001, false);
                    ReportDashboards.Resolved direct = boards.resolve(request(v1), 10001);
                    assertThat(fixed.entry())
                            .isEqualTo(
                                    com.lingan.ucp.nocode.enums.ReportDashboardEntryEnum
                                            .APPLICATION_FIXED);
                    ReportDashboards.Detail current = boards.get(board.id(), 10001);
                    ReportDashboards.Detail edited =
                            boards.save(
                                    new ReportDashboards.Save(
                                            board.id(),
                                            current.revision(),
                                            renamed(current.draft(), "固定引用后的新版")),
                                    10001);
                    publish(edited, "fixed_v2");
                    assertThat(boards.publishedFixed(board.id(), 1, v1.checksum(), 10001))
                            .isEqualTo(v1);
                    assertThat(boards.refresh(fixed, 10001, false).entry())
                            .isEqualTo(fixed.entry());
                    boards.recheck(fixed, 10001);
                    // 固定引用本身不构成应用授权；只有应用运行时提供可信门卫后才能取数。
                    assertThatThrownBy(
                                    () ->
                                            auth.withDashboardDataAccess(
                                                    fixed, 10001, access -> "unguarded"))
                            .hasMessageContaining("可信应用授权门卫");
                    com.lingan.ucp.nocode.runtime.service.report.ReportDatasetQueryService runner =
                            context.getBean(
                                    com.lingan.ucp.nocode.runtime.service.report
                                            .ReportDatasetQueryService.class);
                    ReportDashboards.Execution execution =
                            new ReportDashboards.Execution(
                                    fixed.chart(), null, List.of(), List.of(), List.of(), fixed);
                    assertThatThrownBy(() -> runner.chart(execution, false, 10001))
                            .hasMessageContaining("可信应用授权门卫");
                    assertThatThrownBy(() -> runner.chart(execution, true, 10001))
                            .hasMessageContaining("可信应用授权门卫");
                    changed(
                            () ->
                                    auth.withDashboardDataAccess(
                                            direct, 10001, access -> "old_independent"));
                    assertThatThrownBy(
                                    () ->
                                            boards.resolveFixed(
                                                    new ReportDashboards.Query(
                                                            board.id(), "target", true, null, null),
                                                    10001,
                                                    false))
                            .hasMessageContaining("不接受草稿预览");
                    assertThatThrownBy(() -> boards.publishedFixed(board.id(), 1, "forged", 10001))
                            .hasMessageContaining("校验和");
                    assertThatThrownBy(() -> boards.resolveFixed(request(v1), 10002, false))
                            .isInstanceOf(AccessDeniedException.class);
                    current = boards.get(board.id(), 10001);
                    boards.status(
                            new ReportDashboards.ChangeStatus(
                                    board.id(), current.revision(), "INACTIVE", "固定引用仍尊重停用"),
                            10001);
                    assertThatThrownBy(
                                    () ->
                                            boards.publishedFixed(
                                                    board.id(), 1, v1.checksum(), 10001))
                            .hasMessageContaining("已停用");
                    assertThatThrownBy(
                                    () ->
                                            boards.publishedFixed(
                                                    board.id(), 1, v1.checksum(), 10001))
                            .hasMessageContaining("已停用");
                });
    }

    @Test
    void workbenchSummaryAlwaysUsesCurrentPublishedContentAndRechecksViewAccess() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Detail board = create(seed);
                    ReportDashboards.Release v1 = publish(board, "summary");
                    ReportDashboards.Detail current = boards.get(board.id(), 10001);
                    boards.save(
                            new ReportDashboards.Save(
                                    board.id(),
                                    current.revision(),
                                    new ReportDashboards.Content(1, "未发布私密名称", "", List.of())),
                            10001);
                    ReportDashboards.AvailableItem summary =
                            boards.publishedSummary(board.id(), 10001);
                    assertThat(summary.name()).isEqualTo(v1.content().name());
                    assertThat(summary.chartCount()).isEqualTo(2);
                    assertThat(summary.modified()).isFalse();
                    assertThat(summary.revision()).isNull();
                    assertThat(summary.capabilities().canEdit()).isTrue();
                    assertThatThrownBy(() -> boards.publishedSummary(board.id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    current = boards.get(board.id(), 10001);
                    boards.status(
                            new ReportDashboards.ChangeStatus(
                                    board.id(), current.revision(), "INACTIVE", "工作台过滤停用"),
                            10001);
                    assertThatThrownBy(() -> boards.publishedSummary(board.id(), 10001))
                            .hasMessageContaining("已停用");
                });
    }

    @Test
    void activatingChecksPublishedDependenciesAndCurrentObjectCeilingsInsteadOfDraft() {
        // 此历史场景专门验证对象上限，显式打开兼容模式，不依赖现行简化默认值。
        org.springframework.test.util.ReflectionTestUtils.setField(
                context.getBean(
                        com.lingan.ucp.nocode.report.service.authorization.ReportSourcePermissions
                                .class),
                "enabled",
                true);
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Detail board = create(seed);
                    publish(board, "activation");
                    ReportDashboards.Detail current = boards.get(board.id(), 10001);
                    ReportDashboards.Detail inactive =
                            boards.status(
                                    new ReportDashboards.ChangeStatus(
                                            board.id(), current.revision(), "INACTIVE", "依赖检查"),
                                    10001);
                    ReportDashboards.Detail emptyDraft =
                            boards.save(
                                    new ReportDashboards.Save(
                                            board.id(),
                                            inactive.revision(),
                                            new ReportDashboards.Content(
                                                    1, "无依赖草稿", "", List.of())),
                                    10001);
                    ReportDatasets.Detail dataset = datasets.get(seed.data().datasetId(), 10001);
                    ReportDatasets.Detail dataInactive =
                            datasets.status(
                                    new ReportDatasets.ChangeStatus(
                                            dataset.id(), dataset.revision(), "INACTIVE", "数据集停用"),
                                    10001);
                    assertThatThrownBy(
                                    () ->
                                            boards.status(
                                                    new ReportDashboards.ChangeStatus(
                                                            board.id(),
                                                            emptyDraft.revision(),
                                                            "ACTIVE",
                                                            "依赖停用"),
                                                    10001))
                            .hasMessageContaining("启用");
                    datasets.status(
                            new ReportDatasets.ChangeStatus(
                                    dataset.id(), dataInactive.revision(), "ACTIVE", "数据集启用"),
                            10001);
                    auth.saveCeiling(
                            new ReportAuthorization.SaveCeiling(
                                    dataset.id(), seed.object(), 1, null, "撤销上限"),
                            10003);
                    assertThatThrownBy(
                                    () ->
                                            boards.status(
                                                    new ReportDashboards.ChangeStatus(
                                                            board.id(),
                                                            emptyDraft.revision(),
                                                            "ACTIVE",
                                                            "上限撤销"),
                                                    10001))
                            .hasMessageContaining("尚未授权");
                    assertThat(boards.get(board.id(), 10001).revision())
                            .isEqualTo(emptyDraft.revision());
                    auth.saveCeiling(
                            new ReportAuthorization.SaveCeiling(
                                    dataset.id(), seed.object(), 2, grant(seed), "恢复上限"),
                            10003);
                    assertThat(
                                    boards.status(
                                                    new ReportDashboards.ChangeStatus(
                                                            board.id(),
                                                            emptyDraft.revision(),
                                                            "ACTIVE",
                                                            "固定发布依赖有效"),
                                                    10001)
                                            .status())
                            .isEqualTo("ACTIVE");
                });
    }

    @Test
    void
            deletionChecksEveryApplicationDraftAndHistoricalVersionAndReleasesOnlyOwnOutgoingDependencies() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Detail board = create(seed);
                    publish(board, "delete_source");
                    ReportDashboards.Detail current = boards.get(board.id(), 10001);
                    incoming(board.id(), "DRAFT", 0);
                    incoming(board.id(), "VERSION", 7);
                    ReportDashboards.DeletePreview blocked =
                            boards.deletePreview(board.id(), 10001);
                    assertThat(blocked.referenceCount()).isEqualTo(2);
                    assertThat(blocked.canDelete()).isFalse();
                    assertThatThrownBy(
                                    () ->
                                            boards.delete(
                                                    new ReportDashboards.Delete(
                                                            board.id(), current.revision(), "引用阻止"),
                                                    10001))
                            .hasMessageContaining("仍被其他资源引用");
                    jdbc.update(
                            "UPDATE public.nocode_report_dependency SET deleted=1 WHERE"
                                    + " source_kind='APPLICATION' AND source_id=? AND"
                                    + " source_stage='DRAFT'",
                            Long.parseLong(board.id()));
                    assertThat(boards.deletePreview(board.id(), 10001).referenceCount())
                            .isEqualTo(1);
                    assertThatThrownBy(
                                    () ->
                                            boards.delete(
                                                    new ReportDashboards.Delete(
                                                            board.id(),
                                                            current.revision(),
                                                            "历史仍引用"),
                                                    10001))
                            .hasMessageContaining("历史版本");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT deleted FROM public.nocode_report_dependency"
                                                + " WHERE source_kind='APPLICATION' AND source_id=?"
                                                + " AND source_stage='VERSION'",
                                            Integer.class,
                                            Long.parseLong(board.id())))
                            .isZero();
                    jdbc.update(
                            "UPDATE public.nocode_report_dependency SET deleted=1 WHERE"
                                    + " source_kind='APPLICATION' AND source_id=? AND"
                                    + " source_stage='VERSION'",
                            Long.parseLong(board.id()));
                    assertThat(boards.deletePreview(board.id(), 10001).canDelete()).isTrue();
                    assertThatThrownBy(
                                    () ->
                                            boards.delete(
                                                    new ReportDashboards.Delete(
                                                            board.id(),
                                                            current.revision() - 1,
                                                            "旧修订"),
                                                    10001))
                            .hasMessageContaining("已被修改");
                    ReportDashboards.Deleted deleted =
                            boards.delete(
                                    new ReportDashboards.Delete(
                                            board.id(), current.revision(), "全部引用已解除"),
                                    10001);
                    assertThat(deleted.deleted()).isTrue();
                    assertThat(deleted.revision()).isEqualTo(current.revision() + 1);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM"
                                                + " public.nocode_report_dashboard_version WHERE"
                                                + " dashboard_id=? AND deleted=0",
                                            Long.class,
                                            Long.parseLong(board.id())))
                            .isEqualTo(1);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.nocode_report_dependency"
                                                + " WHERE source_kind='DASHBOARD' AND source_id=?"
                                                + " AND deleted=0",
                                            Long.class,
                                            Long.parseLong(board.id())))
                            .isZero();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM"
                                                + " public.nocode_report_operation_log WHERE"
                                                + " resource_kind='DASHBOARD' AND resource_id=? AND"
                                                + " action='DELETE' AND reason='全部引用已解除'",
                                            Long.class,
                                            Long.parseLong(board.id())))
                            .isEqualTo(1);
                    assertThatThrownBy(() -> boards.get(board.id(), 10001))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThat(datasets.deletePreview(seed.data().datasetId(), 10001).canDelete())
                            .isTrue();
                });
    }

    @Test
    void governanceRequiresSystemPermissionAndTheExactResourceAction() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Detail board = create(seed);
                    assertThat(board.capabilities().canDelete()).isTrue();
                    assertThatThrownBy(() -> boards.deletePreview(board.id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    boards.saveResource(
                            new ReportAuthorization.SaveDashboardResource(
                                    board.id(),
                                    0,
                                    List.of(
                                            new ReportAuthorization.ResourceMember(
                                                    "USER", "10002", Set.of("DELETE"))),
                                    "删除协作"),
                            10001);
                    publish(board, "governance_summary");
                    ReportDashboards.Detail published = boards.get(board.id(), 10001);
                    boards.save(
                            new ReportDashboards.Save(
                                    board.id(),
                                    published.revision(),
                                    renamed(published.draft(), "私密草稿名称")),
                            10001);
                    ReportDashboards.AvailableItem summary =
                            boards.availablePage(1, 10, fixture.prefix + "仪表板", 10002)
                                    .getList()
                                    .getFirst();
                    assertThat(summary.capabilities().canDelete()).isTrue();
                    assertThat(summary.capabilities().canView()).isFalse();
                    assertThat(summary.name()).isEqualTo(board.draft().name());
                    assertThat(summary.revision()).isNull();
                    assertThat(summary.modified()).isFalse();
                    assertThat(boards.deletePreview(board.id(), 10002).canDelete()).isTrue();
                    assertThatThrownBy(
                                    () ->
                                            boards.status(
                                                    new ReportDashboards.ChangeStatus(
                                                            board.id(),
                                                            board.revision(),
                                                            "INACTIVE",
                                                            "缺发布权限"),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    when(permissions.hasAnyPermissions(10002L, "nocode:report:manage"))
                            .thenReturn(false);
                    assertThat(boards.availablePage(1, 10, fixture.prefix, 10002).getTotal())
                            .isZero();
                    assertThatThrownBy(
                                    () ->
                                            boards.delete(
                                                    new ReportDashboards.Delete(
                                                            board.id(),
                                                            board.revision(),
                                                            "缺系统管理权限"),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> boards.releases(board.id(), 0, 10, 10001))
                            .hasMessageContaining("分页");
                });
    }

    @Test
    void copiedDefaultsRemainClearableAndQueriesApplyOnlySubmittedConditions() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Content base = content(seed, "默认条件");
                    List<ReportDashboards.Filter> filters =
                            List.of(
                                    new ReportDashboards.Filter(
                                            "names",
                                            "名称",
                                            "SELECT",
                                            List.of(new ReportDashboards.Mapping("target", "name")),
                                            new ReportDashboards.FilterDefault(
                                                    List.of("A"), null, null)),
                                    new ReportDashboards.Filter(
                                            "amounts",
                                            "金额",
                                            "NUMBER_RANGE",
                                            List.of(
                                                    new ReportDashboards.Mapping(
                                                            "target", "amount")),
                                            new ReportDashboards.FilterDefault(null, "10", "15")));
                    ReportDashboards.Detail board =
                            boards.save(
                                    new ReportDashboards.Save(
                                            null,
                                            0,
                                            new ReportDashboards.Content(
                                                    base.schemaVersion(),
                                                    base.name(),
                                                    base.description(),
                                                    base.charts(),
                                                    filters)),
                                    10001);
                    assertThat(board.draft().filters()).isEqualTo(filters);
                    ReportDashboards.Release released = publish(board, "filter_defaults");
                    ReportDashboards.Query clear = request(released);
                    assertThat(query.query(clear, 10001).recordCount()).isEqualTo(2);
                    ReportDashboards.Query submitted =
                            new ReportDashboards.Query(
                                    clear.id(),
                                    clear.chartId(),
                                    false,
                                    clear.versionNo(),
                                    clear.checksum(),
                                    List.of(
                                            new ReportDashboards.FilterValue(
                                                    "names", List.of("A"), null, null),
                                            new ReportDashboards.FilterValue(
                                                    "amounts", null, "10", "15")),
                                    null,
                                    null);
                    assertThat(query.query(submitted, 10001).recordCount()).isEqualTo(1);
                    assertThat(
                                    query.query(
                                                    new ReportDashboards.Query(
                                                            clear.id(),
                                                            clear.chartId(),
                                                            false,
                                                            clear.versionNo(),
                                                            clear.checksum(),
                                                            List.of(),
                                                            null,
                                                            null),
                                                    10001)
                                            .recordCount())
                            .isEqualTo(2);
                    ReportDashboards.Detail current = boards.get(board.id(), 10001);
                    ReportDashboards.Detail copied =
                            boards.copy(
                                    new ReportDashboards.Copy(
                                            board.id(),
                                            current.revision(),
                                            fixture.prefix + "默认条件副本",
                                            "默认条件复制"),
                                    10001);
                    assertThat(copied.draft().filters())
                            .extracting(ReportDashboards.Filter::defaultValue)
                            .containsExactly(
                                    filters.get(0).defaultValue(), filters.get(1).defaultValue());
                    assertThat(copied.draft().filters())
                            .extracting(ReportDashboards.Filter::id)
                            .doesNotContain("names", "amounts");
                    String target = copied.draft().charts().get(1).id();
                    assertThat(copied.draft().filters().get(0).mappings())
                            .containsExactly(new ReportDashboards.Mapping(target, "name"));
                    assertThat(copied.draft().filters().get(1).mappings())
                            .containsExactly(new ReportDashboards.Mapping(target, "amount"));
                    assertThat(boards.published(board.id(), 1, released.checksum(), 10001))
                            .isEqualTo(released);
                    assertThat(boards.get(board.id(), 10001).checksum())
                            .isEqualTo(current.checksum());
                });
    }

    @Test
    void rejectsEmptyMalformedOrIncompatibleDefaultsBeforeSaving() {
        rollback(
                () -> {
                    Seed seed = seed();
                    ReportDashboards.Content base = content(seed, "非法默认条件");
                    record InvalidDefault(
                            String kind,
                            String field,
                            ReportDashboards.FilterDefault value,
                            String message) {}
                    List<InvalidDefault> invalid =
                            List.of(
                                    new InvalidDefault(
                                            "TEXT",
                                            "name",
                                            new ReportDashboards.FilterDefault(
                                                    List.of(" "), null, null),
                                            "默认值不能为空"),
                                    new InvalidDefault(
                                            "SELECT",
                                            "name",
                                            new ReportDashboards.FilterDefault(
                                                    List.of(), null, null),
                                            "默认值不能为空"),
                                    new InvalidDefault(
                                            "SELECT",
                                            "name",
                                            new ReportDashboards.FilterDefault(
                                                    List.of("A", "B"), null, null),
                                            "数量"),
                                    new InvalidDefault(
                                            "MULTISELECT",
                                            "name",
                                            new ReportDashboards.FilterDefault(
                                                    Arrays.asList(null, null), null, null),
                                            "重复"),
                                    new InvalidDefault(
                                            "SELECT",
                                            "name",
                                            new ReportDashboards.FilterDefault(
                                                    List.of("A"), "1", null),
                                            "配置类型"),
                                    new InvalidDefault(
                                            "SELECT",
                                            "name",
                                            new ReportDashboards.FilterDefault(
                                                    List.of("x".repeat(2001)), null, null),
                                            "原键"),
                                    new InvalidDefault(
                                            "SELECT",
                                            "name",
                                            new ReportDashboards.FilterDefault(
                                                    List.of("A\u0000B"), null, null),
                                            "原键"),
                                    new InvalidDefault(
                                            "NUMBER_RANGE",
                                            "amount",
                                            new ReportDashboards.FilterDefault(
                                                    List.of("10"), null, null),
                                            "配置类型"),
                                    new InvalidDefault(
                                            "NUMBER_RANGE",
                                            "amount",
                                            new ReportDashboards.FilterDefault(null, null, null),
                                            "至少填写一端"),
                                    new InvalidDefault(
                                            "NUMBER_RANGE",
                                            "amount",
                                            new ReportDashboards.FilterDefault(null, "20", "10"),
                                            "起点晚于终点"),
                                    new InvalidDefault(
                                            "NUMBER_RANGE",
                                            "amount",
                                            new ReportDashboards.FilterDefault(null, "NaN", null),
                                            "格式无效"),
                                    new InvalidDefault(
                                            "SELECT",
                                            "amount",
                                            new ReportDashboards.FilterDefault(
                                                    List.of("invalid-number"), null, null),
                                            "原值类型"),
                                    new InvalidDefault(
                                            "DATE_RANGE",
                                            "name",
                                            new ReportDashboards.FilterDefault(
                                                    null, "2026-02-30", null),
                                            "格式无效"),
                                    new InvalidDefault(
                                            "DATE_RANGE",
                                            "name",
                                            new ReportDashboards.FilterDefault(
                                                    null, "2026-01-01", "2026-12-31"),
                                            "映射字段不匹配"));
                    for (InvalidDefault item : invalid) {
                        ReportDashboards.Filter filter =
                                new ReportDashboards.Filter(
                                        "default",
                                        "默认条件",
                                        item.kind(),
                                        List.of(
                                                new ReportDashboards.Mapping(
                                                        "target", item.field())),
                                        item.value());
                        assertThatThrownBy(
                                        () ->
                                                boards.save(
                                                        new ReportDashboards.Save(
                                                                null,
                                                                0,
                                                                new ReportDashboards.Content(
                                                                        base.schemaVersion(),
                                                                        base.name(),
                                                                        base.description(),
                                                                        base.charts(),
                                                                        List.of(filter))),
                                                        10001))
                                .hasMessageContaining(item.message());
                    }
                });
    }

    @Test
    void standaloneMenuDraftPublishHideRestoreCopyDeleteKeepsStableIdentity() {
        rollback(
                () -> {
                    MenuApi menus = servicesContext.getBean(MenuApi.class);
                    for (String action : List.of("create", "update", "delete"))
                        when(permissions.hasAnyPermissions(10001L, "system:menu:" + action))
                                .thenReturn(true);
                    Long parent = menuDirectory();
                    ReportDashboards.Detail board =
                            menuSave(
                                    create(seed()),
                                    new ReportDashboards.Navigation(
                                            true,
                                            parent.toString(),
                                            "经营报表",
                                            "BarChartOutlined",
                                            20));
                    assertThat(menuFor(board.id())).isNull();
                    ReportDashboards.Release first = publish(board, "nav_first");
                    MenuApi.Menu entry = menuFor(board.id());
                    assertThat(entry.path())
                            .isEqualTo("/nocode/report-center/dashboard-view?id=" + board.id());
                    assertThat(entry.parentId()).isEqualTo(parent);
                    assertThat(entry.name()).isEqualTo("经营报表");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.system_role_menu WHERE"
                                                    + " menu_id=? AND deleted=0",
                                            Long.class,
                                            entry.id()))
                            .isZero();
                    jdbc.update(
                            "INSERT INTO public.system_role_menu"
                                    + " (id,role_id,menu_id,creator,updater) VALUES"
                                    + " (nextval('public.system_role_menu_seq'),?,?,?,?)",
                            -entry.id(),
                            entry.id(),
                            fixture.prefix,
                            fixture.prefix);
                    ReportDashboards.Detail hidden =
                            menuSave(
                                    boards.get(board.id(), 10001),
                                    new ReportDashboards.Navigation(false, "", "隐藏名称", "", 20));
                    assertThat(menuFor(board.id()).visible()).isTrue();
                    publish(hidden, "nav_hide");
                    assertThat(menuFor(board.id()).visible()).isFalse();
                    assertThat(menuFor(board.id()).id()).isEqualTo(entry.id());
                    ReportDashboards.Detail restored =
                            boards.restore(
                                    new ReportDashboards.Restore(
                                            board.id(),
                                            boards.get(board.id(), 10001).revision(),
                                            first.versionNo(),
                                            "恢复菜单草稿"),
                                    10001);
                    assertThat(menuFor(board.id()).visible()).isFalse();
                    publish(restored, "nav_restore");
                    assertThat(menuFor(board.id()).visible()).isTrue();
                    assertThat(menuFor(board.id()).id()).isEqualTo(entry.id());
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.system_role_menu WHERE"
                                                    + " menu_id=? AND deleted=0",
                                            Long.class,
                                            entry.id()))
                            .isEqualTo(1);
                    ReportDashboards.Detail copy =
                            boards.copy(
                                    new ReportDashboards.Copy(
                                            board.id(),
                                            boards.get(board.id(), 10001).revision(),
                                            fixture.prefix + "复制",
                                            "不复制菜单"),
                                    10001);
                    assertThat(copy.draft().navigation()).isNull();
                    publish(copy, "nav_copy");
                    assertThat(menuFor(copy.id())).isNull();
                    boards.delete(
                            new ReportDashboards.Delete(
                                    board.id(), boards.get(board.id(), 10001).revision(), "清理自有菜单"),
                            10001);
                    assertThat(menuFor(board.id())).isNull();
                });
    }

    @Test
    void publishingUnchangedMenuDoesNotRequireMenuWritePermission() {
        rollback(
                () -> {
                    when(permissions.hasAnyPermissions(10001L, "system:menu:create"))
                            .thenReturn(true);
                    Long parent = menuDirectory();
                    ReportDashboards.Detail board =
                            menuSave(
                                    create(seed()),
                                    new ReportDashboards.Navigation(
                                            true, parent.toString(), "只改内容", "", 10));
                    publish(board, "nav_base");
                    when(permissions.hasAnyPermissions(10001L, "system:menu:create"))
                            .thenReturn(false);
                    MenuApi.Menu before = menuFor(board.id());
                    publish(boards.get(board.id(), 10001), "nav_content");
                    assertThat(menuFor(board.id())).isEqualTo(before);
                });
    }

    @Test
    void menuFailureRollsBackPublicationInNestedTransaction() {
        rollback(
                () -> {
                    ReportDashboards.Detail board =
                            menuSave(
                                    create(seed()),
                                    new ReportDashboards.Navigation(
                                            true, "9223372036854775807", "无效目录", "", 10));
                    TransactionTemplate nested = new TransactionTemplate(manager);
                    nested.setPropagationBehavior(
                            org.springframework.transaction.TransactionDefinition
                                    .PROPAGATION_NESTED);
                    assertThatThrownBy(
                                    () -> nested.execute(status -> publish(board, "nav_invalid")))
                            .hasMessageContaining("一级目录");
                    ReportDashboards.Detail after = boards.get(board.id(), 10001);
                    assertThat(after.publishedVersion()).isNull();
                    assertThat(after.revision()).isEqualTo(board.revision());
                    assertThat(boards.releases(board.id(), 1, 20, 10001).getTotal()).isZero();
                    assertThat(menuFor(board.id())).isNull();
                });
    }

    private Long menuDirectory() {
        return servicesContext
                .getBean(MenuApi.class)
                .create(
                        new MenuApi.Menu(
                                null,
                                fixture.prefix + "目录",
                                "",
                                1,
                                0,
                                0L,
                                "/" + fixture.prefix,
                                "",
                                "",
                                "",
                                0,
                                true,
                                false,
                                true));
    }

    private ReportDashboards.Detail menuSave(
            ReportDashboards.Detail board, ReportDashboards.Navigation navigation) {
        ReportDashboards.Content source = board.draft();
        return boards.save(
                new ReportDashboards.Save(
                        board.id(),
                        board.revision(),
                        new ReportDashboards.Content(
                                source.schemaVersion(),
                                source.name(),
                                source.description(),
                                source.charts(),
                                source.filters(),
                                navigation)),
                10001);
    }

    private MenuApi.Menu menuFor(String id) {
        return servicesContext.getBean(MenuApi.class).list().stream()
                .filter(menu -> ("NocodeDashboard_" + id).equals(menu.componentName()))
                .findFirst()
                .orElse(null);
    }

    private Seed seed() {
        String code = fixture.prefix + "life";
        ObjectDraft draft =
                service.create(
                        new SaveObjectDraft(
                                null,
                                null,
                                code,
                                code,
                                null,
                                "biz_" + code,
                                "name",
                                List.of(
                                        new FieldDefinition(
                                                "name", null, "name", "名称", "TEXT", 100, null, null,
                                                false, false, 0),
                                        new FieldDefinition(
                                                "amount", null, "amount", "金额", "DECIMAL", null, 20,
                                                2, false, false, 1)),
                                List.of()),
                        10001,
                        UUID.randomUUID());
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(draft.id(), draft.lockVersion(), null), 10001);
        assertThat(
                        publisher
                                .execute(new DataCenter.ExecutePlan(plan.id(), "生命周期夹具"), 10001)
                                .state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi.PublishedObject object =
                servicesContext.getBean(DataObjectApi.class).getVersion(draft.id(), null);
        Map<String, String> fields = new LinkedHashMap<>();
        draft.fields().forEach(field -> fields.put(field.code(), field.id()));
        ReportDatasets.Source source =
                new ReportDatasets.Source(
                        1,
                        new ReportDatasets.ObjectReference(
                                draft.id(), object.versionNo(), object.checksum()),
                        List.of(),
                        List.of(
                                new ReportDatasets.Field(
                                        "name", List.of(), fields.get("name"), "名称", "DIMENSION"),
                                new ReportDatasets.Field(
                                        "amount",
                                        List.of(),
                                        fields.get("amount"),
                                        "金额",
                                        "MEASURE")));
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
                                                        "sum", "金额", "SUM", "amount")),
                                        null,
                                        Map.of(),
                                        "Asia/Shanghai")),
                        10001);
        Set<String> fieldIds = Set.copyOf(fields.values());
        ObjectGrant grant =
                new ObjectGrant(
                        draft.id(),
                        Set.of("READ", "EXPORT"),
                        "ALL",
                        fieldIds,
                        Set.of(),
                        Set.of(),
                        Set.of());
        auth.saveCeiling(
                new ReportAuthorization.SaveCeiling(data.id(), draft.id(), 0, grant, "生命周期上限"),
                10003);
        auth.saveDataPolicy(
                new ReportAuthorization.SaveDataPolicy(
                        data.id(),
                        0,
                        List.of(new Member("USER", "10001", List.of(grant))),
                        "生命周期成员"),
                10001);
        ReportDatasets.Release released =
                datasets.publish(
                        new ReportDatasets.Publish(
                                data.id(), data.revision(), "dataset_lifecycle", "生命周期数据发布"),
                        10001);
        jdbc.update(
                "INSERT INTO public.\""
                        + object.definition().tableName()
                        + "\"(name,amount,creator,deleted) VALUES"
                        + " ('A',10.25,'10001',0),('B',20.75,'10001',0)");
        return new Seed(released, draft.id(), fieldIds);
    }

    private ObjectGrant grant(Seed seed) {
        return new ObjectGrant(
                seed.object(),
                Set.of("READ", "EXPORT"),
                "ALL",
                seed.fields(),
                Set.of(),
                Set.of(),
                Set.of());
    }

    private ReportDashboards.Content content(Seed seed, String suffix) {
        ReportDashboards.Dataset ref =
                new ReportDashboards.Dataset(
                        seed.data().datasetId(), seed.data().versionNo(), seed.data().checksum());
        List<ReportDatasetQueries.Dimension> dimensions =
                List.of(new ReportDatasetQueries.Dimension("name", "VALUE"));
        ReportDashboards.Chart from =
                new ReportDashboards.Chart(
                        "source",
                        "来源",
                        "TABLE",
                        ref,
                        dimensions,
                        List.of("sum"),
                        0,
                        0,
                        6,
                        4,
                        null,
                        null,
                        null,
                        List.of(new ReportDashboards.Link("target", "name", "name")));
        ReportDashboards.Chart to =
                new ReportDashboards.Chart(
                        "target", "目标", "TABLE", ref, dimensions, List.of("sum"), 6, 0, 6, 4);
        return new ReportDashboards.Content(
                1,
                fixture.prefix + suffix,
                "",
                List.of(from, to),
                List.of(
                        new ReportDashboards.Filter(
                                "names",
                                "名称",
                                "SELECT",
                                List.of(new ReportDashboards.Mapping("target", "name")))));
    }

    private ReportDashboards.Content renamed(ReportDashboards.Content source, String name) {
        return new ReportDashboards.Content(
                source.schemaVersion(),
                fixture.prefix + name,
                source.description(),
                source.charts(),
                source.filters());
    }

    private ReportDashboards.Detail create(Seed seed) {
        return boards.save(new ReportDashboards.Save(null, 0, content(seed, "仪表板")), 10001);
    }

    private ReportDashboards.Release publish(ReportDashboards.Detail board, String key) {
        return boards.publish(
                new ReportDashboards.Publish(board.id(), board.revision(), key), 10001);
    }

    private ReportDashboards.Query request(ReportDashboards.Release release) {
        return new ReportDashboards.Query(
                release.id(), "target", false, release.versionNo(), release.checksum());
    }

    private Long folder(String kind, String name) {
        return jdbc.queryForObject(
                "INSERT INTO public.nocode_report_folder(resource_kind,name,creator,updater)"
                        + " VALUES(?,?, '10001','10001') RETURNING id",
                Long.class,
                kind,
                fixture.prefix + name);
    }

    private void incoming(String target, String stage, int version) {
        jdbc.update(
                "INSERT INTO"
                    + " public.nocode_report_dependency(source_kind,source_id,source_stage,source_version,target_kind,target_id,target_version)"
                    + " VALUES('APPLICATION',?,?,?,'DASHBOARD',?,1)",
                Long.parseLong(target),
                stage,
                version,
                Long.parseLong(target));
    }

    private void rollback(Runnable action) {
        new TransactionTemplate(manager)
                .execute(
                        tx -> {
                            action.run();
                            tx.setRollbackOnly();
                            return null;
                        });
    }

    private void changed(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(com.lingan.ucp.framework.common.exception.ServiceException.class)
                .satisfies(
                        error ->
                                assertThat(
                                                ((com.lingan.ucp.framework.common.exception
                                                                        .ServiceException)
                                                                error)
                                                        .getCode())
                                        .isEqualTo(
                                                com.lingan.ucp.nocode.api.NocodeErrorCodes
                                                        .VERSION_CHANGED));
    }
}
