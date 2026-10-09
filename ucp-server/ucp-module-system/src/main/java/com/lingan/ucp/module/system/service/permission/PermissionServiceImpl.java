package com.lingan.ucp.module.system.service.permission;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.extra.spring.SpringUtil;
import com.lingan.ucp.framework.common.util.collection.CollectionUtils;
import com.lingan.ucp.framework.common.biz.system.permission.dto.DeptDataPermissionRespDTO;
import com.lingan.ucp.module.system.dal.dataobject.permission.MenuDO;
import com.lingan.ucp.module.system.dal.dataobject.permission.RoleDO;
import com.lingan.ucp.module.system.dal.dataobject.permission.RoleMenuDO;
import com.lingan.ucp.module.system.dal.dataobject.permission.UserRoleDO;
import com.lingan.ucp.module.system.dal.mysql.permission.RoleMenuMapper;
import com.lingan.ucp.module.system.dal.mysql.permission.UserRoleMapper;
import com.lingan.ucp.module.system.dal.redis.RedisKeyConstants;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.google.common.collect.Sets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.*;

import static com.lingan.ucp.framework.common.util.collection.CollectionUtils.convertSet;

/**
 * 权限 Service 实现类
 *
 * @author os
 */
@Service
@Slf4j
public class PermissionServiceImpl implements PermissionService {

    @Resource
    private RoleMenuMapper roleMenuMapper;
    @Resource
    private UserRoleMapper userRoleMapper;

    @Resource
    private PermissionRelationService permissionRelationService;
    @Resource
    private RoleService roleService;
    @Resource
    private MenuService menuService;
    @Resource
    private UserPermissionQueryService userPermissionQueryService;

    @Override
    public boolean hasAnyPermissions(Long userId, String... permissions) {
        // 如果为空，说明已经有权限
        if (ArrayUtil.isEmpty(permissions)) {
            return true;
        }

        // 获得当前登录的角色。如果为空，说明没有权限
        List<RoleDO> roles = userPermissionQueryService.getEnableUserRoleListByUserIdFromCache(userId);
        if (CollUtil.isEmpty(roles)) {
            return false;
        }

        // 情况一：遍历判断每个权限，如果有一满足，说明有权限
        for (String permission : permissions) {
            if (hasAnyPermission(roles, permission)) {
                return true;
            }
        }

        // 情况二：如果是超管，也说明有权限
        return roleService.hasAnySuperAdmin(convertSet(roles, RoleDO::getId));
    }

    /**
     * 判断指定角色，是否拥有该 permission 权限
     *
     * @param roles 指定角色数组
     * @param permission 权限标识
     * @return 是否拥有
     */
    private boolean hasAnyPermission(List<RoleDO> roles, String permission) {
        List<Long> menuIds = menuService.getMenuIdListByPermissionFromCache(permission);
        // 采用严格模式，如果权限找不到对应的 Menu 的话，也认为没有权限
        if (CollUtil.isEmpty(menuIds)) {
            return false;
        }

        // 判断是否有权限
        Set<Long> roleIds = convertSet(roles, RoleDO::getId);
        for (Long menuId : menuIds) {
            // 获得拥有该菜单的角色编号集合
            Set<Long> menuRoleIds = getSelf().getMenuRoleIdListByMenuIdFromCache(menuId);
            // 如果有交集，说明有权限
            if (CollUtil.containsAny(menuRoleIds, roleIds)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean hasAnyRoles(Long userId, String... roles) {
        // 如果为空，说明已经有权限
        if (ArrayUtil.isEmpty(roles)) {
            return true;
        }

        // 获得当前登录的角色。如果为空，说明没有权限
        List<RoleDO> roleList = userPermissionQueryService.getEnableUserRoleListByUserIdFromCache(userId);
        if (CollUtil.isEmpty(roleList)) {
            return false;
        }

        // 判断是否有角色
        Set<String> userRoles = convertSet(roleList, RoleDO::getCode);
        return CollUtil.containsAny(userRoles, Sets.newHashSet(roles));
    }

    // ========== 角色-菜单的相关方法  ==========

    @Override
    @DSTransactional // 多数据源，使用 @DSTransactional 保证本地事务，以及数据源的切换
    @Caching(evict = {
            @CacheEvict(value = RedisKeyConstants.MENU_ROLE_ID_LIST,
            allEntries = true),
            @CacheEvict(value = RedisKeyConstants.PERMISSION_MENU_ID_LIST,
            allEntries = true) // allEntries 清空所有缓存，主要一次更新涉及到的 menuIds 较多，反倒批量会更快
    })
    public void assignRoleMenu(Long roleId, Set<Long> menuIds) {
        // 获得角色拥有菜单编号
        Set<Long> dbMenuIds = convertSet(roleMenuMapper.selectListByRoleId(roleId), RoleMenuDO::getMenuId);
        // 计算新增和删除的菜单编号
        Set<Long> menuIdList = CollUtil.emptyIfNull(menuIds);
        Collection<Long> createMenuIds = CollUtil.subtract(menuIdList, dbMenuIds);
        Collection<Long> deleteMenuIds = CollUtil.subtract(dbMenuIds, menuIdList);
        // 执行新增和删除。对于已经授权的菜单，不用做任何处理
        if (CollUtil.isNotEmpty(createMenuIds)) {
            roleMenuMapper.insertBatch(CollectionUtils.convertList(createMenuIds, menuId -> {
                RoleMenuDO entity = new RoleMenuDO();
                entity.setRoleId(roleId);
                entity.setMenuId(menuId);
                return entity;
            }));
        }
        if (CollUtil.isNotEmpty(deleteMenuIds)) {
            roleMenuMapper.deleteListByRoleIdAndMenuIds(roleId, deleteMenuIds);
        }
    }

    @Override
    public void processRoleDeleted(Long roleId) {
        permissionRelationService.processRoleDeleted(roleId);
    }

    @Override
    @CacheEvict(value = RedisKeyConstants.MENU_ROLE_ID_LIST, key = "#menuId")
    public void processMenuDeleted(Long menuId) {
        roleMenuMapper.deleteListByMenuId(menuId);
    }

    @Override
    public Set<Long> getRoleMenuListByRoleId(Collection<Long> roleIds) {
        if (CollUtil.isEmpty(roleIds)) {
            return Collections.emptySet();
        }

        // 如果是管理员的情况下，获取全部菜单编号
        if (roleService.hasAnySuperAdmin(roleIds)) {
            return convertSet(menuService.getMenuList(), MenuDO::getId);
        }
        // 如果是非管理员的情况下，获得拥有的菜单编号
        return convertSet(roleMenuMapper.selectListByRoleId(roleIds), RoleMenuDO::getMenuId);
    }

    @Override
    @Cacheable(value = RedisKeyConstants.MENU_ROLE_ID_LIST, key = "#menuId")
    public Set<Long> getMenuRoleIdListByMenuIdFromCache(Long menuId) {
        return convertSet(roleMenuMapper.selectListByMenuId(menuId), RoleMenuDO::getRoleId);
    }

    // ========== 用户-角色的相关方法  ==========

    @Override
    @DSTransactional // 多数据源，使用 @DSTransactional 保证本地事务，以及数据源的切换
    @CacheEvict(value = RedisKeyConstants.USER_ROLE_ID_LIST, key = "#userId")
    public void assignUserRole(Long userId, Set<Long> roleIds) {
        // 获得角色拥有角色编号
        Set<Long> dbRoleIds = convertSet(userRoleMapper.selectListByUserId(userId),
                UserRoleDO::getRoleId);
        // 计算新增和删除的角色编号
        Set<Long> roleIdList = CollUtil.emptyIfNull(roleIds);
        Collection<Long> createRoleIds = CollUtil.subtract(roleIdList, dbRoleIds);
        Collection<Long> deleteMenuIds = CollUtil.subtract(dbRoleIds, roleIdList);
        // 执行新增和删除。对于已经授权的角色，不用做任何处理
        if (!CollectionUtil.isEmpty(createRoleIds)) {
            userRoleMapper.insertBatch(CollectionUtils.convertList(createRoleIds, roleId -> {
                UserRoleDO entity = new UserRoleDO();
                entity.setUserId(userId);
                entity.setRoleId(roleId);
                return entity;
            }));
        }
        if (!CollectionUtil.isEmpty(deleteMenuIds)) {
            userRoleMapper.deleteListByUserIdAndRoleIdIds(userId, deleteMenuIds);
        }
    }

    @Override
    @CacheEvict(value = RedisKeyConstants.USER_ROLE_ID_LIST, key = "#userId")
    public void processUserDeleted(Long userId) {
        permissionRelationService.processUserDeleted(userId);
    }

    @Override
    @DSTransactional
    @CacheEvict(value = RedisKeyConstants.USER_ROLE_ID_LIST, allEntries = true)
    public void assignRoleUsers(Long roleId, Set<Long> userIds) {
        Set<Long> targetUserIds = CollUtil.emptyIfNull(userIds);
        Set<Long> dbUserIds = convertSet(userRoleMapper.selectListByRoleIds(Collections.singleton(roleId)),
                UserRoleDO::getUserId);
        Collection<Long> createUserIds = CollUtil.subtract(targetUserIds, dbUserIds);
        Collection<Long> deleteUserIds = CollUtil.subtract(dbUserIds, targetUserIds);

        if (CollUtil.isNotEmpty(createUserIds)) {
            userRoleMapper.insertBatch(CollectionUtils.convertList(createUserIds, userId -> {
                UserRoleDO entity = new UserRoleDO();
                entity.setUserId(userId);
                entity.setRoleId(roleId);
                return entity;
            }));
        }
        if (CollUtil.isNotEmpty(deleteUserIds)) {
            userRoleMapper.deleteListByRoleIdAndUserIds(roleId, deleteUserIds);
        }
    }

    @Override
    public Set<Long> getUserRoleIdListByUserId(Long userId) {
        return userPermissionQueryService.getUserRoleIdListByUserId(userId);
    }

    @Override
    @Cacheable(value = RedisKeyConstants.USER_ROLE_ID_LIST, key = "#userId")
    public Set<Long> getUserRoleIdListByUserIdFromCache(Long userId) {
        return userPermissionQueryService.getUserRoleIdListByUserIdFromCache(userId);
    }

    @Override
    public Set<Long> getUserRoleIdListByRoleId(Collection<Long> roleIds) {
        return userPermissionQueryService.getUserRoleIdListByRoleId(roleIds);
    }

    // ========== 用户-部门的相关方法  ==========

    @Override
    public void assignRoleDataScope(Long roleId, Integer dataScope, Set<Long> dataScopeDeptIds) {
        roleService.updateRoleDataScope(roleId, dataScope, dataScopeDeptIds);
    }

    @Override
    public DeptDataPermissionRespDTO getDeptDataPermission(Long userId) {
        return userPermissionQueryService.getDeptDataPermission(userId);
    }

    /**
     * 获得自身的代理对象，解决 AOP 生效问题
     *
     * @return 自己
     */
    private PermissionServiceImpl getSelf() {
        return SpringUtil.getBean(getClass());
    }

}
