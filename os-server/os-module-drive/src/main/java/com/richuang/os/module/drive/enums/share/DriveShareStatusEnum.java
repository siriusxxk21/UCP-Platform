package com.richuang.os.module.drive.enums.share;

import cn.hutool.core.util.StrUtil;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 网盘分享状态 */
@Getter
@AllArgsConstructor
public enum DriveShareStatusEnum {
    ACTIVE("ACTIVE", "生效中"),
    REVOKED("REVOKED", "已撤销");

    private final String code;
    private final String name;

    public static boolean isActive(String code) {
        return StrUtil.equals(ACTIVE.code, code);
    }
}
