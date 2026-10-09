package com.lingan.ucp.module.system.api.permission;

import java.util.List;

/** 供业务模块复用底座菜单服务；不授予角色权限，也不绕过调用方的业务授权。 */
public interface MenuApi {
    /** 菜单快照，使用底座字段语义，不向业务模块暴露 Web VO。 */
    record Menu(
            Long id,
            String name,
            String permission,
            Integer type,
            Integer sort,
            Long parentId,
            String path,
            String icon,
            String component,
            String componentName,
            Integer status,
            Boolean visible,
            Boolean keepAlive,
            Boolean alwaysShow) {}

    /** 获取当前菜单，由调用方筛选本业务拥有的入口。 */
    List<Menu> list();

    /** 创建菜单，沿用底座重名、父节点及缓存校验。 */
    Long create(Menu menu);

    /** 更新菜单，保留既有菜单 ID 和角色授权关系。 */
    void update(Menu menu);

    /** 删除菜单及既有角色关联，沿用底座删除保护。 */
    void delete(Long id);
}
