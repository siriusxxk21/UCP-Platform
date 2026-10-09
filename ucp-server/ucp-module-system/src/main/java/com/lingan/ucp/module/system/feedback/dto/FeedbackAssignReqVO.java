package com.lingan.ucp.module.system.feedback.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 反馈负责人指派请求；指派与处理流转分离，避免通过跟进接口隐式改派。
 */
@Data
public class FeedbackAssignReqVO {

    @NotNull(message = "请选择负责人")
    private Long assigneeId;

    @NotNull(message = "反馈版本不能为空")
    private Integer version;
}
