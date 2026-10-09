package com.richuang.os.framework.job.core.handler;

/**
 * 通用 PowerJob Processor 的路由参数。
 *
 * <p>该对象作为 PowerJob 任务参数存储，用于将调度请求路由到现有 Spring JobHandler。</p>
 *
 * @param jobId         本地任务编号
 * @param handlerName   Spring 处理器 Bean 名称
 * @param handlerParam  业务参数
 * @param retryInterval 重试前等待毫秒数
 */
public record JobExecutionParam(Long jobId, String handlerName, String handlerParam, Integer retryInterval) {
}
