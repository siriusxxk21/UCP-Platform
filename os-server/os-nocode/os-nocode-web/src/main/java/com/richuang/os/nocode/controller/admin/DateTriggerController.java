package com.richuang.os.nocode.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.nocode.api.DateTriggerRuns;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.application.service.resource.ApplicationDateTriggers;
import com.richuang.os.nocode.runtime.job.application.DateTriggerScheduler;
import com.richuang.os.nocode.web.NocodeAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 业务动作「按日期自动执行」的运行情况与立即执行；只给应用创建人与平台管理员。 */
@Tag(name = "无代码 - 按日期自动执行")
@RestController
@RequestMapping("/nocode/application/date-trigger")
public class DateTriggerController {
    @Resource private ApplicationService applications;
    @Resource private ApplicationDateTriggers ledger;
    @Resource private DateTriggerScheduler scheduler;
    @Resource private NocodeAccess access;
    @Resource private com.richuang.os.nocode.web.StrictRequestDecoder requests;

    @GetMapping("/status")
    @Operation(summary = "查询应用中按日期自动执行的业务动作的最近执行结果")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<List<DateTriggerRuns.Status>> status(@RequestParam String id) {
        applications.requireDesigner(id, access.actor());
        return Result.success(ledger.status(id));
    }

    @PostMapping("/run")
    @Operation(summary = "立即按今天执行一条按日期自动执行的业务动作")
    @PreAuthorize(
            "@nocodeAccess.has('nocode:app:query') && @nocodeAccess.has('nocode:app:publish')")
    public Result<DateTriggerRuns.Result> run(@RequestBody JsonNode body) {
        var command = requests.application(body, DateTriggerRuns.Run.class);
        applications.requireDesigner(command.id(), access.actor());
        return Result.success(scheduler.runNow(command.id(), command.resourceId()));
    }
}
