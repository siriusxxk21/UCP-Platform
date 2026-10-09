package com.richuang.os.module.system.feedback.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 原反馈人对待确认反馈的处理结果确认请求。
 */
@Data
public class FeedbackVerificationReqVO {

    @NotNull(message = "请选择验证结果")
    private Boolean passed;

    @Size(max = 2000, message = "验证说明不能超过2000字")
    private String remark;

    @NotNull(message = "反馈版本不能为空")
    private Integer version;
}
