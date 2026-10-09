package com.lingan.ucp.module.system.api.permission;

import com.lingan.ucp.framework.common.util.object.BeanUtils;
import com.lingan.ucp.framework.security.core.util.SecurityFrameworkUtils;
import com.lingan.ucp.module.system.api.permission.dto.RoleRespDTO;
import com.lingan.ucp.module.system.dal.dataobject.permission.RoleDO;
import com.lingan.ucp.module.system.service.permission.RoleService;
import com.lingan.ucp.module.system.service.permission.UserPermissionQueryService;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 角色 API 实现类
 *
 * @author os
 */
@Service
public class RoleApiImpl implements RoleApi {

    @Resource
    private RoleService roleService;

    @Resource
    private UserPermissionQueryService userPermissionQueryService;

    @Override
    public void validRoleList(Collection<Long> ids) {
        roleService.validateRoleList(ids);
    }

    @Override
    public RoleRespDTO getRole(Long id) {
        RoleDO role = roleService.getRole(id);
        return BeanUtils.toBean(role, RoleRespDTO.class);
    }

    @Override
    public List<RoleRespDTO> getRoleList(Collection<Long> ids) {
        List<RoleDO> list = roleService.getRoleList(ids);
        return BeanUtils.toBean(list, RoleRespDTO.class);
    }

    @Override
    public Set<String> getCurrentUserRoleCodes() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            return Collections.emptySet();
        }
        List<RoleDO> roles = userPermissionQueryService.getEnableUserRoleListByUserIdFromCache(userId);
        return roles.stream()
                .map(RoleDO::getCode)
                .collect(Collectors.toSet());
    }

    @Override
    public boolean currentUserHasGlobalProjectView() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        if (userId == null) {
            return false;
        }
        List<RoleDO> roles = userPermissionQueryService.getEnableUserRoleListByUserIdFromCache(userId);
        return roles.stream().anyMatch(role -> Integer.valueOf(1).equals(role.getGlobalProjectView()));
    }
}
