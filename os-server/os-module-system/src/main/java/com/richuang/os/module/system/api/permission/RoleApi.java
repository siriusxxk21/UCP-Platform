package com.richuang.os.module.system.api.permission;

import com.richuang.os.framework.common.util.collection.CollectionUtils;
import com.richuang.os.module.system.api.permission.dto.RoleRespDTO;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 角色 API 接口
 *
 * @author os
 */
public interface RoleApi {

    /**
     * 校验角色们是否有效。如下情况，视为无效：
     * 1. 角色编号不存在
     * 2. 角色被禁用
     *
     * @param ids 角色编号数组
     */
    void validRoleList(Collection<Long> ids);

    /**
     * 获得角色信息
     *
     * @param id 角色编号
     * @return 角色信息
     */
    RoleRespDTO getRole(Long id);

    /**
     * 获得角色信息数组
     *
     * @param ids 角色编号数组
     * @return 角色信息数组
     */
    List<RoleRespDTO> getRoleList(Collection<Long> ids);

    /**
     * 获得指定编号的角色 Map
     *
     * @param ids 角色编号数组
     * @return 角色 Map
     */
    default Map<Long, RoleRespDTO> getRoleMap(Collection<Long> ids) {
        List<RoleRespDTO> list = getRoleList(ids);
        return CollectionUtils.convertMap(list, RoleRespDTO::getId);
    }

    /**
     * 获取当前登录用户的所有系统角色 code 集合
     *
     * @return 角色 code 集合（如 ["EXECUTIVE", "MANAGER"]），用户未登录返回空集合
     */
    Set<String> getCurrentUserRoleCodes();

    /**
     * 判断当前登录用户是否拥有全局项目访问权
     * 
     * 即用户是否拥有 global_project_view=1 的系统角色
     *
     * @return true=可访问所有项目空间（只读），false=无全局访问权
     */
    boolean currentUserHasGlobalProjectView();

}
