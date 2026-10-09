package com.richuang.os.module.system.api.permission;

import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;

import java.util.Collection;
import java.util.Set;

/**
 * 权限 API 接口
 *
 * @author os
 */
public interface PermissionApi extends PermissionCommonApi {

    /** 读取当前用户角色关联，绕过角色关联缓存；调用方另行核对角色启用状态。 */
    Set<Long> getUserRoleIds(Long userId);

    /**
     * 获得拥有多个角色的用户编号集合
     *
     * @param roleIds 角色编号集合
     * @return 用户编号集合
     */
    Set<Long> getUserRoleIdListByRoleIds(Collection<Long> roleIds);
}
