package com.richuang.os.module.bpm.api.task;

import com.richuang.os.module.bpm.api.task.dto.BpmTaskCenterNodeArrivalDTO;

import java.util.Set;

/** 任务节点的业务扩展；BPM 只管理等待点，不依赖任务中心的内部状态机。 */
public interface BpmTaskCenterNodeHandler {

    /** 发布事务中校验配置、固定模板版本并返回不可变的运行配置；不得创建真实任务。 */
    String prepare(String nodeId, String configurationJson, long publisherId);

    /** 需要读取的流程人员字段；发布时核对字段，运行时只投影这些变量。 */
    default Set<String> neededFields(String configurationJson) {
        return Set.of();
    }

    /** 在引擎到达节点的事务中登记可重试记录，返回此轮唯一绑定编号。 不在监听器内调用引擎推进，也不应因后续创建任务的暂时失败回滚前序审批。 */
    String arrive(BpmTaskCenterNodeArrivalDTO arrival);
}
