package com.lingan.ucp.module.drive.controller.admin.mark;

import static com.lingan.ucp.framework.common.pojo.Result.success;
import static com.lingan.ucp.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.module.drive.controller.admin.mark.vo.DriveMarkedEntryRespVO;
import com.lingan.ucp.module.drive.service.mark.DriveMarkService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理后台 - 网盘收藏与最近使用")
@RestController
@RequestMapping("/drive/mark")
@Validated
public class DriveMarkController {

    @Resource private DriveMarkService markService;

    @GetMapping("/favorite-list")
    @Operation(summary = "获得我的收藏列表")
    @PreAuthorize("@ss.hasPermission('drive:mark:query')")
    public Result<List<DriveMarkedEntryRespVO>> getFavoriteList() {
        return success(markService.getFavoriteList(getLoginUserId()));
    }

    @GetMapping("/recent-list")
    @Operation(summary = "获得最近使用列表")
    @Parameter(name = "limit", description = "返回条数上限", example = "20")
    @PreAuthorize("@ss.hasPermission('drive:mark:query')")
    public Result<List<DriveMarkedEntryRespVO>> getRecentList(
            @RequestParam(value = "limit", required = false) Integer limit) {
        return success(markService.getRecentList(getLoginUserId(), limit));
    }

    @PutMapping("/favorite")
    @Operation(summary = "收藏或取消收藏节点")
    @Parameter(name = "entryId", description = "节点编号", required = true, example = "1024")
    @Parameter(name = "favorite", description = "是否收藏", required = true, example = "true")
    @PreAuthorize("@ss.hasPermission('drive:mark:update')")
    public Result<Boolean> updateFavorite(
            @RequestParam("entryId") Long entryId, @RequestParam("favorite") Boolean favorite) {
        return success(markService.updateFavorite(entryId, favorite, getLoginUserId()));
    }

    @PostMapping("/access")
    @Operation(summary = "记录节点访问", description = "用于最近使用列表，重复访问只刷新时间")
    @Parameter(name = "entryId", description = "节点编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('drive:mark:update')")
    public Result<Boolean> recordAccess(@RequestParam("entryId") Long entryId) {
        markService.recordAccess(entryId, getLoginUserId());
        return success(true);
    }
}
