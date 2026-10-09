package com.lingan.ucp.nocode.application.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 按日期自动执行：某规则某业务日已处理的一条来源记录。唯一键 = 规则 × 业务日 × 来源记录，同一天不重复执行就落在它上面。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nocode_date_trigger_done")
public class DateTriggerDoneDO extends BaseDO {
    @TableId private Long id;
    private Long applicationId;
    private String resourceId;
    private LocalDate businessDate;
    private String sourceRecordId;

    /** RUNNING（只存在于处理它的未提交事务里）/ SUCCESS / UNCHANGED / FAILED。 */
    private String outcome;

    private Integer targetCount;
    private String message;
    private LocalDateTime doneAt;
}
