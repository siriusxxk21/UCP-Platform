package com.richuang.os.module.bpm.api.task;

import com.richuang.os.module.bpm.api.task.dto.BpmBusinessTaskCompleteReqDTO;
import com.richuang.os.module.bpm.api.task.dto.BpmBusinessTaskDTO;

/**
 * 业务来源提供的完成校验扩展。实现不得再次调用任务完成接口，也不得产生外部副作用。
 *
 * <p>校验当前资格、固定资源、同事务提交材料及受控变量；任何缺失均抛出异常。 一个 handler 必须恰好对应一个实现。引擎完成监听可能重复校验，不能以此消费材料或发送消息。
 */
public interface BpmTaskCompletionGuard {
    String handler();

    void validate(BpmBusinessTaskDTO task, BpmBusinessTaskCompleteReqDTO command, long actor);
}
