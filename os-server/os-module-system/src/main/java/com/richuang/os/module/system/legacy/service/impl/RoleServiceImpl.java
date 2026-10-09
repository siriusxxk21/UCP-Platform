package com.richuang.os.module.system.legacy.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richuang.os.module.system.legacy.dto.RoleQueryDTO;
import com.richuang.os.module.system.legacy.entity.SysRole;
import com.richuang.os.module.system.legacy.entity.SysUser;
import com.richuang.os.module.system.legacy.mapper.SysRoleMapper;
import com.richuang.os.module.system.legacy.mapper.SysRoleMenuMapper;
import com.richuang.os.module.system.legacy.mapper.SysUserMapper;
import com.richuang.os.module.system.legacy.mapper.SysUserRoleMapper;
import com.richuang.os.module.system.legacy.service.RoleService;
import com.richuang.os.module.system.legacy.vo.RolePageVO;
import com.richuang.os.module.system.legacy.vo.RoleVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

// @Service -- 已废弃（v2.0 统一认证迁移）
@RequiredArgsConstructor
public class RoleServiceImpl implements RoleService {

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysUserMapper userMapper;

    @Override
    public RolePageVO list(RoleQueryDTO queryDTO) {
        Page<SysRole> page = new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize());
        LambdaQueryWrapper<SysRole> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(queryDTO.getRoleName())) {
            wrapper.like(SysRole::getRoleName, queryDTO.getRoleName());
        }
        if (queryDTO.getStatus() != null) {
            wrapper.eq(SysRole::getStatus, queryDTO.getStatus());
        }
        wrapper.eq(SysRole::getDeleted, 0);
        wrapper.orderByAsc(SysRole::getId);

        Page<SysRole> result = roleMapper.selectPage(page, wrapper);

        List<RoleVO> roleVOList = result.getRecords().stream().map(this::convertToVO).collect(Collectors.toList());

        RolePageVO vo = new RolePageVO();
        vo.setList(roleVOList);
        vo.setTotal(result.getTotal());
        return vo;
    }

    @Override
    public SysRole getById(String id) {
        return roleMapper.selectById(id);
    }

    @Override
    public void add(SysRole role) {
        roleMapper.insert(role);
    }

    @Override
    public void update(SysRole role) {
        roleMapper.updateById(role);
    }

    @Override
    @Transactional
    public void delete(String id) {
        SysRole role = new SysRole();
        role.setId(id);
        role.setDeleted(1);
        roleMapper.deleteById(id);
        // 删除角色菜单关联
        roleMenuMapper.deleteByRoleId(id);
    }

    @Override
    @Transactional
    public void batchDelete(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        for (String id : ids) {
            SysRole role = new SysRole();
            role.setId(id);
            role.setDeleted(1);
            roleMapper.deleteById(id);
            roleMenuMapper.deleteByRoleId(id);
        }
    }

    @Override
    public List<String> getRoleMenus(String roleId) {
        return roleMenuMapper.selectMenuIdsByRoleId(roleId);
    }

    @Override
    @Transactional
    public void updateRoleMenus(String roleId, List<String> menuIds) {
        // 删除原有权限
        roleMenuMapper.deleteByRoleId(roleId);
        // 添加新权限
        if (menuIds != null && !menuIds.isEmpty()) {
            for (String menuId : menuIds) {
                roleMenuMapper.insertRoleMenu(roleId, menuId);
            }
        }
    }

    private RoleVO convertToVO(SysRole role) {
        RoleVO vo = new RoleVO();
        BeanUtils.copyProperties(role, vo);
        return vo;
    }

    @Override
    public List<SysUser> getRoleUsers(String roleId) {
        // 查询角色下的所有用户ID
        List<String> userIds = userRoleMapper.selectUserIdsByRoleId(roleId);
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        // 查询用户详情
        return userMapper.selectBatchIds(userIds);
    }

    @Override
    @Transactional
    public void addRoleUsers(String roleId, List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        // 过滤掉已存在的关联
        List<String> existingUserIds = userRoleMapper.selectUserIdsByRoleId(roleId);
        List<String> newUserIds = userIds.stream()
                .filter(id -> !existingUserIds.contains(id))
                .collect(Collectors.toList());
        if (!newUserIds.isEmpty()) {
            userRoleMapper.batchInsert(roleId, newUserIds);
        }
    }

    @Override
    @Transactional
    public void removeRoleUsers(String roleId, List<String> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        userRoleMapper.batchDelete(roleId, userIds);
    }

    @Override
    public List<SysRole> listByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return roleMapper.selectBatchIds(ids);
    }
}
