package com.lingan.ucp.module.system.legacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingan.ucp.module.system.legacy.entity.SysUserRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

// @Mapper -- 已废弃（v2.0 统一认证迁移）
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {

    /**
     * 根据角色ID查询用户ID列表
     */
    @Select("SELECT user_id FROM sys_user_role WHERE role_id = #{roleId}")
    List<String> selectUserIdsByRoleId(@Param("roleId") String roleId);

    /**
     * 根据用户ID查询角色ID列表
     */
    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId}")
    List<String> selectRoleIdsByUserId(@Param("userId") String userId);

    /**
     * 批量插入用户角色关联
     */
    void batchInsert(@Param("roleId") String roleId, @Param("userIds") List<String> userIds);

    /**
     * 批量删除用户角色关联
     */
    void batchDelete(@Param("roleId") String roleId, @Param("userIds") List<String> userIds);

    /**
     * 根据角色ID删除所有关联
     */
    void deleteByRoleId(@Param("roleId") String roleId);

    /**
     * 根据多个角色ID查询用户ID列表
     */
    @Select("<script>" +
            "SELECT DISTINCT user_id FROM sys_user_role WHERE role_id IN " +
            "<foreach collection='roleIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            "</script>")
    List<String> selectUserIdsByRoleIds(@Param("roleIds") List<String> roleIds);
}
