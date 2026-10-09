package com.richuang.os.nocode.runtime.dal.dataobject;

import com.baomidou.mybatisplus.annotation.*;
import com.richuang.os.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 个人计划只是任务引用，不复制任务也不改变执行状态。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "nocode_task_plan", schema = "public")
public class TaskPlanDO extends BaseDO {
    @TableId(type = IdType.INPUT)
    private String id;

    private String taskId;
    private Long userId;
    private String period;
    private String planMode;
    private LocalDate planDate;
    private LocalDate endDate;

    /** 撤销/改期/改派保留原引用，历史原因不充当删除授权。 */
    private String historyReason;

    private Long arrangedById;
    private String source;
    private LocalDateTime arrangedAt;

    /** 清单继承查询专用；不落库、不复制历史引用。 */
    @TableField(exist = false)
    private String inheritedFromTaskId;

    @TableField(exist = false)
    private String inheritedFromTitle;

    @TableField(exist = false)
    private boolean inherited;
}
