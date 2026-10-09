package com.richuang.os.nocode.web;

import com.richuang.os.framework.common.biz.system.permission.PermissionCommonApi;
import com.richuang.os.framework.security.core.util.SecurityFrameworkUtils;

import jakarta.annotation.Resource;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

/** 沿用底座登录身份与角色权限，不建立另一套账号、会话或授权缓存。 */
@Component("nocodeAccess")
public class NocodeAccess {
    @Resource private PermissionCommonApi permissions;

    public boolean query() {
        Long id = SecurityFrameworkUtils.getLoginUserId();
        return id != null && permissions.hasAnyPermissions(id, "nocode:object:query");
    }

    /** 任务菜单授权不替代实例参与人及业务数据授权。 */
    public boolean taskQuery() {
        return has("nocode:task:query");
    }

    public boolean taskCreate() {
        return taskQuery() && has("nocode:task:create");
    }

    public boolean taskTemplate() {
        return taskQuery() && has("nocode:task:template");
    }

    public boolean create() {
        return query() && permissions.hasAnyPermissions(actor(), "nocode:object:create");
    }

    public boolean update() {
        return query() && permissions.hasAnyPermissions(actor(), "nocode:object:update");
    }

    /** 数据中心沿用底座权限码；细分操作不能仅凭拥有菜单权限调用。 */
    public boolean has(String permission) {
        Long id = SecurityFrameworkUtils.getLoginUserId();
        return id != null && permissions.hasAnyPermissions(id, permission);
    }

    public boolean publish() {
        return query() && has("nocode:object:publish");
    }

    public boolean manage() {
        return query() && has("nocode:object:manage");
    }

    public boolean adopt() {
        return create() && has("nocode:object:adopt") && has("nocode:table:query");
    }

    public boolean importDesign() {
        return create() && has("nocode:object:import");
    }

    public boolean table() {
        return has("nocode:table:query");
    }

    public boolean preview() {
        return table() && has("nocode:table:preview");
    }

    /** 审计操作者只能来自底座受信会话，不能从请求体读取。 */
    public long actor() {
        Long id = SecurityFrameworkUtils.getLoginUserId();
        if (id == null) throw new AccessDeniedException("请先登录");
        return id;
    }
}
