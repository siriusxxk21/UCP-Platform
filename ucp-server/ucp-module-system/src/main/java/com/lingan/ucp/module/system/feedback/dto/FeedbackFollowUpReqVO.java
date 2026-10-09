package com.lingan.ucp.module.system.feedback.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 直接保存状态及可选处理说明；版本防止多人处理时覆盖。 */
@Data
public class FeedbackFollowUpReqVO {
    @NotBlank(message = "请选择解决状态")
    private String status;

    @Size(max = 2000, message = "处理说明不能超过2000字")
    private String content;

    @NotNull(message = "工单版本不能为空")
    private Integer version;
}
