package com.richuang.os.module.system.service.organization;

import com.richuang.os.module.system.controller.admin.organization.vo.OrgDeptTreeRespVO;
import com.richuang.os.module.system.controller.admin.organization.vo.OrganizationSaveReqVO;
import com.richuang.os.module.system.controller.admin.organization.vo.OrganizationTreeRespVO;
import com.richuang.os.module.system.dal.dataobject.organization.OrganizationDO;

import java.util.Collection;
import java.util.List;

/**
 * 组织 Service 接口
 */
public interface OrganizationService {

    /**
     * 获取当前租户组织与部门混合树。
     *
     * 当前新组织模型未提供部门归属关系，因此只返回组织节点。
     */
    List<OrgDeptTreeRespVO> getOrgDeptTree();

    /**
     * 获取指定租户组织树。
     *
     * @param tenantId 租户编号
     * @return 组织树
     */
    List<OrganizationTreeRespVO> getTree(Long tenantId);

    /**
     * 获取当前租户组织树。
     *
     * @return 组织树
     */
    List<OrganizationTreeRespVO> getCurrentTenantTree();

    /**
     * 获取当前租户组织树。
     *
     * @param orgName 组织名称，模糊匹配
     * @param status 状态
     * @return 组织树
     */
    List<OrganizationTreeRespVO> getCurrentTenantTree(String orgName, Integer status);

    /**
     * 获得组织信息。
     *
     * @param id 组织编号
     * @return 组织信息
     */
    OrganizationDO getOrganization(Long id);

    /**
     * 获得组织列表。
     *
     * @param ids 组织编号集合
     * @return 组织列表
     */
    List<OrganizationDO> getOrganizationList(Collection<Long> ids);

    /**
     * 创建组织。
     *
     * @param createReqVO 组织信息
     * @return 组织编号
     */
     Long createOrganization(OrganizationSaveReqVO createReqVO);

    /**
     * 更新组织。
     *
     * @param updateReqVO 组织信息
     */
    void updateOrganization(OrganizationSaveReqVO updateReqVO);

    /**
     * 删除组织。
     *
     * @param id 组织编号
     */
    void deleteOrganization(Long id);

    /**
     * 修改组织状态。
     *
     * @param id 组织编号
     * @param status 状态
     */
    void updateOrganizationStatus(Long id, Integer status);

    /**
     * 检查组织编码是否存在。
     *
     * @param orgCode 组织编码
     * @param excludeId 排除的组织编号
     * @return 是否存在
     */
    boolean checkCodeExists(String orgCode, String excludeId);

    /**
     * 获取组织的所有子组织。
     *
     * @param id 组织编号
     * @return 子组织列表
     */
    List<OrganizationDO> getChildOrganizationList(Long id);

    /**
     * 校验组织列表是否有效。
     *
     * @param ids 组织编号集合
     */
    void validateOrganizationList(Collection<Long> ids);

}
