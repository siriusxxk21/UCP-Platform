package com.lingan.ucp.module.system.api.organization;

import com.lingan.ucp.framework.common.util.object.BeanUtils;
import com.lingan.ucp.module.system.api.organization.dto.OrganizationRespDTO;
import com.lingan.ucp.module.system.dal.dataobject.organization.OrganizationDO;
import com.lingan.ucp.module.system.service.organization.OrganizationService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;

/**
 * 组织 API 实现类
 */
@Service
public class OrganizationApiImpl implements OrganizationApi {

    @Resource
    private OrganizationService organizationService;

    @Override
    public OrganizationRespDTO getOrganization(Long id) {
        return BeanUtils.toBean(organizationService.getOrganization(id), OrganizationRespDTO.class);
    }

    @Override
    public List<OrganizationRespDTO> getOrganizationList(Collection<Long> ids) {
        return BeanUtils.toBean(organizationService.getOrganizationList(ids), OrganizationRespDTO.class);
    }

    @Override
    public List<OrganizationRespDTO> getChildOrganizationList(Long id) {
        List<OrganizationDO> organizations = organizationService.getChildOrganizationList(id);
        return BeanUtils.toBean(organizations, OrganizationRespDTO.class);
    }

    @Override
    public void validateOrganizationList(Collection<Long> ids) {
        organizationService.validateOrganizationList(ids);
    }

}
