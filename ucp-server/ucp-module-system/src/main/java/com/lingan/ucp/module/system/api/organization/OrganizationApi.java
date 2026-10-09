package com.lingan.ucp.module.system.api.organization;

import com.lingan.ucp.framework.common.util.collection.CollectionUtils;
import com.lingan.ucp.module.system.api.organization.dto.OrganizationRespDTO;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 组织 API 接口
 */
public interface OrganizationApi {

    /**
     * 获得组织信息
     *
     * @param id 组织编号
     * @return 组织信息
     */
    OrganizationRespDTO getOrganization(Long id);

    /**
     * 获得组织列表
     *
     * @param ids 组织编号集合
     * @return 组织列表
     */
    List<OrganizationRespDTO> getOrganizationList(Collection<Long> ids);

    /**
     * 获得指定组织的所有子组织
     *
     * @param id 组织编号
     * @return 子组织列表
     */
    List<OrganizationRespDTO> getChildOrganizationList(Long id);

    /**
     * 校验组织列表是否有效。
     *
     * @param ids 组织编号集合
     */
    void validateOrganizationList(Collection<Long> ids);

    /**
     * 获得指定编号的组织 Map
     *
     * @param ids 组织编号集合
     * @return 组织 Map
     */
    default Map<String, OrganizationRespDTO> getOrganizationMap(Collection<Long> ids) {
        return CollectionUtils.convertMap(getOrganizationList(ids), OrganizationRespDTO::getId);
    }

}
