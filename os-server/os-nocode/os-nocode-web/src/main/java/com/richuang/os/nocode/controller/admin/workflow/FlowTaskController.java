package com.richuang.os.nocode.controller.admin.workflow;

import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.nocode.api.SelectionFields;
import com.richuang.os.nocode.api.work.WorkDrafts;
import com.richuang.os.nocode.api.workflow.FlowTasks;
import com.richuang.os.nocode.web.NocodeAccess;
import com.richuang.os.nocode.workflow.service.task.FlowTaskService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 身份从当前登录态取得，具体资格与业务权限由来源服务共同校验。 */
@RestController
@RequestMapping("/nocode/flow-task")
@Tag(name = "无代码 - 流程任务办理")
@PreAuthorize("isAuthenticated()")
public class FlowTaskController {
    @Resource private FlowTaskService service;
    @Resource private NocodeAccess access;

    @PostMapping("/open")
    @Operation(summary = "打开本人流程任务表单或提交材料")
    public Result<FlowTasks.Workspace> open(@RequestBody FlowTasks.Open command) {
        return Result.success(
                service.open(command == null ? null : command.taskId(), access.actor()));
    }

    @PostMapping("/selection")
    @Operation(summary = "按任务固定表单查询当前可见候选项")
    public Result<SelectionFields.Result> selection(@RequestBody FlowTasks.Selection command) {
        return Result.success(service.selection(command, access.actor()));
    }

    @GetMapping("/context")
    @Operation(summary = "获取当前任务的固定资源")
    public Result<FlowTasks.Context> context(@RequestParam String taskId) {
        return Result.success(service.context(taskId, access.actor()));
    }

    @PostMapping("/draft/save")
    @Operation(summary = "暂存当前流程任务的业务输入")
    public Result<WorkDrafts.Draft> save(@RequestBody FlowTasks.Save command) {
        return Result.success(service.saveDraft(command, access.actor()));
    }

    @GetMapping("/draft")
    @Operation(summary = "恢复本人当前任务草稿")
    public Result<WorkDrafts.Draft> draft(
            @RequestParam String taskId, @RequestParam String draftId) {
        return Result.success(service.getDraft(taskId, draftId, access.actor()));
    }

    @PostMapping("/submit")
    @Operation(summary = "提交业务材料并完成当前流程任务")
    public Result<WorkDrafts.Submission> submit(@RequestBody FlowTasks.Submit command) {
        return Result.success(service.submit(command, access.actor()));
    }

    @GetMapping("/submission")
    @Operation(summary = "按当前业务权限读取本人流程提交材料")
    public Result<WorkDrafts.Submission> submission(@RequestParam String taskId) {
        return Result.success(service.getSubmission(taskId, access.actor()));
    }
}
