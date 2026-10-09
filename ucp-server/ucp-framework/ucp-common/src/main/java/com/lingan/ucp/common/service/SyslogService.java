package com.lingan.ucp.common.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.graylog2.syslog4j.Syslog;
import org.graylog2.syslog4j.SyslogIF;
import org.graylog2.syslog4j.impl.net.udp.UDPNetSyslogConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class SyslogService {

    private static final String SYSLOG_PROTOCOL = "udp";

    @Value("${syslog.host:192.168.16.128}")
    private String syslogHost;

    @Value("${syslog.port:514}")
    private int syslogPort;

    @Value("${syslog.local-name:code-gen-server}")
    private String localName;

    // 定义一个 Syslog 客户端实例
    private volatile SyslogIF syslogClient;

    // 在 Bean 初始化后创建连接
    @PostConstruct
    public void init() {
        try {
            UDPNetSyslogConfig config = new UDPNetSyslogConfig();
            config.setHost(syslogHost);
            config.setPort(syslogPort);
            config.setLocalName(localName);
            // getInstance 内部会注册到 Map，确保 destroyInstance 能正确销毁；
            // 若已存在同协议实例则复用，避免 createInstance 重复创建抛异常
            syslogClient = Syslog.getInstance(SYSLOG_PROTOCOL);
            syslogClient.initialize(SYSLOG_PROTOCOL, config);
            log.info("Syslog UDP 客户端初始化成功, host={}, port={}", syslogHost, syslogPort);
        } catch (Exception e) {
            log.warn("Syslog 初始化失败，远程日志推送功能不可用: {}", e.getMessage());
            syslogClient = null;
        }
    }

    // 发送一条 INFO 级别的消息
    public void sendInfo(String message) {
        if (syslogClient != null) {
            try {
                syslogClient.info(message);
            } catch (Exception e) {
                log.debug("Syslog info 发送失败: {}", e.getMessage());
            }
        }
    }

    // 发送一条 ERROR 级别的消息
    public void sendError(String message) {
        if (syslogClient != null) {
            try {
                syslogClient.error(message);
            } catch (Exception e) {
                log.debug("Syslog error 发送失败: {}", e.getMessage());
            }
        }
    }

    // 在 Bean 销毁前关闭客户端连接
    @PreDestroy
    public void destroy() {
        if (syslogClient != null) {
            try {
                Syslog.destroyInstance(SYSLOG_PROTOCOL);
                log.info("Syslog UDP 客户端已关闭");
            } catch (Exception e) {
                log.debug("Syslog 销毁异常: {}", e.getMessage());
            }
        }
    }
}