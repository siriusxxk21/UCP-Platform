package com.richuang.os.nocode.controller.admin.task;

import com.richuang.os.framework.common.pojo.*;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.task.TaskEntryPolicyService;
import com.richuang.os.nocode.runtime.service.task.TaskEntryRuntimeService;
import com.richuang.os.nocode.web.NocodeAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 仅保留存量任务、业务草稿和申请的兼容执行接口；旧门户目录、草稿列表和已办入口已退役。 */
@Tag(name = "无代码 - 任务入口")
@RestController
@RequestMapping("/nocode/task-entry")
@PreAuthorize("isAuthenticated()")
public class TaskEntryController {
    @Resource private TaskEntryRuntimeService runtime;
    @Resource private TaskEntryPolicyService policies;
    @Resource private ApplicationService applications;
    @Resource private NocodeAccess access;
    @Resource private com.richuang.os.nocode.web.StrictRequestDecoder requests;

    @GetMapping("/mine")
    @Operation(summary = "查询本人可用入口")
    public Result<List<TaskEntries.Card>> mine() {
        return Result.success(runtime.mine(access.actor()));
    }

    @PostMapping("/form-fill")
    @Operation(summary = "读取入口表单带入值")
    public Result<java.util.Map<String, Object>> formFill(
            @RequestBody TaskEntries.FormFill command) {
        return Result.success(runtime.formFill(command, access.actor()));
    }

    /** 严格解码：规则只取入口固定版本，请求体携带任何规则配置直接拒绝。 */
    @PostMapping("/field-rules")
    @Operation(summary = "求值入口表单的数据联动与公式默认值")
    public Result<FieldRules.Evaluation> fieldRules(
            @RequestBody com.fasterxml.jackson.databind.JsonNode body) {
        return Result.success(
                runtime.fieldRules(
                        requests.runtime(body, TaskEntries.FieldRules.class), access.actor()));
    }

    @PostMapping("/related-form")
    @Operation(summary = "读取入口关联表单")
    public Result<RelatedForms.Result> relatedForm(@RequestBody RelatedForms.TaskQuery command) {
        return Result.success(runtime.relatedForm(command, access.actor()));
    }

    @PostMapping("/related-selection")
    @Operation(summary = "查询入口关联候选")
    public Result<SelectionFields.Result> relatedSelection(
            @RequestBody RelatedForms.TaskSelection command) {
        return Result.success(runtime.relatedSelection(command, access.actor()));
    }

    @PostMapping("/related-fill")
    @Operation(summary = "读取入口关联带入值")
    public Result<java.util.Map<String, Object>> relatedFill(
            @RequestBody RelatedForms.TaskFill command) {
        return Result.success(runtime.relatedFill(command, access.actor()));
    }

    @PostMapping("/view-model")
    @Operation(summary = "读取入口视图模型")
    public Result<DataViews.Model> viewModel(@RequestBody TaskEntries.ViewModel command) {
        return Result.success(runtime.viewModel(command, access.actor()));
    }

    @PostMapping("/view-children")
    @Operation(summary = "分页查询入口视图子表")
    public Result<PageResult<ApplicationRecords.Row>> viewChildren(
            @RequestBody TaskEntries.ViewChildren command) {
        return Result.success(runtime.viewChildren(command, access.actor()));
    }

    @PostMapping("/context")
    @Operation(summary = "读取可信入口上下文")
    public Result<TaskEntries.Context> context(@RequestBody TaskEntries.Locator command) {
        return Result.success(runtime.context(command, access.actor()));
    }

    @PostMapping("/page")
    @Operation(summary = "分页查询入口业务记录")
    public Result<PageResult<ApplicationRecords.Row>> page(@RequestBody TaskEntries.Query command) {
        return Result.success(runtime.page(command, access.actor()));
    }

    @PostMapping("/get")
    @Operation(summary = "读取入口业务整单")
    public Result<ApplicationRecords.Aggregate> get(@RequestBody TaskEntries.Get command) {
        return Result.success(runtime.get(command, access.actor()));
    }

    @PostMapping("/save")
    @Operation(summary = "保存入口业务整单")
    public Result<ApplicationRecords.Aggregate> save(@RequestBody TaskEntries.Save command) {
        return Result.success(runtime.save(command, access.actor()));
    }

    @PostMapping("/submit")
    @Operation(summary = "提交入口业务办理")
    public Result<BusinessHandling.Result> submit(@RequestBody TaskEntries.Save command) {
        return Result.success(runtime.submit(command, access.actor()));
    }

    @PostMapping("/submit-receipt")
    @Operation(summary = "查询入口办理收据")
    public Result<BusinessHandling.Result> submitReceipt(@RequestBody TaskEntries.Receipt command) {
        return Result.success(runtime.handlingReceipt(command, access.actor()));
    }

    @PostMapping("/delete")
    @Operation(summary = "删除入口业务记录")
    public Result<Boolean> delete(@RequestBody TaskEntries.Delete command) {
        runtime.delete(command, access.actor());
        return Result.success(true);
    }

    @PostMapping("/selection")
    @Operation(summary = "查询入口字段候选")
    public Result<SelectionFields.Result> selection(@RequestBody TaskEntries.Selection command) {
        return Result.success(runtime.selection(command, access.actor()));
    }

    @PostMapping("/receipt")
    @Operation(summary = "查询入口保存收据")
    public Result<ApplicationRecords.SaveReceipt> receipt(
            @RequestBody TaskEntries.Receipt command) {
        return Result.success(runtime.receipt(command, access.actor()));
    }

    @PostMapping("/draft")
    @Operation(summary = "读取入口个人草稿")
    public Result<com.richuang.os.nocode.api.work.WorkDrafts.Draft> draft(
            @RequestBody TaskEntries.Locator command) {
        return Result.success(runtime.draft(command, access.actor()));
    }

    @PostMapping("/draft/save")
    @Operation(summary = "保存入口个人草稿")
    public Result<com.richuang.os.nocode.api.work.WorkDrafts.Draft> saveDraft(
            @RequestBody TaskEntries.Save command) {
        return Result.success(runtime.saveDraft(command, access.actor()));
    }

    /** 存量绑定仍需读取、撤回或停用授权；保留策略接口不重新开放入口配置。 */
    @GetMapping("/policy")
    @Operation(summary = "读取入口授权策略")
    @PreAuthorize("@nocodeAccess.has('nocode:app:manage')")
    public Result<TaskEntries.Policy> policy(
            @RequestParam String applicationId, @RequestParam String entryId) {
        applications.requireDesigner(applicationId, access.actor());
        return Result.success(policies.get(applicationId, entryId));
    }

    @PostMapping("/policy")
    @Operation(summary = "保存入口授权策略")
    @PreAuthorize("@nocodeAccess.has('nocode:app:manage')")
    public Result<TaskEntries.Policy> policy(@RequestBody TaskEntries.SavePolicy command) {
        return Result.success(policies.save(command, access.actor()));
    }
}
