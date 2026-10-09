package com.richuang.os.module.bpm.api.event;

import org.springframework.context.ApplicationListener;

import java.util.Objects;

/**
 * 按固定流程定义 Key 过滤流程结果的业务监听器基类。
 */
public abstract class BpmProcessInstanceStatusEventListener
        implements ApplicationListener<BpmProcessInstanceStatusEvent> {

    @Override
    public final void onApplicationEvent(BpmProcessInstanceStatusEvent event) {
        if (Objects.equals(event.getProcessDefinitionKey(), getProcessDefinitionKey())) {
            onEvent(event);
        }
    }

    protected abstract String getProcessDefinitionKey();

    protected abstract void onEvent(BpmProcessInstanceStatusEvent event);
}
