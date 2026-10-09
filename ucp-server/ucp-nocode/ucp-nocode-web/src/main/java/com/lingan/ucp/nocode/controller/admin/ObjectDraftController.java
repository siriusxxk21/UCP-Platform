package com.lingan.ucp.nocode.controller.admin;

import com.fasterxml.jackson.databind.*;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.nocode.api.*;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDesignService;
import com.lingan.ucp.nocode.metadata.service.object.ObjectDraftService;
import com.lingan.ucp.nocode.web.NocodeAccess;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** 对象管理入口；身份、鉴权、分页响应与业务异常均复用底座约定。 */
@Tag(name = "无代码 - 对象草稿")
@RestController
@RequestMapping("/nocode/object")
public class ObjectDraftController {
    @Resource private com.lingan.ucp.nocode.web.StrictRequestDecoder requests;

    @Resource private ObjectDraftService service;
    @Resource private ObjectDesignService designs;
    @Resource private NocodeAccess access;

    @GetMapping("/page")
    @Operation(summary = "分页查询对象草稿")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<PageResult<ObjectSummary>> page(@RequestParam Map<String, String> parameters) {
        checkKeys(parameters, Set.of("pageNo", "pageSize", "name", "code"));
        try {
            return Result.success(
                    service.page(
                            Integer.parseInt(parameters.getOrDefault("pageNo", "1")),
                            Integer.parseInt(parameters.getOrDefault("pageSize", "10")),
                            parameters.get("name"),
                            parameters.get("code")));
        } catch (NumberFormatException ex) {
            throw NocodeErrorCodes.invalid("分页必须为整数");
        }
    }

    @GetMapping("/get")
    @Operation(summary = "读取对象草稿")
    @PreAuthorize("@nocodeAccess.query()")
    public Result<ObjectDraft> get(@RequestParam Map<String, String> parameters) {
        checkKeys(parameters, Set.of("id"));
        return Result.success(service.get(parameters.get("id")));
    }

    @PostMapping("/create")
    @Operation(summary = "创建数据对象")
    @PreAuthorize("@nocodeAccess.create()")
    public Result<ObjectDraft> create(@RequestBody JsonNode body) {
        var command = requests.draft(body);
        if (command.id() != null) throw NocodeErrorCodes.invalid("创建请求不能指定既有对象 ID");
        return Result.success(
                designs.save(
                                new DataCenter.SaveDesign(command, null, null, null, null, null),
                                access.actor())
                        .draft());
    }

    @PutMapping("/save-draft")
    @Operation(summary = "保存已有对象草稿")
    @PreAuthorize("@nocodeAccess.update()")
    public Result<ObjectDraft> update(@RequestBody JsonNode body) {
        var command = requests.draft(body);
        if (command.id() == null) throw NocodeErrorCodes.invalid("保存请求必须指定对象 ID");
        return Result.success(
                designs.save(
                                new DataCenter.SaveDesign(command, null, null, null, null, null),
                                access.actor())
                        .draft());
    }

    private void checkKeys(Map<String, String> parameters, Set<String> allowed) {
        if (!allowed.containsAll(parameters.keySet())) throw NocodeErrorCodes.invalid("包含未声明的查询参数");
    }
}
