package com.lingan.ucp.module.system.legacy.service;

import com.lingan.ucp.module.system.legacy.dto.DepartmentDTO;
import com.lingan.ucp.module.system.legacy.entity.SysDepartment;
import com.lingan.ucp.module.system.legacy.vo.DepartmentTreeVO;

import java.util.List;

/**
 * 部门服务接口
 */
public interface DepartmentService {

    /**
     * 获取部门树
     */
    List<DepartmentTreeVO> getTree(String tenantId);

    /**
     * 根据组织ID获取部门树
     */
    List<DepartmentTreeVO> getTreeByOrgId(String orgId);

    /**
     * 获取当前租户的部门树
     */
    List<DepartmentTreeVO> getCurrentTenantTree();

    /**
     * 根据ID查询部门
     */
    SysDepartment getById(String id);

    /**
     * 创建部门
     */
    String create(DepartmentDTO dto);

    /**
     * 更新部门
     */
    void update(DepartmentDTO dto);

    /**
     * 删除部门
     */
    void delete(String id);

    /**
     * 修改部门状态
     */
    void updateStatus(String id, Integer status);

    /**
     * 检查部门编码是否存在
     */
    boolean checkCodeExists(String deptCode, String excludeId);

    /**
     * 获取部门的所有子部门ID
     */
    List<String> getChildIds(String deptId);

    /**
     * 获取用户的部门列表
     */
    List<SysDepartment> getByUserId(String userId);

    /**
     * 根据ID列表查询部门
     */
    List<DepartmentTreeVO> listByIds(List<String> ids);
}
