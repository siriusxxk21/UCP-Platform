package com.richuang.os.module.system.legacy.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.richuang.os.module.system.legacy.dto.TenantQueryDTO;
import com.richuang.os.module.system.legacy.entity.SysTenant;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 租户Mapper
 */
// @Mapper -- 已废弃（v2.0 统一认证迁移）
public interface SysTenantMapper extends BaseMapper<SysTenant> {

    /**
     * 分页查询租户列表
     */
    Page<SysTenant> selectTenantPage(Page<SysTenant> page, @Param("query") TenantQueryDTO query);

    /**
     * 根据编码查询租户
     */
    @Select("SELECT * FROM sys_tenant WHERE tenant_code = #{tenantCode} AND deleted = 0")
    SysTenant selectByCode(@Param("tenantCode") String tenantCode);

    /**
     * 根据域名查询租户
     */
    @Select("SELECT * FROM sys_tenant WHERE domain = #{domain} AND deleted = 0")
    SysTenant selectByDomain(@Param("domain") String domain);

    /**
     * 查询所有有效租户
     */
    @Select("SELECT * FROM sys_tenant WHERE status = 1 AND deleted = 0")
    List<SysTenant> selectAllValid();
}
