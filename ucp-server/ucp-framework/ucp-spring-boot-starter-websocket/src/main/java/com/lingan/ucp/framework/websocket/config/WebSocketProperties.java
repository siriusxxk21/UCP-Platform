package com.lingan.ucp.framework.websocket.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotEmpty;

/**
 * WebSocket 配置项
 *
 * @author xingyu4j
 */
@ConfigurationProperties("os.websocket")
@Data
@Validated
public class WebSocketProperties {

    /**
     * WebSocket 的连接路径
     */
    @NotEmpty(message = "WebSocket 的连接路径不能为空")
    private String path = "/ws";

}
