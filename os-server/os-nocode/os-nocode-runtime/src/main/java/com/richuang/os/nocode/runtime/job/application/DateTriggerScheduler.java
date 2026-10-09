package com.richuang.os.nocode.runtime.job.application;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.nocode.api.DateTriggerRuns;
import com.richuang.os.nocode.metadata.service.formula.FormulaDates;
import com.richuang.os.nocode.runtime.service.record.RecordDateTriggers;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.TimeUnit;

/**
 * 业务动作「按日期自动执行」的进程内定时（线上没有 PowerJob，不另登记调度中心任务）。
 *
 * <ul>
 *   <li>每分钟 tick 一次：每天 {@code nocode.date-trigger.run-at}（默认 00:10，按系统「今天」的时区 Asia/Shanghai）之后处理当天，
 *       之后每 {@code nocode.date-trigger.rescan-minutes}（默认 10，0 = 不重扫）分钟重扫当天，补上当天新录入的记录；停机漏掉的日期在下一次
 *       tick 补跑；
 *   <li>多实例：Redisson 锁 tryLock，拿不到（别的实例正在跑）或拿锁失败（Redis 不通）就跳过这一轮；即使锁失效两边同时进来，
 *       同一条来源记录也只会被一个事务处理（账本的唯一键与写目标同事务提交）；
 *   <li>任何失败只记警告，下一轮照常再试，不影响进程；工具进程不调度任何定时方法（ToolProcessGuard）。
 * </ul>
 *
 * <p>总开关 {@code nocode.date-trigger.enabled}（默认开）。这几项只在代码里给默认值，不需要写进配置文件。
 */
@Component
@Slf4j
public class DateTriggerScheduler {
    static final String LOCK_KEY = "nocode:date-trigger:lock";

    /** 「立即按今天执行」等锁的最长时间。 */
    private static final long MANUAL_WAIT_SECONDS = 30;

    @Resource private RecordDateTriggers triggers;

    /** 字段名不能叫 redisson：容器里有同名的 RedissonClient，按名注入会撞上它。 */
    @Resource private ObjectProvider<RedissonClient> redissonProvider;

    @Value("${nocode.date-trigger.enabled:true}")
    private boolean enabled;

    @Value("${nocode.date-trigger.run-at:00:10}")
    private String runAt;

    @Value("${nocode.date-trigger.rescan-minutes:10}")
    private int rescanMinutes;

    @Scheduled(
            initialDelayString = "${nocode.date-trigger.tick-delay:60000}",
            fixedDelayString = "${nocode.date-trigger.tick-delay:60000}")
    public void scheduled() {
        tick();
    }

    public RecordDateTriggers.Settings settings() {
        return new RecordDateTriggers.Settings(LocalTime.parse(runAt), rescanMinutes);
    }

    /** 按当前时刻跑一轮。 */
    public boolean tick() {
        return tick(LocalDateTime.now(FormulaDates.ZONE));
    }

    /**
     * 跑一轮。
     *
     * @return 这一轮是否真的执行了（被关掉、别的实例持锁、拿锁失败、执行失败都返回 false）
     */
    public boolean tick(LocalDateTime now) {
        if (!enabled) return false;
        RLock lock = null;
        boolean locked = false;
        try {
            RedissonClient client = redissonProvider.getIfAvailable();
            if (client != null) {
                lock = client.getLock(LOCK_KEY);
                locked = lock.tryLock();
                if (!locked) {
                    log.info("[tick][按日期自动执行：别的实例正在执行，这一轮跳过]");
                    return false;
                }
            }
            DateTriggerRuns.Result result = triggers.run(now, settings(), null, null, false);
            if (result.success() + result.failed() > 0)
                log.info(
                        "[tick][按日期自动执行：业务日 {} 写入 {} 条，无变化 {} 条，失败 {} 条]",
                        result.businessDate(),
                        result.success(),
                        result.unchanged(),
                        result.failed());
            return true;
        } catch (RuntimeException failure) {
            log.warn("[tick][按日期自动执行失败，下一轮再试]", failure);
            return false;
        } finally {
            unlock(lock, locked);
        }
    }

    /** 立即按今天执行一条规则：与定时共用一把锁，最多等 30 秒。 */
    public DateTriggerRuns.Result runNow(String application, String resource) {
        RLock lock = null;
        boolean locked = false;
        try {
            RedissonClient client = redissonProvider.getIfAvailable();
            if (client != null) {
                lock = client.getLock(LOCK_KEY);
                try {
                    locked = lock.tryLock(MANUAL_WAIT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                if (!locked) throw invalid("按日期自动执行正在运行，请稍后再试");
            }
            return triggers.run(
                    LocalDateTime.now(FormulaDates.ZONE), settings(), application, resource, true);
        } finally {
            unlock(lock, locked);
        }
    }

    private static void unlock(RLock lock, boolean locked) {
        if (!locked) return;
        try {
            lock.unlock();
        } catch (RuntimeException failure) {
            log.warn("[tick][按日期自动执行释放锁失败]", failure);
        }
    }
}
