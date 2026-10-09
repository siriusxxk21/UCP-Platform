package com.lingan.ucp.nocode.enums;

/** 物理结构的管理边界，与业务数据写入能力分别校验。 */
public enum StructureModeEnum implements NocodeCodeEnum {
    RETAIN("RETAIN"),
    MANAGED("MANAGED");
    private final String code;

    StructureModeEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static StructureModeEnum fromCode(String code) {
        return NocodeCodeEnum.require(StructureModeEnum.class, code);
    }
}
