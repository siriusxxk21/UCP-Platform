package com.lingan.ucp.module.system.service.organization;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjectUtil;
import com.lingan.ucp.module.system.enums.organization.OrganizationStatusEnum;
import com.lingan.ucp.framework.common.util.object.BeanUtils;
import com.lingan.ucp.framework.common.util.object.ObjectUtils;
import com.lingan.ucp.framework.security.core.util.SecurityFrameworkUtils;
import com.lingan.ucp.framework.tenant.config.TenantProperties;
import com.lingan.ucp.framework.tenant.core.context.TenantContextHolder;
import com.lingan.ucp.module.system.controller.admin.organization.vo.OrgDeptTreeRespVO;
import com.lingan.ucp.module.system.controller.admin.organization.vo.OrganizationSaveReqVO;
import com.lingan.ucp.module.system.controller.admin.organization.vo.OrganizationTreeRespVO;
import com.lingan.ucp.module.system.dal.dataobject.dept.DeptDO;
import com.lingan.ucp.module.system.dal.dataobject.organization.OrganizationDO;
import com.lingan.ucp.module.system.dal.mysql.dept.DeptMapper;
import com.lingan.ucp.module.system.dal.mysql.organization.OrganizationMapper;
import com.google.common.annotations.VisibleForTesting;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.lingan.ucp.framework.common.exception.util.ServiceExceptionUtil.exception;
import static com.lingan.ucp.framework.common.util.collection.CollectionUtils.convertMap;
import static com.lingan.ucp.framework.common.util.collection.CollectionUtils.convertSet;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.ORGANIZATION_CODE_DUPLICATE;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.ORGANIZATION_EXISTS_CHILDREN;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.ORGANIZATION_NOT_ENABLE;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.ORGANIZATION_NOT_EXISTS;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.ORGANIZATION_PARENT_ERROR;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.ORGANIZATION_PARENT_IS_CHILD;
import static com.lingan.ucp.module.system.enums.ErrorCodeConstants.ORGANIZATION_PARENT_NOT_EXISTS;

/**
 * 组织 Service 实现类
 */
@Service
@Validated
@Slf4j
public class OrganizationServiceImpl implements OrganizationService {

    @Resource
    private OrganizationMapper organizationMapper;

    @Resource
    private DeptMapper deptMapper;

    @SuppressWarnings("SpringJavaAutowiredFieldsWarningInspection")
    @Autowired(required = false)
    private TenantProperties tenantProperties;

    @Override
    public List<OrgDeptTreeRespVO> getOrgDeptTree() {
        List<OrganizationDO> orgs;
        List<DeptDO> depts;

        if (isTenantDisable()) {
            orgs = organizationMapper.selectListAll();
            depts = deptMapper.selectListAll();
        } else {
            Long tenantId = TenantContextHolder.getTenantId();
            if (tenantId == null) {
                return List.of();
            }
            orgs = organizationMapper.selectListByTenantId(tenantId);
            depts = deptMapper.selectListByTenantId(tenantId);
        }

        if (CollUtil.isEmpty(orgs)) {
            return List.of();
        }

        // 将组织转换为 OrgDeptTreeRespVO 节点
        Map<String, OrgDeptTreeRespVO> orgNodeMap = new HashMap<>();
        for (OrganizationDO org : orgs) {
            OrgDeptTreeRespVO vo = new OrgDeptTreeRespVO();
            vo.setId("org_" + org.getId());
            vo.setRawId(String.valueOf(org.getId()));
            vo.setName(org.getOrgName());
            vo.setNodeType("org");
            vo.setChildren(new ArrayList<>());
            orgNodeMap.put(String.valueOf(org.getId()), vo);
        }

        // 将部门转换为 OrgDeptTreeRespVO 节点
        Map<String, OrgDeptTreeRespVO> deptNodeMap = new HashMap<>();
        for (DeptDO dept : depts) {
            OrgDeptTreeRespVO vo = new OrgDeptTreeRespVO();
            vo.setId("dept_" + dept.getId());
            vo.setRawId(String.valueOf(dept.getId()));
            vo.setName(dept.getName());
            vo.setNodeType("dept");
            vo.setOrgId(dept.getOrgId() != null ? String.valueOf(dept.getOrgId()) : null);
            vo.setChildren(new ArrayList<>());
            deptNodeMap.put(String.valueOf(dept.getId()), vo);
        }

        // 构建树结构：先挂载组织父子关系
        List<OrgDeptTreeRespVO> rootNodes = new ArrayList<>();
        for (OrganizationDO org : orgs) {
            OrgDeptTreeRespVO currentNode = orgNodeMap.get(String.valueOf(org.getId()));
            if (currentNode == null) {
                continue;
            }
            if (org.getParentId() == null || OrganizationDO.PARENT_ID_ROOT.equals(org.getParentId())) {
                rootNodes.add(currentNode);
            } else {
                OrgDeptTreeRespVO parentNode = orgNodeMap.get(String.valueOf(org.getParentId()));
                if (parentNode != null) {
                    parentNode.getChildren().add(currentNode);
                } else {
                    rootNodes.add(currentNode);
                }
            }
        }

        // 挂载部门：子部门挂父部门，顶级部门挂所属组织
        for (DeptDO dept : depts) {
            OrgDeptTreeRespVO currentNode = deptNodeMap.get(String.valueOf(dept.getId()));
            if (currentNode == null) {
                continue;
            }
            // 如果有父部门，挂载到父部门下
            if (dept.getParentId() != null && !DeptDO.PARENT_ID_ROOT.equals(dept.getParentId())) {
                OrgDeptTreeRespVO parentDeptNode = deptNodeMap.get(String.valueOf(dept.getParentId()));
                if (parentDeptNode != null) {
                    parentDeptNode.getChildren().add(currentNode);
                    continue;
                }
            }
            // 顶级部门，挂载到所属组织下
            if (dept.getOrgId() != null) {
                OrgDeptTreeRespVO orgNode = orgNodeMap.get(String.valueOf(dept.getOrgId()));
                if (orgNode != null) {
                    orgNode.getChildren().add(currentNode);
                }
            }
        }

        return rootNodes;
    }

    @Override
    public List<OrganizationTreeRespVO> getTree(Long tenantId) {
        if (ObjectUtil.isNull(tenantId)) {
            return new ArrayList<>();
        }
        return buildTree(organizationMapper.selectListByTenantId(tenantId));
    }

    @Override
    public List<OrganizationTreeRespVO> getCurrentTenantTree() {
        return getCurrentTenantTree(null, null);
    }

    @Override
    public List<OrganizationTreeRespVO> getCurrentTenantTree(String orgName, Integer status) {
        List<OrganizationTreeRespVO> tree;
        if (isTenantDisable()) {
            tree = buildTree(organizationMapper.selectListAll());
            return filterTree(tree, orgName, status);
        }
        Long tenantId = TenantContextHolder.getTenantId();
        if (tenantId == null) {
            return new ArrayList<>();
        }
        tree = getTree(tenantId);
        return filterTree(tree, orgName, status);
    }

    @Override
    public OrganizationDO getOrganization(Long id) {
        OrganizationDO organization = organizationMapper.selectById(id);
        if (organization == null) {
            throw exception(ORGANIZATION_NOT_EXISTS);
        }
        return organization;
    }

    @Override
    public List<OrganizationDO> getOrganizationList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return List.of();
        }
        return organizationMapper.selectListByIds(ids);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createOrganization(OrganizationSaveReqVO createReqVO) {
        Long tenantId = TenantContextHolder.getTenantId();;
        validateOrganizationCodeUnique(null, tenantId, createReqVO.getOrgCode());
        OrganizationDO parent = validateParentOrganization(null, createReqVO.getParentId());

        OrganizationDO organization = BeanUtils.toBean(createReqVO, OrganizationDO.class);
        organization.setTenantId(tenantId);
        organization.setParentId(normalizeParentId(createReqVO.getParentId()));
        fillTreePath(organization, parent);
        fillCreateAuditFields(organization);
        organizationMapper.insert(organization);
        return organization.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateOrganization(OrganizationSaveReqVO updateReqVO) {
        if (ObjectUtil.isNull(updateReqVO.getId())) {
            throw exception(ORGANIZATION_NOT_EXISTS);
        }
        OrganizationDO oldOrganization = getOrganization(updateReqVO.getId());
        Long tenantId = oldOrganization.getTenantId();
        validateOrganizationCodeUnique(updateReqVO.getId(), tenantId, updateReqVO.getOrgCode());
        OrganizationDO parent = validateParentOrganization(updateReqVO.getId(), updateReqVO.getParentId());

        OrganizationDO updateObj = BeanUtils.toBean(updateReqVO, OrganizationDO.class);
        updateObj.setTenantId(tenantId);
        updateObj.setParentId(normalizeParentId(updateReqVO.getParentId()));
        fillTreePath(updateObj, parent);
        fillUpdateAuditFields(updateObj);
        organizationMapper.updateById(updateObj);

        if (!Objects.equals(oldOrganization.getParentIds(), updateObj.getParentIds())
                || !Objects.equals(oldOrganization.getLevel(), updateObj.getLevel())) {
            refreshChildrenPath(updateObj);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteOrganization(Long id) {
        getOrganization(id);
        if (CollUtil.isNotEmpty(organizationMapper.selectListByParentId(id))) {
            throw exception(ORGANIZATION_EXISTS_CHILDREN);
        }
        organizationMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateOrganizationStatus(Long id, Integer status) {
        getOrganization(id);
        OrganizationDO updateObj = new OrganizationDO();
        updateObj.setId(id);
        updateObj.setStatus(status);
        fillUpdateAuditFields(updateObj);
        organizationMapper.updateById(updateObj);
    }

    @Override
    public boolean checkCodeExists(String orgCode, String excludeId) {
        if (!StringUtils.hasText(orgCode)) {
            return false;
        }
        OrganizationDO organization = organizationMapper.selectByTenantIdAndOrgCode(TenantContextHolder.getTenantId(), orgCode);
        if (organization == null) {
            return false;
        }
        return !Objects.equals(String.valueOf(organization.getId()), excludeId);
    }

    @Override
    public List<OrganizationDO> getChildOrganizationList(Long id) {
        OrganizationDO organization = organizationMapper.selectById(id);
        if (organization == null) {
            return List.of();
        }
        return organizationMapper.selectListByParentIdsPrefix(buildSelfPath(organization));
    }

    @Override
    public void validateOrganizationList(Collection<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        Map<Long, OrganizationDO> organizationMap = convertMap(getOrganizationList(ids), OrganizationDO::getId);
        ids.forEach(id -> {
            OrganizationDO organization = organizationMap.get(id);
            if (organization == null) {
                throw exception(ORGANIZATION_NOT_EXISTS);
            }
            if (!OrganizationStatusEnum.isEnable(organization.getStatus())) {
                throw exception(ORGANIZATION_NOT_ENABLE, organization.getOrgName());
            }
        });
    }

    @VisibleForTesting
    void validateOrganizationCodeUnique(Long id, Long tenantId, String orgCode) {
        OrganizationDO organization = organizationMapper.selectByTenantIdAndOrgCode(tenantId, orgCode);
        if (organization == null) {
            return;
        }
        if (id == null || ObjectUtil.notEqual(organization.getId(), id)) {
            throw exception(ORGANIZATION_CODE_DUPLICATE, orgCode);
        }
    }

    @VisibleForTesting
    OrganizationDO validateParentOrganization(Long id, Long parentId) {
        parentId = normalizeParentId(parentId);
        if (OrganizationDO.PARENT_ID_ROOT.equals(parentId)) {
            return null;
        }
        if (ObjectUtil.isNull(id)&&ObjectUtil.isNull(parentId)){
            return null;
        }
        if (Objects.equals(id, parentId)) {
            throw exception(ORGANIZATION_PARENT_ERROR);
        }
        OrganizationDO parent = organizationMapper.selectById(parentId);
        if (parent == null) {
            throw exception(ORGANIZATION_PARENT_NOT_EXISTS);
        }
        if (id == null) {
            return parent;
        }
        String parentSelfPath = buildSelfPath(parent);
        if (Objects.equals(parent.getId(), id)
                || parentSelfPath.startsWith(buildSelfPath(getOrganization(id)))) {
            throw exception(ORGANIZATION_PARENT_IS_CHILD);
        }
        return parent;
    }

    private void refreshChildrenPath(OrganizationDO parent) {
        List<OrganizationDO> children = organizationMapper.selectListByParentId(parent.getId());
        for (OrganizationDO child : children) {
            child.setParentIds(buildSelfPath(parent));
            child.setLevel(parent.getLevel() + 1);
            fillUpdateAuditFields(child);
            organizationMapper.updateById(child);
            refreshChildrenPath(child);
        }
    }

    private List<OrganizationTreeRespVO> buildTree(List<OrganizationDO> organizations) {
        if (CollUtil.isEmpty(organizations)) {
            return List.of();
        }
        List<OrganizationTreeRespVO> nodes = organizations.stream()
                .sorted(Comparator.comparing(OrganizationDO::getSortOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(OrganizationDO::getId))
                .map(this::toTreeRespVO)
                .toList();
        Map<String, OrganizationTreeRespVO> nodeMap = convertMap(nodes, OrganizationTreeRespVO::getId);
        List<OrganizationTreeRespVO> roots = new LinkedList<>();
        for (OrganizationTreeRespVO node : nodes) {
            if (!StringUtils.hasText(node.getParentId()) || OrganizationDO.PARENT_ID_ROOT.equals(node.getParentId())) {
                roots.add(node);
                continue;
            }
            OrganizationTreeRespVO parent = nodeMap.get(node.getParentId());
            if (parent == null) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    private List<OrganizationTreeRespVO> filterTree(List<OrganizationTreeRespVO> tree, String orgName, Integer status) {
        if (!StringUtils.hasText(orgName) && status == null) {
            return tree;
        }
        List<OrganizationTreeRespVO> filtered = new ArrayList<>();
        for (OrganizationTreeRespVO node : tree) {
            OrganizationTreeRespVO matched = filterNode(node, orgName, status);
            if (matched != null) {
                filtered.add(matched);
            }
        }
        return filtered;
    }

    private OrganizationTreeRespVO filterNode(OrganizationTreeRespVO node, String orgName, Integer status) {
        boolean nameMatch = !StringUtils.hasText(orgName)
                || (node.getOrgName() != null && node.getOrgName().toLowerCase().contains(orgName.toLowerCase()));
        boolean statusMatch = status == null || Objects.equals(node.getStatus(), status);
        List<OrganizationTreeRespVO> children = new ArrayList<>();
        if (CollUtil.isNotEmpty(node.getChildren())) {
            for (OrganizationTreeRespVO child : node.getChildren()) {
                OrganizationTreeRespVO matchedChild = filterNode(child, orgName, status);
                if (matchedChild != null) {
                    children.add(matchedChild);
                }
            }
        }
        if (!nameMatch || !statusMatch) {
            return children.isEmpty() ? null : copyTreeNode(node, children);
        }
        return copyTreeNode(node, children);
    }

    private OrganizationTreeRespVO copyTreeNode(OrganizationTreeRespVO source, List<OrganizationTreeRespVO> children) {
        OrganizationTreeRespVO target = BeanUtils.toBean(source, OrganizationTreeRespVO.class);
        target.setChildren(children);
        return target;
    }

    private OrganizationTreeRespVO toTreeRespVO(OrganizationDO organization) {
        OrganizationTreeRespVO vo = BeanUtils.toBean(organization, OrganizationTreeRespVO.class);
        vo.setOrgTypeName(getOrgTypeName(organization.getOrgType()));
        if (vo.getChildren() == null) {
            vo.setChildren(new ArrayList<>());
        }
        return vo;
    }

    /**
     * 将组织树节点转换为组织/部门混合树节点。
     * 根据 orgType 区分节点类型：orgType=3 为部门(dept)，其余为组织(org)。
     */
    private OrgDeptTreeRespVO toOrgDeptTree(OrganizationTreeRespVO organization) {
        OrgDeptTreeRespVO vo = new OrgDeptTreeRespVO();
        // orgType: 1-公司 2-分公司 3-部门
        boolean isDept = organization.getOrgType() != null && organization.getOrgType() == 3;
        String prefix = isDept ? "dept_" : "org_";
        vo.setId(prefix + organization.getId());
        vo.setRawId(organization.getId());
        vo.setName(organization.getOrgName());
        vo.setNodeType(isDept ? "dept" : "org");
        vo.setChildren(organization.getChildren().stream().map(this::toOrgDeptTree).toList());
        return vo;
    }

    private void fillTreePath(OrganizationDO organization, OrganizationDO parent) {
        if (parent == null) {
            organization.setLevel(1);
            organization.setParentIds(String.valueOf(OrganizationDO.PARENT_ID_ROOT));
            return;
        }
        organization.setLevel(parent.getLevel() + 1);
        organization.setParentIds(buildSelfPath(parent));
    }

    private String buildSelfPath(OrganizationDO organization) {
        return organization.getParentIds() + "," + organization.getId();
    }

    private Long normalizeParentId(Long parentId) {
        return ObjectUtil.isNull(parentId) ? OrganizationDO.PARENT_ID_ROOT : parentId;
    }

    private String getRequiredTenantId() {
        return TenantContextHolder.getRequiredTenantId().toString();
    }

    private boolean isTenantDisable() {
        return tenantProperties == null || Boolean.FALSE.equals(tenantProperties.getEnable());
    }

    private void fillCreateAuditFields(OrganizationDO organization) {
        LocalDateTime now = LocalDateTime.now();
        organization.setCreateTime(now);
        organization.setUpdateTime(now);
        String userId = getLoginUserId();
        organization.setCreator(userId);
        organization.setUpdater(userId);
    }

    private void fillUpdateAuditFields(OrganizationDO organization) {
        organization.setUpdateTime(LocalDateTime.now());
        organization.setUpdater(getLoginUserId());
    }

    private String getLoginUserId() {
        Long userId = SecurityFrameworkUtils.getLoginUserId();
        return userId == null ? null : userId.toString();
    }

    private String getOrgTypeName(Integer orgType) {
        if (orgType == null) {
            return "未知";
        }
        return switch (orgType) {
            case 1 -> "公司";
            case 2 -> "分公司";
            case 3 -> "部门";
            default -> "未知";
        };
    }

}
