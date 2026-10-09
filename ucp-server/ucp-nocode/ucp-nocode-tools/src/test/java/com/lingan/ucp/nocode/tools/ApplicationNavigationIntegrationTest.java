package com.lingan.ucp.nocode.tools;

import static com.lingan.ucp.nocode.tools.NocodeIntegrationSupport.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lingan.ucp.framework.common.biz.system.permission.PermissionCommonApi;
import com.lingan.ucp.module.system.api.permission.MenuApi;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.api.ApplicationCenter.*;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.application.service.resource.ApplicationAutomationCatalog;

import org.junit.jupiter.api.*;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** 当前开发库验证真实发布、底座菜单服务及事务回滚；全部夹具仅在回滚事务中存在。 */
class ApplicationNavigationIntegrationTest {
    private static final long ACTOR = 10001L;
    private NocodeIntegrationSupport fixture;
    private ApplicationService applications;
    private MenuApi menus;
    private PermissionCommonApi permissions;

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
        applications = servicesContext.getBean(ApplicationService.class);
        menus = servicesContext.getBean(MenuApi.class);
        permissions = servicesContext.getBean(PermissionCommonApi.class);
        allow(true);
    }

    @AfterEach
    void cleanup() {
        writeFailure.clear();
        fixture.clean();
    }

    @Test
    void draftDoesNotChangePlatformAndPublishCreatesStablePageRoutesWithoutRoleGrants() {
        rollback(
                () -> {
                    Long parent = directory("采购");
                    ObjectReference object = reference();
                    Resource page = page("home");
                    Resource setting = setting("home", parent, "采购工作台", true, true);
                    Detail app = save(null, "app", object, page, setting);
                    String appId = app.application().id();
                    assertThat(owned(appId)).isEmpty();
                    app = publish(app);
                    MenuApi.Menu menu = owned(appId).getFirst();
                    assertThat(menu.path())
                            .isEqualTo("/nocode-app/runtime?id=" + appId + "&page=home");
                    assertThat(menu.parentId()).isEqualTo(parent);
                    assertThat(menu.visible()).isTrue();
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.system_role_menu WHERE"
                                                    + " menu_id=? AND deleted=0",
                                            Long.class,
                                            menu.id()))
                            .isZero();
                    app =
                            save(
                                    app,
                                    "app",
                                    object,
                                    page,
                                    setting("home", parent, "采购总览", true, true));
                    assertThat(owned(appId).getFirst().name()).isEqualTo("采购工作台");
                    publish(app);
                    assertThat(owned(appId).getFirst().id()).isEqualTo(menu.id());
                    assertThat(owned(appId).getFirst().name()).isEqualTo("采购总览");
                });
    }

    @Test
    void hidingAndShowingRetainMenuIdentityRoleAuthorizationAndOnlyNeedUpdatePermission() {
        rollback(
                () -> {
                    Long parent = directory("采购");
                    ObjectReference object = reference();
                    Detail app =
                            publish(
                                    save(
                                            null,
                                            "app",
                                            object,
                                            page("home"),
                                            setting("home", parent, "工作台", true, true)));
                    String appId = app.application().id();
                    MenuApi.Menu original = owned(appId).getFirst();
                    jdbc.update(
                            "INSERT INTO public.system_role_menu"
                                    + " (id,role_id,menu_id,creator,updater) VALUES"
                                    + " (nextval('public.system_role_menu_seq'),?,?,?,?)",
                            -original.id(),
                            original.id(),
                            fixture.prefix,
                            fixture.prefix);
                    allow(false);
                    Detail hiddenDraft =
                            save(
                                    app,
                                    "app",
                                    object,
                                    page("home"),
                                    setting("home", null, "隐藏草稿标题", false, true));
                    expectPublishFailure(hiddenDraft, "修改平台页面菜单");
                    when(permissions.hasAnyPermissions(ACTOR, "system:menu:update"))
                            .thenReturn(true);
                    app = publish(hiddenDraft);
                    MenuApi.Menu hidden = owned(appId).getFirst();
                    assertThat(hidden.id()).isEqualTo(original.id());
                    assertThat(hidden.parentId()).isEqualTo(parent);
                    assertThat(hidden.path()).isEqualTo(original.path());
                    assertThat(hidden.name()).isEqualTo(original.name());
                    assertThat(hidden.visible()).isFalse();
                    assertThat(roleBindings(original.id())).isEqualTo(1L);
                    allow(false);
                    app = publish(app);
                    when(permissions.hasAnyPermissions(ACTOR, "system:menu:update"))
                            .thenReturn(true);
                    grantApplicationObjects(appId);
                    Published runtime =
                            servicesContext
                                    .getBean(
                                            com.lingan.ucp.nocode.runtime.service.application
                                                    .ApplicationRuntimeService.class)
                                    .application(appId, ACTOR);
                    assertThat(
                                    runtime.definition().resources().stream()
                                            .filter(resource -> resource.id().equals("nav_home"))
                                            .findFirst()
                                            .orElseThrow()
                                            .config())
                            .containsEntry("navigationVersion", 2)
                            .containsEntry("showInMenu", false)
                            .containsEntry("defaultHome", true);
                    app =
                            publish(
                                    save(
                                            app,
                                            "app",
                                            object,
                                            page("home"),
                                            setting("home", parent, "再次显示", true, true)));
                    MenuApi.Menu shown = owned(appId).getFirst();
                    assertThat(shown.id()).isEqualTo(original.id());
                    assertThat(shown.visible()).isTrue();
                    assertThat(shown.name()).isEqualTo("再次显示");
                    assertThat(roleBindings(original.id())).isEqualTo(1L);
                    verify(
                                    servicesContext.getBean(
                                            com.lingan.ucp.module.system.service.permission
                                                    .PermissionService.class),
                                    never())
                            .processMenuDeleted(original.id());
                });
    }

    @Test
    void hiddenNonHomeDashboardCanGainRecordContextWhileVisibleHomeAndLegacyEntriesStillRejectIt() {
        rollback(
                () -> {
                    ObjectReference object = reference();
                    Resource dashboard =
                            resource(
                                    "dashboard",
                                    "REPORT_DASHBOARD",
                                    new ApplicationDashboards.Config(
                                            new ApplicationDashboards.Reference(
                                                    "17", 1, "a".repeat(64)),
                                            object.objectId(),
                                            List.of(),
                                            List.of()));
                    Definition hidden =
                            new Definition(
                                    List.of(object),
                                    List.of(
                                            dashboard,
                                            setting("dashboard", null, "记录上下文看板", false, false)));
                    assertThat(applications.normalize(hidden).resources()).hasSize(2);
                    assertThatThrownBy(
                                    () ->
                                            applications.normalize(
                                                    new Definition(
                                                            List.of(object),
                                                            List.of(
                                                                    dashboard,
                                                                    setting(
                                                                            "dashboard",
                                                                            17L,
                                                                            "可见入口",
                                                                            true,
                                                                            false)))))
                            .hasMessageContaining("依赖当前记录");
                    assertThatThrownBy(
                                    () ->
                                            applications.normalize(
                                                    new Definition(
                                                            List.of(object),
                                                            List.of(
                                                                    dashboard,
                                                                    setting(
                                                                            "dashboard",
                                                                            null,
                                                                            "应用首页",
                                                                            false,
                                                                            true)))))
                            .hasMessageContaining("依赖当前记录");
                    assertThatThrownBy(
                                    () ->
                                            applications.normalize(
                                                    new Definition(
                                                            List.of(object),
                                                            List.of(
                                                                    dashboard,
                                                                    resource(
                                                                            "legacy",
                                                                            "MENU",
                                                                            new ApplicationUi.Menu(
                                                                                    "dashboard"))))))
                            .hasMessageContaining("含记录上下文的仪表板");
                });
    }

    @Test
    void hiddenPageWithoutAnExistingPlatformEntryDoesNotCreateOneOrRequireMenuPermissions() {
        rollback(
                () -> {
                    ObjectReference object = reference();
                    allow(false);
                    Detail app =
                            publish(
                                    save(
                                            null,
                                            "app",
                                            object,
                                            page("home"),
                                            setting("home", null, "应用首页", false, true)));
                    assertThat(owned(app.application().id())).isEmpty();
                });
    }

    private long roleBindings(Long menuId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM public.system_role_menu WHERE menu_id=? AND deleted=0",
                Long.class,
                menuId);
    }

    @Test
    void unchangedMenusPublishWithoutMenuPermissionButEveryActualMutationRequiresItsPermission() {
        rollback(
                () -> {
                    Long parent = directory("采购");
                    ObjectReference object = reference();
                    Detail app =
                            publish(
                                    save(
                                            null,
                                            "app",
                                            object,
                                            page("home"),
                                            setting("home", parent, "工作台", true, true)));
                    allow(false);
                    app = publish(app);
                    assertThat(app.application().publishedVersion()).isEqualTo(2);
                    Detail rename =
                            save(
                                    app,
                                    "app",
                                    object,
                                    page("home"),
                                    setting("home", parent, "已修改", true, true));
                    expectPublishFailure(rename, "修改平台页面菜单");
                    assertThat(owned(app.application().id()).getFirst().name()).isEqualTo("工作台");
                    assertThat(
                                    applications
                                            .get(app.application().id())
                                            .application()
                                            .publishedVersion())
                            .isEqualTo(2);
                    Detail remove = save(rename, "app", object, page("home"));
                    expectPublishFailure(remove, "删除平台页面菜单");
                    assertThat(owned(app.application().id())).hasSize(1);
                    Detail newApp =
                            save(
                                    null,
                                    "newapp",
                                    object,
                                    page("other"),
                                    setting("other", parent, "新入口", true, false));
                    expectPublishFailure(newApp, "新增平台页面菜单");
                    assertThat(owned(newApp.application().id())).isEmpty();
                });
    }

    @Test
    void deletingResourceCleansOnlyOwnedEntryAndRestoringVersionRecreatesItsMenu() {
        rollback(
                () -> {
                    Long parent = directory("采购");
                    ObjectReference object = reference();
                    Detail app =
                            save(
                                    null,
                                    "app",
                                    object,
                                    page("home"),
                                    setting("home", parent, "工作台", true, true));
                    String appId = app.application().id();
                    Long manual =
                            menus.create(
                                    new MenuApi.Menu(
                                            null,
                                            "旧应用入口",
                                            "",
                                            2,
                                            99,
                                            parent,
                                            "/nocode-app/runtime?id=" + appId,
                                            "",
                                            "nocode/application/runtime",
                                            "NocodeApplication_" + appId,
                                            0,
                                            true,
                                            false,
                                            true));
                    app = publish(app);
                    app = publish(save(app, "app", object, page("replacement")));
                    assertThat(owned(appId)).isEmpty();
                    assertThat(menus.list()).anyMatch(menu -> menu.id().equals(manual));
                    Detail restored =
                            applications.restore(
                                    new Restore(appId, app.application().revision(), 1, "恢复入口验证"),
                                    ACTOR);
                    assertThat(restored.application().publishedVersion()).isEqualTo(3);
                    assertThat(owned(appId)).hasSize(1);
                    assertThat(owned(appId).getFirst().path()).endsWith("&page=home");
                    assertThat(menus.list()).anyMatch(menu -> menu.id().equals(manual));
                });
    }

    @Test
    void menuWritesAndVersionPointerRollBackTogetherOnFailureAfterDatabaseWrite() {
        rollback(
                () -> {
                    Long parent = directory("采购");
                    ObjectReference object = reference();
                    Detail app =
                            publish(
                                    save(
                                            null,
                                            "app",
                                            object,
                                            page("home"),
                                            setting("home", parent, "旧入口", true, true)));
                    String appId = app.application().id();
                    Long menuId = owned(appId).getFirst().id();
                    Detail changed =
                            save(
                                    app,
                                    "app",
                                    object,
                                    page("next"),
                                    setting("next", parent, "新入口", true, true));
                    writeFailure.failAfter(
                            "UPDATE public.nocode_application SET published_version");
                    try {
                        TransactionTemplate nested = new TransactionTemplate(manager);
                        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
                        assertThatThrownBy(() -> nested.execute(status -> publish(changed)))
                                .hasStackTraceContaining("intentional failure");
                    } finally {
                        writeFailure.clear();
                    }
                    assertThat(applications.get(appId).application().publishedVersion())
                            .isEqualTo(1);
                    assertThat(owned(appId)).hasSize(1);
                    assertThat(owned(appId).getFirst().id()).isEqualTo(menuId);
                    assertThat(owned(appId).getFirst().name()).isEqualTo("旧入口");
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT count(*) FROM public.nocode_application_version"
                                                    + " WHERE application_id=?",
                                            Integer.class,
                                            Long.valueOf(appId)))
                            .isEqualTo(1);
                });
    }

    @Test
    void twoApplicationsWithTheSamePageIdentityStaySeparateAndInvalidParentIsRejected() {
        rollback(
                () -> {
                    Long firstParent = directory("采购");
                    Long secondParent = directory("销售");
                    ObjectReference object = reference();
                    Detail first =
                            publish(
                                    save(
                                            null,
                                            "app1",
                                            object,
                                            page("home"),
                                            setting("home", firstParent, "工作台", true, true)));
                    Detail second =
                            publish(
                                    save(
                                            null,
                                            "app2",
                                            object,
                                            page("home"),
                                            setting("home", secondParent, "工作台", true, true)));
                    assertThat(owned(first.application().id()).getFirst().componentName())
                            .isNotEqualTo(
                                    owned(second.application().id()).getFirst().componentName());
                    Detail invalid =
                            save(
                                    first,
                                    "app1",
                                    object,
                                    page("home"),
                                    setting(
                                            "home",
                                            owned(second.application().id()).getFirst().id(),
                                            "工作台",
                                            true,
                                            true));
                    expectPublishFailure(invalid, "所属一级目录");
                    assertThat(owned(first.application().id()).getFirst().parentId())
                            .isEqualTo(firstParent);
                });
    }

    private void expectPublishFailure(Detail app, String message) {
        TransactionTemplate nested = new TransactionTemplate(manager);
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        assertThatThrownBy(() -> nested.execute(status -> publish(app)))
                .hasMessageContaining(message);
    }

    private void allow(boolean allowed) {
        for (String action : List.of("create", "update", "delete"))
            when(permissions.hasAnyPermissions(ACTOR, "system:menu:" + action)).thenReturn(allowed);
    }

    private Long directory(String label) {
        return menus.create(
                new MenuApi.Menu(
                        null,
                        fixture.prefix + label,
                        "",
                        1,
                        0,
                        0L,
                        "/" + fixture.prefix + label,
                        "",
                        "",
                        "",
                        0,
                        true,
                        false,
                        true));
    }

    private List<MenuApi.Menu> owned(String appId) {
        return menus.list().stream()
                .filter(
                        menu ->
                                menu.componentName() != null
                                        && menu.componentName()
                                                .startsWith("NocodePage_" + appId + "_"))
                .toList();
    }

    private Resource page(String id) {
        return resource(id, "PAGE", new ApplicationUi.Page(List.of()));
    }

    private Resource setting(String target, Long parent, String name, boolean shown, boolean home) {
        return resource(
                "nav_" + target,
                "MENU",
                new ApplicationUi.Menu(
                        target,
                        2,
                        shown,
                        parent == null ? null : parent.toString(),
                        name,
                        "HomeOutlined",
                        10,
                        home));
    }

    private Resource resource(String id, String kind, Object config) {
        return new Resource(
                id,
                kind,
                id,
                id,
                mapper.convertValue(config, new TypeReference<Map<String, Object>>() {}));
    }

    private Detail save(
            Detail current, String suffix, ObjectReference object, Resource... resources) {
        return applications.save(
                new Save(
                        current == null ? null : current.application().id(),
                        current == null ? null : current.application().revision(),
                        fixture.prefix + suffix,
                        "页面导航验证",
                        "事务回滚夹具",
                        "AppstoreOutlined",
                        new Definition(List.of(object), List.of(resources))),
                ACTOR);
    }

    private Detail publish(Detail app) {
        return applications.publish(
                new Revision(app.application().id(), app.application().revision(), "页面导航测试"),
                ACTOR);
    }

    private ObjectReference reference() {
        DataCenter.Design design =
                designs.save(
                        new DataCenter.SaveDesign(
                                fixture.createRequest("navobject"),
                                DataCenter.Settings.defaults(),
                                Map.of(),
                                List.of(),
                                List.of(),
                                List.of()),
                        ACTOR);
        DataCenter.PublishPlan plan =
                publisher.plan(
                        new DataCenter.Revision(
                                design.draft().id(), design.draft().lockVersion(), null),
                        ACTOR);
        assertThat(plan.checks()).noneMatch(DataCenter.Check::blocking);
        assertThat(publisher.execute(new DataCenter.ExecutePlan(plan.id(), "导航夹具"), ACTOR).state())
                .isEqualTo("SUCCEEDED");
        DataObjectApi.PublishedObject published =
                servicesContext.getBean(DataObjectApi.class).getVersion(design.draft().id(), null);
        return new ObjectReference(
                published.objectId(), published.versionNo(), published.checksum());
    }

    private void rollback(Runnable action) {
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            try {
                                servicesContext
                                        .getBean(ApplicationAutomationCatalog.class)
                                        .lock(true);
                                action.run();
                            } finally {
                                status.setRollbackOnly();
                            }
                        });
    }
}
