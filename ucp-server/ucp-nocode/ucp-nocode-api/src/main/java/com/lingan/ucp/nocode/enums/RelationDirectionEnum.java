package com.lingan.ucp.nocode.enums;

/** 相对页面当前记录：入向关系来自关联对象，出向关系定义在当前对象。 */
public enum RelationDirectionEnum implements NocodeCodeEnum {
    INCOMING("INCOMING"),
    OUTGOING("OUTGOING");
    private final String code;

    RelationDirectionEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static RelationDirectionEnum fromCode(String code) {
        return NocodeCodeEnum.require(RelationDirectionEnum.class, code);
    }
}
