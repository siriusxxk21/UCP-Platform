package com.lingan.ucp.framework.job.config;

import com.alibaba.ttl.TtlRunnable;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * 异步任务上下文传递配置。
 */
@AutoConfiguration
public class OsAsyncAutoConfiguration {

    @Bean
    public BeanPostProcessor threadPoolTaskExecutorBeanPostProcessor() {
        return new BeanPostProcessor() {

            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
                if (bean instanceof ThreadPoolTaskExecutor executor) {
                    executor.setTaskDecorator(TtlRunnable::get);
                    return executor;
                }
                if (bean instanceof SimpleAsyncTaskExecutor executor) {
                    executor.setTaskDecorator(TtlRunnable::get);
                    return executor;
                }
                return bean;
            }
        };
    }
}
