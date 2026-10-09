package com.richuang.os.module.system.enums.organization;

import com.richuang.os.framework.common.core.ArrayValuable;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;

/**
 * 组织状态沿用 system_organization 的既有编码：1 启用、0 禁用。
 * 与用户、部门等目录使用的通用状态编码不同，候选与保存校验统一使用此枚举。
 */
@Getter
@AllArgsConstructor
public enum OrganizationStatusEnum implements ArrayValuable<Integer> {

    ENABLE(1, "启用"),
    DISABLE(0, "禁用");

    public static final Integer[] ARRAYS = Arrays.stream(values()).map(OrganizationStatusEnum::getStatus).toArray(Integer[]::new);

    private final Integer status;
    private final String name;

    @Override
    public Integer[] array() {
        return ARRAYS;
    }

    public static boolean isEnable(Integer status) {
        return ENABLE.status.equals(status);
    }
}
