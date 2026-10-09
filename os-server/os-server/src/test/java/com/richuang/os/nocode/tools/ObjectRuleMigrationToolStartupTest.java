package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import java.util.List;

/**
 * 对象规则迁移工具 apply / rollback / compare 的完整装配（正式启动类 + 懒加载）按工具模式启动后，不启动任何后台作业（设计稿 9.5）。
 *
 * <p>冒烟实跑时这些组件都出现过：Flowable 异步执行器、{@code @Scheduled} 办理请求补偿（scheduling-1）、启动横幅、 BPM 消息类型注册（启动时 4 次
 * UPDATE sys_msg_template）。本用例拉起同一套装配，从线程、调度登记、日志、库里四个方向核对。
 */
class ObjectRuleMigrationToolStartupTest {
    private static final String LIVE_DATABASE = "os-newserver0916";
    private static final String TEMPLATE_UPDATED =
            "SELECT count(*) || '/' || coalesce(max(update_time)::text, '-')"
                    + " FROM public.sys_msg_template";

    /**
     * 测试运行库由模板重建，结构导出只带了 act_ge_property；act_id_property 为空时 Flowable IDM 引擎读到默认版本 5.99.0.0
     * 拒绝启动。与冒烟种子相同，补上与引擎一致的版本行（线上库本来就有）。
     */
    private static final String IDM_VERSION =
            "INSERT INTO public.act_id_property(name_, value_, rev_)"
                    + " SELECT 'schema.version', value_, 1 FROM public.act_ge_property"
                    + " WHERE name_ = 'schema.version' AND NOT EXISTS"
                    + " (SELECT 1 FROM public.act_id_property WHERE name_ = 'schema.version')";

    @Test
    void servicesContextStartsWithoutBackgroundJobs() {
        var url = System.getenv("SPRING_DATASOURCE_DYNAMIC_DATASOURCE_MASTER_URL");
        assertThat(url == null ? "" : url).doesNotContain(LIVE_DATABASE);
        String templatesBefore;
        try (var database = NocodeToolContext.application(NocodeToolDatabase.class).run()) {
            var jdbc = database.getBean(JdbcTemplate.class);
            jdbc.update(IDM_VERSION);
            templatesBefore = jdbc.queryForObject(TEMPLATE_UPDATED, String.class);
        }

        var logs = new ListAppender<ILoggingEvent>();
        try (var context =
                ToolProcessGuard.start(
                        ObjectRuleMigrationTool.servicesApplication()
                                .listeners(new Capture(logs)))) {
            var factory = context.getBeanFactory();
            // 各方向独立核对、一次报全，便于看清是哪一类后台作业漏了
            var soft = new SoftAssertions();

            // 冒烟里拉起过后台作业的组件都在这套装配里，排除「组件不存在所以没日志」的空转
            soft.assertThat(context.getBeanNamesForType(ApplicationRunner.class))
                    .contains("bannerApplicationRunner", "msgSendService", "bpmMessageServiceImpl");
            soft.assertThat(context.getBeanNamesForType(NocodeDatabaseTool.class))
                    .as("工具 jar 里的配置不得被正式启动类的组件扫描收进来")
                    .isEmpty();

            soft.assertThat(ToolProcessGuard.backgroundThreads()).as("后台作业线程").isEmpty();
            soft.assertThat(
                            factory.containsBeanDefinition(
                                    TaskManagementConfigUtils
                                            .SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                    .as("定时任务处理器")
                    .isFalse();
            for (var name : factory.getBeanNamesForType(ScheduledTaskHolder.class, true, false))
                if (factory.containsSingleton(name))
                    soft.assertThat(
                                    factory.getBean(name, ScheduledTaskHolder.class)
                                            .getScheduledTasks())
                            .as("已调度的任务")
                            .isEmpty();
            ToolProcessGuard.FORCED.forEach(
                    (key, value) ->
                            soft.assertThat(context.getEnvironment().getProperty(key))
                                    .as(key)
                                    .isEqualTo(value));

            List<ILoggingEvent> events = List.copyOf(logs.list);
            soft.assertThat(events)
                    .as("日志确实被捕获到")
                    .anyMatch(e -> e.getFormattedMessage().contains("工具进程自检通过"));
            soft.assertThat(events)
                    .as("scheduling- 线程上的日志")
                    .noneMatch(e -> e.getThreadName().startsWith("scheduling-"));
            soft.assertThat(events)
                    .as("Flowable 异步执行器启动")
                    .noneMatch(e -> e.getFormattedMessage().contains("async job executor"));
            soft.assertThat(events)
                    .as("消息类型注册")
                    .noneMatch(e -> e.getFormattedMessage().contains("MsgTypeRegist"));
            soft.assertThat(events)
                    .as("启动横幅")
                    .noneMatch(e -> e.getLoggerName().endsWith("BannerApplicationRunner"));

            soft.assertThat(
                            context.getBean(JdbcTemplate.class)
                                    .queryForObject(TEMPLATE_UPDATED, String.class))
                    .as("启动时不写消息模板")
                    .isEqualTo(templatesBefore);
            soft.assertAll();
        } finally {
            var root =
                    ((LoggerContext) LoggerFactory.getILoggerFactory())
                            .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
            root.detachAppender(logs);
            logs.stop();
        }
    }

    /** 日志系统在环境准备阶段才初始化，捕获器挂在其后、容器刷新之前。 */
    private record Capture(ListAppender<ILoggingEvent> appender)
            implements ApplicationListener<ApplicationPreparedEvent> {
        @Override
        public void onApplicationEvent(ApplicationPreparedEvent event) {
            var logging = (LoggerContext) LoggerFactory.getILoggerFactory();
            appender.setContext(logging);
            appender.start();
            logging.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender);
        }
    }
}
