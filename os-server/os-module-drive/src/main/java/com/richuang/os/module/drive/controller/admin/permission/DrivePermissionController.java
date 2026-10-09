package com.richuang.os.module.drive.controller.admin.permission;

import static com.richuang.os.framework.common.pojo.Result.success;
import static com.richuang.os.framework.security.core.util.SecurityFrameworkUtils.getLoginUserId;

import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.module.drive.controller.admin.permission.vo.DrivePermissionRespVO;
import com.richuang.os.module.drive.controller.admin.permission.vo.DrivePermissionSaveReqVO;
import com.richuang.os.module.drive.controller.admin.permission.vo.DrivePermissionSourceRespVO;
import com.richuang.os.module.drive.service.permission.DrivePermissionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "管理后台 - 网盘授权")
@RestController
@RequestMapping("/drive/permission")
@Validated
public class DrivePermissionController {

    @Resource private DrivePermissionService permissionService;

    @GetMapping("/list")
    @Operation(summary = "获得节点上直接配置的授权列表", description = "含授权主体与节点名称，需对该节点可查看")
    @Parameter(name = "spaceId", description = "空间编号", required = true, example = "1")
    @Parameter(name = "entryId", description = "节点编号，0 表示空间根", required = true, example = "0")
    @PreAuthorize("@ss.hasPermission('drive:permission:query')")
    public Result<List<DrivePermissionRespVO>> getPermissionList(
            @RequestParam("spaceId") Long spaceId, @RequestParam("entryId") Long entryId) {
        return success(permissionService.getPermissionList(spaceId, entryId, getLoginUserId()));
    }

    @GetMapping("/source-list")
    @Operation(summary = "获得授权来源列表", description = "含空间归属主体与继承而来的授权，用于说明有效权限出处")
    @Parameter(name = "spaceId", description = "空间编号", required = true, example = "1")
    @Parameter(name = "entryId", description = "节点编号，0 表示空间根", required = true, example = "0")
    @PreAuthorize("@ss.hasPermission('drive:permission:query')")
    public Result<List<DrivePermissionSourceRespVO>> getPermissionSourceList(
            @RequestParam("spaceId") Long spaceId, @RequestParam("entryId") Long entryId) {
        return success(
                permissionService.getPermissionSourceList(spaceId, entryId, getLoginUserId()));
    }

    @PostMapping("/save")
    @Operation(summary = "新增或修改节点授权")
    @PreAuthorize("@ss.hasPermission('drive:permission:update')")
    public Result<Long> savePermission(@Valid @RequestBody DrivePermissionSaveReqVO reqVO) {
        return success(permissionService.savePermission(reqVO, getLoginUserId()));
    }

    @DeleteMapping("/delete")
    @Operation(summary = "移除节点授权")
    @Parameter(name = "id", description = "授权编号", required = true, example = "1024")
    @PreAuthorize("@ss.hasPermission('drive:permission:update')")
    public Result<Boolean> deletePermission(@RequestParam("id") Long id) {
        permissionService.deletePermission(id, getLoginUserId());
        return success(true);
    }
}
