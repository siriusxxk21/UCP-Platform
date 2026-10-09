package com.lingan.ucp.nocode.enums;

import java.util.Set;

/** 一条记录在某个文件夹来源下解析出来的状态。编码显式固定，不依赖枚举名称或序号持久化。 */
public enum RecordFolderStateEnum implements NocodeCodeEnum {
    /** 文件夹已存在 */
    READY("READY"),
    /** 还没建：第一次写入或后台建立时才建 */
    PENDING("PENDING"),
    /** 关联字段没有值 */
    RELATION_EMPTY("RELATION_EMPTY"),
    /** 文件夹被删、在回收站、空间停用或配置失效 */
    UNAVAILABLE("UNAVAILABLE");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(RecordFolderStateEnum.class);

    RecordFolderStateEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static RecordFolderStateEnum fromCode(String code) {
        return NocodeCodeEnum.require(RecordFolderStateEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
