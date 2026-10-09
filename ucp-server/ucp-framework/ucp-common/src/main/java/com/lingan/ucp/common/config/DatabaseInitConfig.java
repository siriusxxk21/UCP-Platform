package com.lingan.ucp.common.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.database", name = "init-enabled", havingValue = "true", matchIfMissing = true)
public class DatabaseInitConfig {

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void init() {
        try {
            // 检查数据库表是否存在
            jdbcTemplate.queryForObject("SELECT 1 FROM sys_user LIMIT 1", Integer.class);
            log.info("数据库表已存在，跳过初始化");
        } catch (Exception e) {
            log.info("开始初始化数据库...");
            initDatabase();
            log.info("数据库初始化完成");
        }
    }

    private void initDatabase() {
        // 创建用户表
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sys_user (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID'," +
                "username VARCHAR(50) NOT NULL COMMENT '用户名'," +
                "password VARCHAR(100) NOT NULL COMMENT '密码'," +
                "nickname VARCHAR(50) COMMENT '昵称'," +
                "avatar VARCHAR(255) COMMENT '头像'," +
                "email VARCHAR(100) COMMENT '邮箱'," +
                "phone VARCHAR(20) COMMENT '手机号'," +
                "status TINYINT DEFAULT 1 COMMENT '状态：0-禁用 1-启用'," +
                "create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'," +
                "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'," +
                "deleted TINYINT DEFAULT 0 COMMENT '是否删除：0-未删除 1-已删除'," +
                "UNIQUE KEY uk_username (username)" +
                ") COMMENT '用户表'");

        // 创建角色表
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sys_role (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID'," +
                "role_name VARCHAR(50) NOT NULL COMMENT '角色名称'," +
                "role_code VARCHAR(50) NOT NULL COMMENT '角色编码'," +
                "description VARCHAR(255) COMMENT '描述'," +
                "status TINYINT DEFAULT 1 COMMENT '状态：0-禁用 1-启用'," +
                "create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'," +
                "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'," +
                "deleted TINYINT DEFAULT 0 COMMENT '是否删除：0-未删除 1-已删除'," +
                "UNIQUE KEY uk_role_code (role_code)" +
                ") COMMENT '角色表'");

        // 创建菜单表
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sys_menu (" +
                "id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID'," +
                "name VARCHAR(50) NOT NULL COMMENT '菜单名称'," +
                "path VARCHAR(100) COMMENT '路由路径'," +
                "component VARCHAR(100) COMMENT '组件路径'," +
                "icon VARCHAR(50) COMMENT '图标'," +
                "parent_id BIGINT DEFAULT 0 COMMENT '父菜单ID'," +
                "sort INT DEFAULT 0 COMMENT '排序'," +
                "status TINYINT DEFAULT 1 COMMENT '状态：0-禁用 1-启用'," +
                "menu_type TINYINT DEFAULT 1 COMMENT '菜单类型：0-目录 1-菜单 2-按钮'," +
                "create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间'," +
                "update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'," +
                "deleted TINYINT DEFAULT 0 COMMENT '是否删除：0-未删除 1-已删除'" +
                ") COMMENT '菜单表'");

        // 创建用户角色关联表
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sys_user_role (" +
                "user_id BIGINT NOT NULL COMMENT '用户ID'," +
                "role_id BIGINT NOT NULL COMMENT '角色ID'," +
                "PRIMARY KEY (user_id, role_id)" +
                ") COMMENT '用户角色关联表'");

        // 创建角色菜单关联表
        jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS sys_role_menu (" +
                "role_id BIGINT NOT NULL COMMENT '角色ID'," +
                "menu_id BIGINT NOT NULL COMMENT '菜单ID'," +
                "PRIMARY KEY (role_id, menu_id)" +
                ") COMMENT '角色菜单关联表'");

        // 初始化数据
        initData();
    }

    private void initData() {
        // 插入管理员用户（密码：123456，MD5加密）
        jdbcTemplate.update("INSERT INTO sys_user (id, username, password, nickname, status) VALUES " +
                "(1, 'admin', 'e10adc3949ba59abbe56e057f20f883e', '管理员', 1)");

        // 插入角色
        jdbcTemplate.update("INSERT INTO sys_role (id, role_name, role_code, description, status) VALUES " +
                "(1, '超级管理员', 'admin', '拥有所有权限', 1)," +
                "(2, '普通用户', 'user', '普通用户权限', 1)");

        // 插入菜单（顶部一级菜单）
        jdbcTemplate.update("INSERT INTO sys_menu (id, name, path, icon, parent_id, sort, status, menu_type) VALUES " +
                "(1, '系统管理', '/system', 'SettingOutlined', 0, 1, 1, 0)");

        // 插入菜单（左侧二级菜单）
        jdbcTemplate.update("INSERT INTO sys_menu (id, name, path, component, icon, parent_id, sort, status, menu_type) VALUES " +
                "(11, '仪表盘', '/dashboard', 'system/Dashboard', 'HomeOutlined', 1, 1, 1, 1)," +
                "(12, '用户管理', '/system/user', 'system/user/index', 'UserOutlined', 1, 2, 1, 1)," +
                "(13, '角色管理', '/system/role', 'system/role/index', 'TeamOutlined', 1, 3, 1, 1)");

        // 关联用户角色
        jdbcTemplate.update("INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1)");

        // 关联角色菜单（超级管理员拥有所有菜单）
        jdbcTemplate.update("INSERT INTO sys_role_menu (role_id, menu_id) VALUES " +
                "(1, 1), (1, 11), (1, 12), (1, 13)");
    }
}
