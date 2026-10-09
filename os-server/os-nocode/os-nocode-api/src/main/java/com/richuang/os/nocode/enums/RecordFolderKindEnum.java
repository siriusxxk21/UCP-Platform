package com.richuang.os.nocode.enums;

import java.util.Set;

/** 记录文件夹来源的种类。编码显式固定，不依赖枚举名称或序号持久化。 */
public enum RecordFolderKindEnum implements NocodeCodeEnum {
    /** 指定网盘里的一个文件夹 */
    FOLDER("FOLDER"),
    /** 用关联记录的文件夹 */
    RELATION("RELATION");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(RecordFolderKindEnum.class);

    RecordFolderKindEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static RecordFolderKindEnum fromCode(String code) {
        return NocodeCodeEnum.require(RecordFolderKindEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
