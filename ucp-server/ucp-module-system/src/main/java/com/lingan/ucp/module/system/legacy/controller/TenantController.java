//package com.lingan.ucp.module.system.legacy.controller;
//
//import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
//import com.lingan.ucp.framework.common.pojo.Result;
//import com.lingan.ucp.common.annotation.RequiresSystemAdmin;
//import com.lingan.ucp.module.system.legacy.dto.TenantDTO;
//import com.lingan.ucp.module.system.legacy.dto.TenantQueryDTO;
//import com.lingan.ucp.module.system.legacy.service.TenantService;
//import com.lingan.ucp.module.system.legacy.vo.TenantVO;
//import lombok.RequiredArgsConstructor;
//import org.springframework.validation.annotation.Validated;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//
///**
// * 租户管理控制器
// * 仅系统管理员(userType=3)可操作
// */
//@RestController
//@RequestMapping("/api/system/tenant")
//@RequiredArgsConstructor
//public class TenantController {
//
//    private final TenantService tenantService;
//
//    /**
//     * 分页查询租户列表
//     */
//    @GetMapping("/list")
//    @RequiresSystemAdmin
//    public Result<Page<TenantVO>> list(TenantQueryDTO query) {
//        Page<TenantVO> page = tenantService.list(query);
//        return Result.success(page);
//    }
//
//    /**
//     * 查询所有有效租户
//     */
//    @GetMapping("/all-valid")
//    public Result<List<TenantVO>> listAllValid() {
//        List<TenantVO> list = tenantService.listAllValid();
//        return Result.success(list);
//    }
//
//    /**
//     * 根据ID查询租户
//     */
//    @GetMapping("/{id}")
//    @RequiresSystemAdmin
//    public Result<TenantVO> getById(@PathVariable String id) {
//        TenantVO vo = tenantService.getById(id);
//        return Result.success(vo);
//    }
//
//    /**
//     * 创建租户
//     */
//    @PostMapping
//    @RequiresSystemAdmin
//    public Result<String> create(@RequestBody @Validated(TenantDTO.Create.class) TenantDTO dto) {
//        String id = tenantService.create(dto);
//        return Result.success(id);
//    }
//
//    /**
//     * 更新租户
//     */
//    @PutMapping("/{id}")
//    @RequiresSystemAdmin
//    public Result<Void> update(@PathVariable String id, @RequestBody @Validated(TenantDTO.Update.class) TenantDTO dto) {
//        dto.setId(id);
//        tenantService.update(dto);
//        return Result.success();
//    }
//
//    /**
//     * 删除租户
//     */
//    @DeleteMapping("/{id}")
//    @RequiresSystemAdmin
//    public Result<Void> delete(@PathVariable String id) {
//        tenantService.delete(id);
//        return Result.success();
//    }
//
//    /**
//     * 修改租户状态
//     */
//    @PutMapping("/{id}/status")
//    @RequiresSystemAdmin
//    public Result<Void> updateStatus(@PathVariable String id, @RequestParam Integer status) {
//        tenantService.updateStatus(id, status);
//        return Result.success();
//    }
//
//    /**
//     * 检查租户编码是否存在
//     */
//    @GetMapping("/check-code")
//    public Result<Boolean> checkCodeExists(@RequestParam String tenantCode,
//                                           @RequestParam(required = false) String excludeId) {
//        boolean exists = tenantService.checkCodeExists(tenantCode, excludeId);
//        return Result.success(exists);
//    }
//}
