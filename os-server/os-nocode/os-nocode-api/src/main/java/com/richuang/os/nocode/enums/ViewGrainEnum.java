package com.richuang.os.nocode.enums;

/** ROOT 每行一个主记录；DETAIL 每行一条内部明细，操作仍定位所属整单。 */
public enum ViewGrainEnum implements NocodeCodeEnum {
    ROOT("ROOT"),
    DETAIL("DETAIL");
    private final String code;

    ViewGrainEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static ViewGrainEnum fromCode(String code) {
        return NocodeCodeEnum.require(ViewGrainEnum.class, code);
    }
}
