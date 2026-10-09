package com.richuang.os.module.system.legacy.service.impl;

import com.richuang.os.common.exception.BusinessException;
import com.richuang.os.common.tenant.TenantContext;
import com.richuang.os.module.system.legacy.dto.OrganizationDTO;
import com.richuang.os.module.system.legacy.entity.SysDepartment;
import com.richuang.os.module.system.legacy.entity.SysOrganization;
import com.richuang.os.module.system.legacy.mapper.SysDepartmentMapper;
import com.richuang.os.module.system.legacy.mapper.SysOrganizationMapper;
import com.richuang.os.module.system.legacy.service.OrganizationService;
import com.richuang.os.module.system.legacy.vo.OrgDeptTreeVO;
import com.richuang.os.module.system.legacy.vo.OrganizationTreeVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 组织服务实现类
 */
@Slf4j
// @Service -- 已废弃（v2.0 统一认证迁移）
@RequiredArgsConstructor
public class OrganizationServiceImpl implements OrganizationService {

    private final SysOrganizationMapper organizationMapper;
    private final SysDepartmentMapper departmentMapper;

    @Override
    public List<OrgDeptTreeVO> getOrgDeptTree() {
        String tenantId = TenantContext.getTenantId();
        if (org.apache.commons.lang3.StringUtils.isEmpty(tenantId)) {
            return new ArrayList<>();
        }

        // 1. 获取所有组织和所有部门
        List<SysOrganization> orgs = organizationMapper.selectByTenantId(tenantId);
        List<SysDepartment> depts = departmentMapper.selectByTenantId(tenantId);

        if (CollectionUtils.isEmpty(orgs)) {
            return new ArrayList<>();
        }

        // 2. 将它们转换为通用节点 OrgDeptTreeVO
        List<OrgDeptTreeVO> orgNodes = orgs.stream().map(org -> {
            OrgDeptTreeVO vo = new OrgDeptTreeVO();
            vo.setId("org_" + org.getId());
            vo.setRawId(org.getId());
            vo.setName(org.getOrgName());
            vo.setNodeType("org");
            vo.setChildren(new ArrayList<>());
            return vo;
        }).collect(Collectors.toList());

        List<OrgDeptTreeVO> deptNodes = depts.stream().map(dept -> {
            OrgDeptTreeVO vo = new OrgDeptTreeVO();
            vo.setId("dept_" + dept.getId());
            vo.setRawId(dept.getId());
            vo.setName(dept.getDeptName());
            vo.setNodeType("dept");
            vo.setOrgId(dept.getOrgId());
            vo.setChildren(new ArrayList<>());
            return vo;
        }).collect(Collectors.toList());

        // 3. 构建关联关系
        Map<String, OrgDeptTreeVO> orgMap = orgNodes.stream()
                .collect(Collectors.toMap(OrgDeptTreeVO::getRawId, vo -> vo));
        Map<String, OrgDeptTreeVO> deptMap = deptNodes.stream()
                .collect(Collectors.toMap(OrgDeptTreeVO::getRawId, vo -> vo));

        List<OrgDeptTreeVO> rootNodes = new ArrayList<>();

        // 3.1 挂载组织的子组织
        for (SysOrganization org : orgs) {
            OrgDeptTreeVO currentOrgNode = orgMap.get(org.getId());
            if (org.getParentId() == null || "0".equals(org.getParentId())) {
                rootNodes.add(currentOrgNode);
            } else {
                OrgDeptTreeVO parentOrgNode = orgMap.get(org.getParentId());
                if (parentOrgNode != null) {
                    parentOrgNode.getChildren().add(currentOrgNode);
                } else {
                    rootNodes.add(currentOrgNode);
                }
            }
        }

        // 3.2 挂载部门的子部门及顶级部门到组织
        for (SysDepartment dept : depts) {
            OrgDeptTreeVO currentDeptNode = deptMap.get(dept.getId());
            if (currentDeptNode == null) {
                continue;
            }
            // 如果是子部门，挂载到父部门
            if (dept.getParentId() != null && !"0".equals(dept.getParentId())) {
                OrgDeptTreeVO parentDeptNode = deptMap.get(dept.getParentId());
                if (parentDeptNode != null) {
                    parentDeptNode.getChildren().add(currentDeptNode);
                    continue;
                }
            }
            // 如果是顶级部门，挂载到所属的组织
            if (dept.getOrgId() != null) {
                OrgDeptTreeVO orgNode = orgMap.get(dept.getOrgId());
                if (orgNode != null) {
                    orgNode.getChildren().add(currentDeptNode);
                }
            }
        }

        return rootNodes;
    }

    @Override
    public List<OrganizationTreeVO> getTree(String tenantId) {
        List<SysOrganization> list = organizationMapper.selectByTenantId(tenantId);
        return buildTree(list);
    }

    @Override
    public List<OrganizationTreeVO> getCurrentTenantTree() {
        String tenantId = TenantContext.getTenantId();
        if (org.apache.commons.lang3.StringUtils.isEmpty(tenantId)) {
            return new ArrayList<>();
        }
        return getTree(tenantId);
    }

    @Override
    public SysOrganization getById(String id) {
        SysOrganization org = organizationMapper.selectById(id);
        if (org == null || org.getDeleted() == 1) {
            throw new BusinessException("组织不存在");
        }
        return org;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(OrganizationDTO dto) {
        String tenantId = TenantContext.getTenantId();
        if (org.apache.commons.lang3.StringUtils.isEmpty(tenantId)) {
            throw new BusinessException("租户信息缺失");
        }

        // 检查编码是否已存在
        if (checkCodeExists(dto.getOrgCode(), null)) {
            throw new BusinessException("组织编码已存在");
        }

        SysOrganization org = new SysOrganization();
        BeanUtils.copyProperties(dto, org);
        org.setTenantId(tenantId);
        org.setDeleted(0);
        org.setCreateTime(LocalDateTime.now());
        org.setUpdateTime(LocalDateTime.now());

        // 处理父节点
        if (org.getParentId() == null) {
            org.setParentId("0");
        }

        // 计算层级和路径
        if (org.getParentId() == null || "0".equals(org.getParentId())) {
            org.setLevel(1);
            org.setParentIds("0");
        } else {
            SysOrganization parent = organizationMapper.selectById(org.getParentId());
            if (parent == null || parent.getDeleted() == 1) {
                throw new BusinessException("父组织不存在");
            }
            org.setLevel(parent.getLevel() + 1);
            org.setParentIds(parent.getParentIds() + "," + parent.getId());
        }

        if (org.getStatus() == null) {
            org.setStatus(1);
        }

        organizationMapper.insert(org);
        log.info("创建组织成功: {}", org.getOrgName());

        return org.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(OrganizationDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("组织ID不能为空");
        }

        SysOrganization existing = organizationMapper.selectById(dto.getId());
        if (existing == null || existing.getDeleted() == 1) {
            throw new BusinessException("组织不存在");
        }

        // 检查编码是否冲突
        if (!existing.getOrgCode().equals(dto.getOrgCode())
                && checkCodeExists(dto.getOrgCode(), dto.getId())) {
            throw new BusinessException("组织编码已存在");
        }

        SysOrganization org = new SysOrganization();
        BeanUtils.copyProperties(dto, org);
        org.setUpdateTime(LocalDateTime.now());

        organizationMapper.updateById(org);
        log.info("更新组织成功: {}", org.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        SysOrganization org = organizationMapper.selectById(id);
        if (org == null || org.getDeleted() == 1) {
            throw new BusinessException("组织不存在");
        }

        // 检查是否有子组织
        List<SysOrganization> children = organizationMapper.selectByParentId(id);
        if (!CollectionUtils.isEmpty(children)) {
            throw new BusinessException("该组织下存在子组织，无法删除");
        }

        organizationMapper.deleteById(id);
        log.info("删除组织成功: {}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(String id, Integer status) {
        SysOrganization org = new SysOrganization();
        org.setId(id);
        org.setStatus(status);
        org.setUpdateTime(LocalDateTime.now());
        organizationMapper.updateById(org);
    }

    @Override
    public boolean checkCodeExists(String orgCode, String excludeId) {
        if (!StringUtils.hasText(orgCode)) {
            return false;
        }
        String tenantId = TenantContext.getTenantId();
        SysOrganization org = organizationMapper.selectByCode(tenantId, orgCode);
        if (org == null) {
            return false;
        }
        return excludeId == null || !excludeId.equals(org.getId());
    }

    @Override
    public List<String> getChildIds(String orgId) {
        SysOrganization org = organizationMapper.selectById(orgId);
        if (org == null) {
            return new ArrayList<>();
        }
        return organizationMapper.selectChildIds(org.getParentIds() + "," + org.getId());
    }

    /**
     * 构建组织树
     */
    private List<OrganizationTreeVO> buildTree(List<SysOrganization> list) {
        List<OrganizationTreeVO> voList = list.stream()
                .map(this::convertToTreeVO)
                .collect(Collectors.toList());

        // 构建树形结构
        List<OrganizationTreeVO> rootList = new ArrayList<>();
        for (OrganizationTreeVO vo : voList) {
            if (vo.getParentId() == null || "0".equals(vo.getParentId())) {
                rootList.add(vo);
            }
        }

        for (OrganizationTreeVO root : rootList) {
            buildChildren(root, voList);
        }

        return rootList;
    }

    /**
     * 递归构建子节点
     */
    private void buildChildren(OrganizationTreeVO parent, List<OrganizationTreeVO> allList) {
        List<OrganizationTreeVO> children = new ArrayList<>();
        for (OrganizationTreeVO vo : allList) {
            if (parent.getId().equals(vo.getParentId())) {
                children.add(vo);
                buildChildren(vo, allList);
            }
        }
        parent.setChildren(children);
    }

    /**
     * 转换为树形VO
     */
    private OrganizationTreeVO convertToTreeVO(SysOrganization org) {
        OrganizationTreeVO vo = new OrganizationTreeVO();
        BeanUtils.copyProperties(org, vo);

        // 设置类型名称
        if (org.getOrgType() != null) {
            switch (org.getOrgType()) {
                case 1:
                    vo.setOrgTypeName("公司");
                    break;
                case 2:
                    vo.setOrgTypeName("分公司");
                    break;
                case 3:
                    vo.setOrgTypeName("部门");
                    break;
                default:
                    vo.setOrgTypeName("未知");
            }
        }

        return vo;
    }

    @Override
    public List<OrganizationTreeVO> listByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<SysOrganization> orgs = organizationMapper.selectBatchIds(ids);
        return orgs.stream()
                .map(this::convertToTreeVO)
                .collect(Collectors.toList());
    }
}
