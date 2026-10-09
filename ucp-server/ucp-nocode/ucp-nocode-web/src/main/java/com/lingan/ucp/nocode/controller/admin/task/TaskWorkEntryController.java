package com.lingan.ucp.nocode.controller.admin.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.pojo.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskHandlingHistoryService;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskWorkEntryService;
import com.lingan.ucp.nocode.web.*;

import io.swagger.v3.oas.annotations.*;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 多入口办理适配：复用公共表单运行时，登录用户和任务范围均由服务端核验。 */
@Tag(name = "无代码 - 任务业务反馈入口")
@RestController
@RequestMapping("/nocode/tasks/entries")
@PreAuthorize("isAuthenticated()")
public class TaskWorkEntryController {
    @Resource private TaskWorkEntryService service;
    @Resource private TaskHandlingHistoryService history;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @PostMapping("/history-page")
    @Operation(summary = "分页读取真实任务办理记录，包含有权查看的已删除业务历史")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<PageResult<TaskWorkEntries.HandlingRow>> historyPage(@RequestBody JsonNode body) {
        return Result.success(
                history.page(
                        requests.design(body, TaskWorkEntries.HistoryQuery.class), access.actor()));
    }

    @PostMapping("/history-detail")
    @Operation(summary = "读取单次办理真实前后快照及有权字段差异")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskWorkEntries.HandlingDetail> historyDetail(@RequestBody JsonNode body) {
        return Result.success(
                history.detail(
                        requests.design(body, TaskWorkEntries.HistoryRef.class), access.actor()));
    }

    @PostMapping("/handling-location")
    @Operation(summary = "定位本人申请对应的反馈入口与贡献记录")
    @PreAuthorize("isAuthenticated()")
    public Result<TaskWorkEntries.HandlingLocation> handlingLocation(@RequestBody JsonNode body) {
        return Result.success(
                service.handlingLocation(
                        requests.design(body, TaskCenter.Ref.class).id(), access.actor()));
    }

    @PostMapping("/list")
    @Operation(summary = "读取任务有效业务入口")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<List<TaskWorkEntries.Entry>> list(@RequestBody JsonNode body) {
        return Result.success(
                service.entries(requests.design(body, TaskCenter.Ref.class).id(), access.actor()));
    }

    @PostMapping("/page")
    @Operation(summary = "分页读取入口业务及贡献来源")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<PageResult<TaskWorkEntries.Item>> page(@RequestBody JsonNode body) {
        return Result.success(
                service.page(requests.design(body, TaskWorkEntries.Query.class), access.actor()));
    }

    @PostMapping("/form")
    @Operation(summary = "打开固定版本入口表单")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskCenter.FormContext> form(@RequestBody JsonNode body) {
        return Result.success(
                service.form(requests.design(body, TaskWorkEntries.Form.class), access.actor()));
    }

    @PostMapping("/receipt")
    @Operation(summary = "恢复本人反馈提交的真实收据")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskWorkEntries.Saved> receipt(@RequestBody JsonNode body) {
        return Result.success(
                service.receipt(
                        requests.design(body, TaskWorkEntries.Receipt.class), access.actor()));
    }

    @PostMapping("/save")
    @Operation(summary = "提交入口业务反馈")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskWorkEntries.Saved> save(@RequestBody JsonNode body) {
        return Result.success(
                service.save(requests.design(body, TaskWorkEntries.Save.class), access.actor()));
    }

    @PostMapping("/link")
    @Operation(summary = "明确关联现有业务记录为当前任务贡献")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskWorkEntries.Saved> link(@RequestBody JsonNode body) {
        return Result.success(
                service.link(requests.design(body, TaskWorkEntries.Link.class), access.actor()));
    }

    @PostMapping("/materials")
    @Operation(summary = "读取完成时固定的各入口提交材料")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<List<TaskWorkEntries.Material>> materials(@RequestBody JsonNode body) {
        return Result.success(
                service.materials(
                        requests.design(body, TaskCenter.Ref.class).id(), access.actor()));
    }

    @PostMapping("/delete")
    @Operation(summary = "在总任务授权范围内删除业务记录并记录真实操作来源")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Boolean> delete(@RequestBody JsonNode body) {
        return Result.success(
                service.delete(
                        requests.design(body, TaskWorkEntries.Delete.class), access.actor()));
    }

    @PostMapping("/selection")
    @Operation(summary = "读取入口表单字段候选")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<SelectionFields.Result> selection(@RequestBody JsonNode body) {
        return Result.success(
                service.selection(
                        requests.design(body, TaskWorkEntries.Selection.class), access.actor()));
    }

    @PostMapping("/field-rules")
    @Operation(summary = "在任务授权范围内计算表单字段规则")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<FieldRules.Evaluation> fieldRules(@RequestBody JsonNode body) {
        return Result.success(
                service.fieldRules(
                        requests.design(body, TaskWorkEntries.FieldRules.class), access.actor()));
    }

    @PostMapping("/related-field-rules")
    @Operation(summary = "在任务关联表单范围内计算字段规则")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<FieldRules.Evaluation> relatedFieldRules(@RequestBody JsonNode body) {
        return Result.success(
                service.relatedFieldRules(
                        requests.design(body, TaskWorkEntries.RelatedFieldRules.class),
                        access.actor()));
    }

    @PostMapping("/fill")
    @Operation(summary = "读取入口表单字段带入")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Map<String, Object>> fill(@RequestBody JsonNode body) {
        return Result.success(
                service.fill(requests.design(body, TaskWorkEntries.Fill.class), access.actor()));
    }

    @PostMapping("/related")
    @Operation(summary = "读取入口关联表单")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<RelatedForms.Result> related(@RequestBody JsonNode body) {
        return Result.success(
                service.related(
                        requests.design(body, TaskWorkEntries.Related.class), access.actor()));
    }

    @PostMapping("/related-selection")
    @Operation(summary = "读取入口关联候选")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<SelectionFields.Result> relatedSelection(@RequestBody JsonNode body) {
        return Result.success(
                service.relatedSelection(
                        requests.design(body, TaskWorkEntries.RelatedSelection.class),
                        access.actor()));
    }

    @PostMapping("/related-fill")
    @Operation(summary = "读取入口关联带入")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Map<String, Object>> relatedFill(@RequestBody JsonNode body) {
        return Result.success(
                service.relatedFill(
                        requests.design(body, TaskWorkEntries.RelatedFill.class), access.actor()));
    }
}
