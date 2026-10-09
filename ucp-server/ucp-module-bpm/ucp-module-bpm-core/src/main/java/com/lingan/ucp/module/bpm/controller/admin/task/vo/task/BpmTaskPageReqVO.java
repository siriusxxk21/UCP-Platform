package com.lingan.ucp.module.bpm.controller.admin.task.vo.task;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.lingan.ucp.framework.common.pojo.PageParam;
import com.lingan.ucp.framework.common.util.date.DateUtils;
import com.lingan.ucp.framework.common.validation.InEnum;
import com.lingan.ucp.module.bpm.enums.task.BpmTaskStatusEnum;

import io.swagger.v3.oas.annotations.media.Schema;

import jakarta.validation.constraints.AssertTrue;

import lombok.Data;

import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

@Schema(description = "管理后台 - 流程任务的的分页 Request VO") // 待办、已办，都使用该分页
@Data
public class BpmTaskPageReqVO extends PageParam {

    @Schema(description = "流程任务名", example = "芋道")
    private String name;

    @Schema(description = "流程分类", example = "1")
    private String category;

    @Schema(description = "流程定义的标识", example = "2048")
    private String processDefinitionKey; // 精准匹配

    @Schema(description = "审批状态", example = "1")
    @InEnum(BpmTaskStatusEnum.class)
    private Integer status; // 【已办】与任务管理使用

    @Schema(description = "创建时间")
    @DateTimeFormat(pattern = DateUtils.FORMAT_YEAR_MONTH_DAY_HOUR_MINUTE_SECOND)
    private LocalDateTime[] createTime;

    /** 防止不完整或倒置的时间范围进入引擎查询。 */
    @AssertTrue(message = "创建时间必须包含有效的起止时间，且开始时间不能晚于结束时间")
    @JsonIgnore
    public boolean isValidCreateTimeRange() {
        return createTime == null
                || createTime.length == 0
                || createTime.length == 2
                        && createTime[0] != null
                        && createTime[1] != null
                        && !createTime[0].isAfter(createTime[1]);
    }
}
