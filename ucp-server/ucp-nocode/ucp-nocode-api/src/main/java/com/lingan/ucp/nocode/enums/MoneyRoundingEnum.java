package com.lingan.ucp.nocode.enums;

import java.math.RoundingMode;
import java.util.Set;

/** 金额目标的取整方式；规则上为空时按 FLOOR（向下取整）。 编码显式固定，不依赖枚举名称或序号持久化。 */
public enum MoneyRoundingEnum implements NocodeCodeEnum {
    HALF_UP("HALF_UP", "四舍五入", RoundingMode.HALF_UP),
    FLOOR("FLOOR", "向下取整", RoundingMode.FLOOR),
    DOWN("DOWN", "去掉小数", RoundingMode.DOWN);

    private final String code;
    private final String label;
    private final RoundingMode mode;
    private static final Set<String> CODES = NocodeCodeEnum.codes(MoneyRoundingEnum.class);

    MoneyRoundingEnum(String code, String label, RoundingMode mode) {
        this.code = code;
        this.label = label;
        this.mode = mode;
    }

    @Override
    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    public RoundingMode mode() {
        return mode;
    }

    public static MoneyRoundingEnum fromCode(String code) {
        return NocodeCodeEnum.require(MoneyRoundingEnum.class, code);
    }

    /** 规则未配置取整方式时的缺省档；未知编码仍由 fromCode 拒绝，不回落为缺省。 */
    public static MoneyRoundingEnum of(String code) {
        return code == null ? FLOOR : fromCode(code);
    }

    public static boolean containsCode(String code) {
        return code != null && CODES.contains(code);
    }
}
