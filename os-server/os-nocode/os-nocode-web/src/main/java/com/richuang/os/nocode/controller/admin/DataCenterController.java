package com.richuang.os.nocode.controller.admin;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.*;
import com.richuang.os.framework.common.pojo.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.enums.ObjectSourceEnum;
import com.richuang.os.nocode.metadata.service.formula.FormulaPreviewService;
import com.richuang.os.nocode.metadata.service.object.ObjectDesignService;
import com.richuang.os.nocode.metadata.service.table.DataTableService;
import com.richuang.os.nocode.schema.service.publish.SchemaPublishService;
import com.richuang.os.nocode.schema.service.reconcile.ObjectReconcileService;
import com.richuang.os.nocode.web.NocodeAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 数据对象设计和发布的正式管理接口，使用底座身份、权限及统一响应。 */
@Tag(name = "无代码 - 对象设计与发布")
@RestController
@RequestMapping("/nocode/design")
public class DataCenterController {
    @Resource private ObjectDesignService designs;
    @Resource private FormulaPreviewService formulaPreview;
    @Resource private DataTableService tables;
    @Resource private SchemaPublishService publisher;

    @Resource
    private com.richuang.os.nocode.schema.service.convert.FieldSwitchPreviewService
            fieldSwitchPreview;

    @Resource private NocodeAccess access;
    @Resource private com.richuang.os.nocode.web.StrictRequestDecoder requests;
    @Resource private ObjectMapper json;
    @Resource private ObjectReconcileService reconciler;

    @Resource
    private com.richuang.os.nocode.runtime.service.selection.SelectionCatalog selectionCatalog;

    @Resource
    private com.richuang.os.nocode.schema.service.selection.SelectionMigrationService
            selectionMigrations;

    @GetMapping("/page")
    @Operation(summary = "分页查询数据对象")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<PageResult<ObjectRow>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String ownerId,
            @RequestParam(required = false) String category) {
        return Result.success(
                designs.page(pageNo, pageSize, name, code, status, source, ownerId, category));
    }

    @GetMapping("/categories")
    @Operation(summary = "查询数据对象分类")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<java.util.List<String>> categories() {
        return Result.success(designs.categories());
    }

    @GetMapping("/get")
    @Operation(summary = "读取对象完整设计")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<Design> get(@RequestParam String id) {
        return Result.success(designs.get(id));
    }

    @PostMapping("/formula-preview")
    @Operation(summary = "试算字段公式")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<FormulaPreviewResult> formulaPreview(@RequestBody JsonNode body) {
        return Result.success(formulaPreview.preview(requests.design(body, FormulaPreview.class)));
    }

    @PostMapping("/field-switch-preview")
    @Operation(summary = "只读预检字段类型与来源切换影响")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<com.richuang.os.nocode.api.FieldSwitchPreview.Result> fieldSwitchPreview(
            @RequestBody JsonNode body) {
        return Result.success(
                fieldSwitchPreview.preview(
                        requests.design(
                                body,
                                com.richuang.os.nocode.api.FieldSwitchPreview.Request.class)));
    }

    @PostMapping("/field-switch-preview-rows")
    @Operation(summary = "分页查看字段切换前的已发布原值")
    @PreAuthorize("@nocodeAccess.publish() && @nocodeAccess.preview()")
    public Result<com.richuang.os.nocode.api.FieldConversions.Page> fieldSwitchPreviewRows(
            @RequestBody JsonNode body) {
        com.richuang.os.nocode.api.FieldSwitchPreview.RowsRequest request =
                requests.design(
                        body, com.richuang.os.nocode.api.FieldSwitchPreview.RowsRequest.class);
        return Result.success(fieldSwitchPreview.rows(request));
    }

    @GetMapping("/inactive-fields")
    @Operation(summary = "读取主表或指定内部明细的已停用字段")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<List<InactiveField>> inactiveFields(
            @RequestParam String id, @RequestParam(required = false) String detailId) {
        return Result.success(designs.inactiveFields(id, detailId));
    }

    @PostMapping("/save")
    @Operation(summary = "保存对象完整设计")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<Design> save(@RequestBody JsonNode body) {
        SaveDesign command = requests.design(body, SaveDesign.class);
        if (command.draft() == null) throw invalid("对象草稿必填");
        if (command.draft().id() == null ? !access.create() : !access.update())
            throw new AccessDeniedException("没有保存对象的权限");
        // 已有明细的首次绑定同样属于纳管；不能借设计保存绕过目录纳管入口的授权。
        // 已绑定明细的身份和物理映射由服务端锁定，普通修改仍沿用对象更新权限。
        if (command.details() != null
                && command.details().stream()
                        .anyMatch(
                                d ->
                                        d.id() == null
                                                && d.binding() != null
                                                && ObjectSourceEnum.ADOPTED.matches(
                                                        d.binding().source()))
                && !access.adopt()) throw new AccessDeniedException("绑定已有明细表需要纳管权限");
        return Result.success(designs.save(command, access.actor()));
    }

    @PostMapping("/edit")
    @Operation(summary = "从发布版本建立编辑草稿")
    @PreAuthorize("@nocodeAccess.update()")
    public Result<Design> edit(@RequestBody JsonNode body) {
        return Result.success(
                designs.editPublished(requests.design(body, Revision.class), access.actor()));
    }

    @PostMapping("/copy")
    @Operation(summary = "复制数据对象")
    @PreAuthorize("@nocodeAccess.create()")
    public Result<Design> copy(@RequestBody JsonNode body) {
        return Result.success(designs.copy(requests.design(body, Copy.class), access.actor()));
    }

    @PostMapping({"/enable", "/disable", "/delete"})
    @Operation(summary = "启用、停用或删除数据对象")
    @PreAuthorize("@nocodeAccess.manage()")
    public Result<Design> lifecycle(
            jakarta.servlet.http.HttpServletRequest request, @RequestBody JsonNode body) {
        String path = request.getRequestURI();
        return Result.success(
                designs.lifecycle(
                        requests.design(body, Revision.class),
                        path.substring(path.lastIndexOf('/') + 1),
                        access.actor()));
    }

    @GetMapping("/version")
    @Operation(summary = "读取对象历史版本定义")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<JsonNode> version(@RequestParam String id, @RequestParam int versionNo) {
        try {
            return Result.success(json.readTree(designs.version(id, versionNo)));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public record SelectionChange(
            String fieldId,
            String fieldName,
            List<String> existingValues,
            Map<String, List<String>> mapping,
            long conflicts,
            String error) {}

    @GetMapping("/selection-options")
    @Operation(summary = "读取字段选择选项")
    @PreAuthorize("@nocodeAccess.update()")
    public Result<List<com.richuang.os.nocode.api.SelectionFields.Option>> selectionOptions(
            @RequestParam String id, @RequestParam String fieldId) {
        var d = designs.definition(id);
        // 唯一例外：数据对象设计器没有应用，预览的是对象自身，挑取值来源按其当前发布版（见 SelectionCatalog.inObjectDesign）。
        return selectionCatalog.inObjectDesign(
                () -> {
                    for (var f : d.fields())
                        if (f.id().equals(fieldId))
                            return Result.success(
                                    selectionCatalog.options(f, d.fieldOptions().get(f.id())));
                    for (var detail : d.details())
                        for (var f : detail.fields())
                            if (f.id().equals(fieldId))
                                return Result.success(
                                        selectionCatalog.options(
                                                f, detail.fieldOptions().get(f.id())));
                    throw invalid("字段不属于当前对象");
                });
    }

    @GetMapping("/selection-changes")
    @Operation(summary = "预览选择来源变更")
    @PreAuthorize("@nocodeAccess.publish() && @nocodeAccess.update()")
    public Result<List<SelectionChange>> selectionChanges(@RequestParam String id) {
        return Result.success(
                selectionMigrations.preview(designs.definition(id), designs.published(id)).stream()
                        .map(
                                c ->
                                        new SelectionChange(
                                                c.fieldId(),
                                                c.fieldName(),
                                                c.existingValues(),
                                                c.mapping(),
                                                c.conflicts(),
                                                c.error()))
                        .toList());
    }

    @PostMapping("/plan")
    @Operation(summary = "生成对象发布计划")
    @PreAuthorize("@nocodeAccess.publish()")
    public Result<PublishPlan> plan(@RequestBody JsonNode body) {
        return Result.success(
                publisher.plan(requests.design(body, Revision.class), access.actor()));
    }

    @PostMapping("/execute")
    @Operation(summary = "执行对象发布计划")
    @PreAuthorize("@nocodeAccess.publish()")
    public Result<Execution> execute(@RequestBody JsonNode body) {
        ExecutePlan request = requests.design(body, ExecutePlan.class);
        if (!request.clearFieldIds().isEmpty() && (!access.manage() || !access.update()))
            throw new AccessDeniedException("清空字段历史值需要对象编辑和管理权限");
        if (!request.suspendApplicationIds().isEmpty() && !access.has("nocode:app:manage"))
            throw new AccessDeniedException("暂停受影响应用需要应用管理权限");
        return Result.success(publisher.execute(request, access.actor()));
    }

    @GetMapping("/conversion-rows")
    @Operation(summary = "分页查看字段转换将清空的原值")
    @PreAuthorize("@nocodeAccess.publish() && @nocodeAccess.preview()")
    public Result<com.richuang.os.nocode.api.FieldConversions.Page> conversionRows(
            @RequestParam String planId,
            @RequestParam String fieldId,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.success(publisher.conversionRows(planId, fieldId, pageNo, pageSize));
    }

    @GetMapping("/history")
    @Operation(summary = "查询对象发布历史")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<List<Execution>> history(@RequestParam String id) {
        return Result.success(publisher.history(id));
    }

    public record Id(String id) {}

    @PostMapping("/verify")
    @Operation(summary = "核验物理结构")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<List<Check>> verify(@RequestBody JsonNode body) {
        return Result.success(tables.verify(requests.design(body, Id.class).id(), access.actor()));
    }

    @GetMapping("/reconcile-preview")
    @Operation(summary = "预览结构核对结果")
    @PreAuthorize("@nocodeAccess.publish()")
    public Result<com.richuang.os.nocode.api.ObjectReconciliation.Preview> reconcilePreview(
            @RequestParam String id) {
        return Result.success(reconciler.preview(id));
    }

    @PostMapping("/reconcile")
    @Operation(summary = "将结构核对结果应用到草稿")
    @PreAuthorize("@nocodeAccess.publish() && @nocodeAccess.update()")
    public Result<Design> reconcile(@RequestBody JsonNode body) {
        return Result.success(
                reconciler.apply(
                        requests.design(
                                body, com.richuang.os.nocode.api.ObjectReconciliation.Apply.class),
                        access.actor()));
    }

    @Resource
    private com.richuang.os.nocode.application.service.application.ApplicationFollowService
            applicationFollows;

    @GetMapping("/follow-result")
    @Operation(summary = "查询一次对象发布的应用自动跟随结果")
    @PreAuthorize("@nocodeAccess.publish()")
    public Result<List<com.richuang.os.nocode.api.ApplicationFollows.FollowResult>> followResult(
            @RequestParam String planId) {
        return Result.success(applicationFollows.result(planId));
    }
}
