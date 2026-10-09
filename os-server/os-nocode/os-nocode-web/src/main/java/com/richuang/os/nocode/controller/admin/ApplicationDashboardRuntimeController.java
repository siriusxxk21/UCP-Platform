package com.richuang.os.nocode.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.nocode.api.*;
import com.richuang.os.nocode.runtime.service.report.ApplicationDashboardRuntimeService;
import com.richuang.os.nocode.web.NocodeAccess;
import com.richuang.os.nocode.web.RecordExcelService;
import com.richuang.os.nocode.web.StrictRequestDecoder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 普通应用成员通过应用、看板与数据双层授权运行，不要求报表中心菜单权限。 */
@RestController
@RequestMapping("/nocode/runtime")
@Tag(name = "无代码 - 应用固定看板运行")
@PreAuthorize("isAuthenticated()")
public class ApplicationDashboardRuntimeController {
    @Resource private ApplicationDashboardRuntimeService dashboards;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;
    @Resource private RecordExcelService excel;

    @GetMapping("/dashboard")
    @Operation(summary = "读取应用固定看板模型")
    public Result<ApplicationDashboards.Model> model(
            @RequestParam String applicationId, @RequestParam String resourceId) {
        return Result.success(dashboards.model(applicationId, resourceId, access.actor()));
    }

    @PostMapping("/dashboard-query")
    @Operation(summary = "查询应用固定看板组件")
    public Result<ApplicationReports.Result> query(@RequestBody JsonNode body) {
        return Result.success(
                dashboards.query(
                        requests.runtime(body, ApplicationDashboards.Query.class), access.actor()));
    }

    @PostMapping("/dashboard-options")
    @Operation(summary = "查询应用固定看板受控筛选候选")
    public Result<ReportDatasetQueries.OptionPage> options(@RequestBody JsonNode body) {
        return Result.success(
                dashboards.options(
                        requests.runtime(body, ApplicationDashboards.Options.class),
                        access.actor()));
    }

    @PostMapping("/dashboard-details")
    @Operation(summary = "查询应用固定看板只读明细")
    public Result<ReportDashboards.DetailPage> details(@RequestBody JsonNode body) {
        return Result.success(
                dashboards.details(
                        requests.runtime(body, ApplicationDashboards.Details.class),
                        access.actor()));
    }

    @PostMapping("/dashboard-export")
    @Operation(summary = "导出应用固定看板组件")
    public ResponseEntity<byte[]> export(@RequestBody JsonNode body) {
        ApplicationReports.Result result =
                dashboards.export(
                        requests.runtime(body, ApplicationDashboards.Query.class), access.actor());
        return ResponseEntity.ok()
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=report.xlsx")
                .contentType(
                        org.springframework.http.MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel.reportResult(result));
    }
}
