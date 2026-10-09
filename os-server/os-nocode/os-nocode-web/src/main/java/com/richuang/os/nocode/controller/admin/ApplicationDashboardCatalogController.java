package com.richuang.os.nocode.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.nocode.api.ApplicationDashboards;
import com.richuang.os.nocode.api.ReportCatalogApi;
import com.richuang.os.nocode.web.NocodeAccess;
import com.richuang.os.nocode.web.StrictRequestDecoder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 应用设计器只读目录；候选按当前VIEW过滤，固定版本校验不产生业务授权。 */
@RestController
@RequestMapping("/nocode/application")
@Tag(name = "无代码 - 应用固定仪表板目录")
@PreAuthorize("isAuthenticated()")
public class ApplicationDashboardCatalogController {
    @Resource private ReportCatalogApi catalog;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @GetMapping("/dashboard-catalog-page")
    @Operation(summary = "发现当前可引用的已发布仪表板")
    @PreAuthorize(
            "@nocodeAccess.has('nocode:app:create') || @nocodeAccess.has('nocode:app:update')")
    public Result<PageResult<ApplicationDashboards.Candidate>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search) {
        return Result.success(catalog.page(pageNo, pageSize, search, access.actor()));
    }

    @PostMapping("/dashboard-catalog")
    @Operation(summary = "校验应用显式对象引用与固定仪表板版本")
    @PreAuthorize(
            "@nocodeAccess.has('nocode:app:create') || @nocodeAccess.has('nocode:app:update')")
    public Result<ApplicationDashboards.Catalog> fixed(@RequestBody JsonNode body) {
        ApplicationDashboards.CatalogRequest request =
                requests.application(body, ApplicationDashboards.CatalogRequest.class);
        return Result.success(
                catalog.fixed(request.reference(), request.objects(), access.actor()));
    }
}
