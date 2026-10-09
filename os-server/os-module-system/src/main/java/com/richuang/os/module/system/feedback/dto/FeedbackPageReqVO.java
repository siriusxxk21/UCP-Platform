package com.richuang.os.module.system.feedback.dto;

import com.richuang.os.framework.common.pojo.PageParam;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/**
 * 系统管理工单列表筛选参数。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FeedbackPageReqVO extends PageParam {
    private String keyword;
    private String type;
    private String status;
    @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime[] createTime;
}
