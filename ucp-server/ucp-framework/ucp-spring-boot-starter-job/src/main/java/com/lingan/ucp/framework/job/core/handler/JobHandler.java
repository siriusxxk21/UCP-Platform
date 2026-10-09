package com.lingan.ucp.framework.job.core.handler;

/**
 * 业务定时任务处理器。
 *
 * <p>业务模块只需实现该接口，PowerJob 协议和执行上下文由框架层统一适配。</p>
 */
public interface JobHandler {

    /**
     * 执行任务。
     *
     * @param param 业务参数
     * @return 执行结果
     * @throws Exception 执行异常
     */
    String execute(String param) throws Exception;
}
