//package com.lingan.ucp.module.system.legacy.controller;
//
//import com.lingan.ucp.framework.common.pojo.Result;
//import com.lingan.ucp.module.system.legacy.dto.RoleMenuDTO;
//import com.lingan.ucp.module.system.legacy.dto.RoleQueryDTO;
//import com.lingan.ucp.module.system.legacy.dto.RoleUserDTO;
//import com.lingan.ucp.module.system.legacy.entity.SysRole;
//import com.lingan.ucp.module.system.legacy.entity.SysUser;
//import com.lingan.ucp.module.system.legacy.service.RoleService;
//import com.lingan.ucp.module.system.legacy.vo.RolePageVO;
//import lombok.RequiredArgsConstructor;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//
//@RestController
//@RequestMapping("/api/system/role")
//@RequiredArgsConstructor
//public class RoleController {
//
//    private final RoleService roleService;
//
//    @GetMapping("/list")
//    public Result<RolePageVO> list(RoleQueryDTO queryDTO) {
//        RolePageVO pageVO = roleService.list(queryDTO);
//        return Result.success(pageVO);
//    }
//
//    @GetMapping("/{id}")
//    public Result<SysRole> getById(@PathVariable String id) {
//        SysRole role = roleService.getById(id);
//        return Result.success(role);
//    }
//
//    @PostMapping
//    public Result<Void> add(@RequestBody SysRole role) {
//        roleService.add(role);
//        return Result.success();
//    }
//
//    @PutMapping
//    public Result<Void> update(@RequestBody SysRole role) {
//        roleService.update(role);
//        return Result.success();
//    }
//
//    @DeleteMapping("/{id}")
//    public Result<Void> delete(@PathVariable String id) {
//        roleService.delete(id);
//        return Result.success();
//    }
//
//    @DeleteMapping("/batch")
//    public Result<Void> batchDelete(@RequestBody List<String> ids) {
//        roleService.batchDelete(ids);
//        return Result.success();
//    }
//
//    @GetMapping("/{id}/menus")
//    public Result<List<String>> getRoleMenus(@PathVariable String id) {
//        List<String> menuIds = roleService.getRoleMenus(id);
//        return Result.success(menuIds);
//    }
//
//    @PutMapping("/{id}/menus")
//    public Result<Void> updateRoleMenus(@PathVariable String id, @RequestBody RoleMenuDTO dto) {
//        roleService.updateRoleMenus(id, dto.getMenuIds());
//        return Result.success();
//    }
//
//    // ==================== 角色用户管理 ====================
//
//    @GetMapping("/{id}/users")
//    public Result<List<SysUser>> getRoleUsers(@PathVariable String id) {
//        List<SysUser> users = roleService.getRoleUsers(id);
//        return Result.success(users);
//    }
//
//    @PostMapping("/{id}/users")
//    public Result<Void> addRoleUsers(@PathVariable String id, @RequestBody RoleUserDTO dto) {
//        roleService.addRoleUsers(id, dto.getUserIds());
//        return Result.success();
//    }
//
//    @DeleteMapping("/{id}/users")
//    public Result<Void> removeRoleUsers(@PathVariable String id, @RequestBody RoleUserDTO dto) {
//        roleService.removeRoleUsers(id, dto.getUserIds());
//        return Result.success();
//    }
//
//    /**
//     * 根据ID列表查询角色
//     */
//    @PostMapping("/list-by-ids")
//    public Result<List<SysRole>> listByIds(@RequestBody List<String> ids) {
//        List<SysRole> roles = roleService.listByIds(ids);
//        return Result.success(roles);
//    }
//}
