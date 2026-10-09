package com.richuang.os.module.system.legacy.config;

import com.richuang.os.module.system.legacy.entity.SysMenu;
import com.richuang.os.module.system.legacy.mapper.SysMenuMapper;
import com.richuang.os.module.system.legacy.mapper.SysRoleMenuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜单数据初始化器（已废弃 - v2.0 统一认证迁移）
 * 后台侧边栏 sys_menu
 * 遵循现有菜单规范：顶级为分组(menuType=0, 无component)，子级为可点击路由(component非空)
 *
 * @Order(1) 确保在其他初始化器之前执行
 */
@Slf4j
// @Component -- 已废弃，新版 MenuService 处理菜单逻辑
@RequiredArgsConstructor
@Order(1)
public class MenuDataInitializer implements CommandLineRunner {

    private static final String ADMIN_ROLE_ID = "1";
    private final SysMenuMapper sysMenuMapper;
    private final SysRoleMenuMapper sysRoleMenuMapper;

    @Override
    public void run(String... args) {
        log.info("[菜单初始化] 开始检查并修正菜单数据...");

        // ===== 工作台 =====
        fixTopGroup("200", "工作台", "/workspace", "DesktopOutlined", 0);
        ensureChildMenu("2001", "工作台", "/workspace", "workspace/index", "DesktopOutlined", "200", 0);
        // 顶级分组也需要角色关联（selectMenusByUserId 用 INNER JOIN sys_role_menu）
        ensureRoleMenu("200");
        ensureRoleMenu("2001");

        // ===== 项目（顶级分组）=====
        fixTopGroup("201", "项目", "/project", "ProjectOutlined", 7);
        // 子菜单1：全部项目
        ensureChildMenu("2011", "全部项目", "/project/list", "project/list/index", "AppstoreOutlined", "201", 0);
        // 子菜单2：我负责的
        ensureChildMenu("2012", "我负责的", "/project/list?tab=managed", "project/list/index", "UserOutlined", "201", 1);
        // 子菜单3：我参与的
        ensureChildMenu("2013", "我参与的", "/project/list?tab=my", "project/list/index", "TeamOutlined", "201", 2);

        // 绑定角色权限（顶级分组也需要，否则菜单树查不到）
        ensureRoleMenu("201");
        ensureRoleMenu("2011");
        ensureRoleMenu("2012");
        ensureRoleMenu("2013");

        // 清理旧版可能存在的残存角色关联
        cleanRoleMenuIfExists("200");
        cleanRoleMenuIfExists("201");

        log.info("[菜单初始化] 菜单数据检查完成");
    }

    /**
     * 确保顶级分组菜单存在并正确
     */
    private void fixTopGroup(String id, String name, String path, String icon, int sort) {
        SysMenu existing = sysMenuMapper.selectById(id);
        if (existing == null) {
            SysMenu menu = new SysMenu();
            menu.setId(id);
            menu.setName(name);
            menu.setPath(path);
            menu.setComponent(null);
            menu.setIcon(icon);
            menu.setParentId("0");
            menu.setSort(sort);
            menu.setStatus(1);
            menu.setMenuType(0);  // 分组
            menu.setCreateTime(LocalDateTime.now());
            menu.setDeleted(0);
            sysMenuMapper.insert(menu);
            log.info("[菜单初始化] 创建顶级分组: id={}, name={}", id, name);
        } else {
            boolean needUpdate = false;
            if (existing.getMenuType() == null || existing.getMenuType() != 0) {
                existing.setMenuType(0);
                needUpdate = true;
            }
            if (existing.getComponent() != null && !existing.getComponent().isEmpty()) {
                existing.setComponent(null);
                needUpdate = true;
            }
            if (needUpdate) {
                existing.setUpdateTime(LocalDateTime.now());
                sysMenuMapper.updateById(existing);
                log.info("[菜单初始化] 修正为分组: id={}", id);
            } else {
                log.info("[菜单初始化] 顶级分组已正确: id={}", id);
            }
        }
    }

    /**
     * 确保子菜单存在
     */
    private void ensureChildMenu(String id, String name, String path, String component,
                                 String icon, String parentId, int sort) {
        SysMenu existing = sysMenuMapper.selectById(id);
        if (existing == null) {
            SysMenu menu = new SysMenu();
            menu.setId(id);
            menu.setName(name);
            menu.setPath(path);
            menu.setComponent(component);
            menu.setIcon(icon);
            menu.setParentId(parentId);
            menu.setSort(sort);
            menu.setStatus(1);
            menu.setMenuType(1);
            menu.setCreateTime(LocalDateTime.now());
            menu.setDeleted(0);
            sysMenuMapper.insert(menu);
            log.info("[菜单初始化] 插入子菜单: id={}, name={}", id, name);
        } else if (!name.equals(existing.getName())) {
            // 如果名称不一致则更新
            existing.setName(name);
            existing.setUpdateTime(LocalDateTime.now());
            sysMenuMapper.updateById(existing);
            log.info("[菜单初始化] 更新子菜单名称: id={}, name={}", id, name);
        }
    }

    private void ensureRoleMenu(String menuId) {
        List<String> menuIds = sysRoleMenuMapper.selectMenuIdsByRoleId(ADMIN_ROLE_ID);
        if (menuIds != null && menuIds.contains(menuId)) return;
        sysRoleMenuMapper.insertRoleMenu(ADMIN_ROLE_ID, menuId);
        log.info("[菜单初始化] 绑定角色-菜单: role={}, menu={}", ADMIN_ROLE_ID, menuId);
    }

    private void cleanRoleMenuIfExists(String menuId) {
        // 无需操作，角色只需关联子菜单
    }
}
