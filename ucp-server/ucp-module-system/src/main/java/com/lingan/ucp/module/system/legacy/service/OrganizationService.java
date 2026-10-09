package com.lingan.ucp.module.system.legacy.service;

import com.lingan.ucp.module.system.legacy.dto.OrganizationDTO;
import com.lingan.ucp.module.system.legacy.entity.SysOrganization;
import com.lingan.ucp.module.system.legacy.vo.OrgDeptTreeVO;
import com.lingan.ucp.module.system.legacy.vo.OrganizationTreeVO;

import java.util.List;

/**
 * 组织服务接口
 */
public interface OrganizationService {

    /**
     * 获取当前租户下所有组织与部门构成的混合树
     */
    List<OrgDeptTreeVO> getOrgDeptTree();

    /**
     * 获取组织树
     */
    List<OrganizationTreeVO> getTree(String tenantId);

    /**
     * 获取当前租户的组织树
     */
    List<OrganizationTreeVO> getCurrentTenantTree();

    /**
     * 根据ID查询组织
     */
    SysOrganization getById(String id);

    /**
     * 创建组织
     */
    String create(OrganizationDTO dto);

    /**
     * 更新组织
     */
    void update(OrganizationDTO dto);

    /**
     * 删除组织
     */
    void delete(String id);

    /**
     * 修改组织状态
     */
    void updateStatus(String id, Integer status);

    /**
     * 检查组织编码是否存在
     */
    boolean checkCodeExists(String orgCode, String excludeId);

    /**
     * 获取组织的所有子组织ID
     */
    List<String> getChildIds(String orgId);

    /**
     * 根据ID列表查询组织
     */
    List<OrganizationTreeVO> listByIds(List<String> ids);
}
