package com.lingan.ucp.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 一次实际办理贡献；同一业务记录可保留多任务、多人员、多个版本的来源。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_task_entry_record", schema = "public")
public class TaskWorkRecordDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private String taskId;
    private String entryKey;
    private String datasetId;
    private String businessJson;
    private String operation;
    private String recordRevision;
    private String snapshotJson;
    // 审批提交时即冻结，批准时间或后续调价不得回写此规则。
    private String workRuleJson;
    private String requestKey;
    private String requestHash;
    private String supersededBy;
}
