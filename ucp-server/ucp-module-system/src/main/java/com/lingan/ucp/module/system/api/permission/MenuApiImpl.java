package com.lingan.ucp.module.system.api.permission;

import com.lingan.ucp.module.system.controller.admin.permission.vo.menu.MenuSaveVO;
import com.lingan.ucp.module.system.service.permission.MenuService;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Service;

import java.util.List;

/** 底座菜单接口适配，所有写入仍经过既有 MenuService。 */
@Service
public class MenuApiImpl implements MenuApi {
    @Resource private MenuService menuService;

    @Override
    public List<Menu> list() {
        return menuService.getMenuList().stream()
                .map(
                        menu ->
                                new Menu(
                                        menu.getId(),
                                        menu.getName(),
                                        menu.getPermission(),
                                        menu.getType(),
                                        menu.getSort(),
                                        menu.getParentId(),
                                        menu.getPath(),
                                        menu.getIcon(),
                                        menu.getComponent(),
                                        menu.getComponentName(),
                                        menu.getStatus(),
                                        menu.getVisible(),
                                        menu.getKeepAlive(),
                                        menu.getAlwaysShow()))
                .toList();
    }

    @Override
    public Long create(Menu menu) {
        return menuService.createMenu(request(menu));
    }

    @Override
    public void update(Menu menu) {
        menuService.updateMenu(request(menu));
    }

    @Override
    public void delete(Long id) {
        menuService.deleteMenu(id);
    }

    private MenuSaveVO request(Menu menu) {
        MenuSaveVO request = new MenuSaveVO();
        request.setId(menu.id());
        request.setName(menu.name());
        request.setPermission(menu.permission());
        request.setType(menu.type());
        request.setSort(menu.sort());
        request.setParentId(menu.parentId());
        request.setPath(menu.path());
        request.setIcon(menu.icon());
        request.setComponent(menu.component());
        request.setComponentName(menu.componentName());
        request.setStatus(menu.status());
        request.setVisible(menu.visible());
        request.setKeepAlive(menu.keepAlive());
        request.setAlwaysShow(menu.alwaysShow());
        return request;
    }
}
