package com.richuang.os.nocode.report.service.dashboard;

import static com.richuang.os.nocode.api.NocodeErrorCodes.invalid;

import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.common.enums.CommonStatusEnum;
import com.richuang.os.module.system.api.permission.MenuApi;
import com.richuang.os.module.system.enums.permission.MenuTypeEnum;
import com.richuang.os.nocode.api.ReportDashboards;

import jakarta.annotation.Resource;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;

/** 独立仪表板发布到平台二级菜单，复用系统菜单服务和角色绑定，不创建另一套导航。 */
@Component
public class ReportDashboardNavigation {
    private static final String COMPONENT = "nocode/report-center/dashboard-view";
    @Resource private MenuApi menus;
    @Resource private PermissionCommonApi permissions;

    /** 旧内容缺少导航时保持原 JSON；目录状态在发布事务内复核。 */
    public void validate(ReportDashboards.Navigation navigation) {
        if (navigation == null) return;
        if (navigation.showInMenu() == null) throw invalid("请设置仪表板是否显示在菜单");
        if (Boolean.TRUE.equals(navigation.showInMenu())) {
            parentId(navigation.platformParentId());
            if (navigation.menuName() == null || navigation.menuName().isBlank())
                throw invalid("请填写菜单名称");
        }
        if (navigation.menuName() != null && navigation.menuName().length() > 50)
            throw invalid("菜单名称长度不能超过 50 个字符");
        if (navigation.icon() != null && navigation.icon().length() > 100)
            throw invalid("菜单图标长度不能超过 100 个字符");
        if (navigation.sort() != null && (navigation.sort() < 0 || navigation.sort() > 9999))
            throw invalid("菜单排序须为 0 至 9999 的整数");
    }

    /** 同步与发布共用事务，失败整体回滚；关闭显示保留原菜单编号及角色绑定。 */
    public void synchronize(String id, ReportDashboards.Navigation navigation, long actor) {
        transaction();
        validate(navigation);
        List<MenuApi.Menu> all = menus.list();
        MenuApi.Menu existing = owned(id, all);
        if (navigation == null || !Boolean.TRUE.equals(navigation.showInMenu())) {
            if (existing != null && !Boolean.FALSE.equals(existing.visible())) {
                require(actor, "update", "修改");
                menus.update(
                        new MenuApi.Menu(
                                existing.id(),
                                existing.name(),
                                existing.permission(),
                                existing.type(),
                                existing.sort(),
                                existing.parentId(),
                                existing.path(),
                                existing.icon(),
                                existing.component(),
                                existing.componentName(),
                                existing.status(),
                                false,
                                existing.keepAlive(),
                                existing.alwaysShow()));
            }
            return;
        }
        Long parent = parentId(navigation.platformParentId());
        boolean valid =
                all.stream()
                        .anyMatch(
                                menu ->
                                        Objects.equals(menu.id(), parent)
                                                && Objects.equals(menu.parentId(), 0L)
                                                && Objects.equals(
                                                        menu.type(), MenuTypeEnum.DIR.getType())
                                                && Objects.equals(
                                                        menu.status(),
                                                        CommonStatusEnum.ENABLE.getStatus())
                                                && !Boolean.FALSE.equals(menu.visible()));
        if (!valid) throw invalid("仪表板菜单所属一级目录不存在、已停用或不可见，请重新选择目录");
        MenuApi.Menu desired =
                new MenuApi.Menu(
                        existing == null ? null : existing.id(),
                        navigation.menuName().trim(),
                        existing == null ? "" : existing.permission(),
                        MenuTypeEnum.MENU.getType(),
                        navigation.sort() == null ? 10 : navigation.sort(),
                        parent,
                        path(id),
                        navigation.icon() == null ? "" : navigation.icon(),
                        COMPONENT,
                        identity(id),
                        CommonStatusEnum.ENABLE.getStatus(),
                        true,
                        false,
                        true);
        if (existing == null) {
            require(actor, "create", "新增");
            menus.create(desired);
        } else if (!desired.equals(existing)) {
            require(actor, "update", "修改");
            menus.update(desired);
        }
    }

    /** 删除仅清理本看板托管入口，不接管手工菜单或其他应用入口。 */
    public void remove(String id, long actor) {
        transaction();
        MenuApi.Menu existing = owned(id, menus.list());
        if (existing == null) return;
        require(actor, "delete", "删除");
        menus.delete(existing.id());
    }

    private MenuApi.Menu owned(String id, List<MenuApi.Menu> all) {
        List<MenuApi.Menu> found =
                all.stream()
                        .filter(
                                menu ->
                                        identity(id).equals(menu.componentName())
                                                && COMPONENT.equals(menu.component())
                                                && path(id).equals(menu.path()))
                        .toList();
        if (found.size() > 1) throw invalid("仪表板菜单身份重复，请检查系统菜单配置");
        return found.isEmpty() ? null : found.getFirst();
    }

    private String identity(String id) {
        return "NocodeDashboard_" + id;
    }

    private String path(String id) {
        return "/nocode/report-center/dashboard-view?id=" + id;
    }

    private void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("仪表板菜单同步必须处于发布或删除事务中");
    }

    private Long parentId(String id) {
        if (id == null || !id.matches("[1-9][0-9]*")) throw invalid("请选择平台一级目录");
        try {
            return Long.valueOf(id);
        } catch (NumberFormatException exception) {
            throw invalid("平台一级目录编号无效");
        }
    }

    private void require(long actor, String action, String label) {
        if (actor <= 0 || !permissions.hasAnyPermissions(actor, "system:menu:" + action))
            throw invalid("本次操作需要" + label + "平台报表菜单，请由具有系统菜单" + label + "权限的人员执行");
    }
}
