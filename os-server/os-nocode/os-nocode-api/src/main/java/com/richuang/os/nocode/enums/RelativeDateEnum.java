package com.richuang.os.nocode.enums;

import java.util.Set;

/**
 * 条件里的相对日期（2026-10-03）：条件值存 {"relative": 编码[, "n": 天数]}，每次执行时按当时的日期换算成区间，不存换算后的日期。
 *
 * <p>编码显式固定、即持久化值，不依赖枚举名称或序号。区间口径（左闭右开）见 RelativeDates#range；周从周一开始。
 */
public enum RelativeDateEnum implements NocodeCodeEnum {
    TODAY("TODAY", "今天", false),
    YESTERDAY("YESTERDAY", "昨天", false),
    TOMORROW("TOMORROW", "明天", false),
    THIS_WEEK("THIS_WEEK", "本周", false),
    LAST_WEEK("LAST_WEEK", "上周", false),
    NEXT_WEEK("NEXT_WEEK", "下周", false),
    THIS_MONTH("THIS_MONTH", "本月", false),
    LAST_MONTH("LAST_MONTH", "上月", false),
    NEXT_MONTH("NEXT_MONTH", "下月", false),
    THIS_YEAR("THIS_YEAR", "本年", false),
    LAST_YEAR("LAST_YEAR", "去年", false),
    /** 过去 N 天：含今天，共 N 天。 */
    PAST_N_DAYS("PAST_N_DAYS", "过去 N 天", true),
    /** 未来 N 天：含今天，共 N 天。 */
    NEXT_N_DAYS("NEXT_N_DAYS", "未来 N 天", true);

    private static final Set<String> CODES = NocodeCodeEnum.codes(RelativeDateEnum.class);

    private final String code;
    private final String label;
    private final boolean counted;

    RelativeDateEnum(String code, String label, boolean counted) {
        this.code = code;
        this.label = label;
        this.counted = counted;
    }

    @Override
    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 是否需要天数 n（过去 / 未来 N 天）。 */
    public boolean counted() {
        return counted;
    }

    public static RelativeDateEnum fromCode(String code) {
        return NocodeCodeEnum.require(RelativeDateEnum.class, code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
