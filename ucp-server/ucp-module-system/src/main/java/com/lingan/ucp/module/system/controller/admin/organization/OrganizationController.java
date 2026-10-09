package com.lingan.ucp.module.system.controller.admin.organization;

import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.framework.common.util.object.BeanUtils;
import com.lingan.ucp.module.system.controller.admin.organization.vo.OrgDeptTreeRespVO;
import com.lingan.ucp.module.system.controller.admin.organization.vo.OrganizationRespVO;
import com.lingan.ucp.module.system.controller.admin.organization.vo.OrganizationSaveReqVO;
import com.lingan.ucp.module.system.controller.admin.organization.vo.OrganizationTreeRespVO;
import com.lingan.ucp.module.system.service.organization.OrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.lingan.ucp.framework.common.pojo.Result.success;

@Tag(name = "管理后台 - 组织")
@RestController
@RequestMapping("/system/organization")
@RequiredArgsConstructor
@Validated
public class OrganizationController {

    private final OrganizationService organizationService;

    @GetMapping("/org-dept-tree")
    @Operation(summary = "获取当前租户下所有组织与部门构成的混合树")
    public Result<List<OrgDeptTreeRespVO>> getOrgDeptTree() {
        return success(organizationService.getOrgDeptTree());
    }

    @GetMapping("/tree")
    @Operation(summary = "获取当前租户组织树")
    public Result<List<OrganizationTreeRespVO>> getTree(@RequestParam(value = "orgName", required = false) String orgName,
                                                        @RequestParam(value = "status", required = false) Integer status) {
        return success(organizationService.getCurrentTenantTree(orgName, status));
    }

    @GetMapping("/get")
    @Operation(summary = "获取组织详情")
    public Result<OrganizationRespVO> get(@RequestParam("id") Long id) {
        return success(toRespVO(organizationService.getOrganization(id)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "获取组织详情")
    public Result<OrganizationRespVO> getByPath(@PathVariable("id") Long id) {
        return success(toRespVO(organizationService.getOrganization(id)));
    }

    @PostMapping({"", "/create"})
    @Operation(summary = "创建组织")
    public Result<Long> create(@Valid @RequestBody OrganizationSaveReqVO createReqVO) {
        return success(organizationService.createOrganization(createReqVO));
    }

    @PutMapping("/update")
    @Operation(summary = "更新组织")
    public Result<Boolean> update(@Valid @RequestBody OrganizationSaveReqVO updateReqVO) {
        organizationService.updateOrganization(updateReqVO);
        return success(true);
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新组织")
    public Result<Boolean> updateByPath(@PathVariable("id") Long id, @Valid @RequestBody OrganizationSaveReqVO updateReqVO) {
        updateReqVO.setId(id);
        organizationService.updateOrganization(updateReqVO);
        return success(true);
    }

    @DeleteMapping("/delete")
    @Operation(summary = "删除组织")
    public Result<Boolean> delete(@RequestParam("id") Long id) {
        organizationService.deleteOrganization(id);
        return success(true);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除组织")
    public Result<Boolean> deleteByPath(@PathVariable("id") Long id) {
        organizationService.deleteOrganization(id);
        return success(true);
    }

    @PutMapping("/update-status")
    @Operation(summary = "修改组织状态")
    public Result<Boolean> updateStatus(@RequestParam("id") Long id, @RequestParam("status") Integer status) {
        organizationService.updateOrganizationStatus(id, status);
        return success(true);
    }

    @PutMapping("/{id}/status")
    @Operation(summary = "修改组织状态")
    public Result<Boolean> updateStatusByPath(@PathVariable("id") Long id, @RequestParam("status") Integer status) {
        organizationService.updateOrganizationStatus(id, status);
        return success(true);
    }

    @GetMapping("/check-code")
    @Operation(summary = "检查组织编码是否存在")
    public Result<Boolean> checkCodeExists(@RequestParam("orgCode") String orgCode,
                                           @RequestParam(value = "excludeId", required = false) String excludeId) {
        return success(organizationService.checkCodeExists(orgCode, excludeId));
    }

    @PostMapping("/list-by-ids")
    @Operation(summary = "根据 ID 列表查询组织")
    public Result<List<OrganizationRespVO>> listByIds(@RequestBody List<Long> ids) {
        return success(BeanUtils.toBean(organizationService.getOrganizationList(ids), OrganizationRespVO.class));
    }

    private OrganizationRespVO toRespVO(com.lingan.ucp.module.system.dal.dataobject.organization.OrganizationDO organization) {
        OrganizationRespVO vo = BeanUtils.toBean(organization, OrganizationRespVO.class);
        vo.setOrgTypeName(getOrgTypeName(organization.getOrgType()));
        return vo;
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
