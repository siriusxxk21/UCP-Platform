package com.richuang.os.module.drive.enums.mark;

import cn.hutool.core.util.StrUtil;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 网盘用户标记类型 */
@Getter
@AllArgsConstructor
public enum DriveMarkTypeEnum {
    FAVORITE("FAVORITE", "收藏"),
    RECENT("RECENT", "最近访问");

    private final String code;
    private final String name;

    public static boolean isFavorite(String code) {
        return StrUtil.equals(FAVORITE.code, code);
    }
}
