package com.richuang.os.module.system.legacy.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richuang.os.common.exception.BusinessException;
import com.richuang.os.module.system.legacy.dto.UserDeptDTO;
import com.richuang.os.module.system.legacy.entity.SysDepartment;
import com.richuang.os.module.system.legacy.entity.SysUser;
import com.richuang.os.module.system.legacy.entity.SysUserDept;
import com.richuang.os.module.system.legacy.mapper.SysDepartmentMapper;
import com.richuang.os.module.system.legacy.mapper.SysUserDeptMapper;
import com.richuang.os.module.system.legacy.mapper.SysUserMapper;
import com.richuang.os.module.system.legacy.service.SysUserDeptService;
import com.richuang.os.module.system.legacy.vo.UserDeptVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户部门关联服务实现
 */
@Slf4j
// @Service -- 已废弃（v2.0 统一认证迁移）
@RequiredArgsConstructor
public class SysUserDeptServiceImpl implements SysUserDeptService {

    private final SysUserDeptMapper userDeptMapper;
    private final SysUserMapper userMapper;
    private final SysDepartmentMapper departmentMapper;

    @Override
    public Page<UserDeptVO> getDeptUsersPage(String deptId, String username, Page<UserDeptVO> page) {
        return userDeptMapper.selectDeptUsersPage(page, deptId, username);
    }

    @Override
    public List<UserDeptVO> getDeptUsers(String deptId) {
        return userDeptMapper.selectDeptUsers(deptId);
    }

    @Override
    public Page<UserDeptVO> getAvailableUsersPage(String deptId, String username, Page<UserDeptVO> page) {
        return userDeptMapper.selectAvailableUsersPage(page, deptId, username);
    }

    @Override
    public List<UserDeptVO> getAvailableUsers(String deptId, String username) {
        return userDeptMapper.selectAvailableUsers(deptId, username);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addUserToDept(UserDeptDTO dto) {
        // 检查用户是否存在
        SysUser user = userMapper.selectById(dto.getUserId());
        if (user == null || user.getDeleted() == 1) {
            throw new BusinessException("用户不存在");
        }

        // 检查部门是否存在
        SysDepartment dept = departmentMapper.selectById(dto.getDeptId());
        if (dept == null || dept.getDeleted() == 1) {
            throw new BusinessException("部门不存在");
        }

        // 检查用户是否已在部门中
        int count = userDeptMapper.countByUserIdAndDeptId(dto.getUserId(), dto.getDeptId());
        if (count > 0) {
            throw new BusinessException("用户已在该部门中");
        }

        // 如果设置为主部门，先清除其他主部门标识
        if (Boolean.TRUE.equals(dto.getIsMain())) {
            userDeptMapper.clearMainDept(dto.getUserId());
        }

        SysUserDept userDept = new SysUserDept();
        userDept.setUserId(dto.getUserId());
        userDept.setDeptId(dto.getDeptId());
        userDept.setPost(dto.getPost());
        userDept.setIsMain(Boolean.TRUE.equals(dto.getIsMain()) ? 1 : 0);
        userDept.setCreateTime(LocalDateTime.now());

        userDeptMapper.insert(userDept);

        // 如果设置为主部门，同步更新用户表的orgId和deptId
        if (Boolean.TRUE.equals(dto.getIsMain())) {
            SysUser userUpdate = new SysUser();
            userUpdate.setId(dto.getUserId());
            userUpdate.setOrgId(dept.getOrgId());
            userUpdate.setDeptId(dto.getDeptId());
            userMapper.updateById(userUpdate);
        }

        log.info("添加用户到部门成功: userId={}, deptId={}", dto.getUserId(), dto.getDeptId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void batchAddUsersToDept(String deptId, List<String> userIds, String post, Boolean isMain) {
        if (CollectionUtils.isEmpty(userIds)) {
            return;
        }

        // 检查部门是否存在
        SysDepartment dept = departmentMapper.selectById(deptId);
        if (dept == null || dept.getDeleted() == 1) {
            throw new BusinessException("部门不存在");
        }

        int addedCount = 0;
        for (String userId : userIds) {
            // 检查用户是否存在且未被删除
            SysUser user = userMapper.selectById(userId);
            if (user == null || user.getDeleted() == 1) {
                continue; // 跳过无效用户
            }

            // 跳过已在部门的用户
            int count = userDeptMapper.countByUserIdAndDeptId(userId, deptId);
            if (count > 0) {
                continue;
            }

            // 如果设置为主部门，先清除其他主部门标识
            if (Boolean.TRUE.equals(isMain)) {
                userDeptMapper.clearMainDept(userId);
            }

            SysUserDept userDept = new SysUserDept();
            userDept.setUserId(userId);
            userDept.setDeptId(deptId);
            userDept.setPost(post);
            userDept.setIsMain(Boolean.TRUE.equals(isMain) ? 1 : 0);
            userDept.setCreateTime(LocalDateTime.now());

            userDeptMapper.insert(userDept);

            // 如果设置为主部门，同步更新用户表的orgId和deptId
            if (Boolean.TRUE.equals(isMain)) {
                SysUser userUpdate = new SysUser();
                userUpdate.setId(userId);
                userUpdate.setOrgId(dept.getOrgId());
                userUpdate.setDeptId(deptId);
                userMapper.updateById(userUpdate);
            }

            addedCount++;
        }
        log.info("批量添加用户到部门成功: deptId={}, addedCount={}", deptId, addedCount);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeUserFromDept(String id) {
        SysUserDept userDept = userDeptMapper.selectById(id);
        if (userDept == null) {
            throw new BusinessException("关联记录不存在");
        }

        userDeptMapper.deleteById(id);
        log.info("移除部门用户成功: id={}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setAsMainDept(String id) {
        SysUserDept userDept = userDeptMapper.selectById(id);
        if (userDept == null) {
            throw new BusinessException("关联记录不存在");
        }

        // 先清除该用户的其他主部门标识
        userDeptMapper.clearMainDept(userDept.getUserId());

        // 设置当前为主部门
        SysUserDept update = new SysUserDept();
        update.setId(id);
        update.setIsMain(1);
        userDeptMapper.updateById(update);

        // 获取部门信息以获取组织ID
        SysDepartment dept = departmentMapper.selectById(userDept.getDeptId());
        if (dept != null) {
            // 同步更新用户表的orgId和deptId
            SysUser userUpdate = new SysUser();
            userUpdate.setId(userDept.getUserId());
            userUpdate.setOrgId(dept.getOrgId());
            userUpdate.setDeptId(userDept.getDeptId());
            userMapper.updateById(userUpdate);
        }

        log.info("设置主部门成功: userId={}, deptId={}", userDept.getUserId(), userDept.getDeptId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserPost(String id, String post) {
        SysUserDept userDept = userDeptMapper.selectById(id);
        if (userDept == null) {
            throw new BusinessException("关联记录不存在");
        }

        SysUserDept update = new SysUserDept();
        update.setId(id);
        update.setPost(post);
        userDeptMapper.updateById(update);

        log.info("更新用户岗位成功: id={}, post={}", id, post);
    }
}
