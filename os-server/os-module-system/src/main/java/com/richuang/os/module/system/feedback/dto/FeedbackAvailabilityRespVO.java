package com.richuang.os.module.system.feedback.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * 当前租户反馈入口的可用状态和前端上传约束。
 */
@Data
@AllArgsConstructor
public class FeedbackAvailabilityRespVO {
    private boolean enabled;
    private int maxImages;
    private int maxImageSizeMb;
    private List<String> allowedImageTypes;
}
