package com.lingan.ucp.nocode.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.nocode.api.LinkageSync;
import com.lingan.ucp.nocode.runtime.service.maintenance.LinkageSyncService;
import com.lingan.ucp.nocode.web.NocodeAccess;
import com.lingan.ucp.nocode.web.StrictRequestDecoder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 数据联动「来源变化时自动更新」的总览、预告与回填；应用设计权限 + 服务内的应用设计者校验。 */
@Tag(name = "无代码 - 数据联动自动更新")
@RestController
@RequestMapping("/nocode/application/linkage-sync")
public class ApplicationLinkageSyncController {
    @Resource private LinkageSyncService linkageSync;
    @Resource private NocodeAccess access;
    @Resource private StrictRequestDecoder requests;

    @GetMapping("/overview")
    @Operation(summary = "查询应用里开启自动更新的字段及其变化")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query') && @nocodeAccess.has('nocode:app:manage')")
    public Result<LinkageSync.Overview> overview(
            @RequestParam String applicationId,
            @RequestParam(defaultValue = LinkageSync.BASIS_PUBLISHED) String basis) {
        return Result.success(linkageSync.overview(applicationId, basis, access.actor()));
    }

    @PostMapping("/preview")
    @Operation(summary = "预告一页：自动更新字段的应有值与现值比较，不写数据")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query') && @nocodeAccess.has('nocode:app:manage')")
    public Result<LinkageSync.Preview> preview(@RequestBody JsonNode body) {
        return Result.success(
                linkageSync.preview(
                        requests.application(body, LinkageSync.PreviewRequest.class),
                        access.actor()));
    }

    @PostMapping("/backfill")
    @Operation(summary = "回填一页：按当前数据重算并写回自动更新字段")
    @PreAuthorize("@nocodeAccess.has('nocode:app:query') && @nocodeAccess.has('nocode:app:manage')")
    public Result<LinkageSync.Backfill> backfill(@RequestBody JsonNode body) {
        return Result.success(
                linkageSync.backfill(
                        requests.application(body, LinkageSync.BackfillRequest.class),
                        access.actor()));
    }
}
