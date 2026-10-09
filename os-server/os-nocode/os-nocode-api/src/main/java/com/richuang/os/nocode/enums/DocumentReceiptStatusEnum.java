package com.richuang.os.nocode.enums;

/** 查不到收据仅表示尚无已提交成功结果，不等于正在执行的请求已失败。 */
public enum DocumentReceiptStatusEnum implements NocodeCodeEnum {
    SUCCEEDED("SUCCEEDED"),
    NOT_FOUND("NOT_FOUND");
    private final String code;

    DocumentReceiptStatusEnum(String code) {
        this.code = code;
    }

    @Override
    public String getCode() {
        return code;
    }
}
