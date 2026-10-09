package com.lingan.ucp.nocode.controller.admin;

import com.fasterxml.jackson.databind.*;
import com.lingan.ucp.framework.common.pojo.*;
import com.lingan.ucp.nocode.api.ApplicationCenter;
import com.lingan.ucp.nocode.api.ApplicationRecords.*;
import com.lingan.ucp.nocode.api.RelatedForms;
import com.lingan.ucp.nocode.application.service.application.ApplicationService;
import com.lingan.ucp.nocode.metadata.service.request.ReadRequestMemo;
import com.lingan.ucp.nocode.runtime.service.access.ApplicationRuntimePolicy;
import com.lingan.ucp.nocode.runtime.service.application.ApplicationRuntimeService;
import com.lingan.ucp.nocode.runtime.service.record.RecordService;
import com.lingan.ucp.nocode.runtime.service.report.ApplicationReportService;
import com.lingan.ucp.nocode.runtime.service.view.DataViewService;
import com.lingan.ucp.nocode.web.NocodeAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 应用运行通过底座登录后叠加应用授权；用户不能通过设计菜单权限读取业务数据。
 *
 * <p>只读接口（应用定义、模型、候选、规则求值、分页、视图、统计、整单读取）在 ReadRequestMemo
 * 作用域内执行：同一次请求里重复读取的发布定义、授权上限和物理表结构只查一次。保存、删除、动作、导入、导出等接口不开启作用域。
 */
@Tag(name = "无代码 - 应用运行")
@RestController
@RequestMapping("/nocode/runtime")
@PreAuthorize("isAuthenticated()")
public class ApplicationRuntimeController {
    @Resource private ApplicationService applications;
    @Resource private ApplicationRuntimeService runtime;
    @Resource private ApplicationRuntimePolicy policy;
    @Resource private RecordService records;
    @Resource private com.lingan.ucp.nocode.runtime.service.record.RelatedFormService relatedForms;

    @PostMapping("/related-form")
    @Operation(summary = "读取关联表单区域")
    public Result<RelatedForms.Result> relatedForm(@RequestBody RelatedForms.Query query) {
        return Result.success(relatedForms.query(query, access.actor()));
    }

    @PostMapping("/related-selection")
    @Operation(summary = "查询关联表单候选")
    public Result<com.lingan.ucp.nocode.api.SelectionFields.Result> relatedSelection(
            @RequestBody RelatedForms.Selection query) {
        return Result.success(relatedForms.selection(query, access.actor()));
    }

    @PostMapping("/related-fill")
    @Operation(summary = "读取关联表单带入值")
    public Result<java.util.Map<String, Object>> relatedFill(@RequestBody RelatedForms.Fill query) {
        return Result.success(relatedForms.fill(query, access.actor()));
    }

    @Resource private DataViewService dataViews;
    @Resource private ApplicationReportService reports;
    @Resource private com.lingan.ucp.nocode.web.RecordExcelService excel;
    @Resource private NocodeAccess access;
    @Resource private com.lingan.ucp.nocode.web.StrictRequestDecoder requests;

    @GetMapping("/application")
    @Operation(summary = "读取可访问的应用发布定义")
    public Result<ApplicationCenter.Published> application(@RequestParam String id) {
        return Result.success(
                ReadRequestMemo.within(() -> runtime.application(id, access.actor())));
    }

    @GetMapping("/mine")
    @Operation(summary = "查询本人可运行应用")
    public Result<java.util.List<ApplicationCenter.Row>> mine() {
        return Result.success(ReadRequestMemo.within(() -> runtime.mine(access.actor())));
    }

    @GetMapping("/model")
    @Operation(summary = "读取对象运行模型")
    public Result<Model> model(@RequestParam String applicationId, @RequestParam String objectId) {
        return Result.success(
                ReadRequestMemo.within(
                        () -> records.model(applicationId, objectId, access.actor())));
    }

    @PostMapping("/selection")
    @Operation(summary = "查询字段候选")
    public Result<com.lingan.ucp.nocode.api.SelectionFields.Result> selection(
            @RequestBody JsonNode body) {
        return Result.success(
                ReadRequestMemo.within(
                        () ->
                                records.selection(
                                        requests.runtime(
                                                body,
                                                com.lingan.ucp.nocode.api.SelectionFields.Query
                                                        .class),
                                        access.actor())));
    }

    /** 严格解码：请求体只能定位对象、记录与当前值，携带任何规则配置（未知属性）直接拒绝。 */
    @PostMapping("/field-rules/evaluate")
    @Operation(summary = "求值数据联动与公式默认值")
    public Result<com.lingan.ucp.nocode.api.FieldRules.Evaluation> evaluateFieldRules(
            @RequestBody JsonNode body) {
        return Result.success(
                ReadRequestMemo.within(
                        () ->
                                records.evaluateRules(
                                        requests.runtime(
                                                body,
                                                com.lingan.ucp.nocode.api.FieldRules.EvaluateQuery
                                                        .class),
                                        access.actor())));
    }

    @PostMapping("/form-fill")
    @Operation(summary = "读取表单关联带入值")
    public Result<java.util.Map<String, Object>> formFill(@RequestBody JsonNode body) {
        return Result.success(
                records.formFill(
                        requests.runtime(body, com.lingan.ucp.nocode.api.FormFills.Query.class),
                        access.actor()));
    }

    /** 带 reportDrill 时返回 {list,total,drillTotal}（统计下钻）；否则与原分页结果完全相同。 */
    @PostMapping("/page")
    @Operation(summary = "分页查询业务记录")
    public Result<?> page(@RequestBody JsonNode body) {
        var query = requests.runtime(body, Query.class);
        if (query.reportDrill() != null)
            return Result.success(
                    ReadRequestMemo.within(() -> records.drillPage(query, access.actor())));
        if (query.dashboardDrill() != null)
            return Result.success(
                    ReadRequestMemo.within(
                            () -> records.dashboardDrillPage(query, access.actor())));
        return Result.success(ReadRequestMemo.within(() -> records.page(query, access.actor())));
    }

    @GetMapping("/view-model")
    @Operation(summary = "读取数据视图模型")
    public Result<com.lingan.ucp.nocode.api.DataViews.Model> viewModel(
            @RequestParam String applicationId,
            @RequestParam String objectId,
            @RequestParam String viewId) {
        return Result.success(
                ReadRequestMemo.within(
                        () -> dataViews.model(applicationId, objectId, viewId, access.actor())));
    }

    @PostMapping("/view-children")
    @Operation(summary = "分页查询视图子表")
    public Result<PageResult<Row>> viewChildren(@RequestBody JsonNode body) {
        return Result.success(
                ReadRequestMemo.within(
                        () ->
                                dataViews.children(
                                        requests.runtime(
                                                body,
                                                com.lingan.ucp.nocode.api.DataViews.ChildQuery
                                                        .class),
                                        access.actor())));
    }

    @PostMapping("/report")
    @Operation(summary = "查询报表结果")
    public Result<com.lingan.ucp.nocode.api.ApplicationReports.Result> report(
            @RequestBody JsonNode body) {
        return Result.success(
                ReadRequestMemo.within(
                        () ->
                                reports.query(
                                        requests.runtime(
                                                body,
                                                com.lingan.ucp.nocode.api.ApplicationReports.Query
                                                        .class),
                                        access.actor())));
    }

    @PostMapping("/report-details")
    @Operation(summary = "查询报表明细")
    public Result<PageResult<Row>> reportDetails(@RequestBody JsonNode body) {
        return Result.success(
                ReadRequestMemo.within(
                        () ->
                                reports.details(
                                        requests.runtime(
                                                body,
                                                com.lingan.ucp.nocode.api.ApplicationReports.Query
                                                        .class),
                                        access.actor())));
    }

    @PostMapping("/report-export")
    @Operation(summary = "导出报表结果")
    public org.springframework.http.ResponseEntity<byte[]> reportExport(
            @RequestBody JsonNode body) {
        return file(
                "统计报表.xlsx",
                excel.report(
                        requests.runtime(
                                body, com.lingan.ucp.nocode.api.ApplicationReports.Query.class),
                        access.actor()));
    }

    @GetMapping("/get")
    @Operation(summary = "读取业务整单")
    public Result<Aggregate> get(
            @RequestParam String applicationId,
            @RequestParam String objectId,
            @RequestParam String id) {
        return Result.success(
                ReadRequestMemo.within(
                        () -> records.get(applicationId, objectId, id, access.actor())));
    }

    @PostMapping("/save")
    @Operation(summary = "保存业务整单")
    public Result<Aggregate> save(@RequestBody JsonNode body) {
        return Result.success(records.save(requests.runtime(body, Save.class), access.actor()));
    }

    @GetMapping("/save-receipt")
    @Operation(summary = "查询整单保存收据")
    public Result<SaveReceipt> saveReceipt(
            @RequestParam String applicationId,
            @RequestParam String objectId,
            @RequestParam String requestKey) {
        return Result.success(records.receipt(applicationId, objectId, requestKey, access.actor()));
    }

    @PostMapping("/delete")
    @Operation(summary = "删除业务记录")
    public Result<Boolean> delete(@RequestBody JsonNode body) {
        records.delete(requests.runtime(body, Delete.class), access.actor());
        return Result.success(true);
    }

    @GetMapping("/import-template")
    @Operation(summary = "下载记录导入模板")
    public org.springframework.http.ResponseEntity<byte[]> template(
            @RequestParam String applicationId, @RequestParam String objectId) {
        return file("业务导入模板.xlsx", excel.template(applicationId, objectId, access.actor()));
    }

    @PostMapping("/import")
    @Operation(summary = "导入业务记录")
    public Result<Integer> importFile(
            @RequestParam String applicationId,
            @RequestParam String objectId,
            @RequestParam org.springframework.web.multipart.MultipartFile file,
            @RequestParam(required = false) String pageId,
            @RequestParam(required = false) String nodeId,
            @RequestParam(required = false) String recordId) {
        Context context =
                pageId == null && nodeId == null && recordId == null
                        ? null
                        : new Context(pageId, nodeId, recordId);
        return Result.success(
                excel.importFile(applicationId, objectId, file, access.actor(), context));
    }

    @PostMapping("/export")
    @Operation(summary = "导出业务记录")
    public org.springframework.http.ResponseEntity<byte[]> export(@RequestBody JsonNode body) {
        return file("业务记录.xlsx", excel.export(requests.runtime(body, Query.class), access.actor()));
    }

    private org.springframework.http.ResponseEntity<byte[]> file(String filename, byte[] content) {
        return org.springframework.http.ResponseEntity.ok()
                .header(
                        org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment()
                                .filename(filename, java.nio.charset.StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .contentType(
                        org.springframework.http.MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(content);
    }

    @Resource private com.lingan.ucp.nocode.runtime.service.engine.EngineLinkService engines;

    /** 设计引擎区块：按当前用户对该记录的读/写权签发短期令牌（只收不放）。 */
    @PostMapping("/engine/token")
    @Operation(summary = "签发设计引擎令牌")
    public Result<com.lingan.ucp.nocode.api.EngineLink.Issued> engineToken(
            @RequestBody JsonNode body) {
        return Result.success(
                engines.issue(
                        requests.runtime(body, com.lingan.ucp.nocode.api.EngineLink.Issue.class),
                        access.actor()));
    }

    @GetMapping("/process-record")
    @Operation(summary = "定位流程关联记录")
    public Result<ProcessRecord> processRecord(@RequestParam String businessKey) {
        return Result.success(records.processRecord(businessKey, access.actor()));
    }

    @PostMapping("/action")
    @Operation(summary = "执行业务动作")
    public Result<Aggregate> action(@RequestBody JsonNode body) {
        return Result.success(
                records.execute(
                        requests.runtime(
                                body, com.lingan.ucp.nocode.api.ApplicationBusiness.Execute.class),
                        access.actor()));
    }
}
