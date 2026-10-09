package com.richuang.os.module.system.legacy.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.richuang.os.module.system.legacy.dto.MenuQueryDTO;
import com.richuang.os.module.system.legacy.entity.SysMenu;
import com.richuang.os.module.system.legacy.mapper.SysMenuMapper;
import com.richuang.os.module.system.legacy.service.MenuService;
import com.richuang.os.module.system.legacy.vo.MenuVO;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// @Service -- 已废弃（v2.0 统一认证迁移）
@RequiredArgsConstructor
public class MenuServiceImpl implements MenuService {

    private final SysMenuMapper menuMapper;

    @Override
    public List<SysMenu> list(MenuQueryDTO queryDTO) {
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(queryDTO.getName())) {
            wrapper.like(SysMenu::getName, queryDTO.getName());
        }
        if (queryDTO.getStatus() != null) {
            wrapper.eq(SysMenu::getStatus, queryDTO.getStatus());
        }
        wrapper.eq(SysMenu::getDeleted, 0);
        wrapper.orderByAsc(SysMenu::getSort);

        return menuMapper.selectList(wrapper);
    }

    @Override
    public List<MenuVO> treeList(MenuQueryDTO queryDTO) {
        // 如果有查询条件，先查询所有菜单再本地过滤，以保留完整的树形结构
        if (StringUtils.hasText(queryDTO.getName()) || queryDTO.getStatus() != null) {
            // 查询所有未删除的菜单
            LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(SysMenu::getDeleted, 0);
            wrapper.orderByAsc(SysMenu::getSort);
            List<SysMenu> allMenus = menuMapper.selectList(wrapper);

            // 构建完整树
            List<MenuVO> fullTree = buildMenuTree(allMenus);

            // 本地过滤
            return filterMenuTree(fullTree, queryDTO);
        }

        // 无查询条件时直接返回所有菜单
        List<SysMenu> menus = list(queryDTO);
        return buildMenuTree(menus);
    }

    /**
     * 过滤菜单树，保留匹配的节点及其完整路径
     */
    private List<MenuVO> filterMenuTree(List<MenuVO> menus, MenuQueryDTO queryDTO) {
        List<MenuVO> result = new ArrayList<>();

        for (MenuVO menu : menus) {
            boolean matchName = !StringUtils.hasText(queryDTO.getName()) ||
                    menu.getName().contains(queryDTO.getName());
            boolean matchStatus = queryDTO.getStatus() == null ||
                    menu.getStatus().equals(queryDTO.getStatus());

            // 递归过滤子节点
            List<MenuVO> filteredChildren = new ArrayList<>();
            if (menu.getChildren() != null && !menu.getChildren().isEmpty()) {
                filteredChildren = filterMenuTree(menu.getChildren(), queryDTO);
            }

            // 如果当前节点匹配或有匹配的子节点，则保留
            if ((matchName && matchStatus) || !filteredChildren.isEmpty()) {
                MenuVO copy = new MenuVO();
                BeanUtils.copyProperties(menu, copy);
                // 确保 icon 字段被复制
                copy.setIcon(menu.getIcon());
                copy.setChildren(filteredChildren);
                result.add(copy);
            }
        }

        return result;
    }

    @Override
    public List<SysMenu> tree() {
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMenu::getDeleted, 0);
        wrapper.eq(SysMenu::getStatus, 1);
        wrapper.orderByAsc(SysMenu::getSort);
        return menuMapper.selectList(wrapper);
    }

    @Override
    public SysMenu getById(String id) {
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMenu::getId, id);
        wrapper.eq(SysMenu::getDeleted, 0);
        return menuMapper.selectOne(wrapper);
    }

    @Override
    public void add(SysMenu menu) {
        // 检查是否会导致循环引用
        if (menu.getParentId() != null && menu.getParentId() != "0") {
            checkCircularReference(menu.getParentId(), menu.getId());
        }
        menuMapper.insert(menu);
    }

    @Override
    public void update(SysMenu menu) {
        // 检查是否会导致循环引用
        if (menu.getParentId() != null && menu.getParentId() != "0" && menu.getId() != null) {
            checkCircularReference(menu.getParentId(), menu.getId());
        }
        menuMapper.updateById(menu);
    }

    @Override
    @Transactional
    public void delete(String id) {
        // 递归删除所有子菜单
        deleteChildren(id);
    }

    @Override
    public boolean hasChildren(String menuId) {
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMenu::getParentId, menuId);
        wrapper.eq(SysMenu::getDeleted, 0);
        return menuMapper.selectCount(wrapper) > 0;
    }

    /**
     * 递归删除子菜单
     */
    private void deleteChildren(String parentId) {
        LambdaQueryWrapper<SysMenu> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMenu::getParentId, parentId);
        wrapper.eq(SysMenu::getDeleted, 0);
        List<SysMenu> children = menuMapper.selectList(wrapper);

        // 先递归删除子菜单
        for (SysMenu child : children) {
            deleteChildren(child.getId());
        }

        // 删除当前菜单 - 使用 deleteById 让 @TableLogic 自动处理逻辑删除
        menuMapper.deleteById(parentId);
    }

    /**
     * 检查是否会导致循环引用
     */
    private void checkCircularReference(String parentId, String currentId) {
        if (parentId.equals(currentId)) {
            throw new IllegalArgumentException("不能将菜单设置为自己的父菜单");
        }
        SysMenu parent = menuMapper.selectById(parentId);
        if (parent != null && parent.getParentId() != null && parent.getParentId() != "0") {
            checkCircularReference(parent.getParentId(), currentId);
        }
    }

    /**
     * 构建菜单树
     */
    private List<MenuVO> buildMenuTree(List<SysMenu> menus) {
        Map<String, MenuVO> menuMap = menus.stream()
                .collect(Collectors.toMap(
                        SysMenu::getId,
                        menu -> {
                            MenuVO vo = new MenuVO();
                            BeanUtils.copyProperties(menu, vo);
                            // 确保 icon 字段被复制
                            vo.setIcon(menu.getIcon());
                            vo.setChildren(new ArrayList<>());
                            return vo;
                        }
                ));

        List<MenuVO> result = new ArrayList<>();

        for (SysMenu menu : menus) {
            MenuVO vo = menuMap.get(menu.getId());
            if (menu.getParentId() == null || menu.getParentId().equals("0")) {
                result.add(vo);
            } else {
                MenuVO parent = menuMap.get(menu.getParentId());
                if (parent != null) {
                    parent.getChildren().add(vo);
                }
            }
        }

        // 按排序号排序
        sortMenuTree(result);

        return result;
    }

    /**
     * 递归排序菜单树
     */
    private void sortMenuTree(List<MenuVO> menus) {
        menus.sort((a, b) -> {
            Integer sortA = a.getSort() != null ? a.getSort() : 0;
            Integer sortB = b.getSort() != null ? b.getSort() : 0;
            return sortA.compareTo(sortB);
        });

        for (MenuVO menu : menus) {
            if (menu.getChildren() != null && !menu.getChildren().isEmpty()) {
                sortMenuTree(menu.getChildren());
            }
        }
    }
}
