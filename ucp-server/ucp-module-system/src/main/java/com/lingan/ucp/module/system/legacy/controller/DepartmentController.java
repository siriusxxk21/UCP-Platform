package com.lingan.ucp.module.system.legacy.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingan.ucp.framework.common.pojo.Result;
import com.lingan.ucp.module.system.legacy.dto.BatchAddUsersDTO;
import com.lingan.ucp.module.system.legacy.dto.DepartmentDTO;
import com.lingan.ucp.module.system.legacy.dto.UserDeptDTO;
import com.lingan.ucp.module.system.legacy.service.DepartmentService;
import com.lingan.ucp.module.system.legacy.service.SysUserDeptService;
import com.lingan.ucp.module.system.legacy.vo.DepartmentTreeVO;
import com.lingan.ucp.module.system.legacy.vo.UserDeptVO;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 部门管理控制器（已废弃 - v2.0 统一认证迁移）
 */
// @RestController
@RequestMapping("/api/system/department")
@RequiredArgsConstructor
public class DepartmentController {

    private final DepartmentService departmentService;
    private final SysUserDeptService userDeptService;

    /**
     * 获取部门树
     */
    @GetMapping("/tree")
    public Result<List<DepartmentTreeVO>> getTree() {
        List<DepartmentTreeVO> tree = departmentService.getCurrentTenantTree();
        return Result.success(tree);
    }

    /**
     * 根据组织ID获取部门树
     */
    @GetMapping("/tree/{orgId}")
    public Result<List<DepartmentTreeVO>> getTreeByOrgId(@PathVariable String orgId) {
        List<DepartmentTreeVO> tree = departmentService.getTreeByOrgId(orgId);
        return Result.success(tree);
    }

    /**
     * 根据ID查询部门
     */
    @GetMapping("/{id}")
    public Result<DepartmentTreeVO> getById(@PathVariable String id) {
        return Result.success(null);
    }

    /**
     * 创建部门
     */
    @PostMapping
    public Result<String> create(@RequestBody @Validated DepartmentDTO dto) {
        String id = departmentService.create(dto);
        return Result.success(id);
    }

    /**
     * 更新部门
     */
    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable String id, @RequestBody @Validated DepartmentDTO dto) {
        dto.setId(id);
        departmentService.update(dto);
        return Result.success();
    }

    /**
     * 删除部门
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable String id) {
        departmentService.delete(id);
        return Result.success();
    }

    /**
     * 修改部门状态
     */
    @PutMapping("/{id}/status")
    public Result<Void> updateStatus(@PathVariable String id, @RequestParam Integer status) {
        departmentService.updateStatus(id, status);
        return Result.success();
    }

    /**
     * 检查部门编码是否存在
     */
    @GetMapping("/check-code")
    public Result<Boolean> checkCodeExists(@RequestParam String deptCode,
                                           @RequestParam(required = false) String excludeId) {
        boolean exists = departmentService.checkCodeExists(deptCode, excludeId);
        return Result.success(exists);
    }

    // ==================== 部门用户管理 ====================

    /**
     * 分页获取部门用户列表
     */
    @GetMapping("/{deptId}/users/page")
    public Result<Page<UserDeptVO>> getDeptUsersPage(
            @PathVariable String deptId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String username) {
        Page<UserDeptVO> page = new Page<>(pageNum, pageSize);
        Page<UserDeptVO> result = userDeptService.getDeptUsersPage(deptId, username, page);
        return Result.success(result);
    }

    /**
     * 获取部门用户列表
     */
    @GetMapping("/{deptId}/users")
    public Result<List<UserDeptVO>> getDeptUsers(@PathVariable String deptId) {
        List<UserDeptVO> users = userDeptService.getDeptUsers(deptId);
        return Result.success(users);
    }

    /**
     * 添加用户到部门
     */
    @PostMapping("/{deptId}/users")
    public Result<Void> addUserToDept(@PathVariable String deptId, @RequestBody @Validated UserDeptDTO dto) {
        dto.setDeptId(deptId);
        userDeptService.addUserToDept(dto);
        return Result.success();
    }

    /**
     * 批量添加用户到部门
     */
    @PostMapping("/{deptId}/users/batch")
    public Result<Void> batchAddUsersToDept(@PathVariable String deptId, @RequestBody @Validated BatchAddUsersDTO dto) {
        userDeptService.batchAddUsersToDept(deptId, dto.getUserIds(), dto.getPost(), dto.getIsMain());
        return Result.success();
    }

    /**
     * 移除部门用户
     */
    @DeleteMapping("/users/{id}")
    public Result<Void> removeUserFromDept(@PathVariable String id) {
        userDeptService.removeUserFromDept(id);
        return Result.success();
    }

    /**
     * 设置为主部门
     */
    @PutMapping("/users/{id}/main")
    public Result<Void> setAsMainDept(@PathVariable String id) {
        userDeptService.setAsMainDept(id);
        return Result.success();
    }

    /**
     * 更新用户岗位
     */
    @PutMapping("/users/{id}/post")
    public Result<Void> updateUserPost(@PathVariable String id, @RequestParam String post) {
        userDeptService.updateUserPost(id, post);
        return Result.success();
    }

    /**
     * 分页获取可添加到部门的用户
     */
    @GetMapping("/{deptId}/available-users/page")
    public Result<Page<UserDeptVO>> getAvailableUsersPage(
            @PathVariable String deptId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String username) {
        Page<UserDeptVO> page = new Page<>(pageNum, pageSize);
        Page<UserDeptVO> result = userDeptService.getAvailableUsersPage(deptId, username, page);
        return Result.success(result);
    }

    /**
     * 获取可添加到部门的用户
     */
    @GetMapping("/{deptId}/available-users")
    public Result<List<UserDeptVO>> getAvailableUsers(
            @PathVariable String deptId,
            @RequestParam(required = false) String username) {
        List<UserDeptVO> users = userDeptService.getAvailableUsers(deptId, username);
        return Result.success(users);
    }

    /**
     * 根据ID列表查询部门
     */
    @PostMapping("/list-by-ids")
    public Result<List<DepartmentTreeVO>> listByIds(@RequestBody List<String> ids) {
        List<DepartmentTreeVO> depts = departmentService.listByIds(ids);
        return Result.success(depts);
    }
}
