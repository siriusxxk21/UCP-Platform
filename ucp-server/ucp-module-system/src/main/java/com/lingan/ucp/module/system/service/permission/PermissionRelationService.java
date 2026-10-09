package com.lingan.ucp.module.system.service.permission;

/**
 * 权限关联数据 Service。
 *
 * @author os
 */
public interface PermissionRelationService {

    /**
     * 处理角色删除时，删除关联授权数据。
     *
     * @param roleId 角色编号
     */
    void processRoleDeleted(Long roleId);

    /**
     * 处理用户删除时，删除关联授权数据。
     *
     * @param userId 用户编号
     */
    void processUserDeleted(Long userId);

}
