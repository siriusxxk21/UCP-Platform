package com.lingan.ucp.nocode.enums;

/** LIVE 读取时按当前源数据计算，ON_SAVE 在本记录事务内保存快照。 */
public enum CalculationUpdateEnum implements NocodeCodeEnum {
    LIVE("LIVE"),
    ON_SAVE("ON_SAVE");
    private final String code;

    CalculationUpdateEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static CalculationUpdateEnum fromCode(String code) {
        return NocodeCodeEnum.require(CalculationUpdateEnum.class, code);
    }
}
