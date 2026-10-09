package com.lingan.ucp.module.system.legacy.service;

import com.lingan.ucp.module.system.legacy.dto.MenuQueryDTO;
import com.lingan.ucp.module.system.legacy.entity.SysMenu;
import com.lingan.ucp.module.system.legacy.vo.MenuVO;

import java.util.List;

public interface MenuService {
    List<SysMenu> list(MenuQueryDTO queryDTO);

    List<MenuVO> treeList(MenuQueryDTO queryDTO);

    List<SysMenu> tree();

    SysMenu getById(String id);

    void add(SysMenu menu);

    void update(SysMenu menu);

    void delete(String id);

    boolean hasChildren(String menuId);
}
