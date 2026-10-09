package com.lingan.ucp.module.bpm.support;

import org.springframework.beans.BeanUtils;

import java.util.UUID;
import java.util.function.Consumer;

/** 候选人策略测试夹具。只设置用例声明的字段，不随机填充影响业务分支的状态。 */
public final class BpmTestFixtures {
    private BpmTestFixtures() {}

    public static <T> T bean(Class<T> type, Consumer<T> configure) {
        T value = BeanUtils.instantiateClass(type);
        configure.accept(value);
        return value;
    }

    public static String uniqueId() {
        return UUID.randomUUID().toString();
    }
}
