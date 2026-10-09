package com.richuang.os.module.system.legacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richuang.os.module.system.legacy.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

// @Mapper -- 已废弃（v2.0 统一认证迁移）
public interface SysUserMapper extends BaseMapper<SysUser> {

    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted = 0")
    SysUser selectByUsername(@Param("username") String username);

    /**
     * 根据用户名和租户ID查询用户（租户范围内唯一性校验）
     */
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND tenant_id = #{tenantId} AND deleted = 0")
    SysUser selectByUsernameAndTenantId(@Param("username") String username, @Param("tenantId") String tenantId);

    /**
     * 查询租户下所有用户ID
     */
    @Select("SELECT id FROM sys_user WHERE tenant_id = #{tenantId} AND deleted = 0 AND status = 1")
    List<String> selectUserIdsByTenantId(@Param("tenantId") String tenantId);

    /**
     * 查询组织下所有用户ID
     */
    @Select("<script>" +
            "SELECT id FROM sys_user WHERE org_id IN " +
            "<foreach collection='orgIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            " AND deleted = 0 AND status = 1" +
            "</script>")
    List<String> selectUserIdsByOrgIds(@Param("orgIds") List<String> orgIds);

    /**
     * 查询部门下所有用户ID
     */
    @Select("<script>" +
            "SELECT id FROM sys_user WHERE dept_id IN " +
            "<foreach collection='deptIds' item='id' open='(' separator=',' close=')'>" +
            "#{id}" +
            "</foreach>" +
            " AND deleted = 0 AND status = 1" +
            "</script>")
    List<String> selectUserIdsByDeptIds(@Param("deptIds") List<String> deptIds);
}
