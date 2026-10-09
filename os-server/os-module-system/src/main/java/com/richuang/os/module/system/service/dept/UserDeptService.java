package com.richuang.os.module.system.service.dept;

import com.richuang.os.framework.common.pojo.PageResult;
import com.richuang.os.module.system.controller.admin.dept.vo.dept.DeptUserRespVO;

import java.util.Collection;
import java.util.List;

/**
 * 用户部门关联服务。
 * 维护兼职部门关系，并保证唯一主部门与用户表主部门字段一致。
 */
public interface UserDeptService {

    PageResult<DeptUserRespVO> getDeptUsersPage(Long deptId, int pageNum, int pageSize, String username);

    List<DeptUserRespVO> getDeptUsers(Long deptId, String username);

    PageResult<DeptUserRespVO> getAvailableUsersPage(Long deptId, int pageNum, int pageSize, String username);

    List<DeptUserRespVO> getAvailableUsers(Long deptId, String username);

    void addUsers(Long deptId, Collection<Long> userIds, String post, boolean mainDept);

    void setMainDept(Long relationId, Long deptId);

    void removeUser(Long relationId);

    void updatePost(Long relationId, String post);

    void syncMainDept(Long userId, Long deptId);
}
