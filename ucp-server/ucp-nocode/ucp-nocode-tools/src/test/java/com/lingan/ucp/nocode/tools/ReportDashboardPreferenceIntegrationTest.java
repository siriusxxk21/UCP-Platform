package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.lingan.ucp.module.system.api.permission.PermissionApi;
import com.lingan.ucp.module.system.api.permission.RoleApi;
import com.lingan.ucp.module.system.api.user.AdminUserApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.Member;
import com.lingan.ucp.nocode.api.ApplicationAuthorization.ObjectGrant;
import com.lingan.ucp.nocode.enums.ReportResourceKindEnum;
import com.lingan.ucp.nocode.report.dal.mapper.ReportDashboardPreferenceMapper;
import com.lingan.ucp.nocode.report.service.authorization.ReportDatasetAuthorizationService;
import com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardService;
import com.lingan.ucp.nocode.report.service.dataset.ReportDatasetService;
import com.lingan.ucp.nocode.report.service.folder.ReportFolderService;
import com.lingan.ucp.nocode.report.service.preference.*;

import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 当前开发库验证分类、发布摘要与个人偏好；使用正式发布链路，业务变更每项回滚。 */
class ReportDashboardPreferenceIntegrationTest {
    private NocodeIntegrationSupport fixture;
    private AnnotationConfigApplicationContext reportContext, context;
    private ReportDashboardService boards;
    private ReportDatasetService datasets;
    private ReportDatasetAuthorizationService authorization;
    private ReportFolderService folders;
    private ReportDashboardPreferenceService preferences;
    private PermissionApi permissions;

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
        context = new AnnotationConfigApplicationContext();
        context.setParent(reportContext);
        context.registerBean(
                ReportDashboardPreferenceMapper.class,
                () -> session.getMapper(ReportDashboardPreferenceMapper.class));
        context.register(ReportDashboardPreferenceServiceImpl.class);
        context.refresh();
        boards = context.getBean(ReportDashboardService.class);
        datasets = context.getBean(ReportDatasetService.class);
        authorization = context.getBean(ReportDatasetAuthorizationService.class);
        folders = context.getBean(ReportFolderService.class);
        preferences = context.getBean(ReportDashboardPreferenceService.class);
        permissions = servicesContext.getBean(PermissionApi.class);
        reset(
                permissions,
                servicesContext.getBean(RoleApi.class),
                servicesContext.getBean(AdminUserApi.class));
        ReportIntegrationSupport.activeUsers(10001, 10002, 10003);
        for (long actor : List.of(10001L, 10003L)) {
            for (String action :
                    List.of("query", "create", "update", "publish", "manage", "authorize"))
                when(permissions.hasAnyPermissions(actor, "nocode:report:" + action))
                        .thenReturn(true);
            when(permissions.hasAnyPermissions(actor, "nocode:object:query")).thenReturn(true);
        }
        when(permissions.hasAnyPermissions(10003L, "nocode:object:share")).thenReturn(true);
        // 普通受众只有 report query 和精确 VIEW，不能因收藏获取数据集设计权限。
        when(permissions.hasAnyPermissions(10002L, "nocode:report:query")).thenReturn(true);
    }

    @AfterEach
    void cleanup() {
        writeFailure.clear();
        context.close();
        reportContext.close();
        fixture.clean();
    }

    @Test
    void directoriesKeepDatasetDefaultAndRejectCrossKindParentsAndDestinations() {
        rollback(
                () -> {
                    ReportFolders.Item data =
                            folders.save(
                                    new ReportFolders.Save(
                                            null, 0, null, fixture.prefix + "同名", 0, "兼容入口"),
                                    10001);
                    ReportFolders.Item board = folder(null, "同名");
                    assertThat(folders.tree(10001))
                            .extracting(ReportFolders.Item::id)
                            .contains(data.id())
                            .doesNotContain(board.id());
                    assertThat(folders.tree("DASHBOARD", 10001))
                            .extracting(ReportFolders.Item::id)
                            .contains(board.id())
                            .doesNotContain(data.id());
                    assertThat(folders.destination(data.id(), 10001))
                            .isEqualTo(Long.valueOf(data.id()));
                    assertThat(
                                    folders.destination(
                                            board.id(), 10001, ReportResourceKindEnum.DASHBOARD))
                            .isEqualTo(Long.valueOf(board.id()));
                    assertThatThrownBy(() -> folders.destination(board.id(), 10001))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            folders.destination(
                                                    data.id(),
                                                    10001,
                                                    ReportResourceKindEnum.DASHBOARD))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> folder(data.id(), "错父级"))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> folders.tree("FOLDER", 10001))
                            .hasMessageContaining("类别");
                    assertThatThrownBy(
                                    () ->
                                            folders.save(
                                                    new ReportFolders.Save(
                                                            board.id(),
                                                            board.revision(),
                                                            null,
                                                            board.name(),
                                                            0,
                                                            "错类别",
                                                            "DATASET"),
                                                    10001))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            folders.delete(
                                                    new ReportFolders.Delete(
                                                            board.id(), board.revision(), "错类别"),
                                                    10001))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> folder(null, "同名")).hasMessageContaining("已存在");
                    ReportFolders.Item child = folder(board.id(), "子目录");
                    assertThatThrownBy(
                                    () ->
                                            folders.save(
                                                    new ReportFolders.Save(
                                                            board.id(),
                                                            board.revision(),
                                                            child.id(),
                                                            board.name(),
                                                            0,
                                                            "形成循环",
                                                            "DASHBOARD"),
                                                    10001))
                            .hasMessageContaining("后代");
                    ReportFolders.Item level = child;
                    for (int depth = 3; depth <= 8; depth++)
                        level = folder(level.id(), "层级" + depth);
                    String last = level.id();
                    assertThatThrownBy(() -> folder(last, "第九层")).hasMessageContaining("8 层");
                });
    }

    @Test
    void viewerDiscoversOnlyAncestorsWithoutInheritingSiblingContentOrDatasetAccess() {
        rollback(
                () -> {
                    ReportDatasets.Release data = seed();
                    ReportFolders.Item root = folder(null, "根"),
                            child = folder(root.id(), "子"),
                            hidden = folder(null, "隐藏");
                    ReportDashboards.Detail visible = board(data, "公开", child.id());
                    ReportDashboards.Detail sibling = board(data, "同目录私有", child.id());
                    ReportDashboards.Detail secret = board(data, "隐藏私有", hidden.id());
                    publish(visible);
                    publish(sibling);
                    publish(secret);
                    share(visible.id(), 0, true);
                    assertThat(folders.tree("DASHBOARD", 10002))
                            .extracting(ReportFolders.Item::id)
                            .contains(root.id(), child.id())
                            .doesNotContain(hidden.id());
                    assertThat(
                                    boards.availablePage(
                                                    1, 100, fixture.prefix, root.id(), null, 10002)
                                            .getList())
                            .extracting(ReportDashboards.AvailableItem::id)
                            .containsExactly(visible.id());
                    assertThat(preferences.page(1, 100, fixture.prefix, "ALL", 10002).getList())
                            .extracting(item -> item.dashboard().id())
                            .containsExactly(visible.id());
                    assertThatThrownBy(() -> boards.get(sibling.id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> datasets.get(data.datasetId(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            folders.destination(
                                                    hidden.id(),
                                                    10002,
                                                    ReportResourceKindEnum.DASHBOARD))
                            .isInstanceOf(AccessDeniedException.class);
                    share(visible.id(), 1, false);
                    assertThat(folders.tree("DASHBOARD", 10002))
                            .extracting(ReportFolders.Item::id)
                            .doesNotContain(root.id(), child.id(), hidden.id());
                    when(permissions.hasAnyPermissions(10002L, "nocode:report:manage"))
                            .thenReturn(true);
                    assertThat(folders.tree("DASHBOARD", 10002))
                            .extracting(ReportFolders.Item::id)
                            .contains(hidden.id());
                    assertThat(preferences.page(1, 100, fixture.prefix, "ALL", 10002).getList())
                            .isEmpty();
                    assertThatThrownBy(() -> boards.get(secret.id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void dashboardDirectoriesProtectChildrenAndResourcesAndDeleteOnlyAfterMovingOut() {
        rollback(
                () -> {
                    ReportDatasets.Release data = seed();
                    ReportFolders.Item root = folder(null, "根"), child = folder(root.id(), "子");
                    ReportDashboards.Detail draft = board(data, "占用", child.id());
                    assertThatThrownBy(
                                    () ->
                                            folders.delete(
                                                    new ReportFolders.Delete(
                                                            root.id(),
                                                            root.revision(),
                                                            "含子目录",
                                                            "DASHBOARD"),
                                                    10001))
                            .hasMessageContaining("非空");
                    assertThatThrownBy(
                                    () ->
                                            folders.delete(
                                                    new ReportFolders.Delete(
                                                            child.id(),
                                                            child.revision(),
                                                            "含看板",
                                                            "DASHBOARD"),
                                                    10001))
                            .hasMessageContaining("非空");
                    ReportDashboards.Detail moved =
                            boards.move(
                                    new ReportDashboards.Move(
                                            draft.id(), draft.revision(), null, "移出目录"),
                                    10001);
                    assertThat(moved.draft()).isEqualTo(draft.draft());
                    folders.delete(
                            new ReportFolders.Delete(
                                    child.id(), child.revision(), "删除空目录", "DASHBOARD"),
                            10001);
                    folders.delete(
                            new ReportFolders.Delete(
                                    root.id(), root.revision(), "删除空根", "DASHBOARD"),
                            10001);
                    assertThat(folders.tree("DASHBOARD", 10001))
                            .extracting(ReportFolders.Item::id)
                            .doesNotContain(root.id(), child.id());
                });
    }

    @Test
    void preferencesArePerActorIdempotentAndUsePublishedSummaryEvenForOwner() {
        rollback(
                () -> {
                    ReportDatasets.Release data = seed();
                    ReportDashboards.Detail first = board(data, "发布名称", null),
                            second = board(data, "第二看板", null);
                    publish(first);
                    publish(second);
                    share(first.id(), 0, true);
                    share(second.id(), 0, true);
                    assertThat(preferences.get(first.id(), 10002))
                            .isEqualTo(new ReportDashboardPreferences.State(false, null));
                    preferences.favorite(
                            new ReportDashboardPreferences.SetFavorite(first.id(), true), 10002);
                    ReportDashboardPreferenceMapper mapper =
                            context.getBean(ReportDashboardPreferenceMapper.class);
                    java.time.LocalDateTime updated =
                            mapper.get(10002, Long.parseLong(first.id())).getUpdateTime();
                    preferences.favorite(
                            new ReportDashboardPreferences.SetFavorite(first.id(), true), 10002);
                    assertThat(mapper.get(10002, Long.parseLong(first.id())).getUpdateTime())
                            .isEqualTo(updated);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.nocode_report_preference"
                                                    + " WHERE user_id=? AND dashboard_id=?",
                                            Long.class,
                                            10002,
                                            Long.parseLong(first.id())))
                            .isEqualTo(1L);
                    String visited =
                            preferences
                                    .visit(new ReportDashboardPreferences.Visit(first.id()), 10002)
                                    .lastVisitedAt();
                    assertThat(visited).isNotBlank();
                    assertThat(
                                    preferences
                                            .favorite(
                                                    new ReportDashboardPreferences.SetFavorite(
                                                            first.id(), true),
                                                    10002)
                                            .lastVisitedAt())
                            .isEqualTo(visited);
                    assertThat(
                                    preferences
                                            .visit(
                                                    new ReportDashboardPreferences.Visit(
                                                            second.id()),
                                                    10002)
                                            .favorite())
                            .isFalse();
                    assertThat(preferences.page(1, 100, fixture.prefix, "RECENT", 10002).getList())
                            .extracting(item -> item.dashboard().id())
                            .containsExactly(second.id(), first.id());
                    assertThat(
                                    preferences
                                            .page(1, 100, fixture.prefix, "FAVORITE", 10002)
                                            .getList())
                            .extracting(item -> item.dashboard().id())
                            .containsExactly(first.id());
                    preferences.favorite(
                            new ReportDashboardPreferences.SetFavorite(first.id(), true), 10001);
                    preferences.favorite(
                            new ReportDashboardPreferences.SetFavorite(first.id(), false), 10002);
                    assertThat(preferences.get(first.id(), 10001).favorite()).isTrue();
                    assertThat(preferences.get(first.id(), 10002).favorite()).isFalse();
                    ReportDashboards.Detail current = boards.get(first.id(), 10001);
                    boards.save(
                            new ReportDashboards.Save(
                                    first.id(),
                                    current.revision(),
                                    new ReportDashboards.Content(
                                            1, fixture.prefix + "私密草稿名称", "私密说明", List.of())),
                            10001);
                    ReportDashboards.AvailableItem owner =
                            preferences
                                    .page(1, 100, fixture.prefix + "发布名称", "ALL", 10001)
                                    .getList()
                                    .getFirst()
                                    .dashboard();
                    assertThat(owner.name()).isEqualTo(first.draft().name());
                    assertThat(owner.chartCount()).isEqualTo(1);
                    assertThat(owner.modified()).isFalse();
                    assertThat(owner.revision()).isNull();
                    assertThat(owner.capabilities().canEdit()).isTrue();
                    assertThat(preferences.page(1, 100, "私密草稿名称", "ALL", 10001).getList())
                            .isEmpty();
                    assertThat(
                                    preferences
                                            .page(1, 100, fixture.prefix + "发布名称", "ALL", 10002)
                                            .getList()
                                            .getFirst()
                                            .dashboard()
                                            .name())
                            .isEqualTo(owner.name());
                });
    }

    @Test
    void withdrawalInactiveAndDeletedHeadsFilterListsButPreservePersonalState() {
        rollback(
                () -> {
                    ReportDashboards.Detail draft = board(seed(), "状态", null);
                    publish(draft);
                    share(draft.id(), 0, true);
                    preferences.favorite(
                            new ReportDashboardPreferences.SetFavorite(draft.id(), true), 10002);
                    preferences.visit(new ReportDashboardPreferences.Visit(draft.id()), 10002);
                    share(draft.id(), 1, false);
                    assertThat(
                                    preferences
                                            .page(1, 100, fixture.prefix, "FAVORITE", 10002)
                                            .getList())
                            .isEmpty();
                    assertThatThrownBy(() -> preferences.get(draft.id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(
                                    () ->
                                            preferences.favorite(
                                                    new ReportDashboardPreferences.SetFavorite(
                                                            draft.id(), false),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    share(draft.id(), 2, true);
                    assertThat(preferences.get(draft.id(), 10002).favorite()).isTrue();
                    ReportDashboards.Detail current = boards.get(draft.id(), 10001);
                    ReportDashboards.Detail inactive =
                            boards.status(
                                    new ReportDashboards.ChangeStatus(
                                            draft.id(), current.revision(), "INACTIVE", "暂时停用"),
                                    10001);
                    assertThat(preferences.page(1, 100, fixture.prefix, "RECENT", 10002).getList())
                            .isEmpty();
                    assertThatThrownBy(
                                    () ->
                                            preferences.visit(
                                                    new ReportDashboardPreferences.Visit(
                                                            draft.id()),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                    ReportDashboards.Detail active =
                            boards.status(
                                    new ReportDashboards.ChangeStatus(
                                            draft.id(), inactive.revision(), "ACTIVE", "恢复入口"),
                                    10001);
                    assertThat(preferences.get(draft.id(), 10002).favorite()).isTrue();
                    assertThat(preferences.get(draft.id(), 10002).lastVisitedAt()).isNotBlank();
                    boards.delete(
                            new ReportDashboards.Delete(draft.id(), active.revision(), "删除自己的测试看板"),
                            10001);
                    assertThat(preferences.page(1, 100, fixture.prefix, "ALL", 10002).getList())
                            .isEmpty();
                    assertThat(
                                    context.getBean(ReportDashboardPreferenceMapper.class)
                                            .get(10002, Long.parseLong(draft.id()))
                                            .getFavorite())
                            .isTrue();
                    assertThatThrownBy(() -> preferences.get(draft.id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    @Test
    void preferenceCommandsRejectUnpublishedOrUnauthorizedResourcesAndInvalidParameters() {
        rollback(
                () -> {
                    ReportDashboards.Detail draft = board(seed(), "尚未发布", null);
                    assertThat(preferences.page(1, 100, fixture.prefix, null, 10001).getList())
                            .isEmpty();
                    assertThatThrownBy(
                                    () ->
                                            preferences.favorite(
                                                    new ReportDashboardPreferences.SetFavorite(
                                                            draft.id(), true),
                                                    10001))
                            .isInstanceOf(AccessDeniedException.class);
                    publish(draft);
                    assertThatThrownBy(() -> preferences.get(draft.id(), 10002))
                            .isInstanceOf(AccessDeniedException.class);
                    assertThatThrownBy(() -> preferences.page(0, 20, null, null, 10001))
                            .hasMessageContaining("分页");
                    assertThatThrownBy(() -> preferences.page(1, 101, null, null, 10001))
                            .hasMessageContaining("分页");
                    assertThatThrownBy(() -> preferences.page(1, 20, "a".repeat(81), null, 10001))
                            .hasMessageContaining("80");
                    assertThatThrownBy(() -> preferences.page(1, 20, null, "ANY", 10001))
                            .hasMessageContaining("偏好");
                    assertThatThrownBy(
                                    () ->
                                            preferences.favorite(
                                                    new ReportDashboardPreferences.SetFavorite(
                                                            draft.id(), null),
                                                    10001))
                            .hasMessageContaining("收藏");
                    assertThatThrownBy(() -> preferences.get("0", 10001))
                            .hasMessageContaining("仪表板");
                    assertThatThrownBy(() -> preferences.get(draft.id(), 0))
                            .isInstanceOf(AccessDeniedException.class);
                    share(draft.id(), 0, true);
                    when(permissions.hasAnyPermissions(10002L, "nocode:report:query"))
                            .thenReturn(false);
                    assertThatThrownBy(
                                    () ->
                                            preferences.visit(
                                                    new ReportDashboardPreferences.Visit(
                                                            draft.id()),
                                                    10002))
                            .isInstanceOf(AccessDeniedException.class);
                });
    }

    private ReportFolders.Item folder(String parent, String suffix) {
        return folders.save(
                new ReportFolders.Save(
                        null, 0, parent, fixture.prefix + suffix, 0, "看板分类验收", "DASHBOARD"),
                10001);
    }

    private void share(String id, int revision, boolean visible) {
        boards.saveResource(
                new ReportAuthorization.SaveDashboardResource(
                        id,
                        revision,
                        visible
                                ? List.of(
                                        new ReportAuthorization.ResourceMember(
                                                "USER", "10002", Set.of("VIEW")))
                                : List.of(),
                        "个人偏好权限验收"),
                10001);
    }

    private ReportDashboards.Detail board(
            ReportDatasets.Release data, String suffix, String folder) {
        ReportDashboards.Chart chart =
                new ReportDashboards.Chart(
                        "amount",
                        "金额",
                        "TABLE",
                        new ReportDashboards.Dataset(
                                data.datasetId(), data.versionNo(), data.checksum()),
                        List.of(new ReportDatasetQueries.Dimension("name", "VALUE")),
                        List.of("sum"),
                        0,
                        0,
                        12,
                        4);
        return boards.save(
                new ReportDashboards.Save(
                        null,
                        0,
                        new ReportDashboards.Content(
                                1, fixture.prefix + suffix, "", List.of(chart)),
                        folder),
                10001);
    }

    private void publish(ReportDashboards.Detail draft) {
        boards.publish(
                new ReportDashboards.Publish(
                        draft.id(), draft.revision(), UUID.randomUUID().toString()),
                10001);
    }

    private ReportDatasets.Release seed() {
        String code = fixture.prefix + "pref";
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
                                .execute(new DataCenter.ExecutePlan(plan.id(), "偏好正式来源夹具"), 10001)
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
        ObjectGrant grant =
                new ObjectGrant(
                        draft.id(),
                        Set.of("READ", "EXPORT"),
                        "ALL",
                        Set.copyOf(fields.values()),
                        Set.of(),
                        Set.of(),
                        Set.of());
        authorization.saveCeiling(
                new ReportAuthorization.SaveCeiling(data.id(), draft.id(), 0, grant, "偏好上限"),
                10003);
        authorization.saveDataPolicy(
                new ReportAuthorization.SaveDataPolicy(
                        data.id(),
                        0,
                        List.of(new Member("USER", "10001", List.of(grant))),
                        "偏好所有者"),
                10001);
        return datasets.publish(
                new ReportDatasets.Publish(
                        data.id(), data.revision(), UUID.randomUUID().toString(), "偏好来源发布"),
                10001);
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
