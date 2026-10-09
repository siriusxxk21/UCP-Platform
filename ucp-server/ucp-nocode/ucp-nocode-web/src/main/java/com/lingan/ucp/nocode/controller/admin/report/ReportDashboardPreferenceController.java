package com.lingan.ucp.nocode.controller.admin.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.pojo.*;
import com.lingan.ucp.nocode.api.ReportDashboardPreferences;
import com.lingan.ucp.nocode.report.service.preference.ReportDashboardPreferenceService;
import com.lingan.ucp.nocode.web.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 当前登录用户的个人偏好；不接受客户端指定用户身份或资源授权。 */
@Tag(name = "无代码 - 仪表板工作台")
@RestController
@RequestMapping("/nocode/report/dashboard")
@PreAuthorize("isAuthenticated() && @nocodeAccess.has('nocode:report:query')")
public class ReportDashboardPreferenceController {
    @Resource private ReportDashboardPreferenceService preferences;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @GetMapping("/preference-page")
    @Operation(summary = "分页查询当前可查看的仪表板及个人分类")
    public Result<PageResult<ReportDashboardPreferences.Item>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "ALL") String view) {
        return Result.success(preferences.page(pageNo, pageSize, search, view, access.actor()));
    }

    @GetMapping("/preference")
    @Operation(summary = "读取当前用户的看板收藏与最近访问状态")
    public Result<ReportDashboardPreferences.State> get(@RequestParam String id) {
        return Result.success(preferences.get(id, access.actor()));
    }

    @PostMapping("/favorite")
    @Operation(summary = "幂等设置当前用户的看板收藏")
    public Result<ReportDashboardPreferences.State> favorite(@RequestBody JsonNode body) {
        return Result.success(
                preferences.favorite(
                        requests.design(body, ReportDashboardPreferences.SetFavorite.class),
                        access.actor()));
    }

    @PostMapping("/visit")
    @Operation(summary = "记录当前用户成功进入可访问看板")
    public Result<ReportDashboardPreferences.State> visit(@RequestBody JsonNode body) {
        return Result.success(
                preferences.visit(
                        requests.design(body, ReportDashboardPreferences.Visit.class),
                        access.actor()));
    }
}
