package com.richuang.os.nocode.tools;

import com.baomidou.dynamic.datasource.spring.boot.autoconfigure.DynamicDataSourceAutoConfiguration;

import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;

/**
 * 工具的数据库装配：数据源、事务管理器与 JdbcTemplate，配置来自启动模块的 application.yml 及其激活的配置。
 *
 * <p>单独作为启动源时不注册 {@link NocodeDatabaseTool}，因此不需要 Flyway；只需数据库的工具或测试用它（对象规则迁移的 dry-run 自 2026-09-29
 * 起改用正式业务装配判断空草稿）。
 *
 * <p>不标 {@code @Configuration}：工具 jar 经 {@code loader.path} 挂到正式包上运行时，启动类按 {@code com.richuang.os}
 * 扫描组件，标了就会被扫进完整服务的上下文。作为启动源或被 {@code @Import} 时仍按配置类处理。
 */
@ImportAutoConfiguration({
    DynamicDataSourceAutoConfiguration.class,
    DataSourceTransactionManagerAutoConfiguration.class,
    JdbcTemplateAutoConfiguration.class
})
class NocodeToolDatabase {}
