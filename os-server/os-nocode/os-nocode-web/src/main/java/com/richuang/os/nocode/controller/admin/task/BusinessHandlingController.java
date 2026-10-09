package com.richuang.os.nocode.controller.admin.task;

import com.richuang.os.framework.common.pojo.*;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.runtime.service.handling.BusinessHandlingService;
import com.richuang.os.nocode.web.NocodeAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 个人申请及已分配审批任务入口，Service 按真实身份核验材料。 */
@Tag(name = "无代码 - 业务办理申请")
@RestController
@RequestMapping("/nocode/handling")
@PreAuthorize("isAuthenticated()")
public class BusinessHandlingController {
    @PostMapping("/reopen")
    @Operation(summary = "读取可重新填写的申请")
    public Result<BusinessHandling.Reopen> reopen(@RequestBody BusinessHandling.Ref query) {
        return Result.success(handling.reopen(query.id(), access.actor()));
    }

    @Resource private BusinessHandlingService handling;
    @Resource private NocodeAccess access;

    @PostMapping("/submit")
    @Operation(summary = "提交业务办理申请")
    public Result<BusinessHandling.Result> submit(@RequestBody ApplicationRecords.Save command) {
        return Result.success(handling.submit(command, access.actor()));
    }

    @PostMapping("/receipt")
    @Operation(summary = "查询业务办理收据")
    public Result<BusinessHandling.Result> receipt(@RequestBody BusinessHandling.Receipt query) {
        return Result.success(handling.receipt(query, access.actor()));
    }

    @PostMapping("/mine")
    @Operation(summary = "分页查询本人申请")
    public Result<PageResult<BusinessHandling.Request>> mine(
            @RequestBody BusinessHandling.Query query) {
        return Result.success(handling.mine(query, access.actor()));
    }

    @GetMapping("/detail")
    @Operation(summary = "读取申请与审批材料")
    public Result<BusinessHandling.Detail> detail(
            @RequestParam String id, @RequestParam(required = false) String taskId) {
        return Result.success(handling.detail(id, taskId, access.actor()));
    }

    @PostMapping("/withdraw")
    @Operation(summary = "撤回业务申请")
    public Result<BusinessHandling.Request> withdraw(
            @RequestBody BusinessHandling.Withdraw command) {
        return Result.success(handling.withdraw(command, access.actor()));
    }

    @PostMapping("/retry")
    @Operation(summary = "重试未生效的业务申请")
    public Result<BusinessHandling.Request> retry(@RequestBody BusinessHandling.Retry command) {
        return Result.success(handling.retry(command, access.actor()));
    }
}
