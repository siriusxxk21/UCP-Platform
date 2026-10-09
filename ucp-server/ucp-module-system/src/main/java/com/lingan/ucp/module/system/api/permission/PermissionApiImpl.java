package com.lingan.ucp.module.system.api.permission;

import com.lingan.ucp.framework.common.biz.system.permission.dto.DeptDataPermissionRespDTO;
import com.lingan.ucp.module.system.service.permission.PermissionService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Set;

/**
 * 权限 API 实现类
 *
 * @author os
 */
@Service
public class PermissionApiImpl implements PermissionApi {

    @Resource private PermissionService permissionService;

    @Override
    public Set<Long> getUserRoleIds(Long userId) {
        return permissionService.getUserRoleIdListByUserId(userId);
    }

    @Override
    public Set<Long> getUserRoleIdListByRoleIds(Collection<Long> roleIds) {
        return permissionService.getUserRoleIdListByRoleId(roleIds);
    }

    @Override
    public boolean hasAnyPermissions(Long userId, String... permissions) {
        return permissionService.hasAnyPermissions(userId, permissions);
    }

    @Override
    public boolean hasAnyRoles(Long userId, String... roles) {
        return permissionService.hasAnyRoles(userId, roles);
    }

    @Override
    public DeptDataPermissionRespDTO getDeptDataPermission(Long userId) {
        return permissionService.getDeptDataPermission(userId);
    }
}
