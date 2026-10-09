package com.lingan.ucp.nocode.controller.admin.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.pojo.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.report.service.dashboard.ReportDashboardService;
import com.lingan.ucp.nocode.runtime.service.report.ReportDashboardQueryService;
import com.lingan.ucp.nocode.web.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 独立仪表板设计和运行，不创建或借用应用 REPORT 资源。 */
@Tag(name = "无代码 - 仪表板")
@RestController
@RequestMapping("/nocode/report/dashboard")
@PreAuthorize("isAuthenticated()")
public class ReportDashboardController {
    @Resource private ReportDashboardService dashboards;
    @Resource private ReportDashboardQueryService queries;
    @Resource private NocodeAccess access;
    @Resource private RecordExcelService excel;
    @Resource private StrictRequestDecoder requests;

    @GetMapping("/page")
    @Operation(summary = "分页查询我的仪表板")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<PageResult<ReportDashboards.Detail>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search) {
        return Result.success(dashboards.page(pageNo, pageSize, search, access.actor()));
    }

    @GetMapping("/available-page")
    @Operation(summary = "分页查询可访问仪表板摘要")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<PageResult<ReportDashboards.AvailableItem>> availablePage(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String folderId,
            @RequestParam(required = false) String status) {
        return Result.success(
                dashboards.availablePage(
                        pageNo, pageSize, search, folderId, status, access.actor()));
    }

    @GetMapping("/resource-policy")
    @Operation(summary = "读取仪表板资源协作权限")
    @PreAuthorize("@nocodeAccess.has('nocode:report:manage')")
    public Result<ReportAuthorization.ResourcePolicy> resourcePolicy(@RequestParam String id) {
        return Result.success(dashboards.resourcePolicy(id, access.actor()));
    }

    @PostMapping("/resource-policy")
    @Operation(summary = "保存仪表板资源协作权限")
    @PreAuthorize("@nocodeAccess.has('nocode:report:manage')")
    public Result<ReportAuthorization.ResourcePolicy> saveResourcePolicy(
            @RequestBody JsonNode body) {
        return Result.success(
                dashboards.saveResource(
                        requests.design(body, ReportAuthorization.SaveDashboardResource.class),
                        access.actor()));
    }

    @GetMapping("/get")
    @Operation(summary = "读取仪表板草稿")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<ReportDashboards.Detail> get(@RequestParam String id) {
        return Result.success(dashboards.get(id, access.actor()));
    }

    @PostMapping("/save")
    @Operation(summary = "保存仪表板草稿")
    @PreAuthorize(
            "@nocodeAccess.has('nocode:report:update') ||"
                    + " @nocodeAccess.has('nocode:report:create')")
    public Result<ReportDashboards.Detail> save(@RequestBody JsonNode body) {
        return Result.success(
                dashboards.save(
                        requests.design(body, ReportDashboards.Save.class), access.actor()));
    }

    @PostMapping("/publish")
    @Operation(summary = "发布仪表板固定版本")
    @PreAuthorize("@nocodeAccess.has('nocode:report:publish')")
    public Result<ReportDashboards.Release> publish(@RequestBody JsonNode body) {
        return Result.success(
                dashboards.publish(
                        requests.design(body, ReportDashboards.Publish.class), access.actor()));
    }

    @PostMapping("/copy")
    @Operation(summary = "复制仪表板为新草稿")
    @PreAuthorize("@nocodeAccess.has('nocode:report:create')")
    public Result<ReportDashboards.Detail> copy(@RequestBody JsonNode body) {
        return Result.success(
                dashboards.copy(
                        requests.design(body, ReportDashboards.Copy.class), access.actor()));
    }

    @PostMapping("/move")
    @Operation(summary = "移动仪表板分类目录")
    @PreAuthorize("@nocodeAccess.has('nocode:report:update')")
    public Result<ReportDashboards.Detail> move(@RequestBody JsonNode body) {
        return Result.success(
                dashboards.move(
                        requests.design(body, ReportDashboards.Move.class), access.actor()));
    }

    @PostMapping("/status")
    @Operation(summary = "启用或停用仪表板全部版本")
    @PreAuthorize("@nocodeAccess.has('nocode:report:manage')")
    public Result<ReportDashboards.Detail> status(@RequestBody JsonNode body) {
        return Result.success(
                dashboards.status(
                        requests.design(body, ReportDashboards.ChangeStatus.class),
                        access.actor()));
    }

    @PostMapping("/restore")
    @Operation(summary = "恢复历史仪表板配置为新草稿")
    @PreAuthorize("@nocodeAccess.has('nocode:report:update')")
    public Result<ReportDashboards.Detail> restore(@RequestBody JsonNode body) {
        return Result.success(
                dashboards.restore(
                        requests.design(body, ReportDashboards.Restore.class), access.actor()));
    }

    @GetMapping("/releases")
    @Operation(summary = "分页查询仪表板历史发布配置")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<PageResult<ReportDashboards.Release>> releases(
            @RequestParam String id,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(dashboards.releases(id, pageNo, pageSize, access.actor()));
    }

    @GetMapping("/delete-preview")
    @Operation(summary = "预检仪表板全部固定引用")
    @PreAuthorize("@nocodeAccess.has('nocode:report:manage')")
    public Result<ReportDashboards.DeletePreview> deletePreview(@RequestParam String id) {
        return Result.success(dashboards.deletePreview(id, access.actor()));
    }

    @PostMapping("/delete")
    @Operation(summary = "解除引用后逻辑删除仪表板")
    @PreAuthorize("@nocodeAccess.has('nocode:report:manage')")
    public Result<ReportDashboards.Deleted> delete(@RequestBody JsonNode body) {
        return Result.success(
                dashboards.delete(
                        requests.design(body, ReportDashboards.Delete.class), access.actor()));
    }

    @GetMapping("/published")
    @Operation(summary = "读取已发布仪表板")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<ReportDashboards.Release> published(
            @RequestParam String id,
            @RequestParam(required = false) Integer versionNo,
            @RequestParam(required = false) String checksum) {
        return Result.success(dashboards.published(id, versionNo, checksum, access.actor()));
    }

    @PostMapping("/chart-preview")
    @Operation(summary = "按固定数据集与当前制作权限预览未保存的单图配置")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<ApplicationReports.Result> previewChart(@RequestBody JsonNode body) {
        return Result.success(
                queries.previewChart(
                        requests.design(body, ReportDashboards.Chart.class), access.actor()));
    }

    @PostMapping("/query")
    @Operation(summary = "查询仪表板固定组件")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<ApplicationReports.Result> query(@RequestBody JsonNode body) {
        return Result.success(
                queries.query(requests.design(body, ReportDashboards.Query.class), access.actor()));
    }

    @PostMapping("/options")
    @Operation(summary = "查询公共筛选受控候选")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<ReportDatasetQueries.OptionPage> options(@RequestBody JsonNode body) {
        return Result.success(
                queries.options(
                        requests.design(body, ReportDashboards.Options.class), access.actor()));
    }

    @PostMapping("/details")
    @Operation(summary = "查询独立图表只读明细")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<ReportDashboards.DetailPage> details(@RequestBody JsonNode body) {
        return Result.success(
                queries.details(
                        requests.design(body, ReportDashboards.Details.class), access.actor()));
    }

    @PostMapping("/export")
    @Operation(summary = "导出独立图表 Excel")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public org.springframework.http.ResponseEntity<byte[]> export(@RequestBody JsonNode body) {
        byte[] bytes =
                excel.reportResult(
                        queries.export(
                                requests.design(body, ReportDashboards.Query.class),
                                access.actor()));
        return org.springframework.http.ResponseEntity.ok()
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=report.xlsx")
                .contentType(
                        org.springframework.http.MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
