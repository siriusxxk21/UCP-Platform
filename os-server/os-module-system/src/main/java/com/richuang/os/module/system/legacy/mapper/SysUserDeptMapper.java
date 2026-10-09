package com.richuang.os.module.system.legacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richuang.os.module.system.legacy.entity.SysUserDept;
import com.richuang.os.module.system.legacy.vo.UserDeptVO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 用户部门关联Mapper
 */
// @Mapper -- 已废弃（v2.0 统一认证迁移）
public interface SysUserDeptMapper extends BaseMapper<SysUserDept> {

    /**
     * 分页查询部门用户
     */
    Page<UserDeptVO> selectDeptUsersPage(Page<UserDeptVO> page, @Param("deptId") String deptId, @Param("username") String username);

    /**
     * 根据用户ID查询部门关联
     */
    @Select("SELECT * FROM sys_user_dept WHERE user_id = #{userId}")
    List<SysUserDept> selectByUserId(@Param("userId") String userId);

    /**
     * 根据部门ID查询用户关联
     */
    @Select("SELECT * FROM sys_user_dept WHERE dept_id = #{deptId}")
    List<SysUserDept> selectByDeptId(@Param("deptId") String deptId);

    /**
     * 查询用户的主部门
     */
    default SysUserDept selectMainDept(@Param("userId") String userId) {
        return selectOne(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysUserDept>()
                .eq(SysUserDept::getUserId, userId)
                .eq(SysUserDept::getIsMain, 1));
    }

    /**
     * 删除用户的所有部门关联
     */
    @Delete("DELETE FROM sys_user_dept WHERE user_id = #{userId}")
    void deleteByUserId(@Param("userId") String userId);

    /**
     * 查询用户是否已在部门中
     */
    @Select("SELECT COUNT(*) FROM sys_user_dept WHERE user_id = #{userId} AND dept_id = #{deptId}")
    int countByUserIdAndDeptId(@Param("userId") String userId, @Param("deptId") String deptId);

    /**
     * 清除用户的主部门标识
     */
    @Update("UPDATE sys_user_dept SET is_main = 0 WHERE user_id = #{userId}")
    void clearMainDept(@Param("userId") String userId);

    /**
     * 获取部门用户列表
     */
    List<UserDeptVO> selectDeptUsers(@Param("deptId") String deptId);

    /**
     * 分页获取可添加到部门的用户
     */
    Page<UserDeptVO> selectAvailableUsersPage(Page<UserDeptVO> page, @Param("deptId") String deptId, @Param("username") String username);

    /**
     * 获取可添加到部门的用户（不在该部门的用户）
     */
    List<UserDeptVO> selectAvailableUsers(@Param("deptId") String deptId, @Param("username") String username);
}
