package com.richuang.os.module.system.controller.admin.permission;

import cn.hutool.core.collection.CollUtil;
import com.richuang.os.framework.common.pojo.Result;
import com.richuang.os.module.system.controller.admin.permission.vo.permission.PermissionAssignRoleDataScopeReqVO;
import com.richuang.os.module.system.controller.admin.permission.vo.permission.PermissionAssignRoleMenuReqVO;
import com.richuang.os.module.system.controller.admin.permission.vo.permission.PermissionAssignRoleUsersReqVO;
import com.richuang.os.module.system.controller.admin.permission.vo.permission.PermissionAssignUserRoleReqVO;
import com.richuang.os.module.system.service.permission.PermissionService;
import com.richuang.os.module.system.service.tenant.TenantService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import java.util.Set;

import static com.richuang.os.framework.common.pojo.Result.success;

/**
 * 权限 Controller，提供赋予用户、角色的权限的 API 接口
 *
 * @author os
 */
@Tag(name = "管理后台 - 权限")
@RestController
@RequestMapping("/system/permission")
public class PermissionController {

    @Resource
    private PermissionService permissionService;
    @Resource
    private TenantService tenantService;

    @Operation(summary = "获得角色拥有的菜单编号")
    @Parameter(name = "roleId", description = "角色编号", required = true)
    @GetMapping("/list-role-menus")
    @PreAuthorize("@ss.hasPermission('system:permission:assign-role-menu')")
    public Result<Set<Long>> getRoleMenuList(Long roleId) {
        return success(permissionService.getRoleMenuListByRoleId(roleId));
    }

    @PostMapping("/assign-role-menu")
    @Operation(summary = "赋予角色菜单")
    @PreAuthorize("@ss.hasPermission('system:permission:assign-role-menu')")
    public Result<Boolean> assignRoleMenu(@Validated @RequestBody PermissionAssignRoleMenuReqVO reqVO) {
        // 开启多租户的情况下，需要过滤掉未开通的菜单
        tenantService.handleTenantMenu(menuIds -> reqVO.getMenuIds().removeIf(menuId -> !CollUtil.contains(menuIds, menuId)));

        // 执行菜单的分配
        permissionService.assignRoleMenu(reqVO.getRoleId(), reqVO.getMenuIds());
        return success(true);
    }

    @PostMapping("/assign-role-data-scope")
    @Operation(summary = "赋予角色数据权限")
    @PreAuthorize("@ss.hasPermission('system:permission:assign-role-data-scope')")
    public Result<Boolean> assignRoleDataScope(@Valid @RequestBody PermissionAssignRoleDataScopeReqVO reqVO) {
        permissionService.assignRoleDataScope(reqVO.getRoleId(), reqVO.getDataScope(), reqVO.getDataScopeDeptIds());
        return success(true);
    }

    @Operation(summary = "获得管理员拥有的角色编号列表")
    @Parameter(name = "userId", description = "用户编号", required = true)
    @GetMapping("/list-user-roles")
    @PreAuthorize("@ss.hasPermission('system:permission:assign-user-role')")
    public Result<Set<String>> listAdminRoles(@RequestParam("userId") Long userId) {
        Set<String> roleIds = permissionService.getUserRoleIdListByUserId(userId).stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.toSet());
        return success(roleIds);
    }

    @Operation(summary = "获得拥有指定角色的用户编号列表")
    @Parameter(name = "roleId", description = "角色编号", required = true)
    @GetMapping("/list-role-users")
    @PreAuthorize("@ss.hasPermission('system:permission:assign-user-role')")
    public Result<Set<Long>> listRoleUsers(@RequestParam("roleId") Long roleId) {
        return success(permissionService.getUserRoleIdListByRoleId(Set.of(roleId)));
    }

    @Operation(summary = "赋予用户角色")
    @PostMapping("/assign-user-role")
    @PreAuthorize("@ss.hasPermission('system:permission:assign-user-role')")
    public Result<Boolean> assignUserRole(@Validated @RequestBody PermissionAssignUserRoleReqVO reqVO) {
        permissionService.assignUserRole(reqVO.getUserId(), reqVO.getRoleIds());
        return success(true);
    }

    @Operation(summary = "覆盖分配角色用户")
    @PostMapping("/assign-role-users")
    @PreAuthorize("@ss.hasPermission('system:permission:assign-user-role')")
    public Result<Boolean> assignRoleUsers(@Validated @RequestBody PermissionAssignRoleUsersReqVO reqVO) {
        permissionService.assignRoleUsers(reqVO.getRoleId(), reqVO.getUserIds());
        return success(true);
    }

}
