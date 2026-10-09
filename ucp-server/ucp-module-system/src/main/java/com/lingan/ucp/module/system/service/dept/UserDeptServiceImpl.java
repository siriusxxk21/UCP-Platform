package com.lingan.ucp.module.system.service.dept;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingan.ucp.framework.common.pojo.PageResult;
import com.lingan.ucp.module.system.controller.admin.dept.vo.dept.DeptUserRespVO;
import com.lingan.ucp.module.system.dal.dataobject.dept.DeptDO;
import com.lingan.ucp.module.system.dal.dataobject.dept.UserDeptDO;
import com.lingan.ucp.module.system.dal.dataobject.user.AdminUserDO;
import com.lingan.ucp.module.system.dal.mysql.dept.UserDeptMapper;
import com.lingan.ucp.module.system.dal.mysql.user.AdminUserMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.DEPT_MAIN_RELATION_REMOVE_FORBIDDEN;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.DEPT_NOT_FOUND;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.DEPT_USER_RELATION_NOT_FOUND;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.USER_NOT_EXISTS;

/**
 * 用户部门关联服务实现。
 * 关联表承载多部门关系，用户表只保存权限体系使用的主部门。
 */
@Service
public class UserDeptServiceImpl implements UserDeptService {

    private static final int MAIN_DEPT = 1;
    private static final int SECONDARY_DEPT = 0;

    @Resource
    private UserDeptMapper userDeptMapper;
    @Resource
    private AdminUserMapper userMapper;
    @Resource
    private DeptService deptService;

    @Override
    public PageResult<DeptUserRespVO> getDeptUsersPage(Long deptId, int pageNum, int pageSize, String username) {
        ensureDeptExists(deptId);
        Page<DeptUserRespVO> page = userDeptMapper.selectDeptUsersPage(
                new Page<>(pageNum, pageSize), deptId, username);
        return new PageResult<>(page.getRecords(), page.getTotal());
    }

    @Override
    public List<DeptUserRespVO> getDeptUsers(Long deptId, String username) {
        ensureDeptExists(deptId);
        return userDeptMapper.selectDeptUsers(deptId, username);
    }

    @Override
    public PageResult<DeptUserRespVO> getAvailableUsersPage(
            Long deptId, int pageNum, int pageSize, String username) {
        DeptDO dept = ensureDeptExists(deptId);
        Page<DeptUserRespVO> page = userDeptMapper.selectAvailableUsersPage(
                new Page<>(pageNum, pageSize), deptId, dept.getOrgId(), username);
        return new PageResult<>(page.getRecords(), page.getTotal());
    }

    @Override
    public List<DeptUserRespVO> getAvailableUsers(Long deptId, String username) {
        DeptDO dept = ensureDeptExists(deptId);
        return userDeptMapper.selectAvailableUsers(deptId, dept.getOrgId(), username);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void addUsers(Long deptId, Collection<Long> userIds, String post, boolean mainDept) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        ensureDeptExists(deptId);
        for (Long userId : userIds) {
            if (userId == null) {
                continue;
            }
            ensureUserExists(userId);
            UserDeptDO relation = findRelation(userId, deptId);
            if (relation == null) {
                relation = new UserDeptDO();
                relation.setUserId(userId);
                relation.setDeptId(deptId);
                relation.setPost(post);
                relation.setIsMain(SECONDARY_DEPT);
                userDeptMapper.insert(relation);
            } else if (post != null && !post.equals(relation.getPost())) {
                relation.setPost(post);
                userDeptMapper.updateById(relation);
            }
            if (mainDept) {
                setMainDeptForUser(userId, relation);
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setMainDept(Long relationId, Long deptId) {
        UserDeptDO relation = getRelation(relationId);
        if (!Objects.equals(relation.getDeptId(), deptId)) {
            throw exception(DEPT_USER_RELATION_NOT_FOUND);
        }
        setMainDeptForUser(relation.getUserId(), relation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeUser(Long relationId) {
        UserDeptDO relation = getRelation(relationId);
        if (Integer.valueOf(MAIN_DEPT).equals(relation.getIsMain())) {
            throw exception(DEPT_MAIN_RELATION_REMOVE_FORBIDDEN);
        }
        userDeptMapper.deleteById(relationId);
    }

    @Override
    public void updatePost(Long relationId, String post) {
        UserDeptDO relation = getRelation(relationId);
        relation.setPost(post);
        userDeptMapper.updateById(relation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncMainDept(Long userId, Long deptId) {
        if (userId == null) {
            return;
        }
        lockUser(userId);
        clearMainRelation(userId);
        if (deptId == null) {
            updateUserMainDept(userId, null, null);
            return;
        }
        DeptDO dept = ensureDeptExists(deptId);
        UserDeptDO relation = findRelation(userId, deptId);
        if (relation == null) {
            relation = new UserDeptDO();
            relation.setUserId(userId);
            relation.setDeptId(deptId);
            relation.setIsMain(MAIN_DEPT);
            userDeptMapper.insert(relation);
        } else {
            relation.setIsMain(MAIN_DEPT);
            userDeptMapper.updateById(relation);
        }
        updateUserMainDept(userId, deptId, dept.getOrgId());
    }

    private void setMainDeptForUser(Long userId, UserDeptDO relation) {
        lockUser(userId);
        DeptDO dept = ensureDeptExists(relation.getDeptId());
        clearMainRelation(userId);
        relation.setIsMain(MAIN_DEPT);
        userDeptMapper.updateById(relation);

        updateUserMainDept(userId, relation.getDeptId(), dept.getOrgId());
    }

    private void updateUserMainDept(Long userId, Long deptId, Long orgId) {
        userMapper.update(null, new LambdaUpdateWrapper<AdminUserDO>()
                .set(AdminUserDO::getDeptId, deptId)
                .set(AdminUserDO::getOrgId, orgId)
                .eq(AdminUserDO::getId, userId));
    }

    private void clearMainRelation(Long userId) {
        userDeptMapper.update(null, new LambdaUpdateWrapper<UserDeptDO>()
                .set(UserDeptDO::getIsMain, SECONDARY_DEPT)
                .eq(UserDeptDO::getUserId, userId)
                .eq(UserDeptDO::getIsMain, MAIN_DEPT));
    }

    private UserDeptDO findRelation(Long userId, Long deptId) {
        return userDeptMapper.selectOne(new LambdaQueryWrapper<UserDeptDO>()
                .eq(UserDeptDO::getUserId, userId)
                .eq(UserDeptDO::getDeptId, deptId));
    }

    private UserDeptDO getRelation(Long relationId) {
        UserDeptDO relation = userDeptMapper.selectById(relationId);
        if (relation == null) {
            throw exception(DEPT_USER_RELATION_NOT_FOUND);
        }
        return relation;
    }

    private DeptDO ensureDeptExists(Long deptId) {
        DeptDO dept = deptService.getDept(deptId);
        if (dept == null) {
            throw exception(DEPT_NOT_FOUND);
        }
        return dept;
    }

    private void ensureUserExists(Long userId) {
        if (userMapper.selectById(userId) == null) {
            throw exception(USER_NOT_EXISTS);
        }
    }

    private void lockUser(Long userId) {
        if (userDeptMapper.lockUser(userId) == null) {
            throw exception(USER_NOT_EXISTS);
        }
    }
}
