package com.richuang.os.module.bpm.api.event;

/**
 * 流程实例结束状态常量，供不依赖 Flowable 实现的业务监听器使用。
 */
public final class BpmProcessInstanceStatus {

    public static final int APPROVED = 2;
    public static final int REJECTED = 3;
    public static final int CANCELED = 4;

    private BpmProcessInstanceStatus() {
    }
}
