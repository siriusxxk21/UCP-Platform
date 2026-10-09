package com.lingan.ucp.module.system.dal.mysql.organization;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lingan.ucp.framework.mybatis.core.mapper.BaseMapperX;
import com.lingan.ucp.framework.mybatis.core.query.LambdaQueryWrapperX;
import com.lingan.ucp.module.system.dal.dataobject.organization.OrganizationDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

@Mapper
public interface OrganizationMapper extends BaseMapperX<OrganizationDO> {

    @Select("SELECT * FROM system_organization WHERE id = #{id} AND deleted = 0 FOR UPDATE")
    OrganizationDO selectByIdForUpdate(Long id);

    default List<OrganizationDO> selectListAll() {
        return selectList(new LambdaQueryWrapperX<OrganizationDO>()
                .orderByAsc(OrganizationDO::getSortOrder)
                .orderByAsc(OrganizationDO::getId));
    }

    default List<OrganizationDO> selectListByTenantId(Long tenantId) {
        return selectList(new LambdaQueryWrapperX<OrganizationDO>()
                .eq(OrganizationDO::getTenantId, tenantId)
                .orderByAsc(OrganizationDO::getSortOrder)
                .orderByAsc(OrganizationDO::getId));
    }

    default List<OrganizationDO> selectListByParentId(Long parentId) {
        return selectList(new LambdaQueryWrapperX<OrganizationDO>()
                .eq(OrganizationDO::getParentId, parentId)
                .orderByAsc(OrganizationDO::getSortOrder)
                .orderByAsc(OrganizationDO::getId));
    }

    default OrganizationDO selectByTenantIdAndOrgCode(Long tenantId, String orgCode) {
        return selectOne(new QueryWrapper<OrganizationDO>().lambda()
                .isNull(tenantId == null, OrganizationDO::getTenantId)
                .eq(tenantId != null, OrganizationDO::getTenantId, tenantId)
                .eq(OrganizationDO::getOrgCode, orgCode));
    }

    default List<OrganizationDO> selectListByParentIdsPrefix(String parentIdsPrefix) {
        return selectList(new LambdaQueryWrapperX<OrganizationDO>()
                .likeRight(OrganizationDO::getParentIds, parentIdsPrefix)
                .orderByAsc(OrganizationDO::getLevel)
                .orderByAsc(OrganizationDO::getSortOrder)
                .orderByAsc(OrganizationDO::getId));
    }

    default List<OrganizationDO> selectListByIds(Collection<Long> ids) {
        return selectBatchIds(ids);
    }

}
