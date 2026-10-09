package com.richuang.os.nocode.runtime.job.application;

import com.richuang.os.nocode.application.service.application.ApplicationFollowService;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 应用自动跟随重试的进程内兜底。
 *
 * <p>{@link ApplicationFollowRetryJob} 要靠外部调度中心（PowerJob）触发；没有部署调度中心时它永远不会被执行，「跟随待处理」的应用就只能等
 * 下一次对象发布或有人点「重试」。这里在进程内按固定间隔调同一个入口 {@code retryPending}，让默认的自动跟随不依赖人工。
 *
 * <ul>
 *   <li>PowerJob 的执行端开着（{@code powerjob.worker.enabled=true}）时不跑：那时由调度中心触发 Job，避免重复执行；
 *   <li>多实例：沿用底座定时作业的做法，用 Redisson 的锁 tryLock，拿不到就跳过这一轮（别的实例正在跑）；
 *       即使两边同时进来也不会重复跟随——每个应用的跟随本身在设计写锁内重读状态；
 *   <li>任何失败只记一条警告，下一轮照常再试，不影响进程；
 *   <li>工具进程不调度任何定时方法（ToolProcessGuard），所以存量转换等工具运行时不会触发这里。
 * </ul>
 *
 * <p>间隔 {@code nocode.follow.retry-fallback-delay}（毫秒，默认 5 分钟，上一轮结束后才开始计时）； 总开关 {@code
 * nocode.follow.retry-fallback-enabled}（默认开）。两项只在代码里给默认值，不需要写进配置文件。
 */
@Component
@Slf4j
public class ApplicationFollowRetryFallback {
    static final String LOCK_KEY = "nocode:follow:retry-fallback:lock";

    /** 与 {@link ApplicationFollowRetryJob} 相同的批量上限。 */
    private static final int BATCH_LIMIT = 50;

    @Resource private ApplicationFollowService follows;

    /** 字段名不能叫 redisson：容器里有同名的 RedissonClient，按名注入会撞上它。 */
    @Resource private ObjectProvider<RedissonClient> redissonProvider;

    @Value("${nocode.follow.retry-fallback-enabled:true}")
    private boolean enabled;

    @Value("${powerjob.worker.enabled:false}")
    private boolean powerJobWorker;

    @Scheduled(
            initialDelayString = "${nocode.follow.retry-fallback-delay:300000}",
            fixedDelayString = "${nocode.follow.retry-fallback-delay:300000}")
    public void scheduled() {
        tick();
    }

    /**
     * 跑一轮。
     *
     * @return 这一轮是否真的调了重试（被关掉、调度中心接管、别的实例持锁、执行失败都返回 false）
     */
    public boolean tick() {
        if (!enabled || powerJobWorker) return false;
        RLock lock = null;
        boolean locked = false;
        try {
            RedissonClient client = redissonProvider.getIfAvailable();
            if (client != null) {
                lock = client.getLock(LOCK_KEY);
                locked = lock.tryLock();
                if (!locked) return false;
            }
            ApplicationFollowService.Retried result = follows.retryPending(BATCH_LIMIT);
            if (result.examined() > 0)
                log.info(
                        "[tick][应用自动跟随兜底重试：处理 {} 行，跟上 {} 行]", result.examined(), result.followed());
            return true;
        } catch (RuntimeException failure) {
            log.warn("[tick][应用自动跟随兜底重试失败，下一轮再试]", failure);
            return false;
        } finally {
            if (locked) {
                try {
                    lock.unlock();
                } catch (RuntimeException failure) {
                    log.warn("[tick][应用自动跟随兜底重试释放锁失败]", failure);
                }
            }
        }
    }
}
