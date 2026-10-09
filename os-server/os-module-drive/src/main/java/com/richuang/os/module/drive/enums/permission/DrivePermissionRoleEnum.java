package com.richuang.os.module.drive.enums.permission;

import cn.hutool.core.util.StrUtil;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 网盘授权角色
 *
 * <p>角色按能力递增：查看即可下载与预览，编辑可增删改内容，管理可继续授权他人。 空间归属主体不受授权表约束，始终按 MANAGER 对待。
 */
@Getter
@AllArgsConstructor
public enum DrivePermissionRoleEnum {
    VIEWER("VIEWER", "可查看"),
    EDITOR("EDITOR", "可编辑"),
    MANAGER("MANAGER", "可管理");

    private final String code;
    private final String name;

    public static DrivePermissionRoleEnum valueOfCode(String code) {
        for (DrivePermissionRoleEnum item : values()) {
            if (StrUtil.equals(item.code, code)) {
                return item;
            }
        }
        return null;
    }

    /**
     * 判断当前角色是否达到所需角色的能力
     *
     * <p>角色能力按枚举顺序递增，仅比较序号；持久化仍使用编码，不依赖序号存储。
     */
    public boolean satisfies(DrivePermissionRoleEnum required) {
        return required == null || this.ordinal() >= required.ordinal();
    }

    /** 取能力更强的角色，用于合并多处授权 */
    public static DrivePermissionRoleEnum max(
            DrivePermissionRoleEnum first, DrivePermissionRoleEnum second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.ordinal() >= second.ordinal() ? first : second;
    }
}
