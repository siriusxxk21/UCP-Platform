package com.lingan.ucp.module.system.legacy.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingan.ucp.module.system.legacy.dto.UserDeptDTO;
import com.lingan.ucp.module.system.legacy.vo.UserDeptVO;

/**
 * 用户部门关联服务接口
 */
public interface SysUserDeptService {

    /**
     * 分页获取部门用户列表
     *
     * @param deptId   部门ID
     * @param username 用户名（模糊查询）
     * @param page     分页参数
     * @return 用户分页列表
     */
    Page<UserDeptVO> getDeptUsersPage(String deptId, String username, Page<UserDeptVO> page);

    /**
     * 获取部门用户列表（不分页）
     *
     * @param deptId 部门ID
     * @return 用户列表
     */
    java.util.List<UserDeptVO> getDeptUsers(String deptId);

    /**
     * 分页获取可添加到部门的用户
     *
     * @param deptId   部门ID
     * @param username 用户名（模糊查询）
     * @param page     分页参数
     * @return 用户分页列表
     */
    Page<UserDeptVO> getAvailableUsersPage(String deptId, String username, Page<UserDeptVO> page);

    /**
     * 获取可添加到部门的用户（不分页）
     *
     * @param deptId   部门ID
     * @param username 用户名（模糊查询）
     * @return 用户列表
     */
    java.util.List<UserDeptVO> getAvailableUsers(String deptId, String username);

    /**
     * 添加用户到部门
     *
     * @param dto 用户部门关联信息
     */
    void addUserToDept(UserDeptDTO dto);

    /**
     * 批量添加用户到部门
     *
     * @param deptId  部门ID
     * @param userIds 用户ID列表
     * @param post    岗位
     * @param isMain  是否主部门
     */
    void batchAddUsersToDept(String deptId, java.util.List<String> userIds, String post, Boolean isMain);

    /**
     * 移除部门用户
     *
     * @param id 关联ID
     */
    void removeUserFromDept(String id);

    /**
     * 设置为主部门
     *
     * @param id 关联ID
     */
    void setAsMainDept(String id);

    /**
     * 更新用户岗位
     *
     * @param id   关联ID
     * @param post 岗位
     */
    void updateUserPost(String id, String post);
}
