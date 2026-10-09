package com.richuang.os.nocode.controller.admin;

import com.richuang.os.framework.common.pojo.*;
import com.richuang.os.nocode.api.DataCenter.*;
import com.richuang.os.nocode.metadata.api.DataTables.*;
import com.richuang.os.nocode.metadata.service.table.DataTableService;
import com.richuang.os.nocode.web.NocodeAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 当前单数据库的物理目录、纳管与只读预览；没有数据库配置和任意 SQL 入口。 */
@Tag(name = "无代码 - 数据库表纳管")
@RestController
@RequestMapping("/nocode/table")
public class DataTableController {
    @Resource private DataTableService tables;
    @Resource private NocodeAccess access;

    @GetMapping("/schemas")
    @Operation(summary = "查询可见数据库模式")
    @PreAuthorize("@nocodeAccess.table()")
    public Result<List<String>> schemas() {
        return Result.success(tables.schemas());
    }

    @GetMapping("/page")
    @Operation(summary = "分页查询数据库表")
    @PreAuthorize("@nocodeAccess.table()")
    public Result<PageResult<TableRow>> page(
            @RequestParam(defaultValue = "public") String schema,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String management,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String objectId,
            @RequestParam(required = false) String structureState,
            @RequestParam(defaultValue = "false") boolean includeSystem,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        if (includeSystem && !access.has("nocode:table:system"))
            throw new AccessDeniedException("没有查看系统表的权限");
        return Result.success(
                tables.page(
                        schema,
                        name,
                        management,
                        role,
                        objectId,
                        structureState,
                        includeSystem,
                        pageNo,
                        pageSize));
    }

    @GetMapping("/get")
    @Operation(summary = "读取数据库表结构")
    @PreAuthorize("@nocodeAccess.table()")
    public Result<TableDetail> get(@RequestParam String schema, @RequestParam String name) {
        return Result.success(tables.detail(schema, name, access.has("nocode:table:system")));
    }

    @GetMapping("/preflight")
    @Operation(summary = "检查数据库表纳管条件")
    @PreAuthorize("@nocodeAccess.adopt()")
    public Result<Preflight> preflight(@RequestParam String schema, @RequestParam String name) {
        return Result.success(tables.preflight(schema, name));
    }

    @PostMapping("/adopt")
    @Operation(summary = "纳管已有数据库表")
    @PreAuthorize("@nocodeAccess.adopt()")
    public Result<Design> adopt(@RequestBody Adoption body) {
        return Result.success(tables.adopt(body, access.actor()));
    }

    @GetMapping("/preview")
    @Operation(summary = "预览数据库表记录")
    @PreAuthorize("@nocodeAccess.preview()")
    public Result<Preview> preview(
            @RequestParam String schema,
            @RequestParam String name,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Result.success(tables.preview(schema, name, pageNo, pageSize, access.actor()));
    }
}
