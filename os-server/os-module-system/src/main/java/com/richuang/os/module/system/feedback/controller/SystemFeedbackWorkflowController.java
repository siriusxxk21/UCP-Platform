package com.richuang.os.module.system.feedback.controller;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.module.system.feedback.dto.FeedbackDetailRespVO;
import com.richuang.os.module.system.feedback.dto.FeedbackFollowUpReqVO;
import com.richuang.os.module.system.feedback.dto.FeedbackPageReqVO;
import com.richuang.os.module.system.feedback.entity.SystemFeedback;
import com.richuang.os.module.system.feedback.service.SystemFeedbackWorkflowService;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/** 系统管理工单入口，沿用底座登录，不增加工单级权限或本人可见过滤。 */
@RestController
@RequestMapping("/api/system/feedback/admin")
public class SystemFeedbackWorkflowController {
    @Resource
    private SystemFeedbackWorkflowService workflowService;

    @GetMapping("/page")
    public Result<PageResult<SystemFeedback>> page(@Valid FeedbackPageReqVO request) {
        return Result.success(workflowService.getAdminPage(request));
    }

    @GetMapping("/{id}")
    public Result<FeedbackDetailRespVO> detail(@PathVariable Long id) {
        return Result.success(workflowService.getAdminDetail(id));
    }

    @PutMapping("/{id}/follow-up")
    public Result<Boolean> save(@PathVariable Long id, @Valid @RequestBody FeedbackFollowUpReqVO request) {
        workflowService.followUp(id, request);
        return Result.success(true);
    }
}
