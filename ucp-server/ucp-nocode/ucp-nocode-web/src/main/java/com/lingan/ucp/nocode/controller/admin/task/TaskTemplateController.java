package com.lingan.ucp.nocode.controller.admin.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.pojo.*;
import com.lingan.ucp.nocode.api.TaskCenter.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskCenterService;
import com.lingan.ucp.nocode.web.NocodeAccess;
import com.lingan.ucp.nocode.web.StrictRequestDecoder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 模板草稿与不可变发布版本入口。 */
@Tag(name = "无代码 - 任务模板")
@RestController
@RequestMapping("/nocode/task-templates")
@PreAuthorize("isAuthenticated()")
public class TaskTemplateController {
    @Resource private TaskCenterService tasks;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @GetMapping("/list")
    @Operation(summary = "查询可用任务模板")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<List<Template>> list() {
        return Result.success(tasks.templates(access.actor()));
    }

    @PostMapping("/instances")
    @Operation(summary = "分页查询模板产生的可见任务实例")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<PageResult<TemplateInstance>> instances(@RequestBody JsonNode body) {
        return Result.success(
                tasks.templateInstances(
                        requests.design(body, TemplateInstances.class), access.actor()));
    }

    @GetMapping("/version")
    @Operation(summary = "读取任务模板发布版本")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<TemplateVersion> version(
            @RequestParam String id, @RequestParam(required = false) Integer version) {
        return Result.success(tasks.version(id, version, access.actor()));
    }

    @GetMapping("/versions")
    @Operation(summary = "查询任务模板全部已发布版本")
    @PreAuthorize("@nocodeAccess.taskQuery()")
    public Result<List<TemplateVersionSummary>> versions(@RequestParam String id) {
        return Result.success(tasks.versions(id, access.actor()));
    }

    @PostMapping("/primary")
    @Operation(summary = "切换任务模板主版本")
    @PreAuthorize("@nocodeAccess.taskTemplate()")
    public Result<Template> primary(@RequestBody JsonNode body) {
        return Result.success(
                tasks.setPrimaryVersion(
                        requests.design(body, SetPrimaryTemplateVersion.class), access.actor()));
    }

    @PostMapping("/save")
    @Operation(summary = "保存任务模板草稿")
    @PreAuthorize("@nocodeAccess.taskTemplate()")
    public Result<Template> save(@RequestBody JsonNode body) {
        return Result.success(
                tasks.saveTemplate(requests.design(body, SaveTemplate.class), access.actor()));
    }

    @PostMapping("/publish")
    @Operation(summary = "发布任务模板版本")
    @PreAuthorize("@nocodeAccess.taskTemplate()")
    public Result<TemplateVersion> publish(@RequestBody JsonNode body) {
        return Result.success(
                tasks.publish(requests.design(body, PublishTemplate.class), access.actor()));
    }
}
