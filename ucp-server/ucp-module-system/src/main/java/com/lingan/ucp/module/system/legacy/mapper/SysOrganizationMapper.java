package com.lingan.ucp.module.system.legacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingan.ucp.module.system.legacy.entity.SysOrganization;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 组织Mapper
 */
// @Mapper -- 已废弃（v2.0 统一认证迁移）
public interface SysOrganizationMapper extends BaseMapper<SysOrganization> {

    /**
     * 根据租户ID查询组织列表
     */
    @Select("SELECT * FROM sys_organization WHERE tenant_id = #{tenantId} AND deleted = 0 ORDER BY sort_order, id")
    List<SysOrganization> selectByTenantId(@Param("tenantId") String tenantId);

    /**
     * 根据父ID查询子组织
     */
    @Select("SELECT * FROM sys_organization WHERE parent_id = #{parentId} AND deleted = 0 ORDER BY sort_order, id")
    List<SysOrganization> selectByParentId(@Param("parentId") String parentId);

    /**
     * 根据编码查询组织
     */
    @Select("SELECT * FROM sys_organization WHERE tenant_id = #{tenantId} AND org_code = #{orgCode} AND deleted = 0")
    SysOrganization selectByCode(@Param("tenantId") String tenantId, @Param("orgCode") String orgCode);

    /**
     * 查询组织下的所有子组织ID
     */
    List<String> selectChildIds(@Param("parentIds") String parentIds);
}
