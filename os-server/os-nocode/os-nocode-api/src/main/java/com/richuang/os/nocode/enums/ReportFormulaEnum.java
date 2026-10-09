package com.richuang.os.nocode.enums;

/** 仅允许已定义指标间的四则计算；SQL 运算符由枚举提供。 */
public enum ReportFormulaEnum implements NocodeCodeEnum {
    ADD("+"),
    SUBTRACT("-"),
    MULTIPLY("*"),
    DIVIDE("/");

    private final String sql;

    ReportFormulaEnum(String sql) {
        this.sql = sql;
    }

    public String getCode() {
        return name();
    }

    public String sql() {
        return sql;
    }

    public static ReportFormulaEnum fromCode(String value) {
        return NocodeCodeEnum.require(ReportFormulaEnum.class, value);
    }
}
