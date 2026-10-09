package com.richuang.os.nocode.controller.admin.work;

import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.nocode.api.SelectionFields;
import com.richuang.os.nocode.api.work.WorkDraftViews;
import com.richuang.os.nocode.api.work.WorkDrafts;
import com.richuang.os.nocode.runtime.service.work.WorkDraftQueryService;
import com.richuang.os.nocode.runtime.service.work.WorkFormService;
import com.richuang.os.nocode.web.NocodeAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** 公共业务草稿接口；Controller 不访问 Mapper，业务与实时授权由 Service 处理。 */
@Tag(name = "无代码 - 工作草稿")
@RestController
@RequestMapping("/nocode/work-draft")
@Validated
@PreAuthorize("isAuthenticated()")
public class WorkDraftController {
    @Resource private WorkFormService workFormService;
    @Resource private NocodeAccess access;
    @Resource private WorkDraftQueryService workDraftQueryService;

    @PostMapping("/page")
    @Operation(summary = "按当前权限查询本人草稿或提交")
    public Result<WorkDraftViews.Page> page(@Valid @RequestBody WorkDraftViews.Query request) {
        return Result.success(workDraftQueryService.page(request, access.actor()));
    }

    @GetMapping("/context")
    @Operation(summary = "恢复固定版本表单、草稿与当前权限")
    public Result<WorkDraftViews.Context> context(@RequestParam String id) {
        return Result.success(workDraftQueryService.context(id, access.actor()));
    }

    @PostMapping("/selection")
    @Operation(summary = "在草稿固定版本中查询授权候选")
    public Result<SelectionFields.Result> selection(
            @Valid @RequestBody WorkDraftViews.Selection request) {
        return Result.success(workDraftQueryService.selection(request, access.actor()));
    }

    @PostMapping("/save")
    @Operation(summary = "保存个人业务表单草稿")
    public Result<WorkDrafts.Draft> save(@Valid @RequestBody WorkDrafts.Save request) {
        return Result.success(workFormService.saveDraft(request, access.actor()));
    }

    @GetMapping("/get")
    @Operation(summary = "读取本人草稿并按当前权限过滤")
    public Result<WorkDrafts.Draft> get(@RequestParam String id) {
        return Result.success(workFormService.getDraft(id, access.actor()));
    }

    @PostMapping("/submit")
    @Operation(summary = "正式保存业务并封存本次提交")
    public Result<WorkDrafts.Submission> submit(@Valid @RequestBody WorkDrafts.Submit request) {
        return Result.success(workFormService.submit(request, access.actor()));
    }

    @GetMapping("/submission")
    @Operation(summary = "按当前权限读取本人提交材料")
    public Result<WorkDrafts.Submission> submission(@RequestParam String id) {
        return Result.success(workFormService.getSubmission(id, access.actor()));
    }
}
