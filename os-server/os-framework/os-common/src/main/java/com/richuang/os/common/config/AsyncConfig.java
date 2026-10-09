package com.richuang.os.common.config;

import com.richuang.os.common.tenant.TenantContextAwareExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 异步任务配置
 * <p>
 * 所有线程池均使用 TenantContextAwareExecutor 包装，实现租户上下文的自动透传。
 * 业务代码使用 @Async 时无需关心租户上下文，TenantMetaObjectHandler 会自动填充 tenant_id。
 *
 * @see com.richuang.os.common.tenant.TenantContextAwareExecutor
 * @see com.richuang.os.common.tenant.TenantMetaObjectHandler
 */
@Slf4j
@Configuration
public class AsyncConfig {

    /**
     * 包装执行器为租户上下文感知执行器
     *
     * @param executor 原始执行器
     * @param name     执行器名称
     * @return 包装后的执行器
     */
    private Executor wrapWithTenantContext(Executor executor, String name) {
        log.info("[AsyncConfig] 包装执行器为租户上下文感知执行器: {}", name);
        return new TenantContextAwareExecutor(executor, name);
    }

    /**
     * 文档处理任务线程池
     * <p>
     * 支持租户上下文自动透传，异步处理文档时自动填充 tenant_id
     */
    @Bean("documentTaskExecutor")
    public Executor documentTaskExecutor() {
        ThreadPoolTaskExecutor executor = TenantContextAwareExecutor.createTaskExecutor(
                2,                      // 核心线程数
                5,                      // 最大线程数
                100,                    // 队列容量
                "doc-process-",         // 线程名前缀
                "documentTaskExecutor"  // 执行器名称
        );
        return executor;
    }

    /**
     * 上传任务线程池
     * <p>
     * 支持租户上下文自动透传，异步处理上传任务时自动填充 tenant_id
     * 声明具体返回类型，供 Flowable 按 AsyncTaskExecutor 类型判断，避免重复注册 taskExecutor。
     */
    @Bean("taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = TenantContextAwareExecutor.createTaskExecutor(
                5,                      // 核心线程数
                20,                     // 最大线程数
                100,                    // 队列容量
                "upload-task-",         // 线程名前缀
                "taskExecutor"          // 执行器名称
        );
        return executor;
    }

    /**
     * 消息发送任务线程池
     * <p>
     * 支持租户上下文自动透传，异步发送消息时自动填充 tenant_id
     */
    @Bean("messageTaskExecutor")
    public Executor messageTaskExecutor() {
        ThreadPoolTaskExecutor executor = TenantContextAwareExecutor.createTaskExecutor(
                3,                      // 核心线程数
                10,                     // 最大线程数
                50,                     // 队列容量
                "message-task-",        // 线程名前缀
                "messageTaskExecutor"   // 执行器名称
        );
        return executor;
    }

}
