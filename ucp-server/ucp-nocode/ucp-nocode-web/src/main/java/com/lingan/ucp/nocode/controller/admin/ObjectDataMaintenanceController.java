package com.lingan.ucp.nocode.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.pojo.*;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.runtime.service.maintenance.ObjectDataMaintenanceService;
import com.lingan.ucp.nocode.runtime.service.maintenance.OrderedCalibrationService;
import com.lingan.ucp.nocode.web.*;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 与结构管理共用权限；客户端不能用应用 ID 或任意物理表扩大操作范围。 */
@Tag(name = "无代码 - 对象数据维护")
@RestController
@RequestMapping("/nocode/object-data")
@PreAuthorize("@nocodeAccess.manage()")
public class ObjectDataMaintenanceController {
    @Resource private ObjectDataMaintenanceService service;
    @Resource private OrderedCalibrationService calibration;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @GetMapping("/calculation/status")
    @Operation(summary = "读取有序计算校准状态")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<java.util.List<OrderedCalculations.State>> calculationStatus(
            @RequestParam String objectId) {
        return Result.success(calibration.status(objectId, access.actor()));
    }

    @PostMapping("/calculation/calibrate-preview")
    @Operation(summary = "预览有序计算校准差异")
    public Result<OrderedCalculationCalibration.Preview> calibrationPreview(
            @RequestBody JsonNode body) {
        return Result.success(
                calibration.preview(
                        requests.runtime(body, OrderedCalculationCalibration.PreviewRequest.class),
                        access.actor()));
    }

    @PostMapping("/calculation/calibrate")
    @Operation(summary = "开始并分组推进有序计算校准")
    public Result<OrderedCalculationCalibration.Result> calibrate(@RequestBody JsonNode body) {
        return Result.success(
                calibration.calibrate(
                        requests.runtime(body, OrderedCalculationCalibration.Command.class),
                        access.actor()));
    }

    @PostMapping({"/calculation/resume", "/calculation/retry"})
    @Operation(summary = "继续或重试有序计算校准批次")
    public Result<OrderedCalculationCalibration.Result> resumeCalibration(
            @RequestBody JsonNode body) {
        return Result.success(
                calibration.resume(
                        requests.runtime(body, OrderedCalculationCalibration.Command.class),
                        access.actor()));
    }

    @PostMapping("/calculation/pause")
    @Operation(summary = "暂停有序计算校准批次")
    public Result<OrderedCalculationCalibration.Result> pauseCalibration(
            @RequestBody JsonNode body) {
        return Result.success(
                calibration.pause(
                        requests.runtime(body, OrderedCalculationCalibration.Command.class),
                        access.actor()));
    }

    @GetMapping("/model")
    @Operation(summary = "读取已发布对象维护模型")
    public Result<ObjectDataMaintenance.Model> model(@RequestParam String objectId) {
        return Result.success(service.model(objectId, access.actor()));
    }

    @PostMapping("/page")
    @Operation(summary = "分页查询对象共享数据")
    public Result<PageResult<ApplicationRecords.Row>> page(@RequestBody JsonNode body) {
        return Result.success(
                service.page(
                        requests.runtime(body, ObjectDataMaintenance.Query.class), access.actor()));
    }

    @GetMapping("/get")
    @Operation(summary = "查看记录及现有明细")
    public Result<ApplicationRecords.Aggregate> get(
            @RequestParam String objectId, @RequestParam String id) {
        return Result.success(service.get(objectId, id, access.actor()));
    }

    @PostMapping("/save")
    @Operation(summary = "保存对象维护记录")
    public Result<ApplicationRecords.Aggregate> save(@RequestBody JsonNode body) {
        return Result.success(
                service.save(
                        requests.runtime(body, ObjectDataMaintenance.Save.class), access.actor()));
    }

    @PostMapping("/selection")
    @Operation(summary = "查询对象字段候选")
    public Result<SelectionFields.Result> selection(@RequestBody JsonNode body) {
        return Result.success(
                service.selection(
                        requests.runtime(body, ObjectDataMaintenance.Selection.class),
                        access.actor()));
    }

    @PostMapping("/delete-preview")
    @Operation(summary = "检查记录删除影响")
    public Result<ObjectDataMaintenance.DeletePreview> preview(@RequestBody JsonNode body) {
        return Result.success(
                service.previewDelete(
                        requests.runtime(body, ObjectDataMaintenance.Delete.class),
                        access.actor()));
    }

    @PostMapping("/delete")
    @Operation(summary = "确认删除对象记录")
    public Result<Boolean> delete(@RequestBody JsonNode body) {
        service.delete(requests.runtime(body, ObjectDataMaintenance.Delete.class), access.actor());
        return Result.success(true);
    }

    @PostMapping("/clear-column-preview")
    @Operation(summary = "检查已发布对象整列清空影响")
    public Result<ObjectDataMaintenance.ClearColumnPreview> previewClearColumn(
            @RequestBody JsonNode body) {
        return Result.success(
                service.previewClearColumn(
                        requests.runtime(body, ObjectDataMaintenance.ClearColumn.class),
                        access.actor()));
    }

    @PostMapping("/clear-column")
    @Operation(summary = "确认清空已发布对象的单列值")
    public Result<ObjectDataMaintenance.ClearColumnResult> clearColumn(@RequestBody JsonNode body) {
        return Result.success(
                service.clearColumn(
                        requests.runtime(body, ObjectDataMaintenance.ClearColumn.class),
                        access.actor()));
    }
}
