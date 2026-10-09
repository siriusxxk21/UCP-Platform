package com.lingan.ucp.nocode.application.dal.dataobject;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.lingan.ucp.framework.mybatis.core.dataobject.BaseDO;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 按日期自动执行：一条规则（应用 × 资源）的账本。closedDate 及以前的业务日不再处理。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("nocode_date_trigger_state")
public class DateTriggerStateDO extends BaseDO {
    @TableId private Long id;
    private Long applicationId;
    private String resourceId;

    /** 规则当前是否生效：发布 / 启用时置真，停用、暂停应用或从版本里拿掉时置假。重新生效时账本从当天重新起算。 */
    private Boolean armed;

    private LocalDate closedDate;
    private LocalDateTime armedAt;
    private LocalDateTime lastScanAt;
    private LocalDate lastScanDate;

    /** AUTO：定时；MANUAL：立即按今天执行。 */
    private String lastTrigger;

    private String lastError;
}
