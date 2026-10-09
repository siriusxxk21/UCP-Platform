package com.lingan.ucp.module.drive.enums.entry;

import cn.hutool.core.util.StrUtil;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 网盘节点类型
 *
 * <p>目录节点只承载结构，文件节点通过 fileId 指向 infra 文件记录；两者都按节点做授权。
 */
@Getter
@AllArgsConstructor
public enum DriveEntryTypeEnum {
    FOLDER("FOLDER", "目录"),
    FILE("FILE", "文件");

    private final String code;
    private final String name;

    public static boolean isFolder(String code) {
        return StrUtil.equals(FOLDER.code, code);
    }

    public static boolean isFile(String code) {
        return StrUtil.equals(FILE.code, code);
    }
}
