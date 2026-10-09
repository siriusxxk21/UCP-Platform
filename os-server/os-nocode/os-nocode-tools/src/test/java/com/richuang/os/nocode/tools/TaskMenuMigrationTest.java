package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.richuang.os.module.system.controller.admin.auth.vo.AuthPermissionInfoRespVO;
import com.richuang.os.module.system.convert.auth.AuthConvert;
import com.richuang.os.module.system.dal.dataobject.permission.MenuDO;
import com.richuang.os.module.system.dal.dataobject.user.AdminUserDO;
import com.richuang.os.module.system.service.permission.MenuServiceImpl;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** 正式迁移仅改写到会话临时表；验证菜单收敛不能丢发起权或扩大已有权限集合。 */
class TaskMenuMigrationTest {
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static String migration;

    @BeforeAll
    static void open() throws Exception {
        migration =
                new ClassPathResource("db/nocode/V052__task_center_navigation.sql")
                        .getContentAsString(StandardCharsets.UTF_8)
                        .replace("public.system_role_menu_seq", "pg_temp.task_menu_role_seq")
                        .replace("public.system_role_menu", "pg_temp.task_menu_roles")
                        .replace("public.system_menu", "pg_temp.task_menus");
        // 后续迁移若增加其他持久化对象，先阻止测试误触开发库，再显式扩展临时夹具。
        assertThat(migration).doesNotContain("public.");
        context = NocodeToolContext.open();
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void close() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void keepsExistingPermissionsAndAddsOnlySafeNavigationIdempotently() {
        fixture(
                ignored -> {
                    Map<Long, AuthPermissionInfoRespVO> before = new LinkedHashMap<>();
                    for (long role : List.of(100L, 200L, 300L, 400L, 500L, 600L, 700L, 800L)) {
                        before.put(role, permissions(role));
                    }
                    List<String> untouchedMenus = snapshots("task_menus", "id<>4");
                    List<String> originalGrants = snapshots("task_menu_roles", "id<1000");
                    Map<String, Object> originalLaunch =
                            jdbc.queryForMap("SELECT * FROM pg_temp.task_menus WHERE id=4");

                    jdbc.execute(migration);

                    assertThat(snapshots("task_menus", "id<>4")).isEqualTo(untouchedMenus);
                    assertThat(snapshots("task_menu_roles", "id<1000")).isEqualTo(originalGrants);
                    Map<String, Object> launch =
                            jdbc.queryForMap("SELECT * FROM pg_temp.task_menus WHERE id=4");
                    assertThat(launch)
                            .containsEntry("parent_id", 2L)
                            .containsEntry("permission", "nocode:task:create")
                            .containsEntry("status", 0)
                            .containsEntry("path", "/nocode-app/task-center/launch")
                            .containsEntry("type", 3)
                            .containsEntry("visible", false)
                            .containsEntry("updater", "task-navigation-migration");
                    for (String changed : List.of("type", "visible", "updater", "update_time")) {
                        originalLaunch.remove(changed);
                        launch.remove(changed);
                    }
                    assertThat(launch).isEqualTo(originalLaunch);
                    assertThat(
                                    jdbc.queryForList(
                                            "SELECT role_id FROM pg_temp.task_menu_roles WHERE"
                                                    + " id>=1000 ORDER BY role_id",
                                            Long.class))
                            .containsExactly(100L, 400L, 800L);
                    assertThat(
                                    jdbc.queryForList(
                                            "SELECT tenant_id FROM pg_temp.task_menu_roles WHERE"
                                                    + " id>=1000 ORDER BY role_id",
                                            Long.class))
                            .containsExactly(0L, 0L, 23L);
                    for (Map.Entry<Long, AuthPermissionInfoRespVO> entry : before.entrySet()) {
                        assertThat(permissions(entry.getKey()).getPermissions())
                                .as("角色 %s 的操作权限不变", entry.getKey())
                                .isEqualTo(entry.getValue().getPermissions());
                    }
                    assertThat(taskMenuPaths(permissions(100L)))
                            .containsExactly(
                                    "/nocode-app/task-center", "/nocode-app/task-center/manage");
                    assertThat(taskMenuPaths(permissions(200L)))
                            .containsExactly(
                                    "/nocode-app/task-center",
                                    "/nocode-app/task-center/manage",
                                    "/nocode-app/task-center/templates");
                    assertThat(permissions(100L).getPermissions())
                            .contains("nocode:task:query", "nocode:task:create")
                            .doesNotContain("nocode:task:manage-all");
                    assertThat(permissions(300L).getPermissions())
                            .contains("nocode:task:create")
                            .doesNotContain("nocode:task:query", "nocode:task:manage-all");
                    // query 与 create 来自不同角色时，查询角色提供导航，不向发起角色补查询权。
                    assertThat(taskMenuPaths(permissions(300L, 400L)))
                            .contains("/nocode-app/task-center/manage");
                    assertThat(permissions(300L, 400L).getPermissions())
                            .contains("nocode:task:query", "nocode:task:create")
                            .doesNotContain("nocode:task:manage-all");
                    // 孤儿 manage-all、缺目录和禁用 query 均不因补父菜单获得额外权限。
                    assertThat(taskMenuPaths(permissions(500L)))
                            .doesNotContain("/nocode-app/task-center/manage");
                    assertThat(permissions(500L).getPermissions())
                            .doesNotContain("nocode:task:manage-all");
                    assertThat(permissions(600L).getPermissions())
                            .doesNotContain("nocode:task:query", "nocode:task:create");
                    assertThat(permissions(700L).getPermissions())
                            .doesNotContain("nocode:task:query");

                    List<String> migratedMenus = snapshots("task_menus", "true");
                    List<String> migratedGrants = snapshots("task_menu_roles", "true");
                    jdbc.execute(migration);
                    assertThat(snapshots("task_menus", "true")).isEqualTo(migratedMenus);
                    assertThat(snapshots("task_menu_roles", "true")).isEqualTo(migratedGrants);
                });
    }

    @Test
    void usesTheTopLevelTaskFolderWithoutDependingOnTheFormerDashboardParent() {
        fixture(
                ignored -> {
                    jdbc.update("DELETE FROM pg_temp.task_menus WHERE id=1");
                    jdbc.execute(migration);
                    assertThat(taskMenuPaths(permissions(100L)))
                            .containsExactly(
                                    "/nocode-app/task-center", "/nocode-app/task-center/manage");
                    assertThat(permissions(100L).getPermissions())
                            .contains("nocode:task:query", "nocode:task:create");
                });
    }

    @Test
    void respectsDisabledManagementMenuAndDoesNotGrantIt() {
        fixture(
                ignored -> {
                    jdbc.update("UPDATE pg_temp.task_menus SET status=1 WHERE id=5");
                    List<String> originalGrants = snapshots("task_menu_roles", "true");
                    jdbc.execute(migration);
                    assertThat(snapshots("task_menu_roles", "true")).isEqualTo(originalGrants);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT status FROM pg_temp.task_menus WHERE id=5",
                                            Integer.class))
                            .isEqualTo(1);
                    assertThat(permissions(100L).getPermissions())
                            .contains("nocode:task:query", "nocode:task:create");
                });
    }

    @Test
    void preservesDisabledLaunchPermission() {
        fixture(
                ignored -> {
                    jdbc.update("UPDATE pg_temp.task_menus SET status=1 WHERE id=4");
                    jdbc.execute(migration);
                    assertThat(
                                    jdbc.queryForObject(
                                            "SELECT status FROM pg_temp.task_menus WHERE id=4",
                                            Integer.class))
                            .isEqualTo(1);
                    assertThat(permissions(100L).getPermissions())
                            .contains("nocode:task:query")
                            .doesNotContain("nocode:task:create");
                });
    }

    @Test
    void rejectsUnexpectedLaunchIdentityInsteadOfModifyingAnotherMenu() {
        fixture(
                ignored -> {
                    jdbc.update(
                            "UPDATE pg_temp.task_menus SET permission='fixture:another:create'"
                                    + " WHERE id=4");
                    assertThatThrownBy(() -> jdbc.execute(migration))
                            .isInstanceOf(DataAccessException.class);
                });
    }

    private static void fixture(Consumer<JdbcTemplate> action) {
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                .executeWithoutResult(
                        status -> {
                            status.setRollbackOnly();
                            jdbc.execute(
                                    "CREATE TEMP TABLE task_menus (LIKE public.system_menu"
                                            + " INCLUDING ALL) ON COMMIT DROP");
                            jdbc.execute(
                                    "CREATE TEMP TABLE task_menu_roles (LIKE"
                                        + " public.system_role_menu INCLUDING ALL) ON COMMIT DROP");
                            jdbc.execute("CREATE TEMP SEQUENCE task_menu_role_seq START WITH 1000");
                            jdbc.update(
                                    """
INSERT INTO pg_temp.task_menus
(id,name,permission,type,sort,parent_id,path,component,component_name,
 status,visible,keep_alive,always_show,creator,updater,create_time,update_time,deleted)
VALUES
(1,'首页','',1,0,0,'/dashboard',NULL,'Dashboard',0,true,false,true,'fixture','fixture','2020-01-01','2020-01-01',0),
(2,'任务中心','',1,6,0,'/task-center',NULL,'NocodeTaskFolder',0,true,false,true,'fixture','fixture','2020-01-01','2020-01-01',0),
(3,'我的任务','nocode:task:query',2,1,2,'/nocode-app/task-center','nocode/task-center/index','NocodeTaskCenter',0,true,false,true,'fixture','fixture','2020-01-01','2020-01-01',0),
(4,'发起任务','nocode:task:create',2,3,2,'/nocode-app/task-center/launch','nocode/task-center/index','NocodeTaskLaunch',0,true,false,true,'fixture','fixture','2020-01-01','2020-01-01',0),
(5,'任务管理','nocode:task:query',2,2,2,'/nocode-app/task-center/manage','nocode/task-center/index','NocodeTaskManage',0,true,false,true,'fixture','fixture','2020-01-01','2020-01-01',0),
(6,'任务模板','nocode:task:template',2,4,2,'/nocode-app/task-center/templates','nocode/task-center/index','NocodeTaskTemplates',0,true,false,true,'fixture','fixture','2020-01-01','2020-01-01',0),
(7,'管理全部','nocode:task:manage-all',3,1,5,'',NULL,NULL,0,true,false,false,'fixture','fixture','2020-01-01','2020-01-01',0),
(8,'已禁用查询','nocode:task:query',3,5,2,'',NULL,NULL,1,false,false,false,'fixture','fixture','2020-01-01','2020-01-01',0),
(9,'已删除旧发起','nocode:task:create',2,3,2,'/nocode-app/task-center/launch','nocode/task-center/index','NocodeTaskLaunch',0,true,false,true,'fixture','fixture','2020-01-01','2020-01-01',1)
""");
                            grant(100, 0, 1, 2, 3, 4);
                            grant(200, 0, 1, 2, 3, 4, 5, 6, 7);
                            grant(300, 0, 1, 2, 4);
                            grant(400, 0, 1, 2, 3);
                            grant(500, 0, 1, 2, 3, 4, 7);
                            grant(600, 0, 1, 3, 4);
                            grant(700, 0, 1, 2, 4, 8);
                            grant(800, 23, 1, 2, 3, 4);
                            action.accept(jdbc);
                        });
    }

    private static void grant(long role, long tenant, long... menus) {
        for (long menu : menus) {
            jdbc.update(
                    "INSERT INTO pg_temp.task_menu_roles"
                        + " (id,role_id,menu_id,tenant_id,creator,updater,create_time,update_time,deleted)"
                        + " VALUES (?,?,?,?, 'fixture','fixture','2020-01-01','2020-01-01',0)",
                    role + menu,
                    role,
                    menu,
                    tenant);
        }
    }

    private static AuthPermissionInfoRespVO permissions(Long... roles) {
        List<MenuDO> menus =
                jdbc.query(
                        "SELECT DISTINCT m.* FROM pg_temp.task_menus m"
                                + " JOIN pg_temp.task_menu_roles r ON r.menu_id=m.id"
                                + " WHERE r.deleted=0 AND m.deleted=0 AND r.role_id IN ("
                                + String.join(",", java.util.Collections.nCopies(roles.length, "?"))
                                + ") ORDER BY m.id",
                        (row, index) -> {
                            MenuDO menu = new MenuDO();
                            menu.setId(row.getLong("id"));
                            menu.setParentId(row.getLong("parent_id"));
                            menu.setName(row.getString("name"));
                            menu.setPermission(row.getString("permission"));
                            menu.setPath(row.getString("path"));
                            menu.setType(row.getInt("type"));
                            menu.setSort(row.getInt("sort"));
                            menu.setStatus(row.getInt("status"));
                            menu.setVisible(row.getBoolean("visible"));
                            return menu;
                        },
                        (Object[]) roles);
        List<MenuDO> enabled = new MenuServiceImpl().filterDisableMenus(menus);
        return AuthConvert.INSTANCE.convert(new AdminUserDO(), List.of(), enabled);
    }

    private static List<String> taskMenuPaths(AuthPermissionInfoRespVO info) {
        List<String> paths = new ArrayList<>();
        collectPaths(info.getMenus(), paths);
        return paths.stream().filter(path -> path.startsWith("/nocode-app/task-center")).toList();
    }

    private static void collectPaths(
            List<AuthPermissionInfoRespVO.MenuVO> menus, List<String> paths) {
        if (menus == null) {
            return;
        }
        for (AuthPermissionInfoRespVO.MenuVO menu : menus) {
            paths.add(menu.getPath());
            collectPaths(menu.getChildren(), paths);
        }
    }

    private static List<String> snapshots(String table, String condition) {
        return jdbc.queryForList(
                "SELECT to_jsonb(snapshot_row)::text FROM pg_temp."
                        + table
                        + " snapshot_row WHERE "
                        + condition
                        + " ORDER BY id",
                String.class);
    }
}
