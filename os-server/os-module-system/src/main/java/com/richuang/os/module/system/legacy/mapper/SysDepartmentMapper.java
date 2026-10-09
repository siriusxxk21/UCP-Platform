package com.richuang.os.module.system.legacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.richuang.os.module.system.legacy.entity.SysDepartment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 部门Mapper
 */
// @Mapper -- 已废弃（v2.0 统一认证迁移）
public interface SysDepartmentMapper extends BaseMapper<SysDepartment> {

    /**
     * 根据租户ID查询部门列表
     */
    @Select("SELECT * FROM sys_department WHERE tenant_id = #{tenantId} AND deleted = 0 ORDER BY sort_order, id")
    List<SysDepartment> selectByTenantId(@Param("tenantId") String tenantId);

    /**
     * 根据组织ID查询部门列表
     */
    @Select("SELECT * FROM sys_department WHERE org_id = #{orgId} AND deleted = 0 ORDER BY sort_order, id")
    List<SysDepartment> selectByOrgId(@Param("orgId") String orgId);

    /**
     * 根据父ID查询子部门
     */
    @Select("SELECT * FROM sys_department WHERE parent_id = #{parentId} AND deleted = 0 ORDER BY sort_order, id")
    List<SysDepartment> selectByParentId(@Param("parentId") String parentId);

    /**
     * 根据编码查询部门
     */
    @Select("SELECT * FROM sys_department WHERE tenant_id = #{tenantId} AND dept_code = #{deptCode} AND deleted = 0")
    SysDepartment selectByCode(@Param("tenantId") String tenantId, @Param("deptCode") String deptCode);

    /**
     * 查询部门下的所有子部门ID
     */
    List<String> selectChildIds(@Param("parentIds") String parentIds);

    /**
     * 查询用户的部门列表
     */
    List<SysDepartment> selectByUserId(@Param("userId") String userId);
}
