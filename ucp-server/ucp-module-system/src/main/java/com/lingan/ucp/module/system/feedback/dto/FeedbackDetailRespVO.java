package com.lingan.ucp.module.system.feedback.dto;

import com.lingan.ucp.module.system.feedback.entity.SystemFeedback;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.util.List;

/** 反馈详情聚合提交图片和管理员处理记录，不返回状态机或操作权限模型。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class FeedbackDetailRespVO extends SystemFeedback {
    private List<String> imageUrls;
    private List<FeedbackFollowUpRespVO> followUps;
}
