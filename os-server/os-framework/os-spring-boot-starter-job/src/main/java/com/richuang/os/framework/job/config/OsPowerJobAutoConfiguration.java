package com.richuang.os.framework.job.config;

import com.richuang.os.framework.job.core.handler.JobHandlerProcessor;
import com.richuang.os.framework.job.core.scheduler.PowerJobManager;
import com.richuang.os.framework.job.core.service.JobLogFrameworkService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.Assert;
import tech.powerjob.client.ClientConfig;
import tech.powerjob.client.PowerJobClient;

import java.util.Arrays;
import java.util.List;

/**
 * PowerJob 定时任务自动配置。
 *
 * <p>PowerJob Server 是外部调度中心，本配置只创建 Worker 所需的通用处理器和管理端客户端。</p>
 */
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(PowerJobClientProperties.class)
public class OsPowerJobAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "powerjob.client", name = "enabled", havingValue = "true")
    public PowerJobClient powerJobClient(PowerJobClientProperties properties) {
        Assert.hasText(properties.getServerAddress(), "powerjob.client.server-address 不能为空");
        Assert.hasText(properties.getAppName(), "powerjob.client.app-name 不能为空");
        Assert.hasText(properties.getPassword(), "powerjob.client.password 不能为空");

        List<String> addresses = Arrays.stream(properties.getServerAddress().split(","))
                .map(String::trim)
                .filter(address -> !address.isEmpty())
                .toList();
        ClientConfig clientConfig = new ClientConfig()
                .setAddressList(addresses)
                .setAppName(properties.getAppName())
                .setPassword(properties.getPassword())
                .setConnectionTimeout(properties.getConnectionTimeout())
                .setReadTimeout(properties.getReadTimeout())
                .setWriteTimeout(properties.getWriteTimeout());
        return new PowerJobClient(clientConfig);
    }

    @Bean
    public PowerJobManager powerJobManager(ObjectProvider<PowerJobClient> clientProvider) {
        return new PowerJobManager(clientProvider.getIfAvailable());
    }

    @Bean(name = JobHandlerProcessor.PROCESSOR_BEAN_NAME)
    @ConditionalOnBean(JobLogFrameworkService.class)
    public JobHandlerProcessor jobHandlerProcessor(ApplicationContext applicationContext,
                                                   JobLogFrameworkService jobLogFrameworkService) {
        return new JobHandlerProcessor(applicationContext, jobLogFrameworkService);
    }
}
