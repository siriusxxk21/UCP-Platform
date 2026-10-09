package com.lingan.ucp.module.bpm.enums.definition;

import com.lingan.ucp.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * BPM HTTP 请求参数设置类型。用于 Simple 设计器任务监听器和触发器配置。
 *
 * @author Lesan
 */
@Getter
@AllArgsConstructor
public enum BpmHttpRequestParamTypeEnum implements ArrayValuable<Integer> {

    FIXED_VALUE(1, "固定值"),
    FROM_FORM(2, "表单");

    public static final Integer[] ARRAYS = Arrays.stream(values()).map(BpmHttpRequestParamTypeEnum::getType).toArray(Integer[]::new);
    private final Integer type;
    private final String name;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }

}