package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import com.richuang.os.nocode.application.service.application.ApplicationFollowService;
import com.richuang.os.nocode.runtime.job.application.ApplicationFollowRetryFallback;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 应用自动跟随重试的进程内兜底：不连库的小装配，开着 Spring 的定时调度，跟随服务用替身。
 *
 * <p>钉三件事：没有调度中心时也会被执行；调度中心的执行端开着时兜底不跑；执行失败不影响进程（下一轮照常）。 另钉多实例互斥：别的实例持锁时这一轮跳过。
 */
class ApplicationFollowRetryFallbackTest {
    private static final String DELAY = "nocode.follow.retry-fallback-delay";
    private AnnotationConfigApplicationContext context;

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    private void start(Map<String, Object> properties, Class<?>... configurations) {
        Map<String, Object> all = new HashMap<>(Map.of(DELAY, "40"));
        all.putAll(properties);
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("fallbackTest", all));
        context.register(configurations);
        context.refresh();
    }

    private static boolean within(long millis, BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private ApplicationFollowService follows() {
        return context.getBean(ApplicationFollowService.class);
    }

    private ApplicationFollowRetryFallback fallback() {
        return context.getBean(ApplicationFollowRetryFallback.class);
    }

    /** 没有 PowerJob、没有任何人工触发：进程自己按间隔调 retryPending(50)。 */
    @Test
    void runsOnItsOwnWhenThereIsNoExternalScheduler() throws Exception {
        start(Map.of(), Plain.class);
        assertThat(within(5_000, () -> Plain.CALLS.get() >= 2)).as("定时调度自己调了至少两轮").isTrue();
        verify(follows(), atLeast(2)).retryPending(50);
        assertThat(fallback().tick()).as("手工再跑一轮也照常").isTrue();
    }

    /** PowerJob 的执行端开着：由调度中心触发 Job，兜底一轮都不跑。 */
    @Test
    void staysIdleWhenThePowerJobWorkerIsEnabled() throws Exception {
        start(Map.of("powerjob.worker.enabled", "true"), Plain.class);
        Thread.sleep(600);
        verify(follows(), never()).retryPending(anyInt());
        assertThat(fallback().tick()).isFalse();
        verify(follows(), never()).retryPending(anyInt());
    }

    /** 总开关关掉：不跑。 */
    @Test
    void staysIdleWhenSwitchedOff() throws Exception {
        start(Map.of("nocode.follow.retry-fallback-enabled", "false"), Plain.class);
        Thread.sleep(600);
        verify(follows(), never()).retryPending(anyInt());
        assertThat(fallback().tick()).isFalse();
    }

    /** 每一轮都抛异常：调度不中断（下一轮照常被调），手工调用也不把异常抛给调用方，容器照常可用。 */
    @Test
    void failureDoesNotStopTheScheduleOrTheProcess() throws Exception {
        start(Map.of(), Failing.class);
        assertThat(within(5_000, () -> Failing.CALLS.get() >= 3)).as("失败之后下一轮照常被调").isTrue();
        assertThat(fallback().tick()).as("失败只记日志，返回 false，不抛").isFalse();
        assertThat(context.isActive()).isTrue();
        assertThat(context.getBean(ApplicationFollowRetryFallback.class)).isNotNull();
    }

    /** 多实例互斥：锁被别的实例拿着 ⇒ 这一轮跳过；拿到锁 ⇒ 跑完释放。 */
    @Test
    void skipsTheRoundWhenAnotherInstanceHoldsTheLock() throws Exception {
        start(Map.of(DELAY, "3600000"), Locked.class);
        RLock lock = context.getBean(RLock.class);
        when(lock.tryLock()).thenReturn(false);
        assertThat(fallback().tick()).isFalse();
        verify(follows(), never()).retryPending(anyInt());
        verify(lock, never()).unlock();

        when(lock.tryLock()).thenReturn(true);
        assertThat(fallback().tick()).isTrue();
        verify(follows(), times(1)).retryPending(50);
        verify(lock, times(1)).unlock();

        // 拿锁本身失败（例如 Redis 连不上）：同样只是跳过这一轮。
        when(lock.tryLock()).thenThrow(new IllegalStateException("redis down"));
        assertThat(fallback().tick()).isFalse();
        verify(follows(), times(1)).retryPending(50);
        verify(lock, times(1)).unlock();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Plain {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Bean
        ApplicationFollowService follows() {
            CALLS.set(0);
            ApplicationFollowService service = mock(ApplicationFollowService.class);
            when(service.retryPending(anyInt()))
                    .thenAnswer(
                            call -> {
                                CALLS.incrementAndGet();
                                return new ApplicationFollowService.Retried(0, 0);
                            });
            return service;
        }

        @Bean
        ApplicationFollowRetryFallback fallback() {
            return new ApplicationFollowRetryFallback();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Failing {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Bean
        ApplicationFollowService follows() {
            CALLS.set(0);
            ApplicationFollowService service = mock(ApplicationFollowService.class);
            when(service.retryPending(anyInt()))
                    .thenAnswer(
                            call -> {
                                CALLS.incrementAndGet();
                                throw new IllegalStateException("database is down");
                            });
            return service;
        }

        @Bean
        ApplicationFollowRetryFallback fallback() {
            return new ApplicationFollowRetryFallback();
        }
    }

    /** 容器里有 Redisson（Bean 名与线上相同：redisson）。 */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Locked {
        @Bean
        RLock lock() {
            return mock(RLock.class);
        }

        @Bean
        RedissonClient redisson(RLock lock) {
            RedissonClient client = mock(RedissonClient.class);
            when(client.getLock("nocode:follow:retry-fallback:lock")).thenReturn(lock);
            return client;
        }

        @Bean
        ApplicationFollowService follows() {
            ApplicationFollowService service = mock(ApplicationFollowService.class);
            when(service.retryPending(anyInt()))
                    .thenReturn(new ApplicationFollowService.Retried(2, 1));
            return service;
        }

        @Bean
        ApplicationFollowRetryFallback fallback() {
            return new ApplicationFollowRetryFallback();
        }
    }
}
