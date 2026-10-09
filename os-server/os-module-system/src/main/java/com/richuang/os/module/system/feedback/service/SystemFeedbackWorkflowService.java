package com.richuang.os.module.system.feedback.service;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.module.system.feedback.dto.FeedbackDetailRespVO;
import com.richuang.os.module.system.feedback.dto.FeedbackFollowUpReqVO;
import com.richuang.os.module.system.feedback.dto.FeedbackPageReqVO;
import com.richuang.os.module.system.feedback.entity.SystemFeedback;

/** 管理员统一查询和处理工单；不提供提交人确认或状态流转接口。 */
public interface SystemFeedbackWorkflowService {
    PageResult<SystemFeedback> getAdminPage(FeedbackPageReqVO request);
    FeedbackDetailRespVO getAdminDetail(Long id);
    void followUp(Long id, FeedbackFollowUpReqVO request);
}
