package com.richuang.os.module.drive.enums.permission;

import cn.hutool.core.util.StrUtil;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 网盘授权主体类型 */
@Getter
@AllArgsConstructor
public enum DriveSubjectTypeEnum {
    USER("USER", "用户"),
    DEPT("DEPT", "部门");

    private final String code;
    private final String name;

    public static DriveSubjectTypeEnum valueOfCode(String code) {
        for (DriveSubjectTypeEnum item : values()) {
            if (StrUtil.equals(item.code, code)) {
                return item;
            }
        }
        return null;
    }
}
