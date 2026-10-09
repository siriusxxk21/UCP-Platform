package com.richuang.os.module.system.service.dict;

import com.richuang.os.framework.common.enums.CommonStatusEnum;
import com.richuang.os.module.system.controller.admin.permission.vo.menu.MenuSaveVO;
import com.richuang.os.module.system.dal.dataobject.permission.MenuDO;
import com.richuang.os.module.system.dal.mysql.dict.DictTypeMapper;
import com.richuang.os.module.system.enums.permission.MenuTypeEnum;
import com.richuang.os.module.system.service.permission.MenuService;

import jakarta.annotation.Resource;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 通过底座菜单服务幂等补齐字典入口；保留管理员已有配置，不自动扩大普通角色授权。 */
@Component
public class DictMenuRegistrar implements ApplicationRunner {

    @Resource private MenuService menuService;
    @Resource private DictTypeMapper dictTypeMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void run(ApplicationArguments args) {
        dictTypeMapper.lockDictionaryWrites();
        List<MenuDO> menus = menuService.getMenuList();
        MenuDO system =
                menus.stream()
                        .filter(
                                menu ->
                                        "/system".equals(menu.getPath())
                                                && MenuDO.ID_ROOT.equals(menu.getParentId()))
                        .findFirst()
                        .orElse(null);
        if (system == null) {
            return;
        }
        MenuDO existing =
                menus.stream()
                        .filter(menu -> "/system/dict".equals(menu.getPath()))
                        .findFirst()
                        .orElse(null);
        Long menuId =
                existing == null
                        ? menuService.createMenu(
                                menu(
                                        "数据字典",
                                        system.getId(),
                                        MenuTypeEnum.MENU.getType(),
                                        6,
                                        "/system/dict",
                                        "system/dict/index",
                                        "SystemDict",
                                        ""))
                        : existing.getId();
        String[] actions = {"query", "create", "update", "delete", "export"};
        String[] names = {"字典查询", "字典新增", "字典修改", "字典删除", "字典导出"};
        for (int i = 0; i < actions.length; i++) {
            String permission = "system:dict:" + actions[i];
            if (menus.stream().noneMatch(item -> permission.equals(item.getPermission()))) {
                menuService.createMenu(
                        menu(
                                names[i],
                                menuId,
                                MenuTypeEnum.BUTTON.getType(),
                                i,
                                "",
                                "",
                                "",
                                permission));
            }
        }
    }

    private MenuSaveVO menu(
            String name,
            Long parentId,
            Integer type,
            int sort,
            String path,
            String component,
            String componentName,
            String permission) {
        MenuSaveVO menu = new MenuSaveVO();
        menu.setName(name);
        menu.setParentId(parentId);
        menu.setType(type);
        menu.setSort(sort);
        menu.setPath(path);
        menu.setComponent(component);
        menu.setComponentName(componentName);
        menu.setPermission(permission);
        menu.setIcon("BookOutlined");
        menu.setStatus(CommonStatusEnum.ENABLE.getStatus());
        menu.setVisible(true);
        menu.setKeepAlive(false);
        menu.setAlwaysShow(true);
        return menu;
    }
}
