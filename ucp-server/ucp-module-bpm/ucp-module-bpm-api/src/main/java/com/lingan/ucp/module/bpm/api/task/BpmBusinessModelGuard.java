package com.lingan.ucp.module.bpm.api.task;

/** 发布前由业务模块验证节点固定资源；BPM 不依赖任何业务模块实现。 */
public interface BpmBusinessModelGuard {
    String handler();

    void validate(String nodeId, String configuration, long actor);
}
