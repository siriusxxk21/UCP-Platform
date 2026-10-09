package com.lingan.ucp.module.system.feedback.service;

import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.module.system.feedback.dto.FeedbackDetailRespVO;
import com.lingan.ucp.module.system.feedback.dto.FeedbackFollowUpReqVO;
import com.lingan.ucp.module.system.feedback.dto.FeedbackPageReqVO;
import com.lingan.ucp.module.system.feedback.entity.SystemFeedback;

/** 管理员统一查询和处理工单；不提供提交人确认或状态流转接口。 */
public interface SystemFeedbackWorkflowService {
    PageResult<SystemFeedback> getAdminPage(FeedbackPageReqVO request);
    FeedbackDetailRespVO getAdminDetail(Long id);
    void followUp(Long id, FeedbackFollowUpReqVO request);
}
