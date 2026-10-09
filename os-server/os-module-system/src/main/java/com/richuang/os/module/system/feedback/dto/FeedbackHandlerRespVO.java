package com.richuang.os.module.system.feedback.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 可分派的反馈处理人。 */
@Data
@AllArgsConstructor
public class FeedbackHandlerRespVO {
    private Long id;
    private String name;
}
