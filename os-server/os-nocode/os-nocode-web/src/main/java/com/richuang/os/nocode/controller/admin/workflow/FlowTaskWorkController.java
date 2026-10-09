package com.richuang.os.nocode.controller.admin.workflow;

import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.nocode.api.workflow.FlowTaskWorkViews;
import com.richuang.os.nocode.web.NocodeAccess;
import com.richuang.os.nocode.workflow.service.task.FlowTaskWorkQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 个人流程工作索引；操作者只能来自当前登录态，不能按他人身份查询。 */
@RestController
@RequestMapping("/nocode/flow-task")
@Tag(name = "无代码 - 本人流程工作")
@PreAuthorize("isAuthenticated()")
public class FlowTaskWorkController {
    @Resource private FlowTaskWorkQueryService service;
    @Resource private NocodeAccess access;

    @PostMapping("/work-page")
    @Operation(summary = "分页读取本人可见流程草稿或已提交材料摘要")
    public Result<FlowTaskWorkViews.Page> page(@RequestBody FlowTaskWorkViews.Query query) {
        return Result.success(service.page(query, access.actor()));
    }
}
