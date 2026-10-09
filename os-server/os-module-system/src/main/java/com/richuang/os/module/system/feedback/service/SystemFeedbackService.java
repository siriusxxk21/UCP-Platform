package com.richuang.os.module.system.feedback.service;

import com.richuang.os.module.system.feedback.dto.FeedbackAvailabilityRespVO;
import com.richuang.os.module.system.feedback.dto.FeedbackCreateReqVO;

/**
 * 问题反馈业务服务，定义可用性检查和提交入口。
 */
public interface SystemFeedbackService {
    FeedbackAvailabilityRespVO getAvailability();

    Long createFeedback(FeedbackCreateReqVO request, String userAgent);
}
