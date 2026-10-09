package com.richuang.os.nocode.enums;

/** 系统替配置取数（计算、数据联动、引用筛选、挑取值）被拦下的原因；按判定顺序排列。 */
public enum SystemReadDenialEnum implements NocodeCodeEnum {
    /** 应用对来源对象的有效上限为空：没有授权、授权已撤销，且不是因关联而可读的对象。 */
    NOT_GRANTED("NOT_GRANTED"),
    /** 记录范围不是全部记录。 */
    SCOPE("SCOPE"),
    /** 查看设了记录条件。 */
    CONDITION("CONDITION"),
    /** 可查看字段不含所需字段。 */
    FIELD("FIELD");

    private final String code;

    SystemReadDenialEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static SystemReadDenialEnum fromCode(String code) {
        return NocodeCodeEnum.require(SystemReadDenialEnum.class, code);
    }
}
