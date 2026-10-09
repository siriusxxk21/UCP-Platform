package com.lingan.ucp.nocode.controller.admin.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.pojo.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.report.service.authorization.ReportDatasetAuthorizationService;
import com.lingan.ucp.nocode.report.service.dataset.ReportDatasetCatalogService;
import com.lingan.ucp.nocode.report.service.dataset.ReportDatasetService;
import com.lingan.ucp.nocode.runtime.service.report.ReportDatasetQueryService;
import com.lingan.ucp.nocode.web.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 独立报表数据集入口；操作者来自 OS 会话，配置、资源 ACL 与业务数据授权由服务分别检查。 */
@Tag(name = "无代码 - 报表数据集")
@RestController
@RequestMapping("/nocode/report/dataset")
@PreAuthorize("isAuthenticated()")
public class ReportDatasetController {
    @Resource private ReportDatasetService datasets;
    @Resource private ReportDatasetCatalogService catalog;
    @Resource private ReportDatasetAuthorizationService authorization;
    @Resource private ReportDatasetQueryService queries;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @GetMapping("/source-objects")
    @Operation(summary = "分页发现可用于报表的已发布对象")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<PageResult<ReportDatasetCatalog.ObjectItem>> sourceObjects(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search) {
        return Result.success(catalog.objects(pageNo, pageSize, search, access.actor()));
    }

    @GetMapping("/source-object")
    @Operation(summary = "读取对象发布版本的分析字段与关系候选")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<ReportDatasetCatalog.ObjectVersion> sourceObject(
            @RequestParam String id, @RequestParam(required = false) Integer versionNo) {
        return Result.success(catalog.object(id, versionNo, access.actor()));
    }

    @GetMapping("/authorization-targets")
    @Operation(summary = "供对象共享管理员定位数据集授权目标")
    @PreAuthorize("@nocodeAccess.has('nocode:object:share')")
    public Result<PageResult<ReportDatasetCatalog.AuthorizationTarget>> authorizationTargets(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search) {
        return Result.success(
                catalog.authorizationTargets(pageNo, pageSize, search, access.actor()));
    }

    @GetMapping("/authorization-objects")
    @Operation(summary = "读取对象管理员可授权或撤销的来源对象字段")
    @PreAuthorize("@nocodeAccess.has('nocode:object:share')")
    public Result<List<ReportDatasetCatalog.AuthorizationObject>> authorizationObjects(
            @RequestParam String id) {
        return Result.success(catalog.authorizationObjects(id, access.actor()));
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询可查看的数据集")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<PageResult<ReportDatasets.Detail>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String folderId) {
        return Result.success(datasets.page(pageNo, pageSize, search, folderId, access.actor()));
    }

    @GetMapping("/get")
    @Operation(summary = "读取数据集草稿及发布状态")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<ReportDatasets.Detail> get(@RequestParam String id) {
        return Result.success(datasets.get(id, access.actor()));
    }

    @PostMapping("/move")
    @Operation(summary = "移动数据集分类但不改变发布内容与授权")
    @PreAuthorize(
            "@nocodeAccess.has('nocode:report:update') && @nocodeAccess.has('nocode:report:query')")
    public Result<ReportDatasets.Detail> move(@RequestBody JsonNode body) {
        return Result.success(
                datasets.move(requests.design(body, ReportDatasets.Move.class), access.actor()));
    }

    @PostMapping("/copy")
    @Operation(summary = "复制数据集草稿但不复制授权或发布状态")
    @PreAuthorize(
            "@nocodeAccess.has('nocode:report:create') && @nocodeAccess.has('nocode:report:query')")
    public Result<ReportDatasets.Detail> copy(@RequestBody JsonNode body) {
        return Result.success(
                datasets.copy(requests.design(body, ReportDatasets.Copy.class), access.actor()));
    }

    @GetMapping("/delete-preview")
    @Operation(summary = "检查数据集删除权限及所有版本引用")
    @PreAuthorize("@nocodeAccess.has('nocode:report:manage')")
    public Result<ReportDatasets.DeletePreview> deletePreview(@RequestParam String id) {
        return Result.success(datasets.deletePreview(id, access.actor()));
    }

    @PostMapping("/delete")
    @Operation(summary = "重新检查引用并逻辑删除数据集")
    @PreAuthorize("@nocodeAccess.has('nocode:report:manage')")
    public Result<ReportDatasets.Deleted> delete(@RequestBody JsonNode body) {
        return Result.success(
                datasets.delete(
                        requests.design(body, ReportDatasets.Delete.class), access.actor()));
    }

    @PostMapping("/save")
    @Operation(summary = "保存数据集草稿")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<ReportDatasets.Detail> save(@RequestBody JsonNode body) {
        return Result.success(
                datasets.save(requests.design(body, ReportDatasets.Save.class), access.actor()));
    }

    @PostMapping("/publish")
    @Operation(summary = "发布数据集固定版本")
    @PreAuthorize("@nocodeAccess.has('nocode:report:publish')")
    public Result<ReportDatasets.Release> publish(@RequestBody JsonNode body) {
        return Result.success(
                datasets.publish(
                        requests.design(body, ReportDatasets.Publish.class), access.actor()));
    }

    @GetMapping("/releases")
    @Operation(summary = "分页查询数据集发布记录")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<PageResult<ReportDatasets.Release>> releases(
            @RequestParam String id,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(datasets.releases(id, pageNo, pageSize, access.actor()));
    }

    @PostMapping("/restore")
    @Operation(summary = "将历史数据集版本恢复为草稿")
    @PreAuthorize("@nocodeAccess.has('nocode:report:update')")
    public Result<ReportDatasets.Detail> restore(@RequestBody JsonNode body) {
        return Result.success(
                datasets.restore(
                        requests.design(body, ReportDatasets.Restore.class), access.actor()));
    }

    @PostMapping("/status")
    @Operation(summary = "启用或停用数据集")
    @PreAuthorize("@nocodeAccess.has('nocode:report:manage')")
    public Result<ReportDatasets.Detail> status(@RequestBody JsonNode body) {
        return Result.success(
                datasets.status(
                        requests.design(body, ReportDatasets.ChangeStatus.class), access.actor()));
    }

    @GetMapping("/resource-policy")
    @Operation(summary = "读取数据集资源协作权限")
    @PreAuthorize("@nocodeAccess.has('nocode:report:manage')")
    public Result<ReportAuthorization.ResourcePolicy> resourcePolicy(@RequestParam String id) {
        return Result.success(authorization.resourcePolicy(id, access.actor()));
    }

    @PostMapping("/resource-policy")
    @Operation(summary = "保存数据集资源协作权限")
    @PreAuthorize("@nocodeAccess.has('nocode:report:manage')")
    public Result<ReportAuthorization.ResourcePolicy> saveResourcePolicy(
            @RequestBody JsonNode body) {
        return Result.success(
                authorization.saveResource(
                        requests.design(body, ReportAuthorization.SaveResource.class),
                        access.actor()));
    }

    @GetMapping("/ceilings")
    @Operation(summary = "读取授予数据集的对象上限")
    @PreAuthorize(
            "@nocodeAccess.has('nocode:report:query') || @nocodeAccess.has('nocode:object:share')")
    public Result<List<ReportAuthorization.ObjectCeiling>> ceilings(@RequestParam String id) {
        return Result.success(authorization.ceilings(id, access.actor()));
    }

    @PostMapping("/ceiling")
    @Operation(summary = "保存或撤销数据集对象共享上限")
    @PreAuthorize("@nocodeAccess.has('nocode:object:share')")
    public Result<ReportAuthorization.ObjectCeiling> saveCeiling(@RequestBody JsonNode body) {
        return Result.success(
                authorization.saveCeiling(
                        requests.design(body, ReportAuthorization.SaveCeiling.class),
                        access.actor()));
    }

    @GetMapping("/data-policy")
    @Operation(summary = "读取数据集成员数据策略")
    @PreAuthorize("@nocodeAccess.has('nocode:report:authorize')")
    public Result<ReportAuthorization.DataPolicy> dataPolicy(@RequestParam String id) {
        return Result.success(authorization.dataPolicy(id, access.actor()));
    }

    @PostMapping("/data-policy")
    @Operation(summary = "保存数据集成员数据策略")
    @PreAuthorize("@nocodeAccess.has('nocode:report:authorize')")
    public Result<ReportAuthorization.DataPolicy> saveDataPolicy(@RequestBody JsonNode body) {
        return Result.success(
                authorization.saveDataPolicy(
                        requests.design(body, ReportAuthorization.SaveDataPolicy.class),
                        access.actor()));
    }

    @PostMapping("/query")
    @Operation(summary = "按当前数据权限预览或查询固定版本数据集")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<ApplicationReports.Result> query(@RequestBody JsonNode body) {
        return Result.success(
                queries.query(
                        requests.design(body, ReportDatasetQueries.Query.class), access.actor()));
    }

    @PostMapping("/options")
    @Operation(summary = "查询数据集实时授权范围内的筛选候选")
    @PreAuthorize("@nocodeAccess.has('nocode:report:query')")
    public Result<ReportDatasetQueries.OptionPage> options(@RequestBody JsonNode body) {
        return Result.success(
                queries.options(
                        requests.design(body, ReportDatasetQueries.Options.class), access.actor()));
    }
}
