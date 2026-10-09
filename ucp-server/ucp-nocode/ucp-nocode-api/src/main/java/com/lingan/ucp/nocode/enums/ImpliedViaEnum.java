package com.lingan.ucp.nocode.enums;

/** 应用没有引用、却因关联而可只读的对象，是经由哪一种配置连过来的。 */
public enum ImpliedViaEnum implements NocodeCodeEnum {
    /** 关系的目标对象（含内部明细上的关系、多对多关系）。 */
    RELATION("RELATION"),
    /** 数据联动的来源对象。 */
    LINKAGE("LINKAGE"),
    /** 引用筛选的目标对象。 */
    REFERENCE_FILTER("REFERENCE_FILTER"),
    /** 挑取值的来源对象。 */
    PICK("PICK"),
    /** 计算字段（查找取值、累计、统计）的来源对象。 */
    CALCULATION("CALCULATION");

    private final String code;

    ImpliedViaEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static ImpliedViaEnum fromCode(String code) {
        return NocodeCodeEnum.require(ImpliedViaEnum.class, code);
    }
}
