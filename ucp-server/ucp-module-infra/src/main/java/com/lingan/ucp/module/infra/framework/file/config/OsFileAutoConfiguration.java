package com.lingan.ucp.module.infra.framework.file.config;

import com.lingan.ucp.module.infra.framework.file.core.client.FileClientFactory;
import com.lingan.ucp.module.infra.framework.file.core.client.FileClientFactoryImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件配置类
 *
 * @author os
 */
@Configuration(proxyBeanMethods = false)
public class OsFileAutoConfiguration {

    @Bean
    public FileClientFactory fileClientFactory() {
        return new FileClientFactoryImpl();
    }

}
