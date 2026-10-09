package com.lingan.ucp.nocode.api.work;

/** 固定发布资源身份；只能由已发布流程／任务配置或有权的配置服务产生。 */
public record PublishedResourceRef(
        String applicationId,
        int applicationVersion,
        String applicationChecksum,
        String resourceId,
        String resourceKind) {}
