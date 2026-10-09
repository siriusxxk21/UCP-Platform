package com.lingan.ucp.module.msg.core;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@ConfigurationProperties(prefix = "message.notice")
@Component
@Data
public class MessageNoticeProperty {

    private final Lxt lxt = new Lxt();

    /**
     * 凌信通
     */
    @Data
    public static class Lxt {
        private Boolean enabled = true;
        private String url;
        private String appId = "appId";
        private String sysName = "凌安智研管理平台";
    }
}
