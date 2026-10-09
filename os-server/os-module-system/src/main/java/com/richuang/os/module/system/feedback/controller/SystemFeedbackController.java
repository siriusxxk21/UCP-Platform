package com.richuang.os.module.system.feedback.controller;

import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.module.system.feedback.dto.FeedbackAvailabilityRespVO;
import com.richuang.os.module.system.feedback.dto.FeedbackCreateReqVO;
import com.richuang.os.module.system.feedback.service.SystemFeedbackService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 登录用户的问题反馈入口，仅提供可用性检查和提交能力。
 */
@RestController
@RequestMapping("/api/system/feedback")
public class SystemFeedbackController {

    @Resource
    private SystemFeedbackService feedbackService;

    @GetMapping("/availability")
    public Result<FeedbackAvailabilityRespVO> availability() {
        return Result.success(feedbackService.getAvailability());
    }

    @PostMapping
    public Result<Long> create(@ModelAttribute FeedbackCreateReqVO request, HttpServletRequest servletRequest) {
        return Result.success(feedbackService.createFeedback(request, servletRequest.getHeader("User-Agent")));
    }
}
