package com.lingan.ucp.module.drive.enums.entry;

import cn.hutool.core.util.StrUtil;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 网盘节点回收站状态
 *
 * <p>移入回收站只改变状态并保留内容记录，只有彻底删除才清理 infra 文件与存储介质。
 */
@Getter
@AllArgsConstructor
public enum DriveTrashStateEnum {
    NORMAL("NORMAL", "正常"),
    TRASHED("TRASHED", "回收站");

    private final String code;
    private final String name;

    public static boolean isTrashed(String code) {
        return StrUtil.equals(TRASHED.code, code);
    }
}
