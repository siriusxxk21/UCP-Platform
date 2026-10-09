package com.lingan.ucp.module.drive.enums.space;

import cn.hutool.core.util.StrUtil;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 网盘空间类型
 *
 * <p>个人空间按用户唯一建立，团队空间按部门或责任人建立并依赖授权共享；
 * 业务空间承载无代码业务文件目录，内容由业务规则管理，空间不归属个人或部门。
 */
@Getter
@AllArgsConstructor
public enum DriveSpaceTypeEnum {
    PERSONAL("PERSONAL", "个人空间"),
    TEAM("TEAM", "团队空间"),
    BIZ("BIZ", "业务空间");

    private final String code;
    private final String name;

    public static DriveSpaceTypeEnum valueOfCode(String code) {
        for (DriveSpaceTypeEnum item : values()) {
            if (StrUtil.equals(item.code, code)) {
                return item;
            }
        }
        return null;
    }

    /** 业务空间内容只能由业务文件规则建立与调整，普通网盘操作入口全部拒绝 */
    public static boolean isBiz(String code) {
        return StrUtil.equals(BIZ.code, code);
    }
}
