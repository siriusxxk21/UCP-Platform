package com.richuang.os.module.system.legacy.service.impl;

import com.richuang.os.common.exception.BusinessException;
import com.richuang.os.common.tenant.TenantContext;
import com.richuang.os.module.system.legacy.dto.DepartmentDTO;
import com.richuang.os.module.system.legacy.entity.SysDepartment;
import com.richuang.os.module.system.legacy.mapper.SysDepartmentMapper;
import com.richuang.os.module.system.legacy.service.DepartmentService;
import com.richuang.os.module.system.legacy.vo.DepartmentTreeVO;
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
import java.util.stream.Collectors;

/**
 * 部门服务实现类
 */
@Slf4j
// @Service -- 已废弃（v2.0 统一认证迁移）
@RequiredArgsConstructor
public class DepartmentServiceImpl implements DepartmentService {

    private final SysDepartmentMapper departmentMapper;

    @Override
    public List<DepartmentTreeVO> getTree(String tenantId) {
        List<SysDepartment> list = departmentMapper.selectByTenantId(tenantId);
        return buildTree(list);
    }

    @Override
    public List<DepartmentTreeVO> getTreeByOrgId(String orgId) {
        List<SysDepartment> list = departmentMapper.selectByOrgId(orgId);
        return buildTree(list);
    }

    @Override
    public List<DepartmentTreeVO> getCurrentTenantTree() {
        String tenantId = TenantContext.getTenantId();
        if (org.apache.commons.lang3.StringUtils.isEmpty(tenantId)) {
            return new ArrayList<>();
        }
        return getTree(tenantId);
    }

    @Override
    public SysDepartment getById(String id) {
        SysDepartment dept = departmentMapper.selectById(id);
        if (dept == null || dept.getDeleted() == 1) {
            throw new BusinessException("部门不存在");
        }
        return dept;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(DepartmentDTO dto) {
        String tenantId = TenantContext.getTenantId();
        if (org.apache.commons.lang3.StringUtils.isEmpty(tenantId)) {
            throw new BusinessException("租户信息缺失");
        }

        // 检查编码是否已存在
        if (checkCodeExists(dto.getDeptCode(), null)) {
            throw new BusinessException("部门编码已存在");
        }

        SysDepartment dept = new SysDepartment();
        BeanUtils.copyProperties(dto, dept);
        dept.setTenantId(tenantId);
        dept.setDeleted(0);
        dept.setCreateTime(LocalDateTime.now());
        dept.setUpdateTime(LocalDateTime.now());

        // 处理父节点
        if (dept.getParentId() == null) {
            dept.setParentId("0");
        }

        // 计算层级和路径
        if (dept.getParentId() == null || "0".equals(dept.getParentId())) {
            dept.setLevel(1);
            dept.setParentIds("0");
        } else {
            SysDepartment parent = departmentMapper.selectById(dept.getParentId());
            if (parent == null || parent.getDeleted() == 1) {
                throw new BusinessException("父部门不存在");
            }
            dept.setLevel(parent.getLevel() + 1);
            dept.setParentIds(parent.getParentIds() + "," + parent.getId());
        }

        if (dept.getStatus() == null) {
            dept.setStatus(1);
        }

        departmentMapper.insert(dept);
        log.info("创建部门成功: {}", dept.getDeptName());

        return dept.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(DepartmentDTO dto) {
        if (dto.getId() == null) {
            throw new BusinessException("部门ID不能为空");
        }

        SysDepartment existing = departmentMapper.selectById(dto.getId());
        if (existing == null || existing.getDeleted() == 1) {
            throw new BusinessException("部门不存在");
        }

        // 检查编码是否冲突
        if (!existing.getDeptCode().equals(dto.getDeptCode())
                && checkCodeExists(dto.getDeptCode(), dto.getId())) {
            throw new BusinessException("部门编码已存在");
        }

        SysDepartment dept = new SysDepartment();
        BeanUtils.copyProperties(dto, dept);
        dept.setUpdateTime(LocalDateTime.now());

        departmentMapper.updateById(dept);
        log.info("更新部门成功: {}", dept.getId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String id) {
        SysDepartment dept = departmentMapper.selectById(id);
        if (dept == null || dept.getDeleted() == 1) {
            throw new BusinessException("部门不存在");
        }

        // 检查是否有子部门
        List<SysDepartment> children = departmentMapper.selectByParentId(id);
        if (!CollectionUtils.isEmpty(children)) {
            throw new BusinessException("该部门下存在子部门，无法删除");
        }

        departmentMapper.deleteById(id);
        log.info("删除部门成功: {}", id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStatus(String id, Integer status) {
        SysDepartment dept = new SysDepartment();
        dept.setId(id);
        dept.setStatus(status);
        dept.setUpdateTime(LocalDateTime.now());
        departmentMapper.updateById(dept);
    }

    @Override
    public boolean checkCodeExists(String deptCode, String excludeId) {
        if (!StringUtils.hasText(deptCode)) {
            return false;
        }
        String tenantId = TenantContext.getTenantId();
        SysDepartment dept = departmentMapper.selectByCode(tenantId, deptCode);
        if (dept == null) {
            return false;
        }
        return excludeId == null || !excludeId.equals(dept.getId());
    }

    @Override
    public List<String> getChildIds(String deptId) {
        SysDepartment dept = departmentMapper.selectById(deptId);
        if (dept == null) {
            return new ArrayList<>();
        }
        return departmentMapper.selectChildIds(dept.getParentIds() + "," + dept.getId());
    }

    @Override
    public List<SysDepartment> getByUserId(String userId) {
        return departmentMapper.selectByUserId(userId);
    }

    /**
     * 构建部门树
     */
    private List<DepartmentTreeVO> buildTree(List<SysDepartment> list) {
        List<DepartmentTreeVO> voList = list.stream()
                .map(this::convertToTreeVO)
                .collect(Collectors.toList());

        // 构建树形结构
        List<DepartmentTreeVO> rootList = new ArrayList<>();
        for (DepartmentTreeVO vo : voList) {
            if (vo.getParentId() == null || "0".equals(vo.getParentId())) {
                rootList.add(vo);
            }
        }

        for (DepartmentTreeVO root : rootList) {
            buildChildren(root, voList);
        }

        return rootList;
    }

    /**
     * 递归构建子节点
     */
    private void buildChildren(DepartmentTreeVO parent, List<DepartmentTreeVO> allList) {
        List<DepartmentTreeVO> children = new ArrayList<>();
        for (DepartmentTreeVO vo : allList) {
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
    private DepartmentTreeVO convertToTreeVO(SysDepartment dept) {
        DepartmentTreeVO vo = new DepartmentTreeVO();
        BeanUtils.copyProperties(dept, vo);
        return vo;
    }

    @Override
    public List<DepartmentTreeVO> listByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<SysDepartment> depts = departmentMapper.selectBatchIds(ids);
        return depts.stream()
                .map(this::convertToTreeVO)
                .collect(Collectors.toList());
    }
}
