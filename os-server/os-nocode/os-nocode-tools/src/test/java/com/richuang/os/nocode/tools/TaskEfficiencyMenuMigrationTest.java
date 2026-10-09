package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;

/** 只在事务临时表执行菜单迁移，验证已有管理能力的交集及幂等，不修改开发菜单。 */
class TaskEfficiencyMenuMigrationTest {
    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;
    private static String migration;

    @BeforeAll
    static void open() throws Exception {
        migration =
                new ClassPathResource("db/nocode/V078__task_efficiency_menu.sql")
                        .getContentAsString(StandardCharsets.UTF_8)
                        .replace("public.system_role_menu_seq", "pg_temp.eff_role_seq")
                        .replace("public.system_menu_seq", "pg_temp.eff_menu_seq")
                        .replace("public.system_role_menu", "pg_temp.eff_roles")
                        .replace("public.system_menu", "pg_temp.eff_menus");
        assertThat(migration).doesNotContain("public.");
        context = NocodeToolContext.open();
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
    }

    @Test
    void registersOnlyForEffectiveManagersWithoutAddingCapabilities() {
        new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
                .executeWithoutResult(
                        status -> {
                            status.setRollbackOnly();
                            jdbc.execute("CREATE TEMP SEQUENCE eff_menu_seq START 1000");
                            jdbc.execute("CREATE TEMP SEQUENCE eff_role_seq START 1000");
                            jdbc.execute(
                                    "CREATE TEMP TABLE eff_menus (LIKE public.system_menu INCLUDING"
                                            + " DEFAULTS) ON COMMIT DROP");
                            jdbc.execute(
                                    "CREATE TEMP TABLE eff_roles (LIKE public.system_role_menu"
                                            + " INCLUDING DEFAULTS) ON COMMIT DROP");
                            jdbc.update(
                                    "INSERT INTO"
                                        + " pg_temp.eff_menus(id,name,permission,type,sort,parent_id,path,component_name,status,visible,keep_alive,always_show,creator,updater,deleted)"
                                        + " VALUES(1,'任务中心','',1,1,0,'/task-center','NocodeTaskFolder',0,true,false,true,'fixture','fixture',0)");
                            menu(2, 1, "nocode:task:query", 0);
                            menu(3, 1, "nocode:task:create", 0);
                            menu(4, 1, "", 0);
                            menu(5, 4, "nocode:task:manage-all", 0);
                            menu(6, 1, "nocode:task:create", 1);
                            for (long role = 100; role <= 500; role += 100) {
                                grant(role, 1);
                                grant(role, 2);
                            }
                            grant(100, 3);
                            grant(300, 4);
                            grant(300, 5);
                            grant(400, 6);
                            grant(500, 5);
                            jdbc.execute(migration);
                            long menuId =
                                    jdbc.queryForObject(
                                            "SELECT id FROM pg_temp.eff_menus WHERE"
                                                    + " component_name='NocodeTaskEfficiency'",
                                            Long.class);
                            assertThat(
                                            jdbc.queryForList(
                                                    "SELECT role_id FROM pg_temp.eff_roles WHERE"
                                                            + " menu_id=? ORDER BY role_id",
                                                    Long.class,
                                                    menuId))
                                    .containsExactly(100L, 300L);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT permission FROM pg_temp.eff_menus WHERE"
                                                            + " id=?",
                                                    String.class,
                                                    menuId))
                                    .isEqualTo("nocode:task:query");
                            jdbc.execute(migration);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM pg_temp.eff_roles WHERE"
                                                            + " menu_id=?",
                                                    Integer.class,
                                                    menuId))
                                    .isEqualTo(2);
                        });
    }

    private void menu(long id, long parent, String permission, int state) {
        jdbc.update(
                "INSERT INTO"
                    + " pg_temp.eff_menus(id,name,permission,type,sort,parent_id,path,status,visible,keep_alive,always_show,creator,updater,deleted)"
                    + " VALUES(?,'fixture',?,3,1,?,'',?,true,false,true,'fixture','fixture',0)",
                id,
                permission,
                parent,
                state);
    }

    private void grant(long role, long menu) {
        jdbc.update(
                "INSERT INTO"
                        + " pg_temp.eff_roles(id,role_id,menu_id,creator,updater,deleted,tenant_id)"
                        + " VALUES(nextval('pg_temp.eff_role_seq'),?,?,'fixture','fixture',0,0)",
                role,
                menu);
    }
}
