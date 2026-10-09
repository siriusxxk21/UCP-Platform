package com.richuang.os.nocode.tools;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.richuang.os.nocode.api.DateTriggerRuns;
import com.richuang.os.nocode.runtime.job.application.DateTriggerScheduler;
import com.richuang.os.nocode.runtime.service.record.RecordDateTriggers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 按日期自动执行的进程内定时（不连库的小装配，运行端用替身）：开关、执行时刻配置、多实例锁（被占跳过 / 拿锁失败跳过 / 跑完释放）、
 * 失败不抛给调度、立即执行等锁超时报错；以及「当前业务日」的口径。
 */
class DateTriggerSchedulerTest {
    private AnnotationConfigApplicationContext context;

    @AfterEach
    void close() {
        if (context != null) context.close();
    }

    private void start(Map<String, Object> properties) {
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("dateTriggerTest", new HashMap<>(properties)));
        // 替身以单例登记（不经 Bean 后处理）：运行端类上的 @Resource 字段不需要真实依赖。
        var triggers = mock(RecordDateTriggers.class);
        when(triggers.run(any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new DateTriggerRuns.Result("2026-10-03", 0, 0, 0, 0));
        var lock = mock(RLock.class);
        var client = mock(RedissonClient.class);
        when(client.getLock(anyString())).thenReturn(lock);
        context.getBeanFactory().registerSingleton("triggers", triggers);
        context.getBeanFactory().registerSingleton("lock", lock);
        context.getBeanFactory().registerSingleton("redissonClient", client);
        context.register(DateTriggerScheduler.class);
        context.refresh();
    }

    private RecordDateTriggers triggers() {
        return context.getBean(RecordDateTriggers.class);
    }

    private RLock lock() {
        return context.getBean(RLock.class);
    }

    private DateTriggerScheduler scheduler() {
        return context.getBean(DateTriggerScheduler.class);
    }

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 9, 0);

    @Test
    void runsWithConfiguredTimesAndReleasesTheLock() {
        start(
                Map.of(
                        "nocode.date-trigger.run-at",
                        "01:30",
                        "nocode.date-trigger.rescan-minutes",
                        "0"));
        when(lock().tryLock()).thenReturn(true);
        assertThat(scheduler().tick(NOW)).isTrue();
        verify(triggers())
                .run(
                        eq(NOW),
                        eq(new RecordDateTriggers.Settings(LocalTime.of(1, 30), 0)),
                        isNull(),
                        isNull(),
                        eq(false));
        verify(lock()).unlock();
    }

    @Test
    void defaultsAreTenPastMidnightAndTenMinuteRescans() {
        start(Map.of());
        assertThat(scheduler().settings())
                .isEqualTo(new RecordDateTriggers.Settings(LocalTime.of(0, 10), 10));
    }

    @Test
    void skipsTheRoundWhenAnotherInstanceHoldsTheLockOrRedisFails() {
        start(Map.of());
        when(lock().tryLock()).thenReturn(false);
        assertThat(scheduler().tick(NOW)).isFalse();
        verify(triggers(), never()).run(any(), any(), any(), any(), anyBoolean());
        verify(lock(), never()).unlock();

        when(lock().tryLock()).thenThrow(new IllegalStateException("redis down"));
        assertThat(scheduler().tick(NOW)).isFalse();
        verify(triggers(), never()).run(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void staysIdleWhenSwitchedOff() {
        start(Map.of("nocode.date-trigger.enabled", "false"));
        assertThat(scheduler().tick(NOW)).isFalse();
        verify(triggers(), never()).run(any(), any(), any(), any(), anyBoolean());
        verify(lock(), never()).tryLock();
    }

    @Test
    void failuresAreLoggedNotThrownAndTheLockIsReleased() {
        start(Map.of());
        when(lock().tryLock()).thenReturn(true);
        when(triggers().run(any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("boom"));
        assertThat(scheduler().tick(NOW)).isFalse();
        verify(lock()).unlock();
    }

    @Test
    void manualRunWaitsForTheLockAndReportsBusy() throws Exception {
        start(Map.of());
        when(lock().tryLock(30, TimeUnit.SECONDS)).thenReturn(false);
        assertThatThrownBy(() -> scheduler().runNow("1", "r")).hasMessageContaining("正在运行");
        verify(triggers(), never()).run(any(), any(), any(), any(), anyBoolean());

        when(lock().tryLock(30, TimeUnit.SECONDS)).thenReturn(true);
        when(triggers().run(any(), any(), eq("1"), eq("r"), eq(true)))
                .thenReturn(new DateTriggerRuns.Result("2026-10-03", 1, 0, 0, 0));
        assertThat(scheduler().runNow("1", "r").success()).isEqualTo(1);
        verify(lock()).unlock();
    }

    @Test
    void businessDayTurnsOverAtTheRunTime() {
        LocalTime at = LocalTime.of(0, 10);
        LocalDate day = LocalDate.of(2026, 10, 3);
        assertThat(RecordDateTriggers.businessDay(day.atTime(0, 9, 59), at))
                .isEqualTo(day.minusDays(1));
        assertThat(RecordDateTriggers.businessDay(day.atTime(0, 10), at)).isEqualTo(day);
        assertThat(RecordDateTriggers.businessDay(day.atTime(23, 59), at)).isEqualTo(day);
    }
}
