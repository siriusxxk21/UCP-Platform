package com.richuang.os.module.infra.enums.job;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 任务状态的枚举
 *
 * @author os
 */
@Getter
@AllArgsConstructor
public enum JobStatusEnum {

    /**
     * 初始化中
     */
    INIT(0),
    /**
     * 开启
     */
    NORMAL(1),
    /**
     * 暂停
     */
    STOP(2);

    /**
     * 状态
     */
    private final Integer status;
}
