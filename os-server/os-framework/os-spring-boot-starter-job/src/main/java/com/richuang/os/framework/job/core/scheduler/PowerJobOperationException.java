package com.richuang.os.framework.job.core.scheduler;

/**
 * PowerJob 管理端操作异常。
 *
 * <p>用于保留具体操作和 PowerJob Server 返回信息，避免外部调度失败被静默忽略。</p>
 */
public class PowerJobOperationException extends RuntimeException {

    public PowerJobOperationException(String message) {
        super(message);
    }
}
