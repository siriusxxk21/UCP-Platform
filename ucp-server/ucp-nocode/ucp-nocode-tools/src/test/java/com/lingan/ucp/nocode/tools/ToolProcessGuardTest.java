package com.lingan.ucp.nocode.tools;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 工具进程守卫（设计稿 9.5）：用一个不连库的小装配复现冒烟里出现过的几类后台作业—— {@code @EnableScheduling} 定时任务（线程名
 * scheduling-）、兼做业务服务的启动任务、lambda 启动任务、final 类启动任务、CommandLineRunner。
 * 对照用例不加守卫启动同一装配，证明这些断言在守卫失效时会变红。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ToolProcessGuardTest {
    static final AtomicInteger RUNS = new AtomicInteger();
    static final AtomicInteger TICKS = new AtomicInteger();

    @BeforeEach
    void reset() {
        RUNS.set(0);
        TICKS.set(0);
    }

    @Test
    @Order(1)
    void guardedContextRunsNoBackgroundJobs() {
        var key = "flowable.async-executor-activate";
        var previous = System.getProperty(key);
        System.setProperty(key, "true");
        try (var context = ToolProcessGuard.start(NocodeToolContext.application(Sample.class))) {
            assertThat(RUNS).hasValue(0);
            assertThat(context.getBean(Consumer.class).call()).isEqualTo("ready");
            assertThat(context.getBean(MessageService.class)).isInstanceOf(MessageService.class);
            var factory = context.getBeanFactory();
            var runners = new java.util.TreeSet<String>();
            runners.addAll(Arrays.asList(factory.getBeanNamesForType(ApplicationRunner.class)));
            runners.addAll(Arrays.asList(factory.getBeanNamesForType(CommandLineRunner.class)));
            assertThat(runners)
                    .containsExactly(
                            "commandLineRunner", "finalRunner", "lambdaRunner", "messageService");
            assertThat(context.getBean(ToolProcessGuard.RunnerGuard.class).skipped()).isEqualTo(4);
            assertThat(scheduledTasks(context)).isZero();
            assertThat(ToolProcessGuard.backgroundThreads()).isEmpty();
            ToolProcessGuard.FORCED.forEach(
                    (name, value) ->
                            assertThat(context.getEnvironment().getProperty(name))
                                    .as(name)
                                    .isEqualTo(value));
            // 自检按实例计数：没挂进容器的守卫一个也没拦到，必须报出来
            assertThatThrownBy(() -> new ToolProcessGuard().verify(context))
                    .hasMessageContaining("启动任务 4 个，只拦截到 0 个");
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
        assertThat(TICKS).hasValue(0);
    }

    /** 对照：不加守卫时，同一装配会执行启动任务、登记定时任务、出现 scheduling- 线程，自检全部报出来。 */
    @Test
    @Order(2)
    void unguardedContextIsCaughtBySelfCheck() {
        try (var context = NocodeToolContext.application(Sample.class).run()) {
            assertThat(RUNS).hasValue(4);
            assertThat(scheduledTasks(context)).isEqualTo(1);
            assertThat(ToolProcessGuard.backgroundThreads())
                    .anyMatch(n -> n.startsWith("scheduling-"));
            assertThatThrownBy(() -> new ToolProcessGuard().verify(context))
                    .hasMessageContaining("定时任务处理器仍在容器中")
                    .hasMessageContaining("已调度的任务")
                    .hasMessageContaining("只拦截到 0 个")
                    .hasMessageContaining("后台作业线程");
        }
    }

    private static int scheduledTasks(org.springframework.context.ApplicationContext context) {
        return context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .mapToInt(h -> h.getScheduledTasks().size())
                .sum();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Sample {
        /** 与 Spring Boot 默认调度器同名的线程前缀。 */
        @Bean(destroyMethod = "shutdown")
        ThreadPoolTaskScheduler taskScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setThreadNamePrefix("scheduling-");
            scheduler.setWaitForTasksToCompleteOnShutdown(false);
            scheduler.setAwaitTerminationSeconds(5);
            return scheduler;
        }

        @Bean
        Ticker ticker() {
            return new Ticker();
        }

        @Bean
        MessageService messageService() {
            return new MessageService();
        }

        @Bean
        Consumer consumer(MessageService messages) {
            return new Consumer(messages);
        }

        @Bean
        ApplicationRunner lambdaRunner() {
            return args -> RUNS.incrementAndGet();
        }

        @Bean
        ApplicationRunner finalRunner() {
            return new FinalRunner();
        }

        @Bean
        CommandLineRunner commandLineRunner() {
            return args -> RUNS.incrementAndGet();
        }
    }

    static class Ticker {
        @Scheduled(fixedDelay = 3_600_000)
        void tick() {
            TICKS.incrementAndGet();
        }
    }

    interface Greeting {
        String call();
    }

    /** 兼做业务服务的启动任务（同 MsgSendService）：按具体类注入，代理后业务方法照常可用。 */
    static class MessageService implements Greeting, ApplicationRunner {
        private final String state = "ready";

        @Override
        public String call() {
            return state;
        }

        @Override
        public void run(ApplicationArguments args) {
            RUNS.incrementAndGet();
        }
    }

    record Consumer(MessageService messages) {
        String call() {
            return messages.call();
        }
    }

    static final class FinalRunner implements ApplicationRunner {
        @Override
        public void run(ApplicationArguments args) {
            RUNS.incrementAndGet();
        }
    }
}
