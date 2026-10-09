package com.richuang.os.nocode.controller.admin.workflow;

import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.nocode.api.workflow.WorkflowTaskNodes;
import com.richuang.os.nocode.web.NocodeAccess;
import com.richuang.os.nocode.workflow.service.task.WorkflowTaskNodeService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 双向查看分别由任务、流程原权限校验；不暴露引擎通用唤醒能力。 */
@RestController
@RequestMapping("/nocode/workflow/task-nodes")
@Tag(name = "无代码 - 工作流任务节点")
@PreAuthorize("isAuthenticated()")
public class WorkflowTaskNodeController {
    @Resource private WorkflowTaskNodeService service;
    @Resource private NocodeAccess access;

    @GetMapping
    @Operation(summary = "查看流程关联的任务节点")
    public Result<List<WorkflowTaskNodes.View>> process(@RequestParam String processInstanceId) {
        return Result.success(service.process(processInstanceId, access.actor()));
    }

    @GetMapping("/source")
    @Operation(summary = "查看任务的工作流来源")
    public Result<WorkflowTaskNodes.View> source(@RequestParam String taskId) {
        return Result.success(service.source(taskId, access.actor()));
    }

    @PostMapping("/retry")
    @Operation(summary = "重试当前任务节点的创建或完成交接")
    public Result<WorkflowTaskNodes.View> retry(@RequestBody WorkflowTaskNodes.Retry command) {
        return Result.success(service.retry(command.executionId(), access.actor()));
    }
}
