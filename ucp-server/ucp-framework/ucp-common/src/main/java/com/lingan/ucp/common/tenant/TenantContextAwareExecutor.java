package com.lingan.ucp.common.tenant;

import com.alibaba.ttl.TtlRunnable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 租户上下文感知的执行器包装器
 * <p>
 * 实现原理：
 * 1. 在任务提交时捕获当前线程的 TenantContext（租户ID）
 * 2. 在任务执行前将租户ID设置到执行线程的 TenantContext 中
 * 3. 任务执行完成后清理执行线程的 TenantContext
 * <p>
 * 对业务编码的影响：
 * - 完全无感知，业务代码无需任何修改
 * - 所有使用 @Async 的方法自动获得租户上下文透传能力
 * - TenantMetaObjectHandler 在异步线程中也能正确填充 tenant_id
 * <p>
 * 适用场景：
 * - 文档异步处理（@Async("documentTaskExecutor")）
 * - 上传异步处理（@Async("taskExecutor")）
 * - 消息异步发送（@Async("messageTaskExecutor")）
 *
 * @author CodeGen
 * @since 1.0.0
 */
@Slf4j
public class TenantContextAwareExecutor implements Executor, AutoCloseable {

    /**
     * 被包装的目标执行器
     */
    private final Executor delegate;

    /**
     * 执行器名称，用于日志标识
     */
    private final String executorName;

    public TenantContextAwareExecutor(Executor delegate, String executorName) {
        this.delegate = delegate;
        this.executorName = executorName;
        log.info("[TenantContextAware] 初始化租户上下文感知执行器: {}", executorName);
    }

    /**
     * 创建支持租户上下文透传的 ThreadPoolTaskExecutor
     * <p>
     * 使用 Spring 的 TaskDecorator 机制，在任务执行前后自动处理 TenantContext
     *
     * @param corePoolSize     核心线程数
     * @param maxPoolSize      最大线程数
     * @param queueCapacity    队列容量
     * @param threadNamePrefix 线程名前缀
     * @param executorName     执行器名称（用于日志）
     * @return 配置好的 ThreadPoolTaskExecutor
     */
    public static ThreadPoolTaskExecutor createTaskExecutor(
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            String threadNamePrefix,
            String executorName) {

        log.info("[TenantContextAware] 创建租户上下文感知线程池: {}, 核心线程数: {}, 最大线程数: {}",
                executorName, corePoolSize, maxPoolSize);

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);

        // 配置 TaskDecorator，在任务执行前后处理 TenantContext
        executor.setTaskDecorator(new TenantContextTaskDecorator(executorName));

        // 拒绝策略：由调用线程处理
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

        // 等待所有任务完成后再关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        log.info("[TenantContextAware] 线程池初始化完成: {}", executorName);
        return executor;
    }

    /**
     * 提交任务到线程池
     * <p>
     * 执行流程：
     * 1. 捕获当前线程的 tenantId
     * 2. 创建包装后的 Runnable，在执行前后设置/清理 tenantId
     * 3. 提交到目标执行器
     *
     * @param command 要执行的任务
     */
    @Override
    public void execute(Runnable command) {
        // 捕获当前线程的租户ID
        String tenantId = TenantContext.getTenantId();
        String sourceThread = Thread.currentThread().getName();

        if (log.isDebugEnabled()) {
            log.debug("[TenantContextAware] [{}] 提交任务 - 源线程: {}, tenantId: {}",
                    executorName, sourceThread, tenantId);
        }

        // 创建包装后的任务
        Runnable wrappedCommand = () -> {
            String targetThread = Thread.currentThread().getName();
            try {
                // 在执行线程中设置租户ID
                if (tenantId != null) {
                    TenantContext.setTenantId(tenantId);
                    if (log.isDebugEnabled()) {
                        log.debug("[TenantContextAware] [{}] 设置租户上下文 - 目标线程: {}, tenantId: {}",
                                executorName, targetThread, tenantId);
                    }
                }

                // 执行实际任务
                command.run();

            } finally {
                // 清理租户上下文，防止线程复用导致的数据污染
                if (TenantContext.hasTenant()) {
                    if (log.isDebugEnabled()) {
                        log.debug("[TenantContextAware] [{}] 清理租户上下文 - 目标线程: {}, tenantId: {}",
                                executorName, targetThread, TenantContext.getTenantId());
                    }
                    TenantContext.clear();
                }
            }
        };

        // 提交到目标执行器
        delegate.execute(TtlRunnable.get(wrappedCommand));
    }

    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        } else if (delegate instanceof java.util.concurrent.ExecutorService executorService) {
            executorService.shutdown();
        }
    }

    /**
     * 租户上下文任务装饰器
     * 实现 TaskDecorator 接口，在任务执行前后自动处理 TenantContext
     */
    @Slf4j
    private static class TenantContextTaskDecorator implements TaskDecorator {

        private final String executorName;

        public TenantContextTaskDecorator(String executorName) {
            this.executorName = executorName;
        }

        @Override
        public Runnable decorate(Runnable runnable) {
            // 捕获当前线程的租户ID
            String tenantId = TenantContext.getTenantId();
            String sourceThread = Thread.currentThread().getName();

            if (log.isDebugEnabled()) {
                log.debug("[TenantContextAware] [{}] TaskDecorator 捕获上下文 - 源线程: {}, tenantId: {}",
                        executorName, sourceThread, tenantId);
            }

            return () -> {
                String targetThread = Thread.currentThread().getName();
                try {
                    // 在执行线程中设置租户ID
                    if (tenantId != null) {
                        TenantContext.setTenantId(tenantId);
                        if (log.isDebugEnabled()) {
                            log.debug("[TenantContextAware] [{}] 设置租户上下文 - 目标线程: {}, tenantId: {}",
                                    executorName, targetThread, tenantId);
                        }
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("[TenantContextAware] [{}] 无租户上下文 - 目标线程: {}",
                                    executorName, targetThread);
                        }
                    }

                    // 执行实际任务
                    runnable.run();

                } finally {
                    // 清理租户上下文
                    if (TenantContext.hasTenant()) {
                        if (log.isDebugEnabled()) {
                            log.debug("[TenantContextAware] [{}] 清理租户上下文 - 目标线程: {}, tenantId: {}",
                                    executorName, targetThread, TenantContext.getTenantId());
                        }
                        TenantContext.clear();
                    }
                }
            };
        }
    }
}
