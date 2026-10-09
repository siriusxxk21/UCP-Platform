package com.richuang.os.nocode.controller.admin;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.fasterxml.jackson.databind.*;
import com.richuang.os.framework.common.pojo.*;
import com.richuang.os.nocode.api.ApplicationCenter.*;
import com.richuang.os.nocode.api.DataObjectApi;
import com.richuang.os.nocode.application.service.application.ApplicationService;
import com.richuang.os.nocode.web.NocodeAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 应用设计入口；认证、权限、响应和异常继续使用 OS 底座。 */
@Tag(name = "无代码 - 应用设计")
@RestController
@RequestMapping("/nocode/application")
public class ApplicationController {
    @Resource private ApplicationService applications;

    @Resource
    private com.richuang.os.nocode.application.service.authorization.ApplicationAuthorizationService
            authorization;

    @Resource private DataObjectApi objects;
    @Resource private com.richuang.os.nocode.runtime.service.selection.SelectionCatalog selections;
    @Resource private com.richuang.os.nocode.runtime.service.record.RecordService records;

    @Resource
    private com.richuang.os.nocode.runtime.service.report.ApplicationReportService reports;

    @Resource private NocodeAccess access;
    @Resource private com.richuang.os.nocode.web.StrictRequestDecoder requests;

    @GetMapping("/page")
    @Operation(summary = "分页查询可管理应用")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<PageResult<Row>> page(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String category) {
        return Result.success(
                applications.page(pageNo, pageSize, search, category, access.actor()));
    }

    @GetMapping("/categories")
    @Operation(summary = "查询可管理应用分类")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<java.util.List<String>> categories() {
        return Result.success(applications.categories(access.actor()));
    }

    @GetMapping("/recycle-page")
    @Operation(summary = "分页查询应用回收站")
    @PreAuthorize("@nocodeAccess.has('nocode:app:manage')")
    public Result<PageResult<RecycleRow>> recyclePage(
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String search) {
        return Result.success(applications.recyclePage(pageNo, pageSize, search, access.actor()));
    }

    @GetMapping("/delete-preview")
    @Operation(summary = "预检应用删除影响与流程阻断")
    @PreAuthorize("@nocodeAccess.has('nocode:app:manage')")
    public Result<DeletePreview> deletePreview(@RequestParam String id) {
        return Result.success(applications.deletePreview(id, access.actor()));
    }

    @PostMapping("/delete")
    @Operation(summary = "将应用移入回收站并关闭入口")
    @PreAuthorize("@nocodeAccess.has('nocode:app:manage')")
    public Result<Boolean> delete(@RequestBody JsonNode body) {
        return Result.success(
                applications.delete(requests.application(body, Revision.class), access.actor()));
    }

    @PostMapping("/recycle-restore")
    @Operation(summary = "从回收站恢复应用并保持停用")
    @PreAuthorize("@nocodeAccess.has('nocode:app:manage')")
    public Result<Detail> restoreDeleted(@RequestBody JsonNode body) {
        return Result.success(
                applications.restoreDeleted(
                        requests.application(body, Revision.class), access.actor()));
    }

    @PostMapping("/publish-and-enable")
    @Operation(summary = "恢复应用人工编辑后发布并启用")
    @PreAuthorize(
            "@nocodeAccess.has('nocode:app:manage') && @nocodeAccess.has('nocode:app:publish')")
    public Result<Detail> publishAndEnable(@RequestBody JsonNode body) {
        return Result.success(
                applications.publishAndEnable(
                        requests.application(body, Revision.class), access.actor()));
    }

    @GetMapping("/get")
    @Operation(summary = "读取应用设计详情")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<Detail> get(@RequestParam String id) {
        applications.requireDesigner(id, access.actor());
        return Result.success(applications.get(id));
    }

    @GetMapping("/releases")
    @Operation(summary = "分页查询应用发布记录")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<PageResult<Release>> releases(
            @RequestParam String id,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        applications.requireDesigner(id, access.actor());
        return Result.success(applications.releases(id, pageNo, pageSize));
    }

    @GetMapping("/authorization")
    @Operation(summary = "读取应用授权策略")
    @PreAuthorize("@nocodeAccess.has('nocode:app:manage')")
    public Result<com.richuang.os.nocode.api.ApplicationAuthorization.Policy> authorization(
            @RequestParam String id) {
        applications.requireDesigner(id, access.actor());
        return Result.success(authorization.get(id));
    }

    @PostMapping("/authorization")
    @Operation(summary = "保存应用授权策略")
    @PreAuthorize("@nocodeAccess.has('nocode:app:manage')")
    public Result<com.richuang.os.nocode.api.ApplicationAuthorization.Policy> authorization(
            @RequestBody JsonNode body) {
        return Result.success(
                authorization.save(
                        requests.application(
                                body,
                                com.richuang.os.nocode.api.ApplicationAuthorization.Save.class),
                        access.actor()));
    }

    @PostMapping("/save")
    @Operation(summary = "保存应用草稿")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<Detail> save(@RequestBody JsonNode body) {
        Save command = requests.application(body, Save.class);
        String permission = command.id() == null ? "nocode:app:create" : "nocode:app:update";
        if (!access.has(permission)) throw new AccessDeniedException("没有保存应用的权限");
        return Result.success(applications.save(command, access.actor()));
    }

    @GetMapping("/object-version")
    @Operation(summary = "读取对象固定版本")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query') && @nocodeAccess.query()")
    public Result<DataObjectApi.PublishedObject> objectVersion(
            @RequestParam String id, @RequestParam(required = false) Integer versionNo) {
        return Result.success(objects.getVersion(id, versionNo));
    }

    @GetMapping("/selection-options")
    @Operation(summary = "读取设计候选选项")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query') && @nocodeAccess.query()")
    public Result<java.util.List<com.richuang.os.nocode.api.SelectionFields.Option>>
            selectionOptions(
                    @RequestParam String id,
                    @RequestParam String fieldId,
                    @RequestParam(required = false) Integer versionNo,
                    @RequestParam(required = false) String search,
                    @RequestParam(required = false) String applicationId) {
        if (search != null && search.length() > 128) throw invalid("搜索内容过长");
        // 挑取值来源按正在设计的应用草稿里固定的对象版本解析；未带应用时该类字段 fail-closed（不给选项）。
        if (applicationId != null) {
            applications.requireDesigner(applicationId, access.actor());
            return selections.inApplicationDraft(
                    applicationId, () -> selectionOptions(id, fieldId, versionNo, search, null));
        }
        var d = objects.getVersion(id, versionNo).definition();
        var f =
                d.fields().stream()
                        .filter(v -> v.id().equals(fieldId))
                        .findFirst()
                        .orElseThrow(() -> invalid("字段不存在"));
        com.richuang.os.nocode.api.DataCenter.FieldOptions o =
                d.fieldOptions()
                        .getOrDefault(
                                f.id(),
                                com.richuang.os.nocode.api.DataCenter.FieldOptions.defaults());
        PageResult<com.richuang.os.nocode.api.SelectionFields.Option> page =
                selections.userPage(f, o, search, 1, 100);
        return Result.success(page == null ? selections.options(f, o) : page.getList());
    }

    @PostMapping("/selection-preview")
    @Operation(summary = "预览选择字段候选")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<com.richuang.os.nocode.api.SelectionFields.Result> previewSelection(
            @RequestBody JsonNode body) {
        return Result.success(
                records.previewSelection(
                        requests.application(
                                body,
                                com.richuang.os.nocode.api.SelectionFields.PreviewQuery.class),
                        access.actor()));
    }

    @PostMapping("/field-rules-preview")
    @Operation(summary = "预览数据联动与公式默认值")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<com.richuang.os.nocode.api.FieldRules.Evaluation> previewFieldRules(
            @RequestBody JsonNode body) {
        return Result.success(
                records.previewRules(
                        requests.application(
                                body, com.richuang.os.nocode.api.FieldRules.EvaluatePreview.class),
                        access.actor()));
    }

    @PostMapping("/form-fill-preview")
    @Operation(summary = "预览关联带入")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<java.util.Map<String, Object>> previewFormFill(@RequestBody JsonNode body) {
        return Result.success(
                records.previewFormFill(
                        requests.application(
                                body, com.richuang.os.nocode.api.FormFills.PreviewQuery.class),
                        access.actor()));
    }

    @PostMapping("/report-preview")
    @Operation(summary = "预览报表配置")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<com.richuang.os.nocode.api.ApplicationReports.Result> previewReport(
            @RequestBody JsonNode body) {
        return Result.success(
                reports.preview(
                        requests.application(
                                body, com.richuang.os.nocode.api.ApplicationReports.Preview.class),
                        access.actor()));
    }

    @PostMapping("/publish")
    @Operation(summary = "发布应用版本")
    @PreAuthorize(
            "@nocodeAccess.has('nocode:app:query') && @nocodeAccess.has('nocode:app:publish')")
    public Result<Detail> publish(@RequestBody JsonNode body) {
        return Result.success(
                applications.publish(requests.application(body, Revision.class), access.actor()));
    }

    public record StatusCommand(String id, int expectedRevision, String reason, String status) {}

    @PostMapping("/restore")
    @Operation(summary = "恢复历史应用版本并重新发布")
    @PreAuthorize(
            "@nocodeAccess.has('nocode:app:query') && @nocodeAccess.has('nocode:app:publish')")
    public Result<Detail> restore(@RequestBody JsonNode body) {
        return Result.success(
                applications.restore(requests.application(body, Restore.class), access.actor()));
    }

    @PostMapping("/status")
    @Operation(summary = "调整应用状态")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query') && @nocodeAccess.has('nocode:app:manage')")
    public Result<Detail> status(@RequestBody JsonNode body) {
        ApplicationController.StatusCommand command =
                requests.application(body, StatusCommand.class);
        return Result.success(
                applications.status(
                        new Revision(command.id(), command.expectedRevision(), command.reason()),
                        command.status(),
                        access.actor()));
    }

    @Resource
    private com.richuang.os.nocode.application.service.sharing.ImpliedObjects impliedObjects;

    @PostMapping("/readable-objects")
    @Operation(summary = "读取应用因关联而可读取的对象")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query') && @nocodeAccess.query()")
    public Result<java.util.List<com.richuang.os.nocode.api.ApplicationReadableObjects.Item>>
            readableObjects(@RequestBody JsonNode body) {
        com.richuang.os.nocode.api.ApplicationReadableObjects.Query query =
                requests.application(
                        body, com.richuang.os.nocode.api.ApplicationReadableObjects.Query.class);
        if (query.applicationId() != null)
            applications.requireDesigner(query.applicationId(), access.actor());
        return Result.success(impliedObjects.readable(query));
    }

    @Resource
    private com.richuang.os.nocode.application.service.application.ApplicationFollowService follows;

    @GetMapping("/object-follow")
    @Operation(summary = "查询应用对各引用对象的自动跟随状态")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<java.util.List<com.richuang.os.nocode.api.ApplicationFollows.ObjectFollow>>
            objectFollows(@RequestParam String id) {
        return Result.success(follows.list(id, access.actor()));
    }

    @PostMapping("/object-follow")
    @Operation(summary = "打开或关闭应用对某个对象的自动跟随")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<com.richuang.os.nocode.api.ApplicationFollows.FollowRun> setObjectFollow(
            @RequestBody JsonNode body) {
        return Result.success(
                follows.toggle(
                        requests.application(
                                body, com.richuang.os.nocode.api.ApplicationFollows.Switch.class),
                        access.actor()));
    }

    @PostMapping("/object-follow/run")
    @Operation(summary = "立即跟随对象最新版本")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query')")
    public Result<com.richuang.os.nocode.api.ApplicationFollows.FollowRun> runObjectFollow(
            @RequestBody JsonNode body) {
        return Result.success(
                follows.run(
                        requests.application(
                                body, com.richuang.os.nocode.api.ApplicationFollows.Run.class),
                        access.actor()));
    }
}
