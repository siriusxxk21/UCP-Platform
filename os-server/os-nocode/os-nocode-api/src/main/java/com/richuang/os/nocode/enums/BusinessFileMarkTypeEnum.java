package com.richuang.os.nocode.enums;

/** FAVORITE 业务文件收藏；RECENT 业务文件最近访问。标记按用户独立，不参与授权判定。 */
public enum BusinessFileMarkTypeEnum implements NocodeCodeEnum {
    FAVORITE("FAVORITE"),
    RECENT("RECENT");
    private final String code;

    BusinessFileMarkTypeEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }

    public static BusinessFileMarkTypeEnum fromCode(String code) {
        return NocodeCodeEnum.require(BusinessFileMarkTypeEnum.class, code);
    }
}
