package com.richuang.os.nocode.enums;

import java.util.Set;

/** 子文件夹命名模板里一段的种类。编码显式固定，不依赖枚举名称或序号持久化。 */
public enum RecordFolderNamePartEnum implements NocodeCodeEnum {
    /** 取字段的值 */
    FIELD("FIELD"),
    /** 固定文字 */
    TEXT("TEXT");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(RecordFolderNamePartEnum.class);

    RecordFolderNamePartEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static RecordFolderNamePartEnum fromCode(String code) {
        return NocodeCodeEnum.require(RecordFolderNamePartEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
