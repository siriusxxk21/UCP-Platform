package com.lingan.ucp.module.system.legacy.controller;

import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.module.system.legacy.dto.OrganizationDTO;
import com.lingan.ucp.module.system.legacy.service.OrganizationService;
import com.lingan.ucp.module.system.legacy.vo.OrgDeptTreeVO;
import com.lingan.ucp.module.system.legacy.vo.OrganizationTreeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 组织管理控制器（已废弃 - v2.0 统一认证迁移，新版见 controller/admin/organization/）
 */
// @RestController
@RequestMapping("/api/system/organization")
@RequiredArgsConstructor
public class OrganizationController {

    private final OrganizationService organizationService;

    /**
     * 获取当前租户下所有组织与部门构成的混合树
     */
    @GetMapping("/org-dept-tree")
    public Result<List<OrgDeptTreeVO>> getOrgDeptTree() {
        List<OrgDeptTreeVO> tree = organizationService.getOrgDeptTree();
        return Result.success(tree);
    }

    /**
     * 获取组织树
     */
    @GetMapping("/tree")
    public Result<List<OrganizationTreeVO>> getTree() {
        List<OrganizationTreeVO> tree = organizationService.getCurrentTenantTree();
        return Result.success(tree);
    }

    /**
     * 根据ID查询组织
     */
    @GetMapping("/{id}")
    public Result<OrganizationTreeVO> getById(@PathVariable String id) {
        // 返回简化版VO
        return Result.success(null);
    }

    /**
     * 创建组织
     */
    @PostMapping
    public Result<String> create(@RequestBody @Validated OrganizationDTO dto) {
        String id = organizationService.create(dto);
        return Result.success(id);
    }

    /**
     * 更新组织
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody @Validated OrganizationDTO dto) {
        dto.setId(id);
        organizationService.update(dto);
        return Result.success();
    }

    /**
     * 删除组织
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        organizationService.delete(id);
        return Result.success();
    }

    /**
     * 修改组织状态
     */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable String id, @RequestParam Integer status) {
        organizationService.updateStatus(id, status);
        return Result.success();
    }

    /**
     * 检查组织编码是否存在
     */
    @GetMapping("/check-code")
    public Result<Boolean> checkCodeExists(@RequestParam String orgCode,
                                           @RequestParam(required = false) String excludeId) {
        boolean exists = organizationService.checkCodeExists(orgCode, excludeId);
        return Result.success(exists);
    }

    /**
     * 根据ID列表查询组织
     */
    @PostMapping("/list-by-ids")
    public Result<List<OrganizationTreeVO>> listByIds(@RequestBody List<String> ids) {
        List<OrganizationTreeVO> orgs = organizationService.listByIds(ids);
        return Result.success(orgs);
    }
}
