package com.lingan.ucp.module.bpm.enums.definition;

import com.lingan.ucp.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 用户任务超时处理类型枚举
 *
 * @author jason
 */
@Getter
@AllArgsConstructor
public enum BpmUserTaskTimeoutHandlerTypeEnum implements ArrayValuable<Integer> {

    REMINDER(1, "自动提醒"),
    APPROVE(2, "自动同意"),
    REJECT(3, "自动拒绝");

    public static final Integer[] ARRAYS = Arrays.stream(values()).map(BpmUserTaskTimeoutHandlerTypeEnum::getType).toArray(Integer[]::new);
    private final Integer type;
    private final String name;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }

}
