package com.richuang.os.nocode.tools;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;

import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import javax.sql.DataSource;

/**
 * 工具和集成测试的数据库运行上下文。
 *
 * <p>由 Spring Boot 加载启动模块的 application.yml 及其激活的配置，复用底座的数据源和事务装配。 仅装配数据库所需组件，不启动
 * Web、定时任务或自动迁移；调用方负责关闭上下文和连接池。
 *
 * <p>不标 {@code @Configuration}（原因见 {@link NocodeToolDatabase}）；作为启动源时按配置类处理，行为不变。
 */
@Import(NocodeToolDatabase.class)
public class NocodeToolContext {

    @Bean
    public NocodeDatabaseTool nocodeDatabaseTool(DataSource dataSource) {
        requireSingleDatabase(dataSource);
        return new NocodeDatabaseTool();
    }

    /** 使用与正式启动相同的配置入口，不接受独立数据库配置路径。 */
    public static ConfigurableApplicationContext open() {
        return application(NocodeToolContext.class).run();
    }

    /** 工具共用的启动方式：不启动 Web，不打印横幅，不注册关闭钩子（调用方关闭）。 */
    static SpringApplicationBuilder application(Class<?> source) {
        return new SpringApplicationBuilder(source)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .logStartupInfo(false)
                .registerShutdownHook(false);
    }

    static void requireSingleDatabase(DataSource dataSource) {
        if (dataSource instanceof DynamicRoutingDataSource routing
                && routing.getDataSources().size() != 1) {
            throw new IllegalStateException("本工程必须仅配置一个数据库");
        }
    }
}
