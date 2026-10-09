package com.richuang.os.nocode.application.service.resource;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import cn.hutool.crypto.digest.DigestUtil;

import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.enums.CommonStatusEnum;
import com.richuang.os.module.system.api.permission.MenuApi;
import com.richuang.os.module.system.enums.permission.MenuTypeEnum;
import com.richuang.os.nocode.api.ApplicationCenter;
import com.richuang.os.nocode.api.ApplicationDashboards;
import com.richuang.os.nocode.api.ApplicationUi;
import com.richuang.os.nocode.enums.ApplicationResourceKindEnum;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** 页面导航的草稿规则与发布投影；只接管带专属身份的菜单，角色授权始终由底座维护。 */
@Component
public class ApplicationNavigation {
    private static final int VERSION = 2;
    private static final String RUNTIME_COMPONENT = "nocode/application/runtime";
    @Resource private ApplicationResourceContext resources;
    @Resource private MenuApi menus;
    @Resource private PermissionCommonApi permissions;

    /** 保存即校验页面身份和唯一首页；底座目录是否仍有效在发布时再次检查。 */
    public void validate(Map<String, ApplicationCenter.Resource> graph) {
        Set<String> targets = new HashSet<>();
        boolean hasHome = false;
        for (ApplicationCenter.Resource resource : graph.values()) {
            if (!ApplicationResourceKindEnum.MENU.matches(resource.kind())) continue;
            ApplicationUi.Menu menu = resources.decode(resource.config(), ApplicationUi.Menu.class);
            if (menu.navigationVersion() == null) {
                if (menu.showInMenu() != null
                        || menu.platformParentId() != null
                        || menu.menuName() != null
                        || menu.icon() != null
                        || menu.sort() != null
                        || menu.defaultHome() != null) throw invalid("页面菜单设置缺少导航协议版本");
                continue;
            }
            if (menu.navigationVersion() != VERSION) throw invalid("不支持的页面导航协议版本");
            if (menu.showInMenu() == null) throw invalid("请设置页面是否显示在菜单");
            if (!targets.add(menu.targetId())) throw invalid("同一页面只能配置一份菜单设置");
            ApplicationCenter.Resource target =
                    resources.resource(
                            graph,
                            menu.targetId(),
                            ApplicationResourceKindEnum.PAGE,
                            ApplicationResourceKindEnum.VIEW,
                            ApplicationResourceKindEnum.REPORT_DASHBOARD);
            if (Boolean.TRUE.equals(menu.defaultHome())) {
                if (hasHome) throw invalid("同一应用只能设置一个首页");
                hasHome = true;
            }
            if (Boolean.TRUE.equals(menu.showInMenu()) || Boolean.TRUE.equals(menu.defaultHome())) {
                requireIndependent(target);
            }
            if (Boolean.TRUE.equals(menu.showInMenu())) {
                parentId(menu.platformParentId());
                String name = name(menu, target);
                if (name.length() > 50) throw invalid("菜单名称长度不能超过 50 个字符");
            }
            if (menu.menuName() != null && menu.menuName().length() > 50)
                throw invalid("菜单名称长度不能超过 50 个字符");
            if (menu.icon() != null && menu.icon().length() > 100)
                throw invalid("菜单图标长度不能超过 100 个字符");
            if (menu.sort() != null && (menu.sort() < 0 || menu.sort() > 99999))
                throw invalid("菜单排序须为 0 至 99999 的整数");
        }
    }

    /** 页面导航不携带记录上下文，避免平台入口打开无法渲染的详情页。 */
    public void requireIndependent(ApplicationCenter.Resource target) {
        if (ApplicationResourceKindEnum.PAGE.matches(target.kind())
                && resources.decode(target.config(), ApplicationUi.Page.class).contextObjectId()
                        != null) throw invalid("页面菜单及导航跳转仅支持不依赖当前记录的页面");
        if (ApplicationResourceKindEnum.REPORT_DASHBOARD.matches(target.kind())
                && resources
                                .decode(target.config(), ApplicationDashboards.Config.class)
                                .contextObjectId()
                        != null) throw invalid("页面菜单及导航跳转不能打开依赖当前记录的仪表板");
    }

    /**
     * 必须参与发布事务。恢复发布同样调用此方法；隐藏保留入口身份和角色授权，仅配置真正删除时清理入口。 按实际差异检查底座写权限，因此只发布页面内容或自动跟随对象版本不要求菜单管理权限。
     */
    public void synchronize(
            String applicationId, ApplicationCenter.Definition definition, long actor) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("页面菜单同步必须处于应用发布事务中");
        String prefix = "NocodePage_" + applicationId + "_";
        String pathPrefix = "/nocode-app/runtime?id=" + applicationId + "&page=";
        List<MenuApi.Menu> all = menus.list();
        Map<Long, MenuApi.Menu> directory = new HashMap<>();
        Map<String, MenuApi.Menu> owned = new LinkedHashMap<>();
        for (MenuApi.Menu menu : all) {
            directory.put(menu.id(), menu);
            if (menu.componentName() != null
                    && menu.componentName().startsWith(prefix)
                    && RUNTIME_COMPONENT.equals(menu.component())
                    && menu.path() != null
                    && menu.path().startsWith(pathPrefix)) {
                if (owned.put(menu.componentName(), menu) != null)
                    throw invalid("应用页面菜单身份重复，请检查系统菜单配置");
            }
        }
        Map<String, ApplicationCenter.Resource> graph = new HashMap<>();
        definition.resources().forEach(resource -> graph.put(resource.id(), resource));
        List<MenuApi.Menu> create = new ArrayList<>();
        List<MenuApi.Menu> update = new ArrayList<>();
        for (ApplicationCenter.Resource resource : definition.resources()) {
            if (!ApplicationResourceKindEnum.MENU.matches(resource.kind())) continue;
            ApplicationUi.Menu menu = resources.decode(resource.config(), ApplicationUi.Menu.class);
            if (!Objects.equals(menu.navigationVersion(), VERSION)) continue;
            String identity = prefix + DigestUtil.sha256Hex(menu.targetId()).substring(0, 24);
            MenuApi.Menu existing = owned.remove(identity);
            if (!Boolean.TRUE.equals(menu.showInMenu())) {
                // 隐藏只改显示标记；允许草稿未保留目录，既有目录、路由和授权都继续保留。
                if (existing != null && !Boolean.FALSE.equals(existing.visible()))
                    update.add(hidden(existing));
                continue;
            }
            Long parentId = parentId(menu.platformParentId());
            MenuApi.Menu parent = directory.get(parentId);
            if (parent == null
                    || !Objects.equals(parent.parentId(), 0L)
                    || !Objects.equals(parent.type(), MenuTypeEnum.DIR.getType())
                    || !Objects.equals(parent.status(), CommonStatusEnum.ENABLE.getStatus())
                    || Boolean.FALSE.equals(parent.visible()))
                throw invalid("页面菜单所属一级目录不存在、已停用或不可见，请重新选择目录");
            MenuApi.Menu desired =
                    new MenuApi.Menu(
                            existing == null ? null : existing.id(),
                            name(menu, graph.get(menu.targetId())),
                            existing == null ? "" : existing.permission(),
                            MenuTypeEnum.MENU.getType(),
                            menu.sort() == null ? 0 : menu.sort(),
                            parentId,
                            pathPrefix
                                    + UriUtils.encodeQueryParam(
                                            menu.targetId(), StandardCharsets.UTF_8),
                            menu.icon() == null ? "" : menu.icon(),
                            RUNTIME_COMPONENT,
                            identity,
                            CommonStatusEnum.ENABLE.getStatus(),
                            true,
                            false,
                            true);
            if (existing == null) create.add(desired);
            else if (!desired.equals(existing)) update.add(desired);
        }
        if (!create.isEmpty()) require(actor, "create", "新增");
        if (!update.isEmpty()) require(actor, "update", "修改");
        if (!owned.isEmpty()) require(actor, "delete", "删除");
        // 删除先行可释放同目录下已取消的名称；任一底座校验失败会回滚整个发布及菜单变更。
        owned.values().forEach(menu -> menus.delete(menu.id()));
        update.forEach(menus::update);
        create.forEach(menus::create);
    }

    /** 复制底座已有属性，隐藏时不把未配置的草稿字段误写成默认值。 */
    private MenuApi.Menu hidden(MenuApi.Menu menu) {
        return new MenuApi.Menu(
                menu.id(),
                menu.name(),
                menu.permission(),
                menu.type(),
                menu.sort(),
                menu.parentId(),
                menu.path(),
                menu.icon(),
                menu.component(),
                menu.componentName(),
                menu.status(),
                false,
                menu.keepAlive(),
                menu.alwaysShow());
    }

    private void require(long actor, String action, String label) {
        if (actor <= 0 || !permissions.hasAnyPermissions(actor, "system:menu:" + action))
            throw invalid("本次发布需要" + label + "平台页面菜单，请由具有系统菜单" + label + "权限的人员发布");
    }

    private Long parentId(String id) {
        if (id == null || !id.matches("[1-9][0-9]*")) throw invalid("显示在菜单的页面必须选择平台一级目录");
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException exception) {
            throw invalid("平台一级目录编号无效");
        }
    }

    private String name(ApplicationUi.Menu menu, ApplicationCenter.Resource target) {
        return menu.menuName() == null || menu.menuName().isBlank()
                ? target.name().trim()
                : menu.menuName().trim();
    }
}
