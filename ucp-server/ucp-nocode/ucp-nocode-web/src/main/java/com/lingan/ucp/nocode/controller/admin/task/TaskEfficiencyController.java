package com.lingan.ucp.nocode.controller.admin.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.pojo.*;
import com.lingan.ucp.nocode.api.TaskEfficiency.*;
import com.lingan.ucp.nocode.runtime.service.taskcenter.TaskEfficiencyService;
import com.lingan.ucp.nocode.web.NocodeAccess;
import com.lingan.ucp.nocode.web.StrictRequestDecoder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 任务能效统计使用任务管理范围；HTTP菜单权限不能代替服务端实例范围复核。 */
@Tag(name = "无代码 - 任务能效统计")
@RestController
@RequestMapping("/nocode/tasks/efficiency")
@PreAuthorize("isAuthenticated() and @nocodeAccess.taskQuery()")
public class TaskEfficiencyController {
    @Resource private TaskEfficiencyService efficiency;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @PostMapping("/overview")
    @Operation(summary = "查询管理范围的标准工时与任务概览")
    public Result<Overview> overview(@RequestBody JsonNode body) {
        return Result.success(
                efficiency.overview(requests.design(body, Query.class), access.actor()));
    }

    @PostMapping("/employees")
    @Operation(summary = "分页查询员工标准工时与任务统计")
    public Result<PageResult<Employee>> employees(@RequestBody JsonNode body) {
        return Result.success(
                efficiency.employees(requests.design(body, Query.class), access.actor()));
    }

    @PostMapping("/tasks")
    @Operation(summary = "分页查询整组任务计量和当前进度")
    public Result<PageResult<Task>> tasks(@RequestBody JsonNode body) {
        return Result.success(efficiency.tasks(requests.design(body, Query.class), access.actor()));
    }

    @PostMapping("/records")
    @Operation(summary = "分页追溯去重后的有效标准工时贡献")
    public Result<PageResult<com.lingan.ucp.nocode.api.TaskEfficiency.Record>> records(
            @RequestBody JsonNode body) {
        return Result.success(
                efficiency.records(requests.design(body, Query.class), access.actor()));
    }

    @PostMapping("/options")
    @Operation(summary = "查询管理范围内的统计员工和模板候选")
    public Result<Options> options(@RequestBody JsonNode body) {
        return Result.success(
                efficiency.options(requests.design(body, Query.class), access.actor()));
    }
}
