package com.richuang.os.nocode.enums;

import java.util.Set;

/** 记录文件夹的放法。编码显式固定，不依赖枚举名称或序号持久化。 */
public enum RecordFolderPlacementEnum implements NocodeCodeEnum {
    /** 直接用那个文件夹 */
    DIRECT("DIRECT"),
    /** 在其中为本记录建一个子文件夹 */
    RECORD_SUBFOLDER("RECORD_SUBFOLDER");

    private final String code;
    private static final Set<String> CODES = NocodeCodeEnum.codes(RecordFolderPlacementEnum.class);

    RecordFolderPlacementEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static RecordFolderPlacementEnum fromCode(String code) {
        return NocodeCodeEnum.require(RecordFolderPlacementEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
