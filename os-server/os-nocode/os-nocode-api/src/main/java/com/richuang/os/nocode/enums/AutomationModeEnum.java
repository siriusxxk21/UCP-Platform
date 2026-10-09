package com.richuang.os.nocode.enums;

/** 事件赋值与持续维护使用不同的撤销语义；按日期自动执行不由数据变化触发，每天到日子时执行一次赋值。 */
public enum AutomationModeEnum implements NocodeCodeEnum {
    EVENT,
    MAINTAIN,
    DATE;

    /** 一次性赋值（事件赋值、按日期）：执行一次写入后不再跟随来源变化，多条规则可以写同一字段，后执行的覆盖。 */
    public boolean oneShot() {
        return this == EVENT || this == DATE;
    }

    public String getCode() {
        return name();
    }

    public static AutomationModeEnum fromCode(String code) {
        return NocodeCodeEnum.require(AutomationModeEnum.class, code);
    }
}
