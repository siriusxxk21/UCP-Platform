package com.richuang.os.nocode.controller.admin.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.runtime.service.taskcenter.TaskFormRuntimeService;
import com.richuang.os.nocode.web.NocodeAccess;
import com.richuang.os.nocode.web.StrictRequestDecoder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 任务表单辅助查询，身份来自登录态；固定版本由任务服务解析。 */
@Tag(name = "无代码 - 任务表单辅助")
@RestController
@RequestMapping("/nocode/tasks/form-runtime")
@PreAuthorize("isAuthenticated()")
public class TaskFormController {
    @Resource private TaskFormRuntimeService forms;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @PostMapping("/receipt")
    @Operation(summary = "读取本人当前任务业务提交回执")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<BusinessHandling.Result> receipt(@RequestBody JsonNode body) {
        return Result.success(
                forms.receipt(requests.design(body, TaskForms.Receipt.class), access.actor()));
    }

    @PostMapping("/create-receipt")
    @Operation(summary = "恢复本人一并发起任务与业务的提交回执")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TaskForms.CreatedReceipt> createReceipt(@RequestBody JsonNode body) {
        return Result.success(
                forms.createReceipt(
                        requests.design(body, TaskForms.CreateReceipt.class), access.actor()));
    }

    @PostMapping("/handling-task")
    @Operation(summary = "定位本人审批申请关联的任务")
    @PreAuthorize("isAuthenticated()")
    public Result<String> handlingTask(@RequestBody JsonNode body) {
        return Result.success(
                forms.handlingTask(
                        requests.design(body, TaskCenter.Ref.class).id(), access.actor()));
    }

    @PostMapping("/selection")
    @Operation(summary = "查询任务表单字段候选")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<SelectionFields.Result> selection(@RequestBody JsonNode body) {
        return Result.success(
                forms.selection(requests.design(body, TaskForms.Selection.class), access.actor()));
    }

    @PostMapping("/field-rules")
    @Operation(summary = "计算任务固定表单的字段规则")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<FieldRules.Evaluation> fieldRules(@RequestBody JsonNode body) {
        return Result.success(
                forms.fieldRules(
                        requests.design(body, TaskForms.FieldRules.class), access.actor()));
    }

    @PostMapping("/related-field-rules")
    @Operation(summary = "计算任务固定关联表单的字段规则")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<FieldRules.Evaluation> relatedFieldRules(@RequestBody JsonNode body) {
        return Result.success(
                forms.relatedFieldRules(
                        requests.design(body, TaskForms.RelatedFieldRules.class), access.actor()));
    }

    @PostMapping("/form-fill")
    @Operation(summary = "查询任务表单带入值")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Map<String, Object>> fill(@RequestBody JsonNode body) {
        return Result.success(
                forms.fill(requests.design(body, TaskForms.Fill.class), access.actor()));
    }

    @PostMapping("/related-form")
    @Operation(summary = "读取任务关联表单")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<RelatedForms.Result> related(@RequestBody JsonNode body) {
        return Result.success(
                forms.related(requests.design(body, TaskForms.Related.class), access.actor()));
    }

    @PostMapping("/related-selection")
    @Operation(summary = "查询任务关联表单候选")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<SelectionFields.Result> relatedSelection(@RequestBody JsonNode body) {
        return Result.success(
                forms.relatedSelection(
                        requests.design(body, TaskForms.RelatedSelection.class), access.actor()));
    }

    @PostMapping("/related-fill")
    @Operation(summary = "查询任务关联表单带入值")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<Map<String, Object>> relatedFill(@RequestBody JsonNode body) {
        return Result.success(
                forms.relatedFill(
                        requests.design(body, TaskForms.RelatedFill.class), access.actor()));
    }
}
