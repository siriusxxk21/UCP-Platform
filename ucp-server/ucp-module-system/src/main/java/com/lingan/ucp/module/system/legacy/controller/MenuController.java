//package com.lingan.ucp.module.system.legacy.controller;
//
//import com.lingan.ucp.framework.common.pojo.Result;
//import com.lingan.ucp.module.system.legacy.dto.MenuQueryDTO;
//import com.lingan.ucp.module.system.legacy.entity.SysMenu;
//import com.lingan.ucp.module.system.legacy.service.MenuService;
//import com.lingan.ucp.module.system.legacy.vo.MenuVO;
//import lombok.RequiredArgsConstructor;
//import org.springframework.web.bind.annotation.*;
//
//import java.util.List;
//
//@RestController
//@RequestMapping("/api/system/menu")
//@RequiredArgsConstructor
//public class MenuController {
//
//    private final MenuService menuService;
//
//    @GetMapping("/list")
//    public Result<List<MenuVO>> list(MenuQueryDTO queryDTO) {
//        List<MenuVO> menus = menuService.treeList(queryDTO);
//        return Result.success(menus);
//    }
//
//    @GetMapping("/tree")
//    public Result<List<SysMenu>> tree() {
//        List<SysMenu> menus = menuService.tree();
//        return Result.success(menus);
//    }
//
//    @GetMapping("/{id}")
//    public Result<SysMenu> getById(@PathVariable String id) {
//        SysMenu menu = menuService.getById(id);
//        return Result.success(menu);
//    }
//
//    @PostMapping
//    public Result<Void> add(@RequestBody SysMenu menu) {
//        menuService.add(menu);
//        return Result.success();
//    }
//
//    @PutMapping
//    public Result<Void> update(@RequestBody SysMenu menu) {
//        menuService.update(menu);
//        return Result.success();
//    }
//
//    @DeleteMapping("/{id}")
//    public Result<Void> delete(@PathVariable String id) {
//        menuService.delete(id);
//        return Result.success();
//    }
//}
