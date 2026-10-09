package com.lingan.ucp.module.system.legacy.service;

import com.lingan.ucp.module.system.legacy.dto.RoleQueryDTO;
import com.lingan.ucp.module.system.legacy.entity.SysRole;
import com.lingan.ucp.module.system.legacy.entity.SysUser;
import com.lingan.ucp.module.system.legacy.vo.RolePageVO;

import java.util.List;

public interface RoleService {
    RolePageVO list(RoleQueryDTO queryDTO);

    SysRole getById(String id);

    void add(SysRole role);

    void update(SysRole role);

    void delete(String id);

    void batchDelete(List<String> ids);

    List<String> getRoleMenus(String roleId);

    void updateRoleMenus(String roleId, List<String> menuIds);

    // 角色用户管理
    List<SysUser> getRoleUsers(String roleId);

    void addRoleUsers(String roleId, List<String> userIds);

    void removeRoleUsers(String roleId, List<String> userIds);

    /**
     * 根据ID列表查询角色
     */
    List<SysRole> listByIds(List<String> ids);
}
