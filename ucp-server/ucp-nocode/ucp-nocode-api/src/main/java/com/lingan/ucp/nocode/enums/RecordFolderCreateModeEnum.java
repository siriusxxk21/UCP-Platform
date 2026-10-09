package com.lingan.ucp.nocode.enums;

import java.util.Set;

/** 记录子文件夹的建立时机。编码显式固定，不依赖枚举名称或序号持久化。 */
public enum RecordFolderCreateModeEnum implements NocodeCodeEnum {
    /** 第一次放东西时建 */
    ON_FIRST_WRITE("ON_FIRST_WRITE"),
    /** 记录保存提交后由后台建立 */
    ON_SAVE("ON_SAVE");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(RecordFolderCreateModeEnum.class);

    RecordFolderCreateModeEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static RecordFolderCreateModeEnum fromCode(String code) {
        return NocodeCodeEnum.require(RecordFolderCreateModeEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
