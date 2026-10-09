package com.lingan.ucp.framework.job.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PowerJob OpenAPI 客户端配置。
 *
 * <p>仅用于任务元数据的创建、启停和手动触发，Worker 连接参数仍使用 PowerJob 官方的
 * {@code powerjob.worker} 配置。</p>
 */
@Data
@ConfigurationProperties(prefix = "powerjob.client")
public class PowerJobClientProperties {

    /**
     * 是否启用任务管理客户端。
     */
    private boolean enabled;

    /**
     * PowerJob Server 地址，多个地址使用逗号分隔。
     */
    private String serverAddress;

    /**
     * PowerJob 应用名称。
     */
    private String appName;

    /**
     * PowerJob 应用密码。
     */
    private String password;

    private Integer connectionTimeout;
    private Integer readTimeout;
    private Integer writeTimeout;
}
