package com.lingan.ucp.nocode.controller.admin;

import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.nocode.api.RecordHistory;
import com.lingan.ucp.nocode.runtime.service.history.RecordHistoryService;
import com.lingan.ucp.nocode.web.NocodeAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 登录工作台入口；服务层逐应用、记录和字段校验，不借用设计权限扩权。 */
@Tag(name = "无代码 - 业务记录历史")
@RestController
@RequestMapping("/nocode/record-history")
@PreAuthorize("isAuthenticated()")
public class RecordHistoryController {
    @Resource private RecordHistoryService history;
    @Resource private NocodeAccess access;

    @PostMapping("/query")
    @Operation(summary = "统计可见记录变化")
    public Result<RecordHistory.Summary> query(@RequestBody RecordHistory.Query query) {
        return Result.success(history.query(query, access.actor()));
    }

    @PostMapping("/page")
    @Operation(summary = "分页查询可见记录历史")
    public Result<RecordHistory.Page> page(@RequestBody RecordHistory.PageQuery query) {
        return Result.success(history.page(query, access.actor()));
    }

    @PostMapping("/detail")
    @Operation(summary = "读取单条变更详情")
    public Result<RecordHistory.Detail> detail(@RequestBody RecordHistory.DetailQuery query) {
        return Result.success(history.detail(query, access.actor()));
    }
}
